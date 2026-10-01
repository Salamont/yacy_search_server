#!/usr/bin/env python3
"""Scoutro agent access tests (end to end, against a disposable instance).

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Drives the administrator wizard "Agents & Access" with the admin account
(HTTP Digest), takes the token from the one-time result page and checks the
agent path /scoutro/api/agent/v1 with it:

    SCOUTRO_URL=http://127.0.0.1:8090 \
    SCOUTRO_ADMIN_USER=admin SCOUTRO_ADMIN_PASSWORD=... \
    SCOUTRO_TEST_CRAWL_URL=http://<local test site>/index.html \
    python3 test/scoutro-api/test_agent_api.py -v

Never run it against production data: it creates agents (and, with
SCOUTRO_TEST_CRAWL_URL, a small crawl of the given disposable local site).
Only the Python standard library is required.
"""

import html
import json
import os
import re
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("SCOUTRO_URL", "http://127.0.0.1:8090").rstrip("/")
AGENT = BASE + "/scoutro/api/agent/v1"
USER = os.environ.get("SCOUTRO_ADMIN_USER", "admin")
PASSWORD = os.environ.get("SCOUTRO_ADMIN_PASSWORD", "yacy")
CRAWL_URL = os.environ.get("SCOUTRO_TEST_CRAWL_URL")
RUN = str(int(time.time()))
COLLECTION = "agenttest-" + RUN
OTHER = "agentother-" + RUN


def admin_opener():
    manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
    manager.add_password(None, BASE, USER, PASSWORD)
    return urllib.request.build_opener(urllib.request.HTTPDigestAuthHandler(manager))


def http(method, url, data=None, headers=None, opener=None):
    """Return (status, headers, text)."""
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with (opener or urllib.request.build_opener()).open(request, timeout=90) as response:
            return response.status, response.headers, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.headers, e.read().decode("utf-8", "replace")


