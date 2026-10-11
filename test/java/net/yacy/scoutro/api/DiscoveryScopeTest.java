/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary DATA only, no process is started. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

import javax.servlet.http.HttpServletResponse;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.data.WorkTables;
import net.yacy.scoutro.discovery.DiscoveryHeartbeat;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.scoutro.discovery.JobSchema;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

/**
 * Discovery for an Operator account (docs/SCOUTRO_USERS_ACCESS.md): only the jobs whose profile writes into
 * one of its collections, no global switch, no export of the job store.
 */
public class DiscoveryScopeTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private WorkTables tables;
    private DiscoveryService service;
    private final HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {HttpServletResponse.class}, (proxy, method, args) -> null);
    private final Predicate<String> mine = Set.of("custom-index")::contains;

    @Before public void setUp() throws Exception {
        final Path data = this.tmp.newFolder("DATA").toPath();
        final Path config = this.tmp.newFolder("runtime").toPath();
        Files.writeString(config.resolve("profiles.json"),
                "{\"profiles\":{\"mine\":{\"collection\":\"custom-index\"},\"theirs\":{\"collection\":\"another-index\"}}}");
        Files.writeString(config.resolve("profiles.conf"), "mine = Beratung\ntheirs = Beratung\n");
        Files.writeString(config.resolve("osm_profiles.json"),
                "{\"extract\":\"nwr[website]\",\"profiles\":{\"mine\":{\"text_any\":[\"Beratung\"]},\"theirs\":{\"text_any\":[\"Beratung\"]}}}");
        Files.writeString(config.resolve("osm_regions.txt"), "test-region\n");
        Files.writeString(config.resolve("regions.txt"), "Teststadt\n");
        this.tables = new WorkTables(this.tmp.newFolder("WORK"));
        this.service = new DiscoveryService(data, Path.of(".").toAbsolutePath(), config, data.resolve("SCOUTRO/discovery"),
                new DiscoveryHeartbeat(this.tables), new DiscoveryService.Backend() {
                    @Override public JsonObject capacity() { return new JsonObject().put("allowed", true); }
                    @Override public JsonArray crawls() { return new JsonArray(); }
                    @Override public JsonObject search(final JsonObject p) { throw new AssertionError(); }
                    @Override public JsonObject start(final JsonObject p, final String marker) { throw new AssertionError(); }
                }, System::currentTimeMillis, JobSchema.Limits.hard(),
                (script, state, snapshot, init, handler, timeout) -> new JsonObject().put("snapshot", new JsonObject().put("available", true)
                        .put("profiles", new JsonObject().put("mine", new JsonObject().put("fresh", 1)).put("theirs", new JsonObject().put("fresh", 2)))), 30);
    }

    @After public void tearDown() {
        if (this.service != null) this.service.close();
        this.tables.close();
    }

    private static JsonObject job(final String name, final String profile) {
        return new JsonObject().put("name", name).put("profile", profile).put("enabled", true)
                .put("sources", new JsonObject().put("osm", new JsonObject().put("regions", new JsonArray().put("test-region"))));
    }

    private String create(final String name, final String profile) throws Exception {
        return this.service.create(job(name, profile), null).getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id");
    }

    private JsonObject call(final String method, final Predicate<String> allowed, final JsonObject body, final String... path) throws Exception {
        final String[] parts = new String[3 + path.length];
        parts[0] = ""; parts[1] = "v1"; parts[2] = "discovery";
        System.arraycopy(path, 0, parts, 3, path.length);
        return DiscoveryApi.route(this.service, method, parts, this.service.jobs(null).getLong("revision"), this.response,
                body == null ? new JsonObject() : body, allowed);
    }

    private void refused(final String code, final Throwing call) throws Exception {
        try {
            call.run();
            fail("expected " + code);
        } catch (final ApiException e) {
            assertEquals(code, e.code());
        }
    }

    @FunctionalInterface private interface Throwing { void run() throws Exception; }

    @Test public void operatorsSeeAndChangeOnlyJobsOfTheirCollections() throws Exception {
        final String own = create("Own", "mine");
        final String foreign = create("Foreign", "theirs");

        final JsonArray jobs = call("GET", this.mine, null, "jobs").getJSONArray("jobs");
        assertEquals(1, jobs.length());
        assertEquals(own, jobs.getJSONObject(0).getJSONObject("definition").getString("id"));
        assertEquals(2, call("GET", null, null, "jobs").getJSONArray("jobs").length()); // administrator unchanged

        final JsonArray profiles = call("GET", this.mine, null, "catalog").getJSONArray("profiles");
        assertEquals(1, profiles.length());
        assertEquals("mine", profiles.getJSONObject(0).getString("id"));

        // foreign jobs answer like missing ones
        refused("job_not_found", () -> call("GET", this.mine, null, "jobs", foreign));
        refused("job_not_found", () -> call("PATCH", this.mine, new JsonObject().put("name", "x"), "jobs", foreign));
        refused("job_not_found", () -> call("DELETE", this.mine, null, "jobs", foreign));
        refused("job_not_found", () -> call("POST", this.mine, new JsonObject().put("request_id", "r1"), "jobs", foreign, "run"));
        // no job into a foreign collection, also not by moving an own job
        refused("collection_not_allowed", () -> call("POST", this.mine, job("New", "theirs"), "jobs"));
        refused("collection_not_allowed", () -> call("PATCH", this.mine, new JsonObject().put("profile", "theirs"), "jobs", own));
        // the global switches and the export stay with the administrator
        for (final String global : new String[] {"enable", "disable", "pause", "resume"}) {
            refused("forbidden", () -> call("POST", this.mine, null, global));
        }
        refused("forbidden", () -> call("GET", this.mine, null, "export"));
        // so does the recovery decision for an unconfirmed crawl start
        refused("forbidden", () -> call("POST", this.mine, new JsonObject().put("run_id", "00000000-0000-4000-8000-000000000000"),
                "attempts", "0123456789abcdef0123456789abcdef", "defer"));

        // own jobs work
        assertEquals("Renamed", call("PATCH", this.mine, new JsonObject().put("name", "Renamed"), "jobs", own)
                .getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("name"));
        assertEquals(1, call("POST", this.mine, job("Second", "mine"), "jobs").getJSONArray("jobs").length());
    }

    @Test public void statusHidesForeignWorkAndOffersNoGlobalAction() throws Exception {
        create("Own", "mine");
        create("Foreign", "theirs");
        final JsonObject status = call("GET", this.mine, null, "status");
        assertEquals(1, status.getInt("job_count"));
        assertEquals(0, status.getJSONArray("allowed_actions").length());
        final JsonObject state = status.optJSONObject("state");
        if (state != null && state.optJSONObject("profiles") != null) {
            assertFalse(state.getJSONObject("profiles").has("theirs"));
        }
        assertTrue(call("GET", null, null, "status").getJSONArray("allowed_actions").length() > 0);
    }
}
