/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Temporary embedded index only. */
package net.yacy.scoutro.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.ModifiableSolrParams;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.cora.federate.solr.connector.EmbeddedSolrConnector;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;

/** Host and collection consolidation, representative pages, filters and the streaming export on YaCy's real schema. */
public class DomainCandidatesTest {
    @ClassRule public static TemporaryFolder tmp = new TemporaryFolder();
    private static EmbeddedInstance instance;
    private static EmbeddedSolrConnector solr;
    private static final long DAY = 86_400_000L, T0 = 1_790_000_000_000L;
    private static final int BULK = 450; // more than two export pages

    @BeforeClass public static void index() throws Exception {
        instance = new EmbeddedInstance(new java.io.File("defaults/solr"), tmp.newFolder("index"), "collection1", new String[] {"collection1"});
        solr = new EmbeddedSolrConnector(instance, "collection1");
        // four pages of one host in stackfinder-web; root over http and https, an error page
        add("https://www.agentur.example/", "stack", 0, 200, "Agentur Start", "Wir bauen Shops.", T0);
        add("http://www.agentur.example/", "stack", 0, 301, null, null, T0 - DAY);
        add("https://www.agentur.example/impressum", "stack", 1, 200, "Impressum", null, T0 + DAY);
        add("https://www.agentur.example/kontakt", "stack", 1, 404, null, null, T0 - 2 * DAY);
        // the same host in a second collection
        add("https://www.agentur.example/team", "coach", 2, 200, "Team", null, T0 - 5 * DAY);
        // a host whose shallowest page failed: the 200 page wins
        add("https://broken.example/", "stack", 0, 500, "Fehler", null, T0);
        add("https://broken.example/ok", "stack", 1, 200, "=HYPERLINK(\"x\")", "Text, mit \"Komma\"\nzweite Zeile", T0);
        // no title, no description, no collection
        add("https://plain.example/a", null, 3, 200, null, null, T0);
        for (int i = 0; i < BULK; i++) add(String.format("https://bulk%04d.example/", i), "bulk", 0, 200, "Bulk " + i, null, T0);
        // entity pages, parsed like a crawl would store them; the second collection only has the start page
        addPage("https://www.musterbau.example/", List.of("firmen", "portal"), 0,
                DomainEntityTest.html("Musterbau – Bauen in Köln", "", "<h1>Willkommen</h1><p>Wir bauen seit 1950.</p>"));
        addPage("https://www.musterbau.example/impressum/", List.of("firmen"), 1, DomainEntityTest.html("Impressum", "",
                "<p>Musterbau GmbH<br>Musterstraße 1<br>50667 Köln</p><p>Telefon: 0221 / 12 34 56<br>Fax: 0221 / 12 34 57</p>"
                + "<p>E-Mail: max.muster@musterbau.example</p><p>Registergericht: Amtsgericht Köln, HRB 12345</p>"));
        addPage("https://www.musterbau.example/Kontakt.html", List.of("firmen"), 1,
                DomainEntityTest.html("Kontakt", "", "<p>Schreiben Sie uns: info@musterbau.example</p>"));
        addPage("https://www.musterbau.example/blog/neubau", List.of("firmen"), 2,
                DomainEntityTest.html("Neubau", "", "<p>Partner: Andere Bau GmbH, Nebenweg 9, 10115 Berlin, Tel. 030 999999</p>"));
        instance.getDefaultServer().commit();
    }

    @AfterClass public static void close() { if (instance != null) instance.close(); }

    private static void add(final String url, final String collection, final int depth, final int status, final String title,
            final String description, final long loaded) throws Exception {
        final DigestURL d = new DigestURL(url);
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", d.getHost());
        s.setField("url_protocol_s", d.getProtocol());
        if (collection != null) s.setField("collection_sxt", List.of(collection));
        s.setField("crawldepth_i", depth);
        s.setField("httpstatus_i", status);
        s.setField("load_date_dt", new Date(loaded));
        if (title != null) s.setField("title", List.of(title));
        if (description != null) s.setField("description_txt", List.of(description));
        instance.getDefaultServer().add(s);
    }

