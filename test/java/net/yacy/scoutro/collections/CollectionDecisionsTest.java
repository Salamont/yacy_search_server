package net.yacy.scoutro.collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * How a durable decision turns a crawl profile's collections into a page's collections, and which crawl starts it
 * refuses: the fallback collection only through a decision, exact and transform decisions, host and path scopes, and
 * a store that cannot be read blocking every write.
 */
public class CollectionDecisionsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Path dir;
    private Path marker;
    private CollectionDecisions decisions;

    @Before
    public void open() throws Exception {
        this.dir = this.tmp.newFolder("collections").toPath();
        this.marker = this.tmp.newFolder("settings").toPath().resolve("marker");
        this.decisions = new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", false, () -> 1_800_000_000_000L);
    }

    private static Map<String, Pattern> profile(final String... names) {
        final Map<String, Pattern> m = new LinkedHashMap<>();
        for (final String n : names) m.put(n, Pattern.compile(".*"));
        return m;
    }

    private CollectionDecision decide(final String domain, final String host, final String path, final CollectionDecision.Mode mode,
            final List<String> collections, final List<String> denied) throws Exception {
        final CollectionDecision.Builder b = new CollectionDecision.Builder();
        b.id = CollectionDecisions.newId();
        b.domain = domain;
        b.host = host;
        b.pathPrefix = path;
        b.mode = mode;
        b.collections = collections;
        b.denied = denied;
        b.reason = "fachlich_falsch";
        b.decidedAt = 1L;
        return this.decisions.put(b.build(), "decide");
    }

    private List<String> names(final String url, final String host, final String path, final Map<String, Pattern> p) {
        return List.copyOf(this.decisions.resolve(url, host, path, p).keySet());
    }

    @Test
    public void withoutDecisionTheProfileStaysAndIsTheSameMap() {
        final Map<String, Pattern> p = profile("bauteamcheck-web");
        assertSame(p, this.decisions.resolve("https://drolshagens.de/", "drolshagens.de", "/", p));
    }

    @Test
    public void fallbackNeverComesFromAProfile() {
        assertEquals(List.of("stackfinder-web"),
                names("https://a.de/", "a.de", "/", profile("stackfinder-web", "sonstige")));
        try {
            this.decisions.resolve("https://new.de/", "new.de", "/", profile("sonstige"));
            fail("a new page entered the fallback collection without a decision");
        } catch (final CollectionDecisions.Blocked expected) {
            assertEquals("fallback_without_decision", expected.code);
        }
    }

    @Test
    public void exactDecisionWinsOverEveryProfile() throws Exception {
        decide("drolshagens.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        assertEquals(List.of("sonstige"), names("https://drolshagens.de/products/nuss", "drolshagens.de", "/products/nuss",
                profile("bauteamcheck-web")));
        assertEquals(List.of("sonstige"), names("https://www.drolshagens.de/", "www.drolshagens.de", "/", profile("edelsenior-web")));
        // a different registrable domain is not covered
        assertEquals(List.of("bauteamcheck-web"), names("https://notdrolshagens.de/", "notdrolshagens.de", "/", profile("bauteamcheck-web")));
        assertEquals(List.of("bauteamcheck-web"), names("https://drolshagens.com/", "drolshagens.com", "/", profile("bauteamcheck-web")));
    }

    @Test
    public void transformReplacesDeniedAndKeepsOtherMemberships() throws Exception {
        decide("yowea.com", null, null, CollectionDecision.Mode.TRANSFORM, List.of("checkthecoach-web"), List.of("stackfinder-web"));
        assertEquals(List.of("checkthecoach-web"), names("https://yowea.com/", "yowea.com", "/", profile("stackfinder-web")));
        assertEquals(List.of("partner-web", "checkthecoach-web"),
                names("https://yowea.com/x", "yowea.com", "/x", profile("stackfinder-web", "partner-web")));
        // a page outside the denied collection keeps its own collections
        assertEquals(List.of("partner-web"), names("https://yowea.com/p", "yowea.com", "/p", profile("partner-web")));
    }

    @Test
    public void addOnlyTransformKeepsADecidedMultipleAssignment() throws Exception {
        decide("multi.de", null, null, CollectionDecision.Mode.TRANSFORM, List.of("edelsenior-web"), List.of());
        assertEquals(List.of("stackfinder-web", "edelsenior-web"), names("https://multi.de/", "multi.de", "/", profile("stackfinder-web")));
    }

    @Test
    public void mostSpecificScopeWins() throws Exception {
        decide("plattform.de", null, null, CollectionDecision.Mode.EXACT, List.of("stackfinder-web"), List.of());
        decide("plattform.de", "pflege.plattform.de", null, CollectionDecision.Mode.EXACT, List.of("edelsenior-web"), List.of());
        decide("plattform.de", "www.plattform.de", "/bau/", CollectionDecision.Mode.EXACT, List.of("bauteamcheck-web"), List.of());
        assertEquals(List.of("stackfinder-web"), names("https://www.plattform.de/it", "www.plattform.de", "/it", profile("x-web")));
        assertEquals(List.of("bauteamcheck-web"), names("https://www.plattform.de/bau/a", "www.plattform.de", "/bau/a", profile("x-web")));
        assertEquals(List.of("edelsenior-web"), names("https://pflege.plattform.de/bau/a", "pflege.plattform.de", "/bau/a", profile("x-web")));
    }

    @Test
    public void oneActiveDecisionPerScope() throws Exception {
        decide("a.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        try {
            decide("a.de", null, null, CollectionDecision.Mode.EXACT, List.of("edelsenior-web"), List.of());
            fail("two active decisions for one scope");
        } catch (final CollectionDecisions.Refusal expected) {
            assertEquals("decision_exists", expected.code);
        }
    }

    @Test
    public void revokedDecisionNoLongerApplies() throws Exception {
        final CollectionDecision d = decide("a.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        final CollectionDecision.Builder b = d.toBuilder();
        b.status = CollectionDecision.Status.REVOKED;
        b.revision = 2;
        this.decisions.put(b.build(), "revoke");
        assertEquals(List.of("bauteamcheck-web"), names("https://a.de/", "a.de", "/", profile("bauteamcheck-web")));
        assertNull(this.decisions.find("a.de", "/"));
    }

    @Test
    public void decisionsSurviveARestart() throws Exception {
        decide("drolshagens.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        final CollectionDecisions restarted = new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", false, () -> 2L);
        assertEquals(List.of("sonstige"), List.copyOf(restarted.resolve("https://drolshagens.de/", "drolshagens.de", "/",
                profile("bauteamcheck-web")).keySet()));
    }

    @Test
    public void unreadableDecisionsBlockEveryWriteAndCrawlButNotThePeer() throws Exception {
        decide("drolshagens.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        try (java.util.stream.Stream<Path> files = Files.list(this.dir)) {
            files.forEach(p -> p.toFile().delete());
        }
        final CollectionDecisions damaged = new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", false, () -> 3L);
        assertEquals(DecisionStore.Health.UNAVAILABLE, damaged.state().health);
        for (final String url : new String[] {"https://drolshagens.de/", "https://other.de/"}) {
            try {
                damaged.resolve(url, java.net.URI.create(url).getHost(), "/", profile("bauteamcheck-web"));
                fail("write allowed without reliable decisions: " + url);
            } catch (final CollectionDecisions.Blocked expected) {
                assertEquals("decisions_unavailable", expected.code);
            }
        }
        try {
            damaged.checkCrawl("drolshagens.de", "bauteamcheck-web", true);
            fail("crawl allowed without reliable decisions");
        } catch (final CollectionDecisions.Refusal expected) {
            assertEquals(503, expected.status);
        }
        assertTrue(damaged.status().toString().contains("unavailable"));
        // an administrator accepts the readable (empty) state: writes work again
        damaged.recover("test");
        assertEquals(List.of("bauteamcheck-web"), List.copyOf(damaged.resolve("https://other.de/", "other.de", "/",
                profile("bauteamcheck-web")).keySet()));
    }

    @Test
    public void crawlStartsFollowTheDecision() throws Exception {
        // no decision: only the fallback collection is refused as a crawl target
        this.decisions.checkCrawl("new.de", "bauteamcheck-web", true);
        refused(() -> this.decisions.checkCrawl("new.de", "sonstige", false), "collection_fallback_only");
        decide("drolshagens.de", null, null, CollectionDecision.Mode.EXACT, List.of("sonstige"), List.of());
        // Discovery's recrawl of a domain corrected into the fallback collection is off by default
        refused(() -> this.decisions.checkCrawl("drolshagens.de", "bauteamcheck-web", true), "collection_decided");
        refused(() -> this.decisions.checkCrawl("www.drolshagens.de", "stackfinder-web", false), "collection_decided");
        // an administrator may refresh the corrected pages in their decided collection
        this.decisions.checkCrawl("drolshagens.de", "sonstige", false);
        final CollectionDecisions recrawl = new CollectionDecisions(new DecisionStore(this.dir, this.marker), "sonstige", true, () -> 4L);
        recrawl.checkCrawl("drolshagens.de", "bauteamcheck-web", true);
        refused(() -> recrawl.checkCrawl("drolshagens.de", "bauteamcheck-web", false), "collection_decided");
        // a decided multiple assignment is no conflict for either collection
        decide("multi.de", null, null, CollectionDecision.Mode.EXACT, List.of("stackfinder-web", "edelsenior-web"), List.of());
        this.decisions.checkCrawl("multi.de", "stackfinder-web", true);
        this.decisions.checkCrawl("multi.de", "edelsenior-web", true);
        // transform: the denied collection is refused, another one is fine
        decide("yowea.com", null, null, CollectionDecision.Mode.TRANSFORM, List.of("checkthecoach-web"), List.of("stackfinder-web"));
        refused(() -> this.decisions.checkCrawl("yowea.com", "stackfinder-web", true), "collection_decided");
        this.decisions.checkCrawl("yowea.com", "checkthecoach-web", true);
    }

    interface Call {
        void run() throws Exception;
    }

    private static void refused(final Call c, final String code) throws Exception {
        try {
            c.run();
            fail("not refused: " + code);
        } catch (final CollectionDecisions.Refusal expected) {
            assertEquals(code, expected.code);
        }
    }

    @Test
    public void jsonRoundTripKeepsEveryField() throws Exception {
        final CollectionDecision d = decide("plattform.de", "www.plattform.de", "/bau/", CollectionDecision.Mode.EXACT,
                List.of("bauteamcheck-web"), List.of());
        final CollectionDecision back = CollectionDecision.fromJson(d.toJson());
        assertEquals(d.toJson().toString(), back.toJson().toString());
    }

    @Test
    public void invalidScopesAreRejected() {
        for (final String[] bad : new String[][] {{"a.de", "b.de", null}, {"a.de", null, "/x"}, {"a.de", "a.de", "x"}, {"A", null, null}}) {
            final CollectionDecision.Builder b = new CollectionDecision.Builder();
            b.id = CollectionDecisions.newId();
            b.domain = bad[0];
            b.host = bad[1];
            b.pathPrefix = bad[2];
            b.collections = List.of("sonstige");
            b.reason = "x";
            try {
                b.build();
                fail("accepted " + String.join(",", java.util.Arrays.asList(bad)));
            } catch (final IllegalArgumentException expected) {
                // invalid scope
            }
        }
    }
}
