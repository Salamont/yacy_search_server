/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import com.sun.net.httpserver.HttpServer;

import net.yacy.ai.LLM.LLMModel;
import net.yacy.ai.LLM.LLMType;

/**
 * The budget of the log report's model call (0.10.7), with neutral log lines and a mocked strict endpoint.
 *
 * <p>Production (OpenCode, 2026-10-14) had:
 * <ul>
 * <li>six failed calls with about 263,600 prompt characters and one successful call with 39,775;</li>
 * <li>{@code max_tokens} 65,536 and {@code numCtx} 131,072 on all of them;</li>
 * <li>{@code stream: true}, no retry, no provider message.</li>
 * </ul>
 *
 * <p>The report sized the log lines alone to the window minus the output cap at four characters per token: 262,144
 * characters. The template and the noise summary were not counted, and log lines have fewer characters per token. A strict
 * endpoint that counts three characters per token for logs refuses such a prompt with the cap as exceeding its window: the
 * hypothesis, until a provider's message confirms it. The whole call is now planned against the window: template and
 * instructions included, log lines at {@link LogReportService#LOG_CHARS_PER_TOKEN}, a margin, warnings and errors first,
 * and what is omitted is said.
 */
public class LogReportDiagnosticsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static final int WINDOW = 131_072;
    private HttpServer server;
    private final List<Integer> requested = new ArrayList<>();

    /** A strict endpoint: three characters per token, the output cap counted, beyond the window HTTP 400. */
    @Before
    public void start() throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.server.createContext("/v1/chat/completions", exchange -> {
            final String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int chars = 0;
            int max = 0;
            int window = WINDOW;
            try {
                final JSONObject r = new JSONObject(body);
                final JSONArray m = r.getJSONArray("messages");
                for (int i = 0; i < m.length(); i++) {
                    chars += m.getJSONObject(i).optString("content").length();
                }
                max = r.optInt("max_tokens");
                window = r.optInt("num_ctx", WINDOW);
            } catch (final org.json.JSONException e) {
                chars = Integer.MAX_VALUE / 4;
            }
            final int tokens = chars / 3 + max;
            this.requested.add(tokens);
            final byte[] b;
            final int status;
            if (tokens > window) {
                status = 400;
                b = ("{\"error\":{\"message\":\"maximum context length is " + window + " tokens, requested " + tokens + "\",\"code\":"
                        + "\"context_length_exceeded\",\"param\":\"messages\"}}").getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                b = "data: {\"choices\":[{\"delta\":{\"content\":\"Bericht\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, b.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(b);
            }
        });
        this.server.start();
    }

    @After
    public void stop() {
        this.server.stop(0);
    }

    private LLMModel model(final int maxTokens, final int numCtx) {
        return new LLMModel(new LLM("http://127.0.0.1:" + this.server.getAddress().getPort(), "sk-test-0123456789abcdef", maxTokens, numCtx,
                LLMType.OPENROUTER), "example/model", false, false);
    }

    /** Neutral log lines of an hour; every 50th is a warning, the first one an error. */
    private static List<String> hour(final int n) {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final String level = i == 0 ? "E" : i % 50 == 0 ? "W" : "I";
            lines.add(level + " 2026/10/14 07:" + String.format("%02d", i % 60) + ":00 CRAWLER LOGLINE-MARKER fetched"
                    + " https://www.example.org/section/" + i + "/page?id=" + (i * 7919) + " status=200 in " + (i % 900) + " ms");
        }
        return lines;
    }

    private static LogReportService.Prompt prompt(final LLMModel m, final List<String> lines) {
        return LogReportService.hourlyPrompt(LocalDateTime.of(2026, 10, 14, 7, 0), lines, LogReportService.classifyNoise(lines),
                LogReportService.callBudget(m));
    }

    private String send(final LLMModel m, final LogReportService.Prompt p) throws IOException {
        final LogReportService.CallBudget b = LogReportService.callBudget(m);
        LogReportService.refuseIfTooLarge(p, b);
        return m.llm.chatStream(m.model, "You evaluate YaCy runtime logs.", p.text, b.output, null);
    }

    /**
     * The production settings and a full hour. Before, the prompt had about 263,000 characters, which the strict endpoint
     * refuses with the cap: the reproduction, run against the code before. Now the whole prompt fits the window minus the cap
     * and the margin, the endpoint answers, the cap sent is the configured one, and the omitted lines are counted and named.
     * All warnings and errors are kept.
     */
    @Test
    public void theReportOfAFullHourFitsAStrictWindowWithItsOutputCap() throws IOException {
        final LLMModel m = model(65_536, WINDOW);
        final LogReportService.CallBudget b = LogReportService.callBudget(m);
        assertEquals("the configured cap is sent", 65_536, b.output);
        assertEquals(WINDOW - 6_554 - 65_536, b.prompt);
        final LogReportService.Prompt p = prompt(m, hour(3_000));
        assertTrue(p.estimatedTokens + "", p.estimatedTokens <= b.prompt);
        assertTrue("lines were omitted: " + p.omitted, p.omitted > 0);
        assertEquals("every warning and error is kept", 0, p.omittedPriority);
        assertTrue(p.text, p.text.contains("(" + p.omitted + " of 3000 log lines omitted to fit the model's context window: 0 warning or"
                + " error lines and " + p.omitted + " other lines; warnings and errors were kept first, then the newest lines)"));
        assertTrue("the error of the first minute is kept", p.text.contains("E 2026/10/14 07:00:00"));
        assertEquals("Bericht", send(m, p));
        assertTrue(this.requested.get(0) <= WINDOW);
        final String fields = LogReportService.callFields(m, p, b);
        assertTrue(fields, fields.contains(" function=logreport service=OPENROUTER estPromptTokens=" + p.estimatedTokens + " promptTokenBudget="
                + b.prompt + " maxTokensConfigured=65536 maxTokensSent=65536 numCtx=131072 numCtxConfigured=true windowExceeded=false"));
        assertTrue(fields, fields.endsWith(" inputlines=3000 sentlines=" + p.sent + " omittedlines=" + p.omitted));
        assertFalse(fields, fields.contains("LOGLINE-MARKER"));
    }

    /** An hour that fits is sent whole: nothing is omitted, no note. */
    @Test
    public void anHourThatFitsIsSentWhole() throws IOException {
        final LLMModel m = model(65_536, WINDOW);
        final LogReportService.Prompt p = prompt(m, hour(300));
        assertEquals(0, p.omitted);
        assertEquals(300, p.sent);
        assertFalse(p.text.contains("omitted to fit"));
        assertEquals("Bericht", send(m, p));
    }

    /** A configured window that the output cap would fill: the cap sent is lowered so the prompt keeps a quarter of it. */
    @Test
    public void anOutputCapFillingAConfiguredWindowIsLoweredForTheCall() throws IOException {
        final LLMModel m = model(65_536, 32_768);
        final LogReportService.CallBudget b = LogReportService.callBudget(m);
        assertEquals(32_768 - 1_639 - 8_192, b.output);
        assertEquals(8_192, b.prompt);
        assertEquals("Bericht", send(m, prompt(m, hour(3_000))));
    }

    /** An assumed window (not configured): the configured cap is sent as before; the fields say the window is assumed. */
    @Test
    public void anAssumedWindowKeepsTheConfiguredCap() {
        final LLMModel m = model(65_536, 0);
        final LogReportService.CallBudget b = LogReportService.callBudget(m);
        assertEquals(65_536, b.output);
        assertEquals(1_024, b.prompt);
        final String fields = LogReportService.callFields(m, prompt(m, hour(50)), b);
        assertTrue(fields, fields.contains(" numCtx=4096 numCtxConfigured=false "));
    }

    /** Warnings and errors are kept before other lines; the other lines are the newest ones; the order stays. */
    @Test
    public void warningsAndErrorsAreKeptFirstAndTheOrderStays() {
        final List<String> lines = List.of("I 2026/10/14 07:00:00 a", "W 2026/10/14 07:01:00 early warning", "I 2026/10/14 07:02:00 b",
                "I 2026/10/14 07:03:00 c", "E 2026/10/14 07:04:00 error", "I 2026/10/14 07:05:00 newest");
        final int budget = lines.get(1).length() + lines.get(4).length() + lines.get(5).length() + 3;
        final LogReportService.Selection s = LogReportService.selectLines(lines, budget);
        assertEquals(lines.get(1) + "\n" + lines.get(4) + "\n" + lines.get(5) + "\n", s.text);
        assertEquals(3, s.omitted);
        assertEquals(0, s.omittedPriority);
        final LogReportService.Selection tight = LogReportService.selectLines(lines, lines.get(4).length() + 1);
        assertEquals(lines.get(4) + "\n", tight.text);
        assertEquals("the early warning is counted as omitted", 1, tight.omittedPriority);
    }

    /** The daily prompt keeps the newest whole hourly reports and names how many were omitted. */
    @Test
    public void theDailyPromptKeepsTheNewestWholeReports() throws IOException {
        final List<File> reports = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            final File f = this.tmp.newFile(String.format("log-report-2026-10-14-%02d.md", h));
            Files.writeString(f.toPath(), "# Report " + h + "\n" + "Summary of hour " + h + ". ".repeat(1) + "x".repeat(4_000) + "\n");
            reports.add(f);
        }
        final LLMModel m = model(4_096, 16_384);
        final LogReportService.Prompt p = LogReportService.dailyPrompt(LocalDate.of(2026, 10, 14), reports, LogReportService.callBudget(m));
        assertTrue(p.omitted > 0);
        assertTrue(p.text, p.text.contains("(" + p.omitted + " of 24 hourly reports omitted to fit the model's context window; the newest"
                + " reports were kept)"));
        assertTrue("the newest report is kept", p.text.contains("# Report 23"));
        assertFalse("the oldest is not", p.text.contains("# Report 0\n"));
        assertTrue(p.estimatedTokens <= LogReportService.callBudget(m).prompt);
    }

    /** A prompt that cannot fit a configured window even after its budget is not sent. */
    @Test
    public void aPromptThatCannotFitIsNotSent() {
        final LLMModel m = model(65_536, WINDOW);
        final LogReportService.Prompt big = new LogReportService.Prompt("x", "lines", 1, 1, 0, 0, WINDOW);
        try {
            send(m, big);
            fail("sent");
        } catch (final IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("not sent"));
        }
        assertTrue("nothing reached the endpoint", this.requested.isEmpty());
    }

    /** The failure of a call: the call's fields and the provider's reason, without the prompt and without the key. */
    @Test
    public void aFailureNamesTheProvidersReasonAndNotThePrompt() {
        final LLMModel m = model(65_536, WINDOW);
        final LogReportService.Prompt p = prompt(m, hour(3_000));
        final LogReportService.CallBudget b = LogReportService.callBudget(m);
        final LlmHttpError e = LlmHttpError.of(400, "{\"error\":{\"message\":\"This endpoint's maximum context length is 131072 tokens."
                + " However, you requested about 160000 tokens.\",\"code\":400,\"metadata\":{\"provider_name\":\"Example Provider\"}}}",
                200, "sk-test-0123456789abcdef");
        final String fields = LogReportService.failureFields(e, m, p, b);
        assertTrue(fields, fields.startsWith(" model=example/model promptChars=" + p.text.length() + " maxTokens=65536 function=logreport"));
        assertTrue(fields, fields.contains(" httpStatus=400 providerCode=400 providerParam=- provider=Example Provider providerMessage=\"This"
                + " endpoint's maximum context length is 131072 tokens. However, you requested about 160000 tokens.\" errorBodyBytes=200"));
        assertFalse(fields, fields.contains("LOGLINE-MARKER"));
        assertFalse(fields, fields.contains("0123456789abcdef"));
        assertTrue(LogReportService.failureFields(new IOException("model returned an empty report"), m, p, b).endsWith(" httpStatus=-"));
    }
}
