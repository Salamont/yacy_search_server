/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary tables and directories, fake index answers. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.solr.common.util.NamedList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.kelondro.blob.Tables;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.report.CrawlOutcome;
import net.yacy.scoutro.report.CrawlSnapshot;
import net.yacy.scoutro.report.DomainTable;
import net.yacy.scoutro.report.IndexFacets;
import net.yacy.scoutro.report.PrecheckResult;
import net.yacy.scoutro.report.ReportService;
import net.yacy.scoutro.report.RollupStore;
import net.yacy.search.schema.CollectionSchema;

public class ReportApiTest {
    private static final String JOB = "3276af9c-b8b5-4b8c-bc2b-17b8d1905eb7";
    private static final String MIXED = "c334ac9e-5f0e-41f4-b738-ac432b115a4d";
    private static final long DAY = 86_400_000L, NOON = 1_791_115_200_000L;

    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private Tables tables;
    private DomainTable table;
    private ReportApi api;
    private final AtomicLong clock = new AtomicLong(NOON);

    @Before public void open() throws Exception {
        this.tables = new Tables(this.tmp.newFolder("WORK"), DomainTable.KEY_LENGTH);
        this.table = new DomainTable(this.tables);
        final IndexFacets facets = new IndexFacets(p -> answer(), Set.of(CollectionSchema.collection_sxt, CollectionSchema.host_s)::contains);
        final ReportService reports = new ReportService(this.table, facets, new RollupStore(RollupStore.root(this.tmp.newFolder("app").toPath())),
                () -> List.of(new ReportService.Job(JOB, "Visible job", "fp", 30), new ReportService.Job(MIXED, "Mixed job", "fp", 30)),
                this.clock::get, ZoneOffset.UTC, 0, 30, null, 0, host -> Map.of("ref.example", 3));
        this.api = new ReportApi(reports);
        crawl("a.example", "visible", JOB, NOON - DAY, "indexed");
        crawl("b.example", "visible", JOB, NOON - 40 * DAY, "partial");
        precheck("c.example", "visible", JOB, "robots");
        crawl("d.example", "visible", MIXED, NOON - DAY, "not_indexed");
        crawl("e.example", "secret", MIXED, NOON - DAY, "indexed");
    }

    @After public void close() {
        this.tables.close();
    }

    private static NamedList<Object> answer() {
        final NamedList<Object> facets = new NamedList<>();
        facets.add("count", 3L);
        facets.add("hosts", 2L);
        final NamedList<Object> result = new NamedList<>();
        result.add("responseHeader", new NamedList<>());
        result.add("facets", facets);
        return result;
    }

    private void crawl(final String host, final String collection, final String job, final long ended, final String outcome) throws IOException {
        this.table.complete(host, collection, CrawlSnapshot.builder("c" + host.charAt(0), ended - 1000).endedAt(ended).job(job)
                .counter(CrawlOutcome.PAGES_OK, 3).label(CrawlOutcome.OUTCOME, outcome).label(CrawlOutcome.COVERAGE, "complete").build(), ended);
    }

    private void precheck(final String host, final String collection, final String job, final String result) throws IOException {
        this.table.precheck(host, collection, new PrecheckResult(NOON - DAY, result, null, job, null), NOON);
    }

    private static List<String> path(final String path) {
        return List.of(path.split("/"));
    }

    private static Map<String, String> q(final String... kv) {
        final Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private JSONObject admin(final String path, final String... kv) throws ApiException {
        return this.api.route(path(path), q(kv), null);
    }

    private JSONObject agent(final ReportApi.Scope scope, final String path, final String... kv) throws ApiException {
        return this.api.route(path(path), q(kv), scope);
    }

    private void error(final int status, final String code, final ReportApi.Scope scope, final String path, final String... kv) {
        try {
            this.api.route(path(path), q(kv), scope);
            fail(path + " " + List.of(kv));
        } catch (final ApiException e) {
            assertEquals(path, status, e.status());
            assertEquals(path, code, e.code());
        }
    }

    private static List<String> ids(final JSONArray jobs) {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < jobs.length(); i++) out.add(jobs.optJSONObject(i).optString("id"));
        return out;
    }

