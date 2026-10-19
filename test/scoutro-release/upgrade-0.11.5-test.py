#!/usr/bin/env python3
"""The update 0.11.4 -> 0.11.5 and the rollback with the real images on a disposable DATA volume (no production data, no
crawl, no model, no network for the peer). Every image runs as UID/GID 1000 like the Olares chart, on a loopback port.

1. A peer root as the production peer looks before 0.11.5: protective settings, the knowledge graph on schema 9 (written
   by 0.11.4 itself), a Discovery job store (schema 1) with thirteen jobs, automation disabled, no open batch and the four
   candidates held after an administrator's deferral (DiscoveryHeldSeed.java with the real classes; the worker's state
   carries the same holds), and the knowledge graph paused by hand (the paused LLM queue of production).
2. 0.11.4: two pages through the push API; the old rules store the kind of partner "Banken" as an organisation.
3. 0.11.5 on the same volume: version 1.942-scoutro.39, schema 9 without migration, copy or reextract_pending; the same
   statements; the pause kept; the job store, the jobs, the four holds and the worker state byte-identical; the settings
   unchanged; "Banken" labelled "generic" and hidden from the operator's business relations but still stored; after a
   resume a new page with the same list yields no second "Banken" and no model request; a restart changes nothing.
4. Rollback: 0.11.4 on the same volume opens schema 9 and the job store as 0.11.5 left them.

Usage: upgrade-0.11.5-test.py [old-image] [new-image]   (default scoutro-rc:0.11.4 and scoutro-rc:1.942-scoutro.39)
"""
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PORT = 18195
BASE = f"http://127.0.0.1:{PORT}"
APP = "/opt/yacy_search_server"
KG = APP + "/DATA/SCOUTRO/knowledge"
OLD = sys.argv[1] if len(sys.argv) > 1 else "scoutro-rc:0.11.4"
NEW = sys.argv[2] if len(sys.argv) > 2 else "scoutro-rc:1.942-scoutro.39"
VOL = "scoutro-up115"
NAME = "up115"
SEED = Path(os.environ.get("SCOUTRO_SEED_DIR", "/home/user/up115-seed"))
HELD = ["accsicherheitstechnik.de", "sonjasturm.de", "tarienna.eu", "codecamp-n.com"]
CONF = ["port=8090", "adminAccountForLocalhost=false", "adminAccountAllPages=false", "adminAccountUserName=admin",
        "adminAccountBase64MD5=MD5:8cffbc0d66567a0987a4aba1ec46d63c", "network.unit.definition=defaults/yacy.network.webportal.unit",
        "browserPopUpTrigger=false", "autocrawl=false", "server.https=false", "upnp.enabled=false", "donation.iframesource=",
        "resource.disk.free.min.steadystate=1", "resource.disk.free.min.undershot=1",
        "scoutro.kg.enabled=true", "scoutro.kg.collections=bauteamcheck-web,stackfinder-web", "scoutro.kg.jsonld.enabled=true",
        "scoutro.kg.technology.enabled=false", "scoutro.collections.autocorrect.mode=off", "scoutro.kg.identity.productUrl=false",
        "scoutro.kg.identity.providerServiceKey=false", "scoutro.kg.llm.maxRequestsPerDay=0", "ai.production_models=[]",
        "ai.service_num_ctx={}", "ai.anthropic.workspace_id="]
WATCHED = ("ai.", "scoutro.kg.", "scoutro.collections.", "scoutro.discovery.")
results = []


def sh(*args, check=True):
    r = subprocess.run(list(args), capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"{args}: {r.stderr[-800:]}")
    return r.stdout


def as_root(volume, image, script, extra=()):
    return sh("docker", "run", "--rm", "--user", "0", "--network", "none", "--entrypoint", "/bin/sh", *extra,
              "-v", f"{volume}:{APP}/DATA", image, "-c", script)


def opener():
    p = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    p.add_password(None, BASE, "admin", "yacy")
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPDigestAuthHandler(p),
                                       urllib.request.HTTPCookieProcessor())


OPEN = opener()


def get(path, timeout=60):
    with OPEN.open(BASE + path, timeout=timeout) as r:
        return json.loads(r.read())


def post(path, body):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Origin": BASE})
    with OPEN.open(req, timeout=120) as r:
        return json.loads(r.read())


