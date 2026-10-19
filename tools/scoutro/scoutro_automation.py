"""Deterministic automation adapter around scoutro-discovery's existing engine.

GPL-2.0-or-later. Python alone writes the multi-profile candidate state.
The parent supplies a frozen config directory and an allowlisted JSON RPC peer;
no password, HTTP endpoint, command or arbitrary job path is accepted here.
"""
import copy
import hashlib
import json
import os
import re
import time
import urllib.parse


def emit(value):
    print(json.dumps(value, ensure_ascii=False, separators=(",", ":")), flush=True)


class ProtocolError(Exception):
    pass


class Rpc:
    def __init__(self, engine):
        self.engine = engine
        self.sequence = 0

    def call(self, action, params=None):
        self.sequence += 1
        emit({"type": "request", "id": self.sequence, "action": action, "params": params or {}})
        line = input()
        if len(line) > 1_000_000:
            raise ProtocolError("oversized bridge response")
        reply = json.loads(line)
        if reply.get("id") != self.sequence:
            raise ProtocolError("bridge response sequence mismatch")
        if "error" in reply:
            error = reply["error"]
            if error.get("uncertain"):
                raise UnknownStart(error.get("attempt_id", ""))
            exc = self.engine.ApiError(error.get("status", 503), error.get("code", "bridge_error"),
                                       error.get("message", "bridge request failed"))
            exc.details = error.get("details", {})
            raise exc
        return reply.get("result", {})

    def search(self, query, source="network", limit=20):
        return self.call("search", {"query": query, "source": source, "limit": limit})


class UnknownStart(Exception):
    pass


def held(entry):
    """A start whose outcome is unknown keeps its candidate out of every automatic selection: while it waits for
    reconciliation (submitted_unknown) and after an administrator deferred it (deferred_unknown)."""
    return (entry.get("automation_attempt") or {}).get("state") in ("submitted_unknown", "deferred_unknown")


PRECHECK_RESULTS = (("retry:dns", "dns"), ("retry:site_5xx", "site_5xx"), ("robots", "robots"), ("blocked", "blocked"))


def precheck_detail(raw):
    """A short reason code such as robots-disallow-all or site-5xx:503; free text is dropped."""
    parts = (raw or "").split(":")
    if not re.fullmatch(r"[A-Za-z0-9_.-]{1,40}", parts[0]):
        return None
    code = parts[0]
    if len(parts) > 1 and re.fullmatch(r"[A-Za-z0-9_.-]{1,23}", parts[1]):
        code += ":" + parts[1]
    return code


def precheck_report(preliminary):
    """Crawl report result of a precheck refusal, or None for statuses the report does not record."""
    status = preliminary.get("status", "")
    for prefix, result in PRECHECK_RESULTS:
        if status.startswith(prefix):
            raw = preliminary.get("error") or (status.split(":", 1)[1] if ":" in status else "")
            return result, precheck_detail(raw)
    return None


# processing.outcome_retry: recorded crawl results that make a crawled host a retry.
OUTCOME_RETRY = ("not_indexed", "not_reloaded")
OUTCOME_SUCCESS = ("indexed", "partial")
OUTCOME_WINDOW = 3 * 86400   # only crawls started in the last three days are looked up
OUTCOME_BATCH = 200          # hosts per outcomes request, the bridge's limit


def outcome_retry(job):
    processing = job["processing"]
    return bool(processing.get("outcome_retry")) and bool(processing.get("retry"))


def confirm(engine, entry, result, now, recrawl_days):
    """confirm_start, keeping the attempt count of an outcome retry so that its backoff keeps
    growing until it reaches the recrawl interval."""
    retry = entry.get("status") == "retry" and entry.get("error_class") in OUTCOME_RETRY
    replay = entry.get("status") == "crawled" and entry.get("crawl_id") == result.get("id")  # same confirmation again
    attempts = entry.get("attempts", 0) if retry or replay else 0
    engine.confirm_start(entry, result, now, recrawl_days)
    entry.pop("deferred", None)  # accepted: an earlier refusal no longer applies
    if attempts:
        entry["attempts"] = attempts


