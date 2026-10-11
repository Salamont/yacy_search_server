package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.Test;

/**
 * The token budget of the LLM tier's requests (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3) with the figures of the 0.10.0
 * coverage analysis (11.10.2026, section 11): system prompt 3,283 characters, schema about 2,200, chunks of up to 4,000
 * characters, known entities typically about 200 characters and at worst 40 of 300 characters, a window of 4,096 tokens.
 */
public class RequestBudgetTest {

    private static final String SYSTEM = LlmExtractor.SYSTEM_PROMPT;

    /** German page text of about the given length; every 40th word names "Muster Pflege gGmbH". */
    static String page(final int chars) {
        final StringBuilder sb = new StringBuilder();
        int i = 0;
        while (sb.length() < chars) {
            sb.append(i % 40 == 0 ? "Die Muster Pflege gGmbH " : "betreibt Einrichtungen der Tagespflege und Kurzzeitpflege ");
            i++;
        }
        return sb.substring(0, chars);
    }

    static LlmExtractor.Known known(final int n, final String type, final String name) {
        final Mention m = new Mention("jsonld:" + n, type, 1);
        m.name = name;
        return new LlmExtractor.Known("k" + (n + 1), m);
    }

    static List<LlmExtractor.Known> typical() {
        final List<LlmExtractor.Known> out = new ArrayList<>();
        out.add(known(0, Vocabulary.ORGANIZATION, "Muster Pflege gGmbH"));
        for (int i = 1; i < 5; i++) {
            out.add(known(i, Vocabulary.SERVICE, "Leistung Nummer " + i));
        }
        return out;
    }

    /** 40 organisations with names of 300 characters, one of them named in the page. */
    static List<LlmExtractor.Known> worst() {
        final List<LlmExtractor.Known> out = new ArrayList<>();
        out.add(known(0, Vocabulary.ORGANIZATION, "Muster Pflege gGmbH"));
        for (int i = 1; i < 40; i++) {
            final String base = "Organisation " + i + " mit einem sehr langen Namen ";
            out.add(known(i, Vocabulary.ORGANIZATION, (base.repeat(300 / base.length() + 1)).substring(0, 300)));
        }
        return out;
    }

    @Test
    public void aTypicalRequestFitsAsBeforeOnTheNativePath() {
        final RequestBudget b = new RequestBudget(RequestBudget.DEFAULT_WINDOW, 1200, SYSTEM, null);
        final List<LlmExtractor.Chunk> chunks = LlmExtractor.chunks(page(12_000), 12_000);
        final RequestBudget.Plan plan = b.plan(chunks, typical(), "Impressum", "muster-pflege.de", "de", Set.of("nursinghome"));
        assertFalse(plan.refused);
        assertEquals("one request per chunk, unchanged", chunks.size(), plan.parts.size());
        assertEquals(0, plan.knownTrimmed);
        assertEquals(0, plan.splits);
        for (final RequestBudget.Part p : plan.parts) {
            assertEquals("all known entities, as before", 5, p.known.size());
            assertTrue(p.promptTokens + b.reserve <= b.window);
        }
    }

    @Test
    public void aLargeEntityListKeepsTheEntitiesNamedInTheChunk() {
        final RequestBudget b = new RequestBudget(RequestBudget.DEFAULT_WINDOW, 1200, SYSTEM, null);
        final List<LlmExtractor.Chunk> chunks = LlmExtractor.chunks(page(4000), 12_000);
        final List<LlmExtractor.Known> all = worst();
        final RequestBudget.Plan plan = b.plan(chunks, all, "Impressum", "muster-pflege.de", "de", Set.of());
        assertFalse(plan.refused);
        assertEquals(1, plan.parts.size());
        final RequestBudget.Part p = plan.parts.get(0);
        assertEquals("only the entity the chunk names, numbered again", List.of("k1"), p.known.stream().map(k -> k.id).toList());
        assertEquals("Muster Pflege gGmbH", p.known.get(0).mention.name);
        assertEquals(39, plan.knownTrimmed);
        assertTrue(p.promptTokens + b.reserve <= b.window);
        // without the budget the request would not fit: the worst case of the analysis (about 5,700 tokens)
        final String full = LlmExtractor.userPrompt(new net.yacy.ai.PromptGuard(), chunks.get(0), "Impressum", "muster-pflege.de", "de", all,
                Set.of());
        assertFalse(b.fits(full));
        assertTrue(new RequestBudget(RequestBudget.DEFAULT_WINDOW, 0, SYSTEM, LlmExtractor.SCHEMA).prompt(full) > RequestBudget.DEFAULT_WINDOW);
    }

    @Test
    public void namedEntitiesThatDoNotFitSplitTheChunkAndNoneIsLost() {
        // a page naming 40 long organisations: the needed references alone exceed the window, so the chunk is split
        final List<LlmExtractor.Known> all = worst();
        final StringBuilder text = new StringBuilder();
        for (final LlmExtractor.Known k : all) {
            text.append(k.mention.name).append(" ist Partner. ");
        }
        final String t = text.substring(0, Math.min(text.length(), 12_000));
        final RequestBudget b = new RequestBudget(RequestBudget.DEFAULT_WINDOW, 1200, SYSTEM, null);
        final RequestBudget.Plan plan = b.plan(LlmExtractor.chunks(t, 12_000), all, null, "muster-pflege.de", "de", Set.of());
        assertFalse(plan.refused);
        assertTrue(plan.splits > 0);
        int covered = 0;
        for (final RequestBudget.Part p : plan.parts) {
            assertTrue(p.promptTokens + b.reserve <= b.window);
            assertEquals("each part's text is the page's text at its offset", t.substring(p.chunk.offset, p.chunk.offset + p.chunk.text.length()),
                    p.chunk.text);
            covered += p.chunk.text.length();
            for (final LlmExtractor.Known k : LlmExtractor.needed(all, p.chunk)) {
                assertTrue("every entity a part names is in its request: " + k.mention.name,
                        p.known.stream().anyMatch(x -> x.mention == k.mention));
            }
        }
        assertEquals("no text is lost by a split", t.length(), covered);
    }

    @Test
    public void aWindowTooSmallRefusesInsteadOfSending() {
        final RequestBudget b = new RequestBudget(1500, 1200, SYSTEM, null);
        final RequestBudget.Plan plan = b.plan(LlmExtractor.chunks(page(4000), 12_000), typical(), null, "muster-pflege.de", "de", Set.of());
        assertTrue(plan.refused);
    }

    @Test
    public void theSchemaCountsWhereTheEndpointMayPutItIntoThePrompt() {
        final RequestBudget nativePath = new RequestBudget(4096, 1200, SYSTEM, null);
        final RequestBudget openai = new RequestBudget(4096, 1200, SYSTEM, LlmExtractor.SCHEMA);
        assertTrue(openai.fixedTokens > nativePath.fixedTokens + 500);
        // the system prompt of the analysis: 3,283 characters, about 820 tokens
        assertEquals(RequestBudget.tokens(SYSTEM.length(), RequestBudget.PROSE) + RequestBudget.FRAME_TOKENS, nativePath.fixedTokens);
    }
}
