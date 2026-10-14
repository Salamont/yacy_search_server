package net.yacy.scoutro.knowledge.quality;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.scoutro.knowledge.extract.Vocabulary;

/**
 * The baseline of the evaluation (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 4): what tiers 1 and 2 state is fixed before any
 * answer is looked at. A model that repeats a statement the rules make already changes neither the statements expected of
 * the model nor those given by the rules; the repetition is counted on its own and is not rated. Example data only.
 */
public class QualityBaselineTest {

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

    private static JSONObject caseResult(final JSONObject result, final String caseId) {
        final JSONArray cases = result.optJSONObject("functional").optJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            if (cases.optJSONObject(i).optString("case").equals(caseId)) {
                return cases.optJSONObject(i);
            }
        }
        return null;
    }

    private static Set<String> labels(final JSONArray a, final String field) {
        final Set<String> out = new TreeSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            out.add(a.optJSONObject(i).optString(field));
        }
        return out;
    }

    /** The ideal answer of the own job advertisement plus the employment type the rules read from "in Vollzeit" already. */
    private static String repeatingFullTime(final QualityFixture.Ask a) {
        final String ideal = QualityFixture.ideal(a);
        if (!"own_job_ad".equals(a.caseId)) {
            return ideal;
        }
        try {
            final JSONObject o = new JSONObject(ideal);
            o.optJSONArray("values").put(QualityFixture.value(a.id("Pflegefachkraft (m/w/d)", "e2"), Vocabulary.EMPLOYMENT_TYPE,
                    "eine Pflegefachkraft (m/w/d) in Vollzeit"));
            return o.toString();
        } catch (final org.json.JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The confirmed shift of the stored runs: the rules state "full_time" of the own job advertisement. One reading repeats
     * it, the other does not. Both have the same statements expected of the model and the same ones given by the rules;
     * the repetition is counted as such and is neither a hit of the model nor an unrated statement.
     */
    @Test
    public void repeatingARuleStatementChangesNeitherTheModelsShareNorTheRules() throws Exception {
        final JSONObject without = run();
        this.f.answer = QualityBaselineTest::repeatingFullTime;
        final JSONObject with = run();

        final JSONObject a = caseResult(without, "own_job_ad");
        final JSONObject b = caseResult(with, "own_job_ad");
        final Set<String> rulesA = labels(a.optJSONArray("givenByRules"), "expected");
        final Set<String> rulesB = labels(b.optJSONArray("givenByRules"), "expected");
        boolean fullTime = false;
        for (final String l : rulesA) {
            fullTime |= l.contains(Vocabulary.EMPLOYMENT_TYPE) && l.contains("full_time");
        }
        assertTrue("precondition: the rules state the employment type already: " + rulesA, fullTime);

        assertEquals(b.toString(2), rulesA, rulesB);
        assertEquals(b.toString(2), a.optJSONObject("counts").optInt("expected"), b.optJSONObject("counts").optInt("expected"));
        assertEquals(a.optJSONObject("counts").optInt("givenByRules"), b.optJSONObject("counts").optInt("givenByRules"));
        assertEquals(labels(a.optJSONArray("correct"), "expected"), labels(b.optJSONArray("correct"), "expected"));
        assertEquals(0, a.optJSONObject("counts").optInt("repeatsRules"));
        assertEquals(b.toString(2), 1, b.optJSONObject("counts").optInt("repeatsRules"));
        assertEquals(0, b.optJSONObject("counts").optInt("notRated"));
        assertTrue(b.optJSONArray("repeatsRules").optJSONObject(0).optString("statement").contains("full_time"));
        // the whole run: the same scope, and the same status
        assertEquals(without.optJSONObject("scope").optInt("expectedOfModel"), with.optJSONObject("scope").optInt("expectedOfModel"));
        assertEquals(without.optJSONObject("scope").optInt("givenByRules"), with.optJSONObject("scope").optInt("givenByRules"));
        assertEquals(1, with.optJSONObject("functional").optInt("repeatsRules"));
        assertEquals(without.optString("status"), with.optString("status"));
    }

    /** The expected statements split into the rules' and the model's share by the page alone: the same for any answer. */
    @Test
    public void theBaselineIsTheSameForAnEmptyAndACorrectReading() throws Exception {
        final JSONObject correct = run();
        this.f.answer = a -> QualityFixture.EMPTY;
        final JSONObject empty = run();
        final List<String> ids = new ArrayList<>();
        for (final QualityCases.Case c : QualityCases.CASES) {
            ids.add(c.id);
            final JSONObject x = caseResult(correct, c.id), y = caseResult(empty, c.id);
            assertEquals(c.id, labels(x.optJSONArray("givenByRules"), "expected"), labels(y.optJSONArray("givenByRules"), "expected"));
            assertEquals(c.id, x.optJSONObject("counts").optInt("expected"), y.optJSONObject("counts").optInt("expected"));
        }
        assertEquals(QualityCases.CASES.size(), ids.size());
    }
}