def apply_outcomes(engine, state, rpc, job, now):
    """processing.outcome_retry: a recorded unsuccessful result (OUTCOME_RETRY) of the crawl this
    state started makes the host a retry with the existing backoff; a successful one resets the
    attempt count. Only existing fields of the retry path change; results are never copied.
    Returns the number of hosts that became a retry."""
    if not outcome_retry(job):
        return 0
    profile, days = job["profile"], job["processing"]["recrawl"]["days"]
    pending = []
    for domain, root in state.data["domains"].items():
        entry = (root.get("profiles") or {}).get(profile)
        if not entry or entry.get("status") != "crawled" or not entry.get("crawl_id"):
            continue
        if now - entry.get("last_crawl", 0) > OUTCOME_WINDOW:
            continue
        host = (urllib.parse.urlsplit(entry.get("candidate_url") or "https://" + domain + "/").hostname or "").lower()
        if host:
            pending.append((domain, host, entry["crawl_id"]))
    retries = 0
    for i in range(0, len(pending), OUTCOME_BATCH):
        chunk = pending[i:i + OUTCOME_BATCH]
        try:
            outcomes = rpc.call("outcomes", {"hosts": sorted({host for _, host, _ in chunk})}).get("outcomes") or {}
        except engine.ApiError:
            break  # best effort, like the precheck report: the selection runs without results
        updated = 0
        for domain, host, crawl_id in chunk:
            found = outcomes.get(host) or {}
            if found.get("crawl_id") != crawl_id:
                continue  # not recorded yet, or the table holds another crawl of this host
            entry = copy.deepcopy(state.profile_entry(domain, profile))
            if found.get("outcome") in OUTCOME_RETRY:
                engine.mark_retry(entry, found["outcome"], "crawl:" + found["outcome"], now, days)
                retries += 1
            elif found.get("outcome") in OUTCOME_SUCCESS and entry.get("attempts"):
                entry["attempts"] = 0
            else:
                continue
            state.put_profile_entry(domain, profile, entry, now)
            updated += 1
        if updated:
            state.save()
    return retries


def _slug(value, size):
    return re.sub(r"[^a-z0-9._/-]", "-", str(value or "unknown").lower())[:size] or "unknown"


def autocorrect_signals(engine, root, rules, osm_cfg, profile):
    """The stored Discovery results about one domain as signals for the correction automation (WP-H,
    docs/SCOUTRO_COLLECTION_AUTOCORRECT.md). Read from the state only: no model call, no fetch.

    - llm: each profile's classification by the model (website origin): PASS for, FAIL against its collection; UNSURE,
      a heuristic or a failed reading is no signal.
    - origin (osm:<region>): the OSM object a profile's rule matched speaks for that profile's collection; it speaks
      against the current profile only when it matched another profile by its tags, those tags cover a key of the
      current profile's OSM rule, and that rule does not match them (a text match of the name is no verdict on others).
    """
    profiles = (rules or {}).get("profiles") or {}
    out = []
    for name, rec in sorted((root.get("classifications") or {}).items()):
        if name not in profiles or not rec:
            continue
        cl = rec.get("classifier") or {}
        verdict = rec.get("verdict")
        if cl.get("backend") != "llm" or cl.get("error") or verdict not in ("PASS", "FAIL"):
            continue
        reasons = rec.get("reasons") or []
        why = (reasons[0].get("code", "") + ": " + reasons[0].get("text", "")) if reasons else verdict
        out.append({"family": "llm", "verdict": "for" if verdict == "PASS" else "against",
                    "collection": profiles[name]["collection"], "kind": "discovery_classification",
                    "evidence": ("Discovery %s %s (%.2f): %s" % (name, verdict, float(rec.get("confidence", 0)), why))[:500],
                    "revision": str(rec.get("classified_at", ""))[:80], "criteria": str(cl.get("criteria_version", ""))[:40]})
    osm_rules = (osm_cfg or {}).get("profiles") or {}
    current_rule = osm_rules.get(profile)
    entries = root.get("profiles") or {}
    current_matched = bool((entries.get(profile) or {}).get("osm_match"))
    for name, pe in sorted(entries.items()):
        match = (pe or {}).get("osm_match")
        if not match or name not in profiles:
            continue
        origin = "osm:" + _slug(match.get("source_region"), 100)
        tags = {k: v for k, v in (match.get("tags") or {}).items() if k != "name"}
        described = ", ".join("%s=%s" % (k, v) for k, v in sorted(tags.items())) or "name"
        out.append({"family": "origin", "verdict": "for", "collection": profiles[name]["collection"], "origin": origin,
                    "kind": "osm_rule", "evidence": ("OSM %s of %s matched: %s" % (match.get("rule", ""), name, described))[:500]})
        if name == profile or current_matched or not current_rule or not str(match.get("rule", "")).startswith("tags["):
            continue
        keys = {k for r in current_rule.get("tags", []) for k in r if not k.startswith("_") and k not in engine.OSM_TEXT_KEYS}
        if tags and keys & set(tags) and not engine.osm_matches(dict(tags), current_rule):
            out.append({"family": "origin", "verdict": "against", "collection": profiles[profile]["collection"], "origin": origin,
                        "kind": "osm_rule_mismatch",
                        "evidence": ("OSM tags %s match %s, not the OSM rule of %s" % (described, name, profile))[:500]})
    return out


