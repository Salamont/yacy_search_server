/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Offline; new disposable DATA only. */
import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.discovery.JobSchema;
import net.yacy.scoutro.discovery.JobStore;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.report.CrawlOutcome;
import net.yacy.scoutro.report.CrawlSnapshot;
import net.yacy.scoutro.report.DomainTable;
import net.yacy.scoutro.report.IndexFacets;
import net.yacy.scoutro.report.PrecheckResult;
import net.yacy.scoutro.report.ReportService;
import net.yacy.scoutro.report.RollupStore;
import net.yacy.search.schema.CollectionConfiguration;

import org.apache.solr.common.SolrInputDocument;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * Crawl report fixture: index documents, scoutro_domains rows, rollups and one
 * Discovery job. Rows in "visible" belong to the known job, the "secret" row to a
 * job that is no longer in Discovery. Recent rollups are written by the report's own
 * daily step, so the peer finds nothing left to write.
 */
public class ReportFixture {
    static final String JOB = "5b0f4c1e-7a2d-4c6b-9f1e-2d3c4b5a6f70", ORPHAN = "8e1d2c3b-4a5f-4e6d-8c7b-9a0f1e2d3c4b";
    static final long DAY = 86_400_000L;

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected disposable root");
        Path root = Path.of(args[0]), core = root.resolve("DATA/INDEX/webportal/SEGMENTS/solr_9_0");
        if (!Files.isRegularFile(root.resolve(".scoutro-report-disposable")) || Files.exists(core) || Files.exists(root.resolve("DATA/WORK")))
            throw new IllegalArgumentException("Refusing existing or unmarked DATA");
        long now = System.currentTimeMillis();
        Path config = root.resolve("DATA/SETTINGS/solr.collection.schema");
        Files.copy(Path.of("defaults/solr.collection.schema"), config);
        new CollectionConfiguration(config.toFile(), true);
        EmbeddedInstance instance = new EmbeddedInstance(new File("defaults/solr"), core.toFile(), "collection1", new String[] {"collection1", "webgraph"});
        try {
            add(instance, "a.example", "", "visible", now - DAY, 200, null, "text/html", 0, 11L, 21L);
            add(instance, "a.example", "a", "visible", now - DAY, 200, null, "text/html", 1, 12L, 22L);
            add(instance, "a.example", "a-copy", "visible", now - DAY, 200, null, "text/html", 1, 12L, 22L);
            add(instance, "a.example", "doc.pdf", "visible", now - DAY, 200, null, "application/pdf", 2, 14L, 24L);
            add(instance, "a.example", "markup", "visible", now - DAY, 200, null, "text/html<img src=x onerror=alert(1)>", 2, 15L, 25L);
            add(instance, "a.example", "missing", "visible", now - DAY, 404, "fail", "text/html", 2, null, null);
            add(instance, "b.example", "", "visible", now - 40 * DAY, 200, null, "text/html", 0, 16L, 26L);
            add(instance, "e.example", "", "secret", now - DAY, 200, null, "text/html", 0, 17L, 27L);
            instance.getDefaultServer().commit();
        } finally {
            instance.close();
        }

        JsonObject definition = JobSchema.defaults(new JsonObject().put("name", "Fixture <img src=x onerror=alert(1)> job").put("profile", "fixture")
                .put("candidate_scope", "profile_backlog").put("discovery", new JsonObject().put("replenish", false))
                .put("processing", new JsonObject().put("recrawl", new JsonObject().put("enabled", true).put("days", 30)))).put("id", JOB);
        JobSchema.validate(definition, JobSchema.Limits.hard());
        new JobStore(root.resolve("DATA/SETTINGS/scoutro-discovery-jobs.json")).change(null, store -> store.getJSONArray("jobs").put(new JsonObject()
                .put("definition", definition).put("runtime", new JsonObject().put("next_due", now + 365 * DAY).put("last_served", 0)
                        .put("cursors", new JsonObject()).put("source_last_replenish", new JsonObject()))));

