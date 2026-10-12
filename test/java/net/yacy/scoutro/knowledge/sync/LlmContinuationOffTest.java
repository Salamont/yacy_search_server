package net.yacy.scoutro.knowledge.sync;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.yacy.scoutro.knowledge.KgConfig;

/** A long page with continuations switched off ({@code scoutro.kg.llm.continuationChunks=0}): the cut stays visible. */
public class LlmContinuationOffTest extends LlmFixture {

    static final String PAGE = LlmLongPageTest.PAGE;

    @Override
    protected String[] settings() {
        final String[] base = super.settings();
        final String[] out = java.util.Arrays.copyOf(base, base.length + 2);
        out[base.length] = KgConfig.LLM_CONTINUATION_CHUNKS;
        out[base.length + 1] = "0";
        return out;
    }

    @Test
    public void theFirstReadingEndsTheWorkAndMarksTheCut() throws Exception {
        add(PAGE, "https://www.beispiel-referenzen.de/referenzen/projekt-beispiele/", "c1", LD.replace("muster-pflege", "beispiel-referenzen"),
                LlmLongPageTest.page(), "Projekt-Beispiele");
        settleSync();
        settleLlm();
        assertEquals("1:" + LlmQueue.TEXT_CUT, llmStatus(PAGE));
        assertEquals(net.yacy.scoutro.knowledge.extract.LlmExtractor.chunks(LlmLongPageTest.page(), this.cfg.extractMaxInputChars).size(),
                this.model.calls.get());
    }
}