def submit_autocorrect(engine, state, rpc, init, config, now):
    """WP-H: the batch reports the stored signals of its crawled domains to the correction automation, which decides; only
    while the automation is on (init.autocorrect), at most init.autocorrect_batch domains, each again only when its
    signals changed. Best effort: an error stops the reports, never the batch."""
    if not init.get("autocorrect"):
        return None
    profile = init["job"]["profile"]
    rules = engine.load_rules(config)
    path = os.path.join(config, "osm_profiles.json")
    osm_cfg = engine.load_osm_config(path) if os.path.isfile(path) else {}
    profiles = rules.get("profiles") or {}
    limit = int(init.get("autocorrect_batch") or 10)
    report = {"submitted": 0, "actions": {}}
    for domain in sorted(state.data["domains"]):
        if report["submitted"] >= limit:
            break
        root = state.data["domains"][domain]
        pentry = (root.get("profiles") or {}).get(profile) or {}
        if pentry.get("status") != "crawled":
            continue
        signals = autocorrect_signals(engine, root, rules, osm_cfg, profile)
        if not signals:
            continue
        memberships = sorted({profiles[p]["collection"] for p, pe in (root.get("profiles") or {}).items()
                              if p in profiles and (pe or {}).get("status") == "crawled"})
        fingerprint = json.dumps([init["collection"], memberships, signals], sort_keys=True)
        fingerprint = str(len(fingerprint)) + ":" + hashlib.sha256(fingerprint.encode("utf-8")).hexdigest()[:24]
        if pentry.get("autocorrect_reported") == fingerprint:
            continue
        try:
            answer = rpc.call("autocorrect", {"domain": domain, "memberships": memberships, "signals": signals})
        except engine.ApiError as exc:
            report["error"] = exc.code
            break
        action = answer.get("skipped") or answer.get("action") or "unknown"
        report["actions"][action] = report["actions"].get(action, 0) + 1
        report["submitted"] += 1
        entry = copy.deepcopy(state.profile_entry(domain, profile))
        entry["autocorrect_reported"] = fingerprint
        state.put_profile_entry(domain, profile, entry, now)
        state.save()
        if answer.get("skipped") == "off":
            break  # switched off during the batch
    return report


def origins(entry):
    result = list(entry.get("origins") or [])
    if entry.get("source") and entry.get("source_region"):
        item = {"source": entry["source"], "source_region": entry["source_region"]}
        if item not in result:
            result.append(item)
    return result


def in_scope(entry, job, region_catalog):
    if job.get("candidate_scope", "source_regions") == "profile_backlog":
        return True
    for origin in origins(entry):
        source = origin.get("source")
        spec = job["sources"].get(source)
        if not spec:
            continue
        regions = region_catalog.get(source, []) if spec.get("mode") == "all" else spec.get("regions", [])
        if origin.get("source_region") in regions:
            return True
    return False


# refusals that defer a pair (days; 0: the job's recrawl interval). A decision is re-checked after the interval: if it
# was revoked meanwhile the pair is crawled again, otherwise it is deferred again without a crawl.
DEFERRING = {"collection_decided": 0, "collection_fallback_only": 0, "collection_conflict": 1}


def select_backlog(engine, state, job, region_catalog, now):
    """Reuse the original eligibility/order; apply only job scope and processing mask."""
    processing = job["processing"]
    days = processing["recrawl"]["days"]
    candidates = []
    for domain, root in state.data["domains"].items():
        entry = (root.get("profiles") or {}).get(job["profile"])
        if entry is None or held(entry):
            continue
        if not in_scope(entry, job, region_catalog):
            continue
        kind = engine.selection_kind(entry, now, days)
        allowed = ((kind in (engine.KIND_FRESH, engine.KIND_LEGACY_AUTH) and processing["fresh"])
                   or (kind == engine.KIND_RECRAWL and processing["recrawl"]["enabled"])
                   or (kind in (engine.KIND_RETRY, engine.KIND_PROBLEM) and processing["retry"]))
        if not allowed:
            continue
        url = entry.get("candidate_url") or "https://" + domain + "/"
        host = urllib.parse.urlsplit(url).hostname or ""
        candidates.append({"url": url, "host": host, "domain": domain})
    selected, _, _ = engine.select_domains(candidates, job["profile"], state, False,
                                            job["batch"]["max_domains"], now, days, require_tld=False)
    return selected