        Tables tables = new Tables(root.resolve("DATA/WORK").toFile(), DomainTable.KEY_LENGTH);
        try {
            DomainTable table = new DomainTable(tables);
            crawl(table, "a.example", "visible", JOB, "a1", now - 10 * DAY, "indexed", "complete", Map.of(CrawlOutcome.PAGES_OK, 3L));
            crawl(table, "a.example", "visible", JOB, "a2", now - DAY, "partial", "complete", Map.of(CrawlOutcome.PAGES_TOTAL, 6L,
                    CrawlOutcome.PAGES_OK, 4L, CrawlOutcome.PAGES_CLIENT_ERROR, 1L, CrawlOutcome.PAGES_FAILED, 1L, "excl_noindex", 1L,
                    CrawlOutcome.DEPTH, 2L, CrawlOutcome.MAX_PAGES, 15L));
            crawl(table, "b.example", "visible", JOB, "b1", now - 40 * DAY, "indexed", "complete", Map.of(CrawlOutcome.PAGES_OK, 1L));
            table.precheck("c.example", "visible", new PrecheckResult(now - DAY, "robots", "disallow_all", JOB, "c.example"), now);
            crawl(table, "d.example", "visible", JOB, "d1", now - 2 * DAY, "not_indexed", "partial", Map.of(CrawlOutcome.PAGES_TOTAL, 0L));
            for (int i = 0; i < 30; i++)
                crawl(table, String.format("h%02d.example", i), "visible", JOB, "h" + i, now - (3 + i % 4) * DAY, "indexed", "complete", Map.of(CrawlOutcome.PAGES_OK, 1L));
            crawl(table, "e.example", "secret", ORPHAN, "e1", now - DAY, "indexed", "complete", Map.of(CrawlOutcome.PAGES_OK, 1L));

            RollupStore rollups = new RollupStore(RollupStore.root(root));
            ZoneId zone = ZoneId.systemDefault();
            LocalDate today = LocalDate.now(zone);
            rollup(rollups, today.minusDays(60), 5, Map.of("indexed", 5L), null);
            rollup(rollups, today.minusDays(35), 3, Map.of("indexed", 2L, "partial", 1L),
                    new JsonArray().put(new JsonObject().put("type", "version_changed").put("from", "1.0").put("to", "1.1")));
            rollup(rollups, today.minusDays(20), 2, Map.of("indexed", 2L), new JsonArray().put(new JsonObject().put("type", "job_changed")));
            IndexFacets offline = new IndexFacets(p -> { throw new IOException("offline fixture"); }, f -> true);
            new ReportService(table, offline, rollups, () -> List.of(new ReportService.Job(JOB, definition.getString("name"),
                    ReportService.fingerprint(definition), 30)), System::currentTimeMillis, zone, 0, 30, null, 0).daily();
        } finally {
            tables.close();
        }
        net.yacy.cora.protocol.Domains.close();
        net.yacy.cora.util.ConcurrentLog.shutdown();
        System.out.println("PASS: seeded 8 index documents, 35 scoutro_domains rows, rollups and one Discovery job");
    }

    static void crawl(DomainTable table, String host, String collection, String job, String id, long ended, String outcome, String coverage,
            Map<String, Long> counters) throws IOException {
        CrawlSnapshot.Builder b = CrawlSnapshot.builder(id, ended - 600_000L).endedAt(ended).job(job).discoveryDomain(host)
                .label(CrawlOutcome.OUTCOME, outcome).label(CrawlOutcome.COVERAGE, coverage);
        for (Map.Entry<String, Long> c : new TreeMap<>(counters).entrySet()) b.counter(c.getKey(), c.getValue());
        DomainTable.Status status = table.complete(host, collection, b.build(), ended);
        if (status != DomainTable.Status.CREATED && status != DomainTable.Status.SHIFTED) throw new IOException("row not written: " + host + " " + status);
    }

    static void rollup(RollupStore rollups, LocalDate day, long crawls, Map<String, Long> outcomes, JsonArray markers) throws IOException {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Long> e : new TreeMap<>(outcomes).entrySet()) o.put(e.getKey(), e.getValue());
        JsonObject line = new JsonObject().put("collections", new JsonArray().put("visible")).put("crawls", crawls).put("outcomes", o)
                .put("coverage_partial", 0).put("pages", new JsonObject().put("pages_ok", crawls * 2)).put("exclusions", new JsonObject())
                .put("prechecks", new JsonObject());
        if (markers != null) line.put("markers", markers);
        rollups.append(JOB, day, line);
    }

    static void add(EmbeddedInstance instance, String host, String path, String collection, long loaded, int status, String failType,
            String mime, int depth, Long exact, Long fuzzy) throws Exception {
        String url = "https://" + host + "/" + path;
        DigestURL d = new DigestURL(url);
        SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", host);
        s.setField("host_id_s", d.hosthash());
        s.setField("collection_sxt", List.of(collection));
        s.setField("httpstatus_i", status);
        s.setField("load_date_dt", new Date(loaded));
        s.setField("content_type", List.of(mime));
        s.setField("crawldepth_i", depth);
        s.setField("url_protocol_s", "https");
        s.setField("title", List.of("Title " + path));
        s.setField("text_t", "Scoutro crawl report fixture");
        if (failType != null) s.setField("failtype_s", failType);
        if (exact != null) s.setField("exact_signature_l", exact);
        if (fuzzy != null) s.setField("fuzzy_signature_l", fuzzy);
        instance.getDefaultServer().add(s);
    }
}
