package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.json.JSONObject;
import org.junit.Test;

/**
 * What the LLM tier keeps between its readings and what it measures (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.8): a progress
 * row of a long page is small (no text) and goes when the page is read, cut, changed or removed, also across a
 * restart; checkpoints go with their page; the queue wait and the backlog over time are reported. Example data only.
 */
public class LlmLifecycleTest extends LlmFixture {

    static final String PAGE = LlmLongPageTest.PAGE;

    @Override
    protected LlmServiceTest.Fake newModel() {
        return new LlmContinuationTest.FailingFake();
    }

    private JSONObject status() throws Exception {
        this.clock.addAndGet(LlmService.COUNTS_MILLIS + 1L);
        return this.llm.status();
    }

    private void addPage(final String text) throws Exception {
        add(PAGE, "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/", "c1", LD.replace("muster-pflege", "beispiel-referenzen"),
                text, "Projekt-Beispiele");
        settleSync();
    }

    private void stepUntil(final String status) {
        for (int i = 0; i < 400 && !status.equals(llmStatus(PAGE)); i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals(status, llmStatus(PAGE));
    }

    private long rows(final String prefix) {
        return count("SELECT count(*) FROM kg_meta WHERE key GLOB '" + prefix + "*'");
    }

    @Test
    public void aProgressRowIsSmallAndGoesWithTheReading() throws Exception {
        addPage(LlmContinuationTest.veryLong());
        stepUntil("1:" + LlmQueue.MORE_TEXT);
        final JSONObject storage = status().getJSONObject("storage");
        assertEquals(1L, storage.getLong("progressRows"));
        assertTrue("a row of positions, not of text: " + storage, storage.getLong("progressBytes") < 512L);
        // a restart keeps it: the next pass reads on
        restart();
        assertEquals(1L, rows(LlmQueue.LAYOUT_PREFIX));
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        final JSONObject after = status().getJSONObject("storage");
        assertEquals(0L, after.getLong("progressRows"));
        assertEquals(0L, after.getLong("checkpointRows"));
    }

    @Test
    public void aProgressRowGoesWhenItsPageIsRemoved() throws Exception {
        addPage(LlmContinuationTest.veryLong());
        stepUntil("1:" + LlmQueue.MORE_TEXT);
        assertEquals(1L, rows(LlmQueue.LAYOUT_PREFIX));
        client().deleteById(PAGE);
        client().commit();
        settleSync();
        settleLlm(() -> rows(LlmQueue.LAYOUT_PREFIX) == 0L);
        assertEquals(0L, rows(LlmQueue.LAYOUT_PREFIX) + rows(LlmProgress.PREFIX));
        assertEquals(0L, count("SELECT count(*) FROM kg_llm_work"));
    }

    @Test
    public void checkpointsOfAFailedPassStayUntilThePageIsDoneThenGo() throws Exception {
        final String text = LlmLongPageTest.page();
        final LlmContinuationTest.FailingFake f = (LlmContinuationTest.FailingFake) this.model;
        f.failWhen = u -> u.contains(LlmLongPageTest.QUOTE) && !u.contains("Projekt 0:");
        f.failTimes = this.cfg.llmMaxAttempts;
        addPage(text);
        for (int i = 0; i < 400 && status().getJSONObject("processed").getLong("continuationRetries") < 1L; i++) {
            if (!this.llm.step()) {
                this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
            }
        }
        assertEquals("the failed pass keeps its progress", 1L, rows(LlmQueue.LAYOUT_PREFIX));
        settleLlm();
        assertEquals("1:" + LlmQueue.FULL_TEXT, llmStatus(PAGE));
        assertEquals(0L, rows(LlmQueue.LAYOUT_PREFIX) + rows(LlmProgress.PREFIX));
    }

    @Test
    public void theQueueWaitAndTheBacklogAreMeasured() throws Exception {
        addAll(List.of(imprint('W', 1, "c1"), imprint('W', 2, "c1"), imprint('W', 3, "c1")));
        settleSync();
        final JSONObject before = status().getJSONObject("backlog");
        assertEquals(1, before.getJSONArray("samples").length());
        assertTrue(before.isNull("pendingPerHour"));
        settleLlm();
        this.clock.addAndGet(LlmService.BACKLOG_SAMPLE_MILLIS);
        final JSONObject st = status();
        final JSONObject wait = st.getJSONObject("processed").getJSONObject("queueWait");
        assertEquals(3L, wait.getLong("firstReadings"));
        assertNotNull(wait.get("firstReadingAverageMillis"));
        final JSONObject backlog = st.getJSONObject("backlog");
        assertEquals(2, backlog.getJSONArray("samples").length());
        assertEquals(3L, backlog.getJSONArray("samples").getJSONObject(0).getLong("pending"));
        assertEquals(0L, backlog.getJSONArray("samples").getJSONObject(1).getLong("pending"));
        assertTrue("the backlog shrank: " + backlog, backlog.getLong("pendingPerHour") < 0L);
    }
}