def persist_candidates(engine, state, profile, collection, candidates, source, now):
    """No dispatch limit: every accepted pair is saved, existing outcomes stay intact."""
    _, accepted, rejected = engine.select_domains(candidates, profile, state, False, 0, now, 30,
                                                  require_tld=source == "freeworld")
    added = 0
    discovered_origins = {}
    for candidate in candidates:
        domain = engine.registrable_domain(candidate.get("host"))
        bucket = discovered_origins.setdefault(domain, [])
        for origin in origins(candidate):
            if origin not in bucket:
                bucket.append(origin)
    for domain, candidate in accepted.items():
        existing = state.profile_entry(domain, profile)
        entry = copy.deepcopy(existing or {})
        if entry.get("collection") and entry["collection"] != collection:
            raise ValueError("collection_conflict")
        if not existing:
            added += 1
        if not entry.get("collection"):
            entry["collection"] = collection
        entry.setdefault("candidate_url", candidate["url"])
        entry.setdefault("first_seen", now)
        entry.setdefault("discovered_at", now)
        entry.setdefault("region", candidate.get("region", ""))
        if candidate.get("osm_match") and not entry.get("osm_match"):
            entry["osm_match"] = candidate["osm_match"]
        entry_origins = origins(entry)
        # Filter deduplication keeps one representative; provenance preserves every source region.
        for origin in discovered_origins.get(domain, []):
            if origin not in entry_origins:
                entry_origins.append(origin)
        if entry_origins:
            entry["origins"] = entry_origins
            entry.setdefault("source", entry_origins[0]["source"])
            entry.setdefault("source_region", entry_origins[0]["source_region"])
        state.put_profile_entry(domain, profile, entry, now)
    state.save()
    return {"accepted": len(accepted), "new_pairs": added, "rejected": rejected}


class Freeworld:
    def discover(self, engine, rpc, job, spec, config, workdir, cursor):
        terms = engine.load_profiles(os.path.join(config, "profiles.conf"))
        regions = (engine.load_regions(os.path.join(config, "regions.txt"))
                   if spec.get("mode") == "all" else spec["regions"])
        return engine.discover_candidates(rpc, terms, regions, job["profile"], 8,
                                           min(5, len(regions)), cursor, 20, strict=True)


class Osm:
    def discover(self, engine, rpc, job, spec, config, workdir, cursor):
        regions = (engine.load_regions(os.path.join(config, "osm_regions.txt"))
                   if spec.get("mode") == "all" else spec["regions"])
        # One extract per turn bounds provider work; cursor belongs to this job/source.
        region = regions[cursor % len(regions)]
        cache = os.path.join(workdir, "osm", region + ".osm.pbf")
        if os.path.exists(cache) and time.time() - os.path.getmtime(cache) > 86400:
            # The old cache remains available if a refresh fails, but that refresh is reported as failed.
            engine.download_pbf(region, cache)
        cfg = engine.load_osm_config(os.path.join(config, "osm_profiles.json"))
        result, _ = engine.collect_osm_candidates([region], [job["profile"]], cfg, workdir, True)
        return list(result[job["profile"]].values()), (cursor + 1) % len(regions)


PROVIDERS = {"osm": Osm(), "freeworld": Freeworld()}


def summary(engine, state, jobs, region_catalog):
    result = engine.state_summary(state.data)
    result["available"] = True
    result["paused"] = bool(state.data.get("paused"))
    result["jobs"] = {}
    for job in jobs:
        count = 0
        for root in state.data["domains"].values():
            entry = (root.get("profiles") or {}).get(job["profile"])
            if entry is not None and not held(entry) and (not entry.get("status") or engine.legacy_auth_burned(entry)):
                if in_scope(entry, job, region_catalog):
                    count += 1
        result["jobs"][job["id"]] = {"fresh": count}
    return result


