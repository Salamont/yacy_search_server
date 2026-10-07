package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The LLM tier end to end (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3 to 6.5): the
 * embedded Solr core with the shipped configuration, the real sync for tiers
 * 1 and 2, and a controllable model. Covers grounding, uncertainty, cache,
 * timeouts and the circuit breaker, the per-host cap, a model that is not
 * configured or hangs, changed input, restart and the full reset.
 */
public class LlmServiceTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EmbeddedInstance solr;
    private KgPaths paths;
    private KgConfig cfg;
    private StorageGuard guard;
    private KgStore store;
    private DirtySet dirty;
    private SolrSource source;
    private SyncService sync;
    private LlmService llm;
    private final Fake model = new Fake();
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());

    static final String LD = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\","
            + "\"url\":\"https://www.muster-pflege.de/\",\"telephone\":\"030 1234567\"}";
    static final String TEXT = "Impressum. Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. Das Haus Lindenhof"
            + " bietet Tagespflege an. Die Muster Pflege gGmbH plant ab 2027 das Haus Am See.";

    /** A model under the test's control. */
    static final class Fake implements LlmClient {
        volatile String name = "TEST/fixture";
        final AtomicInteger calls = new AtomicInteger();
        volatile Function<String, String> answer = Fake::grounded;
        volatile IOException failure;
        volatile CountDownLatch entered;
        volatile CountDownLatch release;
        /** Runs inside the call, once (a recrawl while the model thinks). */
        volatile Runnable during;

        @Override
        public String model() {
            return this.name;
        }

        /** The structured-output setting of the last call. */
        volatile String structuredOutput;

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis,
                final String setting) throws IOException {
            this.structuredOutput = setting;
            return complete(system, user, schema, timeoutMillis);
        }

        @Override
        public JSONObject structuredOutput(final String setting) {
            return net.yacy.scoutro.knowledge.KgJson.obj("setting", setting, "mode", "fake");
        }

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
                throws IOException {
            this.calls.incrementAndGet();
            final CountDownLatch in = this.entered;
            final CountDownLatch out = this.release;
            if (in != null && out != null) {
                in.countDown();
                try {
                    out.await(30, TimeUnit.SECONDS);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            final Runnable d = this.during;
            if (d != null) {
                this.during = null;
                d.run();
            }
            final IOException f = this.failure;
            if (f != null) {
                throw f;
            }
            return this.answer.apply(user);
        }

        static String grounded(final String user) {
            if (user.contains("Haus Sonnenhof")) {
                return "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Sonnenhof\",\"kind\":\"nursinghome\","
                        + "\"quote\":\"betreibt das Haus Sonnenhof\"}],\"claims\":[{\"subject\":\"k1\",\"predicate\":\"operates\","
                        + "\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Sonnenhof\"}]}";
            }
            if (!user.contains("Haus Lindenhof")) {
                return "{\"entities\":[],\"claims\":[]}";
            }
            return "{\"entities\":["
                    + "{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"kind\":\"nursinghome\",\"quote\":\"betreibt das Haus Lindenhof in Berlin\"},"
                    + "{\"id\":\"e2\",\"type\":\"service\",\"name\":\"Tagespflege\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"},"
                    + "{\"id\":\"e3\",\"type\":\"facility\",\"name\":\"Haus Am See\",\"quote\":\"plant ab 2027 das Haus Am See\"},"
                    + "{\"id\":\"e4\",\"type\":\"organization\",\"name\":\"Erfundene Holding AG\",\"quote\":\"Die Erfundene Holding AG betreibt\"}],"
                    + "\"claims\":["
                    + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"},"
                    + "{\"subject\":\"e1\",\"predicate\":\"offers\",\"object\":\"e2\",\"quote\":\"Das Haus Lindenhof bietet Tagespflege an\"},"
                    + "{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e3\",\"quote\":\"Die Muster Pflege gGmbH plant ab 2027 das Haus Am See\"},"
                    + "{\"subject\":\"k1\",\"predicate\":\"part_of\",\"object\":\"e4\",\"quote\":\"Die Muster Pflege gGmbH gehört zur Erfundene Holding AG\"}]}";
        }
    }

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1",
                new String[] {"collection1", "webgraph"});
        this.paths = new KgPaths(this.tmp.newFolder("data"));
        configure("2");
        start();
    }

    private void configure(final String maxDocsPerHost) {
        this.cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1,c2", KgConfig.LLM_COLLECTIONS, "c1",
                KgConfig.LLM_MAX_DOCS_PER_HOST, maxDocsPerHost, "scoutro.kg.llm.kinds.c1", "nursinghome"));
        assertTrue(this.cfg.problems().toString(), this.cfg.valid());
    }

    private void start() throws Exception {
        this.guard = new StorageGuard(this.cfg, this.paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, this.guard, KgStore.SQLITE, System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        this.source = new EmbeddedSolrSource(() -> this.solr.getDefaultServer());
        final Gates gates = new Gates(this.cfg, Gates.IDLE);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, this.source, gates, this.clock::get, false);
        this.llm = new LlmService(this.cfg, this.store, this.source, gates, this.model, this.clock::get);
    }

    private void restart() throws Exception {
        this.llm.requestStop();
        this.sync.requestStop();
        Capture.deactivate(this.dirty);
        this.sync.finalDrain(this.clock.get() + 2000L);
        this.store.close();
        start();
    }

    @After
    public void close() {
        if (this.dirty != null) {
            Capture.deactivate(this.dirty);
        }
        JsonLdCapture.clear();
        if (this.store != null) {
            this.store.close();
        }
        if (this.solr != null) {
            this.solr.close();
        }
    }

    // ------------------------------------------------------------ helpers

    private SolrClient client() {
        return this.solr.getDefaultServer();
    }

    private void add(final String id, final String url, final String collection, final String ld, final String text)
            throws Exception {
        add(id, url, collection, ld, text, "Impressum");
    }

    private void add(final String id, final String url, final String collection, final String ld, final String text,
            final String title) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", List.of(collection));
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of(title));
        d.setField("exact_signature_l", (long) text.hashCode());
        d.setField("text_t", text);
        if (ld != null) {
            d.setField("ld_json_txt", List.of(ld));
        }
        client().add(d);
        client().commit();
    }

    private void settleSync() {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !this.sync.reconciler().pending()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(3000L);
            }
        }
        fail("sync did not settle: " + this.sync.status());
    }

    /** Steps the LLM tier until its queue is empty and nothing is left to do. */
    private void settleLlm() {
        settleLlm(() -> count("SELECT count(*) FROM kg_llm_work") == 0L
                && count("SELECT count(*) FROM kg_doc WHERE state = 1 AND llm_status IS NULL") == 0L);
    }

    private void settleLlm(final BooleanSupplier done) {
        for (int i = 0; i < 500; i++) {
            final boolean more = this.llm.step();
            if (!more && done.getAsBoolean()) {
                return;
            }
            if (!more) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        fail("LLM tier did not settle: " + this.llm.status());
    }

    private long count(final String sql) {
        try {
            return this.store.read(c -> KgStore.queryLong(c, sql));
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> strings(final String sql) {
        try {
            return this.store.read(c -> {
                final List<String> out = new ArrayList<>();
                try (java.sql.Statement st = c.createStatement(); java.sql.ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            });
        } catch (final KgException e) {
            throw new AssertionError(e);
        }
    }

    private String llmStatus(final String id) {
        final List<String> s = strings("SELECT coalesce(llm_status, 'null') || ':' || coalesce(llm_reason, '') FROM kg_doc WHERE doc_id = '"
                + id + "'");
        return s.isEmpty() ? null : s.get(0);
    }

    private static final String RELATIONS = "SELECT v.name || ' ' || (SELECT obj_val FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred"
            + " WHERE n.subj = s.obj_ent AND nv.name = 'name' LIMIT 1) || ' q' || s.quality FROM kg_statement s"
            + " JOIN kg_vocab v ON v.term_id = s.pred WHERE s.obj_ent IS NOT NULL ORDER BY 1";

    static net.yacy.scoutro.knowledge.extract.Mention mention() {
        final net.yacy.scoutro.knowledge.extract.Mention m = new net.yacy.scoutro.knowledge.extract.Mention("jsonld:0",
                net.yacy.scoutro.knowledge.extract.Vocabulary.ORGANIZATION, 1);
        m.name = "Muster Pflege gGmbH";
        return m;
    }

    static long sum(final JSONObject byReason) throws Exception {
        long n = 0;
        for (final String k : byReason.keySet()) {
            n += byReason.getLong(k);
        }
        return n;
    }

    // -------------------------------------------------------------- tests

    @Test
    public void theCountersOfTheItemsAddUpAndRefusedAnswersCountNoItem() throws Exception {
        final LlmService.Counters c = new LlmService.Counters();
        final JSONObject zero = c.json();
        assertEquals(0L, zero.getLong("valuesAccepted"));
        assertEquals(0L, zero.getLong("droppedInvalid"));
        final JSONObject by0 = zero.getJSONObject("droppedInvalidByReason");
        assertEquals("every code, also before the first answer", LlmExtractor.INVALID_REASONS.size(), by0.length());
        for (final String reason : LlmExtractor.INVALID_REASONS) {
            assertEquals(reason, 0L, by0.getLong(reason));
        }
        assertTrue("the whole-answer refusals stay apart", zero.getJSONObject("refusedBy").keySet().isEmpty());
        final LlmExtractor.Chunk chunk = LlmExtractor.chunks(TEXT, 12_000).get(0);
        final List<LlmExtractor.Known> known = List.of(new LlmExtractor.Known("k1", mention()));
        final String answer = "{\"entities\":[{\"id\":\"e1\",\"type\":\"facility\",\"name\":\"Haus Lindenhof\",\"quote\":\"betreibt das Haus Lindenhof\"},"
                + "{\"id\":\"e2\",\"type\":\"service\",\"name\":\"Tagespflege\",\"quote\":\"bietet Tagespflege an\",\"source\":\"page\"},"
                + "{\"id\":\"e3\",\"type\":\"company\",\"name\":\"Haus Am See\",\"quote\":\"das Haus Am See\"}],"
                + "\"claims\":[{\"subject\":\"k1\",\"predicate\":\"operates\",\"object\":\"e1\",\"quote\":\"Die Muster Pflege gGmbH betreibt das Haus Lindenhof\"},"
                + "{\"subject\":\"k1\",\"predicate\":\"offers\",\"object\":\"e2\",\"quote\":\"bietet Tagespflege an\"}],"
                + "\"values\":[{\"subject\":\"e1\",\"predicate\":\"price\",\"quote\":\"das Haus Lindenhof\"}]}";
        final LlmExtractor.Result r = LlmExtractor.validate(answer, chunk, known, java.util.Set.of());
        assertNull(r.refused);
        c.accepted(r);
        c.accepted(r);
        c.refused(LlmExtractor.validate("{\"entities\":[],\"note\":1}", chunk, known, java.util.Set.of()).refused);
        c.refused(LlmExtractor.validate("not json", chunk, known, java.util.Set.of()).refused);
        final JSONObject p = c.json();
        assertEquals(2L, p.getLong("answersAccepted"));
        assertEquals(2L, p.getLong("answersRefused"));
        assertEquals(1L, p.getJSONObject("refusedBy").getLong("unknown_field"));
        assertEquals(1L, p.getJSONObject("refusedBy").getLong("invalid_json"));
        assertEquals(2L, p.getLong("entitiesAccepted"));
        assertEquals(2L, p.getLong("claimsAccepted"));
        assertEquals("a price quote without an amount is not grounded", 0L, p.getLong("valuesAccepted"));
        assertEquals(2L, p.getLong("droppedUngrounded"));
        // the entity with the extra field and the one with an unknown type, then the claim about the dropped service
        assertEquals(6L, p.getLong("droppedInvalid"));
        final JSONObject by = p.getJSONObject("droppedInvalidByReason");
        assertEquals(2L, by.getLong("entity_extra_field"));
        assertEquals(2L, by.getLong("entity_unknown_type"));
        assertEquals(2L, by.getLong("claim_unresolved_object"));
        assertEquals(p.getLong("droppedInvalid"), sum(by));
        assertEquals(LlmExtractor.INVALID_REASONS.size(), by.length());
        // a value accepted
        final LlmExtractor.Result v = LlmExtractor.validate("{\"entities\":[{\"id\":\"e2\",\"type\":\"service\",\"name\":\"Tagespflege\","
                + "\"quote\":\"bietet Tagespflege an\"}],\"claims\":[],\"values\":[{\"subject\":\"e2\",\"predicate\":\"category\","
                + "\"quote\":\"bietet Tagespflege an\"}]}", chunk, known, java.util.Set.of());
        c.accepted(v);
        assertEquals(1L, c.json().getLong("valuesAccepted"));
        assertEquals(6L, c.json().getLong("droppedInvalid"));
    }

    @Test
    public void groundedRelationsArePublishedAsUncertainWithProvenance() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        assertEquals("tiers 1 and 2 do not wait for the LLM tier", 0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals(1, this.model.calls.get());
        assertEquals(List.of("offers Tagespflege q2", "operates Haus Am See q2", "operates Haus Lindenhof q2"), strings(RELATIONS));
        assertTrue("the hallucinated organisation never enters the graph",
                strings("SELECT obj_val FROM kg_statement WHERE obj_val LIKE '%Erfundene%'").isEmpty());
        assertEquals("the organisation's name from JSON-LD stays supported", List.of("1"), strings("SELECT s.quality FROM kg_statement s"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE v.name = 'name' AND s.obj_val = 'Muster Pflege gGmbH'"));
        assertEquals("the planned facility is hedged", List.of("2"), strings("SELECT e.certainty FROM kg_evidence e JOIN kg_statement s"
                + " ON s.stmt_rowid = e.stmt_rowid JOIN kg_statement n ON n.subj = s.obj_ent AND n.obj_val = 'Haus Am See' WHERE e.tier = 3"));
        assertEquals("provenance: model and prompt of the extractor", List.of("llm|" + LlmExtractor.VERSION + "|TEST/fixture|" + LlmExtractor.PROMPT_HASH),
                strings("SELECT DISTINCT x.name || '|' || x.version || '|' || x.model || '|' || x.prompt_hash FROM kg_evidence e"
                        + " JOIN kg_extractor x ON x.ext_id = e.ext_id WHERE e.tier = 3"));
        assertTrue(strings("SELECT excerpt FROM kg_evidence WHERE tier = 3").contains("Die Muster Pflege gGmbH betreibt das Haus Lindenhof"));
        assertEquals(List.of("nursinghome"), strings("SELECT DISTINCT e.subkind FROM kg_entity e JOIN kg_statement n ON n.subj = e.ent_rowid"
                + " WHERE n.obj_val = 'Haus Lindenhof'"));
        assertEquals(List.of("7"), strings("SELECT tiers FROM kg_doc WHERE doc_id = 'AAAAAAhost01'"));
        final JSONObject st = this.llm.status();
        assertEquals(1L, st.getJSONObject("processed").getLong("droppedUngrounded"));
        assertEquals(1L, st.getJSONObject("processed").getLong("published"));
        // the structured-output setting reaches the client; its state is part of the status
        assertEquals("auto", this.model.structuredOutput);
        assertEquals("auto", st.getJSONObject("structuredOutput").getString("setting"));
        assertEquals("fake", st.getJSONObject("structuredOutput").getString("mode"));
        // the items of the answer: the claim about the dropped holding is invalid (its object was not accepted)
        final JSONObject p = st.getJSONObject("processed");
        assertEquals(3L, p.getLong("entitiesAccepted"));
        assertEquals(3L, p.getLong("claimsAccepted"));
        assertEquals(0L, p.getLong("valuesAccepted"));
        assertEquals(1L, p.getLong("droppedInvalid"));
        assertEquals(1L, p.getJSONObject("droppedInvalidByReason").getLong("claim_unresolved_object"));
        assertEquals(p.getLong("droppedInvalid"), sum(p.getJSONObject("droppedInvalidByReason")));
        // the same document processed again by the sync (unchanged) keeps the LLM result
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        assertEquals(1, this.model.calls.get());
        assertEquals(3, strings(RELATIONS).size());
    }

    @Test
    public void theManualPauseStopsTheLlmTierBeforeAnyModelCall() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        this.guard.setManualPause(true);
        for (int i = 0; i < 50; i++) {
            this.llm.step();
            this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
        }
        assertEquals("no model call during the pause", 0, this.model.calls.get());
        assertEquals("nothing queued or claimed", 0L, count("SELECT count(*) FROM kg_llm_work"));
        assertEquals(0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        assertEquals("paused", this.llm.status().optString("state"));
        assertEquals(StorageGuard.MANUAL, this.llm.status().optString("reason"));
        // the pause survives a restart (persisted by the runtime; here set again as the runtime does at start)
        restart();
        this.guard.setManualPause(true);
        for (int i = 0; i < 10; i++) {
            this.llm.step();
            this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
        }
        assertEquals(0, this.model.calls.get());
        this.guard.setManualPause(false);
        settleLlm();
        assertEquals("the resume continues where the pause stopped", 1, this.model.calls.get());
        assertEquals("1:", llmStatus("AAAAAAhost01"));
    }

    @Test
    public void withoutModelOrOutsideTheLlmCollectionsTheGraphUsesTiersOneAndTwo() throws Exception {
        this.model.name = null;
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        assertFalse(this.llm.step());
        assertEquals("not_configured", this.llm.status().getString("state"));
        assertEquals(0, this.model.calls.get());
        assertTrue("tiers 1 and 2 published", count("SELECT count(*) FROM kg_evidence WHERE tier = 1") > 0L);
        assertEquals(0L, count("SELECT count(*) FROM kg_llm_work"));
        this.model.name = "TEST/fixture";
        this.clock.addAndGet(LlmService.NOT_CONFIGURED_MILLIS + 1L);
        add("BBBBBBhost02", "https://www.andere-pflege.de/impressum", "c2", LD.replace("muster-pflege", "andere-pflege"), TEXT);
        settleSync();
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals("c2 is followed, but not by the LLM tier", "3:not_selected", llmStatus("BBBBBBhost02"));
        assertEquals(1, this.model.calls.get());
    }

    @Test
    public void invalidAnswersAreRefusedCountedAndCached() throws Exception {
        this.model.answer = u -> "Gern! Hier ist die Liste: Haus Lindenhof, Muster Pflege gGmbH.";
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        assertEquals("the document is done: its text was asked once", "1:", llmStatus("AAAAAAhost01"));
        assertEquals(0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        assertEquals(1L, this.llm.status().getJSONObject("processed").getJSONObject("refusedBy").getLong("invalid_json"));
        assertEquals(1L, count("SELECT count(*) FROM kg_extraction WHERE status = 2"));
        // the same text with the same context on another page of the site: answered from the cache
        add("BBBBBBhost01", "https://www.muster-pflege.de/ueber-uns", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        assertEquals(1, this.model.calls.get());
        assertEquals(1L, this.llm.status().getJSONObject("processed").getLong("cacheHits"));
        assertEquals("1:", llmStatus("BBBBBBhost01"));
    }

    @Test
    public void validResultsAreCachedPerSite() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        add("BBBBBBhost01", "https://www.muster-pflege.de/ueber-uns", "c1", LD, TEXT);
        add("CCCCCChost03", "https://www.dritte-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        assertEquals("one call per site: the same text on another site is asked again", 2, this.model.calls.get());
        assertEquals(3L, count("SELECT count(DISTINCT doc_rowid) FROM kg_evidence WHERE tier = 3"));
        assertEquals(2L, count("SELECT count(*) FROM kg_extraction WHERE status = 1"));
    }

    @Test
    public void transportFailuresAreBoundedOpenTheBreakerAndCanBeRetried() throws Exception {
        this.model.failure = new SocketTimeoutException("Read timed out");
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        add("BBBBBBhost02", "https://www.andere-pflege.de/impressum", "c1", LD.replace("muster-pflege", "andere-pflege"), TEXT);
        settleSync();
        settleLlm(() -> count("SELECT count(*) FROM kg_doc WHERE llm_status = 2") == 2L);
        assertEquals("two attempts per document, then failed", 4, this.model.calls.get());
        assertEquals("1 failed:llm_failed", "2:llm_failed", llmStatus("AAAAAAhost01"));
        final JSONObject st = this.llm.status();
        assertEquals(4L, st.getJSONObject("processed").getLong("timeouts"));
        assertTrue("three consecutive failures open the breaker", st.getJSONObject("breaker").getLong("timesOpened") >= 1L);
        // the crawl and tiers 1 and 2 go on while the tier is paused
        add("CCCCCChost03", "https://www.dritte-pflege.de/impressum", "c2", LD.replace("muster-pflege", "dritte-pflege"), TEXT);
        settleSync();
        assertTrue(count("SELECT count(*) FROM kg_doc WHERE doc_id = 'CCCCCChost03' AND tiers & 1 = 1") == 1L);
        // nothing is retried without bound: more steps make no calls
        for (int i = 0; i < 20; i++) {
            this.llm.step();
            this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
        }
        assertEquals(4, this.model.calls.get());
        // the model is back; the admin retries
        this.model.failure = null;
        assertEquals(2, this.llm.retryFailed());
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals("1:", llmStatus("BBBBBBhost02"));
        assertFalse(this.llm.status().getJSONObject("breaker").getBoolean("open"));
    }

    @Test
    public void thePerHostCapBoundsTheWork() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        add("BBBBBBhost01", "https://www.muster-pflege.de/ueber-uns", "c1", LD, TEXT + " Seit 1990.");
        add("CCCCCChost01", "https://www.muster-pflege.de/kontakt", "c1", LD, TEXT + " Seit 1991.");
        add("DDDDDDhost04", "https://www.vierte-pflege.de/impressum", "c1", LD.replace("muster-pflege", "vierte-pflege"), TEXT);
        settleSync();
        settleLlm();
        assertEquals(List.of("1:", "1:", "3:host_cap", "1:"), List.of(llmStatus("AAAAAAhost01"), llmStatus("BBBBBBhost01"),
                llmStatus("CCCCCChost01"), llmStatus("DDDDDDhost04")));
        assertEquals("imprint and about pages first, contact page left out", 3, this.model.calls.get());
    }

    @Test
    public void pagesThatAreNoCandidatesAreSkipped() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/blog/rezepte", "c1", null, "Ein Rezept für Apfelkuchen.", "Rezepte");
        settleSync();
        settleLlm();
        assertEquals("3:not_candidate", llmStatus("AAAAAAhost01"));
        assertEquals(0, this.model.calls.get());
    }

    @Test
    public void changedInputReplacesTheLlmResult() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        settleLlm();
        assertEquals(3, strings(RELATIONS).size());
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD,
                "Impressum. Die Muster Pflege gGmbH betreibt das Haus Sonnenhof.");
        settleSync();
        assertEquals("the old quotes do not hold for the new text", 0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        assertEquals("null:", llmStatus("AAAAAAhost01"));
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals(List.of("operates Haus Sonnenhof q2"), strings(RELATIONS.replace("ORDER BY 1", "AND s.current_sources > 0 ORDER BY 1")));
        assertTrue(strings("SELECT obj_val FROM kg_statement n JOIN kg_evidence e ON e.stmt_rowid = n.stmt_rowid WHERE e.tier = 3")
                .contains("Haus Sonnenhof"));
        assertFalse(strings("SELECT obj_val FROM kg_statement n JOIN kg_evidence e ON e.stmt_rowid = n.stmt_rowid WHERE e.tier = 3")
                .contains("Haus Lindenhof"));
    }

    @Test
    public void aRecrawlDuringTheCallDiscardsTheOldAnswer() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        this.model.during = () -> {
            try {
                add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD,
                        "Impressum. Die Muster Pflege gGmbH betreibt das Haus Sonnenhof.");
                settleSync();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        };
        this.llm.step();
        assertEquals("the answer about the old text is not published", 0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        assertEquals(1L, this.llm.status().getJSONObject("processed").getLong("abortedChanged"));
        assertEquals("the new input is still to do", "null:", llmStatus("AAAAAAhost01"));
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals(List.of("operates Haus Sonnenhof q2"), strings(RELATIONS.replace("ORDER BY 1", "AND s.current_sources > 0 ORDER BY 1")));
    }

    @Test
    public void aHangingModelBlocksNeitherTheSyncNorTheStore() throws Exception {
        this.model.entered = new CountDownLatch(1);
        this.model.release = new CountDownLatch(1);
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        final Thread worker = new Thread(() -> this.llm.step(), "test-extract");
        worker.start();
        assertTrue("the call is running", this.model.entered.await(20, TimeUnit.SECONDS));
        // meanwhile the crawl indexes and the sync publishes
        add("BBBBBBhost02", "https://www.andere-pflege.de/impressum", "c2", LD.replace("muster-pflege", "andere-pflege"), TEXT);
        settleSync();
        assertEquals(1L, count("SELECT count(*) FROM kg_doc WHERE doc_id = 'BBBBBBhost02' AND tiers & 1 = 1"));
        this.model.release.countDown();
        worker.join(20_000L);
        assertFalse(worker.isAlive());
        assertEquals("1:", llmStatus("AAAAAAhost01"));
    }

    @Test
    public void aRestartKeepsTheQueueAndReleasesClaims() throws Exception {
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        // a call is in flight when Scoutro stops: the claim stays in the queue, nothing is published
        this.model.entered = new CountDownLatch(1);
        this.model.release = new CountDownLatch(1);
        final LlmService old = this.llm;
        final Thread worker = new Thread(old::step, "test-extract");
        worker.start();
        assertTrue(this.model.entered.await(20, TimeUnit.SECONDS));
        assertEquals(1L, count("SELECT count(*) FROM kg_llm_work WHERE claimed_at IS NOT NULL"));
        old.requestStop();
        this.model.release.countDown();
        worker.join(20_000L);
        assertEquals("the stopped worker writes no result", 0L, count("SELECT count(*) FROM kg_evidence WHERE tier = 3"));
        this.model.entered = null;
        this.model.release = null;
        restart();
        settleLlm();
        assertEquals("1:", llmStatus("AAAAAAhost01"));
        assertEquals(3, strings(RELATIONS).size());
    }

    @Test
    public void aFullResetEmptiesTheQueue() throws Exception {
        this.model.failure = new IOException("Request failed with response code 503");
        add("AAAAAAhost01", "https://www.muster-pflege.de/impressum", "c1", LD, TEXT);
        settleSync();
        this.llm.step(); // queued, attempted once, released with a backoff
        assertEquals(1L, count("SELECT count(*) FROM kg_llm_work"));
        client().deleteByQuery("*:*");
        client().commit();
        settleSync();
        assertEquals(0L, count("SELECT count(*) FROM kg_llm_work"));
        assertEquals(0L, count("SELECT count(*) FROM kg_doc"));
        assertNull(llmStatus("AAAAAAhost01"));
    }

    @Test
    public void settingsAreValidated() throws Exception {
        final KgConfig bad = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1,c9",
                KgConfig.LLM_PARALLEL, "3", "scoutro.kg.llm.kinds.c1", "Pflege-Heim"));
        assertFalse(bad.valid());
        assertEquals(bad.problems().toString(), 2, bad.problems().size());
        final KgConfig ok = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1,edelsenior-web",
                KgConfig.LLM_COLLECTIONS, "*"));
        assertTrue(ok.valid());
        assertTrue(ok.llmFollows("edelsenior-web"));
        assertTrue("start vocabulary of the collection", ok.llmKinds(List.of("edelsenior-web")).contains("nursinghome"));
        assertTrue(ok.llmKinds(List.of("c1")).isEmpty());
        assertEquals("auto", ok.llmStructuredOutput);
        for (final String v : List.of("auto", "json_schema", "json_object", "none")) {
            final KgConfig so = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1", KgConfig.LLM_STRUCTURED_OUTPUT, v));
            assertTrue(so.problems().toString(), so.valid());
            assertEquals(v, so.llmStructuredOutput);
        }
        final KgConfig badSo = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1", KgConfig.LLM_STRUCTURED_OUTPUT, "JSON"));
        assertFalse(badSo.valid());
        assertTrue(badSo.problems().toString(), badSo.problems().toString().contains(KgConfig.LLM_STRUCTURED_OUTPUT));
        assertEquals("auto", badSo.llmStructuredOutput);
        final Map<String, String> s = KgTestSupport.enabled(KgConfig.COLLECTIONS, "c1", KgConfig.LLM_COLLECTIONS, "c1,c9");
        assertEquals("[\"c9\"]", KgTestSupport.config(s).toJson().getJSONArray("llmIgnoredCollections").toString());
    }
}
