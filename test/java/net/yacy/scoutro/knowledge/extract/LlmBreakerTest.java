package net.yacy.scoutro.knowledge.extract;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

/** The LLM tier's circuit breaker (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). */
public class LlmBreakerTest {

    @Test
    public void opensAfterConsecutiveFailuresAndAllowsOneTrialAfterThePause() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final LlmBreaker b = new LlmBreaker(3, 60L * 60_000L, now::get);
        assertTrue(b.allow());
        b.failure("timeout");
        b.failure("timeout");
        assertTrue("two failures do not open it", b.allow());
        b.success();
        b.failure("timeout");
        b.failure("timeout");
        assertTrue("a success resets the count", b.allow());
        b.failure("ConnectException");
        assertTrue(b.open());
        assertFalse(b.allow());
        assertEquals("ConnectException", b.status().optString("lastFailure"));
        now.addAndGet(LlmBreaker.FIRST_BACKOFF_MILLIS - 1L);
        assertFalse(b.allow());
        now.addAndGet(1L);
        assertTrue("one trial after the pause", b.allow());
        assertFalse("only one", b.allow());
        assertTrue(b.open());
        b.failure("timeout");
        assertFalse(b.allow());
        assertEquals("the pause doubles", 2L * LlmBreaker.FIRST_BACKOFF_MILLIS, b.status().optLong("backoffMillis"));
        now.addAndGet(2L * LlmBreaker.FIRST_BACKOFF_MILLIS);
        assertTrue(b.allow());
        b.success();
        assertFalse(b.open());
        assertTrue(b.allow());
        assertEquals(0L, b.status().optLong("backoffMillis"));
        assertEquals(2L, b.status().optLong("timesOpened"));
    }

    @Test
    public void backoffIsCapped() {
        final AtomicLong now = new AtomicLong(0L);
        final LlmBreaker b = new LlmBreaker(1, 20L * 60_000L, now::get);
        long last = 0L;
        for (int i = 0; i < 10; i++) {
            b.failure("x");
            last = b.status().optLong("backoffMillis");
            now.addAndGet(last);
            assertTrue(b.allow());
        }
        assertEquals(20L * 60_000L, last);
    }
}
