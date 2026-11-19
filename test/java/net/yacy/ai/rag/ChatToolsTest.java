/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import net.yacy.ai.ToolProvider;
import net.yacy.ai.ToolTestSettings;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgPaths;
import net.yacy.scoutro.knowledge.KgTestSupport;
import net.yacy.scoutro.knowledge.budget.StorageGuard;
import net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.publish.Aggregates;
import net.yacy.scoutro.knowledge.publish.Publisher;
import net.yacy.scoutro.knowledge.publish.Terms;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;
import net.yacy.scoutro.knowledge.store.KgStore;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The tools of a chat request (search, kg_search, kg_entity, kg_evidence) on synthetic data: a real graph store filled
 * through the extractors and the publisher, and a search over invented pages. Two collections, alpha-web and beta-web;
 * a chat of alpha-web never gets a page, entity, relation or evidence row of beta-web, whatever a tool call asks for.
 * No model is involved: the tool calls are made as {@link net.yacy.ai.ToolCallProtocol} makes them for a model.
 */
public class ChatToolsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final long now = java.time.LocalDate.of(2027, 1, 15).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli();
    private KgStore store;
    private KgReader reader;
    private Publisher publisher;
    private long version = 1000L;
    private final List<RagCandidate> pages = new ArrayList<>();
    private final List<String> searched = new ArrayList<>();

    static final String TAPIR = "{\"@type\":\"Organization\",\"name\":\"Tapir Consulting AG\",\"url\":\"https://www.tapir-consulting.example/\"}";
    static final String OKAPI = "{\"@type\":\"Organization\",\"name\":\"Okapi Systems GmbH\",\"url\":\"https://www.okapi-systems.example/\"}";

    private static String posting(final String title, final String description, final String organization) {
        return "{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"description\":\"" + description + "\",\"employmentType\":\"FULL_TIME\","
                + "\"hiringOrganization\":" + organization + "}";
    }

    /** configured subject groups of the job search (scoutro.chat.jobs.subjects), invented for the tests */
    static final String SUBJECTS = "ML: Machine Learning, maschinelles Lernen; Robotik: Robotics";

    @Before
    public void open() throws Exception {
        final KgConfig cfg = KgTestSupport.config(KgTestSupport.enabled(KgConfig.COLLECTIONS, "alpha-web,beta-web",
                KgConfig.JOBS_COLLECTIONS, "alpha-web,beta-web", KgConfig.JOBS_DISPLAY_COLLECTIONS, "alpha-web,beta-web"));
        final KgPaths paths = new KgPaths(this.tmp.newFolder());
        this.store = KgStore.open(paths, cfg, new StorageGuard(cfg, paths, new KgTestSupport.Probe(), System::currentTimeMillis), KgStore.SQLITE,
                System::currentTimeMillis);
        final Terms terms = new Terms();
        this.store.write(WriteClass.SYSTEM, 0, tx -> {
            terms.seed(tx);
            return null;
        });
        this.publisher = new Publisher(cfg, terms);
        this.reader = new KgReader(this.store, cfg, () -> this.now);
        ToolTestSettings.use((key, dflt) -> key.equals(JobSearch.SUBJECTS_KEY) ? SUBJECTS : key.endsWith(".enabled") ? "true" : dflt);

        // alpha-web: a consultancy with two job postings and its own statement about Odoo
        page("ALPHAAhost01", "https://www.tapir-consulting.example/", "alpha-web", "Tapir Consulting AG", TAPIR,
                "Tapir Consulting AG. Wir nutzen Odoo intern. Wir beraten den Mittelstand zu Prozessen.");
        page("ALPHABhost01", "https://www.tapir-consulting.example/karriere/java-senior-berater-finance", "alpha-web",
                "Java Senior Berater (m/w/d) Finance",
                posting("Java Senior Berater (m/w/d) Finance", "Sie führen Java-Finance-Projekte bei unseren Kunden.", TAPIR),
                "Java Senior Berater (m/w/d) Finance. Sie führen Java-Finance-Projekte bei unseren Kunden. Stellenangebot in Vollzeit.");
        page("ALPHAChost01", "https://www.tapir-consulting.example/karriere/ml-entwickler-java", "alpha-web",
                "ML-Entwickler (m/w/d) Java Prozessrobotik",
                posting("ML-Entwickler (m/w/d) Java Prozessrobotik", "Sie entwickeln ML-Modelle für die Robotik von Java-Prozessen.", TAPIR),
                "ML-Entwickler (m/w/d) Java Prozessrobotik. Sie entwickeln ML-Modelle für die Robotik von Java-Prozessen. Stellenangebot.");
        page("ALPHADhost01", "https://www.tapir-consulting.example/leistungen", "alpha-web", "Leistungen der Tapir Consulting", TAPIR,
                "Unsere Leistungen: Geschäftsfeldentwicklung, Prozessrobotik und Beratung für Beratungsunternehmen.");
        // a posting of one subject only, with dates: its end date has passed
        page("ALPHAEhost01", "https://www.tapir-consulting.example/karriere/werkstudent-robotiktechnik", "alpha-web",
                "Werkstudent Robotiktechnik (m/w/d)",
                "{\"@type\":\"JobPosting\",\"title\":\"Werkstudent Robotiktechnik (m/w/d)\",\"description\":\"Sie unterstützen "
                        + "unsere Steuerungstechnik.\",\"datePosted\":\"2026-01-05\",\"validThrough\":\"2026-02-28\",\"hiringOrganization\":" + TAPIR + "}",
                "Werkstudent Robotiktechnik (m/w/d). Sie unterstützen unsere Steuerungstechnik.");
        // beta-web: another company, never visible to a chat of alpha-web
        page("BETAAAhost02", "https://www.okapi-systems.example/", "beta-web", "Okapi Systems GmbH", OKAPI,
                "Okapi Systems GmbH. Wir nutzen Odoo intern. BETAGEHEIM Standort Nordstadt.");
        page("BETABBhost02", "https://www.okapi-systems.example/jobs/java-basis-administrator", "beta-web", "Java Basis Administrator (m/w/d)",
                posting("Java Basis Administrator (m/w/d)", "Sie betreiben unsere Java-Systeme. BETAGEHEIM", OKAPI),
                "Java Basis Administrator (m/w/d). Sie betreiben unsere Java-Systeme. BETAGEHEIM Stellenangebot.");
    }

    @After
    public void close() {
        ToolTestSettings.use(null);
        if (this.store != null) this.store.close();
    }

    /**
     * {@code n} invented postings of one employer in alpha-web, straight through the JSON-LD extractor and the publisher in
     * batches; posting {@code special} shows the subject "Hydraulik" in its field only.
     */
    private void bulkPostings(final int n, final int special) throws Exception {
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(), true, true, List.of("alpha-web"));
        for (int from = 1; from <= n; from += 500) {
            final List<Publisher.Doc> docs = new ArrayList<>();
            final List<Extraction> extractions = new ArrayList<>();
            for (int i = from; i < Math.min(n + 1, from + 500); i++) {
                // 20 postings per employer, each employer on its own host, as in a collection of many companies
                final int e = (i - 1) / 20;
                final String org = "{\"@type\":\"Organization\",\"name\":\"Kranich Anlagenbau " + e + " GmbH\",\"url\":\"https://www.kranich-"
                        + e + ".example/\"}";
                final String title = "Monteur (m/w/d) " + i;
                final String url = "https://www.kranich-" + e + ".example/stelle/" + i;
                final Publisher.Doc d = new Publisher.Doc();
                d.docId = String.format("KR%04dho%04d", i, e);
                d.url = url;
                d.host = "www.kranich-" + e + ".example";
                d.hostId = String.format("ho%04d", e);
                d.language = "de";
                d.state = Aggregates.STATE_ACTIVE;
                d.token = new byte[8];
                d.inputHash = new byte[16];
                d.collections = List.of("alpha-web");
                d.solrVersion = ++this.version;
                d.loadedAt = this.now - 86_400_000L;
                final Extraction ex = new Extraction(300);
                new JsonLdExtractor(200).extract(List.of("{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"description\":\"Montage.\""
                        + (i == special ? ",\"occupationalCategory\":\"Hydraulik\"" : "") + ",\"hiringOrganization\":" + org + "}"), d.url, d.host,
                        "de", ex, ctx);
                docs.add(d);
                extractions.add(ex);
                this.pages.add(new RagCandidate(url, title, "", title + ". Montage.", d.host, List.of("alpha-web"), this.pages.size()));
            }
            this.store.write(WriteClass.GROWTH, 0, tx -> {
                for (int k = 0; k < docs.size(); k++) this.publisher.apply(tx, docs.get(k), -1L, extractions.get(k), this.now);
                return null;
            });
        }
    }

    /** One invented page: in the search corpus and, through the extractors and the publisher, in the graph. */
    private void page(final String id, final String url, final String collection, final String title, final String jsonld, final String text)
            throws Exception {
        final String host = java.net.URI.create(url).getHost();
        this.pages.add(new RagCandidate(url, title, "", text, host, List.of(collection), this.pages.size()));
        final ExtractContext ctx = new ExtractContext(KgVocabularies.get(), Set.of(), true, true, List.of(collection));
        final Publisher.Doc d = new Publisher.Doc();
        d.docId = id;
        d.url = url;
        d.host = host;
        d.hostId = id.substring(6);
        d.language = "de";
        d.state = Aggregates.STATE_ACTIVE;
        d.token = new byte[8];
        d.inputHash = new byte[16];
        d.collections = List.of(collection);
        final Extraction ex = new Extraction(300);
        new JsonLdExtractor(200).extract(List.of(jsonld), d.url, d.host, "de", ex, ctx);
        new RuleExtractor(200, 65536).extract(text, d.url, d.host, "de", ex, ctx, List.of(), List.of());
        final Publisher.Row cur = this.store.read(c -> Publisher.row(c, d.docId));
        d.solrVersion = ++this.version;
        d.loadedAt = this.now - 86_400_000L;
        this.store.write(WriteClass.GROWTH, 0, tx -> this.publisher.apply(tx, d, cur == null ? -1L : cur.generation, ex, this.now));
    }

    /** The scope of a chat request as the servlet builds it: the collection of the session, a search and the graph. */
    private ToolContext chat(final String collection) {
        final ToolContext.Searcher searcher = (concepts, query, c, count) -> {
            this.searched.add(query + " @" + c);
            // like the index: every page with a word of the query, of every collection unless the search filters;
            // this one deliberately does not filter, so the tool's own scope check is what is tested
            final List<RagCandidate> found = new ArrayList<>();
            for (final RagCandidate page : this.pages) {
                if (!page.matched(concepts).isEmpty()) found.add(page);
            }
            return found;
        };
        return new ToolContext(collection, true, searcher, graph(), 16000, "test").withIndex(index());
    }

    /** Pages read through the direct index of the tests: like Solr, every word as a word or the start of one, paged. */
    final List<String> indexQueries = new ArrayList<>();

    ToolContext.Index index() {
        return (words, collection, urls, offset, count) -> {
            this.indexQueries.add(String.join(" ", words) + (urls == null ? "" : " [" + urls.size() + " urls]") + " @" + collection);
            final java.util.Set<String> wanted = urls == null ? null : new java.util.HashSet<>(urls);
            final List<RagCandidate> all = new ArrayList<>();
            for (final RagCandidate page : this.pages) {
                if (collection != null && !page.collections.contains(collection)) continue;
                if (wanted != null && !wanted.contains(page.url)) continue;
                final String text = (page.title + " " + page.description + " " + page.text).toLowerCase(java.util.Locale.ROOT);
                boolean every = true;
                for (final String w : words) {
                    if (!java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(w.toLowerCase(java.util.Locale.ROOT)))
                            .matcher(text).find()) every = false;
                }
                if (every) all.add(page);
            }
            return new ToolContext.IndexPage(new ArrayList<>(all.subList(Math.min(offset, all.size()), Math.min(offset + count, all.size()))),
                    all.size());
        };
    }

    private ToolContext.Graph graph() {
        return new ToolContext.Graph() {
            @Override
            public KgReader reader() {
                return ChatToolsTest.this.reader;
            }

            @Override
            public Viewer viewer(final KgReader r, final String c) throws Exception {
                return r.viewer(c == null ? null : List.of(c));
            }
        };
    }

    private static JSONObject call(final ToolContext context, final String tool, final String arguments) throws Exception {
        return new JSONObject(ToolProvider.executeTool(tool, arguments, context.collection == null, context));
    }

    private static JSONObject first(final JSONObject result, final String list) throws Exception {
        final JSONArray items = result.getJSONArray(list);
        assertTrue(list + " of " + result, items.length() > 0);
        return items.getJSONObject(0);
    }

    private static JSONObject byPredicate(final JSONObject entity, final String list, final String predicate) throws Exception {
        final JSONArray items = entity.getJSONArray(list);
        for (int i = 0; i < items.length(); i++) {
            if (predicate.equals(items.getJSONObject(i).getString("predicate"))) return items.getJSONObject(i);
        }
        throw new AssertionError("no " + predicate + " in " + entity);
    }

    private static RagContext.Source source(final ToolContext context, final String cite) {
        for (final RagContext.Source s : context.sources()) if (("[" + s.id + "]").equals(cite)) return s;
        throw new AssertionError("no source " + cite);
    }

    // ------------------------------------------------------------------ jobs

    @Test
    public void aWeakFirstHitIsFollowedByASearchTheGraphAndItsEvidence() throws Exception {
        final ToolContext chat = chat("alpha-web");
        // the answer starts with the sources of the automatic retrieval: here one page that does not answer a job question
        final List<RagContext.Source> answerSources = new ArrayList<>();
        answerSources.add(new RagContext.Source(1, "Leistungen der Tapir Consulting", "https://www.tapir-consulting.example/leistungen",
                "www.tapir-consulting.example", "alpha-web", null, "Geschäftsfeldentwicklung, Prozessrobotik und Beratung."));
        chat.attach(answerSources);

        // 1. the model searches again with the concrete words of the question
        final JSONObject search = call(chat, "search", "{\"query\":\"Java ML Stellenangebot\"}");
        assertEquals("collection alpha-web", search.getString("scope"));
        assertTrue(search.getBoolean("local"));
        final JSONArray hits = search.getJSONArray("sources");
        assertEquals("the one page with every word: " + search, 1, hits.length());
        assertEquals("all", search.getString("stage"));
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", hits.getJSONObject(0).getString("url"));
        assertEquals("alpha-web", hits.getJSONObject(0).getString("collection"));
        assertEquals("numbered after the sources the answer already has", "[2]", hits.getJSONObject(0).getString("cite"));
        // and once more with other words: a further page gets the next number, a known page keeps its number
        final JSONArray more = call(chat, "search", "{\"query\":\"Java Stellenangebot\"}").getJSONArray("sources");
        final List<String> cites = new ArrayList<>();
        for (int i = 0; i < more.length(); i++) {
            assertEquals("alpha-web", more.getJSONObject(i).getString("collection"));
            assertFalse(more.getJSONObject(i).toString().contains("okapi-systems"));
            cites.add(more.getJSONObject(i).getString("cite") + " " + more.getJSONObject(i).getString("url"));
        }
        assertEquals(cites.toString(), 2, cites.size());
        assertTrue(cites.toString(), cites.contains("[2] https://www.tapir-consulting.example/karriere/ml-entwickler-java"));
        assertTrue(cites.toString(), cites.contains("[3] https://www.tapir-consulting.example/karriere/java-senior-berater-finance"));

        // 2. the graph: job postings by words of their title, with their employer
        final JSONObject jobs = call(chat, "kg_search", "{\"query\":\"Java\",\"type\":\"job\"}");
        assertEquals(2L, jobs.getLong("total"));
        final List<String> titles = new ArrayList<>();
        String aiJob = null;
        for (int i = 0; i < jobs.getJSONArray("entities").length(); i++) {
            final JSONObject job = jobs.getJSONArray("entities").getJSONObject(i);
            titles.add(job.getString("name"));
            assertEquals("Tapir Consulting AG", job.getJSONArray("employer").getJSONObject(0).getString("name"));
            if (job.getString("name").startsWith("ML-Entwickler")) aiJob = job.getString("entity");
        }
        assertTrue(titles.toString(), titles.contains("Java Senior Berater (m/w/d) Finance"));
        assertNotNull(titles.toString(), aiJob);

        // 3. the job's relations, each with the page it was read from as a numbered source
        final JSONObject job = call(chat, "kg_entity", "{\"entity\":\"" + aiJob + "\"}");
        final JSONObject employer = byPredicate(job, "facts", "hiring_organization");
        assertEquals("Tapir Consulting AG", employer.getJSONObject("object").getString("name"));
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", employer.getString("url"));
        final RagContext.Source page = source(chat, employer.getString("cite"));
        assertEquals(RagContext.Source.KIND_GRAPH, page.kind);
        assertEquals("alpha-web", page.collection);

        // 4. the evidence of that relation: page, time and how it was read
        final JSONObject evidence = call(chat, "kg_evidence", "{\"statement\":\"" + employer.getString("statement") + "\"}");
        final JSONObject row = first(evidence, "evidence");
        assertEquals(employer.getString("cite"), row.getString("cite"));
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", row.getString("url"));
        assertEquals("[\"alpha-web\"]", row.getJSONArray("collections").toString());
        assertTrue(evidence.getString("fact").contains("Tapir Consulting AG"));

        // the answer's sources now hold the tools' pages, and the citation check knows what each number was given for
        assertSame(answerSources, chat);
        final String cite = employer.getString("cite");
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG sucht einen ML-Entwickler für Java Prozessrobotik " + cite
                + ". Das Gehalt liegt bei 95.000 Euro im Jahr " + cite + ". Ein Standort München ist belegt [1].", chat.sources());
        assertTrue(check.getJSONArray("invalid").length() == 0);
        final JSONObject verdicts = verdicts(check);
        assertEquals("the salary is not on the cited page, so its worst verdict counts: " + check, RagCitations.UNSUPPORTED, verdicts.getString(cite));
        assertEquals("a source that does not speak about a site in München: " + check, RagCitations.UNSUPPORTED, verdicts.getString("[1]"));
        final JSONObject fine = RagCitations.check("Die Tapir Consulting AG sucht einen ML-Entwickler für Java Prozessrobotik " + cite + ".",
                chat.sources());
        assertEquals(fine.toString(), RagCitations.SUPPORTED, verdicts(fine).getString(cite));
        assertEquals(0, fine.getJSONArray("unsupported").length());
    }

    private static void assertSame(final List<RagContext.Source> answerSources, final ToolContext chat) {
        assertEquals("one list for the reader and the check", answerSources.size(), chat.sources().size());
        assertTrue(chat.added() > 0);
        final Set<Integer> ids = new java.util.TreeSet<>();
        for (final RagContext.Source s : answerSources) assertTrue("unique number " + s.id, ids.add(s.id));
    }

    /** "[n]" to the worst verdict of that source. */
    private static JSONObject verdicts(final JSONObject check) throws Exception {
        final JSONObject out = new JSONObject();
        for (int i = 0; i < check.getJSONArray("support").length(); i++) {
            final JSONObject s = check.getJSONArray("support").getJSONObject(i);
            out.put("[" + s.getInt("id") + "]", s.getString("verdict"));
        }
        return out;
    }

    // ------------------------------------------------------- a company's own system

    @Test
    public void theSystemUseOfACompanyIsAFactWithItsPage() throws Exception {
        final ToolContext chat = chat("alpha-web");
        final JSONObject found = call(chat, "kg_search", "{\"query\":\"Tapir Consulting\",\"type\":\"organization\"}");
        final String id = first(found, "entities").getString("entity");
        final JSONObject company = call(chat, "kg_entity", "{\"entity\":\"" + id + "\",\"predicate\":\"system_signal\"}");
        final JSONObject signal = first(company, "facts");
        assertEquals("system_signal", signal.getString("predicate"));
        assertTrue(signal.toString(), signal.getString("value").contains("odoo"));
        assertTrue(signal.toString(), signal.getString("value").contains("internal_use"));
        assertEquals("https://www.tapir-consulting.example/", signal.getString("url"));
        assertTrue("the passage the fact was read from: " + signal, signal.getString("passage").contains("Odoo intern"));
        final String cite = signal.getString("cite");

        // all relations of the company: its job postings point to it
        final JSONObject all = call(chat, "kg_entity", "{\"entity\":\"" + id + "\"}");
        final JSONArray incoming = all.getJSONArray("incoming_relations");
        final List<String> postings = new ArrayList<>();
        for (int i = 0; i < incoming.length(); i++) {
            if ("hiring_organization".equals(incoming.getJSONObject(i).getString("predicate"))) {
                postings.add(incoming.getJSONObject(i).getJSONObject("subject").getString("name"));
            }
        }
        assertEquals(postings.toString(), 3, postings.size());

        // the citation check: the cited page states the use of Odoo, not a number of users
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG nutzt Odoo intern " + cite + ".", chat.sources());
        assertEquals(check.toString(), RagCitations.SUPPORTED, verdicts(check).getString(cite));
        final JSONObject invented = RagCitations.check("Dort arbeiten 4.200 Beschäftigte an zwölf Standorten " + cite + ".", chat.sources());
        assertEquals(invented.toString(), RagCitations.UNSUPPORTED, verdicts(invented).getString(cite));
        assertEquals("[" + cite.substring(1, cite.length() - 1) + "]", invented.getJSONArray("unsupported").toString());

        // a company the graph does not hold: the result says so, nothing to cite
        final JSONObject unknown = call(chat, "kg_search", "{\"query\":\"Phantasia Holding\"}");
        assertEquals(0L, unknown.getLong("total"));
        assertEquals(0, unknown.getJSONArray("entities").length());
        assertTrue(unknown.getString("note").contains("no such entry"));
    }

    // ---------------------------------------------------- another collection

    @Test
    public void aChatOfOneCollectionNeverReadsAnother() throws Exception {
        // what only beta-web holds, found by the administrator's chat of the whole index
        final ToolContext admin = chat(null);
        final JSONObject found = call(admin, "kg_search", "{\"query\":\"Okapi\"}");
        final String betaCompany = first(found, "entities").getString("entity");
        final JSONObject company = call(admin, "kg_entity", "{\"entity\":\"" + betaCompany + "\"}");
        final String betaStatement = byPredicate(company, "facts", "system_signal").getString("statement");
        assertTrue(call(admin, "kg_evidence", "{\"statement\":\"" + betaStatement + "\"}").getJSONArray("evidence").length() > 0);
        assertEquals("the whole index: three job postings", 3L, call(admin, "kg_search", "{\"query\":\"Java\",\"type\":\"job\"}").getLong("total"));

        // the same calls from an account of alpha-web, also with arguments that ask for the other collection
        final ToolContext rita = chat("alpha-web");
        final List<String> answers = new ArrayList<>();
        final JSONObject search = call(rita, "search", "{\"query\":\"collection:beta-web Java Basis Administrator Okapi BETAGEHEIM\","
                + "\"collection\":\"beta-web\",\"global\":true}");
        answers.add(search.toString());
        assertEquals("collection alpha-web", search.getString("scope"));
        for (final String q : this.searched) assertTrue("the search itself is asked for alpha-web only: " + q, q.endsWith("@alpha-web"));
        final JSONObject names = call(rita, "kg_search", "{\"query\":\"Okapi\",\"collection\":\"beta-web\",\"scope\":\"all\"}");
        answers.add(names.toString());
        assertEquals(0L, names.getLong("total"));
        final JSONObject jobs = call(rita, "kg_search", "{\"query\":\"Java\",\"type\":\"job\",\"collections\":[\"beta-web\"]}");
        answers.add(jobs.toString());
        assertEquals("two of three job postings", 2L, jobs.getLong("total"));
        final JSONObject entity = call(rita, "kg_entity", "{\"entity\":\"" + betaCompany + "\",\"collection\":\"beta-web\"}");
        answers.add(entity.toString());
        assertEquals("not_found", entity.getString("error"));
        final JSONObject evidence = call(rita, "kg_evidence", "{\"statement\":\"" + betaStatement + "\",\"collection\":\"beta-web\"}");
        answers.add(evidence.toString());
        assertEquals("a foreign fact answers like an unknown one", "not_found", evidence.getString("error"));
        assertTrue("and the model is told that there is nothing to cite", evidence.getString("note").contains("cannot be verified"));
        final JSONObject unknown = call(rita, "kg_evidence", "{\"statement\":\"kgs_aaaaaaaaaaaaaaaaaaaa\"}");
        assertEquals(evidence.getString("error"), unknown.getString("error"));
        assertEquals(evidence.getString("note"), unknown.getString("note"));

        for (final String answer : answers) {
            assertFalse(answer, answer.contains("BETAGEHEIM"));
            assertFalse(answer, answer.contains("okapi-systems"));
            assertFalse(answer, answer.contains("Okapi Systems"));
            assertFalse(answer, answer.contains("beta-web\"]"));
        }
        for (final RagContext.Source s : rita.sources()) {
            assertEquals(s.url, "alpha-web", s.collection);
            assertFalse(s.url, s.url.contains("okapi-systems"));
            assertFalse(String.valueOf(s.text()), String.valueOf(s.text()).contains("BETAGEHEIM"));
        }
    }

    // ---------------------------------------------------------------- limits

    @Test
    public void toolsNeedTheScopeOfAChatAndStayWithinItsBudgets() throws Exception {
        // without the scope of a chat request the graph tools do not exist; search stays the index tool it was
        for (final String tool : new String[] {"kg_search", "kg_entity", "kg_evidence", "job_search", "provider_search"}) {
            assertFalse(tool, ToolProvider.offered(tool, true, null));
            assertTrue(tool, ToolProvider.executeTool(tool, "{\"query\":\"Java\"}", true, null).contains("not released"));
            assertEquals(tool, 0, ToolProvider.allowedCallsPerTurn(tool, true, null));
        }
        assertFalse("a chat of some collections without a scope: no index search", ToolProvider.offered("search", false, null));
        final ToolContext chat = chat("alpha-web");
        assertEquals(List.of("search", "kg_search", "kg_entity", "kg_evidence", "job_search", "provider_search"), ToolProvider.scopedTools(false, chat));
        final JSONObject body = new JSONObject();
        ToolProvider.applyReleasedTools(body, true, false, chat);
        assertTrue(body.toString(), body.toString().contains("\"kg_evidence\""));
        // a scope without the graph (an access the graph is not open to): the search only
        final ToolContext noGraph = new ToolContext("alpha-web", false, (a, b, c, d) -> new ArrayList<>(), null, 16000, "test");
        assertEquals(List.of("search", "provider_search"), ToolProvider.scopedTools(false, noGraph));
        assertTrue(ToolProvider.executeTool("kg_search", "{\"query\":\"Java\"}", false, noGraph).contains("not released"));

        // invalid arguments are refused, not passed on
        assertEquals("invalid_arguments", call(chat, "kg_entity", "{\"entity\":\"1 OR 1=1\"}").getString("error"));
        assertEquals("invalid_arguments", call(chat, "kg_evidence", "{\"statement\":\"../../etc\"}").getString("error"));
        assertEquals("invalid_arguments", call(chat, "search", "{}").getString("error"));

        // the calls of one answer are counted over all tools
        for (int i = chat.calls(); i < ToolContext.MAX_CALLS; i++) {
            assertFalse(call(chat, "kg_search", "{\"query\":\"Java\"}").has("error"));
        }
        final JSONObject over = call(chat, "search", "{\"query\":\"Java\"}");
        assertEquals("tool_budget", over.getString("error"));
        assertEquals(ToolContext.MAX_CALLS, chat.calls());

        // the room for results: lists get shorter, and when nothing fits any more the call is refused
        final ToolContext small = new ToolContext("alpha-web", true, (a, b, c, d) -> new ArrayList<>(this.pages), graph(), 0, "test");
        final int room = small.room();
        final JSONObject one = call(small, "search", "{\"query\":\"Java Berater Tapir\"}");
        assertTrue(one.toString(), one.toString().length() <= room);
        assertTrue("fewer pages than matched, and the result says so: " + one, one.getJSONArray("sources").length() < 3 && one.has("not_shown"));
        assertEquals("tool_budget", call(small, "search", "{\"query\":\"Java\"}").getString("error"));
    }

    // ------------------------------------------------------------------- job search (section 12)

    static final String JOB_QUESTION = "Welche Stellenanzeigen gibt es zu Java und Machine Learning bei Beratungsunternehmen mit "
            + "Prozessrobotik?";

    @Test
    public void aJobQuestionIsPlannedAsFewShortTitleSearchesPerSubject() {
        assertTrue(JobSearch.intent(JOB_QUESTION));
        assertTrue(JobSearch.intent("Gibt es offene Positionen als Werkstudent?"));
        assertTrue(JobSearch.intent("Suche Jobs (m/w/d) in Nebenstadt"));
        assertFalse(JobSearch.intent("Was bietet die Tapir Consulting AG an?"));
        final JobSearch.Plan plan = JobSearch.plan(JOB_QUESTION);
        assertEquals(List.of("Java", "ML", "Robotik"), plan.criteria());
        assertEquals(List.of("Java ML Robotik", "Java", "ML", "Robotik", "Prozessrobotik", "Machine Learning"), plan.variants());
        // a compound of the question is searched as it is: the name search matches only the start of a word
        assertEquals(List.of("Robotik", "Prozessrobotik", "Robotics"),
                JobSearch.plan("Gibt es Jobs in der Prozessrobotik bei Beratungsunternehmen?").variants());
        assertTrue(plan.variants().size() <= JobSearch.MAX_VARIANTS);
        // a role of the question is searched with the first subject
        assertEquals(List.of("ML entwickler", "ML", "Machine Learning", "maschinelles Lernen"),
                JobSearch.plan("Stellen als Entwickler für maschinelles Lernen").variants());
        // a long question keeps its short subjects (the words about jobs and the answer do not count)
        assertEquals(List.of("Geschäftsfeldentwicklung", "Robotik", "Java", "ML"), JobSearch.plan("Ich suche für eine Marktanalyse "
                + "Stellenanzeigen von Beratungsunternehmen, die sich mit Geschäftsfeldentwicklung und Prozessrobotik beschäftigen und dabei "
                + "Java sowie maschinelles Lernen einsetzen. Bitte nenne die Stellenbezeichnungen, die Arbeitgeber und die Quelle mit Link.")
                .criteria());
        // without configured subject groups: the subjects are the question's own words
        ToolTestSettings.use((key, dflt) -> key.endsWith(".enabled") ? "true" : dflt);
        assertEquals(List.of("Java", "ML"), JobSearch.plan("Welche Stellenanzeigen gibt es zu Java und ML?").criteria());
        ToolTestSettings.use((key, dflt) -> key.equals(JobSearch.SUBJECTS_KEY) ? SUBJECTS : key.endsWith(".enabled") ? "true" : dflt);
        // any other subject: its own words, no words about jobs or the answer
        assertEquals(List.of("Pflegefachkraft Nebenstadt", "Pflegefachkraft", "Nebenstadt"), JobSearch.plan("Welche Stellenangebote als Pflegefachkraft gibt es in Nebenstadt? "
                + "Nenne Arbeitgeber und Quelle.").variants());
    }

    @Test
    public void aJobQuestionIsAnsweredWeaklyByPagesThatAreNoPostings() {
        final RagQuery query = RagQuery.prepare("Stellenangebot Tapir Consulting Java");
        final RagCandidate home = new RagCandidate("https://www.tapir-consulting.example/", "Tapir Consulting AG", "", "Tapir Consulting AG. Java.",
                "www.tapir-consulting.example", List.of("alpha-web"), 0);
        final RagCandidate posting = new RagCandidate("https://www.tapir-consulting.example/karriere/java-senior-berater-finance",
                "Java Senior Berater (m/w/d) Finance", "", "Stellenangebot.", "www.tapir-consulting.example", List.of("alpha-web"), 1);
        final RagRetriever.Result onlyHome = new RagRetriever((q, c, n, g) -> List.of(home)).retrieve(query, "alpha-web", false, 8);
        assertEquals("majority", onlyHome.stage);
        assertTrue("a company page matching the subjects is no posting", JobSearch.weak(onlyHome));
        final RagRetriever.Result withPosting = new RagRetriever((q, c, n, g) -> List.of(posting, home)).retrieve(query, "alpha-web", false, 8);
        assertFalse("a job page among the hits: the search answered", JobSearch.weak(withPosting));
        assertTrue(JobSearch.weak(new RagRetriever((q, c, n, g) -> List.<RagCandidate>of()).retrieve(query, "alpha-web", false, 8)));
    }

    @Test
    public void aCompoundOfTheQuestionFindsThePosting() throws Exception {
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan("Gibt es Jobs in der Prozessrobotik bei Beratungsunternehmen?"));
        final List<String> titles = new ArrayList<>();
        for (final JobSearch.Job job : result.jobs) titles.add(job.title);
        assertEquals(List.of("ML-Entwickler (m/w/d) Java Prozessrobotik", "Werkstudent Robotiktechnik (m/w/d)"), titles);
    }

    @Test
    public void theJobSearchFindsPostingsWithTheirOriginalPagesAndMarksPartialMatches() throws Exception {
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan(JOB_QUESTION));
        assertEquals(null, result.error);
        final List<String> titles = new ArrayList<>();
        for (final JobSearch.Job job : result.jobs) titles.add(job.title);
        // the full match first; then the subjects in turn (Robotik's working student before the second Java posting)
        assertEquals(List.of("ML-Entwickler (m/w/d) Java Prozessrobotik", "Werkstudent Robotiktechnik (m/w/d)",
                "Java Senior Berater (m/w/d) Finance"), titles);
        assertFalse(result.jobs.get(0).partial());
        assertTrue(result.jobs.get(1).partial() && result.jobs.get(2).partial());
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", result.jobs.get(0).url());

        final JobSearch.Built built = JobSearch.format(result, 1, 9000, "collection alpha-web", false);
        assertEquals(3, built.sources.size());
        final RagContext.Source first = built.sources.get(0);
        assertEquals(1, first.id);
        assertEquals(RagContext.Source.KIND_GRAPH, first.kind);
        assertEquals("alpha-web", first.collection);
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", first.url);
        assertTrue(built.text, built.text.startsWith("[1] Scoutro knowledge graph: job posting ML-Entwickler (m/w/d) Java Prozessrobotik\n"
                + "URL: https://www.tapir-consulting.example/karriere/ml-entwickler-java\nCollection: alpha-web\nText: "));
        assertTrue(built.text, built.text.contains("Employer: Tapir Consulting AG."));
        assertTrue(built.text, built.text.contains("Subjects of the question it shows: Java, ML, Robotik."));
        assertTrue(built.text, built.text.contains("[3] Scoutro knowledge graph: job posting Java Senior Berater (m/w/d) Finance"));
        assertTrue(built.text, built.text.contains("PARTIAL MATCH: not shown for ML, Robotik."));
        assertTrue(built.text, built.text.contains("PARTIAL MATCH: not shown for Java, ML."));
        assertTrue(built.text, built.text.contains("Posted: 2026-01-05. Valid through: 2026-02-28."));
        // the stock of the graph counts as current: dates are shown, never turned into a remark about the posting's state
        assertFalse(built.text, built.text.contains("ended") || built.text.contains("Application status") || built.text.contains("not stated"));
        assertEquals(1, built.meta.getInt("full"));
        assertEquals(2, built.meta.getInt("partial"));
        // the source parsed back from the kept search document of a follow-up is the same, with its text for the support check
        final List<RagContext.Source> kept = RagContext.parse(built.text);
        assertEquals(first.url, kept.get(0).url);
        assertEquals(RagContext.Source.KIND_GRAPH, kept.get(0).kind);
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG sucht einen ML-Entwickler für Java Prozessrobotik [1].",
                built.sources);
        assertEquals(RagCitations.SUPPORTED, verdicts(check).getString("[1]"));
        // the room of the context: whole entries only
        final JobSearch.Built small = JobSearch.format(result, 1, 700, "collection alpha-web", false);
        assertEquals(1, small.sources.size());
        assertEquals("found but without room is never 'none'", 2, small.meta.getInt("notShown"));
    }

    @Test
    public void theJobSearchStaysInTheCollectionOfTheRequestAndSaysWhenThereIsNothing() throws Exception {
        final String question = "Gibt es eine Stelle als Java Basis Administrator?";
        final JobSearch.Result admin = JobSearch.run(chat(null), JobSearch.plan(question));
        assertTrue(admin.jobs.stream().anyMatch(j -> j.title.equals("Java Basis Administrator (m/w/d)")));
        final JobSearch.Result rita = JobSearch.run(chat("alpha-web"), JobSearch.plan(question));
        for (final JobSearch.Job job : rita.jobs) {
            assertFalse(job.title, job.title.contains("Basis Administrator"));
            assertFalse(job.url(), job.url().contains("okapi"));
        }
        final JobSearch.Built built = JobSearch.format(rita, 1, 9000, "collection alpha-web", false);
        assertFalse(built.text, built.text.contains("BETAGEHEIM") || built.text.contains("Okapi"));

        // a real zero: no posting for these subjects anywhere
        final JobSearch.Result none = JobSearch.run(chat(null), JobSearch.plan("Welche Stellenangebote gibt es für Tiefseetaucher mit Unterwasserschweißen?"));
        assertEquals(0, none.jobs.size());
        assertFalse(none.searched.isEmpty());
        final JobSearch.Built empty = JobSearch.format(none, 1, 9000, "all collections of the local index", false);
        assertEquals("", empty.text);
        assertEquals(0, empty.meta.getInt("found"));
        assertTrue(net.yacy.ai.PromptGuard.jobSearchNote(0, none.searched, none.plan.criteria(), "x").contains("found none"));

        // only with the release of kg_search and a scope that admits the graph
        assertTrue(JobSearch.released(false, chat("alpha-web")));
        assertFalse(JobSearch.released(false, null));
        assertFalse(JobSearch.released(false, new ToolContext("alpha-web", false, (a, b, c, d) -> new ArrayList<>(), null, 16000, "test")));
        // job_search follows kg_search while not set on its own; its own setting wins
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.kg_search.enabled") ? "false" : key.equals("ai.tools.job_search.enabled") ? dflt
                : key.endsWith(".enabled") ? "true" : dflt);
        assertFalse(JobSearch.released(false, chat("alpha-web")));
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.job_search.enabled") ? dflt : key.endsWith(".enabled") ? "true" : dflt);
        assertTrue(JobSearch.released(false, chat("alpha-web")));
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.job_search.enabled") ? "false" : key.endsWith(".enabled") ? "true" : dflt);
        assertFalse(JobSearch.released(false, chat("alpha-web")));
    }

    /**
     * A larger stock of invented postings in alpha-web (Tapir): 14 Java postings, a robotics posting by its title, one by its
     * field only (a graph fact), one by the text of its page only, and the robotics posting a second time on another page.
     * None of them states a date.
     */
    private void manyPostingsOfOneSubject() throws Exception {
        for (int i = 1; i <= 14; i++) {
            final String n = String.format("%02d", i);
            final String title = "Java Entwickler (m/w/d) Team " + n;
            page("ALPJ" + n + "host01", "https://www.tapir-consulting.example/karriere/java-entwickler-team-" + n, "alpha-web", title,
                    posting(title, "Sie entwickeln Anwendungen im Team " + n + ".", TAPIR), title + ". Stellenangebot in Vollzeit.");
        }
        page("ALPHRBhost01", "https://www.tapir-consulting.example/karriere/robotik-ingenieur", "alpha-web", "Robotik Ingenieur (m/w/d)",
                posting("Robotik Ingenieur (m/w/d)", "Sie programmieren unsere Montageroboter.", TAPIR),
                "Robotik Ingenieur (m/w/d). Sie programmieren unsere Montageroboter. Stellenangebot.");
        // the same posting on a second page: counted once
        page("ALPHRChost01", "https://www.tapir-consulting.example/jobs/robotik-ingenieur", "alpha-web", "Robotik Ingenieur (m/w/d)",
                posting("Robotik Ingenieur (m/w/d)", "Sie programmieren unsere Montageroboter.", TAPIR),
                "Robotik Ingenieur (m/w/d). Sie programmieren unsere Montageroboter. Stellenangebot.");
        // the subject only in a fact of the posting (its field), not in the title
        page("ALPHFAhost01", "https://www.tapir-consulting.example/karriere/ingenieur-montage", "alpha-web", "Ingenieur (m/w/d) Montage",
                "{\"@type\":\"JobPosting\",\"title\":\"Ingenieur (m/w/d) Montage\",\"description\":\"Sie planen Montagelinien.\","
                        + "\"occupationalCategory\":\"Robotik\",\"hiringOrganization\":" + TAPIR + "}",
                "Ingenieur (m/w/d) Montage. Sie planen Montagelinien. Stellenangebot.");
        // the subject only in the indexed text of the posting's page
        page("ALPHTXhost01", "https://www.tapir-consulting.example/karriere/fachkraft-fertigung", "alpha-web", "Fachkraft (m/w/d) Fertigung",
                posting("Fachkraft (m/w/d) Fertigung", "Sie betreuen unsere Fertigung.", TAPIR),
                "Fachkraft (m/w/d) Fertigung. Sie betreuen unsere Fertigung. Ihr Team arbeitet an Zellen der Robotik. Stellenangebot.");
    }

    @Test
    public void theJobSearchDeterminesAllMatchingPostingsBeforeItLimitsTheAnswer() throws Exception {
        manyPostingsOfOneSubject();
        final JobSearch.Plan plan = JobSearch.plan("Welche Stellenanzeigen gibt es zu Java oder Robotik?");
        assertEquals(List.of("Java", "Robotik"), plan.criteria());
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), plan);
        assertEquals(null, result.error);
        assertTrue(result.searched.toString(), result.complete && !result.lowerBound() && !result.timedOut);
        // every posting of alpha-web was read, those of beta-web never
        assertEquals(21, result.scanned);
        // found before the limit: 16 Java (14 teams, the senior consultant, the ML developer), Robotik by title (the
        // engineer, once although on two pages; the ML developer; the working student), by a fact (assembly) and by the
        // page's text (production): 20 postings, more than the 12 candidates of before
        assertEquals(20, result.found);
        assertEquals(Integer.valueOf(16), result.perSubject.get("Java"));
        assertEquals(Integer.valueOf(5), result.perSubject.get("Robotik"));
        // shown: at most MAX_JOBS, every subject, the posting of both subjects first, no duplicate
        assertEquals(JobSearch.MAX_JOBS, result.jobs.size());
        final List<String> titles = new ArrayList<>();
        for (final JobSearch.Job job : result.jobs) titles.add(job.title);
        assertEquals(titles.toString(), "ML-Entwickler (m/w/d) Java Prozessrobotik", titles.get(0));
        assertTrue(titles.toString(), titles.stream().anyMatch(t -> t.startsWith("Java Entwickler")));
        assertTrue(titles.toString(), titles.contains("Robotik Ingenieur (m/w/d)"));
        assertEquals(titles.toString(), titles.size(), new java.util.HashSet<>(titles).size());
        for (int i = 1; i < result.jobs.size(); i++) assertTrue(titles.toString(), result.jobs.get(i - 1).met.size() >= result.jobs.get(i).met.size());
        for (final JobSearch.Job job : result.jobs) assertTrue(job.url(), job.url().startsWith("https://www.tapir-consulting.example/"));
        // the fact-only and the text-only posting are found and counted, each by its own path
        final JobSearch.Result robotics = JobSearch.run(chat("alpha-web"), JobSearch.plan("Welche Stellenanzeigen gibt es zu Robotik?"));
        final java.util.Map<String, JobSearch.Job> byTitle = new java.util.LinkedHashMap<>();
        for (final JobSearch.Job job : robotics.jobs) byTitle.put(job.title, job);
        assertEquals(5, robotics.found);
        assertEquals(Integer.valueOf(JobSearch.BY_FACT), byTitle.get("Ingenieur (m/w/d) Montage").how.get("Robotik"));
        assertEquals(Integer.valueOf(JobSearch.BY_TEXT), byTitle.get("Fachkraft (m/w/d) Fertigung").how.get("Robotik"));
        assertEquals(Integer.valueOf(JobSearch.BY_TITLE), byTitle.get("Robotik Ingenieur (m/w/d)").how.get("Robotik"));
        // dates neither exclude nor rank: the working student's posting states an end date in the past and is found,
        // the postings without any date are found and no remark about a missing date is made
        assertTrue(byTitle.toString(), byTitle.containsKey("Werkstudent Robotiktechnik (m/w/d)"));
        final JobSearch.Built built = JobSearch.format(robotics, 1, 9000, "collection alpha-web", false);
        assertTrue(built.text, built.text.contains("Valid through: 2026-02-28."));
        assertFalse(built.text, built.text.contains("not stated") || built.text.contains("ended") || built.text.contains("Application status"));
        assertTrue(built.text, built.text.contains("Shown in the text of its page: Robotik."));
        assertEquals(5, built.meta.getInt("found"));
        assertEquals(5, built.meta.getInt("shown"));
        assertFalse(built.meta.getBoolean("lowerBound"));
        assertTrue(net.yacy.ai.PromptGuard.jobSearchNote(20, 6, false, result.searched, result.plan.criteria(), "x").contains("found 20 matching postings; 6 are given"));
    }

    @Test
    public void aCompanyPageIsNoPostingAndTheScopeHolds() throws Exception {
        manyPostingsOfOneSubject();
        // the company's services page names Prozessrobotik, but it is no posting: only postings count
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan("Welche Stellenanzeigen gibt es zu Prozessrobotik?"));
        for (final JobSearch.Job job : result.jobs) assertFalse(job.url(), job.url().endsWith("/leistungen"));
        // beta-web: its own posting only; alpha-web's never
        final JobSearch.Result beta = JobSearch.run(chat("beta-web"), JobSearch.plan("Welche Stellenanzeigen gibt es zu Java?"));
        assertEquals(1, beta.found);
        assertEquals(1, beta.scanned);
        assertEquals("Java Basis Administrator (m/w/d)", beta.jobs.get(0).title);
        // the whole local index (administrator): both collections
        assertEquals(17, JobSearch.run(chat(null), JobSearch.plan("Welche Stellenanzeigen gibt es zu Java?")).found);
    }

    @Test
    public void theJobSearchKeepsItsTimeLimitAndThenCountsALowerBound() throws Exception {
        manyPostingsOfOneSubject();
        final JobSearch.Plan plan = JobSearch.plan("Welche Stellenanzeigen gibt es zu Java oder Robotik?");
        // a clock that is past the limit after a few reads: the search stops, what it found is a lower bound
        final long[] now = {0L};
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), plan, () -> {
            final long t = now[0];
            now[0] += JobSearch.MAX_MILLIS / 3 + 1;
            return t;
        });
        assertTrue(result.timedOut);
        assertTrue(result.lowerBound());
        assertTrue(result.searched.toString(), result.searched.size() < plan.variants().size() + plan.criteria().size());
        assertTrue(result.jobs.size() <= JobSearch.MAX_JOBS);
        final JobSearch.Built built = JobSearch.format(result, 1, 9000, "collection alpha-web", false);
        assertTrue(built.meta.getBoolean("lowerBound"));
        // with nothing found within the budget, the model is told not to claim that the index holds none
        final String none = net.yacy.ai.PromptGuard.jobSearchNote(0, 0, true, result.searched, plan.criteria(), "collection alpha-web");
        assertTrue(none, none.contains("within its time and size budget") && none.contains("do not say that the index holds none"));
        assertTrue(net.yacy.ai.PromptGuard.jobSearchNote(3, 3, true, result.searched, plan.criteria(), "x").contains("at least 3 matching postings"));
    }

    static final String BIBER = "{\"@type\":\"Organization\",\"name\":\"Biber Messtechnik GmbH\",\"url\":\"https://www.biber-messtechnik.example/\"}";

    /** A posting of Biber in alpha-web: its id (12 characters), title, the JSON-LD extras and the text of its page. */
    private void biber(final String id, final String path, final String title, final String extra, final String text) throws Exception {
        page(id, "https://www.biber-messtechnik.example/" + path, "alpha-web", title,
                "{\"@type\":\"JobPosting\",\"title\":\"" + title + "\",\"description\":\"Stellenangebot in Vollzeit.\"" + extra
                        + ",\"hiringOrganization\":" + BIBER + "}", title + ". " + text);
    }

    @Test
    public void sevenPostingsOfOneEmployerAreAllFoundAndAtMostSixShown() throws Exception {
        // seven postings of one employer, each its own (title, page), the subject by title, by a fact or by the page text
        for (int i = 1; i <= 5; i++) biber("BIBK0" + i + "host03", "jobs/kalibrierung-" + i, "Kalibrierung Techniker (m/w/d) Linie " + i, "",
                "Stellenangebot.");
        biber("BIBF01host03", "jobs/pruefstand", "Ingenieur (m/w/d) Prüfstand", ",\"occupationalCategory\":\"Kalibrierung\"", "Stellenangebot.");
        biber("BIBT01host03", "jobs/labor", "Techniker (m/w/d) Labor", "", "Sie übernehmen die Kalibrierung unserer Messgeräte. Stellenangebot.");
        // a ranked search that finds nothing at all: the page text is read through the direct index by the postings' addresses
        final ToolContext chat = new ToolContext("alpha-web", true, (concepts, query, c, count) -> new ArrayList<>(), graph(), 16000, "test")
                .withIndex(index());
        final JobSearch.Result result = JobSearch.run(chat, JobSearch.plan("Wie viele Stellenanzeigen gibt es zur Kalibrierung?"));
        assertTrue(JobSearch.wantsAll("Wie viele Stellenanzeigen gibt es zur Kalibrierung?") && JobSearch.wantsAll("Zeig mir alle Stellen"));
        assertFalse(JobSearch.wantsAll("Welche Stellenanzeigen gibt es zur Kalibrierung?"));
        assertEquals(result.gaps.toString(), 7, result.found);
        assertTrue(result.gaps.toString(), result.complete);
        assertEquals(JobSearch.MAX_JOBS, result.jobs.size());
        final JobSearch.Built built = JobSearch.format(result, 1, 9000, "collection alpha-web", false);
        assertEquals(7, built.meta.getInt("found"));
        assertEquals(6, built.meta.getInt("shown"));
        final java.util.Map<String, Integer> how = new java.util.LinkedHashMap<>();
        final JobSearch.Result all = JobSearch.run(chat, JobSearch.plan("Wie viele Stellenanzeigen gibt es zur Kalibrierung?"));
        for (final JobSearch.Job job : all.jobs) how.put(job.title, job.how.get("Kalibrierung"));
        // the shown six keep the strongest: the titles first; the fact and the text posting counted among the seven
        assertTrue(how.toString(), how.values().stream().allMatch(v -> v != null));
        assertTrue(this.indexQueries.toString(), this.indexQueries.stream().anyMatch(q -> q.contains("urls]") && q.endsWith("@alpha-web")));
        // without a direct index the page text is not reached through the ranked search here: a lower bound, with its reason
        final ToolContext ranked = new ToolContext("alpha-web", true, (concepts, query, c, count) -> new ArrayList<>(), graph(), 16000, "test");
        final JobSearch.Result partial = JobSearch.run(ranked, JobSearch.plan("Wie viele Stellenanzeigen gibt es zur Kalibrierung?"));
        assertEquals(6, partial.found);
        assertTrue(partial.lowerBound());
        assertTrue(partial.gaps.toString(), partial.gaps.get(0).startsWith("no direct index"));
        final String note = net.yacy.ai.PromptGuard.jobSearchNote(partial.found, 6, partial.gaps, partial.searched, partial.plan.criteria(), "x");
        assertTrue(note, note.contains("at least 6 matching postings, a lower bound (no direct index"));
    }

    @Test
    public void aCountQuestionHasNoSubjectOfItsOwnAndCountsLikeShowAll() throws Exception {
        biber("BIBK01host03", "jobs/kalibrierung-1", "Kalibrierung Techniker (m/w/d) Linie 1", "", "Stellenangebot.");
        biber("BIBK02host03", "jobs/kalibrierung-2", "Ingenieur (m/w/d) Prüfstand", "", "Sie übernehmen die Kalibrierung. Stellenangebot.");
        // irrelevant postings whose texts carry the words of a count question
        biber("BIBV01host03", "jobs/projektleitung", "Projektleiter (m/w/d) Bau", "", "Vielseitige Aufgaben erwarten Sie, vielen Dank.");
        biber("BIBV02host03", "jobs/buchhaltung", "Buchhalter (m/w/d)", "", "Sie haben viele Freiheiten und vielfältige Aufgaben.");
        biber("BIBV03host03", "jobs/lager", "Lagerist (m/w/d)", "", "Ein Lager mit vielen Regalen, wie viele andere auch, insgesamt alles komplett.");
        final ToolContext chat = new ToolContext("alpha-web", true, (concepts, query, c, count) -> new ArrayList<>(), graph(), 16000, "test")
                .withIndex(index());
        final String[] questions = {"Wie viele Stellenanzeigen zu Kalibrierung gibt es?", "Zeige alle Stellenanzeigen zu Kalibrierung",
                "Wieviele Stellen zur Kalibrierung gibt es insgesamt?", "Anzahl der Stellenanzeigen zu Kalibrierung", "Liste sämtliche Stellen zur Kalibrierung auf"};
        for (final String q : questions) {
            assertTrue(q, JobSearch.wantsAll(q)); // the full search still runs
            final JobSearch.Plan plan = JobSearch.plan(q);
            assertEquals(q, List.of("Kalibrierung"), plan.criteria());
            final JobSearch.Result result = JobSearch.run(chat, plan);
            assertEquals(q + " " + result.perSubject, 2, result.found);
            assertEquals(q, java.util.Map.of("Kalibrierung", 2), result.perSubject);
            assertFalse(q, result.lowerBound());
        }
        // the tool counts the same
        final JSONObject r = call(chat, "job_search", "{\"query\":\"Wie viele Stellenanzeigen zu Kalibrierung gibt es?\"}");
        assertEquals(r.toString(), 2, r.getInt("found"));
        assertEquals(r.toString(), List.of("Kalibrierung"), List.of(r.getJSONArray("subjects").getString(0)));
        assertEquals(1, r.getJSONArray("subjects").length());
    }

    @Test
    public void theJobSearchToolStaysWithinTheRoomOfTheAnswer() throws Exception {
        for (int i = 1; i <= 7; i++) biber("BIBR0" + i + "host03", "jobs/kalibrierung-r" + i, "Kalibrierung Techniker (m/w/d) Linie " + i, "",
                "Stellenangebot. " + "Sie kalibrieren Messgeräte, prüfen Sensoren und dokumentieren die Ergebnisse. ".repeat(12));
        for (final int room : new int[] {16000, 4096, 1500, 600}) {
            final ToolContext chat = new ToolContext("alpha-web", true, (concepts, query, c, count) -> new ArrayList<>(), graph(), room, "test")
                    .withIndex(index());
            final String raw = net.yacy.ai.ToolProvider.executeTool("job_search", "{\"query\":\"Kalibrierung\"}", false, chat);
            final JSONObject r = new JSONObject(raw); // valid JSON
            assertFalse(room + " " + raw, r.has("error"));
            assertTrue(room + " " + raw.length(), raw.length() <= Math.max(room, ToolContext.MIN_RESULT_CHARS));
            assertEquals(room + " " + raw, 7, r.getInt("found"));
            assertFalse(r.getBoolean("lower_bound"));
            final int shown = r.getInt("shown");
            assertEquals(shown, r.getJSONArray("postings").length());
            assertEquals("only the postings given are sources", shown, chat.sources().size());
            for (int i = 0; i < shown; i++) assertEquals("[" + (i + 1) + "]", r.getJSONArray("postings").getJSONObject(i).getString("cite"));
            if (room == 16000) assertEquals(6, shown);
            if (room <= 1500) assertTrue(raw, r.optBoolean("shortened"));
            if (room == 4096) assertTrue(raw, shown >= 3);
        }
    }

    @Test
    public void sevenRecordsOfOneEmployerAreSixPostings() throws Exception {
        // six postings of one employer; one of them stands on a second page with its title in capitals: seven records
        final String[] fields = {"Finance", "Controlling", "Treasury", "Logistik", "Vertrieb", "Einkauf"};
        for (int i = 0; i < fields.length; i++) {
            biber("BIBS0" + i + "host03", "karriere/" + fields[i].toLowerCase(), "Senior Berater (m/w/d) " + fields[i], "", "Stellenangebot.");
        }
        biber("BIBS07host03", "partner/senior-berater-finance", "SENIOR BERATER (M/W/D) FINANCE", "", "Stellenangebot.");
        final ToolContext chat = chat("alpha-web");
        final JobSearch.Result result = JobSearch.run(chat, JobSearch.plan("Zeige alle Stellenanzeigen von Biber"));
        assertEquals(List.of("Biber"), result.plan.criteria());
        assertTrue(result.gaps.toString(), result.complete);
        assertEquals("the records that match", 7, result.records);
        assertEquals("the postings: the one on two pages once", 6, result.found);
        assertEquals(6, result.ids.size());
        assertEquals(6, new java.util.HashSet<>(result.ids).size());
        assertEquals(JobSearch.MAX_JOBS, result.jobs.size());
        // every title once among the six, whichever of the two pages stands for the one posting
        final java.util.Set<String> titles = new java.util.TreeSet<>();
        for (final JobSearch.Job job : result.jobs) titles.add(RagQuery.fold(job.title));
        assertEquals(6, titles.size());
        final JobSearch.Built built = JobSearch.format(result, 1, 9000, "collection alpha-web", false);
        assertEquals(7, built.meta.getInt("records"));
        assertEquals(6, built.meta.getInt("found"));
        // the tool and the server's own answer to the count question say the same
        final JSONObject tool = call(chat("alpha-web"), "job_search", "{\"query\":\"Stellenanzeigen von Biber\"}");
        assertEquals(6, tool.getInt("found"));
        assertEquals(7, tool.getInt("records"));
        final String answer = QuestionKind.countOfJobs("Wie viele Stellenanzeigen von Biber gibt es?", result, built.sources, chat);
        assertTrue(answer, answer.contains("gibt es **6** Stellenanzeigen zu Biber") && answer.contains("7 Datensätze passen; 1 davon ist dieselbe"));
        assertTrue(answer, answer.contains("[1]") && answer.contains("[6]") && !answer.contains("[7]"));
    }

    @Test
    public void allCollectionsHoldEveryHitOfOneCollection() throws Exception {
        for (int i = 1; i <= 9; i++) biber("BIBA0" + i + "host03", "jobs/kalibrierung-a" + i, "Kalibrierung Techniker (m/w/d) Linie " + i, "", "Stellenangebot.");
        biber("BIBT09host03", "jobs/labor-a", "Techniker (m/w/d) Labor", "", "Sie übernehmen die Kalibrierung unserer Messgeräte. Stellenangebot.");
        page("BETACChost02", "https://www.okapi-systems.example/jobs/kalibrierung", "beta-web", "Kalibrierung Ingenieur (m/w/d)",
                posting("Kalibrierung Ingenieur (m/w/d)", "Sie kalibrieren Sensoren.", OKAPI), "Kalibrierung Ingenieur (m/w/d). Stellenangebot.");
        providers();
        final JobSearch.Plan plan = JobSearch.plan("Zeige alle Stellenanzeigen zu Kalibrierung");
        final JobSearch.Result alpha = JobSearch.run(chat("alpha-web"), plan);
        final JobSearch.Result beta = JobSearch.run(chat("beta-web"), plan);
        final JobSearch.Result all = JobSearch.run(chat(null), plan);
        assertTrue(alpha.complete && beta.complete && all.complete);
        assertEquals(10, alpha.found);
        assertEquals(1, beta.found);
        // the same stock: the whole index holds every posting of each collection, and nothing else here
        assertTrue(all.ids.containsAll(alpha.ids) && all.ids.containsAll(beta.ids));
        assertEquals(alpha.found + beta.found, all.found);
        assertEquals(alpha.scanned + beta.scanned, all.scanned);
        // the providers alike
        final ServiceSearch.Plan service = ServiceSearch.plan("Wie viele Anbieter für Schimmelsanierung gibt es in Musterstadt?");
        final ServiceSearch.Result one = ServiceSearch.run(chat("alpha-web"), service, List.of());
        final ServiceSearch.Result every = ServiceSearch.run(chat(null), service, List.of());
        assertTrue(one.gaps.toString() + every.gaps, !one.lowerBound() && !every.lowerBound());
        assertTrue(every.keys.toString(), every.keys.containsAll(one.keys));
        assertEquals(one.found + 1, every.found);
        assertTrue(every.perPlace.get(ServiceSearch.AT_PLACE) >= one.perPlace.get(ServiceSearch.AT_PLACE));
    }

    @Test
    public void aToolCallForTheSameSearchReusesTheResultOfTheRequest() throws Exception {
        for (int i = 1; i <= 3; i++) biber("BIBU0" + i + "host03", "jobs/kalibrierung-u" + i, "Kalibrierung Techniker (m/w/d) Linie " + i, "", "Stellenangebot.");
        providers();
        final ToolContext chat = chat("alpha-web");
        // the automatic search of the answer
        final JobSearch.Result automatic = JobSearch.run(chat, JobSearch.plan("Wie viele Stellenanzeigen zu Kalibrierung gibt es?"));
        final int queries = this.indexQueries.size();
        // the model asks the tool for the same subjects in other words: no second search
        final JSONObject tool = call(chat, "job_search", "{\"query\":\"Zeige alle Stellenanzeigen zu Kalibrierung\"}");
        assertTrue(tool.toString(), tool.getBoolean("reused"));
        assertEquals(automatic.found, tool.getInt("found"));
        assertEquals("no question to the index for the reused result", queries, this.indexQueries.size());
        assertTrue(automatic == JobSearch.run(chat, JobSearch.plan("Stellenanzeigen Kalibrierung")));
        // another subject is another search; another request (its own context) too
        assertFalse(call(chat, "job_search", "{\"query\":\"Stellenanzeigen zu Robotik\"}").has("reused"));
        assertFalse(call(chat("alpha-web"), "job_search", "{\"query\":\"Stellenanzeigen zu Kalibrierung\"}").has("reused"));
        // the providers: the same services and place, however the request words them
        final ServiceSearch.Result first = ServiceSearch.run(chat, ServiceSearch.plan("Wer hilft in Musterstadt bei Schimmel im Keller?"), List.of());
        final JSONObject again = call(chat, "provider_search", "{\"query\":\"Wer hilft in Musterstadt bei Schimmel im Keller?\"}");
        assertTrue(again.toString(), again.getBoolean("reused"));
        assertEquals(first.found, again.getInt("found"));
        assertEquals(new JSONObject(first.perPlace).toString(), again.getJSONObject("per_place").toString());
        assertFalse(call(chat, "provider_search", "{\"query\":\"Schimmelsanierung Nebenstadt\"}").has("reused"));
    }

    @Test
    public void theKindOfAQuestionAndThePlainCount() {
        assertEquals(QuestionKind.COUNT, QuestionKind.of("Wie viele Stellenanzeigen zu Java gibt es?"));
        assertEquals(QuestionKind.COUNT, QuestionKind.of("How many providers of mould remediation are there in Musterstadt?"));
        assertEquals(QuestionKind.LIST, QuestionKind.of("Zeige alle Stellenanzeigen zu Java"));
        assertEquals(QuestionKind.LIST, QuestionKind.of("Welche Stellenanzeigen gibt es zu Java oder Robotik?"));
        assertEquals(QuestionKind.RECOMMEND, QuestionKind.of("Wer hilft in Musterstadt bei Schimmel im Keller?"));
        assertEquals(QuestionKind.RECOMMEND, QuestionKind.of("Empfiehl mir einen Statiker in Musterstadt"));
        assertEquals(QuestionKind.OTHER, QuestionKind.of("Ist die Stelle als Java Senior Berater noch offen?"));
        // only a question for the number alone is answered without the model
        assertTrue(QuestionKind.plainCount("Wie viele Stellenanzeigen zu Java gibt es?"));
        assertTrue(QuestionKind.plainCount("Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"));
        assertTrue(QuestionKind.plainCount("Anzahl der Stellenanzeigen zu Robotik"));
        assertFalse(QuestionKind.plainCount("Wie viele Stellenanzeigen zu Java gibt es? Nenne Arbeitgeber und Quelle."));
        assertFalse(QuestionKind.plainCount("Wie viele Stellenanzeigen zu Java gibt es, und in welchen Collections?"));
        assertFalse(QuestionKind.plainCount("Wie viele Anbieter gibt es in Musterstadt und wer ist der beste?"));
        assertFalse(QuestionKind.plainCount("Zeige alle Stellenanzeigen zu Java"));
        assertFalse(QuestionKind.plainCount("Wie viele Stellenanzeigen zu Java gibt es?\nUnd warum so wenige?"));
    }

    @Test
    public void theServersAnswerToAPlainCountOfProviders() throws Exception {
        providers();
        final ToolContext chat = chat("alpha-web");
        final ServiceSearch.Result r = ServiceSearch.run(chat, ServiceSearch.plan("Wie viele Anbieter für Schimmelsanierung gibt es in Musterstadt?"), List.of());
        final ServiceSearch.Built built = ServiceSearch.format(r, 1, 9000, chat.scopeLabel(), false);
        final String de = QuestionKind.countOfProviders("Wie viele Anbieter für Schimmelsanierung gibt es in Musterstadt?", r, built.sources, chat);
        assertTrue(de, de.startsWith("In der Collection alpha-web gibt es **" + r.found + "** Anbieter für Schimmelsanierung"));
        assertTrue(de, de.contains("Standort in Musterstadt: **1**") && de.contains("Musterstadt als Einsatzgebiet: **2**"));
        assertTrue(de, de.contains("1 nennen Musterstadt nur") && de.contains("- Becker Schimmelsanierung GmbH (Standort in Musterstadt) [1]"));
        assertFalse(de, de.contains("BETAGEHEIM") || de.contains("Fremd"));
        final String en = QuestionKind.countOfProviders("How many providers of mould remediation are there?", r, built.sources, chat);
        assertTrue(en, en.startsWith("There are **" + r.found + "** providers of Schimmelsanierung, Bausachverständiger in the collection alpha-web"));
        // every cited number is a source of the answer
        final JSONObject check = RagCitations.check(de, built.sources);
        assertEquals(check.toString(), 0, check.getJSONArray("invalid").length());
    }

    /** A page of the direct index only (nothing of it in the graph). */
    private void indexPage(final String url, final String collection, final String title, final String text) {
        this.pages.add(new RagCandidate(url, title, "", text, java.net.URI.create(url).getHost(), List.of(collection), this.pages.size()));
    }

    /**
     * More providers name the place than the search reads ({@link ServiceSearch#PLACE_HOSTS}): the total stands, the numbers
     * located and serving are lower bounds, and a provider whose pages naming the place were not all read is open, not a
     * mere mention. The same for the server's count answer and for provider_search, with and without "in".
     */
    @Test
    public void moreProvidersNamingThePlaceThanReadLeaveTheirRelationOpen() throws Exception {
        final String c = "gamma-web";
        // an office located in the place and one serving it, each with two pages naming the service
        indexPage("https://www.statik-am-ort.example/", c, "Statikbüro am Ort", "Tragwerksplanung und Statik für Wohnhäuser.");
        indexPage("https://www.statik-am-ort.example/kontakt", c, "Kontakt", "Tragwerksplanung. Statikbüro am Ort, Hauptstraße 1, 12345 Musterstadt.");
        indexPage("https://www.statik-umland.example/", c, "Statikbüro Umland", "Tragwerksplanung für Wohnhäuser.");
        indexPage("https://www.statik-umland.example/gebiet", c, "Gebiet", "Tragwerksplanung. Einsatzgebiet: Musterstadt und Umgebung.");
        // one office with three pages naming the service and five naming the place in passing: more than are read of it
        for (int i = 0; i < 5; i++) {
            indexPage("https://www.statik-viele.example/projekt-" + i, c, "Projekt " + i, (i < 3 ? "Tragwerksplanung. " : "")
                    + "Ein Wohnhaus, die Bauherren kommen aus Musterstadt.");
        }
        // 130 house galleries: one page each naming the service and the place in passing
        for (int i = 0; i < 130; i++) {
            indexPage(String.format("https://www.galerie-%03d.example/", i), c, "Hausgalerie " + i,
                    "Ein Einfamilienhaus aus Musterstadt. Die Tragwerksplanung kam vom Architekten.");
        }
        // two offices that never name the place
        indexPage("https://www.statik-fern-a.example/", c, "Statikbüro Fern A", "Tragwerksplanung in Neustadt.");
        indexPage("https://www.statik-fern-b.example/", c, "Statikbüro Fern B", "Tragwerksplanung in Neustadt.");
        final String question = "Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?";
        final ToolContext chat = chat(c);
        final ServiceSearch.Result r = ServiceSearch.run(chat, ServiceSearch.plan(question), List.of());
        // the total: every provider once, complete
        assertEquals(r.searched.toString(), 135, r.found);
        assertFalse(r.gaps.toString(), r.lowerBound());
        // the place: 133 name it; 120 are read (the offices first), 13 galleries are not; the office with five pages naming
        // the place is read in part; both are open, not mentions
        assertTrue(r.placeOpen());
        assertEquals(13, r.placeUnread);
        assertEquals(1, r.placePartly);
        assertFalse(r.placeOutOfTime);
        assertEquals(Integer.valueOf(1), r.perPlace.get(ServiceSearch.AT_PLACE));
        assertEquals(Integer.valueOf(1), r.perPlace.get(ServiceSearch.SERVES_PLACE));
        assertEquals(Integer.valueOf(14), r.perPlace.get(ServiceSearch.UNDECIDED_PLACE));
        assertEquals(Integer.valueOf(117), r.perPlace.get(ServiceSearch.MENTIONS_PLACE));
        assertEquals(Integer.valueOf(2), r.perPlace.get(ServiceSearch.NO_PLACE));
        assertEquals(14, r.undecided());
        assertEquals("Statikbüro am Ort", r.providers.get(0).name);
        assertEquals(ServiceSearch.AT_PLACE, r.providers.get(0).place);
        assertEquals(ServiceSearch.SERVES_PLACE, r.providers.get(1).place);
        assertEquals(ServiceSearch.UNDECIDED_PLACE, r.providers.get(2).place);
        // the server's count answer: the total on its own, the located and serving at least, the open ones with the reason
        final ServiceSearch.Built built = ServiceSearch.format(r, 1, 9000, chat.scopeLabel(), false);
        assertFalse(built.meta.getBoolean("placeComplete"));
        assertTrue(built.text, built.text.contains("not all of them were read"));
        final String de = QuestionKind.countOfProviders(question, r, built.sources, chat);
        assertTrue(de, de.startsWith("In der Collection gamma-web gibt es **135** Anbieter für Tragwerksplanung"));
        assertTrue(de, de.contains("Diese Gesamtzahl ist vollständig") && !de.contains("Untergrenze"));
        assertTrue(de, de.contains("Mit belegtem Standort in Musterstadt: mindestens **1**, mit Musterstadt als Einsatzgebiet: mindestens **1**"));
        assertTrue(de, de.contains("Bei **14** Anbietern ist der Ortsbezug offen"));
        assertTrue(de, de.contains("die Seiten von 13 Anbietern, die Musterstadt nennen, wurden nicht gelesen (Höchstgrenze von 120"));
        assertTrue(de, de.contains("1 Anbieter hat mehr als 3 Seiten zu Musterstadt"));
        assertTrue(de, de.contains("117 nennen Musterstadt nur beiläufig") && de.contains("2 zeigen keinen Bezug zu Musterstadt"));
        assertTrue(de, de.contains("(Ortsbezug offen) [3]"));
        final String en = QuestionKind.countOfProviders("How many providers of Tragwerksplanung are there?", r, built.sources, chat);
        assertTrue(en, en.contains("This total is complete") && en.contains("at least **1**") && en.contains("For **14** providers the relation is open"));
        // the model's note says the same
        final String note = net.yacy.ai.PromptGuard.serviceSearchNote(r.found, built.sources.size(), r.gaps, r.searched, r.plan.place,
                List.of(1, 2), chat.scopeLabel(), r.undecided(), r.placeGaps);
        assertTrue(note, note.contains("it is open for 14 providers") && note.contains("found 135 providers") && !note.contains("at least 135"));
        // provider_search in the same request: the same result; in a new request with and without "in": the same numbers
        final JSONObject reused = call(chat, "provider_search", "{\"query\":\"Tragwerksplanung in Musterstadt\"}");
        assertTrue(reused.toString(), reused.optBoolean("reused"));
        final String[] queries = {"Tragwerksplanung in Musterstadt", "Tragwerksplanung Musterstadt"};
        for (final JSONObject t : new JSONObject[] {reused, call(chat(c), "provider_search", "{\"query\":\"" + queries[0] + "\"}"),
                call(chat(c), "provider_search", "{\"query\":\"" + queries[1] + "\"}")}) {
            assertEquals(t.toString(), 135, t.getInt("found"));
            assertFalse(t.getBoolean("lower_bound"));
            assertFalse(t.getBoolean("place_complete"));
            assertEquals(new JSONObject(r.perPlace).toString(), t.getJSONObject("per_place").toString());
            assertEquals(r.placeGaps.size(), t.getJSONArray("place_gaps").length());
            assertTrue(t.getString("note"), t.getString("note").contains("open for 14 providers"));
        }
        // below the limit nothing is open: a collection with fewer providers naming the place
        final ServiceSearch.Result few = ServiceSearch.run(chat("alpha-web"), ServiceSearch.plan("Wie viele Anbieter für Tragwerksplanung gibt es in Musterstadt?"),
                List.of());
        assertFalse(few.placeGaps.toString(), few.placeOpen());
        assertEquals(Integer.valueOf(0), few.perPlace.getOrDefault(ServiceSearch.UNDECIDED_PLACE, 0));
    }

    @Test
    public void aFactOnlyPostingBehindPosition5000IsFound() throws Exception {
        // about 7,000 postings of one collection, written straight into the graph (the extractors of page() would take minutes);
        // the only posting of the subject shows it in a fact only and comes after the 6,000th
        final long t0 = System.currentTimeMillis();
        bulkPostings(7000, 6500);
        final long written = System.currentTimeMillis() - t0;
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan("Welche Stellenanzeigen gibt es zur Hydraulik?"),
                System::currentTimeMillis, 30_000L);
        assertTrue(result.gaps.toString(), result.complete);
        assertTrue(String.valueOf(result.scanned), result.scanned >= 7000);
        assertEquals(1, result.found);
        assertEquals("Monteur (m/w/d) 6500", result.jobs.get(0).title);
        assertEquals(Integer.valueOf(JobSearch.BY_FACT), result.jobs.get(0).how.get("Hydraulik"));
        System.out.println("SCALE: " + result.scanned + " postings written in " + written + " ms, searched in " + result.millis + " ms");
        // within the default budget the search reports what it read and why the count is a lower bound if it did not finish
        final JobSearch.Result timed = JobSearch.run(chat("alpha-web"), JobSearch.plan("Welche Stellenanzeigen gibt es zur Hydraulik?"));
        System.out.println("SCALE default budget: complete=" + timed.complete + " scanned=" + timed.scanned + " found=" + timed.found
                + " ms=" + timed.millis + " gaps=" + timed.gaps);
        assertTrue(timed.complete || (timed.lowerBound() && timed.gaps.get(0).startsWith("time budget")));
    }

    // ------------------------------------------------------------------- provider search (section 13), graph and index

    /** An organisation's JSON-LD: its name and site, and optionally its address, service area and offered services. */
    private static String org(final String name, final String host, final String locality, final String area, final String... services) {
        final StringBuilder o = new StringBuilder("{\"@type\":\"Organization\",\"name\":\"" + name + "\",\"url\":\"https://" + host + "/\"");
        if (locality != null) {
            o.append(",\"address\":{\"@type\":\"PostalAddress\",\"streetAddress\":\"Hauptstraße 1\",\"postalCode\":\"12345\","
                    + "\"addressLocality\":\"").append(locality).append("\"}");
        }
        if (area != null) o.append(",\"areaServed\":\"").append(area).append('"');
        if (services.length > 0) {
            o.append(",\"hasOfferCatalog\":{\"@type\":\"OfferCatalog\",\"itemListElement\":[");
            for (int i = 0; i < services.length; i++) {
                o.append(i == 0 ? "" : ",").append("{\"@type\":\"Offer\",\"itemOffered\":{\"@type\":\"Service\",\"name\":\"")
                        .append(services[i]).append("\"}}");
            }
            o.append("]}");
        }
        return o.append('}').toString();
    }

    /**
     * Invented providers of mould remediation: Becker (address in Musterstadt, three pages, graph and pages), Adler (a service
     * area in the graph, its page does not name the service), Cramer (no offer in the graph, its page states its area),
     * Dorn (its page mentions Musterstadt in passing), six more without a place, Esser (only the second service, a building
     * surveyor) and, in beta-web, another provider.
     */
    private void providers() throws Exception {
        final String becker = org("Becker Schimmelsanierung GmbH", "www.becker-sanierung.example", "Musterstadt", null, "Schimmelsanierung");
        page("PBEC01pbec01", "https://www.becker-sanierung.example/", "alpha-web", "Becker Schimmelsanierung", becker,
                "Becker Schimmelsanierung GmbH, Hauptstraße 1, 12345 Musterstadt. Schimmelsanierung für Wohnungen.");
        page("PBEC02pbec01", "https://www.becker-sanierung.example/leistungen", "alpha-web", "Leistungen", becker,
                "Schimmelsanierung, Trocknung und Beratung.");
        page("PBEC03pbec01", "https://www.becker-sanierung.example/kontakt", "alpha-web", "Kontakt", becker,
                "Kontakt zur Schimmelsanierung: Telefon und E-Mail.");
        page("PADL01padl01", "https://www.adler-sanierung.example/", "alpha-web", "Adler Sanierung",
                org("Adler Sanierung GmbH", "www.adler-sanierung.example", null, "Musterstadt", "Schimmelsanierung"),
                "Adler Sanierung GmbH. Wir sanieren Keller und Bäder.");
        page("PCRA01pcra01", "https://www.cramer-bautenschutz.example/", "alpha-web", "Cramer Bautenschutz",
                org("Cramer Bautenschutz", "www.cramer-bautenschutz.example", null, null),
                "Cramer Bautenschutz: Schimmelsanierung und Abdichtung. Einsatzgebiet: Musterstadt und Umgebung.");
        page("PDOR01pdor01", "https://www.dorn-fachbetrieb.example/", "alpha-web", "Dorn Fachbetrieb",
                org("Dorn Fachbetrieb", "www.dorn-fachbetrieb.example", null, null),
                "Schimmelsanierung vom Fachbetrieb Dorn. Unser Team war zuletzt auf einer Messe, die in Musterstadt stattfand.");
        for (int i = 1; i <= 6; i++) {
            page("PNOR0" + i + "pnor0" + i, "https://www.nordbau-" + i + ".example/", "alpha-web", "Nordbau " + i,
                    org("Nordbau " + i + " GmbH", "www.nordbau-" + i + ".example", null, null), "Nordbau " + i + ": Schimmelsanierung im Norden.");
        }
        page("PESS01pess01", "https://www.esser-gutachten.example/", "alpha-web", "Gutachterbüro Esser",
                org("Gutachterbüro Esser", "www.esser-gutachten.example", null, null, "Bausachverständiger"),
                "Gutachterbüro Esser: Bausachverständiger für Feuchteschäden.");
        page("PFRE01pfre01", "https://www.fremd-sanierung.example/", "beta-web", "Fremd Sanierung",
                org("Fremd Sanierung GmbH", "www.fremd-sanierung.example", "Musterstadt", null, "Schimmelsanierung"),
                "Fremd Sanierung GmbH, 12345 Musterstadt: Schimmelsanierung. BETAGEHEIM");
    }

    @Test
    public void theProviderSearchCountsEveryProviderOnceWithItsPlace() throws Exception {
        providers();
        final ServiceSearch.Plan plan = ServiceSearch.plan("Wer hilft in Musterstadt bei Schimmel im Keller?");
        assertEquals(List.of("Schimmelsanierung", "Bausachverständiger"), plan.services);
        final ServiceSearch.Result r = ServiceSearch.run(chat("alpha-web"), plan, List.of());
        final java.util.Map<String, ServiceSearch.Provider> byName = new java.util.LinkedHashMap<>();
        final ServiceSearch.Result everything = ServiceSearch.run(chat("alpha-web"), plan, List.of(), System::currentTimeMillis, 30_000L);
        assertTrue(r.gaps.toString(), !r.lowerBound());
        // more than eight providers, each once: Becker over three pages and its name, Adler by the graph only, Cramer by its
        // page only, Dorn, six more, and Esser by the second service only; nothing of beta-web
        assertEquals(r.searched.toString(), 11, r.found);
        assertEquals(ServiceSearch.MAX_SHOWN, r.providers.size());
        assertEquals(11, everything.found);
        for (final ServiceSearch.Provider p : r.providers) byName.put(p.name, p);
        final ServiceSearch.Provider becker = r.providers.get(0);
        assertEquals("Becker Schimmelsanierung GmbH", becker.name);
        assertEquals(ServiceSearch.AT_PLACE, becker.place);
        assertEquals(3, becker.pageCount); // its three pages name the service
        assertTrue(becker.byGraph && becker.byText);
        assertEquals(ServiceSearch.SERVES_PLACE, byName.get("Adler Sanierung GmbH").place);
        assertTrue(byName.get("Adler Sanierung GmbH").byGraph && !byName.get("Adler Sanierung GmbH").byText);
        final ServiceSearch.Provider cramer = byName.get("Cramer Bautenschutz");
        assertTrue(cramer != null && cramer.byText && !cramer.byGraph);
        assertEquals(ServiceSearch.SERVES_PLACE, cramer.place);
        assertEquals(Integer.valueOf(1), r.perPlace.get(ServiceSearch.AT_PLACE));
        assertEquals(Integer.valueOf(2), r.perPlace.get(ServiceSearch.SERVES_PLACE));
        assertEquals(Integer.valueOf(1), r.perPlace.get(ServiceSearch.MENTIONS_PLACE));
        assertEquals(Integer.valueOf(7), r.perPlace.get(ServiceSearch.NO_PLACE));
        // a provider only of the second service (Esser) is one of the eleven: every service of the plan is searched in the
        // graph and the index, also after ten providers of the first
        assertTrue(r.searched.toString(), r.searched.contains("index: Bausachverständiger")
                && r.searched.contains("graph: Bausachverständiger"));
        final ServiceSearch.Plan second = ServiceSearch.planAny("Bausachverständiger");
        final ServiceSearch.Result esser = ServiceSearch.run(chat("alpha-web"), second, List.of());
        assertEquals(1, esser.found);
        assertEquals("Gutachterbüro Esser", esser.providers.get(0).name);
        assertTrue(esser.providers.get(0).byGraph && esser.providers.get(0).byText);
        // the passing mention evidences nothing
        final ServiceSearch.Built built = ServiceSearch.format(everything, 1, 20000, "collection alpha-web", false);
        assertTrue(built.text, built.text.contains("Located in Musterstadt: address in the graph"));
        assertTrue(built.text, built.text.contains("Serves Musterstadt: service area in the graph"));
        assertEquals(11, built.meta.getInt("found"));
        assertEquals(6, built.meta.getInt("shown"));
        assertEquals(3, built.meta.getJSONArray("placeNamedBy").length());
        assertFalse(built.text, built.text.contains("BETAGEHEIM"));
        // the whole index: beta-web's provider too
        assertEquals(12, ServiceSearch.run(chat(null), plan, List.of()).found);
        // the tool: the same search
        final JSONObject tool = call(chat("alpha-web"), "provider_search", "{\"query\":\"Wer hilft in Musterstadt bei Schimmel im Keller?\"}");
        assertEquals(11, tool.getInt("found"));
        assertEquals(6, tool.getInt("shown"));
        assertFalse(tool.getBoolean("lower_bound"));
        assertEquals(ServiceSearch.AT_PLACE, tool.getJSONArray("providers").getJSONObject(0).getString("place"));
        // the time limit before the count is done: a lower bound with its reason
        final long[] now = {0L};
        final ServiceSearch.Result timed = ServiceSearch.run(chat("alpha-web"), plan, List.of(), () -> {
            final long t = now[0];
            now[0] += ServiceSearch.MAX_MILLIS / 2 + 1;
            return t;
        }, ServiceSearch.MAX_MILLIS);
        assertTrue(timed.lowerBound());
        assertTrue(timed.gaps.toString(), timed.gaps.get(0).startsWith("time budget"));
        // the time limit in the place step only: the count stands, the relation to the place is open, not "no relation"
        final long[] later = {0L};
        final ServiceSearch.Result placeTimed = ServiceSearch.run(chat("alpha-web"), plan, List.of(), () -> {
            final long t = later[0];
            later[0] += ServiceSearch.MAX_MILLIS / 4 + 1;
            return t;
        }, ServiceSearch.MAX_MILLIS);
        assertFalse(placeTimed.gaps.toString(), placeTimed.lowerBound());
        assertEquals(11, placeTimed.found);
        assertTrue(placeTimed.placeOpen());
        assertTrue(placeTimed.placeGaps.toString(), placeTimed.placeGaps.get(0).startsWith("time budget"));
        assertTrue(placeTimed.perPlace.toString(), placeTimed.undecided() > 0 && placeTimed.perPlace.get(ServiceSearch.MENTIONS_PLACE) == 0);
        final String note = net.yacy.ai.PromptGuard.serviceSearchNote(timed.found, Math.min(6, timed.found), timed.gaps, timed.searched,
                "Musterstadt", List.of(), "collection alpha-web");
        assertTrue(note, note.contains("a lower bound") || note.contains("within its time budget"));
    }

    @Test
    public void theNamedJobSearchToolUsesTheSameSearch() throws Exception {
        manyPostingsOfOneSubject();
        final ToolContext chat = chat("alpha-web");
        final JSONObject r = call(chat, "job_search", "{\"query\":\"Java oder Robotik\",\"collection\":\"beta-web\"}");
        assertEquals("collection alpha-web", r.getString("scope"));
        assertEquals(20, r.getInt("found"));
        assertFalse(r.getBoolean("lower_bound"));
        assertEquals(JobSearch.MAX_JOBS, r.getInt("shown"));
        assertEquals(16, r.getJSONObject("per_subject").getInt("Java"));
        assertEquals(5, r.getJSONObject("per_subject").getInt("Robotik"));
        assertTrue(r.getString("note"), r.getString("note").contains("Found 20 matching postings, shown 6"));
        assertFalse(r.toString(), r.toString().contains("okapi") || r.toString().contains("BETAGEHEIM"));
    }

    @Test
    public void theNamedJobSearchToolGivesThePostingsAsNumberedSources() throws Exception {
        final ToolContext chat = chat("alpha-web");
        final JSONObject r = call(chat, "job_search", "{\"query\":\"Java ML Robotik\",\"collection\":\"beta-web\"}");
        assertEquals("collection alpha-web", r.getString("scope"));
        assertEquals("[\"Java\",\"ML\",\"Robotik\"]", r.getJSONArray("subjects").toString());
        final JSONArray postings = r.getJSONArray("postings");
        assertTrue(r.toString(), postings.length() >= 2);
        assertEquals("https://www.tapir-consulting.example/karriere/ml-entwickler-java", postings.getJSONObject(0).getString("url"));
        assertTrue(postings.getJSONObject(1).getString("text").contains("PARTIAL MATCH"));
        assertFalse(r.toString(), r.toString().contains("okapi") || r.toString().contains("BETAGEHEIM"));
        assertEquals(RagContext.Source.KIND_GRAPH, source(chat, postings.getJSONObject(0).getString("cite")).kind);
        assertEquals("invalid_arguments", call(chat, "job_search", "{}").getString("error"));
    }
}