def push(url, html, collection):
    boundary = f"----up115{time.time_ns()}"
    fields = {"count": "1", "synchronous": "true", "commit": "true", "url-0": url, "collection-0": collection,
              "contentType-0": "text/html", "lastModified-0": "Tue, 15 Nov 1994 12:45:26 GMT", "responseHeader-0": ""}
    body = b"".join(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{k}\"\r\n\r\n{v}\r\n".encode() for k, v in fields.items())
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"data-0\"; filename=\"page.html\"\r\n"
             "Content-Type: text/html\r\n\r\n").encode() + html.encode() + f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(BASE + "/api/push_p.json", data=body, method="POST",
                                 headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with OPEN.open(req, timeout=120) as r:
        assert json.loads(r.read()).get("countsuccess") == 1


def page(name, url, text):
    return (f"<html><head><title>{name}</title><script type=\"application/ld+json\">"
            f"{{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"{name}\",\"url\":\"{url}\"}}</script></head>"
            f"<body><h1>{name}</h1><p>{text}</p></body></html>")


def start(image, volume):
    sh("docker", "rm", "-f", NAME, check=False)
    sh("docker", "run", "-d", "--name", NAME, "--user", "1000:1000", "-p", f"127.0.0.1:{PORT}:8090", "-v", f"{volume}:{APP}/DATA", image)
    t0 = time.time()
    for _ in range(240):
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(BASE + "/scoutro/api/v1/health", timeout=3) as r:
                return json.loads(r.read()), round(time.time() - t0, 1)
        except Exception:
            time.sleep(1)
    raise RuntimeError("no health: " + sh("docker", "logs", "--tail", "40", NAME, check=False))


def stop():
    sh("docker", "stop", "-t", "60", NAME, check=False)
    logs = sh("docker", "logs", NAME, check=False) + subprocess.run(["docker", "logs", NAME], capture_output=True, text=True).stderr
    sh("docker", "rm", "-f", NAME, check=False)
    return logs


def settle(timeout=600, paused_ok=False):
    end = time.time() + timeout
    while time.time() < end:
        s = get("/scoutro/api/v1/kg/status")
        sync = s.get("sync") or {}
        lag = sync.get("lag") or {}
        if s.get("state") == "running" and (paused_ok or (sync.get("initialized") and lag.get("pending", 1) == 0
                                                         and not lag.get("reconcile_pending", True))):
            return s
        time.sleep(2)
    return get("/scoutro/api/v1/kg/status")


def sql(query):
    out = sh("docker", "exec", NAME, "python3", "-c",
             "import sqlite3,sys,json; c=sqlite3.connect('file:" + KG + "/graph.db?mode=ro',uri=True); "
             "print(json.dumps(c.execute(sys.argv[1]).fetchall()))", query)
    return json.loads(out)


STMTS = ("SELECT s.subj, v.name, coalesce(s.obj_val, 'ent:' || s.obj_ent), s.quality, s.current_sources, "
         "(SELECT group_concat(x.name || '/' || x.version) FROM kg_evidence ev JOIN kg_extractor x ON x.ext_id=ev.ext_id WHERE ev.stmt_rowid=s.stmt_rowid) "
         "FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred ORDER BY 1,2,3")
TABLES = ["kg_doc", "kg_entity", "kg_statement", "kg_evidence", "kg_observation", "kg_derived", "kg_entity_key"]


def counts():
    out = {}
    for t in TABLES:
        try:
            out[t] = sql(f"SELECT count(*) FROM {t}")[0][0]
        except RuntimeError:
            out[t] = None
    return out


def meta(key):
    r = sql(f"SELECT value FROM kg_meta WHERE key='{key}'")
    return r[0][0] if r else None


def named(name):
    return sql(f"SELECT count(*) FROM kg_statement s JOIN kg_vocab v ON v.term_id=s.pred WHERE v.name='name' AND s.obj_val='{name}'")[0][0]


def summary(s):
    store = s.get("store") or {}
    llm = s.get("llm") or {}
    return {"state": s.get("state"), "reason": s.get("reason"), "schema": store.get("schemaVersion"),
            "integrity": (store.get("integrity") or {}).get("state"), "manualPause": store.get("manualPause"),
            "upgrade": s.get("upgrade"), "llm_today": (llm.get("dailyRequests") or {}).get("today"), "llm_state": llm.get("state")}


def before_upgrade():
    return [(b["file"], b.get("kg_schema_version")) for b in get("/scoutro/api/v1/kg/backups").get("items", [])
            if b.get("kind") == "before_upgrade"]


def discovery():
    status = get("/scoutro/api/v1/discovery/status")
    jobs = get("/scoutro/api/v1/discovery/jobs")["jobs"]
    held = sorted((h["domain"], h["attempt_id"], h["state"], h["decision"]["by"], h["decision"].get("automatic"))
                  for j in jobs for h in (j["runtime"].get("held_attempts") or []))
    return {"enabled": status.get("enabled"), "active_batch": status.get("active_batch"), "jobs": len(jobs),
            "job_ids": sorted(j["definition"]["id"] for j in jobs), "held": held, "revision": status.get("revision")}


def file_digest(volume, image, path):
    return as_root(volume, image, f"sha256sum {path} | cut -c1-64").strip()


def settings(volume, image):
    text = as_root(volume, image, f"cat {APP}/DATA/SETTINGS/yacy.conf")
    return sorted(l for l in text.splitlines() if l.startswith(WATCHED))


def record(row, ok):
    row["ok"] = bool(ok)
    results.append(row)
    print(json.dumps(row), flush=True)


def severe(logs):
    return [l[:300] for l in logs.splitlines() if " SEVERE " in l or "unknown field" in l.lower()][:10]


def classpath():
    jars = sorted(str(p) for d in ("lib", "libt", "build/ivy-retrieve/compile") for p in (REPO / d).glob("*.jar"))
    return ":".join([str(REPO / "build/classes/java/main")] + jars)


def seed():
    """The production peer before 0.11.5: settings, Discovery configuration, worker state and the job store with four holds."""
    if SEED.exists():
        shutil.rmtree(SEED)
    data = SEED / "DATA"
    (SEED / ".scoutro-dashboard-disposable").touch(exist_ok=True) if SEED.exists() else None
    for d in ("SETTINGS", "SCOUTRO/config", "SCOUTRO/discovery", "DICTIONARIES/harvesting"):
        (data / d).mkdir(parents=True, exist_ok=True)
    (SEED / ".scoutro-dashboard-disposable").touch()
    (data / "SETTINGS/yacy.conf").write_text("\n".join(CONF) + "\n")
    for f in ("export_roar_ROAR_ListFriends.xml", "ListFriends.xml"):
        (data / "DICTIONARIES/harvesting" / f).write_text('<?xml version="1.0" encoding="UTF-8"?>\n<BaseURLs/>\n')
    cfg = data / "SCOUTRO/config"
    (cfg / "profiles.json").write_text(json.dumps({"profiles": {"new_profile": {"collection": "stackfinder-web"}}}))
    (cfg / "profiles.conf").write_text("new_profile = Beratung;Fachbetrieb\n")
    (cfg / "osm_profiles.json").write_text(json.dumps({"extract": "nwr[website]", "profiles": {"new_profile": {"text_any": ["Beratung"]}}}))
    (cfg / "osm_regions.txt").write_text("test-region\n")
    (cfg / "regions.txt").write_text("Teststadt\n")
    out = subprocess.run(["java", "-cp", classpath(), str(REPO / "test/scoutro-release/DiscoveryHeldSeed.java"), str(SEED), *HELD],
                         capture_output=True, text=True, timeout=600, cwd=REPO)
    if out.returncode != 0:
        raise RuntimeError(out.stderr[-2000:])
    seeded = json.loads(out.stdout.strip().splitlines()[-1])
    # the store as 0.11.4 wrote it: its decisions carry no "automatic" (the field exists since 0.11.5)
    store_path = data / "SETTINGS/scoutro-discovery-jobs.json"
    store = json.loads(store_path.read_text())
    for job in store["jobs"]:
        for held in job["runtime"].get("held_attempts", []):
            held["decision"].pop("automatic", None)
    store_path.write_text(json.dumps(store, indent=2))
    # the worker's state: the same four candidates held with the outcome unknown
    loader = importlib.machinery.SourceFileLoader("scoutro_discovery", str(REPO / "tools/scoutro/scoutro-discovery"))
    spec = importlib.util.spec_from_loader("scoutro_discovery", loader)
    disc = importlib.util.module_from_spec(spec)
    loader.exec_module(disc)
    state = disc.State(str(data / "SCOUTRO/discovery"))
    now = int(time.time())
    for hold in seeded["holds"]:
        state.put_profile_entry(hold["domain"], "new_profile", {
            "collection": "stackfinder-web", "candidate_url": f"https://{hold['domain']}/", "first_seen": now,
            "automation_attempt": {"id": hold["attempt_id"], "state": "deferred_unknown", "outcome": "unknown", "run_id": hold["run_id"],
                                   "submitted_at": now * 1000, "decided_at": now * 1000, "decided_by": "digest:admin"}}, now)
    state.put_profile_entry("firm-free.de", "new_profile", {"collection": "stackfinder-web", "candidate_url": "https://firm-free.de/",
                                                            "first_seen": now}, now)
    state.save()
    return seeded


def main():
    seeded = seed()
    sh("docker", "rm", "-f", NAME, check=False)
    sh("docker", "volume", "rm", "-f", VOL, check=False)
    sh("docker", "volume", "create", VOL)
    as_root(VOL, OLD, f"cp -a /seed/DATA/. {APP}/DATA/ && chown -R 1000:1000 {APP}/DATA", extra=("-v", f"{SEED}:/seed:ro"))
    record(dict(step="seed", jobs=seeded["jobs"], held=seeded["held"], enabled=seeded["enabled"], active_run=seeded["active_run"]),
           seeded["jobs"] == 13 and seeded["held"] == 4 and seeded["enabled"] is False and seeded["active_run"] is None)

    # 2. 0.11.4
    health, secs = start(OLD, VOL)
    push("https://www.rostocker-massivhaus-test.de/finanzierung", page("Rostocker Massivhaus GmbH", "https://www.rostocker-massivhaus-test.de/",
         "Finanzierung: Unsere Partner: Banken, Versicherungen, Bausparkassen und KfW. Wir vermitteln die passende Finanzierung."), "bauteamcheck-web")
    push("https://www.pro-casa-bau-test.de/", page("Pro Casa Bau GmbH", "https://www.pro-casa-bau-test.de/",
         "Startseite Leistungen Referenzen Bildergalerie Kontakt Impressum. Wir bauen Massivhaeuser in Rostock."), "bauteamcheck-web")
    s = settle()
    base_counts = counts()
    base_stmts = sql(STMTS)
    base_meta = {k: meta(k) for k in ("schema_version", "extractors", "reextract_pending")}
    banken = named("Banken")
    disc_old = discovery()
    post("/scoutro/api/v1/kg/control", {"action": "pause"})
    paused = get("/scoutro/api/v1/kg/status")
    row = dict(step="0.11.4", health=health.get("version"), start_s=secs, **summary(s), counts=base_counts, meta=base_meta,
               banken_names=banken, discovery=disc_old, pausedAfter=(paused.get("store") or {}).get("manualPause"))
    record(row, s.get("state") == "running" and row["schema"] == 9 and (row["health"] or {}).get("scoutro") == "1.942-scoutro.38"
           and banken >= 1 and disc_old["jobs"] == 13 and len(disc_old["held"]) == 4 and disc_old["active_batch"] is None
           and disc_old["enabled"] is False and row["pausedAfter"] is True and base_meta["reextract_pending"] in (None, ""))
    logs_old = stop()
    store_digest = file_digest(VOL, OLD, f"{APP}/DATA/SETTINGS/scoutro-discovery-jobs.json")
    state_digest = file_digest(VOL, OLD, f"{APP}/DATA/SCOUTRO/discovery/state.json")
    graph_digest = file_digest(VOL, OLD, f"{KG}/graph.db")
    settings_old = settings(VOL, OLD)
    record(dict(step="0.11.4 stopped", storeSha256=store_digest, stateSha256=state_digest, graphSha256=graph_digest,
                watchedSettings=len(settings_old), severe=severe(logs_old)), not severe(logs_old) and settings_old)

    # 3. 0.11.5 on the same volume
    health, secs = start(NEW, VOL)
    s = settle(paused_ok=True)
    disc_new = discovery()
    row = dict(step="0.11.5 first start (paused)", health=health.get("version"), start_s=secs, **summary(s), copies=before_upgrade(),
               counts=counts(), meta={k: meta(k) for k in ("schema_version", "extractors", "reextract_pending")}, banken_names=named("Banken"),
               discovery=disc_new, statementsEqual=sql(STMTS) == base_stmts)
    record(row, (row["health"] or {}).get("scoutro") == "1.942-scoutro.39" and row["schema"] == 9 and row["state"] == "running"
           and not row["copies"] and row["counts"] == base_counts and row["meta"] == base_meta and row["statementsEqual"]
           and row["manualPause"] is True and row["banken_names"] == banken and disc_new == disc_old)
    # the read side: "Banken" is labelled and hidden, not deleted
    hits = get("/scoutro/api/v1/kg/entities?q=Banken&type=organization").get("items", [])
    generic = [h for h in hits if h.get("name") == "Banken"]
    operator = [h for h in get("/scoutro/api/v1/kg/entities?q=Rostocker&type=organization").get("items", []) if "Rostocker" in (h.get("name") or "")]
    business = get("/scoutro/api/v1/kg/entities/" + operator[0]["id"] + "/business") if operator else {}
    relations = (business.get("relations") or {})
    shown = [r.get("other", {}).get("name") for r in relations.get("facts", [])]
    row = dict(step="0.11.5 read side", genericRole=[h.get("role") for h in generic], operatorRole=[h.get("role") for h in operator],
               hidden_generic=relations.get("hidden_generic"), shownRelations=shown)
    record(row, generic and all(h.get("role") == "generic" for h in generic) and operator and operator[0].get("role") == "operator"
           and (relations.get("hidden_generic") or 0) >= 1 and "Banken" not in shown)
    # the new rules on a new page, without a model: no second "Banken"
    llm_before = summary(s)["llm_today"]
    post("/scoutro/api/v1/kg/control", {"action": "resume"})
    settle()
    push("https://www.dielen-kaufen-test.de/partner", page("Dielen Kaufen GmbH", "https://www.dielen-kaufen-test.de/",
         "Unsere Partner: Banken, Versicherungen, Bausparkassen und KfW. Partner | Showroom | Kontakt"), "bauteamcheck-web")
    s = settle()
    row = dict(step="0.11.5 resumed: a new page with the old list", **summary(s), counts=counts(), banken_names=named("Banken"),
               showroom_names=named("Showroom"), reextractPending=meta("reextract_pending"), quickCheck=sql("PRAGMA quick_check")[0][0])
    record(row, row["state"] == "running" and row["banken_names"] == banken and row["showroom_names"] == 0
           and row["counts"]["kg_doc"] == base_counts["kg_doc"] + 1 and row["reextractPending"] in (None, "")
           and row["quickCheck"] == "ok" and row["llm_today"] in (None, 0, llm_before))
    post("/scoutro/api/v1/kg/control", {"action": "pause"})
    after_counts = counts()
    logs_new = stop()
    row = dict(step="0.11.5 stopped", storeSha256=file_digest(VOL, NEW, f"{APP}/DATA/SETTINGS/scoutro-discovery-jobs.json"),
               stateSha256=file_digest(VOL, NEW, f"{APP}/DATA/SCOUTRO/discovery/state.json"), settingsEqual=settings(VOL, NEW) == settings_old,
               severe=severe(logs_new))
    record(row, row["storeSha256"] == store_digest and row["stateSha256"] == state_digest and row["settingsEqual"] and not row["severe"])
    health, secs = start(NEW, VOL)
    s = settle(paused_ok=True)
    row = dict(step="0.11.5 restart", start_s=secs, **summary(s), copies=before_upgrade(), counts=counts(), discovery=discovery())
    record(row, row["schema"] == 9 and not row["copies"] and row["counts"] == after_counts and row["discovery"] == disc_old
           and row["manualPause"] is True)
    stop()

    # 4. rollback: 0.11.4 on the same volume
    health, secs = start(OLD, VOL)
    s = settle(300, paused_ok=True)
    row = dict(step="0.11.4 rollback on the same volume", health=health.get("version"), start_s=secs, **summary(s), counts=counts(),
               discovery=discovery(), banken_names=named("Banken"))
    logs = stop()
    row["severe"] = severe(logs)
    record(row, (row["health"] or {}).get("scoutro") == "1.942-scoutro.38" and row["state"] == "running" and row["schema"] == 9
           and row["counts"] == after_counts and row["discovery"] == disc_old and row["manualPause"] is True and not row["severe"])

    sh("docker", "volume", "rm", "-f", VOL, check=False)
    print("ALL OK" if all(r["ok"] for r in results) else "FAILED", flush=True)
    return 0 if all(r["ok"] for r in results) else 1


if __name__ == "__main__":
    sys.exit(main())
