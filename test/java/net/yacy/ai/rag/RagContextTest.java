/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/** Budget, excerpts and numbered sources. */
public class RagContextTest {

    private final RagCorpus corpus;

    public RagContextTest() throws Exception {
        this.corpus = new RagCorpus();
    }

    private RagRetriever.Result retrieve(final String question, final String collection) {
        return new RagRetriever(this.corpus.searcher(true, new ArrayList<>())).retrieve(RagQuery.prepare(question), collection, false, 8);
    }

    @Test
    public void budgetLeavesRoomForAnswerSystemPromptAndQuestion() {
        final RagContext.Budget budget = RagContext.plan(4096, 512, 2400, 30000);
        assertEquals((4096 - 512 - RagContext.SAFETY_TOKENS) * RagContext.CHARS_PER_TOKEN - 2400, budget.freeChars);
        assertTrue(budget.sourceChars(0) <= budget.freeChars);
        assertTrue(budget.sourceChars(5000) < budget.sourceChars(0)); // history reduces the sources
        assertEquals(budget.historyAllowance, (int) (budget.freeChars * RagContext.HISTORY_SHARE));
        // the existing upper bound still applies
        assertEquals(2000, RagContext.plan(131072, 512, 2400, 2000).sourceChars(0));
        // an answer length larger than half the window does not eat the whole prompt
        assertTrue(RagContext.plan(4096, 8192, 2400, 30000).freeChars > 0);
    }

    @Test
    public void contextFitsTheModelWindow() {
        final RagContext.Budget budget = RagContext.plan(4096, 512, 2400, 30000);
        final RagContext.Built built = RagContext.build(retrieve("Welche Angebote zur Pflege gibt es?", "edelsenior-web").selected,
                RagQuery.prepare("Welche Angebote zur Pflege gibt es?"), budget.sourceChars(0), 1500, 8, "edelsenior-web");
        assertTrue(built.text.length() <= budget.sourceChars(0));
        final int promptTokens = RagContext.tokens(2400 + built.text.length());
        assertTrue(promptTokens + 512 <= 4096);
        assertTrue(built.sources.size() >= 5);
    }

    @Test
    public void zeroAndOneHit() throws Exception {
        final RagContext.Built none = RagContext.build(new ArrayList<>(), RagQuery.prepare("Weltraumtourismus"), 9000, 1500, 8, null);
        assertTrue(none.sources.isEmpty());
        assertEquals("", none.text);
        assertEquals(0, none.sourcesJson().length());
        final RagContext.Built one = RagContext.build(retrieve("Gibt es ein Hospiz in Düsseldorf?", "edelsenior-web").selected,
                RagQuery.prepare("Gibt es ein Hospiz in Düsseldorf?"), 9000, 1500, 8, "edelsenior-web");
        assertEquals(1, one.sources.size());
        assertTrue(one.text.startsWith("[1] Hospiz am Hofgarten Düsseldorf\nURL: https://hospiz-hofgarten.example/\nCollection: edelsenior-web\nText: "));
    }

    @Test
    public void veryLongDocumentGivesTheRelevantPassageAndIsCutCleanly() {
        final RagQuery query = RagQuery.prepare("Welche Pflegeheime in Aachen stehen im Heimverzeichnis?");
        final RagContext.Built built = RagContext.build(retrieve(query.question, "edelsenior-web").selected, query, 9000, 1500, 8, "edelsenior-web");
        final String first = built.text.split("\n\n\\[2\\] ")[0];
        assertTrue(first.startsWith("[1] Heimverzeichnis NRW"));
        assertTrue(first, first.contains("Pflegeheim Waldblick in Aachen")); // from the end of a 45 000 character page
        assertTrue(first.length() <= 1500);
        assertTrue(first.contains("…")); // gaps are marked, nothing is silently cut
        // every entry is complete: header, URL and text, no entry cut at the end of the block
        final Matcher entries = Pattern.compile("(?m)^\\[(\\d+)\\] .*\\nURL: \\S+\\nCollection: \\S+\\nText: .+").matcher(built.text);
        int count = 0;
        while (entries.find()) count++;
        assertEquals(built.sources.size(), count);
    }

    @Test
    public void manyHitsAreLimitedBySourcesAndBudget() {
        final RagQuery query = RagQuery.prepare("Welche Angebote zur Pflege gibt es?");
        final List<RagRetriever.Scored> selected = retrieve(query.question, "edelsenior-web").selected;
        assertEquals(3, RagContext.build(selected, query, 90000, 1500, 3, null).sources.size());
        final RagContext.Built small = RagContext.build(selected, query, 1200, 1500, 8, null);
        assertTrue(small.sources.size() <= 3);
        assertTrue(small.text.length() <= 1200);
    }

    @Test
    public void sourcesRoundTripThroughTheAttachment() {
        final RagQuery query = RagQuery.prepare("Pflegeheim Köln");
        final RagContext.Built built = RagContext.build(retrieve(query.question, "edelsenior-web").selected, query, 9000, 1500, 8, "edelsenior-web");
        final List<RagContext.Source> parsed = RagContext.parse(built.text);
        assertEquals(built.sources.size(), parsed.size());
        for (int i = 0; i < parsed.size(); i++) {
            assertEquals(built.sources.get(i).id, parsed.get(i).id);
            assertEquals(built.sources.get(i).url, parsed.get(i).url);
            assertEquals(built.sources.get(i).title, parsed.get(i).title);
        }
        final String limited = RagContext.limitEntries(built.text, 900);
        assertTrue(limited.length() <= 900);
        assertFalse(limited.isEmpty());
        assertEquals(RagContext.parse(limited).size(), limited.split("\n\n(?=\\[\\d+\\] )").length);
    }
}
