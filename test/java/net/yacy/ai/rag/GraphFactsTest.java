/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai.rag;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.cora.document.encoding.ASCII;
import net.yacy.cora.document.id.DigestURL;
import net.yacy.scoutro.knowledge.read.ChatFacts;

/**
 * Graph facts in the RAG chat (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.4): who gets
 * them, the scope they are read with, the time and character budgets, and the
 * numbered, labelled source entries.
 */
public class GraphFactsTest {

    /** A fake graph: records the scope and the arguments, answers with one entry. */
    private static final class Fake implements GraphFacts.Source {
        GraphFacts.Settings settings = new GraphFacts.Settings(true, false, 8, 1500, 300);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> collection = new AtomicReference<>();
        final List<String> docIds = new ArrayList<>();
        final List<String> terms = new ArrayList<>();
        long sleep;
        RuntimeException failure;

        @Override
        public GraphFacts.Settings settings() {
            return this.settings;
        }

        @Override
        public List<ChatFacts.Entry> select(final String c, final List<String> docs, final List<String> words, final int maxFacts,
                final long deadline) throws Exception {
            this.calls.incrementAndGet();
            this.collection.set(c);
            this.docIds.addAll(docs);
            this.terms.addAll(words);
            if (this.sleep > 0) {
                Thread.sleep(this.sleep);
            }
            if (this.failure != null) {
                throw this.failure;
            }
            return List.of(entry());
        }
    }

    static ChatFacts.Entry entry() {
        final ChatFacts.Entry e = new ChatFacts.Entry("kge_aaaaaaaaaaaaaaaaaaaa", "Muster Pflege gGmbH", "organization",
                "https://www.muster.de/impressum", "AAAAAAhost01", "kga");
        e.facts.add(new ChatFacts.Fact("kgs_1", "phone", "+49301234567", "supported", false, false));
        e.facts.add(new ChatFacts.Fact("kgs_2", "operates", "Haus Lindenhof", "uncertain", true, false));
        e.facts.add(new ChatFacts.Fact("kgs_3", "opening_hours", "Mo-Fr 8-16", "uncertain", false, true));
        return e;
    }

    private static List<RagRetriever.Scored> found(final String url) {
        final List<RagRetriever.Scored> out = new ArrayList<>();
        out.add(new RagRetriever.Scored(new RagCandidate(url, "Impressum", "", "Die Muster Pflege gGmbH", "www.muster.de", List.of("kga"), 0), 1, 1, 0));
        return out;
    }

    private static final RagQuery QUESTION = RagQuery.prepare("Was betreibt die Muster Pflege gGmbH?");

