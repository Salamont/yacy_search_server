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
        // an administrator approves the proposal: a manual decision
        a.review(D, "approve", null, "checked", applier, "admin");
        assertEquals(List.of(false), automatic);
        // 59 further reviewed corrections without error: the one-sided 95 % lower bound passes 0.95
        for (int i = 0; i < 58; i++) {
            final String dom = "d" + i + ".de";
            a.submit(dom, "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.PROPOSE, 10, applier, "x");
            a.review(dom, "approve", null, null, applier, "admin");
        }
        // a new evaluation of a reviewed domain keeps the review as a measurement (history)
        assertEquals("proposed", a.submit("d0.de", "edelsenior", List.of("edelsenior"), newer, "sonstige", false, AutoCorrect.Mode.PROPOSE, 10,
                applier, "x").getString("action"));
        assertEquals(59, a.stats().getInt("reviewed"));
        final JSONObject accepted = a.accept(59, 0.95, "admin");
        assertTrue(accepted.toString(), accepted.getBoolean("accepted"));
        assertEquals(59, accepted.getJSONObject("stats").getInt("reviewed"));
        // now apply acts, automatically, within the daily limit
        final JSONObject done = a.submit("auto.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false, AutoCorrect.Mode.APPLY, 1,
                applier, "x");
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
        assertFalse(again.unavailable());
        // an unreadable state never applies anything
        Files.write(f.toPath(), "{broken".getBytes());
        final AutoCorrect broken = new AutoCorrect(f, this.clock::get);
        assertTrue(broken.unavailable());
        assertEquals("refused:state_unreadable", broken.submit("x.de", "edelsenior", List.of("edelsenior"), strong, "sonstige", false,
                AutoCorrect.Mode.APPLY, 10, applier, "x").getString("action"));
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
