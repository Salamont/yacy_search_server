#!/usr/bin/env python3
"""Scoutro API v1 tests.

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Runs against a running, disposable Scoutro instance (never production data):

    SCOUTRO_URL=http://127.0.0.1:8090 \
    SCOUTRO_ADMIN_USER=admin SCOUTRO_ADMIN_PASSWORD=yacy \
    SCOUTRO_TEST_CRAWL_URL=http://<local test site>/index.html \
    python3 test/scoutro-api/test_api.py -v

SCOUTRO_TEST_CRAWL_URL must point to a disposable local test site that the
Scoutro instance may crawl (for private addresses the peer must use the
"intranet" use case). Crawl tests are skipped when it is not set.
SCOUTRO_TEST_REJECTED_URL (optional) is a URL that YaCy refuses to crawl,
e.g. a private address on a peer in the "freeworld" network.
Only the Python standard library is required; if openapi-spec-validator is
installed, openapi.json is additionally validated against the OpenAPI 3.1
schema.
"""

import json
import os
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("SCOUTRO_URL", "http://127.0.0.1:8090").rstrip("/")
API = BASE + "/scoutro/api"
USER = os.environ.get("SCOUTRO_ADMIN_USER", "admin")
PASSWORD = os.environ.get("SCOUTRO_ADMIN_PASSWORD", "yacy")
CRAWL_URL = os.environ.get("SCOUTRO_TEST_CRAWL_URL")
REJECTED_URL = os.environ.get("SCOUTRO_TEST_REJECTED_URL")
CLI = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "tools", "scoutro", "scoutroctl")


def call(method, path, body=None, auth=True, password=None, headers=None, raw_body=None):
    """Return (status, parsed JSON or None, raw text)."""
    handlers = []
    if auth:
        manager = urllib.request.HTTPPasswordMgrWithDefaultRealm()
        manager.add_password(None, BASE, USER, password if password is not None else PASSWORD)
        handlers.append(urllib.request.HTTPDigestAuthHandler(manager))
    opener = urllib.request.build_opener(*handlers)
    data = raw_body
    hdrs = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        hdrs["Content-Type"] = "application/json"
    hdrs.update(headers or {})
    request = urllib.request.Request(API + path, data=data, method=method, headers=hdrs)
    try:
        with opener.open(request, timeout=90) as response:
            text = response.read().decode("utf-8")
            status = response.status
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", "replace")
        status = e.code
    try:
        payload = json.loads(text)
    except ValueError:
        payload = None
    return status, payload, text


class PublicEndpoints(unittest.TestCase):

    def test_health_is_public(self):
        status, data, _ = call("GET", "/v1/health", auth=False)
        self.assertEqual(status, 200)
        self.assertEqual(data["status"], "ok")
        self.assertEqual(data["apiVersion"], "1")
        self.assertTrue(data["version"]["yacy"])

    def test_ui_routes_are_public(self):
        status, data, _ = call("GET", "/v1/ui/routes", auth=False)
        self.assertEqual(status, 200)
        names = {r["name"] for r in data["routes"]}
        for required in ("config.accounts", "config.network", "crawler.monitor", "crawl.startSite", "search"):
            self.assertIn(required, names)

    def test_ui_route_accounts(self):
        status, data, _ = call("GET", "/v1/ui/routes/config.accounts", auth=False)
        self.assertEqual(status, 200)
        self.assertEqual(data["path"], "/ConfigAccounts_p.html")
        self.assertEqual(data["auth"], "admin")

    def test_unknown_ui_route(self):
        status, data, _ = call("GET", "/v1/ui/routes/does.not.exist", auth=False)
        self.assertEqual(status, 404)
        self.assertEqual(data["error"]["code"], "route_not_found")

    def test_unknown_path(self):
        status, data, _ = call("GET", "/v1/nothing", auth=True)
        self.assertEqual(status, 404)
        self.assertEqual(data["error"]["code"], "not_found")


