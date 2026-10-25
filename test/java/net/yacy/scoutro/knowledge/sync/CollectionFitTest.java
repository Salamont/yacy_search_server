package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.ai.PromptGuard;
import net.yacy.cora.federate.solr.instance.EmbeddedInstance;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.store.KgStore;

/**
 * The collection fit of a home page in the LLM tier's own request (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md, "Home page
 * reading"), with a model stand-in: only the first request of a home page carries the question; every other request, the
 * system prompt, the schema, the prompt hash and the cache keys are as before; the verdict is validated apart (per assigned
 * collection, the quote found in the text, else uncertain) and never becomes a statement; a cache hit keeps it; the
 * question stays within its budget, a longer one is not asked (uncertain); a failing sink never stops the extraction; with a schema sent the question is not asked.
 */
public class CollectionFitTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String BAU = "bauteamcheck-web", STACK = "stackfinder-web";
    /**
     * The prompt hash of the release (Scoutro 0.11.5, main e45d21d; 0.13.0: the definition of a facility, the products' tree and
     * {@code LlmExtractor.VERSION} 5): the question changes none of it.
     */
    private static final String PROMPT_HASH_0115 = "140eb876497bc9f9";
    private static final String PROMPT_AND_SCHEMA_0115 = "8a09718cfd968614166037f745aaac71ae344e4381cc79fb47cdbdc7b26cce0d";
    private static final String HOME = "Drolshagens Feinkost und Confiserie: Pralinen, Trüffel und Geschenkkörbe aus eigener Manufaktur.";

    private EmbeddedInstance solr;
    private KgPaths paths;
    private KgConfig cfg;
    private KgStore store;
    private DirtySet dirty;
    private SyncService sync;
    private LlmService llm;
    private final AtomicLong clock = new AtomicLong(System.currentTimeMillis());
    private final Model model = new Model();
    private final Sink sink = new Sink();

    /** The model: answers the extraction, and the field the question asks for as the test says. */
    static final class Model implements LlmClient {
        final List<String> users = new CopyOnWriteArrayList<>();
        final List<String> systems = new CopyOnWriteArrayList<>();
        volatile Function<String, String> fit = user -> "\"collection_fit\":{\"verdicts\":{\"" + BAU + "\":\"mismatch\"},\"best\":\"none\","
                + "\"quote\":\"Feinkost und Confiserie\"}";
        /** The field on a request without the question (the validator refuses it as before). */
        volatile boolean fieldEverywhere;

        @Override
        public String model() {
            return "TEST/fit";
        }

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis) {
            this.users.add(user);
            this.systems.add(system);
            final String extraction = "\"entities\":[],\"claims\":[],\"values\":[]";
            final boolean asked = user.contains(CollectionFit.MARKER);
            final String field = asked || this.fieldEverywhere ? this.fit.apply(user) : null;
            return "{" + extraction + (field == null ? "" : "," + field) + "}";
        }

        @Override
        public JSONObject usage() {
            return new JSONObject();
        }

        @Override
        public JSONObject lastUsage() {
            return net.yacy.scoutro.knowledge.KgJson.obj("promptTokens", 900, "answerTokens", 40);
        }

        long asked() {
            return this.users.stream().filter(u -> u.contains(CollectionFit.MARKER)).count();
        }
    }

    /** The sink: keeps the verdicts; may fail. */
    static final class Sink implements CollectionFit.Sink {
        final List<Object[]> verdicts = new CopyOnWriteArrayList<>();
        final AtomicBoolean fail = new AtomicBoolean();
        volatile boolean needed = true;

        @Override
        public boolean needed(final String domain, final String collection, final String contentHash, final String criteria) {
            return this.needed;
        }

        @Override
        public void verdict(final String domain, final String docId, final String contentHash, final String model, final String criteria,
                final CollectionFit.Verdict verdict) {
            if (this.fail.get()) throw new IllegalStateException("sink down");
            this.verdicts.add(new Object[] {domain, docId, contentHash, model, criteria, verdict});
        }

        CollectionFit.Verdict last() {
            return (CollectionFit.Verdict) this.verdicts.get(this.verdicts.size() - 1)[5];
        }
    }

    @Before
    public void open() throws Exception {
        JsonLdCapture.clear();
        this.solr = new EmbeddedInstance(new File("defaults/solr"), this.tmp.newFolder("index"), "collection1", new String[] {"collection1", "webgraph"});
        this.paths = new KgPaths(this.tmp.newFolder("data"));
        CollectionFit.install(() -> new File("tools/scoutro/discovery/profiles.json"), this.sink);
        configure("none");
    }

    private void configure(final String structuredOutput) throws Exception {
        configure(KgConfig.COLLECTIONS, BAU + "," + STACK, KgConfig.LLM_COLLECTIONS, BAU + "," + STACK, KgConfig.LLM_COLLECTION_FIT, "true",
                KgConfig.LLM_STRUCTURED_OUTPUT, structuredOutput, KgConfig.VOCAB_PREFIX + BAU, "construction", KgConfig.VOCAB_PREFIX + STACK,
                "software", KgConfig.LLM_MAX_DOCS_PER_HOST, "10");
    }

    private void configure(final String... settings) throws Exception {
        if (this.store != null) {
            Capture.deactivate(this.dirty);
            this.store.close();
        }
        this.cfg = KgTestSupport.config(KgTestSupport.enabled(settings));
        assertTrue(this.cfg.problems().toString(), this.cfg.valid());
        final StorageGuard guard = new StorageGuard(this.cfg, this.paths, new KgTestSupport.Probe(), System::currentTimeMillis);
        this.store = KgStore.open(this.paths, this.cfg, guard, KgStore.SQLITE, System::currentTimeMillis);
        this.dirty = new DirtySet(1000);
        Capture.activate(this.dirty);
        final SolrSource source = new EmbeddedSolrSource(() -> this.solr.getDefaultServer());
        final Gates gates = new Gates(this.cfg, Gates.IDLE);
        this.sync = new SyncService(this.cfg, this.store, this.dirty, source, gates, this.clock::get, false);
        this.llm = new LlmService(this.cfg, this.store, source, gates, this.model, this.clock::get);
    }

    @After
    public void close() {
        CollectionFit.install(null, null);
        if (this.dirty != null) Capture.deactivate(this.dirty);
        JsonLdCapture.clear();
        if (this.store != null) this.store.close();
        if (this.solr != null) this.solr.close();
    }

    private void add(final String id, final String url, final List<String> collections, final String text) throws Exception {
        final SolrInputDocument d = new SolrInputDocument();
        d.setField("id", id);
        d.setField("sku", url);
        d.setField("host_s", java.net.URI.create(url).getHost());
        d.setField("host_id_s", id.substring(6));
        d.setField("httpstatus_i", 200);
        d.setField("collection_sxt", collections);
        d.setField("language_s", "de");
        d.setField("load_date_dt", new Date(this.clock.get()));
        d.setField("title", List.of("Drolshagens"));
        d.setField("exact_signature_l", (long) text.hashCode());
        d.setField("text_t", text);
        this.solr.getDefaultServer().add(d);
        this.solr.getDefaultServer().commit();
    }

    private void settle() {
        for (int i = 0; i < 2000; i++) {
            final boolean more = this.sync.step();
            if (!more && this.dirty.size() == 0 && count("SELECT count(*) FROM kg_work") == 0L && !this.sync.reconciler().pending()) break;
            if (!more) this.clock.addAndGet(3000L);
        }
        for (int i = 0; i < 500; i++) {
            final boolean more = this.llm.step();
            if (!more && count("SELECT count(*) FROM kg_llm_work") == 0L && count("SELECT count(*) FROM kg_doc WHERE state = 1 AND llm_status IS NULL") == 0L) {
                return;
            }
            if (!more) this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
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

    private JSONObject fitStatus() throws Exception {
        return this.llm.status().getJSONObject("processed").getJSONObject("collectionFit");
    }

    private static String sha(final String s) throws Exception {
        final byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        final StringBuilder sb = new StringBuilder();
        for (final byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Test
    public void onlyTheHomePagesFirstRequestCarriesTheQuestionAndNothingElseChanges() throws Exception {
        assertEquals("the prompt hash of 0.11.5", PROMPT_HASH_0115, LlmExtractor.PROMPT_HASH);
        assertEquals("system prompt and schema of 0.11.5", PROMPT_AND_SCHEMA_0115, sha(LlmExtractor.SYSTEM_PROMPT + "\u0000" + LlmExtractor.SCHEMA));
        assertEquals("5", LlmExtractor.VERSION);
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME);
        add("AAAAAAimpr02", "https://drolshagens-muster.de/impressum", List.of(BAU), "Impressum. Drolshagens Feinkost GmbH, Musterweg 1.");
        settle();
        assertEquals("one question for the domain: " + this.model.users, 1, this.model.asked());
        final String asked = this.model.users.stream().filter(u -> u.contains(CollectionFit.MARKER)).findFirst().get();
        assertTrue(asked, asked.contains("Assigned: " + BAU + " (Professionelle Bau-"));
        assertTrue("another profile by its label as far as it fits: " + asked, asked.contains("\nOthers: checkthecoach-web: "));
        // the answer's shape as valid JSON with the real id (version 2; version 1 showed {id:...} and got the key "id" back)
        assertTrue(asked, asked.contains("Use exactly: \"collection_fit\":{\"verdicts\":{\"" + BAU + "\":\"<verdict>\"},\"best\":\"<id or none>\""));
        assertFalse("no placeholder key id", asked.contains("{id:"));
        assertTrue("the question is outside the DATA block", asked.indexOf(CollectionFit.MARKER) < asked.indexOf("page text:"));
        for (final String s : this.model.systems) assertEquals(LlmExtractor.SYSTEM_PROMPT, s);
        // the verdict: valid, a mismatch, handed over with the page's content hash and the criteria version
        assertEquals(1, this.sink.verdicts.size());
        final Object[] v = this.sink.verdicts.get(0);
        assertEquals("drolshagens-muster.de", v[0]);
        assertEquals("AAAAAAhome01", v[1]);
        assertNotNull(v[2]);
        assertEquals("TEST/fit", v[3]);
        assertEquals("the profiles' criteria and the question's version", CollectionFit.profiles().criteria + "/q2", v[4]);
        assertEquals(CollectionFit.MISMATCH, this.sink.last().verdicts.get(BAU));
        assertEquals(CollectionFit.VALID, this.sink.last().status);
        // never a statement: nothing of the verdict in the graph, the extraction published as always
        assertEquals(0L, count("SELECT count(*) FROM kg_statement WHERE obj_val LIKE '%mismatch%' OR obj_val LIKE '%Confiserie%'"));
        assertEquals("the home page is done", 1L, count("SELECT count(*) FROM kg_doc WHERE doc_id = 'AAAAAAhome01' AND llm_status = "
                + LlmQueue.STATUS_DONE));
        final JSONObject f = fitStatus();
        assertEquals(1, f.getLong("asked"));
        assertEquals(1, f.getJSONObject("status").getLong("valid"));
        assertEquals(1, f.getJSONObject("verdicts").getLong("mismatch"));
        assertEquals(1, f.getLong("delivered"));
        assertEquals(1, f.getJSONObject("tokensWithQuestion").getLong("answers"));
        assertEquals(900, f.getJSONObject("tokensWithQuestion").getLong("promptAverage"));
        // a cache hit keeps the verdict: the same page read again asks the model no more
        final int calls = this.model.users.size();
        this.store.write(StorageGuard.WriteClass.MAINTENANCE, 0L, tx -> {
            LlmQueue.reexamine(tx, LlmQueue.STATUS_DONE);
            LlmQueue.resetCursors(tx);
            return null;
        });
        settle();
        assertEquals("answered from the cache", calls, this.model.users.size());
        assertEquals(2, this.sink.verdicts.size());
        assertEquals(CollectionFit.MISMATCH, this.sink.last().verdicts.get(BAU));
        assertEquals("Feinkost und Confiserie", this.sink.last().quote);
        // a page with a reading of this content asks nothing
        this.sink.needed = false;
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME + " Neu: Weine.");
        settle();
        assertEquals(1, this.model.asked());
    }

    @Test
    public void everyOtherRequestIsByteForByteAsBefore() {
        final LlmExtractor.Chunk chunk = new LlmExtractor.Chunk(HOME, 0);
        final PromptGuard g1 = new PromptGuard(), g2 = new PromptGuard();
        final String before = LlmExtractor.userPrompt(g1, chunk, "Drolshagens", "drolshagens-muster.de", "de", List.of(), List.of("store"));
        final String now = LlmExtractor.userPrompt(g2, chunk, "Drolshagens", "drolshagens-muster.de", "de", List.of(), List.of("store"), null);
        assertEquals(before.replace(g1.dataBegin(), "B").replace(g1.dataEnd(), "E"), now.replace(g2.dataBegin(), "B").replace(g2.dataEnd(), "E"));
        assertArrayEquals(LlmExtractor.cacheKey("h", "m", chunk, "t", "d", "de", List.of(), List.of("store")),
                LlmExtractor.cacheKey("h", "m", chunk, "t", "d", "de", List.of(), List.of("store"), null));
        assertFalse(java.util.Arrays.equals(LlmExtractor.cacheKey("h", "m", chunk, "t", "d", "de", List.of(), List.of("store")),
                LlmExtractor.cacheKey("h", "m", chunk, "t", "d", "de", List.of(), List.of("store"), "a question")));
        final RequestBudget b = new RequestBudget(4096, 1200, LlmExtractor.SYSTEM_PROMPT, LlmExtractor.SCHEMA);
        assertEquals(b.fixedTokens, b.withExtra(0).fixedTokens);
        assertEquals(b.prompt(before), new RequestBudget(4096, 1200, LlmExtractor.SYSTEM_PROMPT, LlmExtractor.SCHEMA).prompt(before));
    }

    @Test
    public void theQuestionStaysWithinItsBudget() throws Exception {
        // K3: the real profiles, the longest question (three assigned, every other profile): at most 500 characters, 150 tokens
        final CollectionFit.Profiles p = CollectionFit.read(new File("tools/scoutro/discovery/profiles.json"));
        assertNotNull(p);
        final List<String> all = new ArrayList<>(p.byCollection.keySet());
        for (int n = 1; n <= 3; n++) {
            final List<String> assigned = all.subList(0, n);
            final List<String> others = all.subList(n, all.size());
            final CollectionFit.Question q = CollectionFit.question(p, assigned, others, Map.of(STACK, "Software und IT", BAU, "Bauen und Planen",
                    "edelsenior-web", "Pflege und Betreuung", "checkthecoach-web", "Coaching"));
            assertNotNull(q);
            assertTrue(q.text.length() + " characters: " + q.text, q.text.length() <= CollectionFit.MAX_CHARS);
            assertTrue(RequestBudget.extraTokens(q.text.length()) + " tokens", RequestBudget.extraTokens(q.text.length()) <= 150);
            assertEquals(assigned, q.assigned);
        }
        // too long profile texts are cut and the others dropped first; assigned ones that do not fit alone: no question
        final File big = this.tmp.newFile("profiles.json");
        final StringBuilder json = new StringBuilder("{\"criteria_version\":\"x\",\"profiles\":{");
        for (int i = 0; i < 5; i++) {
            json.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"collection\":\"").append("c".repeat(60)).append(i)
                    .append("\",\"topic\":\"").append("Thema ".repeat(60)).append("\"}");
        }
        json.append("}}");
        java.nio.file.Files.write(big.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        final CollectionFit.Profiles bp = CollectionFit.read(big);
        final List<String> ids = new ArrayList<>(bp.byCollection.keySet());
        final CollectionFit.Question one = CollectionFit.question(bp, ids.subList(0, 1), ids.subList(1, 5), Map.of());
        assertTrue(one.text.length() <= CollectionFit.MAX_CHARS);
        assertTrue("the topic is cut: " + one.text, !one.text.contains("Thema ".repeat(20)));
        final CollectionFit.Question three = CollectionFit.question(bp, ids.subList(0, 3), ids.subList(3, 5), Map.of());
        assertFalse("three long assigned ones do not fit", three.asked());
        assertNull(three.text);
        final CollectionFit.Verdict skipped = CollectionFit.skipped(three);
        assertEquals(CollectionFit.BUDGET, skipped.status);
        assertEquals(3, skipped.verdicts.size());
        assertFalse(skipped.decided());
        assertEquals(CollectionFit.NONE, skipped.best);
    }

    @Test
    public void aQuestionTooLongIsNotAskedAndItsHomePageIsUncertain() throws Exception {
        // K3: three assigned collections with the longest ids and topics do not fit 500 characters: the home page is read
        // without the question, byte for byte as before, and its verdict is uncertain (fit_budget)
        final List<String> ids = List.of("c".repeat(63) + "1", "c".repeat(63) + "2", "c".repeat(63) + "3");
        final File file = this.tmp.newFile("long-profiles.json");
        final StringBuilder json = new StringBuilder("{\"criteria_version\":\"long\",\"profiles\":{");
        for (int i = 0; i < ids.size(); i++) {
            json.append(i == 0 ? "" : ",").append("\"p").append(i).append("\":{\"collection\":\"").append(ids.get(i))
                    .append("\",\"topic\":\"").append("Thema ".repeat(30)).append("\"}");
        }
        java.nio.file.Files.write(file.toPath(), json.append("}}").toString().getBytes(StandardCharsets.UTF_8));
        CollectionFit.install(() -> file, this.sink);
        final String all = String.join(",", ids);
        configure(KgConfig.COLLECTIONS, all, KgConfig.LLM_COLLECTIONS, all, KgConfig.LLM_COLLECTION_FIT, "true", KgConfig.LLM_STRUCTURED_OUTPUT,
                "none", KgConfig.VOCAB_PREFIX + ids.get(0), "construction", KgConfig.VOCAB_PREFIX + ids.get(1), "construction",
                KgConfig.VOCAB_PREFIX + ids.get(2), "construction", KgConfig.LLM_MAX_DOCS_PER_HOST, "10");
        add("BBBBBBhome01", "https://lang-muster.de/", ids, HOME);
        settle();
        assertTrue("the home page was read", this.model.users.size() > 0);
        assertEquals("no question", 0, this.model.asked());
        final JSONObject f = fitStatus();
        assertEquals(1, f.getLong("budgetSkipped"));
        assertEquals(0, f.getLong("asked"));
        assertEquals(1, this.sink.verdicts.size());
        final CollectionFit.Verdict v = this.sink.last();
        assertEquals(CollectionFit.BUDGET, v.status);
        assertEquals(ids, new ArrayList<>(v.verdicts.keySet()));
        assertFalse(v.decided());
        assertEquals("long/q" + CollectionFit.QUESTION_VERSION, this.sink.verdicts.get(0)[4]);
        assertEquals("the page is done", 1L, count("SELECT count(*) FROM kg_doc WHERE doc_id = 'BBBBBBhome01' AND llm_status = "
                + LlmQueue.STATUS_DONE));
    }

    @Test
    public void theAnswerShapeOfTheQuestionIsValidAndTheOldMistakeIsNamed() throws Exception {
        // the format line of the question, filled in as asked, is a valid field for every number of assigned collections;
        // the answer 0.11.6 got back in production (a key "id") stays invalid, now with its reason (unknown_key)
        final CollectionFit.Profiles p = CollectionFit.read(new File("tools/scoutro/discovery/profiles.json"));
        final List<String> all = new ArrayList<>(p.byCollection.keySet());
        final String text = "Wir sind Feinkost und Confiserie aus eigener Manufaktur.";
        for (int n = 1; n <= 3; n++) {
            final CollectionFit.Question q = CollectionFit.question(p, all.subList(0, n), all.subList(n, all.size()), Map.of());
            assertTrue(q.asked());
            assertTrue(q.text.length() + " characters", q.text.length() <= CollectionFit.MAX_CHARS);
            final String shape = q.text.substring(q.text.indexOf("{\"verdicts\""), q.text.indexOf("}, each ") + 1);
            final JSONObject filled = new JSONObject(shape.replace("<verdict>", "mismatch").replace("<id or none>", "none")
                    .replace("<verbatim page text, max 200 chars>", "Feinkost und Confiserie"));
            final CollectionFit.Verdict v = CollectionFit.check(filled, q, text);
            assertEquals(CollectionFit.VALID, v.status);
            assertEquals(n, v.verdicts.size());
            for (final String c : all.subList(0, n)) assertEquals(CollectionFit.MISMATCH, v.verdicts.get(c));
            final CollectionFit.Verdict old = CollectionFit.check(new JSONObject("{\"verdicts\":{\"id\":\"mismatch\"},\"best\":\"none\","
                    + "\"quote\":\"Feinkost und Confiserie\"}"), q, text);
            assertEquals(CollectionFit.INVALID, old.status);
            assertEquals(CollectionFit.UNKNOWN_KEY, old.reason);
        }
        final CollectionFit.Question one = CollectionFit.question(p, all.subList(0, 1), List.of(), Map.of());
        assertEquals(CollectionFit.NOT_OBJECT, CollectionFit.check("mismatch", one, text).reason);
        assertEquals(CollectionFit.VERDICTS_NOT_OBJECT, CollectionFit.check(new JSONObject("{\"verdicts\":\"mismatch\"}"), one, text).reason);
        assertNull(CollectionFit.check(null, one, text).reason);
        // the reason is counted in the status, never the answer itself
        this.model.fit = user -> "\"collection_fit\":{\"verdicts\":{\"id\":\"mismatch\"},\"best\":\"none\",\"quote\":\"Feinkost und Confiserie\"}";
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME);
        settle();
        final JSONObject f = fitStatus();
        assertEquals(1, f.getJSONObject("status").getLong(CollectionFit.INVALID));
        assertEquals(1, f.getJSONObject("invalidReasons").getLong(CollectionFit.UNKNOWN_KEY));
        assertEquals(CollectionFit.QUESTION_VERSION, f.getString("questionVersion"));
    }

    @Test
    public void aMissingOrUnquotedFieldIsUncertainAndTheExtractionStays() throws Exception {
        // K2: the model leaves the field out
        this.model.fit = user -> null;
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME);
        settle();
        assertEquals(CollectionFit.MISSING, this.sink.last().status);
        assertEquals(CollectionFit.UNCERTAIN, this.sink.last().verdicts.get(BAU));
        assertEquals(1, fitStatus().getJSONObject("status").getLong(CollectionFit.MISSING));
        assertEquals(0, this.llm.status().getJSONObject("processed").getLong("answersRefused"));
        // K7: a match without a quote found in the text is uncertain as a mismatch would be; best none
        this.model.fit = user -> "\"collection_fit\":{\"verdicts\":{\"" + BAU + "\":\"match\"},\"best\":\"" + STACK + "\",\"quote\":\"Wir bauen Häuser\"}";
        add("BBBBBBhome02", "https://zweite-muster.de/", List.of(BAU), HOME);
        settle();
        assertEquals(CollectionFit.UNQUOTED, this.sink.last().status);
        assertEquals(CollectionFit.UNCERTAIN, this.sink.last().verdicts.get(BAU));
        assertEquals(CollectionFit.NONE, this.sink.last().best);
        assertFalse(this.sink.last().decided());
        // the field on a request that did not ask: refused as before (unknown_field)
        this.model.fieldEverywhere = true;
        this.sink.needed = false;
        add("CCCCCChome03", "https://dritte-muster.de/", List.of(BAU), HOME);
        settle();
        assertEquals(1, this.llm.status().getJSONObject("processed").getJSONObject("refusedBy").getLong("unknown_field"));
    }

    @Test
    public void aDomainInTwoCollectionsGetsAVerdictPerCollection() throws Exception {
        // K5
        this.model.fit = user -> "\"collection_fit\":{\"verdicts\":{\"" + BAU + "\":\"mismatch\",\"" + STACK + "\":\"match\"},\"best\":\"" + STACK + "\","
                + "\"quote\":\"Feinkost und Confiserie\"}";
        add("AAAAAAhome01", "https://misch-muster.de/", List.of(BAU, STACK), HOME);
        settle();
        final String asked = this.model.users.stream().filter(u -> u.contains(CollectionFit.MARKER)).findFirst().get();
        assertTrue(asked, asked.contains("Assigned: " + BAU + " (") && asked.contains("; " + STACK + " ("));
        assertTrue(asked.length() > 0);
        CollectionFit.Verdict v = this.sink.last();
        assertEquals(CollectionFit.MISMATCH, v.verdicts.get(BAU));
        assertEquals(CollectionFit.MATCH, v.verdicts.get(STACK));
        assertEquals(STACK, v.best);
        // the check alone: a missing collection is uncertain, an id not assigned makes the field invalid
        final CollectionFit.Question q = CollectionFit.question(CollectionFit.profiles(), List.of(BAU, STACK), List.of(), Map.of());
        v = CollectionFit.check(new JSONObject("{\"verdicts\":{\"" + BAU + "\":\"mismatch\"},\"best\":\"none\",\"quote\":\"Feinkost und Confiserie\"}"), q, HOME);
        assertEquals(CollectionFit.UNCERTAIN, v.verdicts.get(STACK));
        assertEquals(CollectionFit.MISMATCH, v.verdicts.get(BAU));
        v = CollectionFit.check(new JSONObject("{\"verdicts\":{\"edelsenior-web\":\"match\"},\"best\":\"none\",\"quote\":\"Feinkost und Confiserie\"}"), q, HOME);
        assertEquals(CollectionFit.INVALID, v.status);
        assertFalse(v.decided());
        v = CollectionFit.check(new JSONObject("{\"verdicts\":{\"" + BAU + "\":\"maybe\"},\"best\":\"elsewhere\",\"quote\":\"Feinkost und Confiserie\"}"), q, HOME);
        assertEquals(CollectionFit.UNCERTAIN, v.verdicts.get(BAU));
        assertEquals(CollectionFit.NONE, v.best);
    }

    @Test
    public void aFailingSinkNeverStopsTheExtraction() throws Exception {
        this.sink.fail.set(true);
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME);
        settle();
        assertEquals(0, this.sink.verdicts.size());
        assertEquals(1, fitStatus().getLong("sinkErrors"));
        assertEquals("the page is done", 0L, count("SELECT count(*) FROM kg_doc WHERE llm_status IS NULL AND state = 1"));
    }

    @Test
    public void withASchemaSentTheQuestionIsNotAsked() throws Exception {
        configure("json_schema");
        assertFalse(this.cfg.llmCollectionFit);
        add("AAAAAAhome01", "https://drolshagens-muster.de/", List.of(BAU), HOME);
        settle();
        assertEquals(0, this.model.asked());
        assertTrue(this.model.users.size() > 0);
        configure("auto");
        assertFalse(this.cfg.llmCollectionFit);
        configure("json_object");
        assertTrue(this.cfg.llmCollectionFit);
    }

    @Test
    public void theAnswerIsSplitWithoutTouchingTheRest() throws IOException {
        final Object[] s = LlmExtractor.splitField("```json\n{\"entities\":[],\"collection_fit\":{\"verdicts\":{}}}\n```", CollectionFit.FIELD);
        assertEquals("{\"entities\":[]}", s[0]);
        assertTrue(s[1] instanceof JSONObject);
        final Object[] none = LlmExtractor.splitField("prose", CollectionFit.FIELD);
        assertEquals("prose", none[0]);
        assertNull(none[1]);
    }
}
