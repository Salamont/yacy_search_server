package net.yacy.scoutro.collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * WP-H: the rules of the automatic collection correction (independent origins, never OSM or one LLM verdict alone, no
 * absence signals, manual decisions win), its modes (off by default, simulate, propose, apply only after an accepted
 * acceptance), fingerprint and hysteresis, the daily limit, the breaker and the statistics (Clopper-Pearson).
 */
public class AutoCorrectTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final String D = "baustoffe-muster.de";
    private final AtomicLong clock = new AtomicLong(java.time.LocalDate.of(2027, 3, 1).atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant().toEpochMilli());

    private static AutoCorrect.Signal signal(final String family, final String verdict, final String collection, final String origin,
            final String revision) {
        final JSONObject o = AutoCorrect.obj("family", family, "verdict", verdict, "collection", collection, "origin", origin, "evidence",
                family + " says " + verdict + " " + collection, "revision", revision);
        return AutoCorrect.Signal.of(o, D);
    }

    private static List<AutoCorrect.Signal> list(final AutoCorrect.Signal... s) {
        return new ArrayList<>(List.of(s));
    }

    private static AutoCorrect.Outcome eval(final List<AutoCorrect.Signal> s) {
        return AutoCorrect.evaluate("edelsenior", List.of("edelsenior"), s, "sonstige", false);
    }

    @Test
    public void onlyIndependentOriginsWithAStructuredOrTextSignalCorrect() {
        // the website's structured data, its text and an LLM reading it are one origin
        AutoCorrect.Outcome o = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.TEXT_FIT, "against", "edelsenior", null, "r1"), signal(AutoCorrect.LLM, "against", "edelsenior", null, "r1")));
        assertEquals(AutoCorrect.REVIEW, o.result);
        assertEquals("one_origin_only", o.reason);
        // OSM and an LLM verdict never suffice, not even together
        o = eval(list(signal(AutoCorrect.LLM, "against", "edelsenior", null, "r1"), signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1")));
        assertEquals(AutoCorrect.REVIEW, o.result);
        assertEquals("no_structured_or_text_signal", o.reason);
        // the website's structured data and the OSM tags: two independent origins
        o = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1")));
        assertEquals(AutoCorrect.CORRECT, o.result);
        assertEquals("sonstige", o.target);
        assertEquals("medium", o.severity);
        assertEquals(2, o.againstOrigins.size());
        // a signal for the current collection contradicts
        o = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"), signal(AutoCorrect.ORIGIN, "for", "edelsenior", "register:x", "t2")));
        assertEquals(AutoCorrect.REVIEW, o.result);
        assertEquals("contradicting_signals", o.reason);
    }

    @Test
    public void aSpecialistTargetNeedsTheSameRuleOtherwiseTheFallback() {
        final AutoCorrect.Outcome o = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"), signal(AutoCorrect.STRUCTURED, "for", "bauteamcheck", null, "r1"),
                signal(AutoCorrect.ORIGIN, "for", "bauteamcheck", "osm:way/1", "t1")));
        assertEquals(AutoCorrect.CORRECT, o.result);
        assertEquals("bauteamcheck", o.target);
        assertEquals("high", o.severity);
        final AutoCorrect.Outcome weak = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"), signal(AutoCorrect.LLM, "for", "bauteamcheck", null, "r1")));
        assertEquals("one origin for the target is not enough: the fallback", "sonstige", weak.target);
    }

    @Test
    public void manualDecisionsMultipleAssignmentsAndAbsenceNeverCorrect() {
        final List<AutoCorrect.Signal> strong = list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"));
        assertEquals(AutoCorrect.NONE, AutoCorrect.evaluate("edelsenior", List.of("edelsenior"), strong, "sonstige", true).result);
        assertEquals("multiple_assignment", AutoCorrect.evaluate("edelsenior", List.of("edelsenior", "bauteamcheck"), strong, "sonstige", false).reason);
        assertEquals(AutoCorrect.NONE, AutoCorrect.evaluate("sonstige", List.of("sonstige"), strong, "sonstige", false).result);
        for (final String kind : new String[] {"missing_industry", "crawl_error"}) {
            try {
                AutoCorrect.Signal.of(AutoCorrect.obj("family", "structured", "verdict", "against", "collection", "edelsenior", "kind", kind,
                        "evidence", "nothing found"), D);
                fail(kind + " is no signal");
            } catch (final IllegalArgumentException e) {
                assertEquals("kind", e.getMessage());
            }
        }
        try {
            AutoCorrect.Signal.of(AutoCorrect.obj("family", "origin", "verdict", "against", "collection", "edelsenior", "origin", "website:x.de",
                    "evidence", "x"), D);
            fail("an origin signal cannot claim to be the website");
        } catch (final IllegalArgumentException e) {
            assertEquals("origin", e.getMessage());
        }
    }

    @Test
    public void theModesTheFingerprintTheBreakerAndTheAcceptance() throws Exception {
        final File f = new File(this.tmp.getRoot(), "autocorrect.json");
        final AutoCorrect a = new AutoCorrect(f, this.clock::get);
        final AtomicInteger applied = new AtomicInteger();
        final List<Boolean> automatic = new ArrayList<>();
        final AutoCorrect.Applier applier = (domain, from, target, evidence, auto, actor) -> {
            applied.incrementAndGet();
            automatic.add(auto);
            return AutoCorrect.obj("id", "kcd_test" + applied.get(), "target", target);
        };
        final List<AutoCorrect.Signal> strong = list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"));
        // off: nothing recorded
        assertEquals("none", a.submit(D, "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.OFF, 10, applier, "x")
                .getString("action"));
        assertFalse(f.exists());
        // simulate: recorded only
        assertEquals("recorded", a.submit(D, "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.SIMULATE, 10,
                applier, "x").getString("action"));
        assertEquals("the same fingerprint changes nothing twice", "unchanged", a.submit(D, "edelsenior", List.of("edelsenior"), strong,
                "sonstige", false, AutoCorrect.Mode.APPLY, 10, applier, "x").getString("action"));
        // apply without an accepted acceptance only proposes
        final List<AutoCorrect.Signal> newer = list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r2"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"));
        final JSONObject held = a.submit(D, "edelsenior", List.of("edelsenior"), newer, "sonstige", false, AutoCorrect.Mode.APPLY, 10, applier, "x");
        assertEquals("proposed", held.getString("action"));
        assertEquals("not_accepted", held.getJSONObject("case").getString("held"));
        assertEquals(0, applied.get());
        // the acceptance refuses without enough reviewed cases
        assertFalse(a.accept(59, 0.95, "admin").getBoolean("accepted"));
        // an administrator approves the proposal: a manual decision; chosen by hand, it is no part of the sample
        a.review(D, "approve", null, "checked", applier, "admin");
        assertEquals(List.of(false), automatic);
        assertEquals(1, a.stats().getJSONObject("all").getInt("reviewed"));
        assertEquals(0, a.stats().getJSONObject("sample").getInt("reviewed"));
        for (int i = 0; i < 59; i++) {
            a.submit("d" + i + ".de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.PROPOSE, 10, applier, "x");
        }
        // the sample is drawn at random from the open corrections; a drawn case not yet reviewed blocks the acceptance
        final JSONObject draw = a.sample(59, "admin");
        assertEquals(59, draw.getInt("drawn"));
        assertEquals(59, draw.getInt("population"));
        assertTrue(a.accept(59, 0.95, "admin").getJSONArray("missing").toString().contains("not reviewed"));
        for (int i = 0; i < 59; i++) a.review("d" + i + ".de", "correct", null, null, applier, "admin");
        assertEquals("judging a proposal applies nothing", List.of(false), automatic);
        // a new evaluation of a reviewed domain keeps the review as a measurement (history)
        assertEquals("proposed", a.submit("d0.de", "edelsenior", List.of("edelsenior"), newer, "sonstige", false, AutoCorrect.Mode.PROPOSE, 10,
                applier, "x").getString("action"));
        final JSONObject sampled = a.stats().getJSONObject("sample");
        assertEquals(59, sampled.getInt("reviewed"));
        assertEquals(0, sampled.getInt("pending"));
        assertEquals("interim_95", sampled.getString("stage"));
        assertEquals("299 error-free reviews for the 99 % target", 240, sampled.getInt("reviewsForTarget"));
        // the 99 % target is the default acceptance; 0.95 is accepted only as the interim stage, never as the operator release
        assertFalse(a.accept(299, 0.99, "admin").getBoolean("accepted"));
        final JSONObject accepted = a.accept(59, 0.95, "admin");
        assertTrue(accepted.toString(), accepted.getBoolean("accepted"));
        assertEquals("interim_95", accepted.getString("stage"));
        assertFalse(accepted.getBoolean("operatorRelease"));
        assertEquals(59, accepted.getJSONObject("stats").getJSONObject("sample").getInt("reviewed"));
        // now apply acts, automatically, within the daily limit
        final JSONObject done = a.submit("auto.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY, 1,
                0.0, applier, "x");
        assertEquals("applied", done.getString("action"));
        assertTrue(automatic.get(automatic.size() - 1));
        assertEquals("daily_limit", a.submit("auto2.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY,
                1, applier, "x").getJSONObject("case").getString("held"));
        // hysteresis: the same revisions with another signal set change nothing on an applied domain
        assertEquals("unchanged", a.submit("auto.de", "edelsenior", List.of("edelsenior"), list(strong.get(0), strong.get(1),
                signal(AutoCorrect.LLM, "against", "edelsenior", null, "r1")), "sonstige", false, AutoCorrect.Mode.APPLY, 10, applier, "x")
                .getString("action"));
        // a case marked wrong opens the breaker: apply proposes again
        a.review("auto.de", "wrong", null, "it is a care provider", applier, "admin");
        this.clock.addAndGet(86_400_000L);
        assertEquals("breaker_open", a.submit("auto3.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY,
                10, applier, "x").getJSONObject("case").getString("held"));
        a.resetBreaker("admin");
        // the state survives a restart
        final AutoCorrect again = new AutoCorrect(f, this.clock::get);
        assertEquals(1, again.stats().getJSONObject("errorsBySeverity").getInt("medium"));
        assertEquals(1, again.stats().getJSONObject("all").getJSONObject("errorsBySeverity").getInt("medium"));
        assertFalse(again.unavailable());
        // an unreadable state never applies anything
        Files.write(f.toPath(), "{broken".getBytes());
        final AutoCorrect broken = new AutoCorrect(f, this.clock::get);
        assertTrue(broken.unavailable());
        assertEquals("refused:state_unreadable", broken.submit("x.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false,
                AutoCorrect.Mode.APPLY, 10, applier, "x").getString("action"));
    }

    @Test
    public void signalsOfOneSourceCountOnce() throws Exception {
        // an entry read from the site itself (its imprint) is the website, not a second origin
        final AutoCorrect.Signal imprint = AutoCorrect.Signal.of(AutoCorrect.obj("family", "origin", "verdict", "against", "collection",
                "edelsenior", "origin", "register:" + D + "/impressum", "evidence", "HRB from the imprint"), D);
        assertEquals("website:" + D, imprint.origin);
        assertEquals("one_origin_only", eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"), imprint)).reason);
        // an origin signal that repeats the website's evidence is a copy
        final AutoCorrect.Signal site = AutoCorrect.Signal.of(AutoCorrect.obj("family", "structured", "verdict", "against", "collection",
                "edelsenior", "evidence", "JSON-LD @type RoofingContractor"), D);
        final AutoCorrect.Signal copy = AutoCorrect.Signal.of(AutoCorrect.obj("family", "origin", "verdict", "against", "collection",
                "edelsenior", "origin", "directory:gelbe-seiten", "evidence", "JSON-LD  @type RoofingContractor"), D);
        final AutoCorrect.Outcome o = eval(list(site, copy));
        assertEquals("one_origin_only", o.reason);
        assertTrue(o.dependencies.toString(), o.dependencies.get(0).contains("the same evidence"));
        // two OSM objects are one source system
        final AutoCorrect.Outcome osm = eval(list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"), signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:node/2", "t1")));
        assertEquals(AutoCorrect.CORRECT, osm.result);
        assertEquals(java.util.Set.of("website:" + D, "osm"), osm.againstOrigins);
        assertTrue(osm.dependencies.toString(), osm.toJson().getJSONArray("dependencies").toString().contains("one source system"));
    }

    @Test
    public void theTargetOf99PercentNeeds299ErrorFreeSampledReviewsAndTheAuditKeepsWatching() throws Exception {
        final File f = new File(this.tmp.getRoot(), "autocorrect.json");
        final AutoCorrect a = new AutoCorrect(f, this.clock::get, new java.util.Random(7));
        final AtomicInteger applied = new AtomicInteger();
        final AutoCorrect.Applier applier = (domain, from, target, evidence, auto, actor) -> AutoCorrect.obj("id", "kcd_" + applied.incrementAndGet());
        final List<AutoCorrect.Signal> strong = list(signal(AutoCorrect.STRUCTURED, "against", "edelsenior", null, "r1"),
                signal(AutoCorrect.ORIGIN, "against", "edelsenior", "osm:way/1", "t1"));
        for (int i = 0; i < 400; i++) {
            a.submit("s" + i + ".de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.SIMULATE, 10, applier, "x");
        }
        // the cases a reviewer would choose are not the sample: 299 of 400 are drawn at random
        final JSONObject draw = a.sample(299, "admin");
        assertEquals(400, draw.getInt("population"));
        final java.util.Set<String> drawn = new java.util.TreeSet<>();
        for (int i = 0; i < draw.getJSONArray("domains").length(); i++) drawn.add(draw.getJSONArray("domains").getString(i));
        assertEquals(299, drawn.size());
        assertTrue("a uniform draw is not the first 299", !drawn.equals(new java.util.TreeSet<>(java.util.stream.IntStream.range(0, 299)
                .mapToObj(i -> "s" + i + ".de").toList())));
        int n = 0;
        for (final String d : drawn) {
            // one error among the drawn cases: 299 reviews are no longer enough for 99 %
            a.review(d, n++ == 0 ? "wrong" : "correct", null, null, applier, "admin");
        }
        assertEquals("judgements apply nothing", 0, applied.get());
        JSONObject s = a.stats().getJSONObject("sample");
        assertEquals(299, s.getInt("reviewed"));
        assertEquals(298, s.getInt("right"));
        assertEquals("interim_95", s.getString("stage"));
        assertTrue(s.toString(), s.getInt("reviewsForTarget") > 150);
        assertEquals("a wrong judgement of a simulated case opens no breaker", "not_accepted", a.submit("t.de", "edelsenior", List.of("edelsenior"),
                strong, "sonstige", false, AutoCorrect.Mode.APPLY, 10, applier, "x").getJSONObject("case").getString("held"));
        final JSONObject refused = a.accept(299, 0.99, "admin");
        assertFalse(refused.getBoolean("accepted"));
        // a fresh measurement without the error
        final File g = new File(this.tmp.getRoot(), "fresh.json");
        final AutoCorrect b = new AutoCorrect(g, this.clock::get, new java.util.Random(11));
        for (int i = 0; i < 299; i++) {
            b.submit("s" + i + ".de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.SIMULATE, 10, applier, "x");
        }
        b.sample(299, "admin");
        for (int i = 0; i < 299; i++) b.review("s" + i + ".de", "correct", null, null, applier, "admin");
        final JSONObject accepted = b.accept(299, 0.99, "admin");
        assertTrue(accepted.toString(), accepted.getBoolean("accepted"));
        assertEquals("target_99", accepted.getString("stage"));
        assertTrue(accepted.getBoolean("operatorRelease"));
        assertEquals(AutoCorrect.RULES_VERSION, accepted.getString("rulesVersion"));
        // after the acceptance every automatic correction is drawn for the audit at the configured rate; a backlog stops apply
        for (int i = 0; i < AutoCorrect.MAX_AUDIT_BACKLOG; i++) {
            assertEquals("applied", b.submit("a" + i + ".de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY,
                    1000, 1.0, applier, "x").getString("action"));
        }
        s = b.stats().getJSONObject("audit");
        assertEquals(AutoCorrect.MAX_AUDIT_BACKLOG, s.getInt("pending"));
        assertEquals("audit_backlog", b.submit("late.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY,
                1000, 1.0, applier, "x").getJSONObject("case").getString("held"));
        // an audited correction marked wrong opens the breaker
        b.review("a0.de", "wrong", null, null, applier, "admin");
        assertEquals("breaker_open", b.submit("later.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY,
                1000, 1.0, applier, "x").getJSONObject("case").getString("held"));
    }

    @Test
    public void reviewsNeededFollowTheExactBinomialBound() {
        assertEquals(299, AutoCorrect.reviewsNeeded(0, 0, 0.99));
        assertEquals(59, AutoCorrect.reviewsNeeded(0, 0, 0.95));
        assertEquals(0, AutoCorrect.reviewsNeeded(299, 299, 0.99));
        // one error: 473 reviews in all; two errors: 628
        assertEquals(473, AutoCorrect.reviewsNeeded(0, 1, 0.99) + 1);
        assertEquals(628, AutoCorrect.reviewsNeeded(0, 2, 0.99) + 2);
        assertTrue(AutoCorrect.clopperPearsonLower(471, 473, 0.05) < 0.99 || AutoCorrect.clopperPearsonLower(472, 473, 0.05) >= 0.99);
    }

    @Test
    public void clopperPearsonLowerBounds() {
        assertEquals(Math.pow(0.05, 1.0 / 59), AutoCorrect.clopperPearsonLower(59, 59, 0.05), 1e-12);
        assertTrue(AutoCorrect.clopperPearsonLower(59, 59, 0.05) > 0.95);
        assertTrue(AutoCorrect.clopperPearsonLower(58, 58, 0.05) < 0.95);
        // 99 of 100: one-sided 95 % lower bound 0.9534 (exact binomial)
        assertEquals(0.9534, AutoCorrect.clopperPearsonLower(99, 100, 0.05), 5e-4);
        // 299 of 299 for 0.99
        assertTrue(AutoCorrect.clopperPearsonLower(299, 299, 0.05) >= 0.99);
        assertTrue(AutoCorrect.clopperPearsonLower(298, 298, 0.05) < 0.99);
        assertEquals(0.0, AutoCorrect.clopperPearsonLower(0, 10, 0.05), 0.0);
    }
}