class Descriptions(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.openapi = call("GET", "/openapi.json", auth=False)[1]
        cls.actions = call("GET", "/actions.json", auth=False)[1]

    def test_openapi_structure(self):
        spec = self.openapi
        self.assertTrue(spec["openapi"].startswith("3.1"))
        self.assertIn("digest", spec["components"]["securitySchemes"])
        self.assertIn("agentBearer", spec["components"]["securitySchemes"])
        operation_ids = set()
        for path, methods in spec["paths"].items():
            self.assertTrue(path.startswith("/v1/") or path.startswith("/agent/v1/"), path)
            for method, operation in methods.items():
                self.assertIn(method, ("get", "post", "patch"))
                self.assertNotIn(operation["operationId"], operation_ids)
                operation_ids.add(operation["operationId"])
                self.assertTrue(any(code.startswith("2") for code in operation["responses"]))
                self.assertIn("x-scoutro-mutating", operation)
        for required in ("search", "crawl.start", "crawl.list", "crawl.status", "crawl.stop",
                         "index.status", "system.status", "ui.routes", "health"):
            self.assertIn(required, operation_ids)

    def test_openapi_schema_valid(self):
        try:
            from openapi_spec_validator import validate
        except ImportError:
            self.skipTest("openapi-spec-validator not installed")
        validate(self.openapi)

    def test_actions_match_openapi(self):
        paths = self.openapi["paths"]
        for action in self.actions["actions"]:
            path = action["http"]["path"][len("/scoutro/api"):]
            operation = paths[path][action["http"]["method"].lower()]
            self.assertEqual(operation["operationId"], action["name"])
            self.assertEqual(operation["x-scoutro-mutating"], action["mutating"])
            self.assertEqual(bool(operation["security"]), action["auth"] == "admin")
            self.assertTrue(action["mcpTool"].startswith("scoutro_"))
        mutating = {a["name"] for a in self.actions["actions"] if a["mutating"]}
        self.assertEqual(mutating, {"crawl.start", "crawl.stop", "config.set"})

    def test_mutating_actions_require_admin(self):
        for action in self.actions["actions"]:
            if action["mutating"]:
                self.assertEqual(action["auth"], "admin", action["name"])


class Authentication(unittest.TestCase):

    def test_admin_reads_require_auth(self):
        for path in ("/v1/system", "/v1/index", "/v1/search?q=test", "/v1/crawls", "/v1/config",
                     "/v1/crawls/abcdefghijkl", "/v1/index/evidence?domain=example.com"):
            status, _, _ = call("GET", path, auth=False)
            self.assertEqual(status, 401, path)

    def test_mutating_calls_require_auth(self):
        status, _, _ = call("POST", "/v1/crawls", body={"url": "https://example.com"}, auth=False)
        self.assertEqual(status, 401)
        status, _, _ = call("POST", "/v1/crawls/abcdefghijkl/stop", body={}, auth=False)
        self.assertEqual(status, 401)
        status, _, _ = call("PATCH", "/v1/config", body={"search.itemsPerPage": 10}, auth=False)
        self.assertEqual(status, 401)

    def test_wrong_password(self):
        status, _, _ = call("POST", "/v1/crawls", body={"url": "https://example.com"}, password="wrong-password")
        self.assertEqual(status, 401)

    def test_cross_site_protection(self):
        status, data, _ = call("POST", "/v1/crawls", raw_body=b"url=https://example.com",
                               headers={"Content-Type": "application/x-www-form-urlencoded"})
        self.assertEqual(status, 415)
        self.assertEqual(data["error"]["code"], "unsupported_media_type")
        status, data, _ = call("POST", "/v1/crawls", body={"url": "https://example.com"},
                               headers={"Origin": "https://evil.example"})
        self.assertEqual(status, 403)
        self.assertEqual(data["error"]["code"], "cross_origin_forbidden")

    def test_method_not_allowed(self):
        status, data, _ = call("DELETE", "/v1/crawls")
        self.assertEqual(status, 405)
        self.assertEqual(data["error"]["code"], "method_not_allowed")


class ReadActions(unittest.TestCase):

    def test_system(self):
        status, data, _ = call("GET", "/v1/system")
        self.assertEqual(status, 200)
        self.assertIn("memory", data["resources"])
        self.assertIn("state", data["crawler"])

    def test_index(self):
        status, data, _ = call("GET", "/v1/index")
        self.assertEqual(status, 200)
        self.assertIsInstance(data["documents"], int)

    def test_search(self):
        status, data, _ = call("GET", "/v1/search?q=scoutro&limit=5")
        self.assertEqual(status, 200)
        self.assertEqual(data["query"], "scoutro")
        self.assertEqual(data["limit"], 5)
        self.assertIsInstance(data["results"], list)
        for result in data["results"]:
            self.assertNotIn("<", result["snippet"])

    def test_search_validation(self):
        for query, field in (("", "q"), ("?q=x&limit=0", "limit"), ("?q=x&limit=abc", "limit"),
                             ("?q=x&source=web", "source"), ("?q=x&lang=deu", "lang")):
            status, data, _ = call("GET", "/v1/search" + query)
            self.assertEqual(status, 400, query)
            self.assertEqual(data["error"]["details"]["field"], field, query)

    def test_index_lookup_validation(self):
        status, data, _ = call("GET", "/v1/index/lookup")
        self.assertEqual(status, 400)
        status, data, _ = call("GET", "/v1/index/lookup?url=ftp://example.com/")
        self.assertEqual(status, 400)
        status, data, _ = call("GET", "/v1/index/lookup?host=example.com")
        self.assertEqual(status, 200)
        self.assertIsInstance(data["documents"], int)

    def test_index_evidence_validation(self):
        q = urllib.parse.quote
        for query, field in (("", "domain"), ("?domain=", "domain"),
                             ("?domain=" + q("*.example.com"), "domain"),
                             ("?domain=" + q('example.com" OR host_s:*'), "domain"),
                             ("?domain=" + q("example.com OR x"), "domain"),
                             ("?domain=127.0.0.1", "domain"), ("?domain=localhost", "domain"),
                             ("?domain=example.com:8080", "domain"), ("?domain=" + q("http://example.com/"), "domain"),
                             ("?domain=" + q("ex ample.com"), "domain"), ("?domain=" + "a" * 64 + ".com", "domain"),
                             ("?domain=example.com&collection=" + q("x OR y"), "collection"),
                             ("?domain=example.com&collection=" + "c" * 65, "collection"),
                             ("?domain=example.com&limit=0", "limit"), ("?domain=example.com&limit=21", "limit"),
                             ("?domain=example.com&maxChars=99", "maxChars"),
                             ("?domain=example.com&maxChars=4001", "maxChars")):
            status, data, _ = call("GET", "/v1/index/evidence" + query)
            self.assertEqual(status, 400, query)
            self.assertEqual(data["error"]["details"]["field"], field, query)

    def test_index_evidence_unknown_domain(self):
        status, data, _ = call("GET", "/v1/index/evidence?domain=Nothing-Indexed.Example.com&limit=3&maxChars=200")
        self.assertEqual(status, 200)
        self.assertEqual((data["domain"], data["total"], data["documents"]), ("nothing-indexed.example.com", 0, []))
        self.assertEqual((data["limit"], data["maxChars"], data["collection"]), (3, 200, None))

    def test_index_evidence_is_read_only(self):
        status, data, _ = call("POST", "/v1/index/evidence?domain=example.com", body={})
        self.assertEqual(status, 405)

    def test_config_allowlist(self):
        status, data, _ = call("GET", "/v1/config")
        self.assertEqual(status, 200)
        original = data["settings"]["search.itemsPerPage"]["value"]
        status, data, _ = call("PATCH", "/v1/config", body={"search.itemsPerPage": 12})
        self.assertEqual(status, 200)
        self.assertEqual(data["settings"]["search.itemsPerPage"]["value"], 12)
        status, data, _ = call("PATCH", "/v1/config", body={"publicSearchpage": False})
        self.assertEqual(status, 403)
        self.assertEqual(data["error"]["code"], "setting_not_allowed")
        status, data, _ = call("PATCH", "/v1/config", body={"search.itemsPerPage": 1000})
        self.assertEqual(status, 400)
        call("PATCH", "/v1/config", body={"search.itemsPerPage": original})


class Crawls(unittest.TestCase):

    def test_list(self):
        status, data, _ = call("GET", "/v1/crawls")
        self.assertEqual(status, 200)
        self.assertIsInstance(data["crawls"], list)

    def test_invalid_url(self):
        for url in ("ftp://example.com/", "file:///etc/passwd", "not a url", "https://user:pw@example.com/",
                    "", 42, "https://" + "a" * 2100 + ".com/"):
            status, data, _ = call("POST", "/v1/crawls", body={"url": url})
            self.assertEqual(status, 400, url)
            self.assertEqual(data["error"]["details"]["field"], "url", url)

    def test_missing_url(self):
        status, data, _ = call("POST", "/v1/crawls", body={"depth": 2})
        self.assertEqual(status, 400)

    def test_invalid_depth(self):
        for depth in (-1, 11, 99, "3", 2.5, None):
            body = {"url": "https://example.com/", "depth": depth}
            status, data, _ = call("POST", "/v1/crawls", body=body)
            if depth is None:
                continue  # JSON null means "use the default"; this call must not start a crawl here
            self.assertEqual(status, 400, depth)
            self.assertEqual(data["error"]["details"]["field"], "depth", depth)

    def test_invalid_scope_and_fields(self):
        status, data, _ = call("POST", "/v1/crawls", body={"url": "https://example.com/", "scope": "everything"})
        self.assertEqual(status, 400)
        self.assertEqual(data["error"]["details"]["field"], "scope")
        status, data, _ = call("POST", "/v1/crawls", body={"url": "https://example.com/", "deleteIndex": True})
        self.assertEqual(status, 400)
        self.assertEqual(data["error"]["details"]["field"], "deleteIndex")
        status, data, _ = call("POST", "/v1/crawls", raw_body=b"{not json", headers={"Content-Type": "application/json"})
        self.assertEqual(status, 400)
        self.assertEqual(data["error"]["code"], "invalid_json")

    def test_unknown_crawl_id(self):
        status, data, _ = call("GET", "/v1/crawls/unknownCrawl1")
        self.assertEqual(status, 404)
        self.assertEqual(data["error"]["code"], "crawl_not_found")
        status, data, _ = call("POST", "/v1/crawls/unknownCrawl1/stop", body={})
        self.assertEqual(status, 404)
        status, data, _ = call("GET", "/v1/crawls/bad%20id")
        self.assertEqual(status, 400)

    @unittest.skipUnless(CRAWL_URL, "SCOUTRO_TEST_CRAWL_URL not set")
    def test_crawl_lifecycle(self):
        status, crawl, text = call("POST", "/v1/crawls", body={"url": CRAWL_URL, "depth": 1, "scope": "domain",
                                                               "maxPages": 20})
        self.assertEqual(status, 201, text)
        crawl_id = crawl["id"]
        self.assertIn(crawl["state"], ("running", "paused"))
        self.assertEqual(crawl["depth"], 1)
        self.assertEqual(crawl["maxPages"], 20)
        self.assertEqual(crawl["startUrl"], CRAWL_URL)

        status, data, _ = call("GET", "/v1/crawls")
        self.assertIn(crawl_id, {c["id"] for c in data["crawls"]})
        status, data, _ = call("GET", "/v1/crawls/" + crawl_id)
        self.assertEqual(status, 200)
        self.assertEqual(data["id"], crawl_id)

        time.sleep(3)  # let YaCy load the start page before stopping
        status, data, _ = call("POST", "/v1/crawls/" + crawl_id + "/stop", body={})
        if status == 409:
            self.assertEqual(data["error"]["code"], "crawl_not_running")  # finished on its own already
        else:
            self.assertEqual(status, 200)
            self.assertEqual(data["state"], "stopped")
            status, data, _ = call("GET", "/v1/crawls/" + crawl_id)
            self.assertEqual(status, 404)


class CrawlRejected(unittest.TestCase):

    @unittest.skipUnless(REJECTED_URL, "SCOUTRO_TEST_REJECTED_URL not set")
    def test_rejected_crawl(self):
        status, data, _ = call("POST", "/v1/crawls", body={"url": REJECTED_URL})
        self.assertEqual(status, 422)
        self.assertEqual(data["error"]["code"], "crawl_rejected")
        self.assertIn("failed", data["error"]["message"])


class Cli(unittest.TestCase):

    def run_cli(self, *args):
        env = dict(os.environ, SCOUTRO_URL=BASE, SCOUTRO_USER=USER, SCOUTRO_PASSWORD=PASSWORD)
        result = subprocess.run([sys.executable, CLI, "--compact", *args], capture_output=True, text=True, env=env,
                                timeout=120)
        return result.returncode, json.loads(result.stdout)

    def test_cli_health_and_search(self):
        code, data = self.run_cli("health")
        self.assertEqual(code, 0)
        self.assertEqual(data["status"], "ok")
        code, data = self.run_cli("search", "scoutro", "--limit", "3")
        self.assertEqual(code, 0)
        self.assertEqual(data["limit"], 3)

    def test_cli_errors(self):
        code, data = self.run_cli("crawl", "start", "ftp://example.com/")
        self.assertEqual(code, 1)
        self.assertEqual(data["error"]["code"], "invalid_request")
        code, data = self.run_cli("crawl", "status", "unknownCrawl1")
        self.assertEqual(code, 1)
        self.assertEqual(data["error"]["code"], "crawl_not_found")

    def test_cli_routes(self):
        code, data = self.run_cli("ui", "route", "config.network")
        self.assertEqual(code, 0)
        self.assertEqual(data["path"], "/ConfigNetwork_p.html")


if __name__ == "__main__":
    unittest.main()
