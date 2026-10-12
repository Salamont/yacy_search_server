package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.KgJson;

/** The bounds of the refusal diagnostics (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.7): count, per reason, bytes and age. */
public class RefusalSamplesTest {

    private static JSONObject sample(final String reason, final int excerptChars) {
        return KgJson.obj("reason", reason, "item", "claim", "excerpt", "q".repeat(excerptChars));
    }

    @Test
    public void aReasonKeepsItsNewestSamplesOnly() throws Exception {
        final RefusalSamples s = new RefusalSamples();
        for (int i = 0; i < 30; i++) {
            s.add(1000L + i, "AAAAAAdoc" + i, 0, "example.org", "h", sample("claim_unresolved_object", 10));
        }
        s.add(2000L, "BBBBBBdoc", 0, "example.org", "h", sample("claim_self_reference", 10));
        final JSONObject all = s.list(3000L, null, 100);
        assertEquals(RefusalSamples.PER_REASON + 1, all.getJSONArray("samples").length());
        assertEquals("newest first", "claim_self_reference", all.getJSONArray("samples").getJSONObject(0).getString("reason"));
        final JSONObject one = s.list(3000L, "claim_unresolved_object", 5);
        assertEquals(5, one.getJSONArray("samples").length());
        assertEquals("AAAAAAdoc29", one.getJSONArray("samples").getJSONObject(0).getString("docId"));
        assertEquals((long) RefusalSamples.PER_REASON, one.getJSONObject("summary").getJSONObject("byReason").getLong("claim_unresolved_object"));
    }

    @Test
    public void countAndBytesAreBounded() throws Exception {
        final RefusalSamples s = new RefusalSamples();
        for (int i = 0; i < 2000; i++) {
            s.add(1000L + i, "AAAAAAdoc" + i, i, "example.org", "h", sample("reason_" + (i % 200), 160));
        }
        final JSONObject summary = s.summary(5000L);
        assertTrue(summary.toString(), summary.getLong("size") <= RefusalSamples.MAX);
        assertTrue(summary.toString(), summary.getLong("bytes") <= RefusalSamples.MAX_BYTES);
        assertEquals(2000L, summary.getLong("added"));
        assertFalse("the summary holds no excerpt", summary.toString().contains("qqqq"));
    }

    @Test
    public void samplesExpire() throws Exception {
        final RefusalSamples s = new RefusalSamples();
        s.add(1000L, "AAAAAAdoc", 0, "example.org", "h", sample("ungrounded", 10));
        assertEquals(1L, s.summary(1000L + RefusalSamples.KEEP_MILLIS).getLong("size"));
        assertEquals(0L, s.summary(1001L + RefusalSamples.KEEP_MILLIS).getLong("size"));
        assertEquals(0L, s.summary(1001L + RefusalSamples.KEEP_MILLIS).getLong("bytes"));
    }
}