def apply_confirmations(engine, state, confirmations):
    for attempt in confirmations:
        if attempt.get("state") != "accepted":
            continue
        entry = copy.deepcopy(state.profile_entry(attempt["domain"], attempt["profile"]) or {})
        submitted = attempt["submitted_at"] / 1000
        if entry.get("last_crawl", 0) > submitted and entry.get("crawl_id") != attempt["crawl_id"]:
            raise ValueError("state_confirmation_conflict")
        confirm(engine, entry, {"id": attempt["crawl_id"]}, submitted, attempt["recrawl_days"])
        entry["automation_attempt"] = {"id": attempt["id"], "state": "accepted"}
        state.put_profile_entry(attempt["domain"], attempt["profile"], entry, submitted)
        state.save()


def apply_holds(engine, state, holds, collection):
    """An administrator deferred a start whose outcome cannot be established (docs/SCOUTRO_DISCOVERY_AUTOMATION_V1.md,
    section 16): the candidate stays out of every automatic selection with the outcome "unknown". Its status, crawl
    history and other profiles stay untouched; it is never recorded as not submitted. The same decision again
    changes nothing. Returns the number of candidates newly held."""
    count = 0
    for hold in holds:
        entry = copy.deepcopy(state.profile_entry(hold["domain"], hold["profile"]) or {})
        current = entry.get("automation_attempt") or {}
        if current.get("id") == hold["id"]:
            if current.get("state") == "accepted":
                raise ValueError("attempt_accepted_in_state")  # this state saw the start accepted: reconcile instead
            if current.get("state") == "deferred_unknown":
                continue
        if entry.get("collection") and entry["collection"] != collection:
            raise ValueError("collection_conflict")
        entry.setdefault("collection", collection)
        entry["automation_attempt"] = {"id": hold["id"], "state": "deferred_unknown", "outcome": "unknown",
                                       "run_id": hold["run_id"], "submitted_at": hold["submitted_at"],
                                       "decided_at": hold["decided_at"], "decided_by": hold["decided_by"]}
        state.put_profile_entry(hold["domain"], hold["profile"], entry, int(time.time()))
        state.save()
        count += 1
    return count


