#!/usr/bin/env python3
"""Tests of the Scoutro research worker (tools/scoutro/agent/scoutro-agent-bridge).

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Runs offline: fake Clustro (MCP over Streamable HTTP with SSE frames, webhook),
fake Scoutro agent API and a fake OpenAI-compatible model run in-process.

    python3 test/scoutro-agent/test_bridge.py -v
"""

import importlib.machinery
import importlib.util
import json
import os
import re
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
BRIDGE = os.path.join(ROOT, "tools", "scoutro", "agent", "scoutro-agent-bridge")
_loader = importlib.machinery.SourceFileLoader("scoutro_agent_bridge", BRIDGE)
_spec = importlib.util.spec_from_loader("scoutro_agent_bridge", _loader)
bridge = importlib.util.module_from_spec(_spec)
_loader.exec_module(bridge)
sc = bridge.sc

KEY = "ak_" + "1" * 64
TOKEN = "sca_" + "a" * 16 + "." + "b" * 43


class Server:
    """A tiny HTTP server in a thread; handler(method, path, query, headers, body) -> (status, headers, text)."""

    def __init__(self, handler):
        outer = self

        class H(BaseHTTPRequestHandler):
            def log_message(self, *a):
                pass

            def _do(self):
                u = urllib.parse.urlsplit(self.path)
                n = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(n).decode("utf-8") if n else ""
                status, headers, text = handler(self.command, u.path, dict(urllib.parse.parse_qsl(u.query)),
                                                self.headers, body)
                data = text.encode("utf-8")
                self.send_response(status)
                for k, v in (headers or {}).items():
                    self.send_header(k, v)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = do_PATCH = _do

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.url = "http://127.0.0.1:%d" % self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        outer.calls = []

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


class FakeClustro:
    def __init__(self):
        self.runs = {}
        self.calls = []
        self.webhooks = []
        self.down = False
        self.server = Server(self.handle)

    def add(self, run_id, content, status="created"):
        self.runs[run_id] = {"id": run_id, "input": content, "status": status, "agentConnectionId": "conn1"}

    def handle(self, method, path, query, headers, body):
        if self.down:
            return 503, {}, "down"
        if headers.get("Authorization") != "Bearer " + KEY:
            return 401, {"WWW-Authenticate": "Bearer"}, "{}"
        if path == "/webhook":
            msg = json.loads(body)
            self.webhooks.append(msg)
            if msg["event"] == "run_started" and self.runs[msg["runId"]]["status"] == "created":
                self.runs[msg["runId"]]["status"] = "active"
            return 200, {}, "{}"
        if path != "/mcp" or method != "POST":
            return 405, {}, ""
        if "text/event-stream" not in headers.get("Accept", "") or "application/json" not in headers.get("Accept", ""):
            return 406, {}, ""
        rpc = json.loads(body)
        name = rpc["params"]["name"]
        args = rpc["params"]["arguments"]
        self.calls.append((name, args))
        assert args["workspaceId"] == "ws1"
        error = False
        if name == "list_runs":
            out = [r for r in self.runs.values() if r["status"] == args.get("status")]
        elif name == "get_run_status":
            out = {"runId": args["runId"], "status": self.runs[args["runId"]]["status"]}
        elif name == "complete_run":
            r = self.runs[args["runId"]]
            if r["status"] in ("cancelled", "failed"):
                out, error = "run is " + r["status"], True
            else:
                r["status"], r["output"] = "completed", args["output"]
                out = r
        elif name == "fail_run":
            r = self.runs[args["runId"]]
            r["status"], r["error"] = "failed", args["error"]
            out = r
        else:
            out, error = "unknown tool", True
        result = {"content": [{"type": "text", "text": json.dumps(out) if not isinstance(out, str) else out}],
                  "isError": error}
        frame = "event: message\ndata: " + json.dumps({"jsonrpc": "2.0", "id": rpc["id"], "result": result}) + "\n\n"
        return 200, {"Content-Type": "text/event-stream"}, frame

    def tools(self, name):
        return [a for n, a in self.calls if n == name]


