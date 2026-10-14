package net.yacy.scoutro.knowledge.quality;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * The diagnosis of the quality check (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 4.3) and the counter-checks of the stored runs:
 * the job portal's employer and the agency's position dropped by the validator, software used internally answered as a
 * facility, and the offers and prices missing in both runs. Each answer here is a mock; what is checked is that the
 * statement, its verbatim evidence, the names the validator requires in it, its types and its assignment are told apart,
 * that a drop names the reason of exactly that item, and that "not stated" is said only where every answer was read.
 * Example data only.
 */
public class QualityDiagnosisTest {

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

    private JSONObject run() throws Exception {
        this.q.start(this.f.key(), null, true, null, null);
        this.q.await(60_000L);
        return this.q.result(this.f.key());
    }

    /** The ideal answers, with one page answered by {@code page}. */
    private void answering(final String caseId, final Function<QualityFixture.Ask, String> page) {
        this.f.answer = a -> caseId.equals(a.caseId) ? page.apply(a) : QualityFixture.ideal(a);
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

    /** The deviation of this kind about an expectation whose label contains {@code about}. */
    private static JSONObject deviation(final JSONObject c, final String kind, final String about) {
        final JSONArray d = c.optJSONArray("deviations");
        for (int i = 0; i < d.length(); i++) {
            final JSONObject x = d.optJSONObject(i);
            final JSONObject e = x.optJSONObject("expected") != null ? x.optJSONObject("expected") : x.optJSONObject("forbidden");
            if (kind.equals(x.optString("kind")) && e.optString("statement").contains(about)) {
                return x;
            }
        }
        return null;
    }

    private static String answer(final List<JSONObject> e, final List<JSONObject> c, final List<JSONObject> v) {
        return QualityFixture.answer(e, c, v);
    }

    // ------------------------------------------------------------ the job portal (stored: Nemo, dropped_by_validator)

    /** The correct reading of the job portal: the validator keeps the employer, nothing is missing. */
    @Test
    public void theJobPortalsEmployerOfACorrectReadingIsKept() throws Exception {
        final JSONObject c = caseResult(run(), "job_portal");
        assertEquals(c.toString(2), 0, c.optJSONObject("counts").optInt("missing"));
        assertTrue(c.toString(2), c.optJSONObject("diagnosis").optBoolean("complete"));
        assertEquals(0, c.optJSONObject("diagnosis").optJSONArray("answers").optJSONObject(0).optJSONObject("validator").optInt("droppedUngrounded"));
    }

    /**
     * The employer stated with a verbatim quote that lacks the position's full name: the validator drops exactly this claim
     * as ungrounded. The checks tell the parts apart: the statement and the assignment are right, the quote is on the page,
     * the employer's name is in it, the position's name is not.
     */
    @Test
    public void anEmployerWhoseQuoteLacksThePositionsNameIsDroppedForThatReason() throws Exception {
        answering("job_portal", a -> {
            final String job = a.id("Bauleiter (m/w/d)", "e2");
            final List<JSONObject> e = new ArrayList<>(), c = new ArrayList<>();
            e.add(QualityFixture.entity("e1", "organization", "Beispiel Bau GmbH", "Die Beispiel Bau GmbH sucht einen Bauleiter (m/w/d)"));
            if (job.startsWith("e")) {
                e.add(QualityFixture.entity(job, "job", "Bauleiter (m/w/d)", "Die Beispiel Bau GmbH sucht einen Bauleiter (m/w/d)"));
            }
            c.add(QualityFixture.claim(job, "hiring_organization", "e1", "Die Beispiel Bau GmbH sucht einen Bauleiter"));
            return answer(e, c, List.of());
        });
        final JSONObject c = caseResult(run(), "job_portal");
        final JSONObject d = deviation(c, QualityEvaluator.MISSING, Vocabulary.HIRING_ORGANIZATION);
        assertNotNull(c.toString(2), d);
        final JSONObject w = d.optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, w.optString("why"));
        assertEquals("ungrounded", w.optJSONArray("reasons").optString(0));
        assertTrue(w.optBoolean("reasonAttributed"));
        assertTrue(w.optBoolean("samplesComplete"));
        assertEquals("claim", w.optJSONObject("drop").optString("item"));
        final JSONObject k = w.optJSONObject("checks");
        assertTrue(k.toString(), k.optBoolean("statement"));
        assertTrue(k.optBoolean("assignment"));
        assertTrue(k.optBoolean("type"));
        assertTrue("the quote is verbatim on the page", k.optBoolean("quoteOnPage"));
        assertTrue(k.optJSONObject("namesInQuote").optBoolean("Beispiel Bau GmbH"));
        boolean position = true;
        for (final String n : k.optJSONObject("namesInQuote").keySet()) {
            if (n.startsWith("Bauleiter")) {
                position = k.optJSONObject("namesInQuote").optBoolean(n);
            }
        }
        assertFalse("the position's full name is not in the quote", position);
        // the diagnosis lists the drop with its reason, the answer as it was and what the publication took
        final JSONObject a = c.optJSONObject("diagnosis").optJSONArray("answers").optJSONObject(0);
        assertTrue(a.optString("text").contains("Die Beispiel Bau GmbH sucht einen Bauleiter\""));
        assertEquals("ungrounded", a.optJSONObject("validator").optJSONArray("drops").optJSONObject(0).optString("reason"));
        assertTrue(a.optJSONObject("validator").optBoolean("dropsComplete"));
    }

