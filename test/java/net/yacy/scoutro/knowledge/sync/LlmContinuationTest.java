package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONObject;
import org.junit.Test;


/**
 * Bounded, resumable continuations of long content pages (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): what they cost, what
 * stays unread, that they add to the first reading without replacing or duplicating it, and that a stop keeps their
 * answered chunks.
 */
public class LlmContinuationTest extends LlmFixture {

    static final String PAGE = LlmLongPageTest.PAGE;
    static final String QUOTE = LlmLongPageTest.QUOTE;

    /** A model failing once at a given call. */
    static final class FailingFake extends LlmServiceTest.Fake {
        volatile int failAt = -1;
        /** Calls failing from {@link #failAt} on. */
        volatile int failTimes = 1;
        final AtomicInteger seen = new AtomicInteger();

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
                throws IOException {
            final int n = this.seen.incrementAndGet();
            if (this.failAt > 0 && n >= this.failAt && n < this.failAt + this.failTimes) {
                this.calls.incrementAndGet();
                throw new IOException("Request failed with response code 503");
            }
            return super.complete(system, user, schema, timeoutMillis);
        }
    }

    @Override
    protected LlmServiceTest.Fake newModel() {
        return new FailingFake();
    }

    private String url = "https://www.stellwerk-pflege.de/referenzen/projekt-beispiele/";

    private void addPage(final String text) throws Exception {
        add(PAGE, this.url, "c1", LD.replace("muster-pflege", "stellwerk-pflege"), text, "Projekt-Beispiele");
        settleSync();
    }

    private JSONObject processed() throws Exception {
        return this.llm.status().getJSONObject("processed");
    }

    /** Requests of the first reading: the chunks of the first {@code extract.maxInputChars} characters. */
    private int first(final String text) {
        return net.yacy.scoutro.knowledge.extract.LlmExtractor.chunks(text, this.cfg.extractMaxInputChars).size();
    }

    @Test
    public void aQueuedContinuationDoesNotCountAgainstItsHostsCap() throws Exception {
        addPage(LlmLongPageTest.page());
        settleLlm();
        final String host = strings("SELECT host_id FROM kg_doc WHERE doc_id = '" + PAGE + "'").get(0);
        final long done = this.store.read(c -> LlmQueue.hostLoad(c, host));
        assertEquals(1L, done);
        exec("UPDATE kg_doc SET llm_reason = '" + LlmQueue.MORE_TEXT + "' WHERE doc_id = '" + PAGE + "'");
        this.store.write(net.yacy.scoutro.knowledge.budget.StorageGuard.WriteClass.MAINTENANCE, 1024L,
                tx -> LlmQueue.offer(tx, PAGE, host, LlmQueue.CONTINUATION_PRIORITY, this.clock.get()));
        assertTrue(queued(PAGE));
        assertEquals("the page counts once, not as done and as queued", done, (long) this.store.read(c -> LlmQueue.hostLoad(c, host)));
    }

    @Test
    public void aContinuationReadsTheRestOfAPageWithinItsBound() throws Exception {
        final String text = LlmLongPageTest.page(); // about 20,000 characters
        addPage(text);
        settleLlm();
        assertEquals("the whole text was read", "1:", llmStatus(PAGE));
        final long more = processed().getLong("continuationCalls");
        assertTrue("about 8,000 characters more in chunks of up to 4,000: " + more, more >= 2L && more <= 3L);
        assertEquals("every request is the first reading's or the continuation's", first(text) + more, this.model.calls.get());
        assertEquals(1L, processed().getLong("continuationDocs"));
        assertEquals(0L, processed().getLong("textCutDocs"));
    }

    @Test
    public void aVeryLongPageIsReadUpToTheBoundAndTheRestShowsAsCut() throws Exception {
        // STELLWERK's reference page: 67,173 characters, evidence at about 25,000 and at about 50,000
        final String text = "Projekt-Beispiele\n" + LlmLongPageTest.cases(0, 25_000) + "\n" + QUOTE + ".\n" + LlmLongPageTest.cases(2000, 25_000)
                + "\nDie Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin.\n";
        final String padded = text + LlmLongPageTest.cases(5000, 67_173 - text.length());
        assertEquals(67_173, padded.length());
        addPage(padded);
        settleLlm();
        final int bound = this.cfg.extractMaxInputChars + this.cfg.llmContinuationChunks * net.yacy.scoutro.knowledge.extract.LlmExtractor.CHUNK_CHARS;
        assertEquals(32_000, bound);
        assertEquals("the rest stays unread, visibly", "1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals("the continuation makes exactly its bound of requests", this.cfg.llmContinuationChunks,
                processed().getLong("continuationCalls"));
        assertEquals(first(padded) + this.cfg.llmContinuationChunks, this.model.calls.get());
        assertEquals(1L, processed().getLong("textCutDocs"));
        final long cut = processed().getLong("textCutChars");
        assertTrue("about 35,000 characters stay unread: " + cut, cut >= 67_173L - bound && cut <= 67_173L - bound + 1000L);
        assertTrue("the evidence at 25,000 was read", this.model.users.stream().anyMatch(u -> u.contains(QUOTE)));
        assertTrue("the evidence at 50,000 was not", this.model.users.stream().noneMatch(u -> u.contains("Haus Lindenhof in Berlin")));
        final JSONObject cont = this.llm.status().getJSONObject("continuation");
        assertEquals(1L, cont.getLong("textCutDocuments"));
        assertEquals(0L, cont.getLong("pendingDocuments"));
    }

