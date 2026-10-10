#!/usr/bin/env python3
"""A/B/C acceptance on NEW disposable DATA and loopback-only controlled sources.

No external model, production peer or configuration. Requires a fresh compile,
JDK and Playwright/Chromium. Keeps evidence in SCOUTRO_ACCEPTANCE_DIR, or a new
temporary directory; refuses a reused peer directory. Reuses the established
disposable Peer helper, never its full live suite.
"""
import importlib.util
import http.server
import json
import os
from pathlib import Path
import sqlite3
import socket
import subprocess
import tempfile
import threading
import time
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("kg_disposable", ROOT / "test/scoutro-api/kg-e2e-live.py")
helper = importlib.util.module_from_spec(spec)
spec.loader.exec_module(helper)
REPORT = {"checks": [], "timings": {}, "fixtures": {}, "failures": []}


def check(value, message):
    if not value:
        raise AssertionError(message)
    REPORT["checks"].append(message)


def page(org, text="", services=(), job=None, region=None):
    o = {"@context": "https://schema.org", "@type": "Organization", "name": org,
         "url": "URL", "description": text}
    if services:
        o["makesOffer"] = [{"@type": "Offer", "itemOffered": {"@type": "Service", "name": s}} for s in services]
    if region:
        o["areaServed"] = {"@type": "City", "name": region}
    nodes = [o]
    if job:
        nodes.append({"@type": "JobPosting", "title": "Engineer", "datePosted": "2024-01-15", "description": job,
                      "hiringOrganization": {"@type": "Organization", "name": org, "url": "URL"}})
    return '<html lang="de"><head><title>' + org + '</title><script type="application/ld+json">' + json.dumps(nodes, ensure_ascii=False) + \
           '</script></head><body><h1>' + org + '</h1><p>' + text + '</p></body></html>'


FIXTURES = {
    "stack": ("chain-stack", "Stack Service GmbH", page("Stack Service GmbH", services=["SAP Beratung", "Revit Schulung", "Archicad Unterstützung"])),
    "industry": ("chain-industry", "Industrie Werk GmbH", page("Industrie Werk GmbH", job="Wir nutzen SAP intern. SAP Kenntnisse erforderlich.")),
    "consultancy": ("chain-industry", "SAP Anbieter GmbH", page("SAP Anbieter GmbH", "Wir bieten SAP-Beratung an.", job="Wir nutzen SAP intern. SAP Kenntnisse erforderlich.")),
    "architect": ("chain-build", "Architektur Atelier GmbH", page("Architektur Atelier GmbH", "Wir erbringen Architekturleistungen.", job="Wir nutzen Revit intern. Archicad Kenntnisse wünschenswert.")),
    "planner": ("chain-build", "Planung Partner GmbH", page("Planung Partner GmbH", services=["Architektur und Genehmigungsplanung", "Energieberatung"])),
    "building": ("chain-industry", "Gebaeude Werk GmbH", page("Gebaeude Werk GmbH", "Wir planen den Neubau unseres Werkes in Berlin. Als Gebäudebetreiber planen wir die energetische Sanierung unseres Gebäudes in Berlin.")),
    "coach": ("chain-coach", "Team Coaching GmbH", page("Team Coaching GmbH", services=["Führungscoaching", "Teamentwicklung"])),
    "leadership": ("chain-industry", "Team Werk GmbH", page("Team Werk GmbH", "Unser Unternehmen benötigt Führungsentwicklung. Unsere Teams benötigen Teamentwicklung.")),
    "care": ("chain-care", "Ambulante Versorgung GmbH", page("Ambulante Versorgung GmbH", services=["Ambulante Pflege"])),
    "hospital": ("chain-industry", "Klinik Uebergang GmbH", page("Klinik Uebergang GmbH", "Unser Krankenhaus koordiniert unser Entlassmanagement für den Übergang in ambulante Versorgung in Berlin.")),
    "secret": ("chain-secret", "Verdeckte Fabrik GmbH", page("Verdeckte Fabrik GmbH", job="Wir nutzen SAP intern.")),
    "region": ("chain-region", "Ambulante Versorgung GmbH", page("Ambulante Versorgung GmbH", region="Berlin")),
}


