#!/usr/bin/env python3
"""End-to-end test: Clustro run -> Scoutro research worker -> result with sources.

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Needs two disposable instances (never production):

  * Clustro API (e.g. `node --import tsx apps/api/src/server.ts` with a temp
    DATA_DIR and NODE_ENV=development), CLUSTRO_URL, CLUSTER_ADMIN_KEY
  * Scoutro with the agent pages, SCOUTRO_URL, SCOUTRO_ADMIN_PASSWORD,
    SCOUTRO_DATA_DIR (its DATA directory, to find the worker's runtime secret)
  * E2E_COLLECTION: a collection with indexed pages, E2E_QUERY: a query that
    finds them

Steps: register a Clustro account; create a pull connection (adapter custom,
no endpoint, not primary); create a research worker in the Scoutro wizard
with that connection; post tasks to the connection; run the worker once;
check the runs in Clustro and the worker status in Scoutro.

    python3 test/scoutro-agent/e2e_clustro.py
"""

import json
import os
import re
import secrets
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
sys.path.insert(0, os.path.join(ROOT, "test", "scoutro-api"))
import test_agent_api as scoutro  # noqa: E402  (wizard helpers)

CLUSTRO = os.environ.get("CLUSTRO_URL", "http://127.0.0.1:4000").rstrip("/")
ADMIN_KEY = os.environ["CLUSTER_ADMIN_KEY"]
DATA = os.environ.get("SCOUTRO_DATA_DIR", os.path.join(ROOT, "DATA"))
COLLECTION = os.environ["E2E_COLLECTION"]
QUERY = os.environ.get("E2E_QUERY", "Pflegeheim")
BRIDGE = os.path.join(ROOT, "tools", "scoutro", "agent", "scoutro-agent-bridge")


