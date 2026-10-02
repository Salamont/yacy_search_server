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
