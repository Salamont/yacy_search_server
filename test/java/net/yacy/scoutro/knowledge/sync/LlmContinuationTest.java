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
 * Bounded, resumable continuations of long content pages (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.5): a long page is read to
 * the end of its content in passes of a bounded number of requests; what a pass costs, that the passes add to the first
 * reading without replacing or duplicating it, that a stop, a restart or a failed pass keeps their progress, and what
 * stays unread, visibly.
 */
public class LlmContinuationTest extends LlmFixture {

    static final String PAGE = LlmLongPageTest.PAGE;
    static final String QUOTE = LlmLongPageTest.QUOTE;

    /** A model failing once at a given call. */
    static final class FailingFake extends LlmServiceTest.Fake {
        volatile int failAt = -1;
        /** Calls failing from {@link #failAt} on, or requests matching {@link #failWhen}. */
        volatile int failTimes = 1;
        /** If set: the first {@link #failTimes} requests whose user message matches fail (instead of {@link #failAt}). */
        volatile java.util.function.Predicate<String> failWhen;
        final AtomicInteger seen = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
                throws IOException {
            final int n = this.seen.incrementAndGet();
            final java.util.function.Predicate<String> when = this.failWhen;
            final boolean fail = when != null ? when.test(user) && this.failed.get() < this.failTimes
                    : this.failAt > 0 && n >= this.failAt && n < this.failAt + this.failTimes;
            if (fail) {
                this.failed.incrementAndGet();
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

    private String url = "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/";

    private void addPage(final String text) throws Exception {
        add(PAGE, this.url, "c1", LD.replace("muster-pflege", "beispiel-referenzen"), text, "Projekt-Beispiele");
        settleSync();
    }

    private JSONObject processed() throws Exception {
        return this.llm.status().getJSONObject("processed");
    }

    /** The status of the continuations, counted now (the counts are cached for a while). */
    private JSONObject continuation() throws Exception {
        this.clock.addAndGet(LlmService.COUNTS_MILLIS + 1L);
        return this.llm.status().getJSONObject("continuation");
    }

    /** Progress rows and checkpoints left: none once nothing is to read any more. */
    private long leftovers() {
        return count("SELECT count(*) FROM kg_meta WHERE key GLOB '" + LlmQueue.LAYOUT_PREFIX + "*' OR key GLOB 'llm_progress:*'");
    }

    /** A page of 67,173 characters (as the longest reference page of the example), evidence at about 25,000 and 50,000. */
    static String veryLong() {
        final String text = "Projekt-Beispiele\n" + LlmLongPageTest.cases(0, 25_000) + "\n" + QUOTE + ".\n" + LlmLongPageTest.cases(2000, 25_000)
                + "\nDie Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin.\n";
        return text + LlmLongPageTest.cases(5000, 67_173 - text.length());
    }

    /** Steps until the page has the given LLM status, at most {@code steps} times; idle steps move the clock on. */
    private void stepUntil(final String status, final int steps) {
        for (int i = 0; i < steps && !status.equals(llmStatus(PAGE)); i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals(status, llmStatus(PAGE));
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
    public void aContinuationReadsTheRestOfAPageInOnePass() throws Exception {
        final String text = LlmLongPageTest.page(); // about 20,000 characters
        addPage(text);
        settleLlm();
        assertEquals("the whole content was read", "1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        final long more = processed().getLong("continuationCalls");
        assertTrue("about 8,000 characters more in chunks of up to 4,000: " + more, more >= 2L && more <= 3L);
        assertEquals("every request is the first reading's or the continuation's", first(text) + more, this.model.calls.get());
        assertEquals(1L, processed().getLong("continuationDocs"));
        assertEquals(1L, processed().getLong("fullTextDocs"));
        assertEquals(0L, processed().getLong("textCutDocs"));
        assertEquals("nothing kept once the page is read", 0L, leftovers());
    }

    @Test
    public void aVeryLongPageIsReadToTheEndOfItsContentInBoundedPasses() throws Exception {
        final String text = veryLong();
        assertEquals(67_173, text.length());
        addPage(text);
        final int n = this.cfg.llmContinuationChunks;
        int before = 0;
        int passes = 0;
        boolean sawPartial = false;
        for (int i = 0; i < 2000 && !("1:" + LlmQueue.FULL_TEXT).equals(llmStatus(PAGE)); i++) {
            final boolean more = this.llm.step();
            final int calls = this.model.calls.get();
            // one step reads one item: the first reading, or one pass of at most n requests
            assertTrue("a step of at most " + Math.max(n, first(text)) + " requests: " + (calls - before), calls - before <= Math.max(n, first(text)));
            before = calls;
            if (!more) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
            if (processed().getLong("continuationDocs") > passes) {
                passes = (int) processed().getLong("continuationDocs");
                if (("1:" + LlmQueue.MORE_TEXT).equals(llmStatus(PAGE))) {
                    // a pass is published with its progress: what is read is in the graph, the rest is shown
                    final JSONObject cont = continuation();
                    final JSONObject row = new JSONObject(strings("SELECT value FROM kg_meta WHERE key = '" + LlmQueue.LAYOUT_PREFIX + PAGE + "'").get(0));
                    assertEquals(1L, cont.getLong("pendingDocuments"));
                    assertEquals((long) (row.getInt("e") - row.getInt("r")), cont.getLong("remainingChars"));
                    assertTrue(cont.getLong("remainingChars") > 0L && cont.getLong("remainingChars") < 67_173L - 12_000L);
                    sawPartial = true;
                }
            }
        }
        assertEquals("the page is read to the end of its content", "1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertTrue("a partly read page shows as partly read", sawPartial);
        final long p = processed().getLong("continuationDocs");
        assertTrue("about 55,000 characters in passes of about 20,000: " + p, p >= 3L && p <= 4L);
        assertTrue("each pass at most " + n + " requests", processed().getLong("continuationCalls") <= p * n);
        assertEquals(first(text) + processed().getLong("continuationCalls"), this.model.calls.get());
        assertEquals("no request asked twice", this.model.users.size(), new java.util.HashSet<>(this.model.users).size());
        assertTrue("the end of the page was read", this.model.users.stream().anyMatch(u -> u.contains(text.substring(67_173 - 60).trim())));
        final List<String> locators = strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND v.name = 'operates' ORDER BY e.locator");
        final String lindenhof = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof";
        assertEquals("the evidence at 25,000 and at 50,000, each once, verbatim at its place in the stored text",
                List.of("text:" + text.indexOf(QUOTE) + "+" + QUOTE.length(), "text:" + text.indexOf(lindenhof) + "+" + lindenhof.length()),
                locators);
        assertEquals(0L, processed().getLong("textCutDocs"));
        final JSONObject cont = continuation();
        assertEquals(1L, cont.getLong("fullTextDocuments"));
        assertEquals(0L, cont.getLong("pendingDocuments"));
        assertEquals(0L, cont.getLong("remainingChars"));
        assertEquals(0L, leftovers());
    }

    @Test
    public void aRestartBetweenPassesReadsOnWhereTheLastPassStopped() throws Exception {
        final String text = veryLong();
        addPage(text);
        for (int i = 0; i < 400 && processed().getLong("continuationDocs") < 1L; i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals("1:" + LlmQueue.MORE_TEXT, llmStatus(PAGE));
        final int readUpTo = new JSONObject(strings("SELECT value FROM kg_meta WHERE key = '" + LlmQueue.LAYOUT_PREFIX + PAGE + "'").get(0)).getInt("r");
        assertTrue(readUpTo > 12_000);
        final int asked = this.model.users.size();
        restart();
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        final String next = this.model.users.get(asked);
        assertTrue("the next pass starts where the last stopped (at most the overlap before)",
                next.contains(text.substring(readUpTo, readUpTo + 200)) && !next.contains(text.substring(readUpTo - LlmService.CONTINUATION_OVERLAP - 200,
                        readUpTo - LlmService.CONTINUATION_OVERLAP - 100)));
        assertEquals("no request asked twice", this.model.users.size(), new java.util.HashSet<>(this.model.users).size());
        assertEquals(0L, leftovers());
    }

    @Test
    public void aNewTextOfAPartlyReadPageIsReadFromTheStartAndItsProgressGoes() throws Exception {
        addPage(veryLong());
        stepUntil("1:" + LlmQueue.MORE_TEXT, 400);
        assertEquals(1L, count("SELECT count(*) FROM kg_meta WHERE key = '" + LlmQueue.LAYOUT_PREFIX + PAGE + "'"));
        // the page changes: the evidence moves 2,000 characters on
        final String changed = "Projekt-Beispiele\n" + LlmLongPageTest.cases(9000, 2_000) + veryLong().substring("Projekt-Beispiele\n".length());
        addPage(changed);
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        final List<String> locators = strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred"
                + " WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND v.name = 'operates' ORDER BY e.locator");
        final String lindenhof = "Die Muster Pflege gGmbH betreibt das Haus Lindenhof";
        assertEquals("the evidence of the new text, each once, at its new place", List.of("text:" + changed.indexOf(QUOTE) + "+"
                + QUOTE.length(), "text:" + changed.indexOf(lindenhof) + "+" + lindenhof.length()), locators);
        assertEquals(0L, leftovers());
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
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertEquals("no continuation left", 0L, continuation().getLong("pendingDocuments"));
        // the continuation has three chunks: the first was answered before the stop and is kept, the second failed and
        // is asked again, the third is asked once
        assertEquals(firstReading + 2 + 2, this.model.calls.get());
        assertTrue(this.model.users.stream().anyMatch(u -> u.contains(QUOTE)));
    }

    @Test
    public void aContinuationThatKeepsFailingIsTriedAgainLaterAndThenLeavesTheRestVisiblyUnread() throws Exception {
        final String text = LlmLongPageTest.page();
        final FailingFake f = (FailingFake) this.model;
        f.failAt = first(text) + 1; // every request of the continuation fails
        f.failTimes = 100;
        addPage(text);
        stepUntil("1:" + LlmQueue.MORE_TEXT, 400);
        final long start = this.clock.get();
        for (int i = 0; i < 400 && processed().getLong("continuationRetries") < 1L; i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        // the failed pass keeps the page's progress and is tried again in an hour, its attempts counted afresh
        final JSONObject row = new JSONObject(strings("SELECT value FROM kg_meta WHERE key = '" + LlmQueue.LAYOUT_PREFIX + PAGE + "'").get(0));
        assertEquals(1, row.getInt("f"));
        assertEquals(12_000, row.getInt("r"));
        assertEquals(List.of("0"), strings("SELECT attempts FROM kg_llm_work WHERE doc_id = '" + PAGE + "'"));
        assertTrue(count("SELECT not_before FROM kg_llm_work WHERE doc_id = '" + PAGE + "'") >= start + LlmService.CONTINUATION_RETRY_MILLIS);
        settleLlm();
        assertEquals("the page stays done, not failed", "1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertTrue("three passes, an hour and two hours apart: " + (this.clock.get() - start) / 60_000L + " min",
                this.clock.get() - start >= 3L * LlmService.CONTINUATION_RETRY_MILLIS);
        assertEquals(LlmService.CONTINUATION_FAILED_PASSES - 1, processed().getLong("continuationRetries"));
        assertEquals("each pass makes its attempts, no more", first(text) + LlmService.CONTINUATION_FAILED_PASSES * this.cfg.llmMaxAttempts,
                this.model.calls.get());
        assertEquals(0L, processed().getLong("failedDocs"));
        assertEquals(1L, processed().getLong("textCutDocs"));
        assertEquals("the page's content behind the first reading", (long) (text.length() - 12_000), processed().getLong("textCutChars"));
        assertEquals(0L, leftovers());
    }

    @Test
    public void aFailedPassIsTriedAgainFromWhereTheReadingStood() throws Exception {
        final String text = LlmLongPageTest.page();
        final FailingFake f = (FailingFake) this.model;
        f.failWhen = u -> u.contains(QUOTE); // the pass's request with the evidence fails for all its attempts once
        f.failTimes = this.cfg.llmMaxAttempts;
        addPage(text);
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertEquals(1L, processed().getLong("continuationRetries"));
        assertEquals("the pass is published once", 1L, processed().getLong("continuationDocs"));
        assertEquals("the failing request failed its attempts", this.cfg.llmMaxAttempts, f.failed.get());
        assertEquals("then it was answered once in the next pass", 1L, this.model.users.stream().filter(u -> u.contains(QUOTE)).count());
        assertEquals("every request counted, the failed ones too", first(text) + processed().getLong("continuationCalls"), this.model.calls.get());
        assertEquals("every other request asked once: answered chunks are kept",
                this.model.users.stream().filter(u -> !u.contains(QUOTE)).count(),
                this.model.users.stream().filter(u -> !u.contains(QUOTE)).distinct().count());
        assertEquals(List.of("text:" + text.indexOf(QUOTE) + "+" + QUOTE.length()), strings("SELECT e.locator FROM kg_evidence e"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE e.tier = 3 AND d.doc_id = '" + PAGE + "' AND v.name = 'operates'"));
        assertEquals(0L, leftovers());
    }

    @Test
    public void aContinuationReadsThePageLaidOutAsItsFirstReadingWas() throws Exception {
        final String ld = LD.replace("muster-pflege", "beispiel-navigation");
        final String text = LlmNavigationTest.page();
        final String quote = LlmNavigationTest.QUOTE;
        // the host has no other page yet: the first reading reads the page as stored, the menu first
        addAll(List.of(doc(LlmNavigationTest.TARGET, "https://www.beispiel-navigation.de/leistungen/tagespflege/", "c1", ld, text, "Tagespflege")));
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
        addAll(List.of(doc("BBBBBBnav001", "https://www.beispiel-navigation.de/branchen/handel/", "c1", ld,
                "Handel\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Handel", 2000), "Handel"),
                doc("CCCCCCnav001", "https://www.beispiel-navigation.de/unternehmen/", "c1", ld,
                        "Unternehmen\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Wir", 2000), "Unternehmen")));
        settleSync();
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(LlmNavigationTest.TARGET));
        assertTrue("the evidence between the first reading and the end was asked", this.model.users.stream().anyMatch(u -> u.contains(quote)));
        assertEquals(List.of("text:" + text.indexOf(quote) + "+" + quote.length()), strings("SELECT e.locator FROM kg_evidence e"
                + " JOIN kg_doc d ON d.doc_rowid = e.doc_rowid JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid"
                + " JOIN kg_vocab v ON v.term_id = s.pred WHERE e.tier = 3 AND d.doc_id = '" + LlmNavigationTest.TARGET + "' AND v.name = 'operates'"));
        assertEquals("the progress of the page is not kept after the continuation", 0L, leftovers());
    }

    @Test
    public void aLongPageWhoseRestIsNavigationIsDoneWithItsContent() throws Exception {
        final String ld = LD.replace("muster-pflege", "beispiel-navigation");
        // longer than the input only by its menu: the content (about 9,000 characters) fits
        final String text = "Pflege und Betreuung\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Pflege", 8000)
                + LlmNavigationTest.QUOTE + ".\n";
        assertTrue(text.length() > this.cfg.extractMaxInputChars);
        addAll(List.of(doc(LlmNavigationTest.TARGET, "https://www.beispiel-navigation.de/leistungen/tagespflege/", "c1", ld, text, "Tagespflege"),
                doc("BBBBBBnav001", "https://www.beispiel-navigation.de/branchen/handel/", "c1", ld,
                        "Handel\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Handel", 2000), "Handel"),
                doc("CCCCCCnav001", "https://www.beispiel-navigation.de/unternehmen/", "c1", ld,
                        "Unternehmen\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Wir", 2000), "Unternehmen")));
        settleSync();
        settleLlm();
        assertEquals("the content is read, the menu behind it is not", "1:" + LlmQueue.FULL_TEXT, llmStatus(LlmNavigationTest.TARGET));
        assertTrue("no request about the menu", this.model.users.stream().noneMatch(u -> u.contains("Pflege Absatz")
                && u.contains("Leistungsbereich Nummer")));
        assertEquals("no continuation", 0L, processed().getLong("continuationDocs"));
        assertEquals((long) LlmNavigationTest.menu().length(), processed().getLong("navigationSkippedChars"));
        assertEquals(0L, leftovers());
    }

    @Test
    public void theLayoutOfTheFirstReadingHoldsWhenTheHostsOtherPagesGo() throws Exception {
        final String ld = LD.replace("muster-pflege", "beispiel-navigation");
        // the evidence lies behind the first 12,000 characters of the page's own content
        final String text = "Pflege und Betreuung\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Pflege", 12_500)
                + LlmNavigationTest.QUOTE + ".\n" + LlmNavigationTest.content("Betreuung", 3000);
        final String quote = LlmNavigationTest.QUOTE;
        addAll(List.of(doc(LlmNavigationTest.TARGET, "https://www.beispiel-navigation.de/leistungen/tagespflege/", "c1", ld, text, "Tagespflege"),
                doc("BBBBBBnav001", "https://www.beispiel-navigation.de/branchen/handel/", "c1", ld,
                        "Handel\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Handel", 2000), "Handel"),
                doc("CCCCCCnav001", "https://www.beispiel-navigation.de/unternehmen/", "c1", ld,
                        "Unternehmen\n" + LlmNavigationTest.menu() + LlmNavigationTest.content("Wir", 2000), "Unternehmen")));
        settleSync();
        for (int i = 0; i < 400 && !("1:" + LlmQueue.MORE_TEXT).equals(llmStatus(LlmNavigationTest.TARGET)); i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals("1:" + LlmQueue.MORE_TEXT, llmStatus(LlmNavigationTest.TARGET));
        // the host's other pages go before the continuation: the page is still laid out as its first reading was
        client().deleteById(List.of("BBBBBBnav001", "CCCCCCnav001"));
        client().commit();
        settleSync();
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(LlmNavigationTest.TARGET));
        assertEquals("verbatim at its place in the stored text", List.of("text:" + text.indexOf(quote) + "+" + quote.length()),
                strings("SELECT e.locator FROM kg_evidence e JOIN kg_doc d ON d.doc_rowid = e.doc_rowid"
                        + " JOIN kg_statement s ON s.stmt_rowid = e.stmt_rowid JOIN kg_vocab v ON v.term_id = s.pred WHERE e.tier = 3"
                        + " AND d.doc_id = '" + LlmNavigationTest.TARGET + "' AND v.name = 'operates'"));
        assertTrue("the moved menu is never asked", this.model.users.stream().noneMatch(u -> u.contains("Leistungsbereich Nummer 30 der")));
        assertEquals("the menu counts as navigation left unread", (long) LlmNavigationTest.menu().length(),
                processed().getLong("navigationSkippedChars"));
        assertEquals(0L, leftovers());
    }

    @Test
    public void aHostNamedLikeALegalWordIsNoLegalPage() throws Exception {
        this.url = "https://www.agb-pflege.de/referenzen/projekt-beispiele/";
        addPage(LlmLongPageTest.page());
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertTrue(processed().getLong("continuationCalls") > 0L);
    }

    @Test
    public void legalPagesAndDisabledContinuationsLeaveTheRestVisiblyUnread() throws Exception {
        this.url = "https://www.beispiel-referenzen.de/datenschutz/";
        addPage(LlmLongPageTest.page());
        settleLlm();
        assertEquals("1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals("no continuation of a legal page", first(LlmLongPageTest.page()), this.model.calls.get());
        assertEquals(0L, processed().getLong("continuationCalls"));
    }
}
