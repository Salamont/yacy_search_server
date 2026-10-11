package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.RequestBudget;

/** The context window the requests are planned against, and a visible refusal where a request cannot fit. */
public class LlmContextTest extends LlmFixture {

    private static final String A = "A00001a00001";

    /** The fixture's model with a window. */
    static final class WindowFake extends LlmServiceTest.Fake {
        volatile LlmClient.Window window;

        @Override
        public LlmClient.Window window() {
            return this.window;
        }
    }

    @Override
    protected LlmServiceTest.Fake newModel() {
        return new WindowFake();
    }

    private WindowFake fake() {
        return (WindowFake) this.model;
    }

    @Test
    public void aRequestThatCannotFitIsNotSentAndShowsAsFailed() throws Exception {
        fake().window = new LlmClient.Window(1200, true, 4096, false);
        add(A, "https://www.a1-pflege.de/impressum", "c1", LD.replace("muster-pflege", "a1-pflege"), RequestBudgetTestText.text(4000));
        settleSync();
        settleLlm();
        assertEquals("2:" + LlmService.CONTEXT_BUDGET, llmStatus(A));
        assertEquals("never sent", 0, this.model.calls.get());
        final JSONObject st = this.llm.status();
        assertEquals(1L, st.getJSONObject("processed").getLong("contextRefused"));
        assertEquals(1L, st.getJSONObject("documents").getLong("failed"));
        // a larger window configured for the service, then the admin retry: read once
        fake().window = new LlmClient.Window(8192, true, 4096, false);
        assertEquals(1, this.llm.retryFailed());
        settleLlm();
        assertEquals("1:", llmStatus(A));
        assertEquals("asked once, after the retry", 1, this.model.calls.get());
    }

    @Test
    public void theStatusShowsTheWindowAndWhetherItIsSent() throws Exception {
        JSONObject ctx = this.llm.status().getJSONObject("context");
        assertEquals(RequestBudget.DEFAULT_WINDOW, ctx.getInt("windowTokens"));
        assertEquals("assumed_default", ctx.getString("windowSource"));
        assertFalse(ctx.getBoolean("numCtxSent"));
        assertEquals(this.cfg.llmAnswerReserveTokens, ctx.getInt("answerReserveTokens"));
        fake().window = new LlmClient.Window(16384, true, 512, false);
        ctx = this.llm.status().getJSONObject("context");
        assertEquals(16384, ctx.getInt("windowTokens"));
        assertEquals("configured", ctx.getString("windowSource"));
        fake().window = new LlmClient.Window(8192, false, 4096, false, LlmClient.Window.DETECTED, 40960);
        ctx = this.llm.status().getJSONObject("context");
        assertEquals(8192, ctx.getInt("windowTokens"));
        assertEquals("detected", ctx.getString("windowSource"));
        assertFalse("a detected window is not sent", ctx.getBoolean("numCtxSent"));
        assertEquals(40960, ctx.getInt("modelMaxTokens"));
        fake().window = new LlmClient.Window(16384, true, 512, false);
        ctx = this.llm.status().getJSONObject("context");
        assertTrue(ctx.getBoolean("numCtxSent"));
        assertEquals("the reserve never exceeds the output cap", 512, ctx.getInt("answerReserveTokens"));
        assertFalse(ctx.getBoolean("schemaCounted"));
    }

    /** Page text without names of known entities, of a given length. */
    static final class RequestBudgetTestText {
        static String text(final int chars) {
            final StringBuilder sb = new StringBuilder("Impressum. Die Muster Pflege gGmbH betreibt das Haus Lindenhof in Berlin. ");
            while (sb.length() < chars) {
                sb.append("Wir pflegen und betreuen Menschen in ihrem Alltag mit Erfahrung und Sorgfalt. ");
            }
            return sb.substring(0, chars);
        }
    }
}