    @Test
    public void aContinuationAddsToTheFirstReadingAndPublishesNothingTwice() throws Exception {
        // the operator's facility at the start (first reading), and a passage across character 12,000 (both readings see it)
        final String head = "Projekt-Beispiele\nDie Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. Das Haus Lindenhof bietet"
                + " Tagespflege an. Die Muster Pflege gGmbH plant ab 2027 das Haus Am See.\n";
        final String body = head + LlmLongPageTest.cases(0, 12_000 - head.length() - 20);
        final String text = body + "\n" + QUOTE + ".\n" + LlmLongPageTest.cases(3000, 6_000);
        assertTrue(text.indexOf(QUOTE) < 12_000 && text.indexOf(QUOTE) + QUOTE.length() > 12_000);
        addPage(text);
        settleLlm();
        final String evidence = "SELECT v.name || ' ' || (SELECT obj_val FROM kg_statement n JOIN kg_vocab nv ON nv.term_id = n.pred"
                + " WHERE n.subj = s.obj_ent AND nv.name = 'name' LIMIT 1) FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND s.obj_ent IS NOT NULL ORDER BY 1";
        assertEquals("the first reading kept, the passage cut at 12,000 read whole by the continuation, each once",
                List.of("offers Tagespflege", "operates Haus Am See", "operates Haus Lindenhof", "operates Haus Sonnenhof"),
                strings(evidence));
    }

    @Test
    public void aStoppedContinuationKeepsItsAnsweredChunks() throws Exception {
        final String text = LlmLongPageTest.page();
        final int firstReading = first(text);
        ((FailingFake) this.model).failAt = firstReading + 2; // the second request of the continuation fails
        addPage(text);
        settleLlm(() -> this.model.calls.get() >= firstReading + 2);
        assertEquals(firstReading + 2, this.model.calls.get());
        restart();
        settleLlm();
        assertEquals("1:", llmStatus(PAGE));
        assertEquals("no continuation left", 0L, this.llm.status().getJSONObject("continuation").getLong("pendingDocuments"));
        // the continuation has three chunks: the first was answered before the stop and is kept, the second failed and
        // is asked again, the third is asked once
        assertEquals(firstReading + 2 + 2, this.model.calls.get());
        assertTrue(this.model.users.stream().anyMatch(u -> u.contains(QUOTE)));
    }

    @Test
    public void aContinuationThatKeepsFailingLeavesTheFirstReadingDoneAndTheRestVisiblyUnread() throws Exception {
        final String text = LlmLongPageTest.page();
        final FailingFake f = (FailingFake) this.model;
        f.failAt = first(text) + 1; // every request of the continuation fails
        f.failTimes = 100;
        addPage(text);
        settleLlm();
        assertEquals("the page stays done, not failed", "1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals(0L, processed().getLong("failedDocs"));
        assertEquals(1L, processed().getLong("textCutDocs"));
        assertTrue(processed().getLong("textCutChars") > 0L);
    }

    @Test
    public void aContinuationReadsThePageLaidOutAsItsFirstReadingWas() throws Exception {
        final String ld = LD.replace("muster-pflege", "pta-pflege");
        final String text = LlmNavigationTest.page();
        final String quote = LlmNavigationTest.QUOTE;
        // the host has no other page yet: the first reading reads the page as stored, the menu first
        addAll(List.of(doc(LlmNavigationTest.TARGET, "https://www.pta-pflege.de/leistungen/tagespflege/", "c1", ld, text, "Tagespflege")));
        settleSync();
        for (int i = 0; i < 400 && !("1:" + LlmQueue.MORE_TEXT).equals(llmStatus(LlmNavigationTest.TARGET)); i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals("1:" + LlmQueue.MORE_TEXT, llmStatus(LlmNavigationTest.TARGET));
        assertFalse(this.model.users.stream().anyMatch(u -> u.contains(quote)));
        // pages of the host with the same menu arrive before the continuation: it still reads on where the first reading
        // ended (with today's layout the menu would move behind the content and the evidence fall before its start)
        addAll(List.of(doc("BBBBBBpta001", "https://www.pta-pflege.de/branchen/handel/", "c1", ld,
                "Handel\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Handel", 2000), "Handel"),
                doc("CCCCCCpta001", "https://www.pta-pflege.de/unternehmen/", "c1", ld,
                        "Unternehmen\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Wir", 2000), "Unternehmen")));
        settleSync();
        settleLlm();
        assertEquals("1:", llmStatus(LlmNavigationTest.TARGET));
        assertTrue("the evidence between the first reading and the end was asked", this.model.users.stream().anyMatch(u -> u.contains(quote)));
        assertEquals("the layout of the first reading is not kept after the continuation", 0L,
                count("SELECT count(*) FROM kg_meta WHERE key GLOB '" + LlmQueue.LAYOUT_PREFIX + "*'"));
    }

    @Test
    public void aHostNamedLikeALegalWordIsNoLegalPage() throws Exception {
        this.url = "https://www.agb-pflege.de/referenzen/projekt-beispiele/";
        addPage(LlmLongPageTest.page());
        settleLlm();
        assertEquals("1:", llmStatus(PAGE));
        assertTrue(processed().getLong("continuationCalls") > 0L);
    }

    @Test
    public void legalPagesAndDisabledContinuationsLeaveTheRestVisiblyUnread() throws Exception {
        this.url = "https://www.stellwerk-pflege.de/datenschutz/";
        addPage(LlmLongPageTest.page());
        settleLlm();
        assertEquals("1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals("no continuation of a legal page", first(LlmLongPageTest.page()), this.model.calls.get());
        assertEquals(0L, processed().getLong("continuationCalls"));
    }
}
