package net.yacy.scoutro.knowledge.quality;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.quality.QualityCases.Case;
import net.yacy.scoutro.knowledge.quality.QualityCases.Statement;
import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.sync.LlmReading;

/**
 * The reference pages (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 3) are fit to rate a model: the queue would ask the model about
 * each of them in one request; each expects something of the model that the rules do not state already; their names and
 * passages are on the page; their organisations and domains are invented; and the ideal reading of each passes while an
 * empty one does not.
 */
public class QualityCasesTest {

    private static final List<String> CATEGORIES = List.of("own_job_ad", "intermediary", "job_portal", "team_vs_service",
            "page_tool_vs_product", "navigation_next_to_content", "price_with_service", "software_competence_vs_internal_use",
            "contradictory_sources", "missing_stays_unknown");

    @Test
    public void everyCategoryOfTheTaskHasItsPage() {
        final List<String> ids = new ArrayList<>();
        for (final Case c : QualityCases.CASES) {
            ids.add(c.id);
        }
        assertEquals(CATEGORIES, ids);
        assertEquals(ids.size(), new HashSet<>(ids).size());
    }

    @Test
    public void theQueueWouldAskTheModelAboutEveryPageInOneRequest() throws Exception {
        final KgConfig cfg = QualityFixture.settings();
        assertTrue(cfg.problems().toString(), cfg.valid());
        for (final Case c : QualityCases.CASES) {
            final LlmReading.Page p = LlmReading.page(cfg, ModelQualityCheck.doc(c), c.text);
            assertTrue(c.id + " is no candidate page", p.candidate);
            assertEquals(c.id, 1, p.chunks.size());
            assertFalse(c.id, p.cut);
            final RequestBudget.Plan plan = p.reading.plan(new RequestBudget(16384, 2048, LlmExtractor.SYSTEM_PROMPT, LlmExtractor.SCHEMA), p.chunks);
            assertFalse(c.id, plan.refused);
            assertEquals(c.id, 1, plan.parts.size());
            assertTrue(c.id + ": jobs are read in the pages' collection", cfg.jobsFor(p.doc.collections));
        }
    }

    @Test
    public void everyPageExpectsSomethingOfTheModelThatTheRulesDoNotStateAlready() throws Exception {
        final KgConfig cfg = QualityFixture.settings();
        for (final Case c : QualityCases.CASES) {
            final LlmReading.Page p = LlmReading.page(cfg, ModelQualityCheck.doc(c), c.text);
            final JSONObject e = QualityEvaluator.evaluate(c, p.base, new Extraction(100), List.of());
            assertTrue(c.id + " expects nothing of the model", e.optJSONObject("counts").optInt("expected") > 0);
            assertTrue(c.id + ": an empty reading must deviate", QualityEvaluator.deviates(e));
            assertTrue(c.id, e.optBoolean("emptyReading"));
        }
    }

    @Test
    public void namesAndPassagesAreOnThePage() {
        for (final Case c : QualityCases.CASES) {
            final String page = Normalizers.key(c.text + " " + c.title);
            final List<Statement> all = new ArrayList<>(c.expected);
            all.addAll(c.forbidden);
            for (final Statement s : c.expected) {
                assertTrue(c.id + ": " + s.label(), s.subject.names.stream().anyMatch(n -> page.contains(Normalizers.key(n))));
                if (s.evidence != null) {
                    assertTrue(c.id + ": " + s.evidence, c.text.contains(s.evidence));
                }
                if (s.object != null && !s.object.names.isEmpty()) {
                    assertTrue(c.id + ": " + s.label(), s.object.names.stream().anyMatch(n -> page.contains(Normalizers.key(n))));
                }
                assertTrue(c.id + ": every expectation says why", s.reason != null && !s.reason.isEmpty());
            }
            for (final Statement s : c.allowed) {
                assertTrue(c.id + ": " + s.label(), s.subject.names.stream().anyMatch(n -> page.contains(Normalizers.key(n))));
                if (s.object != null && !s.object.names.isEmpty()) {
                    assertTrue(c.id + ": " + s.label(), s.object.names.stream().anyMatch(n -> page.contains(Normalizers.key(n))));
                }
                assertTrue(c.id + ": every allowed statement says why", s.reason != null && !s.reason.isEmpty());
            }
            for (final Statement s : c.forbidden) {
                assertTrue(c.id + ": every forbidden statement says why", s.reason != null && !s.reason.isEmpty());
            }
        }
    }

