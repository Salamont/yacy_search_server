package net.yacy.scoutro.knowledge.quality;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;

/**
 * The model quality check of the LLM selection (docs/SCOUTRO_LLM_QUALITY_CHECK.md) against a fake endpoint through the
 * real client: the evaluation of a correct, a wrong and an empty model, the caps of calls (retries included) and cost,
 * an unknown cost, cancelling, provenance, staleness, and that nothing but the check's own result is written.
 */
public class ModelQualityCheckTest {

    private QualityFixture f;
    private ModelQualityCheck q;

    @Before
    public void start() throws Exception {
        this.f = new QualityFixture();
        this.q = this.f.check();
    }

    @After
    public void stop() {
        this.f.close();
    }

    private static final ModelQualityCheck.Price PRICE = new ModelQualityCheck.Price(new BigDecimal("2"), new BigDecimal("10"), "USD", "entered");

    private JSONObject run(final ModelQualityCheck.Price price, final boolean acceptUnknown, final Integer maxCalls, final BigDecimal maxCost)
            throws Exception {
        this.q.start(this.f.key(), price, acceptUnknown, maxCalls, maxCost);
        this.q.await(60_000L);
        return this.q.result(this.f.key());
    }

    private static List<JSONObject> deviations(final JSONObject result, final String caseId) {
        final List<JSONObject> out = new ArrayList<>();
        final JSONArray cases = result.optJSONObject("functional").optJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            final JSONObject c = cases.optJSONObject(i);
            if (c.optString("case").equals(caseId)) {
                final JSONArray d = c.optJSONArray("deviations");
                for (int j = 0; j < d.length(); j++) {
                    out.add(d.optJSONObject(j));
                }
            }
        }
        return out;
    }

    private static JSONObject caseResult(final JSONObject result, final String caseId) {
        final JSONArray cases = result.optJSONObject("functional").optJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            if (cases.optJSONObject(i).optString("case").equals(caseId)) {
                return cases.optJSONObject(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------ the evaluation

    /** A model that reads every reference page correctly passes, with the scope, the provenance and what it cost. */
    @Test
    public void aCorrectModelPassesWithItsScopeAndProvenance() throws Exception {
        final JSONObject r = run(PRICE, false, null, null);
        assertEquals(r.toString(2), ModelQualityCheck.PASSED, r.optString("status"));
        final JSONObject scope = r.optJSONObject("scope");
        assertEquals(QualityCases.CASES.size(), scope.optInt("casesEvaluated"));
        assertEquals(QualityCases.VERSION, scope.optString("version"));
        assertEquals("knowledge", scope.optString("usage"));
        assertTrue(scope.optInt("expectedOfModel") > 0);
        assertTrue("the rules state some expected statements already", scope.optInt("givenByRules") > 0);
        final JSONObject tech = r.optJSONObject("technical");
        assertEquals(QualityCases.CASES.size(), tech.optInt("calls"));
        assertEquals(0, tech.optInt("retries"));
        assertEquals(QualityCases.CASES.size() * 1000L, tech.optJSONObject("tokens").optLong("prompt"));
        assertEquals(QualityCases.CASES.size() * 200L, tech.optJSONObject("tokens").optLong("answer"));
        // 10 x (1000 x 2 + 200 x 10) / 1e6 = 0.04 USD
        assertEquals(0, new BigDecimal("0.04").compareTo(new BigDecimal(tech.optJSONObject("cost").optString("amount"))));
        final JSONObject prov = r.optJSONObject("provenance");
        assertEquals("OPENAI", prov.optJSONObject("candidate").optString("service"));
        assertEquals("fixture-model", prov.optJSONObject("candidate").optString("model"));
        assertEquals(QualityCases.CASES.size(), prov.optJSONObject("served").optJSONObject("models").optInt("fixture/served-model"));
        assertEquals(QualityCases.CASES.size(), prov.optJSONObject("served").optJSONObject("providers").optInt("FixtureProvider"));
        assertEquals(KnowledgePrompt.defaults().hash, prov.optJSONObject("prompt").optString("hash"));
        assertEquals(ModelQualityCheck.SCHEMA_HASH, prov.optString("schema"));
        assertEquals(ModelQualityCheck.VALIDATOR, prov.optString("validator"));
        assertEquals(QualityCases.ID + "/" + QualityCases.VERSION, prov.optString("testset"));
        assertFalse(r.optBoolean("stale"));
        // no key anywhere in the result or the status
        assertFalse(r.toString().contains("sk-fixture-secret"));
        assertFalse(this.q.status().toString().contains("sk-fixture-secret"));
        // the requests are the knowledge graph's: its system prompt, its schema, the row's output cap
        final JSONObject req = this.f.requests.get(0);
        assertEquals(KnowledgePrompt.defaults().text, req.optJSONArray("messages").optJSONObject(0).optString("content"));
        assertEquals("json_schema", req.optJSONObject("response_format").optString("type"));
        assertEquals(2048, req.optInt("max_tokens"));
    }

    /** The confirmed mistakes: each deviation names its kind, the page's passage, the expectation and the actual statement. */
    @Test
    public void deviationsShowTheSourceTheExpectationAndTheActualStatement() throws Exception {
        this.f.answer = a -> {
            if ("team_vs_service".equals(a.caseId)) {
                // a team as a service, and a listed service as an organisation
                return QualityFixture.answer(List.of(
                        QualityFixture.entity("e1", "service", "Serviceteam", "Unser Serviceteam erreichen Sie werktags"),
                        QualityFixture.entity("e2", "organization", "Heizungswartung", "Unsere Leistungen: Heizungswartung und Badsanierung"),
                        QualityFixture.entity("e3", "service", "Badsanierung", "Unsere Leistungen: Heizungswartung und Badsanierung")),
                        List.of(), List.of());
            }
            if ("intermediary".equals(a.caseId)) {
                // the agency as the employer
                final String q = "Ihr Ansprechpartner bei der Beispiel Personal GmbH";
                return QualityFixture.answer(List.of(
                        QualityFixture.entity("e1", "job", "Industriemechaniker (m/w/d)", "suchen wir einen Industriemechaniker (m/w/d) in Vollzeit"),
                        QualityFixture.entity("e2", "organization", "Beispiel Personal GmbH", q)),
                        List.of(QualityFixture.claim("e1", "hiring_organization", "e2",
                                "Industriemechaniker (m/w/d) in Vollzeit. Ihr Ansprechpartner bei der Beispiel Personal GmbH")),
                        List.of());
            }
            if ("price_with_service".equals(a.caseId)) {
                // the second service's price on the first
                return QualityFixture.answer(List.of(
                        QualityFixture.entity("e1", "service", "Unterhaltsreinigung", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde"),
                        QualityFixture.entity("e2", "service", "Glasreinigung", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt.")),
                        List.of(), List.of(QualityFixture.value("e1", "price", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt."),
                                QualityFixture.value("e2", "price", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt.")));
            }
            return QualityFixture.ideal(a);
        };
        final JSONObject r = run(PRICE, false, null, null);
        assertEquals(r.optJSONObject("technical").toString() + r.optString("stopReason"), ModelQualityCheck.DEVIATIONS, r.optString("status"));
        final List<JSONObject> team = deviations(r, "team_vs_service");
        JSONObject forbidden = null, wrongType = null;
        for (final JSONObject d : team) {
            if (QualityEvaluator.FORBIDDEN.equals(d.optString("kind"))) {
                forbidden = d;
            }
            if (QualityEvaluator.WRONG_TYPE.equals(d.optString("kind"))) {
                wrongType = d;
            }
        }
        assertNotNull(team.toString(), forbidden);
        assertTrue(forbidden.optJSONObject("forbidden").optString("statement").contains("Serviceteam"));
        assertTrue(forbidden.optJSONObject("actual").optString("statement").contains("Serviceteam"));
        assertTrue(forbidden.optString("source").contains("Unser Serviceteam erreichen Sie"));
        assertNotNull(team.toString(), wrongType);
        assertTrue(wrongType.optJSONObject("expected").optString("statement").startsWith("service"));
        assertTrue(wrongType.optJSONObject("actual").optString("statement").startsWith("organization"));
        // the relation to Heizungswartung is missing as well (its service was not read as one)
        final List<JSONObject> agency = deviations(r, "intermediary");
        assertEquals(agency.toString(), 2, agency.size()); // the agency as employer, and an employer at all
        for (final JSONObject d : agency) {
            assertEquals(QualityEvaluator.FORBIDDEN, d.optString("kind"));
            assertTrue(d.optJSONObject("actual").optString("statement").contains("hiring_organization"));
        }
        final List<JSONObject> price = deviations(r, "price_with_service");
        boolean wrongPrice = false, missingPrice = false;
        for (final JSONObject d : price) {
            wrongPrice |= QualityEvaluator.FORBIDDEN.equals(d.optString("kind")) && d.optJSONObject("actual").optString("statement").contains("4.20");
            missingPrice |= QualityEvaluator.MISSING.equals(d.optString("kind")) && d.optJSONObject("expected").optString("statement").contains("28.50");
        }
        assertTrue(price.toString(), wrongPrice);
        assertTrue(price.toString(), missingPrice);
        // the other pages are unaffected
        assertTrue(deviations(r, "own_job_ad").isEmpty());
        final JSONObject fn = r.optJSONObject("functional");
        assertTrue(fn.optInt("forbidden") >= 4);
        assertEquals(1, fn.optInt("wrongType"));
    }

    /** An empty answer is no pass where statements are expected: each one is missing, as not stated. */
    @Test
    public void anEmptyAnswerFailsWhereStatementsAreExpected() throws Exception {
        this.f.answer = a -> QualityFixture.EMPTY;
        final JSONObject r = run(PRICE, false, null, null);
        assertEquals(ModelQualityCheck.DEVIATIONS, r.optString("status"));
        final JSONObject c = caseResult(r, "team_vs_service");
        assertTrue(c.optBoolean("emptyReading"));
        assertEquals(c.optJSONObject("counts").optInt("expected"), c.optJSONObject("counts").optInt("missing"));
        for (final JSONObject d : deviations(r, "team_vs_service")) {
            assertEquals(QualityEvaluator.NOT_STATED, d.optJSONObject("where").optString("why"));
        }
        assertEquals(0, r.optJSONObject("functional").optInt("correct"));
    }

    /** A correct statement the validator drops is missing, and says so: the model stated it, the validator dropped it. */
    @Test
    public void aStatementTheValidatorDropsIsShownAsMissing() throws Exception {
        this.f.answer = a -> {
            if ("page_tool_vs_product".equals(a.caseId)) {
                // the product with a quote the page does not contain verbatim
                return QualityFixture.answer(List.of(QualityFixture.entity("e1", "organization", "Muster Software GmbH",
                        "Die Muster Software GmbH verkauft MusterText Pro"), QualityFixture.entity("e2", "service", "MusterText Pro",
                                "MusterText Pro ist unser bestes Produkt")),
                        List.of(), List.of());
            }
            return QualityFixture.ideal(a);
        };
        final JSONObject r = run(PRICE, false, null, null);
        JSONObject missing = null;
        for (final JSONObject d : deviations(r, "page_tool_vs_product")) {
            if (QualityEvaluator.MISSING.equals(d.optString("kind")) && d.optJSONObject("expected").optString("statement").equals("service “MusterText Pro”")) {
                missing = d;
            }
        }
        assertNotNull(deviations(r, "page_tool_vs_product").toString(), missing);
        assertEquals(QualityEvaluator.DROPPED_BY_VALIDATOR, missing.optJSONObject("where").optString("why"));
        assertTrue(missing.optJSONObject("where").optJSONArray("reasons").toString().contains("ungrounded"));
        assertTrue(missing.optString("source").contains("verkauft MusterText Pro"));
    }

    /** Ids are no measure: the same reading with other ids is evaluated the same. */
    @Test
    public void theIdsOfTheAnswerDoNotMatter() throws Exception {
        final JSONObject first = run(PRICE, false, null, null);
        this.f.answer = a -> QualityFixture.ideal(a).replace("\"e1\"", "\"x9\"").replace("\"e2\"", "\"y7\"").replace("\"e3\"", "\"z5\"");
        final JSONObject second = run(PRICE, false, null, null);
        assertEquals(first.optJSONObject("functional").toString(), second.optJSONObject("functional").toString());
    }

    // ------------------------------------------------------------ limits

    /** No price: refused unless the unknown cost is accepted; then only the caps of calls and tokens apply. */
    @Test
    public void anUnknownCostMustBeAcceptedExplicitly() throws Exception {
        try {
            this.q.start(this.f.key(), null, false, null, null);
            fail("started without a price");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals(400, e.status);
            assertEquals("cost_unknown", e.code);
        }
        assertTrue(this.f.requests.isEmpty());
        final JSONObject r = run(null, true, null, null);
        assertEquals(ModelQualityCheck.PASSED, r.optString("status"));
        assertFalse(r.optJSONObject("technical").optJSONObject("cost").optBoolean("known"));
    }

    /** The plan shows scope and worst-case cost before anything is sent. */
    @Test
    public void thePlanShowsScopeAndMaximumCostWithoutACall() throws Exception {
        final JSONObject p = this.q.plan(this.f.key(), PRICE, null, null);
        assertTrue(this.f.requests.isEmpty());
        final int n = QualityCases.CASES.size();
        assertEquals(n, p.optJSONObject("calls").optInt("planned"));
        assertEquals(2 * n, p.optJSONObject("calls").optInt("worstCase"));
        final JSONObject cost = p.optJSONObject("cost");
        assertTrue(cost.optBoolean("known"));
        final long worstTokens = p.optJSONObject("tokens").optLong("worstCase");
        assertTrue(worstTokens >= 2L * n * 2048);
        assertTrue(new BigDecimal(cost.optString("worstCase")).signum() > 0);
        assertEquals(n, p.optJSONObject("scope").optInt("cases"));
        assertFalse(p.optJSONObject("cost").has("price") && p.toString().contains("sk-fixture"));
        final JSONObject unknown = this.q.plan(this.f.key(), null, null, null);
        assertFalse(unknown.optJSONObject("cost").optBoolean("known"));
    }

    /** The cap of calls counts every transport, the retry without the schema after an HTTP 400 too. */
    @Test
    public void theCallCapCountsRetries() throws Exception {
        this.f.rejectSchema = true;
        final int n = QualityCases.CASES.size();
        final JSONObject r = run(PRICE, false, n, null);
        assertEquals(ModelQualityCheck.FAILED, r.optString("status"));
        assertEquals(ModelQualityCheck.CALL_CAP, r.optString("stopReason"));
        final JSONObject tech = r.optJSONObject("technical");
        assertEquals(n, tech.optInt("calls"));
        assertEquals(n, this.f.requests.size()); // never more requests than the cap
        assertTrue(tech.optInt("retries") >= 1);
        // the refused transport is no unreported answer; its prompt counts at its worst case (estimated)
        assertEquals(0, tech.optJSONObject("tokens").optInt("unreportedCalls"));
        assertTrue(tech.optJSONObject("tokens").optLong("estimated") > 0);
        try {
            this.q.start(this.f.key(), PRICE, false, n - 1, null);
            fail("a cap below the planned requests");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals("invalid_maxCalls", e.code);
        }
        try {
            this.q.start(this.f.key(), PRICE, false, ModelQualityCheck.MAX_CALLS + 1, null);
            fail("a cap above the maximum");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals("invalid_maxCalls", e.code);
        }
    }

    /** The cost cap admits a request only if its worst case still fits: the run stops before it, never after. */
    @Test
    public void theCostCapStopsBeforeTheNextCall() throws Exception {
        // one request's worst case: about (prompt <= 4000 tokens) x 2 + 2048 x 10 per million = under 0.03 USD
        final JSONObject r = run(PRICE, false, null, new BigDecimal("0.05"));
        assertEquals(ModelQualityCheck.FAILED, r.optString("status"));
        assertEquals(ModelQualityCheck.COST_CAP, r.optString("stopReason"));
        final JSONObject cost = r.optJSONObject("technical").optJSONObject("cost");
        assertTrue(new BigDecimal(cost.optString("amount")).compareTo(new BigDecimal("0.05")) <= 0);
        assertTrue(this.f.requests.size() >= 1 && this.f.requests.size() < QualityCases.CASES.size());
        int notAsked = 0;
        final JSONArray cases = r.optJSONObject("technical").optJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            notAsked += ModelQualityCheck.COST_CAP.equals(cases.optJSONObject(i).optString("notAsked")) ? 1 : 0;
        }
        assertTrue(notAsked > 0);
    }

    /** Without a token report the worst case is counted (estimated), never zero. */
    @Test
    public void anUnreportedAnswerIsCountedAtItsWorstCase() throws Exception {
        this.f.noUsage = true;
        final JSONObject r = run(PRICE, false, null, null);
        final JSONObject tokens = r.optJSONObject("technical").optJSONObject("tokens");
        assertEquals(QualityCases.CASES.size(), tokens.optInt("unreportedCalls"));
        assertTrue(tokens.optLong("estimated") >= QualityCases.CASES.size() * 2048L);
        assertTrue(r.optJSONObject("technical").optJSONObject("cost").optBoolean("estimated"));
    }

    /** Cancelling sends no further request; the one in flight is answered. */
    @Test
    public void aCancelledCheckSendsNoFurtherRequest() throws Exception {
        this.f.hold = new CountDownLatch(1);
        this.q.start(this.f.key(), PRICE, false, null, null);
        assertTrue(this.f.arrived.await(10, TimeUnit.SECONDS));
        assertEquals(ModelQualityCheck.CANCELLING, this.q.cancel().optJSONObject("run").optString("state"));
        this.f.hold.countDown();
        this.q.await(30_000L);
        assertEquals(ModelQualityCheck.CANCELLED, this.q.status().optJSONObject("run").optString("state"));
        assertEquals(1, this.f.requests.size());
        final JSONObject r = this.q.result(this.f.key());
        assertEquals(ModelQualityCheck.FAILED, r.optString("status"));
        assertEquals(ModelQualityCheck.CANCEL, r.optString("stopReason"));
        try {
            this.q.cancel();
            fail("nothing runs");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals("not_running", e.code);
        }
    }

    /** One run at a time. */
    @Test
    public void onlyOneCheckRunsAtATime() throws Exception {
        this.f.hold = new CountDownLatch(1);
        this.q.start(this.f.key(), PRICE, false, null, null);
        try {
            this.q.start(this.f.key(), PRICE, false, null, null);
            fail("a second run");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals(409, e.status);
        } finally {
            this.q.cancel();
            this.f.hold.countDown();
            this.q.await(30_000L);
        }
    }

    /** An endpoint error is a technical failure with the provider's reason, without the key. */
    @Test
    public void anApiErrorIsATechnicalFailureWithoutTheKey() throws Exception {
        this.f.answer = a -> null; // HTTP 500
        final JSONObject r = run(PRICE, false, null, null);
        assertEquals(ModelQualityCheck.FAILED, r.optString("status"));
        assertEquals(QualityCases.CASES.size(), r.optJSONObject("technical").optInt("apiErrors"));
        assertTrue(r.optJSONObject("technical").optJSONArray("cases").optJSONObject(0).optJSONObject("error").optString("message").contains("500"));
        assertFalse(r.toString().contains("sk-fixture-secret"));
    }

    /** A result that cannot be stored ends the run all the same: the next check can start. */
    @Test
    public void aResultThatCannotBeStoredEndsTheRun() throws Exception {
        Files.delete(this.f.dir.toPath());
        Files.write(this.f.dir.toPath(), new byte[0]); // a file where the results' directory belongs
        this.q.start(this.f.key(), PRICE, false, null, null);
        this.q.await(60_000L);
        final JSONObject run = this.q.status().optJSONObject("run");
        assertEquals(ModelQualityCheck.STOPPED, run.optString("state"));
        assertEquals("result_not_stored", run.optString("stopReason"));
        this.q.start(this.f.key(), PRICE, false, null, null);
        this.q.await(60_000L);
        assertEquals(2 * QualityCases.CASES.size(), this.f.requests.size());
    }

    // ------------------------------------------------------------ provenance and staleness

    /** A changed row, prompt or test set makes a result stale; nothing is run again on its own. */
    @Test
    public void aChangeMakesTheResultStaleAndRunsNothing() throws Exception {
        run(PRICE, false, null, null);
        final int calls = this.f.requests.size();
        assertFalse(this.q.result(this.f.key()).optBoolean("stale"));
        // the same row with another output cap
        this.f.candidate = this.f.candidate(4096, 16384, "supported");
        JSONObject r = this.q.result(this.f.key());
        assertTrue(r.optBoolean("stale"));
        assertTrue(r.optJSONArray("staleReasons").toString().contains("maxTokens"));
        this.f.candidate = this.f.candidate(2048, 16384, "supported");
        assertFalse(this.q.result(this.f.key()).optBoolean("stale"));
        // another prompt
        this.f.prompt = KnowledgePrompt.next(KnowledgePrompt.defaults().text + "\n11. Be brief.", 2, 1L);
        r = this.q.result(this.f.key());
        assertTrue(r.optBoolean("stale"));
        assertTrue(r.optJSONArray("staleReasons").toString().contains("prompt"));
        // another structured-output setting of the graph
        this.f.prompt = KnowledgePrompt.defaults();
        this.f.settings.put(net.yacy.scoutro.knowledge.KgConfig.LLM_STRUCTURED_OUTPUT, "json_object");
        r = this.q.result(this.f.key());
        assertTrue(r.optJSONArray("staleReasons").toString().contains("structuredOutput"));
        // the status lists it as stale, too
        final JSONObject s = this.q.status().optJSONArray("candidates").optJSONObject(0);
        assertEquals(ModelQualityCheck.PASSED, s.optString("status"));
        assertTrue(s.optBoolean("stale"));
        assertEquals(calls, this.f.requests.size());
        // a test set version is part of the fingerprint
        assertTrue(ModelQualityCheck.fingerprint(this.f.candidate, KnowledgePrompt.defaults(), QualityFixture.settings()).optString("testset")
                .endsWith("/" + QualityCases.VERSION));
    }

    /** Unchecked until a check ran; a result of a row that is gone is stale. */
    @Test
    public void uncheckedAndRemovedCandidates() throws Exception {
        assertEquals(ModelQualityCheck.UNCHECKED, this.q.status().optJSONArray("candidates").optJSONObject(0).optString("status"));
        assertEquals(ModelQualityCheck.UNCHECKED, this.q.result(this.f.key()).optString("status"));
        run(PRICE, false, null, null);
        final String key = this.f.key();
        this.f.candidate = new ModelQualityCheck.Candidate("OPENAI", this.f.stub(), "another-model", 2048, 16384, "supported", "unknown", false, "");
        final JSONObject r = this.q.result(key);
        assertTrue(r.optBoolean("stale"));
        assertEquals("candidate_removed", r.optJSONArray("staleReasons").optString(0));
        try {
            this.q.start(key, PRICE, false, null, null);
            fail("a removed row");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals(404, e.status);
        }
    }

    /** Without the running graph there is no active prompt: no check starts. */
    @Test
    public void noCheckWithoutTheGraph() throws Exception {
        this.f.graphRunning = false;
        try {
            this.q.start(this.f.key(), PRICE, false, null, null);
            fail("started without the graph");
        } catch (final ModelQualityCheck.Refused e) {
            assertEquals("kg_not_running", e.code);
        }
        assertFalse(this.q.status().optBoolean("graphRunning"));
    }

    // ------------------------------------------------------------ writes

    /** The only thing a check writes is its own result file, without a key and without the prompt text. */
    @Test
    public void theCheckWritesOnlyItsOwnResult() throws Exception {
        run(PRICE, false, null, null);
        final File[] files = this.f.dir.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        assertTrue(files[0].getName().endsWith(".json"));
        final String stored = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        assertFalse(stored.contains("sk-fixture-secret"));
        assertFalse(stored.contains(KnowledgePrompt.defaults().text.substring(0, 60)));
        assertNull(new JSONObject(stored).optJSONObject("result").optJSONObject("provenance").optJSONObject("candidate").opt("apiKey"));
    }
}
