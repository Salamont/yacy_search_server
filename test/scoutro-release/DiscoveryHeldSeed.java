// DiscoveryHeldSeed.java
// Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
//
// Seeds a disposable peer root with the Discovery state of the production peer before 0.11.5, with the real Discovery
// classes and no network: thirteen jobs, automation disabled, no open batch, and four candidates held after a deferral by
// an administrator (an unconfirmed crawl start whose reply was lost; the hold is answered by a scripted worker). The
// upgrade test of 0.11.5 (test/scoutro-release/upgrade-0.11.5-test.py) starts the 0.11.4 and 0.11.5 images on this root
// and checks that the job store, the jobs and the four holds stay as they are. Usage: <root> <domain>... (4 domains).

import java.nio.file.Files;
import java.nio.file.Path;

import net.yacy.data.WorkTables;
import net.yacy.scoutro.api.ApiException;
import net.yacy.scoutro.discovery.DiscoveryHeartbeat;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.scoutro.discovery.JobSchema;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

public class DiscoveryHeldSeed {
    public static void main(final String[] args) throws Exception {
        if (args.length < 2) throw new IllegalArgumentException("Expected a new disposable peer root and the domains to hold");
        final Path root = Path.of(args[0]).toAbsolutePath().normalize();
        final Path data = root.resolve("DATA");
        if (!Files.isRegularFile(root.resolve(".scoutro-dashboard-disposable")) || Files.exists(data.resolve("SETTINGS/scoutro-discovery-jobs.json"))) {
            throw new IllegalArgumentException("Refusing to seed an existing or unmarked peer");
        }
        final String[] held = java.util.Arrays.copyOfRange(args, 1, args.length);
        final Path work = Files.createTempDirectory("discovery-held-seed-");
        final WorkTables tables = new WorkTables(work.toFile());
        final DiscoveryHeartbeat heartbeat = new DiscoveryHeartbeat(tables);
        heartbeat.enable();
        final String[] current = {null};
        final DiscoveryService.Backend backend = new DiscoveryService.Backend() {
            public JsonObject capacity() { return new JsonObject().put("allowed", true).put("reason", ""); }
            public JsonArray crawls() { return new JsonArray(); }                     // no profile with the marker: the start is unconfirmed
            public JsonObject search(final JsonObject parameters) throws ApiException { throw new ApiException(503, "offline", "offline seed"); }
            public JsonObject start(final JsonObject parameters, final String marker) throws ApiException {
                throw new ApiException(503, "upstream_unavailable", "reply lost at the maintenance stop");
            }
        };
        final DiscoveryService.Runner runner = (script, state, snapshot, init, handler, timeout) -> {
            final String operation = init.getString("operation");
            if ("hold".equals(operation)) return new JsonObject().put("report", new JsonObject().put("held", init.getJSONArray("holds").length()));
            if (!"run".equals(operation)) return new JsonObject().put("snapshot", new JsonObject().put("available", true));
            try {
                handler.request("crawl", new JsonObject().put("domain", current[0]).put("url", "https://" + current[0] + "/"));
                return new JsonObject().put("report", new JsonObject().put("processed", 1));
            } catch (final ApiException e) {
                return new JsonObject().put("error", e.code()).put("report", new JsonObject().put("processed", 0));
            }
        };
        final DiscoveryService service = new DiscoveryService(data, Path.of(".").toAbsolutePath(), data.resolve("SCOUTRO/config"),
                DiscoveryService.resolveRoot(null, "", data.resolve("SCOUTRO/discovery")), heartbeat, backend,
                System::currentTimeMillis, JobSchema.Limits.hard(), runner, 60);
        final JsonArray holds = new JsonArray();
        try {
            final String[] names = {"Prod Bauteamcheck Mitte-West", "Prod Bauteamcheck Nord", "Prod Bauteamcheck Sued", "Prod StackFinder Sued-West",
                    "Prod StackFinder Nord", "Prod StackFinder Ost", "Prod CheckTheCoach Mitte", "Prod CheckTheCoach Nord", "Prod EdelSenior West",
                    "Prod EdelSenior Ost", "Prod Regional Hamburg", "Prod Regional Berlin", "Prod Regional Muenchen"};
            for (final String name : names) {
                service.create(new JsonObject().put("name", name).put("profile", "new_profile").put("enabled", true)
                        .put("sources", new JsonObject().put("osm", new JsonObject().put("regions", new JsonArray().put("test-region")))), null);
            }
            service.store.change(null, document -> document.put("enabled", true));
            for (final String domain : held) {
                current[0] = domain;
                // one batch whose start reply is lost: three advances bring it into review
                service.advance(); service.advance(); service.advance();
                final JsonObject run = service.store.read().getJSONObject("active_run");
                if (!"needs_review".equals(run.getString("phase"))) throw new IllegalStateException("seed batch not in review for " + domain);
                final String attempt = run.getJSONArray("attempts").getJSONObject(0).getString("id");
                final long revision = service.store.read().getLong("revision");
                service.deferAttempt(attempt, run.getString("id"), revision, "digest:admin");
                for (int i = 0; i < 100 && !service.store.read().isNull("active_run"); i++) Thread.sleep(50);
                if (!service.store.read().isNull("active_run")) throw new IllegalStateException("the deferred batch did not complete for " + domain);
                holds.put(new JsonObject().put("domain", domain).put("attempt_id", attempt).put("run_id", run.getString("id")));
            }
            // the production peer: automation disabled, no open batch
            service.store.change(null, document -> document.put("enabled", false));
            final JsonObject store = service.store.read();
            int heldCount = 0;
            for (final Object row : store.getJSONArray("jobs")) {
                final JsonObject runtime = ((JsonObject) row).getJSONObject("runtime");
                heldCount += runtime.optJSONArray("held_attempts") == null ? 0 : runtime.getJSONArray("held_attempts").length();
            }
            System.out.println(new JsonObject().put("jobs", store.getJSONArray("jobs").length()).put("held", heldCount)
                    .put("active_run", store.isNull("active_run") ? JsonObject.NULL : "open").put("enabled", store.getBoolean("enabled"))
                    .put("holds", holds));
        } finally {
            service.close();
            tables.close();
            try (var files = Files.walk(work)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}