    /** An allowed statement never contradicts the page's expectations: no forbidden statement matches what it allows. */
    @Test
    public void allowedStatementsAreNeitherExpectedNorForbidden() {
        for (final Case c : QualityCases.CASES) {
            for (final Statement a : c.allowed) {
                for (final Statement s : c.forbidden) {
                    if (s.kind.equals(a.kind) && s.predicate.equals(a.predicate) && s.subject.names.equals(a.subject.names)
                            && (s.object == null || a.object == null || s.object.names.equals(a.object.names))) {
                        assertTrue(c.id + ": " + a.label() + " is forbidden as " + s.label(),
                                Statement.VALUE.equals(a.kind) && s.value.except != null && s.value.except.contains(a.value.amount));
                    }
                }
                for (final Statement e : c.expected) {
                    assertFalse(c.id + ": " + a.label() + " is expected already", e.label().equals(a.label()));
                }
            }
        }
    }

    @Test
    public void thePagesAreInventedAndUseReservedDomains() throws Exception {
        final Set<String> hosts = new HashSet<>();
        for (final Case c : QualityCases.CASES) {
            final String host = new URI(c.url).getHost();
            assertTrue(c.url, host.endsWith(".example"));
            hosts.add(host);
            for (final String word : List.of("@", "http://", "+49", "Tel.")) {
                assertFalse(c.id + " contains contact data", c.text.contains(word));
            }
        }
        assertTrue(hosts.size() >= 8);
    }

    @Test
    public void theTestSetIsVersionedAndItsJsonComplete() {
        final JSONObject j = QualityCases.json();
        assertEquals(QualityCases.ID, j.optString("id"));
        assertEquals(QualityCases.VERSION, j.optString("version"));
        assertEquals(QualityEvaluator.VERSION, j.optString("evaluator"));
        assertEquals(QualityCases.CASES.size(), j.optJSONArray("cases").length());
        final JSONObject first = j.optJSONArray("cases").optJSONObject(0);
        assertTrue(first.optJSONArray("expected").length() > 0);
        assertTrue(first.optJSONArray("allowed").length() > 0);
        assertTrue(first.optJSONArray("forbidden").length() > 0);
    }

    /** Normalised comparison: case, punctuation and spacing of a name do not matter, the type does. */
    @Test
    public void namesAreComparedNormalised() {
        final QualityCases.Ref r = new QualityCases.Ref("service", "Beispiel-ERP-Beratung");
        assertTrue(QualityEvaluator.named(r, "service", "beispiel erp  beratung"));
        assertTrue(QualityEvaluator.named(r, "service", "BEISPIEL-ERP-BERATUNG."));
        assertFalse(QualityEvaluator.named(r, "organization", "Beispiel-ERP-Beratung"));
        assertFalse(QualityEvaluator.named(r, "service", "Beispiel-ERP"));
        assertTrue(QualityEvaluator.named(new QualityCases.Ref(QualityCases.ANY, "Muster Office"), "organization", "Muster Office"));
    }

    /** A price matches its amount, currency, unit and VAT condition; another amount is what a forbidden price means. */
    @Test
    public void pricesAreComparedByAmountUnitAndCondition() {
        final String price = "{\"amount\":\"28.50\",\"currency\":\"EUR\",\"kind\":\"fixed\",\"unit\":\"hour\",\"vat\":\"excl\"}";
        assertTrue(QualityEvaluator.valueMatches(QualityCases.Value.price("28.5", "EUR", "hour", "excl"), "price", price));
        assertFalse(QualityEvaluator.valueMatches(QualityCases.Value.price("28.50", "EUR", "day", "excl"), "price", price));
        assertFalse(QualityEvaluator.valueMatches(QualityCases.Value.price("28.50", "EUR", "hour", "incl"), "price", price));
        assertFalse(QualityEvaluator.valueMatches(QualityCases.Value.amountOtherThan("28.50"), "price", price));
        assertTrue(QualityEvaluator.valueMatches(QualityCases.Value.amountOtherThan("4.20"), "price", price));
        assertTrue(QualityEvaluator.valueMatches(QualityCases.Value.anyValue(), "salary", "{}"));
        assertTrue(QualityEvaluator.valueMatches(QualityCases.Value.code("full_time"), "employment_type", "full_time"));
    }
}
