package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.solr.common.SolrInputDocument;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Bounded resumption of the LLM tier without a restart: an input the worker cannot confirm is retried with a growing
 * backoff and then shown as failed, never dropped unmarked and never retried without end.
 */
public class LlmResumeTest extends LlmFixture {

    private static final String A = "A00001a00001";

    @Test
    public void anInputThatIsNeverConfirmedEndsVisiblyFailedAfterBoundedTries() throws Exception {
        addAll(List.of(imprint('A', 1, "c1")));
        settleSync();
        final String[] truth = strings("SELECT hex(input_hash) || ':' || hex(content_hash) FROM kg_doc WHERE doc_id = '" + A + "'").get(0)
                .split(":");
        exec("UPDATE kg_doc SET input_hash = randomblob(16), content_hash = randomblob(16) WHERE doc_id = '" + A + "'");
        int steps = 0;
        for (; steps < 40 && !llmStatus(A).startsWith("2"); steps++) {
            this.llm.step();
            assertEquals("never dropped unmarked while it waits", 0L, stranded());
            this.clock.addAndGet(LlmService.UNCONFIRMED_MAX_BACKOFF_MILLIS + 1000L);
        }
        assertEquals("2:" + LlmQueue.INPUT_UNCONFIRMED, llmStatus(A));
        assertEquals("nothing was asked about an input that was never confirmed", 0, this.model.calls.get());
        assertEquals(0L, count("SELECT count(*) FROM kg_llm_work"));
        final int limit = this.cfg.llmMaxAttempts + LlmService.UNCONFIRMED_EXTRA_ATTEMPTS;
        assertTrue("bounded: " + steps, steps <= limit + 2);
        assertEquals(limit - 1, this.llm.status().getJSONObject("processed").getLong("unconfirmedRetries"));
        assertEquals(1L, this.llm.status().getJSONObject("processed").getLong("inputUnconfirmed"));
        // after the sync caught up, the admin retry makes it to do again and it is read once
        exec("UPDATE kg_doc SET input_hash = x'" + truth[0] + "', content_hash = x'" + truth[1] + "' WHERE doc_id = '" + A + "'");
        assertEquals(1, this.llm.retryFailed());
        settleLlm();
        assertEquals("1:", llmStatus(A));
        assertEquals(1, this.model.calls.get());
    }

    private List<SolrInputDocument> imprints(final char prefix, final int from, final int to, final String collection) {
        final List<SolrInputDocument> out = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(imprint(prefix, i, collection));
        }
        return out;
    }

    private long frontier(final String collection) throws Exception {
        final JSONArray lanes = this.llm.status().getJSONObject("selection").getJSONArray("collections");
        for (int i = 0; i < lanes.length(); i++) {
            final JSONObject lane = lanes.getJSONObject(i);
            if (collection.equals(lane.getString("collection"))) {
                return lane.getLong("frontier");
            }
        }
        return -1L;
    }

    @Test
    public void theScanPositionsSurviveARestartAndNothingIsReadTwice() throws Exception {
        addAll(imprints('B', 1, 40, "c1"));
        settleSync();
        for (int i = 0; i < 5; i++) {
            this.llm.step();
        }
        final List<String> before = strings("SELECT value FROM kg_meta WHERE key GLOB '" + LlmQueue.CURSOR_PREFIX + "*' ORDER BY key");
        assertEquals("one persisted position per collection with pages", 1, before.size());
        final long frontier = Long.parseLong(before.get(0).split(":")[0]);
        assertTrue("the frontier moved", frontier > 0L);
        restart();
        assertEquals("the restart keeps the positions", before,
                strings("SELECT value FROM kg_meta WHERE key GLOB '" + LlmQueue.CURSOR_PREFIX + "*' ORDER BY key"));
        settleLlm();
        assertEquals(40L, count("SELECT count(*) FROM kg_doc WHERE llm_status = 1"));
        assertEquals("every page asked once (one chunk each), also across the restart", 40, this.model.calls.get());
        assertEquals(0L, stranded());
    }

    @Test
    public void aPageLeftUnqueuedBehindTheFrontierIsFoundWithoutRestart() throws Exception {
        addAll(imprints('B', 1, 10, "c1"));
        settleSync();
        settleLlm();
        assertEquals(10, this.model.calls.get());
        // as left by 0.10.0: pages pending behind the scan, neither marked nor queued
        exec("UPDATE kg_doc SET llm_status = NULL, llm_hash = NULL, llm_reason = NULL WHERE doc_id IN ('B00002b00002', 'B00005b00005')");
        assertEquals(2L, stranded());
        // the crawl goes on; no restart
        for (int i = 0; i < 6; i++) {
            addAll(List.of(imprint('N', i + 1, "c1")));
            settleSync();
            this.llm.step();
            this.clock.addAndGet(LlmService.REVISIT_PAUSE_MILLIS / 4);
        }
        settleLlm();
        assertEquals(0L, stranded());
        assertEquals("1:", llmStatus("B00002b00002"));
        assertEquals("1:", llmStatus("B00005b00005"));
        assertTrue(this.llm.status().getJSONObject("processed").getLong("requeuedBehindFrontier") >= 2L);
    }

    @Test
    public void everyCollectionGetsAnEqualShareOfAPass() throws Exception {
        addAll(imprints('B', 1, 300, "c1"));
        settleSync();
        addAll(imprints('Z', 1, 30, "c2"));
        settleSync();
        this.llm.step();
        assertEquals("the small collection is queued in full", 30L, count("SELECT count(*) FROM kg_llm_work WHERE doc_id GLOB 'Z*'"));
        assertEquals("the large one gets the rest of the pass", LlmService.SCAN_BATCH - 30L,
                count("SELECT count(*) FROM kg_llm_work WHERE doc_id GLOB 'B*'") + count("SELECT count(*) FROM kg_doc WHERE llm_status IS NOT NULL"));
    }

    @Test
    public void twoWorkersReadEveryPageOnceAcrossCollections() throws Exception {
        addAll(imprints('B', 1, 30, "c1"));
        addAll(imprints('Z', 1, 30, "c2"));
        settleSync();
        final AtomicBoolean done = new AtomicBoolean();
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < 2; w++) {
            workers.add(pool.submit(() -> {
                while (!done.get()) {
                    if (!this.llm.step()) {
                        this.clock.addAndGet(LlmService.SCAN_PAUSE_MILLIS + 1000L);
                        try {
                            Thread.sleep(2L);
                        } catch (final InterruptedException e) {
                            return;
                        }
                    }
                }
            }));
        }
        try {
            final long deadline = System.currentTimeMillis() + 120_000L;
            while (System.currentTimeMillis() < deadline && count("SELECT count(*) FROM kg_doc WHERE llm_status = 1") < 60L) {
                Thread.sleep(20L);
            }
        } finally {
            done.set(true);
            for (final Future<?> f : workers) {
                f.get(30, TimeUnit.SECONDS);
            }
            pool.shutdownNow();
        }
        assertEquals(60L, count("SELECT count(*) FROM kg_doc WHERE llm_status = 1"));
        assertEquals("each page asked once by two workers", 60, this.model.calls.get());
        assertEquals("each page published once", 60L, this.llm.status().getJSONObject("processed").getLong("published"));
        assertEquals(0L, count("SELECT count(*) FROM kg_llm_work"));
        assertEquals(0L, stranded());
    }
}
