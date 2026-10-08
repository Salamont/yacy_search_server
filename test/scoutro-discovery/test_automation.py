#!/usr/bin/env python3
"""V1 automation invariants, synthetic config/state only, no internet or LLM. GPL-2.0-or-later."""
import argparse
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

import test_discovery as td

sys.path.insert(0, str(Path(td.DISCOVERY).parent))
import scoutro_automation as automation

d = td.disc
NOW = int(time.time())


def job():
    return {"id": "arbitrary-job", "profile": "future_profile", "candidate_scope": "source_regions",
            "sources": {"freeworld": {"mode": "selected", "regions": ["Test City"]}},
            "discovery": {"replenish": False}, "batch": {"max_domains": 50, "depth": 2,
                                                           "max_pages": 15, "seed_delay_seconds": 0},
            "processing": {"fresh": True, "retry": False, "recrawl": {"enabled": False, "days": 30}}}


def candidates(n=120, source="freeworld", region="Test City"):
    return [{"url": "https://firm-%d.de/" % i, "host": "firm-%d.de" % i,
             "source": source, "source_region": region, "region": "Display label"} for i in range(n)]


class AutomationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="scoutro-automation-test-")
        self.addCleanup(self.tmp.cleanup)
        self.state = d.State(self.tmp.name)

    def replenish(self, values=None):
        return automation.persist_candidates(d, self.state, "future_profile", "future-index",
                                             values or candidates(), "freeworld", NOW)

    def select(self, definition=None):
        return automation.select_backlog(d, self.state, definition or job(), {"freeworld": ["Test City"]}, NOW)

    def test_all_accepted_candidates_persist_dispatch_only_is_limited(self):
        result = self.replenish(candidates(250))
        self.assertEqual(result["new_pairs"], 250)
        reloaded = d.State(self.tmp.name)
        self.assertEqual(len(reloaded.data["domains"]), 250)
        self.assertEqual(len(self.select()), 50)
        for root in reloaded.data["domains"].values():
            entry = root["profiles"]["future_profile"]
            self.assertNotIn("status", entry)
            self.assertEqual(entry["source_region"], "Test City")

    def test_existing_outcomes_other_profiles_classifications_and_custom_fields_survive(self):
        self.state.put_profile_entry("firm-0.de", "future_profile", {"status": "crawled", "collection": "future-index",
                                    "last_crawl": NOW, "next_attempt": NOW + 86400, "operator_note": "keep"}, NOW)
        self.state.put_profile_entry("firm-0.de", "another", {"status": "retry", "attempts": 3}, NOW)
        self.state.domain("firm-0.de")["classifications"] = {"another": {"verdict": "PASS", "note": "keep"}}
        before = copy.deepcopy(self.state.domain("firm-0.de"))
        self.replenish()
        after = d.State(self.tmp.name).domain("firm-0.de")
        self.assertEqual(after["profiles"]["another"], before["profiles"]["another"])
        self.assertEqual(after["classifications"], before["classifications"])
        self.assertEqual(after["profiles"]["future_profile"]["last_crawl"], NOW)
        self.assertEqual(after["profiles"]["future_profile"]["operator_note"], "keep")
        self.assertNotIn("firm-0.de", [row[0] for row in self.select()])

    def test_an_excluded_pair_is_never_selected_and_survives_replenishing(self):
        self.replenish(candidates(3))
        entry = self.state.profile_entry("firm-0.de", "future_profile")
        entry.update({"status": "excluded", "collection": "future-index", "last_crawl": NOW - 400 * 86400, "next_attempt": 0,
                      "excluded": {"at": NOW, "reason": "wrong profile", "previous_status": "crawled", "previous_next_attempt": 0}})
        self.state.put_profile_entry("firm-0.de", "another", {"status": "crawled", "last_crawl": NOW - 400 * 86400}, NOW)
        everything = job()
        everything["processing"] = {"fresh": True, "retry": True, "recrawl": {"enabled": True, "days": 30}}
        self.assertNotIn("firm-0.de", [row[0] for row in self.select(everything)])
        self.assertIn("firm-1.de", [row[0] for row in self.select(everything)])
        self.replenish(candidates(3))
        self.assertEqual(self.state.profile_entry("firm-0.de", "future_profile")["status"], "excluded")
        self.assertEqual(self.state.profile_entry("firm-0.de", "another")["status"], "crawled")

    def test_duplicate_domains_preserve_all_source_origins(self):
        self.replenish(candidates(1) + candidates(1, region="Other City"))
        entry = self.state.profile_entry("firm-0.de", "future_profile")
        self.assertEqual(len(entry["origins"]), 2)
        self.assertEqual(len(self.state.data["domains"]), 1)

    def test_legacy_provenance_is_not_invented_and_profile_backlog_is_explicit(self):
        self.state.put_profile_entry("old.de", "future_profile", {"region": "Test City"}, NOW)
        self.assertEqual(self.select(), [])
        definition = job(); definition["candidate_scope"] = "profile_backlog"
        self.assertEqual([row[0] for row in self.select(definition)], ["old.de"])
        self.assertNotIn("source_region", self.state.profile_entry("old.de", "future_profile"))

    def test_processing_masks_and_cooldowns_use_shared_policy(self):
        self.replenish(candidates(4))
        for i, status in enumerate(["retry", "crawled", "robots"]):
            entry = self.state.profile_entry("firm-%d.de" % i, "future_profile")
            entry.update(status=status, last_crawl=NOW - 40 * 86400, next_attempt=NOW - 1)
        self.assertEqual([row[0] for row in self.select()], ["firm-3.de"])
        definition = job(); definition["processing"]["fresh"] = False; definition["processing"]["retry"] = True
        self.assertEqual(set(row[0] for row in self.select(definition)), {"firm-0.de", "firm-2.de"})
        definition["processing"]["retry"] = False; definition["processing"]["recrawl"]["enabled"] = True
        self.assertEqual([row[0] for row in self.select(definition)], ["firm-1.de"])
        self.state.profile_entry("firm-1.de", "future_profile")["next_attempt"] = NOW + 1
        self.assertEqual(self.select(definition), [])

    def test_unconfirmed_attempt_never_selected_again(self):
        self.replenish(candidates(1))
        self.state.profile_entry("firm-0.de", "future_profile")["automation_attempt"] = {"state": "submitted_unknown"}
        self.assertEqual(self.select(), [])

    def test_lock_is_acquired_before_any_state_read(self):
        self.state.save()
        original = d.State._read
        observed = []
        def read(state):
            observed.append(state._run_lock is not None)
            return original(state)
        with patch.object(d.State, "_read", read):
            locked = d.open_state(argparse.Namespace(workdir=self.tmp.name), run=True)
        try:
            self.assertEqual(observed, [True])
        finally:
            locked._run_lock.close()

    def test_fresh_read_occurs_after_lock_not_before_it(self):
        self.state.save()
        original = d.State.acquire_run_lock
        def acquire(state):
            result = original(state)
            raw = json.loads(Path(state.path).read_text()); raw["operator_revision"] = 7
            Path(state.path).write_text(json.dumps(raw))
            return result
        with patch.object(d.State, "acquire_run_lock", acquire):
            locked = d.open_state(argparse.Namespace(workdir=self.tmp.name), run=True)
        try: self.assertEqual(locked.data["operator_revision"], 7)
        finally: locked._run_lock.close()

    def test_freeworld_only_queries_the_jobs_selected_regions(self):
        config = Path(self.tmp.name) / "config"; config.mkdir()
        (config / "profiles.conf").write_text("future_profile=example term\n")
        (config / "regions.txt").write_text("Never Query This\n")
        seen = []
        class Client:
            def search(self, query, **kw): seen.append(query); return {"results": []}
        definition = job()
        automation.Freeworld().discover(d, Client(), definition, definition["sources"]["freeworld"], str(config), self.tmp.name, 0)
        self.assertEqual(seen, ["example term Test City"])

    def test_source_failure_does_not_become_zero_candidates(self):
        class Client:
            def search(self, *args, **kwargs): raise d.ApiError(401, "unauthorized", "test")
        with self.assertRaises(d.ApiError):
            d.discover_candidates(Client(), {"future_profile": ["term"]}, ["City"], "future_profile", 8, 1, 0, 20, strict=True)

    def test_download_failure_preserves_old_cache_and_removes_partial(self):
        cache = Path(self.tmp.name) / "bayern.osm.pbf"; cache.write_bytes(b"old verified cache")
        def fail(argv, **kwargs):
            Path(argv[argv.index("-o") + 1]).write_bytes(b"partial")
            raise subprocess.CalledProcessError(22, argv)
        with patch.object(d.subprocess, "run", fail), self.assertRaises(subprocess.CalledProcessError):
            d.download_pbf("bayern", str(cache))
        self.assertEqual(cache.read_bytes(), b"old verified cache")
        self.assertFalse(Path(str(cache) + ".part").exists())

    def test_osm_features_stream_geojson_sequence(self):
        path = Path(self.tmp.name) / "features.geojsons"
        path.write_text('\x1e{"properties":{"name":"first"}}\n{"properties":{"name":"second"}}\n')
        self.assertEqual([v["properties"]["name"] for v in d.osm_features(path)], ["first", "second"])

    def test_confirmations_are_idempotent_and_keep_other_profile(self):
        self.replenish(candidates(1))
        self.state.put_profile_entry("firm-0.de", "another", {"status": "robots"}, NOW)
        intent = {"id": "marker", "state": "accepted", "domain": "firm-0.de", "profile": "future_profile",
                  "submitted_at": NOW * 1000, "recrawl_days": 30, "crawl_id": "crawl-1"}
        automation.apply_confirmations(d, self.state, [intent]); before = copy.deepcopy(self.state.data)
        automation.apply_confirmations(d, self.state, [intent])
        self.assertEqual(before, self.state.data)
        self.assertEqual(self.state.profile_entry("firm-0.de", "another"), {"status": "robots"})

    def test_confirmations_do_not_overwrite_a_newer_different_crawl(self):
        self.replenish(candidates(1))
        self.state.profile_entry("firm-0.de", "future_profile").update(last_crawl=NOW + 100, crawl_id="newer")
        with self.assertRaises(ValueError):
            automation.apply_confirmations(d, self.state, [{"state": "accepted", "id": "old", "domain": "firm-0.de",
                "profile": "future_profile", "submitted_at": NOW * 1000, "crawl_id": "older", "recrawl_days": 30}])

    def test_dispatch_checkpoints_every_accepted_start_and_stops_at_batch_limit(self):
        self.replenish(candidates(120))
        definition = job(); definition["batch"]["max_domains"] = 3
        starts = []
        class Rpc:
            def call(self, action, params=None):
                if action == "admission": return {"allowed": True}
                if action == "crawl":
                    starts.append(params)
                    return {"id": "crawl-%d" % len(starts), "attempt_id": "marker-%d" % len(starts)}
                if action == "ack":
                    reloaded = d.State(self_outer.tmp.name)
                    self_outer.assertEqual(sum(p["profiles"]["future_profile"].get("status") == "crawled" for p in reloaded.data["domains"].values()), len(starts))
                return {}
        self_outer = self
        with patch.object(d, "precheck_candidate", return_value=None):
            result = automation.execute(d, self.state, {"job": definition, "collection": "future-index", "regions": {"freeworld": ["Test City"]}}, Rpc(), self.tmp.name, self.tmp.name)
        self.assertEqual(result["processed"], 3)
        self.assertEqual(len(self.state.data["domains"]), 120)

    def test_unknown_start_is_persisted_and_stops_the_batch(self):
        self.replenish(candidates(2))
        class Rpc:
            def call(self, action, params=None):
                if action == "admission": return {"allowed": True}
                if action == "crawl": raise automation.UnknownStart("marker")
        with patch.object(d, "precheck_candidate", return_value=None), self.assertRaises(automation.UnknownStart):
            automation.execute(d, self.state, {"job": job(), "collection": "future-index", "regions": {"freeworld": ["Test City"]}}, Rpc(), self.tmp.name, self.tmp.name)
        entry = d.State(self.tmp.name).profile_entry("firm-0.de", "future_profile")
        self.assertEqual(entry["automation_attempt"]["state"], "submitted_unknown")
        self.assertNotIn("status", entry)

    def test_precheck_refusals_are_reported_best_effort(self):
        self.replenish(candidates(2))
        calls = []
        class Rpc:
            def call(self, action, params=None):
                if action == "admission": return {"allowed": True}
                if action == "precheck":
                    calls.append(params)
                    raise d.ApiError(503, "report_unavailable", "report store down")
                raise AssertionError("unexpected " + action)
        with patch.object(d, "precheck_candidate", return_value={"status": "robots:robots-disallow-all"}):
            result = automation.execute(d, self.state, {"job": job(), "collection": "future-index", "regions": {"freeworld": ["Test City"]}}, Rpc(), self.tmp.name, self.tmp.name)
        self.assertEqual(result["processed"], 2)
        self.assertEqual(sorted(c["domain"] for c in calls), ["firm-0.de", "firm-1.de"])
        self.assertEqual({(c["result"], c["detail"]) for c in calls}, {("robots", "robots-disallow-all")})
        self.assertTrue(all(c["url"].startswith("https://") for c in calls))

    def test_precheck_report_keeps_only_short_reason_codes(self):
        self.assertEqual(automation.precheck_report({"status": "retry:dns", "error": "dns:[Errno -2] Name or service not known"}), ("dns", "dns"))
        self.assertEqual(automation.precheck_report({"status": "retry:site_5xx", "error": "site-5xx:503"}), ("site_5xx", "site-5xx:503"))
        self.assertEqual(automation.precheck_report({"status": "blocked:private-ip:10.0.0.1"}), ("blocked", "private-ip:10.0.0.1"))
        self.assertEqual(automation.precheck_report({"status": "robots:robots-disallow-all"}), ("robots", "robots-disallow-all"))
        self.assertEqual(automation.precheck_report({"status": "blocked:bad host name!"}), ("blocked", None))
        self.assertIsNone(automation.precheck_report({"status": "crawled"}))

    def crawled(self, n, last_crawl=None):
        self.replenish(candidates(n))
        for i in range(n):
            entry = self.state.profile_entry("firm-%d.de" % i, "future_profile")
            d.confirm_start(entry, {"id": "crawl-%d" % i}, last_crawl or NOW - 600, 30)
        self.state.save()

    def outcome_job(self, retry=True):
        definition = job(); definition["processing"]["retry"] = retry; definition["processing"]["outcome_retry"] = True
        return definition

    class Outcomes:
        def __init__(self, answers=None, error=None):
            self.answers, self.error, self.calls = answers or {}, error, []

        def call(self, action, params=None):
            if action != "outcomes":
                raise AssertionError("unexpected " + action)
            self.calls.append(params["hosts"])
            if self.error:
                raise self.error
            return {"outcomes": {h: self.answers[h] for h in params["hosts"] if h in self.answers}}

    def test_outcome_retry_needs_the_option_and_retry(self):
        self.crawled(1)
        rpc = self.Outcomes({"firm-0.de": {"crawl_id": "crawl-0", "outcome": "not_indexed"}})
        self.assertEqual(automation.apply_outcomes(d, self.state, rpc, job(), NOW), 0)
        self.assertEqual(automation.apply_outcomes(d, self.state, rpc, self.outcome_job(retry=False), NOW), 0)
        self.assertEqual(rpc.calls, [])
        self.assertEqual(self.state.profile_entry("firm-0.de", "future_profile")["status"], "crawled")

    def test_unsuccessful_results_of_the_own_crawl_become_retries(self):
        self.crawled(5)
        old = self.state.profile_entry("firm-4.de", "future_profile"); old["last_crawl"] = NOW - 4 * 86400; self.state.save()
        before = copy.deepcopy(self.state.data["domains"]["firm-2.de"])
        rpc = self.Outcomes({"firm-0.de": {"crawl_id": "crawl-0", "outcome": "not_indexed"},
                             "firm-1.de": {"crawl_id": "crawl-1", "outcome": "not_reloaded"},
                             "firm-2.de": {"crawl_id": "crawl-2", "outcome": "indexed"},
                             "firm-3.de": {"crawl_id": "an-older-crawl", "outcome": "not_indexed"},
                             "firm-4.de": {"crawl_id": "crawl-4", "outcome": "not_indexed"}})
        self.assertEqual(automation.apply_outcomes(d, self.state, rpc, self.outcome_job(), NOW), 2)
        self.assertEqual(rpc.calls, [["firm-0.de", "firm-1.de", "firm-2.de", "firm-3.de"]])   # firm-4: outside the window
        saved = d.State(self.tmp.name)
        for i, cls in ((0, "not_indexed"), (1, "not_reloaded")):
            entry = saved.profile_entry("firm-%d.de" % i, "future_profile")
            self.assertEqual((entry["status"], entry["error_class"], entry["attempts"]), ("retry", cls, 1))
            self.assertEqual(entry["next_attempt"], NOW + 2 * 3600)
            self.assertEqual(entry["crawl_id"], "crawl-%d" % i)
        self.assertEqual(saved.data["domains"]["firm-2.de"], before)                              # success: untouched
        self.assertEqual(saved.profile_entry("firm-3.de", "future_profile")["status"], "crawled")  # another crawl
        self.assertEqual(saved.profile_entry("firm-4.de", "future_profile")["status"], "crawled")
        self.assertEqual(len(self.select(self.outcome_job())), 0)                                  # backoff not over
        later = automation.select_backlog(d, saved, self.outcome_job(), {"freeworld": ["Test City"]}, NOW + 3 * 3600)
        self.assertEqual(sorted(domain for domain, *_ in later), ["firm-0.de", "firm-1.de"])

    def test_backoff_grows_across_outcome_retries_and_success_resets_it(self):
        self.crawled(1)
        entry = self.state.profile_entry("firm-0.de", "future_profile")
        for attempt in (1, 2, 3):
            rpc = self.Outcomes({"firm-0.de": {"crawl_id": entry["crawl_id"], "outcome": "not_indexed"}})
            automation.apply_outcomes(d, self.state, rpc, self.outcome_job(), NOW)
            entry = self.state.profile_entry("firm-0.de", "future_profile")
            self.assertEqual((entry["attempts"], entry["next_attempt"]), (attempt, NOW + 2 ** attempt * 3600))
            automation.confirm(d, entry, {"id": "crawl-r%d" % attempt}, NOW - 60, 30)          # the retry is started
            self.assertEqual((entry["status"], entry["attempts"]), ("crawled", attempt))
        long_ago = copy.deepcopy(entry); long_ago.update(attempts=12)
        d.mark_retry(long_ago, "not_indexed", "crawl:not_indexed", NOW, 30)
        self.assertEqual(long_ago["next_attempt"], NOW + 30 * 86400)                              # bounded by the recrawl interval
        rpc = self.Outcomes({"firm-0.de": {"crawl_id": entry["crawl_id"], "outcome": "partial"}})
        self.assertEqual(automation.apply_outcomes(d, self.state, rpc, self.outcome_job(), NOW), 0)
        entry = self.state.profile_entry("firm-0.de", "future_profile")
        self.assertEqual((entry["status"], entry["attempts"]), ("crawled", 0))
        retried = copy.deepcopy(entry); retried.update(status="retry", error_class="not_reloaded", attempts=3)
        automation.confirm(d, retried, {"id": "crawl-z"}, NOW, 30)
        automation.confirm(d, retried, {"id": "crawl-z"}, NOW, 30)                             # replayed confirmation
        self.assertEqual((retried["status"], retried["attempts"]), ("crawled", 3))
        dns = copy.deepcopy(entry); dns.update(status="retry", error_class="dns", attempts=4)
        automation.confirm(d, dns, {"id": "x"}, NOW, 30)
        self.assertEqual(dns["attempts"], 0)                                                     # other retries keep their rule

    def test_outcome_lookup_is_batched_and_best_effort(self):
        self.crawled(450)
        rpc = self.Outcomes({})
        automation.apply_outcomes(d, self.state, rpc, self.outcome_job(), NOW)
        self.assertEqual([len(c) for c in rpc.calls], [200, 200, 50])
        failing = self.Outcomes(error=d.ApiError(400, "invalid_request", "outcome retry off"))
        before = copy.deepcopy(self.state.data)
        self.assertEqual(automation.apply_outcomes(d, self.state, failing, self.outcome_job(), NOW), 0)
        self.assertEqual(len(failing.calls), 1)
        self.assertEqual(before, self.state.data)

    def test_execute_reports_outcome_retries_before_selecting(self):
        self.crawled(2)
        actions = []
        class Rpc:
            def call(self, action, params=None):
                actions.append(action)
                if action == "outcomes":
                    return {"outcomes": {"firm-0.de": {"crawl_id": "crawl-0", "outcome": "not_indexed"}}}
                if action == "admission": return {"allowed": True}
                raise AssertionError("unexpected " + action)
        result = automation.execute(d, self.state, {"job": self.outcome_job(), "collection": "future-index",
                                                    "regions": {"freeworld": ["Test City"]}}, Rpc(), self.tmp.name, self.tmp.name)
        self.assertEqual(result["outcome_retries"], 1)
        self.assertEqual(actions[0], "outcomes")
        self.assertNotIn("crawl", actions)                                                      # nothing due yet
        result = automation.execute(d, self.state, {"job": job(), "collection": "future-index",
                                                    "regions": {"freeworld": ["Test City"]}}, Rpc(), self.tmp.name, self.tmp.name)
        self.assertNotIn("outcome_retries", result)

    def test_status_protocol_is_read_only_without_repo_config_fallback(self):
        root = Path(self.tmp.name) / "absent"
        init = {"operation": "status", "jobs": [], "regions": {}}
        result = subprocess.run([sys.executable, td.DISCOVERY, "--workdir", str(root), "--config-dir", str(root / "config"), "automation"],
                                input=json.dumps(init) + "\n", text=True, capture_output=True, check=True,
                                env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"))
        parsed = json.loads(result.stdout)
        self.assertEqual(parsed["type"], "done")
        self.assertEqual(parsed["snapshot"]["domain_profile_pairs"], 0)
        self.assertFalse(root.exists())


if __name__ == "__main__": unittest.main()