def clustro(method, path, token, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(CLUSTRO + path, data=data, method=method,
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def mcp(key, tool, args):
    body = json.dumps({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                       "params": {"name": tool, "arguments": args}}).encode()
    req = urllib.request.Request(CLUSTRO + "/mcp", data=body, method="POST", headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream"})
    with urllib.request.urlopen(req, timeout=30) as r:
        text = r.read().decode()
    frame = next(line[5:] for line in text.splitlines() if line.startswith("data:"))
    return json.loads(json.loads(frame)["result"]["content"][0]["text"])


def check(cond, message):
    print(("ok   " if cond else "FAIL ") + message)
    if not cond:
        sys.exit(1)


def main():
    # --- Clustro: account, workspace, pull connection --------------------------------
    email = "scoutro-e2e-%s@example.com" % secrets.token_hex(4)
    req = urllib.request.Request(CLUSTRO + "/auth/register", method="POST",
                                 data=json.dumps({"email": email, "password": secrets.token_hex(12)}).encode(),
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            reg = json.loads(r.read())
        session, ws = reg["session"]["token"], reg["workspaceId"]
    except urllib.error.HTTPError:
        # registration closed: use the admin key and a fresh workspace
        session, ws = ADMIN_KEY, "scoutro-e2e-" + secrets.token_hex(3)
        status, _ = clustro("POST", "/workspaces", ADMIN_KEY, {"id": ws})
        check(status == 201, "workspace %s created with the admin key" % ws)
    status, conn = clustro("POST", "/workspaces/%s/agent-connections" % ws, session,
                           {"name": "Scoutro research worker (e2e)", "adapterId": "custom", "isPrimary": False,
                            "capabilities": ["research", "source_evidence", "portal_classification"]})
    check(status == 201 and conn["rawKey"].startswith("ak_"), "Clustro pull connection created (no endpoint, not primary)")
    cid, key = conn["connection"]["id"], conn["rawKey"]

    # --- Scoutro: research worker via the wizard ---------------------------------------
    w = scoutro.Wizard()
    w.post(1, {"name": "e2e worker " + scoutro.RUN, "kind": "research_worker"})
    w.post(2, {"scopeForm": "1", "extraCollections": COLLECTION})
    w.post(3, {"actionsForm": "1", "preset": "research"})
    w.post(4, {"limitsForm": "1", "domains": "", "maxDepth": "1", "maxPages": "20", "maxParallelCrawls": "1",
               "requestsPerMinute": "120", "maxTaskSeconds": "120"})
    page = w.post(5, {"expiresInDays": "30", "clustroBaseUrl": CLUSTRO, "clustroWorkspaceId": ws,
                      "clustroConnectionId": cid, "clustroAgentKey": key})
    m = re.search(r"ScoutroAgents_p\.html\?agent=(agt_[a-z2-7]{12})", page)
    check(m is not None, "Scoutro research worker created in the wizard")
    agent_id = m.group(1)
    check(key not in page, "the Clustro key is not echoed by Scoutro")

    # --- tasks ------------------------------------------------------------------------
    runs = {}
    for name, task in (("research", QUERY),
                       ("structured", json.dumps({"scoutro_task": "1", "type": "research", "query": QUERY,
                                                  "limit": 3, "summarize": False})),
                       ("foreign", json.dumps({"scoutro_task": "1", "type": "research", "query": QUERY,
                                               "collections": ["not-granted"]})),
                       ("invalid", '{"scoutro_task": "1", "type": "crawl"}')):
        status, body = clustro("POST", "/workspaces/%s/agent-connections/%s/runs" % (ws, cid), session, {"task": task})
        check(status == 201 and body["run"]["status"] == "created", "run '%s' created for the worker" % name)
        runs[name] = body["run"]["id"]

    # --- worker -----------------------------------------------------------------------
    env = dict(os.environ, SCOUTRO_AGENT_SECRETS_DIR=os.path.join(DATA, "SETTINGS", "agent-runtime"),
               SCOUTRO_AGENT_URL=scoutro.BASE + "/scoutro/api/agent/v1",
               SCOUTRO_AGENT_STATE_DIR=tempfile.mkdtemp())
    for k in ("SCOUTRO_LLM_BASE_URL", "SCOUTRO_LLM_MODEL"):
        env.pop(k, None)
    proc = subprocess.run([sys.executable, BRIDGE, "--agent", agent_id, "--once"], env=env,
                          capture_output=True, text=True, timeout=300)
    print(proc.stderr.strip())
    check(proc.returncode == 0, "worker run finished")
    check(key not in proc.stderr, "the worker log does not contain the Clustro key")

    # --- results in Clustro -----------------------------------------------------------
    run = mcp(key, "get_run", {"workspaceId": ws, "runId": runs["research"]})
    check(run["status"] == "completed", "research run completed in Clustro")
    out = json.loads(run["output"])
    check(out["status"] == "ok" and out["sources"], "result has sources (%d)" % len(out.get("sources", [])))
    check(all(s.get("collection") == COLLECTION for s in out["sources"]), "all sources come from the granted collection")
    check(out["summaryStatus"] == "model_not_allowed", "no model: summaryStatus=model_not_allowed, not an error")
    run = mcp(key, "get_run", {"workspaceId": ws, "runId": runs["structured"]})
    check(run["status"] == "completed" and len(json.loads(run["output"])["sources"]) <= 3, "structured task respected limit")
    run = mcp(key, "get_run", {"workspaceId": ws, "runId": runs["foreign"]})
    check(run["status"] == "failed" and "outside" in json.dumps(run.get("error")), "foreign collection refused")
    run = mcp(key, "get_run", {"workspaceId": ws, "runId": runs["invalid"]})
    check(run["status"] == "failed" and "invalid task" in json.dumps(run.get("error")), "invalid task failed with a clear message")

    # --- worker status in Scoutro -----------------------------------------------------
    status, _, page = scoutro.http("GET", scoutro.BASE + "/ScoutroAgents_p.html?agent=" + agent_id,
                                   opener=scoutro.admin_opener())
    check("yes, confirmed by the worker within 5 minutes" in page, "Agents & Access shows Clustro reachable (from the worker)")
    check("<code>connected</code>" in page, "Agents & Access shows the worker connected")
    print("end-to-end: OK")


if __name__ == "__main__":
    main()