def execute(engine, state, init, rpc, config, workdir):
    job = init["job"]
    profile = job["profile"]
    collection = init["collection"]
    regions = init["regions"]
    report = {"sources": {}, "cursors": dict(init.get("cursors") or {}), "processed": 0}
    now = int(time.time())
    if init.get("operation") == "hold":
        # a hold concerns its own candidates only (apply_holds checks their collection); another domain's foreign
        # collection in this profile must never keep an unconfirmed start from being held
        report["held"] = apply_holds(engine, state, init.get("holds", []), collection)
        return report
    for root in state.data["domains"].values():
        entry = (root.get("profiles") or {}).get(profile) or {}
        if entry.get("collection") and entry["collection"] != collection:
            raise ValueError("collection_conflict")
    apply_confirmations(engine, state, init.get("confirmations", []))
    if init.get("operation") == "confirm":
        for attempt in init.get("confirmations", []):
            rpc.call("ack", {"attempt_id": attempt["id"]})
        return report
    if outcome_retry(job):
        report["outcome_retries"] = apply_outcomes(engine, state, rpc, job, int(time.time()))
    if init.get("replenish_due") and job["discovery"]["replenish"]:
        for source, spec in job["sources"].items():
            if source not in init.get("replenish_sources", list(job["sources"])):
                continue
            if state._load_raw().get("paused") or not rpc.call("admission").get("allowed"):
                report["waiting_reason"] = "paused_or_capacity"
                return report
            candidates, cursor = PROVIDERS[source].discover(engine, rpc, job, spec, config, workdir,
                                                            report["cursors"].get(source, 0))
            report["sources"][source] = persist_candidates(engine, state, profile, collection, candidates, source, now)
            report["cursors"][source] = cursor
            # Persist successful source progress even when another provider subsequently fails.
            rpc.call("source_complete", {"source": source, "cursor": cursor, "cycle_complete": cursor == 0})
        report["replenished"] = True
    selected = select_backlog(engine, state, job, regions, int(time.time()))
    for domain, candidate, _, _ in selected:
        if state._load_raw().get("paused") or not rpc.call("admission").get("allowed"):
            report["waiting_reason"] = "paused_or_capacity"
            break
        entry = copy.deepcopy(state.profile_entry(domain, profile) or {})
        now = int(time.time())
        preliminary = engine.precheck_candidate(candidate, entry, now, job["processing"]["recrawl"]["days"])
        if preliminary is None:
            try:
                result = rpc.call("crawl", {"domain": domain, "url": candidate["url"]})
                confirm(engine, entry, result, now, job["processing"]["recrawl"]["days"])
                entry["automation_attempt"] = {"id": result["attempt_id"], "state": "accepted"}
            except UnknownStart as exc:
                entry["automation_attempt"] = {"id": str(exc), "state": "submitted_unknown"}
                state.put_profile_entry(domain, profile, entry, now)
                state.save()
                raise
            except engine.ApiError as exc:
                if exc.code == "decisions_unavailable":
                    # every crawl start is refused until the decisions are recovered: stop the batch, change nothing
                    report["waiting_reason"] = exc.code
                    break
                if exc.code in DEFERRING:
                    # a durable collection decision (or a conflict) refuses this pair: wait instead of trying it in every
                    # batch; status, origins and history stay (WP-C)
                    days = DEFERRING[exc.code] or job["processing"]["recrawl"]["days"]
                    entry["deferred"] = {"code": exc.code, "at": now, "until": now + days * 86400}
                    report["deferred"] = report.get("deferred", 0) + 1
                    state.put_profile_entry(domain, profile, entry, now)
                    state.save()
                    continue
                if exc.code in ("host_busy", "capacity", "paused"):
                    report["waiting_reason"] = exc.code
                    continue
                if engine.classify_api_error(exc) == "rejected":
                    entry.update({"status": "rejected", "error_class": "rejected",
                                  "attempts": entry.get("attempts", 0) + 1,
                                  "last_error": exc.code, "last_http_status": exc.status,
                                  "next_attempt": now + 60 * 86400})
                else:
                    raise
        state.put_profile_entry(domain, profile, entry, now)
        state.save()
        if entry.get("automation_attempt", {}).get("state") == "accepted":
            rpc.call("ack", {"attempt_id": entry["automation_attempt"]["id"]})
        checked = precheck_report(preliminary) if preliminary is not None else None
        if checked:
            try:
                rpc.call("precheck", {"domain": domain, "url": candidate["url"], "result": checked[0], "detail": checked[1]})
            except engine.ApiError:
                pass  # the crawl report is best effort; the selection state above is already saved
        report["processed"] += 1
        # Admission is checked again after every delay; YaCy's own per-host delay is independent.
        time.sleep(job["batch"]["seed_delay_seconds"])
    corrections = submit_autocorrect(engine, state, rpc, init, config, int(time.time()))
    if corrections is not None:
        report["autocorrect"] = corrections
    return report


def run(args, engine):
    import sys
    state = None
    try:
        line = sys.stdin.readline(8_000_001)
        if len(line) > 8_000_000:
            raise ProtocolError("oversized initialization")
        init = json.loads(line)
        operation = init.get("operation")
        if operation not in ("status", "run", "confirm", "hold"):
            raise ProtocolError("unknown operation")
        # Automation never falls back to the source-tree examples.
        if not args.workdir or not args.config_dir:
            raise ProtocolError("automation requires resolved config and state roots")
        state = engine.State(args.workdir, read=operation == "status")
        if operation != "status":
            if not state.acquire_run_lock():
                emit({"type": "done", "error": "busy"})
                return
            state.data, state.disk_version, state.migrated = state._read()
        rpc = Rpc(engine)
        if operation == "status":
            report = {}
        elif state.data.get("paused") and operation not in ("confirm", "hold"):
            emit({"type": "done", "error": "paused"})
            return
        else:
            report = execute(engine, state, init, rpc, args.config_dir, args.workdir)
        emit({"type": "done", "report": report,
              "snapshot": summary(engine, state, init.get("jobs", []), init.get("regions", {}))})
    except UnknownStart:
        emit({"type": "done", "error": "submitted_unknown"})
    except Exception as exc:
        # No external exception text: it may contain URLs, credentials or payloads.
        code = "state_unreadable" if isinstance(exc, engine.StateError) else "source_or_protocol_error"
        if isinstance(exc, ValueError) and str(exc) in ("collection_conflict", "state_confirmation_conflict",
                                                        "attempt_accepted_in_state"):
            code = str(exc)
        emit({"type": "done", "error": code})
    finally:
        if state is not None and state._run_lock is not None:
            state._run_lock.close()
