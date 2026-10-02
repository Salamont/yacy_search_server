/* Scoutro contributors, GPL-2.0-or-later. All persistence is disposable. */
package net.yacy.scoutro.discovery;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.data.WorkTables;
import net.yacy.scoutro.api.ApiException;

public class DiscoveryAutomationTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Path data, config, state;
    private WorkTables tables;
    private DiscoveryHeartbeat heartbeat;
    private DiscoveryService service;
    private final AtomicLong now = new AtomicLong(1_900_000_000_000L);
    private final FakeBackend backend = new FakeBackend();
    private DiscoveryService.Runner runner;
    private boolean acknowledge;

    @Before public void setup() throws Exception {
        this.data = this.tmp.newFolder("DATA").toPath();
        this.state = this.data.resolve("SCOUTRO/discovery");
        this.config = this.tmp.newFolder("runtime").toPath();
        Files.writeString(this.config.resolve("profiles.json"), "{\"profiles\":{\"new_profile\":{\"collection\":\"custom-index\"},\"no_source\":{\"collection\":\"another-index\"}}}");
        Files.writeString(this.config.resolve("profiles.conf"), "new_profile = Fachbetrieb;Beratung\n");
        Files.writeString(this.config.resolve("osm_profiles.json"), "{\"extract\":\"nwr[website]\",\"profiles\":{\"new_profile\":{\"text_any\":[\"Beratung\"]}}}");
        Files.writeString(this.config.resolve("osm_regions.txt"), "test-region\nother-region\n");
        Files.writeString(this.config.resolve("regions.txt"), "Teststadt\nAndere Stadt\n");
        this.tables = new WorkTables(this.tmp.newFolder("WORK"));
        this.heartbeat = new DiscoveryHeartbeat(this.tables);
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            if ("status".equals(init.getString("operation"))) return new JsonObject().put("snapshot", new JsonObject().put("available", true));
            if ("confirm".equals(init.getString("operation"))) {
                for (Object value : init.getJSONArray("confirmations")) handler.request("ack", new JsonObject().put("attempt_id", ((JsonObject) value).getString("id")));
                return new JsonObject();
            }
            try {
                final JsonObject result = handler.request("crawl", new JsonObject().put("domain", "example.com").put("url", "https://example.com/"));
                if (this.acknowledge) handler.request("ack", new JsonObject().put("attempt_id", result.getString("attempt_id")));
                return new JsonObject().put("report", new JsonObject().put("processed", 1));
            } catch (ApiException e) { return new JsonObject().put("error", e.code()); }
        };
    }
    @After public void teardown() { if (this.service != null) this.service.close(); this.tables.close(); }
    private void open() throws Exception {
        this.service = new DiscoveryService(this.data, Path.of(".").toAbsolutePath(), this.config, this.state, this.heartbeat,
                this.backend, this.now::get, JobSchema.Limits.hard(), this.runner, 30);
    }
    private JsonObject input(String name) { return new JsonObject().put("name", name).put("profile", "new_profile").put("enabled", true)
            .put("sources", new JsonObject().put("osm", new JsonObject().put("regions", new JsonArray().put("test-region")))); }
    private String create(String name) throws Exception { return this.service.create(input(name), null).getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id"); }
    private void enable() throws Exception { this.heartbeat.enable(); this.service.store.change(null, root -> root.put("enabled", true)); }
    private JsonObject active() { return this.service.store.read().getJSONObject("active_run"); }
    private void code(String expected, Throwing operation) throws Exception {
        try { operation.run(); fail("Expected " + expected); } catch (ApiException e) { assertEquals(expected, e.code()); }
    }
    @FunctionalInterface interface Throwing { void run() throws Exception; }

    @Test public void dynamicCatalogAndSourceRegions() throws Exception {
        RuntimeCatalog cat = RuntimeCatalog.load(this.config);
        JsonObject profile = cat.json().getJSONArray("profiles").getJSONObject(0);
        assertEquals("new_profile", profile.getString("id")); assertTrue(profile.getJSONObject("sources").getBoolean("osm"));
        assertTrue(profile.getJSONObject("sources").getBoolean("freeworld"));
        assertFalse(cat.json().getJSONArray("profiles").getJSONObject(1).getJSONObject("sources").getBoolean("osm"));
        assertEquals("test-region", cat.json().getJSONObject("regions").getJSONArray("osm").getString(0));
        assertEquals("Teststadt", cat.json().getJSONObject("regions").getJSONArray("freeworld").getString(0));
    }
    @Test public void noRepositoryFallbackAndExplicitCollection() throws Exception {
        code("runtime_config_invalid", () -> RuntimeCatalog.load(this.tmp.newFolder("missing").toPath()));
        Files.writeString(this.config.resolve("profiles.json"), "{\"profiles\":{\"new_profile\":{}}}");
        code("runtime_config_invalid", () -> RuntimeCatalog.load(this.config));
    }
    @Test public void removedProfileAndRegionBlockWithoutReplacement() throws Exception {
        open(); String id = create("Job");
        Files.writeString(this.config.resolve("osm_regions.txt"), "other-region\n");
        assertEquals("region_removed", this.service.jobs(id).getJSONArray("jobs").getJSONObject(0).getString("waiting_reason"));
        Files.writeString(this.config.resolve("profiles.json"), "{\"profiles\":{}}");
        assertEquals("profile_removed", this.service.jobs(id).getJSONArray("jobs").getJSONObject(0).getString("waiting_reason"));
    }
    @Test public void configRevisionFrozenAndTamperingDetected() throws Exception {
        RuntimeCatalog cat = RuntimeCatalog.load(this.config); Path frozen = cat.freeze(this.data.resolve("snapshots"));
        Files.writeString(this.config.resolve("regions.txt"), "Neue Stadt\n");
        assertNotEquals(cat.revision(), RuntimeCatalog.load(this.config).revision());
        assertEquals("Teststadt\nAndere Stadt\n", Files.readString(frozen.resolve("regions.txt")));
        Files.writeString(frozen.resolve("profiles.json"), "{}");
        assertThrows(IOException.class, () -> cat.freeze(this.data.resolve("snapshots")));
    }
    @Test public void rootPrecedencePortable() {
        assertEquals(Path.of("/env"), DiscoveryService.resolveRoot("/env", "/setting", this.data));
        assertEquals(Path.of("/setting"), DiscoveryService.resolveRoot("", "/setting", this.data));
        assertEquals(this.data, DiscoveryService.resolveRoot(null, "", this.data));
    }
    @Test public void schemaRejectsSecretsForceLimitsAndMixedRegionTypes() throws Exception {
        JsonObject job = JobSchema.defaults(input("Test"));
        JobSchema.validate(job, JobSchema.Limits.hard()); RuntimeCatalog.load(this.config).validate(job);
        job.put("force", true); code("invalid_request", () -> JobSchema.validate(job, JobSchema.Limits.hard())); job.remove("force");
        job.getJSONObject("schedule").put("every_minutes", 9); code("invalid_request", () -> JobSchema.validate(job, JobSchema.Limits.hard()));
        job.getJSONObject("schedule").put("every_minutes", 10); job.getJSONObject("batch").put("max_domains", 501);
        code("invalid_request", () -> JobSchema.validate(job, JobSchema.Limits.hard()));
        job.getJSONObject("sources").getJSONObject("osm").put("regions", new JsonArray().put("Teststadt"));
        code("region_removed", () -> RuntimeCatalog.load(this.config).validate(job));
    }
    @Test public void storeRevisionRestartAndExportWithoutRuntime() throws Exception {
        open(); String id = create("Test"); long revision = this.service.store.read().getLong("revision");
        code("revision_conflict", () -> this.service.edit(id, new JsonObject().put("name", "Conflict"), revision - 1));
        this.service.edit(id, new JsonObject().put("name", "Changed"), revision);
        this.service.close(); open();
        assertEquals("Changed", this.service.export().getJSONArray("jobs").getJSONObject(0).getString("name"));
        assertFalse(this.service.export().toString().contains("last_served"));
    }
    @Test public void invalidStoreIsNeverOverwritten() throws Exception {
        Path path = this.data.resolve("SETTINGS/scoutro-discovery-jobs.json"); Files.createDirectories(path.getParent()); Files.writeString(path, "{not json}");
        assertThrows(IOException.class, this::open); assertEquals("{not json}", Files.readString(path));
    }
    @Test public void malformedRunIsNeverOverwritten() throws Exception {
        open(); create("Job"); this.service.store.change(null, root -> root.put("active_run", new JsonObject().put("attempts", new JsonArray())));
        String bad = Files.readString(this.data.resolve("SETTINGS/scoutro-discovery-jobs.json")); this.service.close();
        assertThrows(IOException.class, this::open); assertEquals(bad, Files.readString(this.data.resolve("SETTINGS/scoutro-discovery-jobs.json")));
    }
    @Test public void heartbeatExplicitUniquePersistentAndNoSilentRepair() throws Exception {
        assertFalse(this.heartbeat.status().getBoolean("installed")); this.heartbeat.enable(); this.heartbeat.enable();
        assertEquals(1, this.heartbeat.status().getInt("count")); assertEquals(10, this.heartbeat.status().getInt("interval_minutes"));
        this.heartbeat.disable(); assertFalse(new DiscoveryHeartbeat(this.tables).status().getBoolean("enabled"));
        open(); create("Job"); this.service.store.change(null, root -> root.put("enabled", true)); this.service.advance(); assertEquals(0, this.backend.starts);
        assertFalse(this.heartbeat.status().getBoolean("enabled"));
        this.tables.clear(WorkTables.TABLE_API_NAME); this.service.advance(); assertFalse(this.heartbeat.status().getBoolean("installed"));
    }
    @Test public void duplicateHeartbeatBlocksUntilExplicitRepair() throws Exception {
        this.heartbeat.enable(); var original = this.tables.iterator(WorkTables.TABLE_API_NAME).next();
        this.tables.insert(WorkTables.TABLE_API_NAME, new java.util.LinkedHashMap<>(original));
        assertEquals(2, this.heartbeat.status().getInt("count")); assertFalse(this.heartbeat.status().getBoolean("enabled"));
        this.heartbeat.enable(); assertEquals(1, this.heartbeat.status().getInt("count"));
    }
    @Test public void fairnessAndNoCatchupStorm() throws Exception {
        this.runner = (a,b,c,init,h,t) -> new JsonObject(); open(); String first = create("First"), second = create("Second"); enable();
        this.service.store.change(null, root -> { JobStore.job(root, first).getJSONObject("runtime").put("next_due", 1); JobStore.job(root, second).getJSONObject("runtime").put("next_due", 2); });
        this.service.advance(); JsonObject root = this.service.store.read();
        assertEquals(first, root.getJSONArray("history").getJSONObject(0).getString("job_id"));
        assertEquals(this.now.get() + 3600000, JobStore.job(root, first).getJSONObject("runtime").getLong("next_due"));
        this.service.advance(); this.service.advance(); assertEquals(2, this.service.store.read().getJSONArray("history").length());
    }
    @Test public void tieUsesLongestNotServed() throws Exception {
        open(); String first = create("First"), second = create("Second");
        JsonObject root = this.service.store.read(); JobStore.job(root, first).getJSONObject("runtime").put("last_served", 100);
        assertEquals(second, DiscoveryService.choose(root, this.now.get()).getJSONObject("definition").getString("id"));
    }
    @Test public void acceptedIntentPrecedesEffectAndRecoveryNeverResubmits() throws Exception {
        open(); create("Job"); enable(); this.backend.before = () -> assertEquals("prepared", active().getJSONArray("attempts").getJSONObject(0).getString("state"));
        this.service.advance(); assertEquals(1, this.backend.starts); assertFalse(active().getJSONArray("attempts").getJSONObject(0).getBoolean("state_applied"));
        this.service.close(); open(); this.service.advance(); assertTrue(active().getJSONArray("attempts").getJSONObject(0).getBoolean("state_applied"));
        assertEquals(1, this.backend.starts); this.backend.crawls.getJSONObject(0).put("state", "terminated"); this.service.advance();
        assertTrue(this.service.store.read().isNull("active_run")); assertEquals(1, this.backend.starts);
    }
    @Test public void threeTerminatedCrawlsConfirmAndCompleteThroughRealPython() throws Exception {
        final int[] acknowledgements = {0};
        this.runner = (script, stateDir, snapshot, init, handler, timeout) -> {
            if ("confirm".equals(init.getString("operation"))) {
                assertEquals(3, init.getJSONArray("confirmations").length());
                assertEquals(active().getString("config_revision"), RuntimeCatalog.load(snapshot).revision());
                return DiscoveryProcess.run(script, stateDir, snapshot, init, (action, params) -> {
                    assertEquals("ack", action);
                    final JsonObject persisted = new JsonObject(Files.readString(stateDir.resolve("state.json")));
                    assertEquals(3, persisted.getJSONObject("domains").length());
                    final JsonObject reply = handler.request(action, params);
                    for (final Object value : active().getJSONArray("attempts")) {
                        final JsonObject attempt = (JsonObject) value;
                        if (params.getString("attempt_id").equals(attempt.getString("id"))) assertTrue(attempt.getBoolean("state_applied"));
                    }
                    acknowledgements[0]++;
                    return reply;
                }, timeout);
            }
            assertEquals("run", init.getString("operation"));
            for (final String domain : java.util.List.of("example.com", "example.org", "example.net")) {
                handler.request("crawl", new JsonObject().put("domain", domain).put("url", "https://" + domain + "/"));
            }
            return new JsonObject().put("report", new JsonObject().put("processed", 5));
        };
        Files.createDirectories(this.state);
        Files.writeString(this.state.resolve("state.json"), "{\"state_version\":2,\"paused\":true,\"domains\":{\"example.com\":{\"profiles\":{\"other\":{\"classification\":\"KEEP\"}}}}}");
        open(); final String jobId = create("Three crawls"); enable();
        this.service.advance();
        final String runId = active().getString("id");
        assertEquals("waiting_for_crawler", active().getString("phase"));
        for (final Object value : active().getJSONArray("attempts")) {
            final JsonObject attempt = (JsonObject) value;
            assertEquals("accepted", attempt.getString("state"));
            assertFalse(attempt.getBoolean("state_applied"));
        }
        for (final Object value : this.backend.crawls) ((JsonObject) value).put("state", "terminated");
        // Existing YaCy profiles without a Scoutro marker are returned as JSON null.
        this.backend.crawls.put(new JsonObject().put("id", "ordinary-yacy-crawl").put("state", "terminated")
                .put("startMarker", JsonObject.NULL));
        this.backend.crawls.put(new JsonObject().put("id", "empty-marker-crawl").put("state", "running").put("startMarker", ""));
        this.backend.crawls.put(new JsonObject().put("id", "missing-marker-crawl").put("state", "running"));
        Files.writeString(this.config.resolve("profiles.json"), "{}"); // Recovery uses the reserved snapshot.
        this.service.store.change(null, root -> root.put("enabled", false)); this.heartbeat.disable();
        this.service.close(); open();
        this.service.advance();
        final JsonObject root = this.service.store.read();
        assertEquals(3, acknowledgements[0]);
        assertTrue(root.isNull("active_run"));
        assertEquals(1, root.getJSONArray("history").length());
        final JsonObject last = JobStore.job(root, jobId).getJSONObject("runtime").getJSONObject("last_run");
        assertEquals(runId, last.getString("id"));
        assertEquals(3, last.getInt("attempt_count"));
        assertEquals(5, last.getJSONObject("report").getInt("processed"));
        assertTrue(last.has("finished_at"));
        assertEquals(last.toString(), root.getJSONArray("history").getJSONObject(0).toString());
        final JsonObject persisted = new JsonObject(Files.readString(this.state.resolve("state.json")));
        assertTrue(persisted.getBoolean("paused"));
        assertEquals("KEEP", persisted.getJSONObject("domains").getJSONObject("example.com").getJSONObject("profiles")
                .getJSONObject("other").getString("classification"));
        for (final Object value : this.backend.crawls) {
            final JsonObject crawl = (JsonObject) value;
            if (crawl.getString("id").startsWith("crawl-")) assertTrue(Files.readString(this.state.resolve("state.json")).contains(crawl.getString("id")));
        }
        assertEquals(3, this.backend.starts);
        this.service.advance();
        assertEquals(3, this.backend.starts);
        assertEquals(1, this.service.store.read().getJSONArray("history").length());
    }
    @Test public void unexpectedReconcileFailureLogsSafeCauseAndStackWithoutLeakingToApi() throws Exception {
        open(); create("Job"); enable(); this.service.advance();
        this.backend.crawlFailure = new IllegalStateException("https://secret-user:secret-password@private.invalid/",
                new IOException("secret-token"));
        final Logger logger = Logger.getLogger("ScoutroDiscovery");
        final boolean parents = logger.getUseParentHandlers(); final Level level = logger.getLevel();
        final AtomicReference<LogRecord> diagnostic = new AtomicReference<>();
        final CountDownLatch logged = new CountDownLatch(1);
        final Handler handler = new Handler() {
            @Override public void publish(final LogRecord record) { diagnostic.set(record); logged.countDown(); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.setUseParentHandlers(false); logger.setLevel(Level.ALL); logger.addHandler(handler);
        try {
            assertTrue(this.service.tick().getBoolean("accepted")); assertTrue(logged.await(5, TimeUnit.SECONDS));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!"needs_reconcile".equals(active().getString("phase")) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals("needs_reconcile", active().getString("phase"));
            assertEquals("coordinator_error", active().getString("error"));
            final JsonObject status = this.service.status();
            assertEquals("coordinator_error", status.getString("waiting_reason"));
            assertFalse(status.toString().contains("secret-")); assertFalse(status.toString().contains("IllegalStateException"));
            assertFalse(status.toString().contains("DiscoveryService.reconcile"));
            final LogRecord record = diagnostic.get(); assertEquals(Level.SEVERE, record.getLevel());
            assertNotNull(record.getThrown());
            final String rendered = new SimpleFormatter().format(record);
            assertTrue(rendered.contains("java.lang.IllegalStateException")); assertTrue(rendered.contains("java.io.IOException"));
            assertTrue(rendered.contains("DiscoveryService.reconcile")); assertTrue(rendered.contains("FakeBackend.crawls"));
            assertFalse(rendered.contains("secret-")); assertFalse(rendered.contains("private.invalid"));
            assertEquals(1, this.backend.starts);
            assertTrue(this.service.store.read().getJSONArray("history").isEmpty());
        } finally { logger.removeHandler(handler); logger.setUseParentHandlers(parents); logger.setLevel(level); }
    }
    @Test public void unmarkedCrawlCannotResolveUnknownStart() throws Exception {
        open(); create("Job"); enable(); this.backend.loseReply = true; this.backend.retain = false; this.service.advance();
        this.backend.crawls.put(new JsonObject().put("id", "ordinary").put("state", "terminated").put("startMarker", JsonObject.NULL));
        this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase")); assertEquals("submitted_unknown", active().getString("error"));
        assertEquals("submitted_unknown", active().getJSONArray("attempts").getJSONObject(0).getString("state"));
        assertEquals(1, this.backend.starts); assertTrue(this.service.store.read().getJSONArray("history").isEmpty());
    }
    @Test public void acceptedMissingCrawlStaysNeedsReviewWithoutResubmitting() throws Exception {
        open(); create("Job"); enable(); this.service.advance(); this.backend.crawls = new JsonArray();
        this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase")); assertEquals("crawl_status_unknown", active().getString("error"));
        assertEquals("accepted", active().getJSONArray("attempts").getJSONObject(0).getString("state"));
        assertTrue(active().getJSONArray("attempts").getJSONObject(0).getBoolean("state_applied"));
        assertEquals(1, this.backend.starts); assertTrue(this.service.store.read().getJSONArray("history").isEmpty());
    }
    @Test public void unknownStartReconcilesByMarkerAndDoesNotDoubleStart() throws Exception {
        open(); create("Job"); enable(); this.backend.loseReply = true; this.service.advance();
        assertEquals("submitted_unknown", active().getJSONArray("attempts").getJSONObject(0).getString("state"));
        this.service.close(); open(); this.service.advance(); assertEquals(1, this.backend.starts);
        assertEquals("accepted", active().getJSONArray("attempts").getJSONObject(0).getString("state"));
    }
    @Test public void missingMarkerAndMissingCrawlRemainNeedsReview() throws Exception {
        open(); create("Job"); enable(); this.backend.loseReply = true; this.backend.retain = false; this.service.advance();
        this.service.close(); open(); this.service.advance(); this.service.advance();
        assertEquals("needs_review", active().getString("phase")); assertEquals(1, this.backend.starts);
    }
    @Test public void restartAfterReservationDoesNotReplayDiscovery() throws Exception {
        this.runner = (a,b,c,init,h,t) -> { throw new IOException("crash during discovery"); }; open(); create("Job"); enable();
        assertThrows(IOException.class, () -> this.service.advance()); this.service.close();
        this.runner = (a,b,c,init,h,t) -> { fail("Must not replay interrupted batch"); return null; }; open(); this.service.advance();
        assertTrue(this.service.store.read().isNull("active_run")); assertEquals(0, this.backend.starts);
    }
    @Test public void queueBackpressureAndActiveRunBlockOtherBatches() throws Exception {
        open(); create("A"); create("B"); enable(); this.backend.allowed = false; this.service.advance(); assertEquals(0, this.backend.starts);
        this.backend.allowed = true; this.service.advance(); this.service.advance(); assertEquals(1, this.backend.starts);
    }
    @Test public void tickReturnsBeforeWorkerAndNoOverlap() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), leave = new CountDownLatch(1);
        this.runner = (a,b,c,init,h,t) -> { entered.countDown(); assertTrue(leave.await(5, TimeUnit.SECONDS)); return new JsonObject(); };
        open(); create("Job"); enable();
        long start = System.nanoTime(); assertTrue(this.service.tick().getBoolean("accepted")); assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(1));
        assertTrue(entered.await(5, TimeUnit.SECONDS)); assertEquals("busy", this.service.tick().getString("reason")); leave.countDown();
    }
    @Test public void realPythonProtocolReadOnlyMalformedAndConfirmation() throws Exception {
        Path script = Path.of("tools/scoutro/scoutro-discovery").toAbsolutePath();
        JsonObject answer = DiscoveryProcess.run(script, this.state, this.config, new JsonObject().put("operation", "status").put("jobs", new JsonArray()),
                (a,p) -> { fail("Read-only status requested effect"); return null; }, 10);
        assertTrue(answer.getJSONObject("snapshot").getBoolean("available")); assertFalse(Files.exists(this.state));
        Files.createDirectories(this.state); Files.writeString(this.state.resolve("state.json"), "INVALID");
        answer = DiscoveryProcess.run(script, this.state, this.config, new JsonObject().put("operation", "run").put("job", JobSchema.defaults(input("Job"))), (a,p) -> new JsonObject(), 10);
        assertEquals("state_unreadable", answer.getString("error")); assertEquals("INVALID", Files.readString(this.state.resolve("state.json")));
        Files.writeString(this.state.resolve("state.json"), "{\"state_version\":2,\"domains\":{\"example.com\":{\"profiles\":{\"other\":{\"classification\":\"KEEP\"}}}}}");
        JsonObject attempt = new JsonObject().put("id", "abc").put("domain", "example.com").put("profile", "new_profile").put("submitted_at", this.now.get()).put("recrawl_days", 30).put("crawl_id", "crawl-1").put("state", "accepted");
        answer = DiscoveryProcess.run(script, this.state, this.config, new JsonObject().put("operation", "confirm").put("job", JobSchema.defaults(input("Job")))
                .put("collection", "custom-index").put("regions", new JsonObject()).put("confirmations", new JsonArray().put(attempt)), (action,p) -> { assertEquals("ack", action); assertEquals("abc", p.getString("attempt_id")); return new JsonObject(); }, 10);
        assertFalse(answer.toString(), answer.has("error")); assertTrue(Files.readString(this.state.resolve("state.json")).contains("KEEP"));
    }
    private Path bridgeFixture() throws Exception {
        final Path fixture = this.tmp.newFile("bridge.py").toPath();
        final String script = Path.of("tools/scoutro/scoutro-discovery").toAbsolutePath().toString();
        Files.writeString(fixture, "import importlib.machinery, importlib.util, sys\n"
                + "loader=importlib.machinery.SourceFileLoader('bridge_fixture', '" + script + "')\n"
                + "spec=importlib.util.spec_from_loader(loader.name, loader)\n"
                + "module=importlib.util.module_from_spec(spec);sys.modules[loader.name]=module;loader.exec_module(module)\n"
                + "module.precheck_candidate=lambda *args: None\nmodule.main()\n");
        return fixture;
    }
    private JsonObject bridgeInit() {
        JsonObject job = JobSchema.defaults(input("Bridge"));
        job.put("sources", new JsonObject().put("freeworld", new JsonObject().put("mode", "selected").put("regions", new JsonArray().put("Teststadt"))));
        job.getJSONObject("batch").put("max_domains", 2).put("seed_delay_seconds", 0);
        return new JsonObject().put("operation", "run").put("job", job).put("collection", "custom-index")
                .put("replenish_due", true).put("replenish_sources", new JsonArray().put("freeworld"))
                .put("regions", new JsonObject().put("freeworld", new JsonArray().put("Teststadt")));
    }
    private JsonObject bridgeSearch() {
        JsonArray results = new JsonArray();
        for (int i=0;i<120;i++) results.put(new JsonObject().put("url", "https://firm-" + i + ".de/"));
        return new JsonObject().put("results", results);
    }
    @Test public void realBridgeReplenishesAllAndDispatchesOnlyBatchLimit() throws Exception {
        final int[] starts={0},acks={0};
        JsonObject answer = DiscoveryProcess.run(bridgeFixture(), this.state, this.config, bridgeInit(), (action,params) -> {
            if ("search".equals(action)) return bridgeSearch();
            if ("admission".equals(action)) return new JsonObject().put("allowed", true);
            if ("crawl".equals(action)) { starts[0]++; return new JsonObject().put("id","crawl-"+starts[0]).put("attempt_id","attempt-"+starts[0]); }
            if ("ack".equals(action)) { acks[0]++; assertTrue(Files.readString(this.state.resolve("state.json")).contains("attempt-"+acks[0])); }
            return new JsonObject();
        }, 10);
        assertFalse(answer.toString(),answer.has("error")); assertEquals(2,starts[0]);assertEquals(2,acks[0]);
        assertEquals(120,new JsonObject(Files.readString(this.state.resolve("state.json"))).getJSONObject("domains").length());
    }
    @Test public void realBridgeUncertainReplyPersistsHoldAndStops() throws Exception {
        final int[] starts={0};
        JsonObject answer = DiscoveryProcess.run(bridgeFixture(), this.state, this.config, bridgeInit(), (action,params) -> {
            if ("search".equals(action)) return bridgeSearch();
            if ("admission".equals(action)) return new JsonObject().put("allowed", true);
            if ("crawl".equals(action)) { starts[0]++; throw new ApiException(503,"submitted_unknown","lost reply",new JsonObject().put("attempt_id","held")); }
            return new JsonObject();
        }, 10);
        assertEquals("submitted_unknown",answer.getString("error"));assertEquals(1,starts[0]);
        assertTrue(Files.readString(this.state.resolve("state.json")).contains("submitted_unknown"));
    }
    private static class FakeBackend implements DiscoveryService.Backend {
        int starts; boolean allowed = true, loseReply, retain = true; Runnable before; RuntimeException crawlFailure;
        JsonArray crawls = new JsonArray();
        public JsonObject capacity() { return new JsonObject().put("allowed", allowed).put("reason", "capacity"); }
        public JsonArray crawls() {
            if (crawlFailure != null) { crawlFailure.fillInStackTrace(); throw crawlFailure; }
            return crawls;
        }
        public JsonObject search(JsonObject p) { throw new AssertionError("Unexpected source call"); }
        public JsonObject start(JsonObject p, String marker) throws ApiException {
            if (before != null) before.run(); starts++;
            JsonObject crawl = new JsonObject().put("id", "crawl-" + starts).put("state", "running").put("startMarker", marker);
            if (retain) crawls.put(crawl);
            if (loseReply) throw new ApiException(503, "upstream_unavailable", "lost reply");
            return new JsonObject(crawl.toString());
        }
    }
}
