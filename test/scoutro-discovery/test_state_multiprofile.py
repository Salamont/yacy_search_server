#!/usr/bin/env python3
"""Tests for the multi-profile discovery state (state.json, state_version 2).

Copyright (C) 2026 Scoutro contributors
Scoutro is an independent community project based on YaCy.
Licensed under the GNU General Public License, version 2 or (at your option)
any later version.

Offline, like test_discovery.py (mock API, no YaCy, no network, no LLM).

    python3 test/scoutro-discovery/test_state_multiprofile.py -v

A 199-domain state in the version 1 layout (scoutro-discovery <= 3) is
generated deterministically with every kind of entry the old tool wrote.
To check a copy of a real state.json without changing it:

    SCOUTRO_STATE_COPY=/path/to/copy/state.json python3 test/scoutro-discovery/test_state_multiprofile.py -v
"""

import argparse
import contextlib
import copy
import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.parse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import test_discovery as td  # noqa: E402  (mocks and fixtures; its tests are not collected here)

disc = td.disc
sc = td.sc
DISCOVERY = td.DISCOVERY
NOW = int(time.time())
DAY = 86400


def v1_state(n=199):
    """A version 1 state as scoutro-discovery <= 3 wrote it: flat crawl fields next to "profile"."""
    clf = sc.Classifier(td.RULES, backend="heuristic")
    docs = {"edelsenior": td.CARE_HOME, "stackfinder": td.IT, "bauteamcheck": td.BUILDER}
    thin = [{"url": "https://x.de/", "title": "Startseite", "snippet": "Willkommen"}]
    domains = {}
    for i in range(n):
        d = "firma-%03d.de" % i
        p = ("edelsenior", "edelsenior", "edelsenior", "stackfinder", "bauteamcheck")[i % 5]
        e = {"profile": p, "domain": d, "candidate_url": "https://www.%s/" % d,
             "collection": ("prospect-%s" if i % 3 == 0 else "%s-web") % p, "region": "Region %d" % (i % 7),
             "first_seen": NOW - (200 - i) * DAY}
        kind = (i // 5) % 10                     # every status in every profile
        if i % 23 == 0:                              # created by "classify --domain": no crawl state
            e = {"domain": d, "profile": p}
        elif i % 17 == 0:
            e.update(status="blocked", error_class="security", attempts=1, last_error="private-ip:10.0.0.1",
                     next_attempt=NOW + DAY)
        elif kind <= 4:
            e.update(status="crawled", last_crawl=NOW - (i % 45) * DAY, attempts=0, last_error="",
                     next_attempt=NOW - (i % 45) * DAY + 30 * DAY, crawl_id="crawl-%d" % i)
            if i % 2:
                e.update(error_class="", last_http_status=None)        # written by version 3
        elif kind == 5:
            e.update(status="retry", error_class="dns", attempts=2, last_error="dns:[Errno -2]",
                     last_http_status=None, next_attempt=NOW + (i % 3 - 1) * 3600)
        elif kind == 6:                                  # bogus 401 of versions <= 2
            e.update(status="error", attempts=5, last_error="401 http_error: digest auth failed",
                     next_attempt=NOW + 5 * DAY)
        elif kind == 7:
            e.update(status="error", attempts=3, last_error="422 crawl_rejected: x", next_attempt=NOW - 10)
        elif kind == 8:
            e.update(status="rejected", error_class="rejected", attempts=1, last_error="422 crawl_rejected: y",
                     last_http_status=422, next_attempt=NOW + 60 * DAY)
        else:
            e.update(status="robots", error_class="robots", attempts=1, last_error="robots-disallow-all",
                     next_attempt=NOW + 30 * DAY)
        if i == 42:
            e["note"] = "manually added field"
        if e.get("status") == "crawled" and i % 2 == 0:
            src = (docs[p], td.NEWS, thin)[i % 3]
            e["classifications"] = {p: clf.classify(p, d, src, location=e["region"])}
        if i % 11 == 0 and "classifications" in e:        # classify --domain for a second profile
            other = "stackfinder" if p != "stackfinder" else "edelsenior"
            e["classifications"][other] = clf.classify(other, d, thin)
        domains[d] = e
    return {"paused": False, "profiles": {"edelsenior": {"region_cursor": 5}}, "domains": domains}


def write_state(directory, state):
    path = os.path.join(directory, "state.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(state, f, indent=2, ensure_ascii=False)
    return path


def read_state(directory, name="state.json"):
    with open(os.path.join(directory, name), encoding="utf-8") as f:
        return json.load(f)


class FakeClient:
    """crawl.start records calls; search returns the given candidate URLs."""

    def __init__(self, urls):
        self.urls = urls
        self.calls = []

    def search(self, q, source="local", limit=10):
        return {"results": [{"url": u, "title": "t"} for u in self.urls]}

    def crawl_start(self, url, collection, depth=2, max_pages=15, scope="domain"):
        self.calls.append((url, collection))
        return {"id": "c-%d" % len(self.calls)}


def run_start(workdir, profile, urls, max_domains=10, force=False, dry_run=False):
    client = FakeClient(urls)
    orig = (disc.make_client, disc.resolve_public, disc.robots_allows)
    disc.make_client = lambda args: client
    disc.resolve_public = lambda host: (True, "1.2.3.4")
    disc.robots_allows = lambda host: (True, "robots-ok")
    out = io.StringIO()
    try:
        args = argparse.Namespace(config_dir=None, workdir=workdir, profile=profile, source="freeworld",
                                  region=[], keep_pbf=False, osm_config=None, limit_terms=1, limit_regions=1,
                                  net_limit=5, max_domains=max_domains, depth=2, max_pages=15, delay=0,
                                  recrawl_days=30, rejected_cooldown_days=60, force=force, dry_run=dry_run)
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            disc.cmd_start(args)
    finally:
        disc.make_client, disc.resolve_public, disc.robots_allows = orig
    return client.calls, json.loads(out.getvalue())[profile]


def url(d):
    return "https://www.%s/" % d


class Base(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.dir = self.tmp.name

    def tearDown(self):
        self.tmp.cleanup()

    def cli(self, *args, ok=True):
        env = dict(os.environ, SCOUTRO_DISCOVERY_DIR=self.dir, SCOUTRO_PASSWORD="test",
                   SCOUTRO_URL=getattr(self, "api_url", "http://127.0.0.1:9"))
        for k in list(env):
            if k.startswith("SCOUTRO_LLM_"):
                del env[k]
        p = subprocess.run([sys.executable, DISCOVERY] + list(args), env=env, capture_output=True, text=True,
                           timeout=120)
        if ok:
            self.assertEqual(p.returncode, 0, p.stderr)
        return p


class MigrationTests(Base):
    """Checks 1, 2, 3, 11: the version 1 state is read, migrated atomically and completely."""

    def setUp(self):
        super().setUp()
        self.old = v1_state()
        write_state(self.dir, self.old)
        with open(os.path.join(self.dir, "state.json"), "rb") as f:
            self.old_bytes = f.read()

    def test_old_state_is_read_and_mapped_per_profile(self):                   # 1
        st = disc.State(self.dir)
        self.assertEqual(st.disk_version, 1)
        self.assertEqual(st.migrated, 199)
        for d, old in self.old["domains"].items():
            new = st.domain(d)
            self.assertNotIn("profile", new)
            self.assertNotIn("status", new)
            pe = new["profiles"][old["profile"]]
            self.assertEqual(list(new["profiles"]), [old["profile"]])
            for k, v in old.items():
                if k in ("profile", "domain", "classifications", "first_seen"):
                    continue
                self.assertEqual(pe[k], v, (d, k))
            if "first_seen" in old:
                self.assertEqual((new["first_seen"], pe["first_seen"]), (old["first_seen"], old["first_seen"]))
        self.assertEqual(st.domain("firma-042.de")["profiles"]["edelsenior"]["note"], "manually added field")
        self.assertEqual(disc.migration_losses(self.old["domains"], st.data["domains"]), [])
        # reading alone never writes
        with open(os.path.join(self.dir, "state.json"), "rb") as f:
            self.assertEqual(f.read(), self.old_bytes)
        self.assertFalse(os.path.exists(os.path.join(self.dir, "state.v1.json")))

    def test_199_domains_stay_199_and_classifications_are_unchanged(self):     # 2 + 3
        st = disc.State(self.dir)
        st.save()
        new = read_state(self.dir)
        self.assertEqual(new["state_version"], 2)
        self.assertEqual(len(new["domains"]), 199)
        self.assertEqual(set(new["domains"]), set(self.old["domains"]))
        n_rec = 0
        for d, old in self.old["domains"].items():
            self.assertEqual(new["domains"][d].get("classifications", {}), old.get("classifications", {}))
            n_rec += len(old.get("classifications", {}))
        self.assertGreater(n_rec, 50)
        self.assertEqual(new["profiles"], self.old["profiles"])            # region cursors kept
        self.assertEqual(read_state(self.dir, "state.v1.json"), self.old)    # original kept once
        # idempotent: a second load/save changes nothing
        st2 = disc.State(self.dir)
        self.assertEqual((st2.disk_version, st2.migrated), (2, 0))
        st2.save()
        self.assertEqual(read_state(self.dir), new)
        self.assertEqual(read_state(self.dir, "state.v1.json"), self.old)

    def test_import_does_not_recrawl_everything(self):
        """Only what was already due before the migration is selected afterwards."""
        doms = sorted(self.old["domains"])
        st = disc.State(self.dir)
        before = {}
        for d in doms:
            old = self.old["domains"][d]
            before[d] = disc.selection_kind(old if old.get("status") else None, NOW, 30)
        for d in doms:
            p = self.old["domains"][d]["profile"]
            self.assertEqual(disc.selection_kind(st.profile_entry(d, p), NOW, 30), before[d], d)
        recent = [d for d in doms if self.old["domains"][d].get("status") == "crawled"
                  and NOW - self.old["domains"][d]["last_crawl"] < 30 * DAY]
        self.assertTrue(recent)
        calls, _ = run_start(self.dir, "edelsenior", [url(d) for d in recent], max_domains=200)
        crawled = {disc.registrable_domain(urllib.parse.urlsplit(u).hostname) for u, _ in calls}
        self.assertEqual(crawled & {d for d in recent if self.old["domains"][d]["profile"] == "edelsenior"}, set())

    def test_crash_during_migration_keeps_old_file(self):                      # 11
        st = disc.State(self.dir)
        orig = disc.json.dump

        def crash(*a, **k):
            a[1].write('{"partial": ')
            raise KeyboardInterrupt("container killed")
        disc.json.dump = crash
        try:
            with self.assertRaises(KeyboardInterrupt):
                st.save()
        finally:
            disc.json.dump = orig
        with open(os.path.join(self.dir, "state.json"), "rb") as f:
            self.assertEqual(f.read(), self.old_bytes)                      # old file untouched
        with open(os.path.join(self.dir, "state.v1.json"), "rb") as f:
            self.assertEqual(f.read(), self.old_bytes)                      # backup complete
        st = disc.State(self.dir)                                            # next run migrates again
        self.assertEqual((st.disk_version, st.migrated), (1, 199))
        st.save()
        self.assertEqual(len(read_state(self.dir)["domains"]), 199)

    def test_backup_is_never_overwritten(self):
        disc.State(self.dir).save()
        st = disc.State(self.dir)
        st.put_profile_entry("neu.de", "edelsenior", {"status": "crawled"}, NOW)
        st.save()
        self.assertEqual(read_state(self.dir, "state.v1.json"), self.old)

    def test_migrate_state_cli(self):
        out = json.loads(self.cli("migrate-state", "--dry-run").stdout)
        self.assertEqual((out["entries_migrated"], out["lossless"], out["dry_run"]), (199, True, True))
        with open(os.path.join(self.dir, "state.json"), "rb") as f:
            self.assertEqual(f.read(), self.old_bytes)                      # dry run writes nothing
        out = json.loads(self.cli("migrate-state").stdout)
        self.assertEqual(out["summary"]["unique_domains"], 199)
        self.assertEqual(read_state(self.dir)["state_version"], 2)
        self.assertEqual(read_state(self.dir, "state.v1.json"), self.old)

    def test_status_counts(self):
        out = json.loads(self.cli("status").stdout)
        self.assertTrue(out["migration_pending"])
        self.assertEqual((out["unique_domains"], out["total_domains"]), (199, 199))
        self.assertEqual(out["domain_profile_pairs"], 199)
        prof = out["profiles"]
        self.assertEqual(sum(p["domains"] for p in prof.values() if p["domains"]), 199)
        old = self.old["domains"].values()
        self.assertEqual(prof["edelsenior"]["crawled"],
                         sum(1 for e in old if e["profile"] == "edelsenior" and e.get("status") == "crawled"))
        self.assertEqual(sum(p["legacy_auth_retry"] for p in prof.values()),
                         sum(1 for e in old if disc.legacy_auth_burned(e)))
        classified = sum(sum(p["classified"].values()) for p in prof.values())
        self.assertEqual(classified, sum(len(e.get("classifications", {})) for e in old))
        for p in prof.values():
            self.assertTrue({"domains", "crawled", "retry", "rejected", "classified"} <= set(p))


class NormalizeTests(unittest.TestCase):
    def test_entry_without_profile(self):
        e, _ = disc.normalize_entry("a.de", {"status": "crawled", "collection": "stackfinder-web"})
        self.assertEqual(e["profiles"], {"stackfinder": {"status": "crawled", "collection": "stackfinder-web"}})
        e, _ = disc.normalize_entry("b.de", {"status": "crawled", "collection": "prospect-edelsenior"})
        self.assertEqual(list(e["profiles"]), ["edelsenior"])
        e, _ = disc.normalize_entry("c.de", {"status": "crawled"})             # unknown: kept, not guessed
        self.assertEqual((e["profiles"], e["unassigned"]), ({}, {"status": "crawled"}))
        self.assertEqual(disc.migration_losses({"c.de": {"status": "crawled"}}, {"c.de": e}), [])

    def test_flat_fields_written_after_migration_are_merged(self):
        """An older tool that rewrites a migrated entry adds flat fields; they are newer and win."""
        v2 = {"domain": "a.de", "profiles": {"edelsenior": {"status": "crawled", "last_crawl": 1},
                                             "stackfinder": {"status": "retry", "attempts": 1}}}
        mixed = dict(v2, profile="stackfinder", status="crawled", last_crawl=5)
        e, changed = disc.normalize_entry("a.de", mixed)
        self.assertTrue(changed)
        self.assertEqual(e["profiles"]["edelsenior"], {"status": "crawled", "last_crawl": 1})
        self.assertEqual(e["profiles"]["stackfinder"], {"status": "crawled", "attempts": 1, "last_crawl": 5})
        self.assertEqual(disc.normalize_entry("a.de", e), (e, False))

    def test_migration_losses_detects_loss(self):
        old = {"a.de": {"profile": "edelsenior", "status": "crawled", "crawl_id": "x",
                        "classifications": {"edelsenior": {"verdict": "PASS"}}}}
        new, _ = disc.normalize_entry("a.de", old["a.de"])
        self.assertEqual(disc.migration_losses(old, {"a.de": new}), [])
        broken = copy.deepcopy(new)
        del broken["profiles"]["edelsenior"]["crawl_id"]
        broken["classifications"] = {}
        self.assertEqual(len(disc.migration_losses(old, {"a.de": broken})), 2)
        self.assertEqual(disc.migration_losses(old, {}), ["a.de: domain missing"])


class PerProfileSelectionTests(Base):
    """Checks 4-7, 9, 10: selection, recrawl, backoff and retry work per (domain, profile)."""

    def state(self, domains):
        write_state(self.dir, {"paused": False, "profiles": {}, "state_version": 2, "domains": domains})

    def test_same_domain_in_two_profiles_and_b_does_not_overwrite_a(self):    # 4 + 5
        write_state(self.dir, {"paused": False, "profiles": {}, "domains": {
            "beide.de": {"profile": "edelsenior", "domain": "beide.de", "status": "crawled", "crawl_id": "a-1",
                         "collection": "edelsenior-web", "region": "Köln", "last_crawl": NOW - DAY,
                         "next_attempt": NOW + 29 * DAY, "attempts": 0, "first_seen": NOW - 9 * DAY}}})
        a_before = disc.State(self.dir).profile_entry("beide.de", "edelsenior")
        calls, report = run_start(self.dir, "stackfinder", [url("beide.de")])
        self.assertEqual(calls, [(url("beide.de"), "stackfinder-web")])
        self.assertEqual(report["selected_by_tier"]["fresh"], 1)
        e = read_state(self.dir)["domains"]["beide.de"]
        self.assertEqual(sorted(e["profiles"]), ["edelsenior", "stackfinder"])
        self.assertEqual(e["profiles"]["edelsenior"], a_before)                     # A untouched
        self.assertEqual((e["profiles"]["stackfinder"]["status"], e["profiles"]["stackfinder"]["collection"]),
                         ("crawled", "stackfinder-web"))
        self.assertEqual(e["first_seen"], NOW - 9 * DAY)                             # domain-wide field kept
        # and A again: not due, nothing crawled, B untouched
        b_before = e["profiles"]["stackfinder"]
        calls, _ = run_start(self.dir, "edelsenior", [url("beide.de")])
        self.assertEqual(calls, [])
        self.assertEqual(read_state(self.dir)["domains"]["beide.de"]["profiles"]["stackfinder"], b_before)

    def test_backoff_of_a_does_not_block_b(self):                                  # 6
        self.state({"x.de": {"domain": "x.de", "profiles": {"edelsenior": {
            "status": "retry", "error_class": "dns", "attempts": 3, "next_attempt": NOW + 5 * DAY}}},
            "y.de": {"domain": "y.de", "profiles": {"edelsenior": {
                "status": "rejected", "attempts": 1, "next_attempt": NOW + 50 * DAY}}}})
        calls, _ = run_start(self.dir, "edelsenior", [url("x.de"), url("y.de")])
        self.assertEqual(calls, [])
        calls, report = run_start(self.dir, "stackfinder", [url("x.de"), url("y.de")])
        self.assertEqual(sorted(u for u, _ in calls), [url("x.de"), url("y.de")])
        after = read_state(self.dir)["domains"]
        self.assertEqual(after["x.de"]["profiles"]["edelsenior"]["next_attempt"], NOW + 5 * DAY)
        self.assertEqual(after["y.de"]["profiles"]["edelsenior"]["status"], "rejected")
        # a failure for B gives B its own backoff and leaves A as it was
        self.state({"z.de": {"domain": "z.de", "profiles": {"edelsenior": {
            "status": "crawled", "last_crawl": NOW - 40 * DAY, "next_attempt": NOW - 10 * DAY}}}})
        orig = FakeClient.crawl_start

        def rejected(self, *a, **k):
            raise disc.ApiError(422, "crawl_rejected", "no")
        FakeClient.crawl_start = rejected
        try:
            run_start(self.dir, "stackfinder", [url("z.de")])
        finally:
            FakeClient.crawl_start = orig
        z = read_state(self.dir)["domains"]["z.de"]["profiles"]
        self.assertEqual(z["stackfinder"]["status"], "rejected")
        self.assertEqual(z["edelsenior"], {"status": "crawled", "last_crawl": NOW - 40 * DAY,
                                           "next_attempt": NOW - 10 * DAY})
        calls, report = run_start(self.dir, "edelsenior", [url("z.de")])
        self.assertEqual((len(calls), report["selected_by_tier"]["recrawl"]), (1, 1))

    def test_recrawl_of_a_does_not_touch_b(self):                                  # 7
        b = {"status": "crawled", "last_crawl": NOW - 2 * DAY, "next_attempt": NOW + 28 * DAY, "crawl_id": "b"}
        self.state({"r.de": {"domain": "r.de", "profiles": {
            "edelsenior": {"status": "crawled", "last_crawl": NOW - 40 * DAY, "next_attempt": NOW - 10 * DAY,
                           "crawl_id": "a-old"},
            "stackfinder": dict(b)}}})
        calls, report = run_start(self.dir, "edelsenior", [url("r.de")])
        self.assertEqual((calls, report["selected_by_tier"]["recrawl"]), ([(url("r.de"), "edelsenior-web")], 1))
        p = read_state(self.dir)["domains"]["r.de"]["profiles"]
        self.assertNotEqual(p["edelsenior"]["crawl_id"], "a-old")
        self.assertGreaterEqual(p["edelsenior"]["last_crawl"], NOW)
        self.assertEqual(p["stackfinder"], b)
        calls, _ = run_start(self.dir, "stackfinder", [url("r.de")])
        self.assertEqual(calls, [])                                                  # B still not due

    def test_legacy_401_is_legacy_auth_retry(self):                                # 9
        self.state({"alt401.de": {"domain": "alt401.de", "profiles": {"edelsenior": {
            "status": "error", "attempts": 5, "next_attempt": NOW + 5 * DAY,
            "last_error": "401 http_error: digest auth failed"}}},
            "alt422.de": {"domain": "alt422.de", "profiles": {"edelsenior": {
                "status": "error", "attempts": 3, "next_attempt": NOW - 10, "last_error": "422 crawl_rejected: x"}}},
            "rec.de": {"domain": "rec.de", "profiles": {"edelsenior": {
                "status": "crawled", "last_crawl": NOW - 40 * DAY, "next_attempt": NOW - DAY}}}})
        st = disc.State(self.dir)
        self.assertEqual(disc.selection_kind(st.profile_entry("alt401.de", "edelsenior"), NOW, 30),
                         "legacy_auth_retry")
        self.assertEqual(disc.selection_tier(st.profile_entry("alt401.de", "edelsenior"), NOW, 30), disc.TIER_NEW)
        self.assertEqual(disc.selection_kind(st.profile_entry("alt422.de", "edelsenior"), NOW, 30), "retry")
        # a small batch takes the legacy 401 entry like a fresh one, before recrawl and retry
        calls, report = run_start(self.dir, "edelsenior", [url("rec.de"), url("alt422.de"), url("alt401.de")],
                                  max_domains=1)
        self.assertEqual(calls, [(url("alt401.de"), "edelsenior-web")])
        self.assertEqual(report["selected_by_tier"],
                         {"fresh": 0, "legacy_auth_retry": 1, "recrawl": 0, "retry": 0, "problematic": 0})
        self.assertEqual(report["processed"][0]["tier"], "legacy_auth_retry")
        self.assertEqual(disc.state_summary(read_state(self.dir))["profiles"]["edelsenior"]["legacy_auth_retry"], 0)

    def test_globally_new(self):                                                   # 10
        self.state({"bekannt.de": {"domain": "bekannt.de", "profiles": {"edelsenior": {
            "status": "crawled", "last_crawl": NOW - DAY, "next_attempt": NOW + 29 * DAY}}}})
        _, report = run_start(self.dir, "stackfinder", [url("bekannt.de"), url("ganz-neu.de")], dry_run=True)
        got = {r["domain"]: (r["tier"], r["globally_new"]) for r in report["processed"]}
        self.assertEqual(got, {"bekannt.de": ("fresh", False), "ganz-neu.de": ("fresh", True)})
        self.assertEqual(report["selected_globally_new"], 1)
        self.assertNotIn("stackfinder", read_state(self.dir)["domains"]["bekannt.de"]["profiles"])  # dry run


class PerProfileClassificationTests(Base):
    """Check 8: classifications stay separate per profile; classify selects per (domain, profile)."""

    def setUp(self):
        super().setUp()
        self.orig_coll = td.EVIDENCE_COLLECTIONS
        td.EVIDENCE_COLLECTIONS = {"edelsenior-web", "stackfinder-web"}
        self.api = td.ApiMock()
        self.api_url = self.api.url

    def tearDown(self):
        self.api.close()
        td.EVIDENCE_COLLECTIONS = self.orig_coll
        super().tearDown()

    def test_classifications_per_profile(self):
        write_state(self.dir, {"paused": False, "profiles": {}, "domains": {
            "sonnenhof-pflege.de": {"profile": "edelsenior", "domain": "sonnenhof-pflege.de", "status": "crawled",
                                    "collection": "edelsenior-web", "region": "Köln", "crawl_id": "c1"}}})
        self.cli("classify", "--profile", "edelsenior", "--backend", "heuristic", "--delay", "0")
        e = read_state(self.dir)["domains"]["sonnenhof-pflege.de"]
        pass_rec = e["classifications"]["edelsenior"]
        self.assertEqual(pass_rec["verdict"], "PASS")
        # stackfinder has no crawl state for the domain: not selected by a profile run
        out = json.loads(self.cli("classify", "--profile", "stackfinder", "--backend", "heuristic",
                                  "--delay", "0").stdout)
        self.assertEqual(out["profiles"]["stackfinder"]["domains"], [])
        # once it is crawled for stackfinder too, it is classified for stackfinder separately
        run_start(self.dir, "stackfinder", [url("sonnenhof-pflege.de")])
        out = json.loads(self.cli("classify", "--profile", "stackfinder", "--backend", "heuristic",
                                  "--delay", "0").stdout)
        self.assertEqual(len(out["profiles"]["stackfinder"]["domains"]), 1)
        e = read_state(self.dir)["domains"]["sonnenhof-pflege.de"]
        self.assertEqual(sorted(e["classifications"]), ["edelsenior", "stackfinder"])
        self.assertEqual(e["classifications"]["edelsenior"], pass_rec)                  # A unchanged
        self.assertEqual(e["classifications"]["stackfinder"]["profile"], "stackfinder")
        self.assertNotEqual(e["classifications"]["stackfinder"]["verdict"], "PASS")     # care home is not IT
        self.assertEqual(e["profiles"]["edelsenior"]["region"], "Köln")                  # A keeps its region
        self.assertEqual(e["classifications"]["edelsenior"]["location"], "Köln")
        exp = json.loads(self.cli("export", "--profile", "edelsenior").stdout)
        self.assertEqual([r["verdict"] for r in exp["classifications"]], ["PASS"])
        st = json.loads(self.cli("status").stdout)
        self.assertEqual(st["profiles"]["edelsenior"]["classified"]["PASS"], 1)
        self.assertEqual(sum(st["profiles"]["stackfinder"]["classified"].values()), 1)
        self.assertEqual((st["unique_domains"], st["domain_profile_pairs"]), (1, 2))


@unittest.skipUnless(os.environ.get("SCOUTRO_STATE_COPY"), "set SCOUTRO_STATE_COPY to a copy of a real state.json")
class RealStateCopyTests(unittest.TestCase):
    """Runs against a COPY of a real state.json: the copy is copied again into a temp dir."""

    def test_copy_migrates_without_loss(self):
        with tempfile.TemporaryDirectory() as tmp:
            shutil.copyfile(os.environ["SCOUTRO_STATE_COPY"], os.path.join(tmp, "state.json"))
            old = read_state(tmp)
            st = disc.State(tmp)
            self.assertEqual(disc.migration_losses(old["domains"], st.data["domains"]), [])
            st.save()
            new = read_state(tmp)
            self.assertEqual(set(new["domains"]), set(old["domains"]))
            for d, e in old["domains"].items():
                self.assertEqual(new["domains"][d].get("classifications", {}), e.get("classifications", {}))
            print("\n" + json.dumps(disc.state_summary(new), indent=2), file=sys.stderr)


if __name__ == "__main__":
    unittest.main()