    /** A page as YaCy indexes it: parsed text, title and publisher, URL path fields. */
    private static void addPage(final String url, final List<String> collections, final int depth, final String html) throws Exception {
        final DigestURL d = new DigestURL(url);
        final net.yacy.document.Document doc = net.yacy.document.TextParser.parseSource(d, "text/html", "UTF-8",
                net.yacy.document.parser.html.TagValency.EVAL, new HashSet<>(), new net.yacy.document.VocabularyScraper(), 0, depth,
                html.getBytes(java.nio.charset.StandardCharsets.UTF_8), null)[0];
        final SolrInputDocument s = new SolrInputDocument();
        s.setField("id", ASCII.String(d.hash()));
        s.setField("sku", url);
        s.setField("host_s", d.getHost());
        s.setField("url_protocol_s", d.getProtocol());
        s.setField("collection_sxt", collections);
        s.setField("crawldepth_i", depth);
        s.setField("httpstatus_i", 200);
        s.setField("load_date_dt", new Date(T0));
        s.setField("title", List.of(doc.dc_title()));
        s.setField("text_t", doc.getTextString());
        if (!doc.dc_publisher().isEmpty()) s.setField("publisher_t", doc.dc_publisher());
        final String file = d.getFileName();
        final String ext = net.yacy.cora.document.id.MultiProtocolURL.getFileExtension(file);
        s.setField("url_file_name_s", file.toLowerCase(java.util.Locale.ROOT).endsWith("." + ext) ? file.substring(0, file.length() - ext.length() - 1) : file);
        if (d.getPaths().length > 0) s.setField("url_paths_sxt", List.of(d.getPaths()));
        instance.getDefaultServer().add(s);
    }

    private static final List<ModifiableSolrParams> REQUESTS = new ArrayList<>();

    private static DomainCandidates candidates(final DomainEnrichment enrichment) {
        return new DomainCandidates(p -> {
            REQUESTS.add(new ModifiableSolrParams(p));
            return solr.getResponseByParams(p).getResponse();
        }, enrichment, () -> T0);
    }

    private static JSONObject page(final Map<String, String> q, final List<String> collections) throws Exception {
        return candidates(DomainEnrichment.NONE).page(q, collections);
    }