    /** The employer typed as a service: dropped with its entity, and the type check says so; the statement is not missing silently. */
    @Test
    public void anEmployerOfTheWrongTypeIsToldApartFromAMissingOne() throws Exception {
        answering("job_portal", a -> {
            final String job = a.id("Bauleiter (m/w/d)", "e2");
            final String q = "Die Beispiel Bau GmbH sucht einen Bauleiter (m/w/d)";
            final List<JSONObject> e = new ArrayList<>(), c = new ArrayList<>();
            e.add(QualityFixture.entity("e1", "service", "Beispiel Bau GmbH", q));
            if (job.startsWith("e")) {
                e.add(QualityFixture.entity(job, "job", "Bauleiter (m/w/d)", q));
            }
            c.add(QualityFixture.claim(job, "hiring_organization", "e1", q));
            return answer(e, c, List.of());
        });
        final JSONObject c = caseResult(run(), "job_portal");
        final JSONObject d = deviation(c, QualityEvaluator.MISSING, Vocabulary.HIRING_ORGANIZATION);
        assertNotNull(c.toString(2), d);
        final JSONObject w = d.optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, w.optString("why"));
        assertTrue(w.optBoolean("reasonAttributed"));
        final String reason = w.optJSONArray("reasons").optString(0);
        assertTrue(reason, reason.startsWith("claim_"));
        assertTrue(w.optJSONObject("checks").optBoolean("quoteOnPage"));
    }

    // ------------------------------------------------------------ the agency (stored: Qwen, dropped_by_validator)

    /** The correct reading of the agency's page keeps the position. */
    @Test
    public void theAgencysPositionOfACorrectReadingIsKept() throws Exception {
        final JSONObject c = caseResult(run(), "intermediary");
        assertEquals(c.toString(2), 1, c.optJSONObject("counts").optInt("correct"));
        assertEquals(0, c.optJSONObject("counts").optInt("missing"));
    }

    /** A paraphrased quote: the position is dropped as ungrounded, and the check says the quote is not on the page. */
    @Test
    public void aPositionWithAParaphrasedQuoteIsUngroundedAndNotOnThePage() throws Exception {
        answering("intermediary", a -> answer(List.of(
                QualityFixture.entity("e1", "job", "Industriemechaniker (m/w/d)", "Industriemechaniker (m/w/d) für einen Kunden gesucht")),
                List.of(), List.of()));
        final JSONObject c = caseResult(run(), "intermediary");
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, "Industriemechaniker").optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, w.optString("why"));
        assertEquals("ungrounded", w.optJSONArray("reasons").optString(0));
        assertFalse(w.optJSONObject("checks").optBoolean("quoteOnPage"));
        assertTrue(w.optJSONObject("checks").optJSONObject("namesInQuote").optBoolean("Industriemechaniker (m/w/d)"));
    }

    /** A verbatim quote without the full name: on the page, but the name the validator requires is not in it. */
    @Test
    public void aPositionWhoseQuoteLacksItsNameIsUngroundedThoughOnThePage() throws Exception {
        answering("intermediary", a -> answer(List.of(
                QualityFixture.entity("e1", "job", "Industriemechaniker (m/w/d)", "suchen wir einen Industriemechaniker")),
                List.of(), List.of()));
        final JSONObject c = caseResult(run(), "intermediary");
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, "Industriemechaniker").optJSONObject("where");
        assertEquals(w.toString(2), "ungrounded", w.optJSONArray("reasons").optString(0));
        assertTrue(w.optJSONObject("checks").optBoolean("quoteOnPage"));
        assertFalse(w.optJSONObject("checks").optJSONObject("namesInQuote").optBoolean("Industriemechaniker (m/w/d)"));
    }

    // ------------------------------------------------------------ software used internally (stored: Qwen, a facility)

    /**
     * "Muster Office" answered as a facility: the validator keeps it (a facility needs no offer), the publication would
     * take it, and the evaluation rates it as the forbidden statement it is, whatever its type. A model mistake the check
     * shows; the validator is not changed for it here.
     */
    @Test
    public void softwareUsedInternallyAsAFacilityIsAForbiddenStatement() throws Exception {
        answering("software_competence_vs_internal_use", a -> withEntity(QualityFixture.ideal(a), QualityFixture.entity("e9", "facility",
                "Muster Office", "Für unsere eigene Büroorganisation nutzen wir intern Muster Office")));
        final JSONObject c = caseResult(run(), "software_competence_vs_internal_use");
        final JSONObject d = deviation(c, QualityEvaluator.FORBIDDEN, "Muster Office");
        assertNotNull(c.toString(2), d);
        assertTrue(d.optJSONObject("actual").optString("statement").startsWith("facility"));
        boolean published = false;
        final JSONArray items = c.optJSONObject("diagnosis").optJSONObject("published").optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            published |= items.optJSONObject(i).optString("statement").contains("Muster Office");
        }
        assertTrue("the publication would take it", published);
    }

    private static String withEntity(final String answer, final JSONObject entity) {
        try {
            final JSONObject o = new JSONObject(answer);
            o.optJSONArray("entities").put(entity);
            return o.toString();
        } catch (final org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------ offers and prices (missing in both stored runs)

    /** The services without their offers: every answer was read, so the offers are "not stated". */
    @Test
    public void offersTheModelLeftOutAreNotStated() throws Exception {
        answering("team_vs_service", a -> answer(List.of(
                QualityFixture.entity("e2", "service", "Heizungswartung", "Unsere Leistungen: Heizungswartung und Badsanierung"),
                QualityFixture.entity("e3", "service", "Badsanierung", "Unsere Leistungen: Heizungswartung und Badsanierung")),
                List.of(), List.of()));
        final JSONObject c = caseResult(run(), "team_vs_service");
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, Vocabulary.OFFERS).optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.NOT_STATED, w.optString("why"));
        assertEquals(1, w.optInt("checkedAnswers"));
    }

    /** The same answer cut off at the output cap: whether the model would have stated the offers is unknown. */
    @Test
    public void offersMissingFromACutOffAnswerHaveAnUnknownCause() throws Exception {
        answering("team_vs_service", a -> answer(List.of(
                QualityFixture.entity("e2", "service", "Heizungswartung", "Unsere Leistungen: Heizungswartung und Badsanierung")),
                List.of(), List.of()));
        this.f.finish = a -> "team_vs_service".equals(a.caseId) ? "length" : "stop";
        final JSONObject c = caseResult(run(), "team_vs_service");
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, Vocabulary.OFFERS).optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.CAUSE_UNKNOWN, w.optString("why"));
        assertEquals("answer_1_truncated", w.optJSONArray("incomplete").optString(0));
        assertTrue(c.optJSONObject("diagnosis").optJSONArray("answers").optJSONObject(0).optBoolean("truncatedByModel"));
    }

    /** An offer between the right entities whose quote names only one of them: dropped as ungrounded, attributed. */
    @Test
    public void anOfferWhoseQuoteNamesOnlyTheServiceIsUngrounded() throws Exception {
        answering("team_vs_service", a -> answer(List.of(
                QualityFixture.entity("e1", "organization", "Muster Haustechnik GmbH", "Kontakt zur Muster Haustechnik GmbH"),
                QualityFixture.entity("e2", "service", "Heizungswartung", "Unsere Leistungen: Heizungswartung und Badsanierung"),
                QualityFixture.entity("e3", "service", "Badsanierung", "Unsere Leistungen: Heizungswartung und Badsanierung")),
                List.of(QualityFixture.claim("e1", "offers", "e2", "Unsere Leistungen: Heizungswartung")), List.of()));
        final JSONObject c = caseResult(run(), "team_vs_service");
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, Vocabulary.OFFERS).optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, w.optString("why"));
        assertEquals("ungrounded", w.optJSONArray("reasons").optString(0));
        assertTrue(w.optJSONObject("checks").optBoolean("quoteOnPage"));
        assertFalse(w.optJSONObject("checks").optJSONObject("namesInQuote").optBoolean("Muster Haustechnik GmbH"));
        assertTrue(w.optJSONObject("checks").optJSONObject("namesInQuote").optBoolean("Heizungswartung"));
    }

    /**
     * A price whose quote holds no amount: the price is dropped, and so is its service, whose only context of an offer was
     * that price (a service needs an offer, #68). Both are told apart, each with the reason of exactly that item.
     */
    @Test
    public void aPriceWithoutItsAmountIsDroppedAndTakesItsServiceAlong() throws Exception {
        answering("price_with_service", a -> answer(List.of(
                QualityFixture.entity("e1", "service", "Unterhaltsreinigung", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt."),
                QualityFixture.entity("e2", "service", "Glasreinigung", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt.")),
                List.of(), List.of(QualityFixture.value("e1", "price", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt."),
                        QualityFixture.value("e2", "price", "die Glasreinigung kostet"))));
        final JSONObject c = caseResult(run(), "price_with_service");
        final JSONObject service = deviation(c, QualityEvaluator.MISSING, "service “Glasreinigung”").optJSONObject("where");
        assertEquals(service.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, service.optString("why"));
        assertEquals("entity_service_not_offered", service.optJSONArray("reasons").optString(0));
        assertTrue(service.optJSONObject("checks").optBoolean("quoteOnPage"));
        final JSONObject price = deviation(c, QualityEvaluator.MISSING, "4.20").optJSONObject("where");
        assertEquals(price.toString(2), QualityEvaluator.DROPPED_BY_VALIDATOR, price.optString("why"));
        assertTrue(price.toString(2), price.optBoolean("reasonAttributed"));
        // the price's own reason, and the sample says its subject was the service the validator dropped before
        assertEquals(price.toString(2), "value_unresolved_subject", price.optJSONArray("reasons").optString(0));
        assertEquals("dropped_entity:entity_service_not_offered", price.optJSONObject("drop").optString("subject"));
        assertFalse("the expected amount is not in its evidence", price.optJSONObject("checks").optBoolean("evidencePassage"));
        // the first service keeps its price
        assertEquals(c.toString(2), 2, c.optJSONObject("counts").optInt("missing"));
    }

    /** A price assigned to the wrong service is kept by the validator; the evaluation rates it as the other service's price. */
    @Test
    public void aPriceOnTheWrongServiceIsTheForbiddenOtherPrice() throws Exception {
        answering("price_with_service", a -> answer(List.of(
                QualityFixture.entity("e1", "service", "Unterhaltsreinigung", "Für die Unterhaltsreinigung berechnen wir 28,50 € pro Stunde zzgl. MwSt."),
                QualityFixture.entity("e2", "service", "Glasreinigung", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt.")),
                List.of(), List.of(QualityFixture.value("e1", "price", "die Glasreinigung kostet 4,20 € pro Stunde zzgl. MwSt."))));
        final JSONObject c = caseResult(run(), "price_with_service");
        assertNotNull(c.toString(2), deviation(c, QualityEvaluator.FORBIDDEN, "Unterhaltsreinigung"));
        final JSONObject w = deviation(c, QualityEvaluator.MISSING, "4.20").optJSONObject("where");
        assertEquals(w.toString(2), QualityEvaluator.STATED_OTHERWISE, w.optString("why"));
        assertFalse("assigned to the other service", w.optJSONObject("checks").optBoolean("assignment"));
        assertTrue(w.optJSONObject("checks").optBoolean("evidencePassage"));
    }

    // ------------------------------------------------------------ bounds, keys and earlier results

    /** A key in an answer is never stored, an answer is cut at its bound, and the cut is marked. */
    @Test
    public void theDiagnosisHoldsNoKeyAndMarksWhatItCut() throws Exception {
        final StringBuilder big = new StringBuilder();
        while (big.length() < QualityDiagnosis.MAX_ANSWER_CHARS + 100) {
            big.append("Die Muster Logistik GmbH sucht einen Lagerhelfer (m/w/d) ");
        }
        answering("missing_stays_unknown", a -> "not json sk-fixture-secret-0123456789 Bearer abcdefghijkl " + big);
        final JSONObject r = run();
        assertFalse(r.toString().contains("sk-fixture-secret"));
        assertFalse(r.toString().contains("abcdefghijkl"));
        final JSONObject d = caseResult(r, "missing_stays_unknown").optJSONObject("diagnosis");
        assertFalse(d.optBoolean("complete"));
        assertEquals("answer_1_cut", d.optJSONArray("incomplete").optString(0));
        final JSONObject a = d.optJSONArray("answers").optJSONObject(0);
        assertFalse(a.optBoolean("textComplete"));
        assertFalse(a.optBoolean("readable"));
        assertEquals(QualityDiagnosis.MAX_ANSWER_CHARS, a.optString("text").length());
        assertEquals("invalid_json", a.optString("refused"));
        // the refused answer is the cause of what is missing, and says so
        final JSONObject w = deviation(caseResult(r, "missing_stays_unknown"), QualityEvaluator.MISSING, "Lagerhelfer").optJSONObject("where");
        assertEquals(QualityEvaluator.ANSWER_REFUSED, w.optString("why"));
    }

    /**
     * An earlier result (evaluated with evaluator 2) is kept as it was when the candidate is checked again: listed with its
     * version and counts, its file unchanged; the new result is evaluated with the current version.
     */
    @Test
    public void anEarlierResultIsKeptAsItWas() throws Exception {
        final JSONObject old = KgJson.obj("status", ModelQualityCheck.DEVIATIONS, "finishedAt", 1L,
                "functional", KgJson.obj("correct", 3, "missing", 14),
                "provenance", KgJson.obj("evaluator", "2", "testset", "kg-reference/2"));
        new QualityResults(this.f.dir).save(this.f.key(), old);
        final File stored = new File(this.f.dir, ModelQualityCheck.sha(this.f.key()) + ".json");
        final byte[] before = Files.readAllBytes(stored.toPath());
        final JSONObject r = run();
        assertEquals(QualityEvaluator.VERSION, r.optJSONObject("provenance").optString("evaluator"));
        final JSONArray earlier = r.optJSONArray("earlier");
        assertEquals(r.optJSONArray("earlier").toString(), 1, earlier.length());
        assertEquals("2", earlier.optJSONObject(0).optString("evaluator"));
        assertEquals(14, earlier.optJSONObject(0).optJSONObject("functional").optInt("missing"));
        final File archived = new File(this.f.dir, earlier.optJSONObject(0).optString("file"));
        assertTrue(archived.isFile());
        assertEquals(new String(before, StandardCharsets.UTF_8), new String(Files.readAllBytes(archived.toPath()), StandardCharsets.UTF_8));
        assertEquals(1, this.q.status().optJSONArray("candidates").optJSONObject(0).optInt("earlierResults"));
        assertNull(this.q.status().optJSONArray("candidates").optJSONObject(0).opt("earlier"));
    }
}
