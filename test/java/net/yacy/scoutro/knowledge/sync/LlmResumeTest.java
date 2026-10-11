package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

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
}
