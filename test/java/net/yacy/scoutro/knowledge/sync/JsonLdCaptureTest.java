package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import net.yacy.cora.document.id.DigestURL;
import net.yacy.document.VocabularyScraper;
import net.yacy.document.parser.htmlParser;
import net.yacy.document.parser.html.ContentScraper;
import net.yacy.document.parser.html.TagValency;

/**
 * The bounded JSON-LD capture in YaCy's HTML parser (plan 6.2): only while
 * active, at most the configured blocks and bytes, a block that would exceed
 * them is dropped whole, invalid or irrelevant blocks are not kept, and the
 * field is written only for followed collections. A paused capture changes
 * nothing for the parse and records the document as skipped.
 */
public class JsonLdCaptureTest {

    private static final String ORG = "{\"@context\":\"https://schema.org\",\"@type\":\"Organization\",\"name\":\"Muster Pflege gGmbH\"}";
    private static final String NURSING = "{\"@type\":\"NursingHome\",\"name\":\"Haus am See\"}";
    private static final String RECIPE = "{\"@type\":\"Recipe\",\"name\":\"Apfelkuchen\"}";
    private static final String BROKEN = "{\"@type\":\"Organization\",\"name\":";

    @Before
    @After
    public void reset() {
        JsonLdCapture.clear();
    }

    private static String page(final String... blocks) {
        final StringBuilder sb = new StringBuilder("<html><head><title>Muster</title>");
        for (final String b : blocks) {
            sb.append("<script type=\"application/ld+json\">").append(b).append("</script>");
        }
        sb.append("<script>var x = 1;</script></head><body><p>Impressum der Muster Pflege gGmbH</p></body></html>");
        return sb.toString();
    }

    private static ContentScraper parse(final String html) throws Exception {
        return htmlParser.parseToScraper(new DigestURL("https://www.muster.de/"), "UTF-8", TagValency.EVAL, Collections.emptySet(),
                new VocabularyScraper(), 0, html, 100, 100);
    }

    @Test
    public void offCollectsNothingAndTheParseIsUnchanged() throws Exception {
        final ContentScraper s = parse(page(ORG));
        assertFalse(s.ldJsonScraped());
        assertNull(s.getLdJsonBlocks());
        assertTrue(s.getText().contains("Impressum"));
        assertNull(JsonLdCapture.field("AAAAAAhost01", List.of("c1"), s.getLdJsonBlocks(), s.ldJsonScraped()));
        assertFalse("off is not a skip", JsonLdCapture.wasSkipped("AAAAAAhost01"));
    }

    @Test
    public void activeKeepsRelevantValidBlocksWithinTheLimits() throws Exception {
        JsonLdCapture.activate(2, 1024, false, Set.of("c1"));
        final ContentScraper s = parse(page(RECIPE, BROKEN, ORG, NURSING, ORG));
        assertTrue(s.ldJsonScraped());
        final JsonLdCapture.Blocks b = s.getLdJsonBlocks();
        assertNotNull(b);
        assertEquals("irrelevant and broken blocks are not kept; the third relevant one exceeds the block limit",
                List.of(ORG, NURSING), b.list());
        assertEquals(1, b.dropped());
        // a block that would exceed the byte limit is dropped whole, never cut
        JsonLdCapture.activate(8, ORG.length() + 10, false, Set.of("c1"));
        final JsonLdCapture.Blocks b2 = parse(page(ORG, NURSING)).getLdJsonBlocks();
        assertEquals(List.of(ORG), b2.list());
        assertEquals(1, b2.dropped());
        // the field only for followed collections
        assertNull(JsonLdCapture.field("BBBBBBhost01", List.of("c2"), b, true));
        assertEquals(List.of(ORG, NURSING), JsonLdCapture.field("AAAAAAhost01", List.of("c1", "c2"), b, true));
        assertEquals(b.bytes(), JsonLdCapture.pendingBytes());
        JsonLdCapture.synced("AAAAAAhost01");
        assertEquals(0L, JsonLdCapture.pendingBytes());
    }

    @Test
    public void pausedCaptureParsesNormallyAndRecordsTheSkip() throws Exception {
        JsonLdCapture.pause(false, Set.of("c1"));
        final ContentScraper s = parse(page(ORG));
        assertFalse(s.ldJsonScraped());
        assertNull(s.getLdJsonBlocks());
        assertTrue(s.getText().contains("Impressum"));
        assertNull(JsonLdCapture.field("AAAAAAhost01", List.of("c1"), s.getLdJsonBlocks(), s.ldJsonScraped()));
        assertTrue(JsonLdCapture.wasSkipped("AAAAAAhost01"));
        assertNull(JsonLdCapture.field("BBBBBBhost01", List.of("c9"), null, false));
        assertFalse("not followed: not a skip", JsonLdCapture.wasSkipped("BBBBBBhost01"));
        // parsed while paused, indexed after the capture resumed: no blocks, so it counts as skipped too
        JsonLdCapture.activate(8, 16384, false, Set.of("c1"));
        assertNull(JsonLdCapture.field("CCCCCChost01", List.of("c1"), null, false));
        assertTrue(JsonLdCapture.wasSkipped("CCCCCChost01"));
        assertTrue(JsonLdCapture.synced("CCCCCChost01"));
        assertFalse(JsonLdCapture.wasSkipped("CCCCCChost01"));
    }
}
