/* Scoutro contributors, GPL-2.0-or-later. All persistence is disposable. */
package net.yacy.scoutro.discovery;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.data.WorkTables;
import net.yacy.scoutro.api.ApiException;

/**
 * A crawl start whose outcome cannot be established blocks every Discovery batch until an administrator defers it
 * (docs/SCOUTRO_DISCOVERY_AUTOMATION_V1.md, section 16). The deferral keeps the outcome "unknown", holds the candidate
 * and never starts anything again by itself. Hosts follow the production finding: bmw-carit.de accepted, codecamp-n.com
 * unconfirmed without a crawl profile.
 */
public class DiscoveryRecoveryTest {
    private static final String ACCEPTED = "bmw-carit.de", UNKNOWN = "codecamp-n.com";
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Path data, config, state;
    private WorkTables tables;
    private DiscoveryHeartbeat heartbeat;
    private DiscoveryService service;
    private final AtomicLong now = new AtomicLong(1_900_000_000_000L);
    private final Backend backend = new Backend();
    /** domains each job's batch starts, in order (a scripted stand-in for the Python selection) */
    private final Map<String, List<String>> plan = new HashMap<>();
    private final List<String> holds = new ArrayList<>();
    private DiscoveryService.Runner runner;
    private DiscoveryService.Runner hold;
    /** when set, a state refresh (operation status) blocks until it is released */
    private volatile CountDownLatch statusGate;