    @Test public void administratorRoutes() throws Exception {
        assertEquals(List.of(JOB, MIXED).size(), admin("jobs").getJSONArray("jobs").length());
        final JSONObject job = admin("jobs/" + JOB, "from", "2026-09-01", "to", "2026-10-04");
        assertEquals("Visible job", job.getString("name"));
        assertEquals(3, job.getJSONObject("table").getLong("hosts"));
        final JSONObject collection = admin("collections/visible");
        assertEquals(4, collection.getJSONObject("table").getLong("hosts"));
        assertEquals("live", collection.getString("index_source"));
        final JSONObject stale = admin("collections/visible/hosts", "filter", "stale");
        assertEquals(1, stale.getLong("total"));
        assertEquals("b.example", stale.getJSONArray("items").getJSONObject(0).getString("host"));
        final JSONObject host = admin("hosts/A.example", "collection", "visible");
        assertEquals("found", host.getString("status"));
        assertEquals("absent", admin("hosts/unknown.example", "collection", "visible").getString("status"));
    }

    private List<String> hosts(final String filter, final String... paging) throws Exception {
        final List<String> kv = new ArrayList<>(List.of("filter", filter));
        kv.addAll(List.of(paging));
        final JSONArray items = admin("collections/visible/hosts", kv.toArray(new String[0])).getJSONArray("items");
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) out.add(items.getJSONObject(i).getString("host"));
        return out;
    }

    @Test public void hostFiltersAndPaging() throws Exception {
        assertEquals(List.of("a.example", "b.example", "c.example", "d.example"), hosts("all"));
        assertEquals(List.of("c.example"), hosts("precheck"));
        assertEquals(List.of("b.example"), hosts("partial"));
        assertEquals(List.of("d.example"), hosts("not_indexed"));
        assertEquals(List.of(), hosts("coverage_partial"));
        assertEquals(List.of("b.example", "c.example"), hosts("all", "offset", "1", "limit", "2"));
        final JSONObject page = admin("collections/visible/hosts", "offset", "3", "limit", "2");
        assertEquals(4, page.getLong("total"));
        assertEquals(1, page.getJSONArray("items").length());
        final JSONObject c = page.getJSONArray("items").getJSONObject(0);
        assertFalse(c.has("crawl_time"));
        final JSONObject pre = admin("collections/visible/hosts", "filter", "precheck").getJSONArray("items").getJSONObject(0);
        assertEquals("robots", pre.getString("precheck"));
        assertTrue(pre.isNull("last_crawl"));
        assertFalse(pre.getBoolean("stale"));
    }

    @Test public void invalidRequestsAreRejectedBeforeReading() {
        error(400, "invalid_request", null, "jobs", "collection", "visible");
        error(400, "invalid_request", null, "jobs/not-a-job");
        error(400, "invalid_request", null, "jobs/" + JOB.toUpperCase());
        error(400, "invalid_request", null, "jobs/" + JOB, "from", "2026-1-1");
        error(400, "invalid_request", null, "jobs/" + JOB, "from", "2026-10-04", "to", "2026-09-01");
        error(400, "invalid_request", null, "jobs/" + JOB, "from", "2020-01-01", "to", "2026-01-01");
        error(400, "invalid_request", null, "collections/bad name");
        error(400, "invalid_request", null, "collections/visible", "q", "*:*");
        error(400, "invalid_request", null, "collections/visible/hosts", "filter", "random()");
        error(400, "invalid_request", null, "collections/visible/hosts", "limit", "101");
        error(400, "invalid_request", null, "collections/visible/hosts", "offset", "10001");
        error(400, "invalid_request", null, "hosts/a.example");
        error(400, "invalid_request", null, "hosts/127.0.0.1", "collection", "visible");
        error(400, "invalid_request", null, "hosts/a.example", "collection", "visible", "fq", "x");
        error(404, "not_found", null, "collections/visible/pages");
        error(404, "not_found", null, "status");
    }

    @Test public void agentsSeeOnlyTheirDataScope() throws Exception {
        final ReportApi.Scope visible = new ReportApi.Scope(false, Set.of("visible"));
        assertEquals(List.of(JOB), ids(agent(visible, "jobs").getJSONArray("jobs")));       // MIXED also covers "secret"
        assertEquals(3, agent(visible, "jobs/" + JOB).getJSONObject("table").getLong("hosts"));
        error(404, "not_found", visible, "jobs/" + MIXED);
        assertEquals(4, agent(visible, "collections/visible").getJSONObject("table").getLong("hosts"));
        error(403, "collection_not_in_scope", visible, "collections/secret");
        error(403, "collection_not_in_scope", visible, "collections/secret/hosts");
        error(403, "collection_not_in_scope", visible, "hosts/e.example", "collection", "secret");
        assertEquals("found", agent(visible, "hosts/a.example", "collection", "visible").getString("status"));
        final ReportApi.Scope all = new ReportApi.Scope(true, Set.of());
        assertEquals(2, agent(all, "jobs").getJSONArray("jobs").length());
        assertEquals(1, agent(all, "collections/secret").getJSONObject("table").getLong("hosts"));
        final ReportApi.Scope none = new ReportApi.Scope(false, Set.of("other"));
        assertEquals(0, agent(none, "jobs").getJSONArray("jobs").length());
    }

    @Test public void referringHostsNeedTheCompleteIndex() throws Exception {
        final JSONObject admin = admin("hosts/a.example", "collection", "visible");
        assertEquals("ref.example", admin.getJSONObject("referring_hosts").getJSONArray("items").getJSONObject(0).getString("host"));
        final JSONObject all = agent(new ReportApi.Scope(true, Set.of()), "hosts/a.example", "collection", "visible");
        assertEquals(1, all.getJSONObject("referring_hosts").getLong("hosts"));
        final JSONObject narrow = agent(new ReportApi.Scope(false, Set.of("visible")), "hosts/a.example", "collection", "visible");
        assertTrue(narrow.isNull("referring_hosts"));
        assertEquals("complete_index_required", narrow.getString("referring_hosts_scope"));
        assertFalse(admin.has("referring_hosts_scope"));
    }

    @Test public void jobsWithoutRecordedCollectionsAreOnlyVisibleWithTheCompleteIndex() throws Exception {
        final String empty = "0b9b2f5e-4b51-4d0c-9e84-0c3a1c7a8a01";
        final ReportService reports = new ReportService(this.table, new IndexFacets(p -> answer(), f -> true),
                new RollupStore(RollupStore.root(this.tmp.newFolder("app2").toPath())),
                () -> List.of(new ReportService.Job(empty, "No crawl yet", "fp", 30)), this.clock::get, ZoneOffset.UTC, 0, 30, null, 0);
        final ReportApi fresh = new ReportApi(reports);
        try { fresh.route(path("jobs/" + empty), q(), new ReportApi.Scope(false, Set.of("visible"))); fail(); }
        catch (final ApiException e) { assertEquals(404, e.status()); }
        assertTrue(fresh.route(path("jobs/" + empty), q(), new ReportApi.Scope(true, Set.of())).getBoolean("known"));
    }

    @Test public void unreadableTableIsAServiceError() throws Exception {
        final java.io.File work = this.tmp.newFolder("WORK2");
        final java.io.File heap = new java.io.File(work, DomainTable.TABLE + ".bheap");
        assertTrue(heap.mkdir() && new java.io.File(heap, "keep").createNewFile());
        final Tables broken = new Tables(work, DomainTable.KEY_LENGTH);
        final ReportApi failing = new ReportApi(new ReportService(new DomainTable(broken), new IndexFacets(p -> answer(), f -> true),
                new RollupStore(RollupStore.root(this.tmp.newFolder("app3").toPath())), List::of, this.clock::get, ZoneOffset.UTC, 0, 30, null, 0));
        try { failing.route(path("collections/visible"), q(), null); fail(); }
        catch (final ApiException e) { assertEquals(503, e.status()); assertEquals("report_unavailable", e.code()); }
        broken.close();
    }

    @Test public void reportGrantIsExplicitAndOutsideEveryPreset() {
        final AgentActionRegistry.Action action = AgentActionRegistry.get("report.read");
        assertEquals("GET", action.method);
        assertTrue(action.scoped);
        assertFalse(action.presetable);
        for (final String preset : AgentActionRegistry.presetNames()) assertFalse(AgentActionRegistry.preset(preset).contains("report.read"));
        assertEquals("report.read", AgentApi.route("GET", List.of("reports", "collections", "visible", "hosts")).action);
        assertEquals(null, AgentApi.route("POST", List.of("reports", "jobs")).action);
    }
}