    private static JSONObject item(final JSONObject page, final String host, final String collection) throws Exception {
        final JSONArray items = page.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            final JSONObject o = items.getJSONObject(i);
            if (o.getString("host").equals(host) && String.valueOf(o.opt("collection")).equals(String.valueOf(collection))) return o;
        }
        fail(host + "/" + collection + " missing in " + page);
        return null;
    }

    @Test public void urlsOfOneHostBecomeOneCandidateWithTheStartPage() throws Exception {
        final JSONObject page = page(Map.of("q", "agentur"), List.of("stack"));
        assertEquals(1, page.getLong("total"));
        assertEquals(1, page.getJSONArray("items").length());
        final JSONObject a = item(page, "www.agentur.example", "stack");
        assertEquals(4, a.getLong("indexed_pages"));
        assertEquals("agentur.example", a.getString("domain"));
        assertEquals("https", a.getString("scheme"));
        assertEquals("https://www.agentur.example/", a.getString("website"));
        assertEquals("https://www.agentur.example/", a.getString("start_url"));
        assertEquals("Agentur Start", a.getString("title"));
        assertEquals("Wir bauen Shops.", a.getString("description"));
        assertEquals(200, a.getInt("http_status"));
        assertEquals(DomainCandidate.iso(T0 + DAY), a.getString("last_loaded")); // newest page of the host
        assertTrue(a.isNull("crawl_status"));
        assertTrue(a.isNull("classification"));
        assertTrue(a.isNull("discovery"));
    }

    @Test public void theSameHostInTwoCollectionsStaysSeparate() throws Exception {
        final JSONObject all = page(Map.of("q", "www.agentur.example"), null);
        assertEquals(2, all.getJSONArray("items").length());
        assertEquals(2, all.getLong("total"));
        assertEquals(1, all.getLong("total_hosts"));
        assertEquals(4, item(all, "www.agentur.example", "stack").getLong("indexed_pages"));
        final JSONObject coach = item(all, "www.agentur.example", "coach");
        assertEquals(1, coach.getLong("indexed_pages"));
        assertEquals("https://www.agentur.example/team", coach.getString("start_url"));
        // a collection filter never shows the other collection
        final JSONObject filtered = page(Map.of("q", "agentur"), List.of("coach"));
        assertEquals(1, filtered.getJSONArray("items").length());
        assertFalse(filtered.toString().contains("\"stack\""));
        assertEquals(0, page(Map.of(), List.of("absent")).getLong("total"));
    }

    @Test public void representativePrefersAnOkPageAndOptionalFieldsAreNull() throws Exception {
        final JSONObject broken = item(page(Map.of("q", "broken"), List.of("stack")), "broken.example", "stack");
        assertEquals("https://broken.example/ok", broken.getString("start_url"));
        assertEquals(200, broken.getInt("http_status"));
        final JSONObject plain = item(page(Map.of("q", "plain"), null), "plain.example", null);
        assertTrue(plain.isNull("collection"));
        assertTrue(plain.isNull("title"));
        assertTrue(plain.isNull("description"));
        assertEquals(1, plain.getLong("indexed_pages"));
    }

    @Test public void hostFilterIsLiteralAcceptsUrlsAndSortsByPages() throws Exception {
        assertEquals(1, page(Map.of("q", "https://www.agentur.example/impressum?x=1"), List.of("stack")).getLong("total"));
        assertEquals(1, page(Map.of("q", "WWW.Agentur.Example:443"), List.of("stack")).getLong("total"));
        for (final String bad : List.of("*:*", "a OR b", "{!lucene}x", "host_s:x", "\" OR \"")) {
            try { page(Map.of("q", bad), null); fail(bad + " accepted"); }
            catch (final ApiException e) { assertEquals(400, e.status()); }
        }
        final JSONObject byPages = page(Map.of("sort", "pages", "limit", "1"), List.of("stack"));
        assertEquals("www.agentur.example", byPages.getJSONArray("items").getJSONObject(0).getString("host"));
        final JSONObject byHost = page(Map.of("limit", "1"), List.of("stack"));
        assertEquals("broken.example", byHost.getJSONArray("items").getJSONObject(0).getString("host"));
        assertEquals(2, byHost.getLong("total"));
        for (final Map<String, String> bad : List.of(Map.of("limit", "101"), Map.of("offset", "-1"), Map.of("sort", "x"), Map.of("fq", "*:*")))
            try { page(bad, null); fail(bad + " accepted"); } catch (final ApiException e) { assertEquals(400, e.status()); }
    }

    private static String export(final String format, final Map<String, String> q, final List<String> collections) throws Exception {
        final StringWriter out = new StringWriter();
        final Map<String, String> input = new HashMap<>(q);
        input.put("format", format);
        candidates(DomainEnrichment.NONE).export(input, collections, DomainExport.sink(format, out));
        return out.toString();
    }

    @Test public void jsonExportIsValidStreamedAndOnlyTheFilterSet() throws Exception {
        final JSONObject json = new JSONObject(export("json", Map.of("q", "agentur"), List.of("stack")));
        assertEquals("scoutro.domains.v1", json.getString("schema"));
        assertEquals("stack", json.getString("collection"));
        assertEquals("agentur", json.getJSONObject("filter").getString("q"));
        assertEquals(1, json.getInt("count"));
        assertTrue(json.getBoolean("complete"));
        assertEquals(4, json.getJSONArray("items").getJSONObject(0).getLong("indexed_pages"));
        final JSONObject all = new JSONObject(export("json", Map.of(), null));
        final Set<String> seen = new HashSet<>();
        final JSONArray items = all.getJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            final JSONObject o = items.getJSONObject(i);
            assertTrue("duplicate " + o, seen.add(o.getString("host") + "|" + o.opt("collection")));
        }
        assertEquals(BULK + 6, items.length()); // agentur/stack, agentur/coach, broken/stack, plain without collection, musterbau/firmen+portal, bulk
        assertEquals(items.length(), all.getInt("count"));
        assertTrue(all.isNull("collection"));
    }

    @Test public void csvExportIsValidAndNeutralizesFormulas() throws Exception {
        final String csv = export("csv", Map.of("q", "broken"), List.of("stack"));
        final List<List<String>> rows = parseCsv(csv);
        assertEquals(DomainCandidate.CSV_COLUMNS, rows.get(0));
        assertEquals(2, rows.size());
        final List<String> row = rows.get(1);
        assertEquals(DomainCandidate.CSV_COLUMNS.size(), row.size());
        assertEquals("broken.example", row.get(0));
        assertEquals("'=HYPERLINK(\"x\")", row.get(DomainCandidate.CSV_COLUMNS.indexOf("title")));
        assertEquals("Text, mit \"Komma\"\nzweite Zeile", row.get(DomainCandidate.CSV_COLUMNS.indexOf("description")));
        assertEquals("", row.get(DomainCandidate.CSV_COLUMNS.indexOf("classification_verdict")));
        assertTrue(csv.endsWith("\r\n"));
        assertFalse(csv.contains("#incomplete"));
    }

    @Test public void largeExportsAreReadPageByPageAndStreamedWithoutSideEffects() throws Exception {
        final List<String> events = new ArrayList<>();
        final DomainCandidates c = new DomainCandidates(p -> {
            final String fl = String.valueOf(p.get("fl"));
            events.add("query " + (p.get("json.facet") != null ? "facet" : fl.contains("text_t") ? "text" : fl.contains("crawldepth_i") ? "entity" : "group"));
            if (fl.contains("text_t")) assertTrue("texts by identifier, at most one fetch page", p.get("q").startsWith("id:(")
                    && Integer.parseInt(p.get("rows")) <= DomainCandidates.ENTITY_FETCH);
            assertNull("no commit or update", p.get("commit"));
            assertNull(p.get("stream.body"));
            if (p.get("json.facet") != null) {
                assertEquals("0", p.get("rows"));
                assertTrue(p.get("json.facet").contains("\"limit\":" + DomainCandidates.EXPORT_PAGE));
            } else {
                assertTrue(Integer.parseInt(p.get("rows")) <= DomainCandidates.EXPORT_PAGE * DomainCandidates.GROUP_DOCS);
            }
            return solr.getResponseByParams(p).getResponse();
        }, DomainEnrichment.NONE, () -> T0);
        final int[] written = {0};
        c.export(Map.of("format", "json"), List.of("bulk"), new DomainCandidates.Sink() {
            @Override public void begin(final DomainCandidates.Filter f, final long at) { events.add("begin"); }
            @Override public void item(final DomainCandidate d) { written[0]++; }
            @Override public void flush() { events.add("flush " + written[0]); }
            @Override public void end(final long count, final boolean complete) { events.add("end " + count + " " + complete); }
        });
        assertEquals(BULK, written[0]);
        // three facet pages (200 + 200 + 50), each streamed before the next page is read; the entity
        // lookup reads 50 hosts at a time (candidate pages, then their texts) inside its page
        final List<String> entity = List.of("query entity", "query text");
        final List<String> expected = new ArrayList<>(List.of("query facet", "query group"));
        for (int i = 0; i < 4; i++) expected.addAll(entity);
        expected.addAll(List.of("begin", "flush 200", "query facet", "query group"));
        for (int i = 0; i < 4; i++) expected.addAll(entity);
        expected.addAll(List.of("flush 400", "query facet", "query group"));
        expected.addAll(entity);
        expected.addAll(List.of("flush 450", "end 450 true"));
        assertEquals(expected, events);
    }

    @Test public void entityDataComesFromTheIndexedPagesOfEachCollection() throws Exception {
        final JSONObject page = page(Map.of("q", "musterbau"), null);
        assertEquals(2, page.getJSONArray("items").length());
        final JSONObject firmen = item(page, "www.musterbau.example", "firmen");
        final JSONObject e = firmen.getJSONObject("entity"), v = firmen.getJSONObject("evidence");
        assertEquals("Musterbau GmbH", e.getString("name"));
        assertEquals("Musterstraße 1", e.getString("street"));
        assertEquals("50667", e.getString("postal_code"));
        assertEquals("Köln", e.getString("city"));
        assertTrue("no region in the index", e.isNull("region"));
        assertTrue(".example: country unknown", e.isNull("country"));
        assertEquals("country unknown: the number as written", "0221123456", e.getString("phone"));
        assertEquals("general mailbox of the contact page, not the personal one of the Impressum", "info@musterbau.example", e.getString("email"));
        assertEquals("https://www.musterbau.example/impressum/", v.getString("entity_url"));
        assertEquals("https://www.musterbau.example/impressum/", v.getString("contact_url"));
        assertEquals("https://www.musterbau.example/Kontakt.html", v.getString("email_url"));
        assertEquals("page_text", v.getString("name_method"));
        // the same host in a second collection keeps its own entry: only its own start page counts
        final JSONObject portal = item(page, "www.musterbau.example", "portal");
        for (final String key : portal.getJSONObject("entity").keySet()) assertTrue(key, portal.getJSONObject("entity").isNull(key));
        for (final String key : portal.getJSONObject("evidence").keySet()) assertTrue(key, portal.getJSONObject("evidence").isNull(key));
        // the blog page names another company: it is neither an entity page nor the representative
        assertFalse(firmen.toString().contains("Andere Bau"));
        // entries without entity pages carry the objects with null values
        final JSONObject bulk = page(Map.of("q", "bulk0001"), null).getJSONArray("items").getJSONObject(0);
        assertTrue(bulk.getJSONObject("entity").isNull("name") && bulk.getJSONObject("evidence").isNull("entity_url"));

        // export: the same values, JSON and the appended CSV columns
        final JSONObject json = new JSONObject(export("json", Map.of("q", "musterbau"), List.of("firmen")));
        assertEquals("Musterbau GmbH", json.getJSONArray("items").getJSONObject(0).getJSONObject("entity").getString("name"));
        final List<List<String>> rows = parseCsv(export("csv", Map.of("q", "musterbau"), List.of("firmen")));
        assertEquals(DomainCandidate.CSV_COLUMNS, rows.get(0));
        assertEquals("existing columns keep their order", List.of("host", "domain", "scheme"), rows.get(0).subList(0, 3));
        assertEquals("discovery_region", rows.get(0).get(19));
        final List<String> row = rows.get(1);
        assertEquals("Musterbau GmbH", row.get(DomainCandidate.CSV_COLUMNS.indexOf("entity_name")));
        assertEquals("50667", row.get(DomainCandidate.CSV_COLUMNS.indexOf("entity_postal_code")));
        assertEquals("", row.get(DomainCandidate.CSV_COLUMNS.indexOf("entity_region")));
        assertEquals("info@musterbau.example", row.get(DomainCandidate.CSV_COLUMNS.indexOf("entity_email")));
        assertEquals("https://www.musterbau.example/impressum/", row.get(DomainCandidate.CSV_COLUMNS.indexOf("evidence_entity_url")));
    }

    @Test public void theEntityLookupOnlyReadsTheIndex() throws Exception {
        final List<ModifiableSolrParams> seen = new ArrayList<>();
        new DomainCandidates(p -> {
            seen.add(new ModifiableSolrParams(p));
            return solr.getResponseByParams(p).getResponse();
        }, DomainEnrichment.NONE, () -> T0).page(Map.of("q", "musterbau"), null);
        assertEquals("facet, then per collection: representative, candidates, texts", 1 + 2 * 3, seen.size());
        for (final ModifiableSolrParams p : seen) {
            assertNull(p.get("commit")); assertNull(p.get("stream.body")); assertNull(p.get("qt")); assertNull(p.get("stream.url"));
            final String fl = String.valueOf(p.get("fl"));
            if (fl.contains("text_t")) assertTrue(p.get("q").startsWith("id:("));
        }
    }

    @Test public void anErrorAfterTheStartEndsTheFileAsIncomplete() throws Exception {
        final int[] enriched = {0};
        final DomainEnrichment failing = () -> b -> {
            if (++enriched[0] > DomainCandidates.EXPORT_PAGE) throw new IllegalStateException("broken state record");
        };
        final StringWriter out = new StringWriter();
        new DomainCandidates(p -> solr.getResponseByParams(p).getResponse(), failing, () -> T0)
                .export(Map.of("format", "json"), List.of("bulk"), DomainExport.sink("json", out));
        final JSONObject json = new JSONObject(out.toString());
        assertFalse(json.getBoolean("complete"));
        assertEquals(DomainCandidates.EXPORT_PAGE, json.getInt("count"));
        assertEquals(DomainCandidates.EXPORT_PAGE, json.getJSONArray("items").length());
        assertFalse("no error document inside the file", out.toString().contains("\"error\""));
    }

    @Test public void anUnavailableIndexFailsBeforeAnythingIsWritten() throws Exception {
        final DomainCandidates c = new DomainCandidates(p -> { throw new java.io.IOException("down"); }, DomainEnrichment.NONE, () -> T0);
        final StringWriter out = new StringWriter();
        try { c.export(Map.of(), null, DomainExport.sink("json", out)); fail("no error"); }
        catch (final ApiException e) { assertEquals(503, e.status()); }
        assertEquals("", out.toString());
        try { c.page(Map.of(), null); fail("no error"); } catch (final ApiException e) { assertEquals(503, e.status()); }
    }

    @Test public void enrichmentMapsOnlyUnambiguousScoutroRecords() throws Exception {
        final Map<String, DomainEnrichment.Job> jobs = Map.of("job-1", new DomainEnrichment.Job("stackfinder", "osm"),
                "job-2", new DomainEnrichment.Job("stackfinder", null));
        final JSONObject state = new JSONObject("{\"state_version\":2,\"domains\":{"
                + "\"agentur.example\":{\"profiles\":{\"stackfinder\":{\"collection\":\"stack\",\"region\":\"Hamburg\"},"
                + "\"coach\":{\"collection\":\"coach\"}},\"classifications\":{\"stackfinder\":{\"verdict\":\"PASS\",\"confidence\":0.91,"
                + "\"collection\":\"stack\",\"classified_at\":\"2026-10-01T10:00:00Z\"},\"coach\":{\"verdict\":\"MAYBE\",\"collection\":\"coach\"}}},"
                + "\"broken.example\":{\"profile\":\"stackfinder\",\"collection\":\"stack\"}}}");
        final Map<String, DomainEnrichment.DomainState> parsed = DomainEnrichment.StateFile.parse(state);
        final DomainEnrichment enrichment = DomainEnrichment.of((host, collection) -> "www.agentur.example".equals(host) && "stack".equals(collection)
                ? new DomainEnrichment.Crawl("indexed", T0, "job-1", "https") : null, () -> jobs, () -> parsed);
        final JSONObject stack = item(candidates(enrichment).page(Map.of("q", "agentur"), null), "www.agentur.example", "stack");
        assertEquals("indexed", stack.getString("crawl_status"));
        assertEquals(DomainCandidate.iso(T0), stack.getString("last_crawled"));
        final JSONObject cls = stack.getJSONObject("classification");
        assertEquals("PASS", cls.getString("verdict"));
        assertEquals(0.91, cls.getDouble("confidence"), 1e-9);
        assertEquals("stackfinder", cls.getString("profile"));
        assertEquals("2026-10-01T10:00:00Z", cls.getString("classified_at"));
        final JSONObject discovery = stack.getJSONObject("discovery");
        assertEquals("stackfinder", discovery.getString("profile"));
        assertEquals("osm", discovery.getString("source"));
        assertEquals("job-1", discovery.getString("job"));
        assertEquals("Hamburg", discovery.getString("region"));
        // other collection: profile from the state, no crawl job, invalid verdict ignored
        final JSONObject coach = item(candidates(enrichment).page(Map.of("q", "agentur"), null), "www.agentur.example", "coach");
        assertTrue(coach.isNull("classification"));
        assertEquals("coach", coach.getJSONObject("discovery").getString("profile"));
        assertTrue(coach.getJSONObject("discovery").isNull("source"));
        // state_version 1 entry: profile without classification
        final JSONObject broken = item(candidates(enrichment).page(Map.of("q", "broken"), null), "broken.example", "stack");
        assertEquals("stackfinder", broken.getJSONObject("discovery").getString("profile"));
        assertTrue(broken.isNull("classification"));
        // nothing recorded: everything stays null
        final JSONObject bulk = item(candidates(enrichment).page(Map.of("q", "bulk0001"), null), "bulk0001.example", "bulk");
        assertTrue(bulk.isNull("discovery"));
        assertTrue(bulk.isNull("crawl_status"));
    }

    @Test public void registrableDomainMatchesScoutroDiscovery() {
        assertEquals("example.de", DomainCandidate.registrableDomain("www.Example.de."));
        assertEquals("example.co.uk", DomainCandidate.registrableDomain("shop.example.co.uk"));
        assertEquals("example.de", DomainCandidate.registrableDomain("example.de"));
        assertEquals("10.0.0.1", DomainCandidate.registrableDomain("10.0.0.1"));
    }

    /** RFC 4180 reader for the assertions. */
    static List<List<String>> parseCsv(final String text) {
        final List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        final StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (ch == '"') quoted = false;
                else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                row.add(cell.toString()); cell.setLength(0); rows.add(row); row = new ArrayList<>(); i++;
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }
}
