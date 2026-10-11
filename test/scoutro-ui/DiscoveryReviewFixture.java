/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later.
 * Offline fixture for a NEW disposable peer only: an open Discovery batch in needs_review with one unconfirmed crawl
 * start (submitted_unknown, no crawl profile carries its marker), as found in production. It uses the real Discovery
 * classes with a backend that loses the reply and a scripted worker; nothing is crawled or sent anywhere.
 */
import java.nio.file.Files;
import java.nio.file.Path;

import net.yacy.data.WorkTables;
import net.yacy.scoutro.api.ApiException;
import net.yacy.scoutro.discovery.DiscoveryHeartbeat;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.scoutro.discovery.JobSchema;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

public class DiscoveryReviewFixture {
    public static void main(final String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected new disposable peer root");
        final Path root = Path.of(args[0]).toAbsolutePath().normalize();
        final Path data = root.resolve("DATA");
        if (!Files.isRegularFile(root.resolve(".scoutro-dashboard-disposable")) || Files.exists(data.resolve("SETTINGS/scoutro-discovery-jobs.json"))) {
            throw new IllegalArgumentException("Refusing to seed an existing or unmarked peer");
        }
        final Path work = Files.createTempDirectory("discovery-review-fixture-");
        final WorkTables tables = new WorkTables(work.toFile());
        final DiscoveryHeartbeat heartbeat = new DiscoveryHeartbeat(tables);
        heartbeat.enable();
        final DiscoveryService.Backend backend = new DiscoveryService.Backend() {
            public JsonObject capacity() { return new JsonObject().put("allowed", true).put("reason", ""); }
            public JsonArray crawls() { return new JsonArray(); }                     // no profile with the marker
            public JsonObject search(final JsonObject parameters) throws ApiException { throw new ApiException(503, "offline", "offline fixture"); }
            public JsonObject start(final JsonObject parameters, final String marker) throws ApiException {
                throw new ApiException(503, "upstream_unavailable", "reply lost at the maintenance stop");
            }
        };
        final DiscoveryService.Runner runner = (script, state, snapshot, init, handler, timeout) -> {
            if (!"run".equals(init.getString("operation"))) return new JsonObject().put("snapshot", new JsonObject().put("available", true));
            try {
                handler.request("crawl", new JsonObject().put("domain", "codecamp-n.com").put("url", "https://codecamp-n.com/"));
                return new JsonObject().put("report", new JsonObject().put("processed", 1));
            } catch (final ApiException e) {
                return new JsonObject().put("error", e.code()).put("report", new JsonObject().put("processed", 0));
            }
        };
        final DiscoveryService service = new DiscoveryService(data, Path.of(".").toAbsolutePath(), data.resolve("SCOUTRO/config"),
                DiscoveryService.resolveRoot(null, "", data.resolve("SCOUTRO/discovery")), heartbeat, backend,
                System::currentTimeMillis, JobSchema.Limits.hard(), runner, 60);
        try {
            service.create(new JsonObject().put("name", "Prod StackFinder Sued-West").put("profile", "new_profile").put("enabled", true)
                    .put("sources", new JsonObject().put("osm", new JsonObject().put("regions", new JsonArray().put("test-region")))), null);
            service.store.change(null, document -> document.put("enabled", true));
            service.advance(); service.advance(); service.advance();
            final JsonObject run = service.store.read().getJSONObject("active_run");
            if (!"needs_review".equals(run.getString("phase"))) throw new IllegalStateException("fixture batch not in review");
            // the peer itself never dispatches: automation stays disabled, only reconciliation may run
            service.store.change(null, document -> document.put("enabled", false));
            System.out.println(new JsonObject().put("run_id", run.getString("id"))
                    .put("attempt_id", run.getJSONArray("attempts").getJSONObject(0).getString("id")));
        } finally {
            service.close();
            tables.close();
            try (var files = Files.walk(work)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        }
    }
}