    @Before public void setup() throws Exception {
        this.data = this.tmp.newFolder("DATA").toPath();
        this.state = this.data.resolve("SCOUTRO/discovery");
        this.config = this.tmp.newFolder("runtime").toPath();
        Files.writeString(this.config.resolve("profiles.json"), "{\"profiles\":{\"new_profile\":{\"collection\":\"stackfinder-web\"},\"no_source\":{\"collection\":\"another-index\"}}}");
        Files.writeString(this.config.resolve("profiles.conf"), "new_profile = Fachbetrieb;Beratung\n");
        Files.writeString(this.config.resolve("osm_profiles.json"), "{\"extract\":\"nwr[website]\",\"profiles\":{\"new_profile\":{\"text_any\":[\"Beratung\"]}}}");
        Files.writeString(this.config.resolve("osm_regions.txt"), "test-region\n");
        Files.writeString(this.config.resolve("regions.txt"), "Teststadt\n");
        this.tables = new WorkTables(this.tmp.newFolder("WORK"));
        this.heartbeat = new DiscoveryHeartbeat(this.tables);
        // the hold always runs the real Python worker on the temporary state
        this.hold = (script, stateDir, snapshot, init, handler, timeout) -> {
            this.holds.add(init.getJSONArray("holds").getJSONObject(0).getString("domain"));
            return DiscoveryProcess.run(script, stateDir, snapshot, init, handler, timeout);
        };
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            switch (init.getString("operation")) {
                case "status":
                    final CountDownLatch gate = this.statusGate;
                    if (gate != null) assertTrue(gate.await(10, TimeUnit.SECONDS));
                    return new JsonObject().put("snapshot", new JsonObject().put("available", true));
                case "hold": return this.hold.run(script, stateDir, snapshot, init, handler, timeout);
                case "confirm":
                    for (final Object value : init.getJSONArray("confirmations")) handler.request("ack", new JsonObject().put("attempt_id", ((JsonObject) value).getString("id")));
                    return new JsonObject();
                default:
                    int processed = 0;
                    for (final String domain : this.plan.getOrDefault(init.getJSONObject("job").getString("name"), List.of())) {
                        try {
                            final JsonObject started = handler.request("crawl", new JsonObject().put("domain", domain).put("url", "https://" + domain + "/"));
                            handler.request("ack", new JsonObject().put("attempt_id", started.getString("attempt_id")));
                            processed++;
                        } catch (final ApiException e) {
                            if ("submitted_unknown".equals(e.code())) return new JsonObject().put("error", "submitted_unknown").put("report", new JsonObject().put("processed", processed));
                        }
                    }
                    return new JsonObject().put("report", new JsonObject().put("processed", processed));
            }
        };
    }
    @After public void teardown() { if (this.service != null) this.service.close(); this.tables.close(); }

    private void open() throws Exception {
        this.service = new DiscoveryService(this.data, Path.of(".").toAbsolutePath(), this.config, this.state, this.heartbeat,
                this.backend, this.now::get, JobSchema.Limits.hard(), this.runner, 30);
        this.service.recoveryWaitMs = 0; // most tests want the immediate refusal; the wait has its own test
    }
    /** past the grace period of the automatic deferral (section 16.2) */
    private void grace() { this.now.addAndGet(DiscoveryService.AUTO_DEFER_MIN_AGE_MS + 60_000); }
    private String job(final String name, final long due, final boolean backlog) throws Exception {
        final JsonObject input = new JsonObject().put("name", name).put("profile", "new_profile").put("enabled", true);
        if (backlog) {
            input.put("candidate_scope", "profile_backlog").put("sources", new JsonObject())
                    .put("discovery", new JsonObject().put("replenish", false))
                    .put("batch", new JsonObject().put("max_domains", 2).put("seed_delay_seconds", 0));
        } else {
            input.put("sources", new JsonObject().put("osm", new JsonObject().put("regions", new JsonArray().put("test-region"))));
        }
        final String id = this.service.create(input, null).getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id");
        this.service.store.change(null, root -> JobStore.job(root, id).getJSONObject("runtime").put("next_due", due));
        return id;
    }
    private void enable() throws Exception { this.heartbeat.enable(); this.service.store.change(null, root -> root.put("enabled", true)); }
    private JsonObject active() { return this.service.store.read().optJSONObject("active_run"); }
    private long revision() { return this.service.store.read().getLong("revision"); }
    private JsonObject attempt(final String domain) {
        for (final Object value : active().getJSONArray("attempts")) if (domain.equals(((JsonObject) value).getString("domain"))) return (JsonObject) value;
        throw new AssertionError("no attempt for " + domain);
    }
    private JsonObject defer(final String domain) throws Exception {
        final JsonObject result = this.service.deferAttempt(attempt(domain).getString("id"), active().getString("id"), revision(), "admin-anna");
        idle();
        return result;
    }
    /** deferAttempt wakes the coordinator; wait for that wakeup before the test drives it directly again */
    private void idle() throws Exception {
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (this.service.workerBusy()) { assertTrue("coordinator did not finish", System.nanoTime() < until); Thread.sleep(5); }
    }
    private void code(final String expected, final Throwing operation) throws Exception {
        try { operation.run(); fail("Expected " + expected); } catch (final ApiException e) { assertEquals(expected, e.code()); }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }
    private JsonObject pythonEntry(final String domain) throws Exception {
        return new JsonObject(Files.readString(this.state.resolve("state.json"))).getJSONObject("domains").getJSONObject(domain)
                .getJSONObject("profiles").getJSONObject("new_profile");
    }
    /** batch A: the production case, one accepted start and one unconfirmed start without a crawl profile */
    private String blocked() throws Exception {
        open();
        this.plan.put("Prod StackFinder Sued-West", List.of(ACCEPTED, UNKNOWN));
        this.plan.put("Other job", List.of("firm-b.de"));
        this.backend.lose.add(UNKNOWN);
        final String a = job("Prod StackFinder Sued-West", 1, false);
        job("Other job", 2, false);
        enable();
        this.service.advance(); this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase"));
        return a;
    }

    /** inside the grace period of section 16.2 the batch waits for a decision; afterwards the coordinator decides (tests below) */
    @Test public void anUnconfirmedStartWithoutProfileBlocksEveryOtherJobDuringTheGracePeriod() throws Exception {
        blocked();
        for (int i = 0; i < 3; i++) { this.now.addAndGet(5 * 60_000); this.service.advance(); }
        assertEquals("needs_review", active().getString("phase"));
        assertEquals("submitted_unknown", active().getString("error"));
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state"));
        assertEquals("accepted", attempt(ACCEPTED).getString("state"));
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1), this.backend.starts);      // the other job never ran
        final JsonObject batch = this.service.status().getJSONObject("active_batch");
        assertEquals(1, batch.getJSONArray("review").length());
        assertEquals(UNKNOWN, batch.getJSONArray("review").getJSONObject(0).getString("domain"));
        assertEquals(attempt(UNKNOWN).getString("id"), batch.getJSONArray("review").getJSONObject(0).getString("attempt_id"));
    }

    @Test public void deferringKeepsTheOutcomeUnknownHoldsTheCandidateAndLetsDiscoveryContinue() throws Exception {
        final String job = blocked();
        final String marker = attempt(UNKNOWN).getString("marker");
        final JsonObject result = defer(UNKNOWN);
        final JsonObject decision = result.getJSONObject("decision");
        assertEquals("defer", decision.getString("action")); assertEquals("unknown", decision.getString("outcome"));
        assertEquals("admin-anna", decision.getString("by")); assertFalse(decision.getBoolean("marker_found"));
        assertTrue(decision.getBoolean("candidate_held"));
        // the decision and the evidence stay with the attempt; it is never recorded as not submitted
        assertEquals("deferred", attempt(UNKNOWN).getString("state"));
        assertEquals(decision.toString(), attempt(UNKNOWN).getJSONObject("decision").toString());
        assertEquals(marker, attempt(UNKNOWN).getString("marker"));
        // the accepted crawl is kept and the batch waits for it instead of for review
        assertEquals("accepted", attempt(ACCEPTED).getString("state"));
        assertEquals("waiting_for_crawler", active().getString("phase"));
        assertFalse(active().has("error"));
        final JsonObject held = JobStore.job(this.service.store.read(), job).getJSONObject("runtime").getJSONArray("held_attempts").getJSONObject(0);
        assertEquals(UNKNOWN, held.getString("domain")); assertEquals(marker, held.getString("marker")); assertEquals("deferred", held.getString("state"));
        // the Python candidate state holds the candidate, the outcome unknown
        final JsonObject entry = pythonEntry(UNKNOWN);
        assertEquals("deferred_unknown", entry.getJSONObject("automation_attempt").getString("state"));
        assertEquals("unknown", entry.getJSONObject("automation_attempt").getString("outcome"));
        assertEquals(marker, entry.getJSONObject("automation_attempt").getString("id"));
        assertFalse(entry.has("status"));
        // the accepted crawl finishes, the batch completes, the other job runs; nothing is started twice
        this.backend.terminate(ACCEPTED);
        this.service.advance();
        assertNull(active());
        final JsonObject last = this.service.store.read().getJSONArray("history").getJSONObject(0);
        assertEquals(2, last.getInt("attempt_count")); assertEquals(1, last.getInt("deferred_count"));
        this.service.advance();
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1, "firm-b.de", 1), this.backend.starts);
    }

    @Test public void aMarkerThatShowsUpRefusesTheDeferralAndReconcilesAsAccepted() throws Exception {
        blocked();
        final String marker = attempt(UNKNOWN).getString("marker");
        this.backend.crawls.put(new JsonObject().put("id", "late-crawl").put("state", "running").put("startMarker", marker));
        try {
            this.service.deferAttempt(marker, active().getString("id"), revision(), "admin-anna");
            fail("deferred despite a crawl profile");
        } catch (final ApiException e) {
            assertEquals("attempt_found", e.code()); assertEquals(409, e.status());
        }
        idle();
        assertTrue(this.holds.isEmpty());                                          // no hold, no decision
        this.service.advance();
        assertEquals("accepted", attempt(UNKNOWN).getString("state")); assertEquals("late-crawl", attempt(UNKNOWN).getString("crawl_id"));
        assertFalse(attempt(UNKNOWN).has("decision"));
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1), this.backend.starts);
    }

    @Test public void aLostReplyWithItsMarkerNeedsNoDecision() throws Exception {
        open();
        this.plan.put("Lost reply", List.of(UNKNOWN));
        this.backend.loseReplyOnly.add(UNKNOWN);                                    // the crawl exists, only the answer is lost
        job("Lost reply", 1, false); enable();
        this.service.advance();
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        this.service.advance();
        assertEquals("accepted", attempt(UNKNOWN).getString("state"));
        code("run_not_in_review", () -> this.service.deferAttempt(marker, run, revision(), "admin-anna"));
        assertEquals(Map.of(UNKNOWN, 1), this.backend.starts);
    }

    @Test public void aRemovedProfileKeepsTheOutcomeUnknownNeverNotSubmitted() throws Exception {
        open();
        this.plan.put("Removed", List.of(UNKNOWN));
        this.backend.loseReplyOnly.add(UNKNOWN);
        job("Removed", 1, false); enable();
        this.service.advance();
        this.backend.crawls = new JsonArray();                                     // the profile is deleted before reconciliation
        this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase"));
        final String id = attempt(UNKNOWN).getString("id");
        defer(UNKNOWN);
        assertNull(active());                                                      // nothing accepted to wait for: completed
        final JsonObject root = this.service.store.read();
        final JsonObject held = JobStore.job(root, root.getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id"))
                .getJSONObject("runtime").getJSONArray("held_attempts").getJSONObject(0);
        assertEquals(id, held.getString("attempt_id")); assertEquals("deferred", held.getString("state"));
        assertEquals("unknown", held.getJSONObject("decision").getString("outcome"));
        assertFalse(root.toString().contains("not_submitted"));                     // never recorded as not submitted
        assertEquals(1, root.getJSONArray("history").getJSONObject(0).getInt("deferred_count"));
        assertEquals("deferred_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state"));
        this.service.advance();
        assertEquals(Map.of(UNKNOWN, 1), this.backend.starts);
    }

    @Test public void aLateConfirmationWhileTheBatchWaitsBecomesAccepted() throws Exception {
        final String job = blocked();
        final String marker = attempt(UNKNOWN).getString("marker");
        defer(UNKNOWN);
        assertEquals("waiting_for_crawler", active().getString("phase"));
        this.backend.crawls.put(new JsonObject().put("id", "late-crawl").put("state", "running").put("startMarker", marker));
        this.service.advance();
        final JsonObject late = attempt(UNKNOWN);
        assertEquals("accepted", late.getString("state")); assertEquals("late-crawl", late.getString("crawl_id"));
        assertTrue(late.getBoolean("state_applied"));                              // confirmed through the usual confirm path
        assertEquals("late-crawl", late.getJSONObject("decision").getJSONObject("superseded").getString("crawl_id"));
        assertEquals("accepted_late", JobStore.job(this.service.store.read(), job).getJSONObject("runtime")
                .getJSONArray("held_attempts").getJSONObject(0).getString("state"));
        this.backend.terminate(ACCEPTED); this.backend.terminate("late-crawl");
        this.service.advance();
        assertNull(active());
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1), this.backend.starts);
    }

    @Test public void theDecisionSurvivesARestart() throws Exception {
        final String job = blocked();
        defer(UNKNOWN);
        final String decision = attempt(UNKNOWN).getJSONObject("decision").toString();
        this.service.close(); open();                                              // the store with the decision validates
        assertEquals("deferred", attempt(UNKNOWN).getString("state"));
        assertEquals(decision, attempt(UNKNOWN).getJSONObject("decision").toString());
        assertEquals(1, JobStore.job(this.service.store.read(), job).getJSONObject("runtime").getJSONArray("held_attempts").length());
        this.backend.terminate(ACCEPTED);
        this.service.advance();
        assertNull(active());                                                      // the restarted coordinator completes the batch
        this.service.advance();
        assertEquals("Other job", active().getJSONObject("job").getString("name")); // and dispatches the other job
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1, "firm-b.de", 1), this.backend.starts);
    }

    @Test public void concurrentRecoveryAndWakeupsAreRefusedWhileOneDecides() throws Exception {
        blocked();
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        final long seen = revision();
        final CountDownLatch inside = new CountDownLatch(1), release = new CountDownLatch(1);
        final DiscoveryService.Runner real = this.hold;
        this.hold = (script, stateDir, snapshot, init, handler, timeout) -> {
            inside.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS));
            return real.run(script, stateDir, snapshot, init, handler, timeout);
        };
        final AtomicReference<Object> first = new AtomicReference<>();
        final Thread thread = new Thread(() -> {
            try { first.set(this.service.deferAttempt(marker, run, seen, "admin-anna")); } catch (final Exception e) { first.set(e); }
        });
        thread.start();
        assertTrue(inside.await(10, TimeUnit.SECONDS));
        code("worker_busy", () -> this.service.deferAttempt(marker, run, seen, "admin-ben"));
        assertEquals("busy", this.service.tick().getString("reason"));               // no wakeup while the decision is taken
        release.countDown(); thread.join(10_000);
        assertTrue(String.valueOf(first.get()), first.get() instanceof JsonObject);
        idle();
        assertEquals("admin-anna", attempt(UNKNOWN).getJSONObject("decision").getString("by"));
        assertEquals(1, this.holds.size());
    }

    @Test public void aRepeatedRequestChangesNothing() throws Exception {
        this.plan.put("Two unknown", List.of(UNKNOWN, "second-unknown.de"));
        this.backend.lose.add(UNKNOWN); this.backend.lose.add("second-unknown.de");
        // the scripted batch goes on after the first unknown start, so that two starts are open
        final DiscoveryService.Runner inner = this.runner;
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            if (!"run".equals(init.getString("operation"))) return inner.run(script, stateDir, snapshot, init, handler, timeout);
            for (final String domain : this.plan.get("Two unknown")) {
                try { handler.request("crawl", new JsonObject().put("domain", domain).put("url", "https://" + domain + "/")); }
                catch (final ApiException e) { assertEquals("submitted_unknown", e.code()); }
            }
            return new JsonObject().put("error", "submitted_unknown");
        };
        open();
        job("Two unknown", 1, false); enable();
        this.service.advance(); this.service.advance(); this.service.advance();
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        final long seen = revision();
        this.service.deferAttempt(marker, run, seen, "admin-anna"); idle();
        code("revision_conflict", () -> this.service.deferAttempt(marker, run, seen, "admin-anna"));
        code("already_deferred", () -> this.service.deferAttempt(marker, run, revision(), "admin-anna"));
        assertEquals("needs_review", active().getString("phase"));                  // the second start still needs a decision
        assertEquals(1, this.holds.size());
        defer("second-unknown.de");
        assertNull(active());                                                      // nothing accepted to wait for: completed
        assertEquals(2, this.service.store.read().getJSONArray("history").getJSONObject(0).getInt("deferred_count"));
        this.service.advance();
        assertEquals(2, this.backend.starts.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test public void aConcurrentEditRefusesTheDecisionAndTheRetryIsIdempotent() throws Exception {
        final String job = blocked();
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        final long seen = revision();
        final DiscoveryService.Runner real = this.hold;
        this.hold = (script, stateDir, snapshot, init, handler, timeout) -> {
            final JsonObject answer = real.run(script, stateDir, snapshot, init, handler, timeout);
            this.service.edit(job, new JsonObject().put("name", "Renamed meanwhile"), revision()); // another administrator
            return answer;
        };
        code("revision_conflict", () -> this.service.deferAttempt(marker, run, seen, "admin-anna"));
        idle();
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state"));      // nothing decided
        assertEquals("needs_review", active().getString("phase"));
        assertEquals("deferred_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state")); // held already: safe
        this.hold = real;
        defer(UNKNOWN);
        assertEquals("deferred", attempt(UNKNOWN).getString("state"));
        assertEquals(2, this.holds.size());
        assertEquals("deferred_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state"));
    }

    @Test public void staleIdsWrongPhaseAndMissingRevisionAreRefused() throws Exception {
        open();
        this.plan.put("Job", List.of(ACCEPTED, UNKNOWN));
        this.backend.lose.add(UNKNOWN);
        job("Job", 1, false); enable();
        this.service.advance();
        final String marker = attempt(UNKNOWN).getString("id"), accepted = attempt(ACCEPTED).getString("id"), run = active().getString("id");
        assertEquals("needs_reconcile", active().getString("phase"));
        code("run_not_in_review", () -> this.service.deferAttempt(marker, run, revision(), "admin-anna")); // not yet reconciled
        this.service.advance(); this.service.advance();
        code("revision_required", () -> this.service.deferAttempt(marker, run, null, "admin-anna"));
        code("invalid_request", () -> this.service.deferAttempt("../x", run, revision(), "admin-anna"));
        code("invalid_request", () -> this.service.deferAttempt(marker, "not-a-run", revision(), "admin-anna"));
        code("stale_run", () -> this.service.deferAttempt(marker, "00000000-0000-4000-8000-000000000000", revision(), "admin-anna"));
        code("attempt_unknown", () -> this.service.deferAttempt("0123456789abcdef0123456789abcdef", run, revision(), "admin-anna"));
        code("attempt_not_unresolved", () -> this.service.deferAttempt(accepted, run, revision(), "admin-anna"));
        assertTrue(this.holds.isEmpty());
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state"));
    }

    @Test public void anUnreadableCandidateStateChangesNothing() throws Exception {
        blocked();
        Files.createDirectories(this.state);
        Files.writeString(this.state.resolve("state.json"), "INVALID");
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        code("state_unreadable", () -> this.service.deferAttempt(marker, run, revision(), "admin-anna"));
        assertEquals("INVALID", Files.readString(this.state.resolve("state.json")));
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state"));
        assertEquals("needs_review", active().getString("phase"));
    }

    /** End to end through the real Python worker: the deferred candidate is never selected again, others are. */
    @Test public void realWorkerNeverStartsTheDeferredCandidateAgain() throws Exception {
        final Path fixture = this.tmp.newFile("bridge.py").toPath();
        Files.writeString(fixture, "import importlib.machinery, importlib.util, sys\n"
                + "loader=importlib.machinery.SourceFileLoader('bridge_fixture', '" + Path.of("tools/scoutro/scoutro-discovery").toAbsolutePath() + "')\n"
                + "spec=importlib.util.spec_from_loader(loader.name, loader)\n"
                + "module=importlib.util.module_from_spec(spec);sys.modules[loader.name]=module;loader.exec_module(module)\n"
                + "module.precheck_candidate=lambda *args: None\nmodule.main()\n");
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            if ("hold".equals(init.getString("operation"))) this.holds.add(init.getJSONArray("holds").getJSONObject(0).getString("domain"));
            return DiscoveryProcess.run(fixture, stateDir, snapshot, init, handler, timeout);
        };
        Files.createDirectories(this.state);
        final StringBuilder domains = new StringBuilder();
        for (final String domain : List.of(ACCEPTED, UNKNOWN, "firm-a.de", "firm-b.de", "firm-c.de")) {
            if (domains.length() > 0) domains.append(',');
            domains.append('"').append(domain).append("\":{\"domain\":\"").append(domain).append("\",\"first_seen\":1,\"profiles\":{\"new_profile\":{")
                    .append("\"collection\":\"stackfinder-web\",\"candidate_url\":\"https://").append(domain).append("/\",\"first_seen\":1}}}");
        }
        Files.writeString(this.state.resolve("state.json"), "{\"state_version\":2,\"domains\":{" + domains + "}}");
        this.backend.lose.add(UNKNOWN);
        open();
        final String job = job("Prod StackFinder Sued-West", 1, true);
        enable();
        this.service.advance();                                                    // bmw-carit.de accepted, codecamp-n.com unknown
        assertEquals("submitted_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state"));
        this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase"));
        defer(UNKNOWN);
        assertEquals("deferred_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state"));
        this.backend.terminate(ACCEPTED);
        this.service.advance();
        assertNull(active());
        for (int round = 0; round < 3; round++) {                                  // the backlog drains without codecamp-n.com
            this.now.addAndGet(3_600_000);
            this.service.store.change(null, root -> JobStore.job(root, job).getJSONObject("runtime").put("next_due", 0));
            this.service.advance();
            for (final Object value : this.backend.crawls) ((JsonObject) value).put("state", "terminated");
            this.service.advance();
        }
        // a crawl for the deferred marker showing up after the batch completed starts nothing either
        this.backend.crawls.put(new JsonObject().put("id", "late").put("state", "terminated").put("startMarker",
                JobStore.job(this.service.store.read(), job).getJSONObject("runtime").getJSONArray("held_attempts").getJSONObject(0).getString("marker")));
        this.now.addAndGet(3_600_000);
        this.service.store.change(null, root -> JobStore.job(root, job).getJSONObject("runtime").put("next_due", 0));
        this.service.advance();
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1, "firm-a.de", 1, "firm-b.de", 1, "firm-c.de", 1), this.backend.starts);
        assertEquals("deferred_unknown", pythonEntry(UNKNOWN).getJSONObject("automation_attempt").getString("state"));
        assertEquals("crawled", pythonEntry(ACCEPTED).getString("status"));
        assertEquals(List.of(UNKNOWN), this.holds);
    }

    // ---- 0.11.5, section 16.2: the coordinator defers an unconfirmed start itself after the grace period ----

    @Test public void theCoordinatorDefersAnUnconfirmedStartAfterTheGracePeriodAndDiscoveryContinues() throws Exception {
        final String job = blocked();                                               // reconciled twice, but younger than the grace
        assertEquals("needs_review", active().getString("phase"));
        final JsonObject review = this.service.status().getJSONObject("active_batch").getJSONArray("review").getJSONObject(0);
        assertEquals(2, review.getInt("reconciled")); assertEquals(0, review.getInt("auto_defer_after"));
        this.now.addAndGet(5 * 60_000); this.service.advance();                     // still inside the grace: nothing decided
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state")); assertTrue(this.holds.isEmpty());
        grace(); this.service.advance();
        final JsonObject deferred = attempt(UNKNOWN);
        assertEquals("deferred", deferred.getString("state"));
        final JsonObject decision = deferred.getJSONObject("decision");
        assertEquals(DiscoveryService.SYSTEM_ACTOR, decision.getString("by")); assertTrue(decision.getBoolean("automatic"));
        assertEquals("unknown", decision.getString("outcome")); assertFalse(decision.getBoolean("marker_found")); assertTrue(decision.getBoolean("candidate_held"));
        assertEquals(List.of(UNKNOWN), this.holds);                                 // held through the real worker, once
        final JsonObject entry = pythonEntry(UNKNOWN);
        assertEquals("deferred_unknown", entry.getJSONObject("automation_attempt").getString("state"));
        assertEquals(DiscoveryService.SYSTEM_ACTOR, entry.getJSONObject("automation_attempt").getString("decided_by"));
        assertFalse(entry.has("status"));                                           // never "not submitted", never crawled
        final JsonObject held = JobStore.job(this.service.store.read(), job).getJSONObject("runtime").getJSONArray("held_attempts").getJSONObject(0);
        assertEquals(UNKNOWN, held.getString("domain")); assertTrue(held.getJSONObject("decision").getBoolean("automatic"));
        // the batch left review in the same wakeup and waits for its accepted crawl; no second start anywhere
        assertEquals("waiting_for_crawler", active().getString("phase")); assertFalse(active().has("error"));
        assertEquals("accepted", attempt(ACCEPTED).getString("state"));
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1), this.backend.starts);
        this.backend.terminate(ACCEPTED); this.service.advance();
        assertNull(active());
        final JsonObject last = this.service.store.read().getJSONArray("history").getJSONObject(0);
        assertEquals(1, last.getInt("deferred_count")); assertEquals(1, last.getInt("automatic_deferred_count"));
        this.service.advance();                                                     // the other job runs
        assertEquals(Map.of(ACCEPTED, 1, UNKNOWN, 1, "firm-b.de", 1), this.backend.starts);
        for (int round = 0; round < 3; round++) { this.now.addAndGet(3_600_000); this.service.advance(); }
        assertEquals(1, (int) this.backend.starts.get(UNKNOWN));                   // the held website is never started again
    }

    @Test public void twoUnconfirmedStartsAreBothDeferredAndTheBatchCompletes() throws Exception {
        this.plan.put("Two unknown", List.of(UNKNOWN, "second-unknown.de"));
        this.backend.lose.add(UNKNOWN); this.backend.lose.add("second-unknown.de");
        final DiscoveryService.Runner inner = this.runner;
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            if (!"run".equals(init.getString("operation")) || !"Two unknown".equals(init.getJSONObject("job").getString("name"))) {
                return inner.run(script, stateDir, snapshot, init, handler, timeout);
            }
            for (final String domain : this.plan.get("Two unknown")) {
                try { handler.request("crawl", new JsonObject().put("domain", domain).put("url", "https://" + domain + "/")); }
                catch (final ApiException e) { assertEquals("submitted_unknown", e.code()); }
            }
            return new JsonObject().put("error", "submitted_unknown");
        };
        open();
        this.plan.put("Other job", List.of("firm-b.de"));
        job("Two unknown", 1, false); job("Other job", 2, false); enable();
        this.service.advance(); this.service.advance(); this.service.advance();
        assertEquals(2, this.service.status().getJSONObject("active_batch").getJSONArray("review").length());
        grace(); this.service.advance();
        assertNull(active());                                                      // both deferred, nothing accepted to wait for
        final JsonObject last = this.service.store.read().getJSONArray("history").getJSONObject(0);
        assertEquals(2, last.getInt("deferred_count")); assertEquals(2, last.getInt("automatic_deferred_count"));
        assertEquals(List.of(UNKNOWN, "second-unknown.de"), this.holds);
        this.service.advance();
        assertEquals(Map.of(UNKNOWN, 1, "second-unknown.de", 1, "firm-b.de", 1), this.backend.starts);
    }

    @Test public void aProfileThatTurnsUpDuringTheGracePeriodIsAcceptedNotDeferred() throws Exception {
        blocked();
        final String marker = attempt(UNKNOWN).getString("marker");
        grace();
        this.backend.crawls.put(new JsonObject().put("id", "late-crawl").put("state", "running").put("startMarker", marker));
        this.service.advance();
        assertEquals("accepted", attempt(UNKNOWN).getString("state")); assertEquals("late-crawl", attempt(UNKNOWN).getString("crawl_id"));
        assertTrue(this.holds.isEmpty()); assertFalse(attempt(UNKNOWN).has("decision"));
        assertEquals("waiting_for_crawler", active().getString("phase"));
    }

    @Test public void aHoldThatFailsPostponesTheAutomaticDeferralToTheNextHeartbeat() throws Exception {
        blocked();
        Files.createDirectories(this.state);
        final Path file = this.state.resolve("state.json");
        final String valid = Files.exists(file) ? Files.readString(file) : "{\"state_version\":2,\"domains\":{}}";
        Files.writeString(file, "INVALID");
        grace(); this.service.advance();
        assertEquals("needs_review", active().getString("phase"));
        assertEquals("submitted_unknown", attempt(UNKNOWN).getString("state"));
        assertEquals("state_unreadable", attempt(UNKNOWN).getString("auto_defer_error"));
        assertEquals("state_unreadable", this.service.status().getJSONObject("active_batch").getJSONArray("review").getJSONObject(0).getString("auto_defer_error"));
        assertEquals("INVALID", Files.readString(file));                           // the unreadable state is left alone
        Files.writeString(file, valid);
        this.service.advance();
        assertEquals("deferred", attempt(UNKNOWN).getString("state")); assertFalse(attempt(UNKNOWN).has("auto_defer_error"));
        assertEquals("waiting_for_crawler", active().getString("phase"));
    }

    @Test public void theAutomaticDecisionSurvivesARestartAndAnOlderStoreStillLoads() throws Exception {
        final String job = blocked();
        grace(); this.service.advance();
        final String decision = attempt(UNKNOWN).getJSONObject("decision").toString();
        this.service.close(); open();                                              // reconciled, reconciled_at, decision.automatic validate
        assertEquals("deferred", attempt(UNKNOWN).getString("state"));
        assertEquals(decision, attempt(UNKNOWN).getJSONObject("decision").toString());
        assertEquals(1, JobStore.job(this.service.store.read(), job).getJSONObject("runtime").getJSONArray("held_attempts").length());
        this.backend.terminate(ACCEPTED); this.service.advance();
        assertNull(active());
    }

    @Test public void aRemovedProfileOfAnAcceptedCrawlEndsTheBatchWithTheResultUnknown() throws Exception {
        open();
        this.plan.put("Accepted only", List.of(ACCEPTED));
        this.plan.put("Other job", List.of("firm-b.de"));
        job("Accepted only", 1, false); job("Other job", 2, false); enable();
        this.service.advance();
        assertEquals("waiting_for_crawler", active().getString("phase"));
        this.backend.crawls = new JsonArray();                                     // stopped by hand or "delete terminated profiles"
        this.service.advance();
        assertNull(active());                                                      // not a dead end any more
        final JsonObject last = this.service.store.read().getJSONArray("history").getJSONObject(0);
        assertEquals(1, last.getInt("profile_removed_count")); assertEquals(1, last.getInt("attempt_count"));
        assertFalse(last.has("deferred_count"));
        assertFalse(last.toString().contains("not_submitted"));
        this.service.advance();
        assertEquals(Map.of(ACCEPTED, 1, "firm-b.de", 1), this.backend.starts);   // the other job runs, the website is not restarted
    }

    @Test public void resumeAndEnableWakeTheCoordinatorAtOnce() throws Exception {
        open();
        this.plan.put("Job", List.of(ACCEPTED));
        job("Job", 1, false);
        this.service.global("enable", revision()); idle();                          // enable ticks: the due job ran without a heartbeat
        assertEquals(Map.of(ACCEPTED, 1), this.backend.starts);
        this.backend.terminate(ACCEPTED); this.service.advance(); assertNull(active());
        this.service.global("pause", revision());
        this.plan.put("Job", List.of("firm-b.de"));
        this.service.store.change(null, root -> JobStore.job(root, root.getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id")).getJSONObject("runtime").put("next_due", 0));
        assertEquals("paused", this.service.tick().getString("reason"));
        this.service.global("resume", revision()); idle();
        assertEquals(Map.of(ACCEPTED, 1, "firm-b.de", 1), this.backend.starts);
    }

    @Test public void aRecoveryDecisionWaitsForARunningStateRefreshInsteadOfRefusing() throws Exception {
        blocked();
        this.service.recoveryWaitMs = 5_000;
        final String marker = attempt(UNKNOWN).getString("id"), run = active().getString("id");
        final long seen = revision();
        this.statusGate = new CountDownLatch(1);
        this.now.addAndGet(60_000);
        this.service.status();                                                     // starts the state refresh, which now holds the guard
        final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!this.service.workerBusy()) { assertTrue(System.nanoTime() < until); Thread.sleep(5); }
        final CountDownLatch gate = this.statusGate;
        new Thread(() -> { try { Thread.sleep(300); } catch (final InterruptedException ignored) { } gate.countDown(); }).start();
        final JsonObject result = this.service.deferAttempt(marker, run, seen, "admin-anna"); // waited, then decided
        this.statusGate = null; idle();
        assertEquals("admin-anna", result.getJSONObject("decision").getString("by"));
        assertFalse(result.getJSONObject("decision").getBoolean("automatic"));
        assertEquals("deferred", attempt(UNKNOWN).getString("state"));
    }

    private static final class Backend implements DiscoveryService.Backend {
        final Map<String, Integer> starts = new java.util.TreeMap<>();
        /** no crawl profile is created and the answer is lost */
        final java.util.Set<String> lose = new java.util.HashSet<>();
        /** the crawl profile is created, only the answer is lost */
        final java.util.Set<String> loseReplyOnly = new java.util.HashSet<>();
        JsonArray crawls = new JsonArray();
        public JsonObject capacity() { return new JsonObject().put("allowed", true).put("reason", ""); }
        public JsonArray crawls() { return this.crawls; }
        public JsonObject search(final JsonObject p) { throw new AssertionError("Unexpected source call"); }
        public JsonObject start(final JsonObject p, final String marker) throws ApiException {
            final String host = java.net.URI.create(p.getString("url")).getHost();
            this.starts.merge(host, 1, Integer::sum);
            if (this.lose.contains(host)) throw new ApiException(503, "upstream_unavailable", "lost reply");
            final JsonObject crawl = new JsonObject().put("id", "crawl-" + host).put("state", "running").put("startMarker", marker).put("host", host);
            this.crawls.put(crawl);
            if (this.loseReplyOnly.contains(host)) throw new ApiException(503, "upstream_unavailable", "lost reply");
            return new JsonObject(crawl.toString());
        }
        void terminate(final String hostOrId) {
            for (final Object value : this.crawls) {
                final JsonObject crawl = (JsonObject) value;
                if (hostOrId.equals(crawl.optString("host")) || hostOrId.equals(crawl.getString("id"))) crawl.put("state", "terminated");
            }
        }
    }
}