class Sources(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        key = self.headers.get("Host", "").split(":")[0].split(".")[0]
        body = "User-agent: *\nAllow: /\n" if self.path == "/robots.txt" else FIXTURES.get(key, (None, None, ""))[2]
        if key == "region":
            url = f"http://care.fixture.test:{self.server.server_port}/"
        else:
            url = f"http://{key}.fixture.test:{self.server.server_port}/"
        body = body.replace("URL", url).encode()
        self.send_response(200 if body else 404)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


def main():
    evidence = Path(os.environ.get("SCOUTRO_ACCEPTANCE_DIR") or tempfile.mkdtemp(prefix="scoutro-chain-"))
    evidence.mkdir(parents=True, exist_ok=True)
    peer_root = evidence / "peer"
    if peer_root.exists():
        raise ValueError("Refusing existing peer DATA")
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Sources)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    settings = ["scoutro.kg.enabled=true", "scoutro.kg.collections=" + ",".join(sorted({f[0] for f in FIXTURES.values()})),
                "scoutro.kg.jsonld.enabled=true", "scoutro.kg.llm.collections=", "ai.production_models=[]",
                "scoutro.kg.jobs.collections=*", "scoutro.kg.reconcile.debounceSeconds=5"]
    peer = helper.Peer(peer_root, settings, "A/B/C acceptance")
    (peer_root / ".scoutro-chain-disposable").write_text("local fixture acceptance\n")
    (peer_root / "hosts").write_text(f"127.0.0.1 localhost {socket.gethostname()}\n"+"".join(f"127.0.0.1 {key}.fixture.test\n" for key in FIXTURES))
    try:
        REPORT["tested_commit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
        REPORT["working_tree_diff"] = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()
        REPORT["timings"]["startup_s"] = peer.start()
        peer.wait("initial reconciliation", lambda s: peer.settled(s))
        t0 = time.monotonic()
        for key, (collection, name, _) in FIXTURES.items():
            url=f"http://{key}.fixture.test:{server.server_port}/"
            if key in ("stack", "industry"):
                print("crawl fixture", key, flush=True)
                peer.crawl(url, collection, depth=0, max_pages=1)
            else:
                print("parser push of served fixture", key, flush=True)
                request=urllib.request.Request(f"http://127.0.0.1:{server.server_port}/",headers={"Host":f"{key}.fixture.test:{server.server_port}"})
                with helper.ANON.open(request) as source: content=source.read().decode()
                peer.ensure_collections([collection]);peer.push([(url,content)],collection)
        peer.wait("fixture extraction", lambda s: peer.settled(s)
                  and s["sync"]["processed"]["published"] >= len(FIXTURES)
                  and all(len(helper.find(peer, name, collection, "organization")) == 1
                          for collection, name, _ in FIXTURES.values()), timeout=300)
        REPORT["timings"]["crawl_extract_s"] = time.monotonic() - t0
        ids = {}
        for key, (collection, name, _) in FIXTURES.items():
            found = helper.find(peer, name, collection, "organization")
            check(len(found) == 1, "one assigned organization for " + key)
            ids[key] = found[0]["id"]
        REPORT["fixtures"] = ids

        def suggestions(key):
            return peer.kg(f"/entities/{ids[key]}/suggestions", collection=FIXTURES[key][0], limit=100)

        previous_derive = peer.status().get("derived", {}).get("lastRun")
        status, _ = peer.control("derive")
        check(status == 200, "actual derive API accepted")
        peer.wait("derived cache", lambda s: (s.get("derived") or {}).get("lastRun") != previous_derive
                  and (s.get("derived") or {}).get("matching", {}).get("checked", 0) > 0, timeout=120)
        expected = {"stack": {"industry", "architect", "secret"}, "planner": {"building"}, "coach": {"leadership"}, "care": {"hospital"}}
        for provider, candidates in expected.items():
            result = suggestions(provider)
            actual = {i["other"]["id"] for i in result["items"]}
            check(actual == {ids[k] for k in candidates}, provider + " exact candidates; no unrelated or consultancy job")
            for item in result["items"]:
                check(item["kind"] == ("suggested_partner" if provider == "care" else "suggested_customer"), provider + " relationship type")
                check(item["contributions_total"] >= 1 and all(c["evidence_complete"] for c in item["contributions"]), "complete reasons " + provider)
        item = next(i for i in suggestions("stack")["items"] if i["other"]["id"] == ids["industry"])
        check(item["target_collection"] == "chain-industry", "cross-collection target context")
        check("chain-industry" in item["other"]["other_collections"], "foreign collection tag")
        observation = next(c for c in item["contributions"] if c.get("context") == "internal_use")["evidence"][1]
        check(peer.kg("/observations/" + observation["id"], collection="chain-industry")["quote"].startswith("Wir nutzen SAP"), "real historical evidence navigation")
        check(observation["job_search"]["status"] == "unknown", "no expiry means unknown job status")
        token = peer.create_agent("limited chain reader", ["kg.read", "kg.export"], ["chain-stack", "chain-industry", "chain-build", "chain-care", "chain-coach"])
        code, limited = peer.agent(token, f"/kg/entities/{ids['stack']}/suggestions", collection="chain-stack", limit=100)
        check(code == 200 and limited["total"] == 2, "restricted grouped counts")
        check("Verdeckte Fabrik" not in json.dumps(limited) and "chain-secret" not in json.dumps(limited), "no hidden candidate/name/count context leak")
        code, limited_care = peer.agent(token, f"/kg/entities/{ids['care']}/suggestions", collection="chain-care")
        check(code == 200 and limited_care["total"] == 0, "additional regional evidence grant required")
        check(peer.agent(token, f"/kg/observations/{observation['id']}", collection="chain-secret")[0] == 403, "selected collection never enlarges grant")
        for provider in expected:
            graph = peer.kg(f"/entities/{ids[provider]}/neighborhood", collection=FIXTURES[provider][0], suggested="true", limit=100)
            check(any(e["type"] in ("suggested_customer", "suggested_partner") for e in graph["edges"]), "real graph " + provider)
        exported, cursor, pages = [], None, 0
        while True:
            code, result = peer.agent(token, "/kg/export", **({"cursor": cursor} if cursor else {}), limit=20)
            check(code == 200, "restricted export page")
            exported.extend(result["items"]); pages += 1
            if result["complete"]: break
            cursor = result["next"]
            check(pages < 100, "export makes progress")
        check(any(r["record"] == "match_contribution" for r in exported), "matching reasons exported")
        check("Verdeckte Fabrik" not in json.dumps(exported) and "chain-secret" not in json.dumps(exported), "export does not reveal secret scope")
        (evidence / "ui-fixtures.json").write_text(json.dumps({"base": peer.base, "ids": ids, "collections": {k: v[0] for k, v in FIXTURES.items()}}))
        env = dict(os.environ, SCOUTRO_CHAIN_FIXTURE=str(evidence / "ui-fixtures.json"))
        browser = subprocess.run(["node", str(ROOT / "test/scoutro-ui/knowledge-chain-e2e.mjs")], env=env, capture_output=True, text=True, timeout=120)
        (evidence / "browser.log").write_text(browser.stdout + browser.stderr)
        check(browser.returncode == 0, "real browser list/graph/navigation/export: " + browser.stdout + browser.stderr)
        # A regular backup is growth and respects pause. Run it while settled;
        # after restore, startup reconciliation may read the current Solr again.
        print("lifecycle: backup",flush=True)
        check(peer.control("backup")[0] == 200, "backup API")
        backed,_=peer.wait("backup",lambda s:s["backup"]["state"]=="idle" and (s["backup"].get("last") or {}).get("result")=="created")
        backup=backed["backup"]["last"]["file"]
        code,_,backup_bytes=peer.call("GET","/scoutro/api/v1/kg/backups/"+backup)
        check(code==200 and backup_bytes.startswith(b"SQLite format 3"),"verified portable backup download")
        (evidence/backup).write_bytes(backup_bytes)
        def archive_rows():
            with sqlite3.connect("file:"+str(peer_root/"DATA/SCOUTRO/knowledge/graph.db")+"?mode=ro",uri=True) as db:
                return list(db.execute("SELECT public_id,quote,observed_at,assertion_status,source_status FROM kg_observation ORDER BY public_id"))
        chosen_rows=archive_rows()
        industry_url=f"http://industry.fixture.test:{server.server_port}/"
        t0=time.monotonic()
        print("lifecycle: confirmed end, incomplete revision, source cleanup",flush=True)
        ended=page("Industrie Werk GmbH",job="Die Stelle ist besetzt.").replace("URL",industry_url)
        peer.push([(industry_url,ended)],"chain-industry")
        peer.wait("confirmed job end",lambda s:peer.settled(s) and peer.kg("/observations/"+observation["id"],collection="chain-industry")["job_search"]["status"]=="ended")
        check(any(i["other"]["id"]==ids["industry"] for i in suggestions("stack")["items"]),"confirmed job end preserves historical matching")
        previous_published=peer.status()["sync"]["processed"]["published"]
        peer.push([(industry_url,'<html><head><title>Incomplete extraction</title><script type="application/ld+json">{bad</script></head><body>Incomplete source</body></html>')],"chain-industry")
        peer.wait("incomplete extraction",lambda s:peer.settled(s) and s["sync"]["processed"]["published"] > previous_published)
        check(peer.kg("/observations/"+observation["id"],collection="chain-industry")["quote"]==observation["quote"],"incomplete extraction preserves original quote")
        peer.transaction_post("IndexControlURLs_p.html",{"urlstring":industry_url,"urldelete":"Delete"})
        check(peer.control("reconcile")[0]==200,"source-loss reconciliation API")
        peer.wait("source cleanup",lambda s:peer.settled(s) and peer.kg("/observations/"+observation["id"],collection="chain-industry")["source"]["status"] in ("removed","gone"))
        check(any(i["other"]["id"]==ids["industry"] for i in suggestions("stack")["items"]),"source deletion does not refute system")
        shutdown_url=industry_url+"shutdown"
        shutdown=page("Industrie Werk GmbH","Wir haben SAP unternehmensweit abgeschaltet.").replace("URL",industry_url)
        peer.push([(shutdown_url,shutdown)],"chain-industry")
        peer.wait("scoped shutdown extraction",lambda s:peer.settled(s) and peer.kg("/observations/"+observation["id"],collection="chain-industry").get("later_system_change") is not None)
        remaining=next(i for i in suggestions("stack")["items"] if i["other"]["id"]==ids["industry"])
        check(all(c.get("context")!="internal_use" for c in remaining["contributions"]),"stale cache rejects affected internal-use contribution")
        check(any(c.get("context")=="required_competence" for c in remaining["contributions"]),"independent competence reason survives shutdown")
        REPORT["timings"]["revision_cleanup_shutdown_s"]=time.monotonic()-t0
        # Controlled mutable correction injection in the disposable DB only;
        # the unchanged original identity and quote remain archived.
        check(peer.control("pause")[0]==200,"pause writes before correction fixture")
        with sqlite3.connect(peer_root/"DATA/SCOUTRO/knowledge/graph.db",timeout=10) as db:
            db.execute("UPDATE kg_observation SET organization_id=? WHERE source_id=?",(ids["consultancy"],observation["source"]["id"]))
            db.execute("DELETE FROM kg_observation_scope WHERE observation_rowid IN(SELECT observation_rowid FROM kg_observation WHERE source_id=?)",(observation["source"]["id"],))
            db.execute("INSERT INTO kg_observation_scope SELECT observation_rowid,(SELECT coll_id FROM kg_collection WHERE name='chain-secret') FROM kg_observation WHERE source_id=?",(observation["source"]["id"],))
        check(not any(i["other"]["id"]==ids["industry"] for i in suggestions("stack")["items"]),"changed employer invalidates cached job chain")
        code,limited=peer.agent(token,f"/kg/entities/{ids['stack']}/suggestions",collection="chain-stack",limit=100)
        check(code==200 and limited["total"]==1 and "chain-secret" not in json.dumps(limited),"current scope correction revokes candidate without count/name leak")
        check(peer.agent(token,"/kg/observations/"+observation["id"],collection="chain-industry")[0]==404,"historical scope no longer authorizes evidence")
        print("lifecycle: exact restore then reconciliation",flush=True)
        check(peer.control("restore",backup=backup)[0]==200,"normal restore API")
        peer.wait("chosen restored state",lambda s:s["state"]=="running")
        restored=peer.kg("/observations/"+observation["id"],collection="chain-industry")
        check(restored["organization"]==ids["industry"],"restore discards newer archive identity/scope correction")
        check(restored["quote"]==observation["quote"],"chosen original archive quote restored")
        check({r[0] for r in archive_rows()}=={r[0] for r in chosen_rows},"restore excludes newer archive revisions before explicit reconciliation")
        check(peer.control("resume")[0]==200 and peer.control("reconcile")[0]==200,"explicit reconciliation after restore")
        peer.wait("post-restore reconciliation",lambda s:peer.settled(s) and peer.kg("/observations/"+observation["id"],collection="chain-industry").get("later_system_change") is not None)
        print("lifecycle: rebuild",flush=True)
        t0=time.monotonic();check(peer.control("rebuild")[0]==200,"rebuild API")
        rebuilt,_=peer.wait("rebuild",lambda s:(s.get("rebuild") or {}).get("phase") in ("done","failed","awaiting_confirmation"),timeout=300)
        check(rebuilt["rebuild"]["phase"]=="done","rebuild completed without incomplete carry")
        peer.wait("post-rebuild reconciliation",lambda s:peer.settled(s))
        check(peer.kg("/observations/"+observation["id"],collection="chain-industry")["quote"]==observation["quote"],"history ID and original quote survive rebuild and reconcile")
        check(any(i["other"]["id"]==ids["industry"] for i in suggestions("stack")["items"]),"independent reason survives rebuild")
        REPORT["timings"]["rebuild_s"]=time.monotonic()-t0
        REPORT["lifecycle"]={"backup":backup,"chosen_observations":len(chosen_rows),"final_observations":len(archive_rows())}
        REPORT["store_status"] = peer.status()["store"]
        REPORT["indexed_documents"] = sum(peer.solr_count(c) for c in sorted({f[0] for f in FIXTURES.values()}))
        REPORT["ingestion"] = {"crawled":2,"served_then_parser_pushed":10}
        check(REPORT["indexed_documents"] == len(FIXTURES), "controlled index has 12 documents after one deletion and one scoped shutdown source")
        REPORT["status"] = "passed"
    except Exception as error:
        REPORT["status"] = "failed"; REPORT["failures"].append(repr(error)); raise
    finally:
        if peer.process: peer.stop()
        server.shutdown(); server.server_close()
        (evidence / "report.json").write_text(json.dumps(REPORT, indent=2, ensure_ascii=False))
        print("Acceptance evidence:", evidence, "checks", len(REPORT["checks"]), flush=True)


if __name__ == "__main__":
    main()
