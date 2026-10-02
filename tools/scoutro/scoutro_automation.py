"""Deterministic automation adapter around scoutro-discovery's existing engine.

GPL-2.0-or-later. Python alone writes the multi-profile candidate state.
The parent supplies a frozen config directory and an allowlisted JSON RPC peer;
no password, HTTP endpoint, command or arbitrary job path is accepted here.
"""
import copy
import json
import os
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


def select_backlog(engine, state, job, region_catalog, now):
    """Reuse the original eligibility/order; apply only job scope and processing mask."""
    processing = job["processing"]
    days = processing["recrawl"]["days"]
    candidates = []
    for domain, root in state.data["domains"].items():
        entry = (root.get("profiles") or {}).get(job["profile"])
        if entry is None or entry.get("automation_attempt", {}).get("state") == "submitted_unknown":
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
            if entry is not None and (not entry.get("status") or engine.legacy_auth_burned(entry)):
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
        engine.confirm_start(entry, {"id": attempt["crawl_id"]}, submitted, attempt["recrawl_days"])
        entry["automation_attempt"] = {"id": attempt["id"], "state": "accepted"}
        state.put_profile_entry(attempt["domain"], attempt["profile"], entry, submitted)
        state.save()


def execute(engine, state, init, rpc, config, workdir):
    job = init["job"]
    profile = job["profile"]
    collection = init["collection"]
    regions = init["regions"]
    report = {"sources": {}, "cursors": dict(init.get("cursors") or {}), "processed": 0}
    now = int(time.time())
    for root in state.data["domains"].values():
        entry = (root.get("profiles") or {}).get(profile) or {}
        if entry.get("collection") and entry["collection"] != collection:
            raise ValueError("collection_conflict")
    apply_confirmations(engine, state, init.get("confirmations", []))
    if init.get("operation") == "confirm":
        for attempt in init.get("confirmations", []):
            rpc.call("ack", {"attempt_id": attempt["id"]})
        return report
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
                engine.confirm_start(entry, result, now, job["processing"]["recrawl"]["days"])
                entry["automation_attempt"] = {"id": result["attempt_id"], "state": "accepted"}
            except UnknownStart as exc:
                entry["automation_attempt"] = {"id": str(exc), "state": "submitted_unknown"}
                state.put_profile_entry(domain, profile, entry, now)
                state.save()
                raise
            except engine.ApiError as exc:
                if exc.code in ("host_busy", "collection_conflict", "capacity", "paused"):
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
        report["processed"] += 1
        # Admission is checked again after every delay; YaCy's own per-host delay is independent.
        time.sleep(job["batch"]["seed_delay_seconds"])
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
        if operation not in ("status", "run", "confirm"):
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
        elif state.data.get("paused") and operation != "confirm":
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
        if isinstance(exc, ValueError) and str(exc) in ("collection_conflict", "state_confirmation_conflict"):
            code = str(exc)
        emit({"type": "done", "error": code})
    finally:
        if state is not None and state._run_lock is not None:
            state._run_lock.close()
