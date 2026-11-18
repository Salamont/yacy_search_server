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
        ToolTestSettings.use((key, dflt) -> key.endsWith(".enabled") ? "true" : dflt);

        // alpha-web: a consultancy with two job postings and its own statement about SAP
        page("ALPHAAhost01", "https://www.tapir-consulting.example/", "alpha-web", "Tapir Consulting AG", TAPIR,
                "Tapir Consulting AG. Wir nutzen SAP S/4HANA intern. Wir beraten den Mittelstand zu Prozessen.");
        page("ALPHABhost01", "https://www.tapir-consulting.example/karriere/sap-senior-berater-finance", "alpha-web",
                "SAP Senior Berater (m/w/d) Finance",
                posting("SAP Senior Berater (m/w/d) Finance", "Sie führen SAP-Finance-Projekte bei unseren Kunden.", TAPIR),
                "SAP Senior Berater (m/w/d) Finance. Sie führen SAP-Finance-Projekte bei unseren Kunden. Stellenangebot in Vollzeit.");
        page("ALPHAChost01", "https://www.tapir-consulting.example/karriere/ki-entwickler-sap", "alpha-web",
                "KI-Entwickler (m/w/d) SAP Prozessautomatisierung",
                posting("KI-Entwickler (m/w/d) SAP Prozessautomatisierung", "Sie entwickeln KI-Agenten für die Automatisierung von SAP-Prozessen.", TAPIR),
                "KI-Entwickler (m/w/d) SAP Prozessautomatisierung. Sie entwickeln KI-Agenten für die Automatisierung von SAP-Prozessen. Stellenangebot.");
        page("ALPHADhost01", "https://www.tapir-consulting.example/leistungen", "alpha-web", "Leistungen der Tapir Consulting", TAPIR,
                "Unsere Leistungen: Geschäftsfeldentwicklung, Prozessautomatisierung und Beratung für Beratungsunternehmen.");
        // a posting of one subject only, with dates: its end date has passed
        page("ALPHAEhost01", "https://www.tapir-consulting.example/karriere/werkstudent-automatisierungstechnik", "alpha-web",
                "Werkstudent Automatisierungstechnik (m/w/d)",
                "{\"@type\":\"JobPosting\",\"title\":\"Werkstudent Automatisierungstechnik (m/w/d)\",\"description\":\"Sie unterstützen "
                        + "unsere Steuerungstechnik.\",\"datePosted\":\"2026-01-05\",\"validThrough\":\"2026-02-28\",\"hiringOrganization\":" + TAPIR + "}",
                "Werkstudent Automatisierungstechnik (m/w/d). Sie unterstützen unsere Steuerungstechnik.");
        // beta-web: another company, never visible to a chat of alpha-web
        page("BETAAAhost02", "https://www.okapi-systems.example/", "beta-web", "Okapi Systems GmbH", OKAPI,
                "Okapi Systems GmbH. Wir nutzen SAP S/4HANA intern. BETAGEHEIM Standort Freiburg.");
        page("BETABBhost02", "https://www.okapi-systems.example/jobs/sap-basis-administrator", "beta-web", "SAP Basis Administrator (m/w/d)",
                posting("SAP Basis Administrator (m/w/d)", "Sie betreiben unsere SAP-Systeme. BETAGEHEIM", OKAPI),
                "SAP Basis Administrator (m/w/d). Sie betreiben unsere SAP-Systeme. BETAGEHEIM Stellenangebot.");
    }

    @After
    public void close() {
        ToolTestSettings.use(null);
        if (this.store != null) this.store.close();
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
        return new ToolContext(collection, true, searcher, graph(), 16000, "test");
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
                "www.tapir-consulting.example", "alpha-web", null, "Geschäftsfeldentwicklung, Prozessautomatisierung und Beratung."));
        chat.attach(answerSources);

        // 1. the model searches again with the concrete words of the question
        final JSONObject search = call(chat, "search", "{\"query\":\"SAP KI Stellenangebot\"}");
        assertEquals("collection alpha-web", search.getString("scope"));
        assertTrue(search.getBoolean("local"));
        final JSONArray hits = search.getJSONArray("sources");
        assertEquals("the one page with every word: " + search, 1, hits.length());
        assertEquals("all", search.getString("stage"));
        assertEquals("https://www.tapir-consulting.example/karriere/ki-entwickler-sap", hits.getJSONObject(0).getString("url"));
        assertEquals("alpha-web", hits.getJSONObject(0).getString("collection"));
        assertEquals("numbered after the sources the answer already has", "[2]", hits.getJSONObject(0).getString("cite"));
        // and once more with other words: a further page gets the next number, a known page keeps its number
        final JSONArray more = call(chat, "search", "{\"query\":\"SAP Stellenangebot\"}").getJSONArray("sources");
        final List<String> cites = new ArrayList<>();
        for (int i = 0; i < more.length(); i++) {
            assertEquals("alpha-web", more.getJSONObject(i).getString("collection"));
            assertFalse(more.getJSONObject(i).toString().contains("okapi-systems"));
            cites.add(more.getJSONObject(i).getString("cite") + " " + more.getJSONObject(i).getString("url"));
        }
        assertEquals(cites.toString(), 2, cites.size());
        assertTrue(cites.toString(), cites.contains("[2] https://www.tapir-consulting.example/karriere/ki-entwickler-sap"));
        assertTrue(cites.toString(), cites.contains("[3] https://www.tapir-consulting.example/karriere/sap-senior-berater-finance"));

        // 2. the graph: job postings by words of their title, with their employer
        final JSONObject jobs = call(chat, "kg_search", "{\"query\":\"SAP\",\"type\":\"job\"}");
        assertEquals(2L, jobs.getLong("total"));
        final List<String> titles = new ArrayList<>();
        String aiJob = null;
        for (int i = 0; i < jobs.getJSONArray("entities").length(); i++) {
            final JSONObject job = jobs.getJSONArray("entities").getJSONObject(i);
            titles.add(job.getString("name"));
            assertEquals("Tapir Consulting AG", job.getJSONArray("employer").getJSONObject(0).getString("name"));
            if (job.getString("name").startsWith("KI-Entwickler")) aiJob = job.getString("entity");
        }
        assertTrue(titles.toString(), titles.contains("SAP Senior Berater (m/w/d) Finance"));
        assertNotNull(titles.toString(), aiJob);

        // 3. the job's relations, each with the page it was read from as a numbered source
        final JSONObject job = call(chat, "kg_entity", "{\"entity\":\"" + aiJob + "\"}");
        final JSONObject employer = byPredicate(job, "facts", "hiring_organization");
        assertEquals("Tapir Consulting AG", employer.getJSONObject("object").getString("name"));
        assertEquals("https://www.tapir-consulting.example/karriere/ki-entwickler-sap", employer.getString("url"));
        final RagContext.Source page = source(chat, employer.getString("cite"));
        assertEquals(RagContext.Source.KIND_GRAPH, page.kind);
        assertEquals("alpha-web", page.collection);

        // 4. the evidence of that relation: page, time and how it was read
        final JSONObject evidence = call(chat, "kg_evidence", "{\"statement\":\"" + employer.getString("statement") + "\"}");
        final JSONObject row = first(evidence, "evidence");
        assertEquals(employer.getString("cite"), row.getString("cite"));
        assertEquals("https://www.tapir-consulting.example/karriere/ki-entwickler-sap", row.getString("url"));
        assertEquals("[\"alpha-web\"]", row.getJSONArray("collections").toString());
        assertTrue(evidence.getString("fact").contains("Tapir Consulting AG"));

        // the answer's sources now hold the tools' pages, and the citation check knows what each number was given for
        assertSame(answerSources, chat);
        final String cite = employer.getString("cite");
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG sucht einen KI-Entwickler für SAP Prozessautomatisierung " + cite
                + ". Das Gehalt liegt bei 95.000 Euro im Jahr " + cite + ". Ein Standort München ist belegt [1].", chat.sources());
        assertTrue(check.getJSONArray("invalid").length() == 0);
        final JSONObject verdicts = verdicts(check);
        assertEquals("the salary is not on the cited page, so its worst verdict counts: " + check, RagCitations.UNSUPPORTED, verdicts.getString(cite));
        assertEquals("a source that does not speak about a site in München: " + check, RagCitations.UNSUPPORTED, verdicts.getString("[1]"));
        final JSONObject fine = RagCitations.check("Die Tapir Consulting AG sucht einen KI-Entwickler für SAP Prozessautomatisierung " + cite + ".",
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

    // ------------------------------------------------------- a company's SAP

    @Test
    public void theSapUseOfACompanyIsAFactWithItsPage() throws Exception {
        final ToolContext chat = chat("alpha-web");
        final JSONObject found = call(chat, "kg_search", "{\"query\":\"Tapir Consulting\",\"type\":\"organization\"}");
        final String id = first(found, "entities").getString("entity");
        final JSONObject company = call(chat, "kg_entity", "{\"entity\":\"" + id + "\",\"predicate\":\"system_signal\"}");
        final JSONObject signal = first(company, "facts");
        assertEquals("system_signal", signal.getString("predicate"));
        assertTrue(signal.toString(), signal.getString("value").contains("sap-s4hana"));
        assertTrue(signal.toString(), signal.getString("value").contains("internal_use"));
        assertEquals("https://www.tapir-consulting.example/", signal.getString("url"));
        assertTrue("the passage the fact was read from: " + signal, signal.getString("passage").contains("SAP S/4HANA"));
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

        // the citation check: the cited page states the use of SAP, not a number of users
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG nutzt SAP S/4HANA intern " + cite + ".", chat.sources());
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
        assertEquals("the whole index: three job postings", 3L, call(admin, "kg_search", "{\"query\":\"SAP\",\"type\":\"job\"}").getLong("total"));

        // the same calls from an account of alpha-web, also with arguments that ask for the other collection
        final ToolContext rita = chat("alpha-web");
        final List<String> answers = new ArrayList<>();
        final JSONObject search = call(rita, "search", "{\"query\":\"collection:beta-web SAP Basis Administrator Okapi BETAGEHEIM\","
                + "\"collection\":\"beta-web\",\"global\":true}");
        answers.add(search.toString());
        assertEquals("collection alpha-web", search.getString("scope"));
        for (final String q : this.searched) assertTrue("the search itself is asked for alpha-web only: " + q, q.endsWith("@alpha-web"));
        final JSONObject names = call(rita, "kg_search", "{\"query\":\"Okapi\",\"collection\":\"beta-web\",\"scope\":\"all\"}");
        answers.add(names.toString());
        assertEquals(0L, names.getLong("total"));
        final JSONObject jobs = call(rita, "kg_search", "{\"query\":\"SAP\",\"type\":\"job\",\"collections\":[\"beta-web\"]}");
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
        for (final String tool : new String[] {"kg_search", "kg_entity", "kg_evidence"}) {
            assertFalse(tool, ToolProvider.offered(tool, true, null));
            assertTrue(tool, ToolProvider.executeTool(tool, "{\"query\":\"SAP\"}", true, null).contains("not released"));
            assertEquals(tool, 0, ToolProvider.allowedCallsPerTurn(tool, true, null));
        }
        assertFalse("a chat of some collections without a scope: no index search", ToolProvider.offered("search", false, null));
        final ToolContext chat = chat("alpha-web");
        assertEquals(List.of("search", "kg_search", "kg_entity", "kg_evidence"), ToolProvider.scopedTools(false, chat));
        final JSONObject body = new JSONObject();
        ToolProvider.applyReleasedTools(body, true, false, chat);
        assertTrue(body.toString(), body.toString().contains("\"kg_evidence\""));
        // a scope without the graph (an access the graph is not open to): the search only
        final ToolContext noGraph = new ToolContext("alpha-web", false, (a, b, c, d) -> new ArrayList<>(), null, 16000, "test");
        assertEquals(List.of("search"), ToolProvider.scopedTools(false, noGraph));
        assertTrue(ToolProvider.executeTool("kg_search", "{\"query\":\"SAP\"}", false, noGraph).contains("not released"));

        // invalid arguments are refused, not passed on
        assertEquals("invalid_arguments", call(chat, "kg_entity", "{\"entity\":\"1 OR 1=1\"}").getString("error"));
        assertEquals("invalid_arguments", call(chat, "kg_evidence", "{\"statement\":\"../../etc\"}").getString("error"));
        assertEquals("invalid_arguments", call(chat, "search", "{}").getString("error"));

        // the calls of one answer are counted over all tools
        for (int i = chat.calls(); i < ToolContext.MAX_CALLS; i++) {
            assertFalse(call(chat, "kg_search", "{\"query\":\"SAP\"}").has("error"));
        }
        final JSONObject over = call(chat, "search", "{\"query\":\"SAP\"}");
        assertEquals("tool_budget", over.getString("error"));
        assertEquals(ToolContext.MAX_CALLS, chat.calls());

        // the room for results: lists get shorter, and when nothing fits any more the call is refused
        final ToolContext small = new ToolContext("alpha-web", true, (a, b, c, d) -> new ArrayList<>(this.pages), graph(), 0, "test");
        final int room = small.room();
        final JSONObject one = call(small, "search", "{\"query\":\"SAP Berater Tapir\"}");
        assertTrue(one.toString(), one.toString().length() <= room);
        assertTrue("fewer pages than matched, and the result says so: " + one, one.getJSONArray("sources").length() < 3 && one.has("not_shown"));
        assertEquals("tool_budget", call(small, "search", "{\"query\":\"SAP\"}").getString("error"));
    }

    // ------------------------------------------------------------------- job search (section 12)

    static final String JOB_QUESTION = "Welche Stellenanzeigen gibt es zu SAP und künstlicher Intelligenz bei Beratungsunternehmen mit "
            + "Prozessautomatisierung?";

    @Test
    public void aJobQuestionIsPlannedAsFewShortTitleSearchesPerSubject() {
        assertTrue(JobSearch.intent(JOB_QUESTION));
        assertTrue(JobSearch.intent("Gibt es offene Positionen als Werkstudent?"));
        assertTrue(JobSearch.intent("Suche Jobs (m/w/d) in Köln"));
        assertFalse(JobSearch.intent("Was bietet die Tapir Consulting AG an?"));
        final JobSearch.Plan plan = JobSearch.plan(JOB_QUESTION);
        assertEquals(List.of("SAP", "KI", "Automatisierung"), plan.criteria());
        assertEquals(List.of("SAP KI Automatisierung", "SAP", "KI", "Automatisierung", "Prozessautomatisierung", "Intelligenz"), plan.variants());
        // a compound of the question is searched as it is: the name search matches only the start of a word
        assertEquals(List.of("Automatisierung", "Prozessautomatisierung", "Automation"),
                JobSearch.plan("Gibt es Jobs in der Prozessautomatisierung bei Beratungsunternehmen?").variants());
        assertTrue(plan.variants().size() <= JobSearch.MAX_VARIANTS);
        // a role of the question is searched with the first subject
        assertEquals(List.of("SAP berater", "SAP"), JobSearch.plan("Stellen als SAP Berater").variants());
        // any other subject: its own words, no words about jobs or the answer
        assertEquals(List.of("pflegefachkraft", "köln"), JobSearch.plan("Welche Stellenangebote als Pflegefachkraft gibt es in Köln? "
                + "Nenne Arbeitgeber und Quelle.").variants());
    }

    @Test
    public void aJobQuestionIsAnsweredWeaklyByPagesThatAreNoPostings() {
        final RagQuery query = RagQuery.prepare("Stellenangebot Tapir Consulting SAP");
        final RagCandidate home = new RagCandidate("https://www.tapir-consulting.example/", "Tapir Consulting AG", "", "Tapir Consulting AG. SAP.",
                "www.tapir-consulting.example", List.of("alpha-web"), 0);
        final RagCandidate posting = new RagCandidate("https://www.tapir-consulting.example/karriere/sap-senior-berater-finance",
                "SAP Senior Berater (m/w/d) Finance", "", "Stellenangebot.", "www.tapir-consulting.example", List.of("alpha-web"), 1);
        final RagRetriever.Result onlyHome = new RagRetriever((q, c, n, g) -> List.of(home)).retrieve(query, "alpha-web", false, 8);
        assertEquals("majority", onlyHome.stage);
        assertTrue("a company page matching the subjects is no posting", JobSearch.weak(onlyHome));
        final RagRetriever.Result withPosting = new RagRetriever((q, c, n, g) -> List.of(posting, home)).retrieve(query, "alpha-web", false, 8);
        assertFalse("a job page among the hits: the search answered", JobSearch.weak(withPosting));
        assertTrue(JobSearch.weak(new RagRetriever((q, c, n, g) -> List.<RagCandidate>of()).retrieve(query, "alpha-web", false, 8)));
    }

    @Test
    public void aCompoundOfTheQuestionFindsThePosting() throws Exception {
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan("Gibt es Jobs in der Prozessautomatisierung bei Beratungsunternehmen?"));
        final List<String> titles = new ArrayList<>();
        for (final JobSearch.Job job : result.jobs) titles.add(job.title);
        assertEquals(List.of("KI-Entwickler (m/w/d) SAP Prozessautomatisierung", "Werkstudent Automatisierungstechnik (m/w/d)"), titles);
    }

    @Test
    public void theJobSearchFindsPostingsWithTheirOriginalPagesAndMarksPartialMatches() throws Exception {
        final JobSearch.Result result = JobSearch.run(chat("alpha-web"), JobSearch.plan(JOB_QUESTION));
        assertEquals(null, result.error);
        final List<String> titles = new ArrayList<>();
        for (final JobSearch.Job job : result.jobs) titles.add(job.title);
        assertEquals(List.of("KI-Entwickler (m/w/d) SAP Prozessautomatisierung", "SAP Senior Berater (m/w/d) Finance",
                "Werkstudent Automatisierungstechnik (m/w/d)"), titles);
        assertFalse(result.jobs.get(0).partial());
        assertTrue(result.jobs.get(1).partial() && result.jobs.get(2).partial());
        assertEquals("https://www.tapir-consulting.example/karriere/ki-entwickler-sap", result.jobs.get(0).url());

        final JobSearch.Built built = JobSearch.format(result, 1, 9000, "collection alpha-web", false);
        assertEquals(3, built.sources.size());
        final RagContext.Source first = built.sources.get(0);
        assertEquals(1, first.id);
        assertEquals(RagContext.Source.KIND_GRAPH, first.kind);
        assertEquals("alpha-web", first.collection);
        assertEquals("https://www.tapir-consulting.example/karriere/ki-entwickler-sap", first.url);
        assertTrue(built.text, built.text.startsWith("[1] Scoutro knowledge graph: job posting KI-Entwickler (m/w/d) SAP Prozessautomatisierung\n"
                + "URL: https://www.tapir-consulting.example/karriere/ki-entwickler-sap\nCollection: alpha-web\nText: "));
        assertTrue(built.text, built.text.contains("Employer: Tapir Consulting AG."));
        assertTrue(built.text, built.text.contains("Subjects of the question it shows: SAP, KI, Automatisierung."));
        assertTrue(built.text, built.text.contains("[2] Scoutro knowledge graph: job posting SAP Senior Berater (m/w/d) Finance"));
        assertTrue(built.text, built.text.contains("PARTIAL MATCH: not shown for KI, Automatisierung."));
        assertTrue(built.text, built.text.contains("PARTIAL MATCH: not shown for SAP, KI."));
        assertTrue(built.text, built.text.contains("Posted: 2026-01-05. Valid through: 2026-02-28."));
        assertTrue(built.text, built.text.contains("the posting's recorded end date or status says it has ended"));
        assertTrue(built.text, built.text.contains("Application status: not checked; whether the posting is still open is a separate question"));
        assertEquals(1, built.meta.getInt("full"));
        assertEquals(2, built.meta.getInt("partial"));
        // the source parsed back from the kept search document of a follow-up is the same, with its text for the support check
        final List<RagContext.Source> kept = RagContext.parse(built.text);
        assertEquals(first.url, kept.get(0).url);
        assertEquals(RagContext.Source.KIND_GRAPH, kept.get(0).kind);
        final JSONObject check = RagCitations.check("Die Tapir Consulting AG sucht einen KI-Entwickler für SAP Prozessautomatisierung [1].",
                built.sources);
        assertEquals(RagCitations.SUPPORTED, verdicts(check).getString("[1]"));
        // the room of the context: whole entries only
        final JobSearch.Built small = JobSearch.format(result, 1, 700, "collection alpha-web", false);
        assertEquals(1, small.sources.size());
        assertEquals("found but without room is never 'none'", 2, small.meta.getInt("notShown"));
    }

    @Test
    public void theJobSearchStaysInTheCollectionOfTheRequestAndSaysWhenThereIsNothing() throws Exception {
        final String question = "Gibt es eine Stelle als SAP Basis Administrator?";
        final JobSearch.Result admin = JobSearch.run(chat(null), JobSearch.plan(question));
        assertTrue(admin.jobs.stream().anyMatch(j -> j.title.equals("SAP Basis Administrator (m/w/d)")));
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
        ToolTestSettings.use((key, dflt) -> key.equals("ai.tools.kg_search.enabled") ? "false" : key.endsWith(".enabled") ? "true" : dflt);
        assertFalse(JobSearch.released(false, chat("alpha-web")));
    }
}