class FakeScoutro:
    def __init__(self, collections=("edelsenior-web",), actions=("search", "index.evidence"), model=False):
        self.caps = {"agent": {"id": "agt_aaaaaaaaaaaa", "kind": "research_worker"},
                     "scope": {"collections": list(collections), "allCollections": False},
                     "limits": {"maxTaskSeconds": 60, "modelAllowed": model},
                     "actions": [{"name": a} for a in actions]}
        self.results = {}       # collection -> list of (url, title, snippet)
        self.evidence = {}      # domain -> list of (url, title, excerpt)
        self.calls = []
        self.heartbeats = []
        self.crawls = {}
        self.crawl_keys = {}
        self.crawl_states = []  # states returned by successive status calls
        self.down = False
        self.server = Server(self.handle)

    def handle(self, method, path, query, headers, body):
        if self.down:
            return 503, {}, "{}"
        if path.startswith(bridge.AGENT_PATH):   # the worker as configured (SCOUTRO_AGENT_URL)
            path = path[len(bridge.AGENT_PATH):]
        if headers.get("Authorization") != "Bearer " + TOKEN:
            return 401, {}, json.dumps({"error": {"code": "invalid_token", "message": "x"}})
        self.calls.append((method, path, query, dict(headers)))
        col = query.get("collection")
        if col and col not in self.caps["scope"]["collections"]:
            return 403, {}, json.dumps({"error": {"code": "collection_not_in_scope", "message": col}})
        if path == "/capabilities":
            return 200, {}, json.dumps(self.caps)
        if path == "/heartbeat":
            self.heartbeats.append(json.loads(body))
            return 200, {}, json.dumps({"status": "ok"})
        if path == "/search":
            items = [{"url": u, "title": t, "snippet": s} for u, t, s in self.results.get(col, [])]
            return 200, {}, json.dumps({"results": items, "total": len(items)})
        if path == "/index/evidence":
            docs = [{"url": u, "title": t, "excerpt": e} for u, t, e in self.evidence.get(query["domain"], [])]
            return 200, {}, json.dumps({"documents": docs, "total": len(docs)})
        if path == "/crawls" and method == "POST":
            key = headers.get("Idempotency-Key")
            if key in self.crawl_keys:
                return 200, {}, json.dumps(dict(self.crawls[self.crawl_keys[key]], idempotentReplay=True))
            cid = "crawl%d" % (len(self.crawls) + 1)
            b = json.loads(body)
            self.crawls[cid] = {"id": cid, "state": "running", "host": urllib.parse.urlsplit(b["url"]).hostname,
                                "collection": b["collection"]}
            self.crawl_keys[key] = cid
            return 201, {}, json.dumps(self.crawls[cid])
        m = re.match(r"^/crawls/([^/]+)(/stop)?$", path)
        if m:
            c = self.crawls[m.group(1)]
            if m.group(2):
                c["state"] = "stopped"
                return 200, {}, json.dumps({"id": c["id"], "state": "stopped"})
            if self.crawl_states:
                c["state"] = self.crawl_states.pop(0)
            return 200, {}, json.dumps(c)
        return 404, {}, json.dumps({"error": {"code": "not_found", "message": path}})

    def count(self, path):
        return len([c for c in self.calls if c[1] == path])


class FakeModel:
    def __init__(self, answer):
        self.answer = answer
        self.requests = []
        self.server = Server(self.handle)

    def handle(self, method, path, query, headers, body):
        self.requests.append(json.loads(body))
        content = self.answer if isinstance(self.answer, str) else json.dumps(self.answer)
        return 200, {}, json.dumps({"choices": [{"message": {"content": content}}]})


class BridgeTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.mkdtemp()
        self.clustro = FakeClustro()
        self.scoutro = None
        self.servers = [self.clustro.server]

    def tearDown(self):
        for s in self.servers:
            s.close()
        shutil.rmtree(self.tmp, ignore_errors=True)

    def worker(self, scoutro=None, model=None, model_answer=None, discovery_dir=None):
        self.scoutro = scoutro or FakeScoutro()
        self.servers.append(self.scoutro.server)
        llm = sc.LlmConfig()
        if model_answer is not None:
            fm = FakeModel(model_answer)
            self.model = fm
            self.servers.append(fm.server)
            llm = sc.LlmConfig(base_url=fm.server.url, model="test-model", retries=0, timeout=5)
        elif model:
            llm = model
        return bridge.Worker(bridge.Scoutro(self.scoutro.server.url, TOKEN),
                             bridge.Clustro(self.clustro.server.url, KEY, "ws1", "conn1"),
                             bridge.Journal(os.path.join(self.tmp, "state")), llm,
                             discovery_dir or os.path.join(self.tmp, "discovery"))

    def output(self, run_id):
        run = self.clustro.runs[run_id]
        self.assertEqual(run["status"], "completed", run.get("error"))
        out = json.loads(run["output"])
        self.assertEqual(sc.validate(out, bridge.load_schema("result.schema.json")), [])
        return out

    # --- research -----------------------------------------------------------

    def seed(self, s):
        s.results["edelsenior-web"] = [("https://sonnenhof.example/", "Pflegeheim Sonnenhof", "Pflege in Musterstadt"),
                                       ("https://other.example/a", "Other", "Kurzzeitpflege")]
        s.evidence["sonnenhof.example"] = [("https://sonnenhof.example/", "Pflegeheim Sonnenhof",
                                            "Vollstationäre Pflege und Kurzzeitpflege in Musterstadt.")]

    def test_free_text_research_with_sources_without_model(self):
        s = FakeScoutro()
        self.seed(s)
        w = self.worker(s)
        self.clustro.add("run1", "Welche Pflegeheime gibt es in Musterstadt?")
        self.assertEqual(w.poll_once(), 1)
        out = self.output("run1")
        self.assertEqual(out["status"], "ok")
        self.assertEqual(out["summaryStatus"], "model_not_allowed")
        self.assertEqual([x["url"] for x in out["sources"]], ["https://sonnenhof.example/", "https://other.example/a"])
        self.assertIn("Vollstationäre", out["sources"][0]["excerpt"])  # excerpt from index evidence
        self.assertEqual(out["sources"][0]["collection"], "edelsenior-web")
        self.assertEqual(self.clustro.webhooks[0], {"event": "run_started", "runId": "run1"})
        self.assertTrue(s.heartbeats[-1]["clustroReachable"])
        self.assertEqual(s.heartbeats[-1]["activeRuns"], 0)
        # every Scoutro call used the scope collection explicitly
        for method, path, query, _ in s.calls:
            if path in ("/search", "/index/evidence"):
                self.assertEqual(query.get("collection"), "edelsenior-web")

    def test_summary_with_source_references(self):
        s = FakeScoutro(model=True)
        self.seed(s)
        answer = {"statements": [{"text": "Sonnenhof bietet Kurzzeitpflege.", "sources": [1]},
                                 {"text": "Erfundene Aussage.", "sources": [9]}]}
        w = self.worker(s, model_answer=answer)
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "research", "query": "Kurzzeitpflege"}))
        w.poll_once()
        out = self.output("run1")
        self.assertEqual(out["summaryStatus"], "generated")
        self.assertEqual(out["summary"], "Sonnenhof bietet Kurzzeitpflege. [1]")
        self.assertTrue(any("dropped" in x for x in out["warnings"]))
        # page text reaches the model only as delimited data, never as the system prompt
        msgs = self.model.requests[0]["messages"]
        self.assertNotIn("Vollstationäre", msgs[0]["content"])
        self.assertIn("Vollstationäre", msgs[1]["content"])

    def test_invalid_model_output_keeps_the_sources(self):
        s = FakeScoutro(model=True)
        self.seed(s)
        w = self.worker(s, model_answer="Sure! Here is my answer: Sonnenhof.")
        self.clustro.add("run1", "Kurzzeitpflege")
        w.poll_once()
        out = self.output("run1")
        self.assertEqual(out["status"], "partial")
        self.assertEqual(out["summaryStatus"], "invalid_model_output")
        self.assertEqual(len(out["sources"]), 2)

    def test_model_allowed_but_not_configured(self):
        s = FakeScoutro(model=True)
        self.seed(s)
        w = self.worker(s)
        self.clustro.add("run1", "Kurzzeitpflege")
        w.poll_once()
        out = self.output("run1")
        self.assertEqual((out["status"], out["summaryStatus"]), ("ok", "model_not_configured"))

    def test_no_indexed_text(self):
        w = self.worker()
        self.clustro.add("run1", "nichts")
        w.poll_once()
        out = self.output("run1")
        self.assertEqual(out["status"], "no_evidence")
        self.assertEqual(out["sources"], [])

    def test_repeated_listing_is_not_new_work(self):
        s = FakeScoutro()
        self.seed(s)
        w = self.worker(s)
        self.clustro.add("run1", "Pflege")
        w.poll_once()
        searches = s.count("/search")
        self.clustro.runs["run1"]["status"] = "created"   # at-least-once: the run shows up again
        self.assertEqual(w.poll_once(), 0)
        self.assertEqual(s.count("/search"), searches)
        self.assertEqual(len(self.clustro.tools("complete_run")), 1)

    def test_invalid_task_and_scope_errors_fail_the_run(self):
        w = self.worker()
        self.clustro.add("bad", '{"scoutro_task": "1", "type": "research"}')
        self.clustro.add("foreign", json.dumps({"scoutro_task": "1", "type": "research", "query": "x",
                                                "collections": ["checkthecoach-web"]}))
        self.clustro.add("modifier", "pflege collection:checkthecoach-web")
        self.clustro.add("unknown", '{"scoutro_task": "1", "type": "delete_index"}')
        w.poll_once()
        self.assertIn("missing query", self.clustro.runs["bad"]["error"])
        self.assertIn("outside the worker's data scope", self.clustro.runs["foreign"]["error"])
        self.assertIn("collection:", self.clustro.runs["modifier"]["error"])
        self.assertEqual(self.clustro.runs["unknown"]["status"], "failed")

    def test_scoutro_refusal_is_reported_understandably(self):
        s = FakeScoutro(actions=("index.evidence",))
        w = self.worker(s)
        self.clustro.add("run1", "Pflege")
        w.poll_once()
        self.assertEqual(self.clustro.runs["run1"]["status"], "failed")
        self.assertIn("no grant for 'search'", self.clustro.runs["run1"]["error"])

    def test_unreachable_services_keep_runs_waiting(self):
        s = FakeScoutro()
        w = self.worker(s)
        self.clustro.add("run1", "Pflege")
        self.clustro.down = True
        self.assertEqual(w.poll_once(), 0)
        self.assertFalse(s.heartbeats[-1]["clustroReachable"])
        self.assertIn("clustro", s.heartbeats[-1]["lastError"])
        self.clustro.down = False
        s.down = True
        self.assertEqual(w.poll_once(), 0)
        self.assertEqual(self.clustro.runs["run1"]["status"], "created")

    def test_cancelled_run_is_not_completed(self):
        s = FakeScoutro()
        self.seed(s)
        w = self.worker(s)
        self.clustro.add("run1", "Pflege")
        original = w.research

        def research_then_cancel(task):
            result = original(task)
            self.clustro.runs["run1"]["status"] = "cancelled"
            return result
        w.research = research_then_cancel
        w.poll_once()
        self.assertEqual(self.clustro.tools("complete_run"), [])
        self.assertEqual(w.journal.get("run1")["state"], "skipped")

    def test_output_budget(self):
        s = FakeScoutro()
        s.results["edelsenior-web"] = [("https://x%d.example/" % i, "T" * 300, "S" * 4000) for i in range(20)]
        w = self.worker(s)
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "research", "query": "x", "limit": 20,
                                             "maxChars": 4000, "summarize": False}))
        w.poll_once()
        raw = self.clustro.runs["run1"]["output"]
        self.assertLessEqual(len(raw), bridge.RESULT_BUDGET)
        out = self.output("run1")
        self.assertTrue(out["truncated"])

    # --- restart ------------------------------------------------------------

    def test_restart_resumes_crawl_without_second_start(self):
        s = FakeScoutro(actions=("search", "crawl.start", "crawl.status", "crawl.stop"))
        s.crawl_states = ["terminated"]
        w = self.worker(s)
        task = {"scoutro_task": "1", "type": "crawl", "url": "https://sonnenhof.example/", "collection": "edelsenior-web",
                "waitSeconds": 1}
        self.clustro.add("run1", json.dumps(task))
        # first attempt: the worker dies right after starting the crawl
        original = w.scoutro.call

        def die_after_start(method, path, query=None, body=None, headers=None):
            result = original(method, path, query, body, headers)
            if method == "POST" and path == "/crawls":
                raise KeyboardInterrupt
            return result
        w.scoutro.call = die_after_start
        with self.assertRaises(KeyboardInterrupt):
            w.poll_once()
        self.assertEqual(w.journal.get("run1")["state"], "started")
        # restart: a new worker with the same journal finds the active run and resumes it
        w2 = bridge.Worker(bridge.Scoutro(s.server.url, TOKEN), bridge.Clustro(self.clustro.server.url, KEY, "ws1", "conn1"),
                           bridge.Journal(os.path.join(self.tmp, "state")), sc.LlmConfig(), self.tmp)
        self.assertEqual(self.clustro.runs["run1"]["status"], "active")
        self.assertEqual(w2.poll_once(), 1)
        self.assertEqual(len(s.crawls), 1, "the restart must not start a second crawl")
        keys = [c[3].get("Idempotency-Key") for c in s.calls if c[0] == "POST" and c[1] == "/crawls"]
        self.assertEqual(keys, ["clustro-run1", "clustro-run1"])
        out = self.output("run1")
        self.assertEqual(out["crawl"]["state"], "terminated")
        self.assertTrue(out["crawl"]["idempotentReplay"])

    def test_too_many_restarts_fail_the_run(self):
        w = self.worker()
        self.clustro.add("run1", "Pflege", status="active")
        w.journal.put("run1", state="started", attempts=bridge.MAX_ATTEMPTS)
        w.poll_once()
        self.assertEqual(self.clustro.runs["run1"]["status"], "failed")
        self.assertIn("restarted", self.clustro.runs["run1"]["error"])

    def test_foreign_active_runs_are_not_touched(self):
        w = self.worker()
        self.clustro.add("someone-else", "Pflege", status="active")
        self.assertEqual(w.poll_once(), 0)
        self.assertEqual(self.clustro.runs["someone-else"]["status"], "active")

    def test_cancel_stops_own_crawl(self):
        s = FakeScoutro(actions=("crawl.start", "crawl.status", "crawl.stop"))
        s.crawl_states = ["running", "running", "running"]
        w = self.worker(s)
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "crawl", "url": "https://a.example/",
                                             "collection": "edelsenior-web", "waitSeconds": 5}))
        original = w.clustro.run_status
        n = {"i": 0}

        def cancel_on_second_check(run_id):
            n["i"] += 1
            if n["i"] >= 2:
                self.clustro.runs[run_id]["status"] = "cancelled"
            return original(run_id)
        w.clustro.run_status = cancel_on_second_check
        w.poll_once()
        self.assertEqual(s.crawls["crawl1"]["state"], "stopped")
        self.assertEqual(self.clustro.tools("complete_run"), [])

    # --- portal evaluation --------------------------------------------------

    def stored_state(self, domain, verdict):
        rec = sc.make_record("edelsenior", "edelsenior-web", domain, verdict, 0.9, "care_facility", "DE", "",
                             [sc._reason("test", "stored")], [], "heuristic", "", "2026-09-30.1")
        d = os.path.join(self.tmp, "discovery")
        os.makedirs(d, exist_ok=True)
        with open(os.path.join(d, "state.json"), "w", encoding="utf-8") as f:
            json.dump({"state_version": 2, "paused": False, "profiles": {},
                       "domains": {domain: {"classifications": {"edelsenior": rec}}}}, f)
        return d

    def test_portal_evaluation_from_stored_classifications(self):
        d = self.stored_state("sonnenhof.example", "PASS")
        w = self.worker(FakeScoutro(collections=("edelsenior-web", "prospect-edelsenior")), discovery_dir=d)
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior",
                                             "domains": ["sonnenhof.example", "unknown.example"]}))
        w.poll_once()
        out = self.output("run1")
        verdicts = {c["domain"]: (c["verdict"], c["source"]) for c in out["classifications"]}
        self.assertEqual(verdicts, {"sonnenhof.example": ("PASS", "stored"), "unknown.example": ("UNCLASSIFIED", "none")})
        self.assertEqual(out["status"], "ok")

    def test_classification_without_model_reports_model_unavailable(self):
        d = self.stored_state("sonnenhof.example", "UNSURE")
        w = self.worker(FakeScoutro(model=True), discovery_dir=d)
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior",
                                             "domains": ["sonnenhof.example"], "mode": "classify"}))
        w.poll_once()
        out = self.output("run1")
        self.assertEqual(out["status"], "model_unavailable")
        self.assertEqual(out["classifications"][0]["verdict"], "UNSURE")

    def test_classification_with_model_and_invalid_output_is_unsure(self):
        s = FakeScoutro(model=True)
        s.evidence["sonnenhof.example"] = [("https://sonnenhof.example/", "Pflegeheim Sonnenhof",
                                            "Vollstationäre Pflege und Kurzzeitpflege. Impressum, Kontakt.")]
        w = self.worker(s, model_answer="not json at all")
        w.llm.response_format = "none"
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior",
                                             "domains": ["sonnenhof.example", "empty.example"], "mode": "classify"}))
        w.poll_once()
        out = self.output("run1")
        by = {c["domain"]: c for c in out["classifications"]}
        self.assertEqual(by["sonnenhof.example"]["verdict"], "UNSURE")
        self.assertEqual(by["sonnenhof.example"]["record"]["classifier"]["error"], "invalid_model_output")
        self.assertEqual(by["empty.example"]["verdict"], "UNSURE")   # no evidence is never guessed
        self.assertEqual(out["status"], "partial")

    def test_profile_outside_scope_fails(self):
        w = self.worker(FakeScoutro(collections=("checkthecoach-web",)))
        self.clustro.add("run1", json.dumps({"scoutro_task": "1", "type": "portal_evaluation", "profile": "edelsenior",
                                             "domains": ["a.example"]}))
        w.poll_once()
        self.assertIn("outside the worker's data scope", self.clustro.runs["run1"]["error"])

    def test_agent_url_is_required_and_checked(self):
        """No silent default: without SCOUTRO_AGENT_URL the worker refuses to start and says what to set,
        before it takes the connection lock."""
        path = os.path.join(self.tmp, "agt_aaaaaaaaaaaa.secret")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"scoutroToken": TOKEN, "clustroBaseUrl": "https://c.example", "clustroWorkspaceId": "ws1",
                       "clustroConnectionId": "conn1", "clustroAgentKey": KEY}, f)
        args = type("A", (), {"secret_file": path, "agent": None})()
        locks = os.path.join(self.tmp, "locks")
        for value, needle in ((None, "is not set"), ("", "is not set"),
                              ("ftp://127.0.0.1/scoutro/api/agent/v1", "not a plain http(s) URL"),
                              ("http://user:pw@127.0.0.1/scoutro/api/agent/v1", "not a plain http(s) URL"),
                              ("http://127.0.0.1:8090/scoutro/api/agent/v1?x=1", "not a plain http(s) URL"),
                              ("http://127.0.0.1:8090/", "does not end with /scoutro/api/agent/v1"),
                              ("http://127.0.0.1:8090/scoutro/api/v1", "does not end with /scoutro/api/agent/v1")):
            env = {"SCOUTRO_AGENT_LOCK_DIR": locks}
            if value is not None:
                env["SCOUTRO_AGENT_URL"] = value
            with self.assertRaises(SystemExit) as cm:
                bridge.build_worker(args, environ=env)
            self.assertIn(needle, str(cm.exception), value)
            self.assertIn("http://127.0.0.1:8090/scoutro/api/agent/v1", str(cm.exception))
            self.assertNotIn("8091", str(cm.exception))
        self.assertFalse(os.path.isdir(locks) and os.listdir(locks), "no lock taken for a refused configuration")

    def test_configuration_from_runtime_secret(self):
        path = os.path.join(self.tmp, "agt_aaaaaaaaaaaa.secret")
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"scoutroToken": TOKEN, "clustroBaseUrl": "https://c.example", "clustroWorkspaceId": "ws1",
                       "clustroConnectionId": "conn1", "clustroAgentKey": KEY}, f)
        args = type("A", (), {"secret_file": path, "agent": None})()
        locks = os.path.join(self.tmp, "locks")
        url = "http://127.0.0.1:9/scoutro/api/agent/v1"
        w = bridge.build_worker(args, environ={"SCOUTRO_AGENT_URL": url + "/", "SCOUTRO_AGENT_LOCK_DIR": locks})
        self.assertTrue(w.lock.path.startswith(locks))
        w.lock.release()
        self.assertEqual(w.scoutro.base, url)
        self.assertEqual(w.clustro.workspace, "ws1")
        self.assertFalse(w.llm.configured)
        with open(path, "w", encoding="utf-8") as f:
            json.dump({"scoutroToken": TOKEN}, f)
        with self.assertRaises(SystemExit):
            bridge.build_worker(args, environ={"SCOUTRO_AGENT_LOCK_DIR": locks})


if __name__ == "__main__":
    unittest.main()