def agent_call(token, method, path, query=None, body=None, headers=None):
    url = AGENT + path + ("?" + urllib.parse.urlencode(query) if query else "")
    hdrs = {"Accept": "application/json"}
    if token is not None:
        hdrs["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    hdrs.update(headers or {})
    status, h, text = http(method, url, data, hdrs)
    try:
        return status, json.loads(text), h
    except ValueError:
        return status, None, h


def error_code(payload):
    return (payload or {}).get("error", {}).get("code")


class Wizard:
    """Walks through ScoutroAgentWizard_p.html like a browser."""

    def __init__(self):
        self.opener = admin_opener()
        status, headers, text = http("GET", BASE + "/ScoutroAgentWizard_p.html", opener=self.opener)
        assert status == 200, status
        self.token = headers.get("X-YaCy-Transaction-Token")
        self.draft = ""
        self.page = text
        self.headers = headers

    def post(self, step, fields):
        form = {"transactionToken": self.token, "draft": self.draft, "step": str(step), "next": "Next"}
        form.update(fields)
        data = urllib.parse.urlencode(form).encode("utf-8")
        status, headers, text = http("POST", BASE + "/ScoutroAgentWizard_p.html", data,
                                     {"Content-Type": "application/x-www-form-urlencoded"}, self.opener)
        assert status == 200, status
        m = re.search(r'name="draft" value="([^"]*)"', text)
        self.draft = html.unescape(m.group(1)) if m else ""
        self.token = headers.get("X-YaCy-Transaction-Token", self.token)
        self.page = text
        self.headers = headers
        return text


def create_agent(name, kind="external", collections=(COLLECTION,), preset="research", extra_actions=(),
                 domains="", max_pages="20", all_collections=False):
    w = Wizard()
    w.post(1, {"name": name, "description": "end-to-end test", "kind": kind})
    scope = {"scopeForm": "1", "extraCollections": ",".join(collections)}
    if all_collections:
        scope.update({"allCollections": "on", "confirmAllCollections": "on"})
    w.post(2, scope)
    w.post(3, dict({"actionsForm": "1", "preset": preset}, **{"act_" + a: "on" for a in extra_actions}))
    w.post(4, {"limitsForm": "1", "domains": domains, "maxDepth": "1", "maxPages": max_pages,
               "maxParallelCrawls": "1", "requestsPerMinute": "120", "maxTaskSeconds": "300"})
    page = w.post(5, {"expiresInDays": "30"})
    m = re.search(r'id="createdToken" readonly="readonly" size="70" value="([^"]+)"', page)
    assert m, "token not shown on the result page:\n" + page[-3000:]
    agent_id = re.search(r"ScoutroAgents_p\.html\?agent=(agt_[a-z2-7]{12})", page).group(1)
    return html.unescape(m.group(1)), agent_id, w


class AgentAccess(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.token, cls.agent_id, cls.wizard = create_agent("e2e research " + RUN)

    def test_token_page_is_not_cached(self):
        cache = self.wizard.headers.get("Cache-Control", "")
        self.assertIn("no-store", cache)
        self.assertEqual(self.wizard.headers.get("Referrer-Policy"), "no-referrer")
        self.assertRegex(self.token, r"^sca_[a-z2-7]{16}\.[A-Za-z0-9_-]{43}$")

    def test_token_is_shown_only_once(self):
        status, _, text = http("GET", BASE + "/ScoutroAgents_p.html?agent=" + self.agent_id, opener=admin_opener())
        self.assertEqual(status, 200)
        self.assertNotIn(self.token, text)
        self.assertIn(self.token.split(".")[0][4:], text)  # the public id is listed

    def test_capabilities(self):
        status, data, _ = agent_call(self.token, "GET", "/capabilities")
        self.assertEqual(status, 200, data)
        names = [a["name"] for a in data["actions"]]
        self.assertEqual(names, ["search", "index.evidence", "index.lookup", "index.status"])
        self.assertEqual(data["scope"]["collections"], [COLLECTION])
        status, _, page = http("GET", BASE + "/ScoutroAgents_p.html?agent=" + self.agent_id, opener=admin_opener())
        self.assertIn("<code>connected</code>", page)

    def test_missing_wrong_and_admin_credentials(self):
        status, data, headers = agent_call(None, "GET", "/capabilities")
        self.assertEqual(status, 401)
        self.assertEqual(error_code(data), "missing_bearer")
        self.assertTrue(headers.get("WWW-Authenticate", "").startswith("Bearer"))
        status, data, _ = agent_call(None, "GET", "/capabilities", headers={"Authorization": "Basic YWRtaW46eWFjeQ=="})
        self.assertEqual(error_code(data), "bearer_required")
        status, data, _ = agent_call(self.token[:-2] + "xx", "GET", "/capabilities")
        self.assertEqual(status, 401)
        self.assertEqual(error_code(data), "invalid_token")
        status, data, _ = agent_call(None, "GET", "/capabilities", query={"access_token": self.token})
        self.assertEqual(error_code(data), "token_in_url")

    def test_agent_token_is_not_an_admin_credential(self):
        for path in ("/scoutro/api/v1/search?q=x", "/scoutro/api/v1/config", "/Settings_p.html"):
            status, _, _ = http("GET", BASE + path, headers={"Authorization": "Bearer " + self.token})
            self.assertEqual(status, 401, path)

    def test_scope_enforcement(self):
        status, data, _ = agent_call(self.token, "GET", "/search", {"q": "test"})
        self.assertEqual(status, 200, data)
        self.assertEqual(data["collections"], [COLLECTION])
        status, data, _ = agent_call(self.token, "GET", "/search", {"q": "collection:" + OTHER + " test"})
        self.assertEqual((status, error_code(data)), (400, "query_modifier_not_allowed"))
        status, data, _ = agent_call(self.token, "GET", "/search", {"q": "test", "collection": OTHER})
        self.assertEqual((status, error_code(data)), (403, "collection_not_in_scope"))
        status, data, _ = agent_call(self.token, "GET", "/search", {"q": "test", "source": "network"})
        self.assertEqual((status, error_code(data)), (403, "action_not_granted"))
        status, data, _ = agent_call(self.token, "GET", "/index/evidence", {"domain": "example.com", "collection": OTHER})
        self.assertEqual((status, error_code(data)), (403, "collection_not_in_scope"))
        status, data, _ = agent_call(self.token, "GET", "/index")
        self.assertEqual(status, 200, data)
        self.assertEqual([c["name"] for c in data["collections"]], [COLLECTION])
        self.assertNotIn("crawler", data)
        status, data, _ = agent_call(self.token, "GET", "/index", {"global": "true"})
        self.assertEqual(error_code(data), "action_not_granted")

    def test_not_granted_actions(self):
        for method, path in (("GET", "/system"), ("GET", "/config"), ("GET", "/crawls"), ("POST", "/crawls")):
            status, data, _ = agent_call(self.token, method, path, body={} if method == "POST" else None)
            self.assertEqual((status, error_code(data)), (403, "action_not_granted"), path)


class Lifecycle(unittest.TestCase):

    def manage(self, agent_id, op, **fields):
        opener = admin_opener()
        status, headers, _ = http("GET", BASE + "/ScoutroAgents_p.html?agent=" + agent_id, opener=opener)
        form = {"transactionToken": headers.get("X-YaCy-Transaction-Token"), "agent": agent_id, "op": op}
        form.update(fields)
        return http("POST", BASE + "/ScoutroAgents_p.html", urllib.parse.urlencode(form).encode("utf-8"),
                    {"Content-Type": "application/x-www-form-urlencoded"}, opener)

    def test_pause_resume_rotate_revoke(self):
        token, agent_id, _ = create_agent("e2e lifecycle " + RUN)
        self.assertEqual(agent_call(token, "GET", "/capabilities")[0], 200)

        self.manage(agent_id, "pause")
        status, data, _ = agent_call(token, "GET", "/search", {"q": "x"})
        self.assertEqual((status, error_code(data)), (403, "agent_paused"))
        self.manage(agent_id, "resume")
        self.assertEqual(agent_call(token, "GET", "/search", {"q": "x"})[0], 200)

        status, headers, page = self.manage(agent_id, "rotate", expiresInDays="30", graceMinutes="0")
        self.assertIn("no-store", headers.get("Cache-Control", ""))
        new = html.unescape(re.search(r'value="(sca_[^"]+)"', page).group(1))
        self.assertEqual(agent_call(token, "GET", "/capabilities")[0], 401)
        self.assertEqual(agent_call(new, "GET", "/capabilities")[0], 200)

        # grant change applies to the next call
        self.manage(agent_id, "update", name="e2e lifecycle " + RUN, extraCollections=COLLECTION,
                    act_search="on", domains="", maxDepth="1", maxPages="20", maxParallelCrawls="1",
                    requestsPerMinute="120", maxTaskSeconds="300")
        status, data, _ = agent_call(new, "GET", "/index/evidence", {"domain": "example.com"})
        self.assertEqual((status, error_code(data)), (403, "action_not_granted"))

        self.manage(agent_id, "revoke", confirmRevoke="on")
        status, data, _ = agent_call(new, "GET", "/capabilities")
        self.assertEqual((status, error_code(data)), (401, "token_revoked"))
        status, _, page = self.manage(agent_id, "resume")
        self.assertIn("Only a paused agent can be resumed", page)


@unittest.skipUnless(CRAWL_URL, "SCOUTRO_TEST_CRAWL_URL is not set")
class Crawls(unittest.TestCase):

    def test_crawl_limits_and_ownership(self):
        host = urllib.parse.urlsplit(CRAWL_URL).hostname
        token, agent_id, _ = create_agent("e2e crawl " + RUN, preset="research_crawl", domains="example.org",
                                          max_pages="5")
        status, data, _ = agent_call(token, "POST", "/crawls", body={"url": CRAWL_URL, "collection": COLLECTION})
        self.assertEqual((status, error_code(data)), (403, "limit_exceeded:domains"), data)
        if not re.match(r"^[a-z0-9.-]+\.[a-z][a-z0-9-]*$", host or ""):
            self.skipTest("the crawl test site needs a DNS name for the domain allowlist (got %r)" % host)

    def test_crawl_lifecycle_on_allowed_domain(self):
        host = urllib.parse.urlsplit(CRAWL_URL).hostname
        if not re.match(r"^[a-z0-9.-]+\.[a-z][a-z0-9-]*$", host or ""):
            self.skipTest("the crawl test site needs a DNS name for the domain allowlist (got %r)" % host)
        token, _, _ = create_agent("e2e crawl ok " + RUN, preset="research_crawl", domains=host, max_pages="5")
        other, _, _ = create_agent("e2e crawl other " + RUN, preset="research_crawl", domains=host, max_pages="5")
        body = {"url": CRAWL_URL, "collection": COLLECTION, "depth": 1, "maxPages": 5}
        status, data, headers = agent_call(token, "POST", "/crawls", body=body, headers={"Idempotency-Key": "e2e-" + RUN})
        if status == 409:
            # the test host already has documents in other collections (earlier runs): a scoped
            # agent must not re-index them into its own collection
            self.assertEqual(error_code(data), "host_indexed_elsewhere")
            token, _, _ = create_agent("e2e crawl all " + RUN, preset="research_crawl", domains=host, max_pages="5",
                                       all_collections=True)
            status, data, headers = agent_call(token, "POST", "/crawls", body=body, headers={"Idempotency-Key": "e2e-" + RUN})
        self.assertEqual(status, 201, data)
        crawl_id = data["id"]
        status, again, _ = agent_call(token, "POST", "/crawls", body=body, headers={"Idempotency-Key": "e2e-" + RUN})
        self.assertEqual((status, again["id"]), (200, crawl_id))
        self.assertEqual(agent_call(other, "GET", "/crawls/" + crawl_id)[0], 404)
        self.assertEqual(agent_call(other, "POST", "/crawls/" + crawl_id + "/stop", body={})[0], 404)
        status, listing, _ = agent_call(token, "GET", "/crawls")
        self.assertIn(crawl_id, [c["id"] for c in listing["crawls"]])
        status, data, _ = agent_call(token, "POST", "/crawls/" + crawl_id + "/stop", body={})
        self.assertIn(status, (200, 409), data)


if __name__ == "__main__":
    unittest.main()