    @Test
    public void onlyLocalAndAdministratorAccessGetsGraphFactsUnlessGuestsAreAllowed() {
        final Fake fake = new Fake();
        assertEquals("access", GraphFacts.collect(fake, false, true, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        assertEquals("access", GraphFacts.collect(fake, false, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        assertEquals("never asked for a guest", 0, fake.calls.get());
        assertEquals(null, GraphFacts.collect(fake, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        fake.settings = new GraphFacts.Settings(true, true, 8, 1500, 300);
        assertEquals(null, GraphFacts.collect(fake, false, true, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        fake.settings = new GraphFacts.Settings(false, true, 8, 1500, 300);
        assertEquals("disabled", GraphFacts.collect(fake, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        fake.settings = null;
        assertEquals("not_running", GraphFacts.collect(fake, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
        assertEquals(2, fake.calls.get());
    }

    @Test
    public void theScopeIsTheRequestsCollectionAndAGlobalQuestionGetsNone() throws Exception {
        final Fake fake = new Fake();
        GraphFacts.collect(fake, true, false, "kga", false, found("https://www.muster.de/impressum"), QUESTION, 9000);
        assertEquals("kga", fake.collection.get());
        assertEquals(List.of(ASCII.String(new DigestURL("https://www.muster.de/impressum").hash())), fake.docIds);
        assertTrue(fake.terms.toString(), fake.terms.containsAll(List.of("muster", "pfleg")));
        GraphFacts.collect(fake, true, false, null, false, found("https://www.muster.de/"), QUESTION, 9000);
        assertEquals("no collection: every collection the graph follows", null, fake.collection.get());
        final int before = fake.calls.get();
        assertEquals("global", GraphFacts.collect(fake, true, false, null, true, found("https://www.muster.de/"), QUESTION, 9000).reason);
        assertEquals(before, fake.calls.get());
    }

    @Test
    public void aSlowOrBrokenGraphIsSkipped() {
        final Fake slow = new Fake();
        slow.sleep = 3000;
        slow.settings = new GraphFacts.Settings(true, false, 8, 1500, 100);
        final long start = System.currentTimeMillis();
        final GraphFacts.Selected s = GraphFacts.collect(slow, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000);
        assertTrue("the answer does not wait for the graph", System.currentTimeMillis() - start < 1500);
        assertEquals("timeout", s.reason);
        assertTrue(s.timedOut);
        final GraphFacts.Built b = GraphFacts.format(s, 1);
        assertTrue(b.sources.isEmpty() && b.text.isEmpty());
        assertEquals(true, b.meta.optBoolean("timedOut"));
        final Fake broken = new Fake();
        broken.failure = new IllegalStateException("store closed");
        assertEquals("error", GraphFacts.collect(broken, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000).reason);
    }

    @Test
    public void entriesAreNumberedAfterTheSearchSourcesLabelledAndCitable() throws Exception {
        final GraphFacts.Selected s = GraphFacts.collect(new Fake(), true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000);
        final GraphFacts.Built b = GraphFacts.format(s, 4);
        assertEquals(1, b.sources.size());
        final RagContext.Source src = b.sources.get(0);
        assertEquals(4, src.id);
        assertEquals("Scoutro knowledge graph: Muster Pflege gGmbH", src.title);
        assertEquals("https://www.muster.de/impressum", src.url);
        assertEquals("kga", src.collection);
        assertEquals(RagContext.Source.KIND_GRAPH, src.kind);
        assertEquals("graph", src.toJson().getString("kind"));
        assertTrue(b.text, b.text.startsWith("[4] Scoutro knowledge graph: Muster Pflege gGmbH\nURL: https://www.muster.de/impressum\nCollection: kga\nText: "));
        assertTrue(b.text, b.text.contains("not model knowledge"));
        assertTrue(b.text, b.text.contains("phone: +49301234567;"));
        assertTrue(b.text, b.text.contains("operates: Haus Lindenhof (uncertain: only read from the page text by a language model)"));
        assertTrue(b.text, b.text.contains("opening hours: Mo-Fr 8-16 (uncertain: the page states it with reservation)"));
        assertTrue(b.text.length() <= 1500);
        assertTrue("the reserve covers the entries", s.reservedChars() >= b.text.length());
        // follow-up questions read the entry back with its kind; the answer may cite it
        final List<RagContext.Source> parsed = RagContext.parse("[1] Search hit\nURL: https://a.example/\nText: x\n\n" + b.text);
        assertEquals(2, parsed.size());
        assertEquals(RagContext.Source.KIND_GRAPH, parsed.get(1).kind);
        assertEquals(null, parsed.get(0).kind);
        final JSONObject cited = RagCitations.check("Sie betreibt das Haus Lindenhof [4].", b.sources);
        assertEquals("[4]", cited.getJSONArray("valid").toString());
        final JSONObject meta = b.meta;
        assertTrue(meta.getBoolean("used"));
        assertEquals(1, meta.getInt("entities"));
        assertEquals(3, meta.getInt("facts"));
        assertEquals("kga", meta.getString("collection"));
    }

    @Test
    public void theCharacterBudgetHolds() throws Exception {
        final Fake fake = new Fake();
        fake.settings = new GraphFacts.Settings(true, false, 8, 400, 300);
        final GraphFacts.Selected s = GraphFacts.collect(fake, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 9000);
        final GraphFacts.Built b = GraphFacts.format(s, 1);
        assertTrue(b.text, b.text.length() <= 400);
        assertTrue(b.meta.getInt("facts") >= 1 && b.meta.getInt("facts") < 3);
        // a third of a small source budget is not enough room: skipped before the graph is asked
        final Fake small = new Fake();
        assertEquals("no_room", GraphFacts.collect(small, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 300).reason);
        assertEquals(0, small.calls.get());
        assertFalse(GraphFacts.format(GraphFacts.collect(small, true, false, "kga", false, found("https://www.muster.de/"), QUESTION, 300), 1)
                .meta.optBoolean("used"));
    }
}
