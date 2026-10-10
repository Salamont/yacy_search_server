package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.*;

import net.yacy.scoutro.knowledge.extract.LlmClient;

import org.junit.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class LlmTimingTest {
    @Test
    public void requestBudgetAndExpiryReportTheirOwnDeferralReasons() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("manual", "[1]", "00:00", "00:00", "UTC", 0),
                        clock::get);
        t.run(25, 1);
        LlmTiming.Session session = t.begin("a");
        try (LlmClient.RequestPermit p = t.start(session, SAFE, RECORD)) {}
        try {
            t.chunk(session);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("manual_request_limit", e.reason);
        }
        t.run(25, 100);
        session = t.begin("a");
        clock.addAndGet(LlmTiming.MANUAL_DURATION);
        try {
            t.chunk(session);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("manual_expired", e.reason);
        }
    }

    @Test
    public void manualOverrideDoesNotPromiseAStartAfterItsExpiry() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("manual", "[1]", "00:00", "00:00", "UTC", 86400),
                        clock::get);
        t.restoreLastStart(1000L);
        t.run(25, 100);
        assertTrue(t.status(null).isNull("nextAllowedStart"));
        t.update(LlmSchedule.defaults());
        assertEquals(1000, t.status(null).getLong("nextAllowedStart"));
    }

    private static final LlmTiming.Protection SAFE = () -> {};
    private static final LlmTiming.Recorder RECORD = at -> {};

    @Test
    public void defaultAllowsTwoInflightRequestsWithoutSpacing() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t = new LlmTiming(LlmSchedule.defaults(), clock::get);
        LlmClient.RequestPermit a = t.start(t.begin("a"), SAFE, RECORD),
                b = t.start(t.begin("b"), SAFE, RECORD);
        assertEquals(2, t.status(null).getInt("runningRequests"));
        a.close();
        b.close();
        assertEquals(0, t.status(null).getInt("runningRequests"));
        t.restoreLastStart(99999L);
        try (LlmClient.RequestPermit p =
                t.start(
                        t.begin("c"),
                        SAFE,
                        RECORD)) {} // gap zero still automatic if clock moves backwards
    }

    @Test
    public void simultaneousWorkersShareSpacingWithoutCatchupCredits() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("automatic", "[1]", "00:00", "00:00", "UTC", 10),
                        clock::get);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger(), deferred = new AtomicInteger();
        try {
            Future<?> a = pool.submit(() -> attempt(t, "a", go, starts, deferred)),
                    b = pool.submit(() -> attempt(t, "b", go, starts, deferred));
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(1, starts.get());
        assertEquals(1, deferred.get());
        assertEquals(11000, t.status(null).getLong("nextAllowedStart"));
        clock.set(100000);
        try (LlmClient.RequestPermit p = t.start(t.begin("c"), SAFE, RECORD)) {}
        try {
            t.start(t.begin("d"), SAFE, RECORD);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("minimum_interval", e.reason);
        }
    }

    private static void attempt(
            LlmTiming t,
            String id,
            CountDownLatch go,
            AtomicInteger starts,
            AtomicInteger deferred) {
        try {
            go.await();
            try (LlmClient.RequestPermit p = t.start(t.begin(id), SAFE, RECORD)) {
                starts.incrementAndGet();
            }
        } catch (LlmTiming.Deferred e) {
            deferred.incrementAndGet();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void runningRequestMayFinishAfterWindowEndFurtherChunksWait() throws Exception {
        AtomicLong clock = new AtomicLong(LlmScheduleTest.at("2026-10-12T08:59:00Z"));
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("scheduled", "[1]", "08:00", "09:00", "UTC", 0),
                        clock::get);
        LlmTiming.Session s = t.begin("a");
        LlmClient.RequestPermit p = t.start(s, SAFE, RECORD);
        clock.set(LlmScheduleTest.at("2026-10-12T09:00:00Z"));
        assertEquals(1, t.status(null).getInt("runningRequests"));
        p.close();
        try {
            t.chunk(s);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("time_window", e.reason);
        }
    }

    @Test
    public void safetyAndTimeRecheckedBeforeEachActualRequest() throws Exception {
        AtomicLong clock = new AtomicLong(LlmScheduleTest.at("2026-10-12T08:59:00Z"));
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("scheduled", "[1]", "08:00", "09:00", "UTC", 0),
                        clock::get);
        AtomicInteger recorded = new AtomicInteger();
        try {
            t.start(
                    t.begin("a"),
                    () -> clock.set(LlmScheduleTest.at("2026-10-12T09:00:00Z")),
                    at -> recorded.incrementAndGet());
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("time_window", e.reason);
        }
        assertEquals(0, recorded.get());
    }

    @Test
    public void manualOverridesWindowButNotProtectionsSpacingOrStop() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("manual", "[1]", "00:00", "00:00", "UTC", 10),
                        clock::get);
        assertEquals("manual_only", t.workReason());
        t.run(2, 10);
        LlmTiming.Session s = t.begin("a");
        for (String reason :
                new String[] {
                    "manual_pause",
                    "load",
                    "heap",
                    "indexing_queue",
                    "disk",
                    "integrity",
                    "budget",
                    "circuit_breaker",
                    "collection_not_selected"
                }) {
            try {
                t.start(
                        s,
                        () -> {
                            throw new LlmTiming.Deferred(reason);
                        },
                        RECORD);
                fail();
            } catch (LlmTiming.Deferred e) {
                assertEquals(reason, e.reason);
            }
        }
        LlmClient.RequestPermit p = t.start(s, SAFE, RECORD);
        try {
            t.start(t.begin("b"), SAFE, RECORD);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("minimum_interval", e.reason);
        }
        t.stop();
        assertEquals("stopping", t.status(null).getJSONObject("manual").getString("state"));
        p.close();
        assertEquals("stopped", t.status(null).getJSONObject("manual").getString("state"));
        try {
            t.chunk(s);
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("manual_stopped", e.reason);
        }
    }

    @Test
    public void documentLimitRetainsOnlyUnfinishedClaimedDocuments() throws Exception {
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("manual", "[1]", "00:00", "00:00", "UTC", 0),
                        () -> 1000);
        t.run(1, 100);
        LlmTiming.Session s = t.begin("a");
        assertTrue(t.claimOnly().contains("a"));
        try {
            t.begin("b");
            fail();
        } catch (LlmTiming.Deferred e) {
            assertEquals("manual_document_limit", e.reason);
        }
        t.finished(s, true);
        assertEquals("document_limit", t.status(null).getJSONObject("manual").getString("state"));
    }

    @Test
    public void requestLimitDurationAndEmptyBacklogFinishRun() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t =
                new LlmTiming(
                        LlmScheduleTest.plan("manual", "[1]", "00:00", "00:00", "UTC", 0),
                        clock::get);
        t.run(10, 1);
        try (LlmClient.RequestPermit p = t.start(t.begin("a"), SAFE, RECORD)) {}
        assertEquals("request_limit", t.status(null).getJSONObject("manual").getString("state"));
        t.run(10, 10);
        clock.addAndGet(LlmTiming.MANUAL_DURATION);
        assertEquals("manual_only", t.workReason());
        assertEquals("expired", t.status(null).getJSONObject("manual").getString("state"));
        t.run(10, 10);
        t.empty(true);
        assertEquals("completed", t.status(null).getJSONObject("manual").getString("state"));
    }

    @Test
    public void timingChangeRetainsInFlightCountersManualStateAndActualStart() throws Exception {
        AtomicLong clock = new AtomicLong(1000);
        LlmTiming t = new LlmTiming(LlmSchedule.defaults(), clock::get);
        t.run(2, 10);
        LlmClient.RequestPermit p = t.start(t.begin("a"), SAFE, RECORD);
        t.update(LlmScheduleTest.plan("manual", "[1]", "01:00", "02:00", "Europe/Berlin", 10));
        assertEquals(1, t.status(null).getInt("runningRequests"));
        assertEquals(1000, t.status(null).getLong("lastActualStart"));
        assertNull(t.workReason());
        p.close();
    }
}
