/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import net.yacy.ai.LLM.LLMModel;
import net.yacy.ai.LLM.LLMType;

/**
 * The fields the log report logs about a model call and its failure (0.10.7). They name the function, the service type,
 * the model, the size of the prompt and its estimated tokens, the output cap, the context window Scoutro assumes and
 * whether prompt and cap exceed it, and for an HTTP error what the provider said; never the prompt or a key. The report's
 * own prompt budget sizes the payload to the window minus the output cap at four characters per token, which log lines do
 * not reach: with a configured window the prompt and the cap can exceed it, which a strict provider refuses with HTTP 400.
 * Whether that refused the production calls is not known; the fields show it the next time.
 */
public class LogReportDiagnosticsTest {

    private static LLMModel model(final int maxTokens, final int numCtx) {
        return new LLMModel(new LLM("https://router.example/api", "sk-test-0123456789abcdef", maxTokens, numCtx, LLMType.OPENROUTER),
                "example/model", false, false);
    }

    /** The hourly prompt of the report for {@code n} log lines, built by the report's own code and budget. */
    private static String hourlyPrompt(final LLMModel model, final int maxTokens, final int n) {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add("I 2026/10/14 07:" + (i % 60) + ":00 CRAWLER LOGLINE-MARKER fetched https://www.example.org/p/" + i + " in 120 ms");
        }
        final LocalDateTime hour = LocalDateTime.of(2026, 10, 14, 7, 0);
        return LogReportService.hourlyPrompt(hour, lines, LogReportService.classifyNoise(lines),
                LogReportService.promptPayloadCharBudget(model, maxTokens));
    }

    /**
     * A configured window of 131,072 tokens and an output cap of 65,536: the report gives the log lines 65,536 tokens at four
     * characters each; at three characters per token the prompt and the cap exceed the window.
     */
    @Test
    public void aConfiguredWindowCanBeExceededByThePromptAndTheOutputCap() {
        final LLMModel m = model(65_536, 131_072);
        assertEquals(65_536 * 4, LogReportService.promptPayloadCharBudget(m, 65_536));
        final String prompt = hourlyPrompt(m, 65_536, 5_000);
        final String fields = LogReportService.callFields(m, prompt, 65_536);
        assertTrue(fields, fields.contains(" function=logreport service=OPENROUTER "));
        assertTrue(fields, fields.contains(" numCtx=131072 numCtxConfigured=true windowExceeded=true"));
        assertTrue(LogReportService.estimatedPromptTokens(prompt) + 65_536 > 131_072);
        assertFalse(fields, fields.contains("LOGLINE-MARKER"));
    }

    /** Without a configured window the assumed one says nothing about the provider's: the fields say it is assumed. */
    @Test
    public void anAssumedWindowIsMarkedAsAssumed() {
        final LLMModel m = model(65_536, 0);
        final String fields = LogReportService.callFields(m, hourlyPrompt(m, 65_536, 50), 65_536);
        assertTrue(fields, fields.contains(" numCtx=4096 numCtxConfigured=false "));
    }

    /** A small prompt within a configured window is not marked. */
    @Test
    public void aCallWithinItsWindowIsNotMarked() {
        final LLMModel m = model(2_048, 32_768);
        final String fields = LogReportService.callFields(m, hourlyPrompt(m, 2_048, 20), 2_048);
        assertTrue(fields, fields.contains(" windowExceeded=false"));
    }

    /** The failure of a call: the call's fields and the provider's reason, without the prompt and without the key. */
    @Test
    public void aFailureNamesTheProvidersReasonAndNotThePrompt() {
        final LLMModel m = model(65_536, 131_072);
        final String prompt = hourlyPrompt(m, 65_536, 5_000);
        final LlmHttpError e = LlmHttpError.of(400, "{\"error\":{\"message\":\"This endpoint's maximum context length is 131072 tokens."
                + " However, you requested about 160000 tokens.\",\"code\":400,\"metadata\":{\"provider_name\":\"Example Provider\"}}}",
                200, "sk-test-0123456789abcdef");
        final String fields = LogReportService.failureFields(e, m, prompt, 65_536);
        assertTrue(fields, fields.startsWith(" model=example/model promptChars=" + prompt.length() + " maxTokens=65536 function=logreport"));
        assertTrue(fields, fields.contains(" httpStatus=400 providerCode=400 providerParam=- provider=Example Provider providerMessage=\"This"
                + " endpoint's maximum context length is 131072 tokens. However, you requested about 160000 tokens.\" errorBodyBytes=200"));
        assertFalse(fields, fields.contains("LOGLINE-MARKER"));
        assertFalse(fields, fields.contains("0123456789abcdef"));
        // a failure without an HTTP status
        assertTrue(LogReportService.failureFields(new java.io.IOException("model returned an empty report"), m, prompt, 65_536)
                .endsWith(" httpStatus=-"));
    }
}
