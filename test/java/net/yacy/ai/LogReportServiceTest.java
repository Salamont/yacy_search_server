/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.ai;

import static org.junit.Assert.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.io.File;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import net.yacy.server.serverSwitch;

/** Real persisted config/report writes, isolated synthetic logs and a counting local model. */
public class LogReportServiceTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();
    private HttpServer endpoint;
    private serverSwitch config;
    private LogReportService service;
    private final AtomicInteger calls = new AtomicInteger();
    private List<String> lines = List.of();
    @Before public void setup() throws Exception {
        this.endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.endpoint.createContext("/v1/chat/completions", exchange -> {
            this.calls.incrementAndGet(); exchange.getRequestBody().readAllBytes();
            final byte[] out = "data: {\"choices\":[{\"delta\":{\"content\":\"Bericht\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length); exchange.getResponseBody().write(out); exchange.close();
        });
        this.endpoint.start();
        this.config = new serverSwitch(this.tmp.getRoot(), new File("."), "defaults/yacy.init", "settings.conf");
        this.config.setConfig(LogReportService.CONFIG_REPORT_DIR, "reports");
        final LLM.LLMModel model = new LLM.LLMModel(new LLM("http://127.0.0.1:" + this.endpoint.getAddress().getPort(), "", 1024, 8192, LLM.LLMType.OPENAI), "fixture", false, false);
        this.service = new LogReportService(this.config, (run, caller) -> model) {
            @Override public List<String> readRuntimeLogLines() { return lines; }
        };
    }
    @After public void close() { this.endpoint.stop(0); }
    private String line(final LocalDateTime at) {
        return "W " + at.format(DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss")) + " TEST synthetic observed failure";
    }
    @Test public void disabledAutomaticScheduleMakesNoCallsAndManualIsIndependent() throws Exception {
        final LocalDateTime now = LocalDateTime.now().withMinute(0).withSecond(0).withNano(0);
        this.lines = List.of(line(now.minusHours(1)), line(now.plusMinutes(1)));
        this.service.control().reset(Instant.now().minusSeconds(7200), ZoneId.systemDefault(), 0, LocalTime.MIDNIGHT);
        this.config.setConfig(LogReportService.CONFIG_ENABLED, false);
        this.service.schedulerTick(Instant.now()); assertEquals(0, this.calls.get());
        assertNotNull(this.service.generateHourReport(now.minusHours(1), false, null)); assertEquals(1, this.calls.get());
        try { this.service.generateHourReport(now.minusHours(1), false, null); fail(); } catch (java.io.IOException expected) { }
        assertEquals(1, this.calls.get());
        assertNotNull(this.service.generateHourReport(now.minusHours(1), true, null)); assertEquals(2, this.calls.get());
        final serverSwitch restarted = new serverSwitch(this.tmp.getRoot(), new File("."), "defaults/yacy.init", "settings.conf");
        assertFalse(restarted.getConfigBool(LogReportService.CONFIG_ENABLED, true));
    }
    @Test public void schedulerHonorsIntervalAndDoesNotReplayDeletedHoursOrDailyCompaction() throws Exception {
        final LocalDateTime hour = LocalDateTime.now().minusHours(2).withMinute(0).withSecond(0).withNano(0);
        this.lines = List.of(line(hour.plusMinutes(2)), line(hour.plusHours(1).plusMinutes(2)));
        this.config.setConfig(LogReportService.CONFIG_ENABLED, true);
        this.config.setConfig(LogReportService.CONFIG_PERIOD_MINUTES, 120);
        this.service.control().reset(hour.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault(), 0, LocalTime.MAX);
        this.service.schedulerTick(Instant.now()); assertEquals(2, this.calls.get());
        this.service.schedulerTick(Instant.now().plusSeconds(60)); assertEquals(2, this.calls.get());
        Files.delete(new File(this.service.getReportDirectory(), LogReportService.hourlyReportFilename(hour)).toPath());
        this.service.schedulerTick(Instant.now().plusSeconds(7201)); assertEquals(2, this.calls.get());
        final LocalDate yesterday = LocalDate.now().minusDays(1);
        final File daily = new File(this.service.getReportDirectory(), LogReportService.dailyReportFilename(yesterday));
        Files.writeString(daily.toPath(), "Existing daily report");
        this.lines = List.of(line(yesterday.atTime(10,15)));
        assertTrue(this.service.generateHourlyReports().isEmpty()); assertEquals(2, this.calls.get());
        assertEquals("Existing daily report", Files.readString(daily.toPath()));
    }
    @Test public void dailyConsolidationPreservesHourlyFilesAndNeverChargesTwice() throws Exception {
        final LocalDateTime hour = LocalDate.now().minusDays(1).atTime(10,0);
        this.lines = List.of(line(hour.plusMinutes(5)));
        final File hourly = this.service.generateHourReport(hour, false, null);
        assertEquals(1, this.calls.get());
        assertEquals(1, this.service.generateDailyReports().size()); assertEquals(2, this.calls.get());
        assertTrue(hourly.isFile());
        assertTrue(this.service.generateDailyReports().isEmpty()); assertEquals(2, this.calls.get());
        final File daily = new File(this.service.getReportDirectory(), LogReportService.dailyReportFilename(hour.toLocalDate()));
        Files.delete(daily.toPath());
        assertTrue(this.service.generateDailyReports().isEmpty()); assertEquals(2, this.calls.get());
        assertTrue(hourly.isFile());
    }
    @Test public void aFailedDurableClaimPreventsPaidRequests() throws Exception {
        final File blocked = new File(this.tmp.getRoot(), "blocked");
        Files.writeString(blocked.toPath(), "not a directory");
        this.config.setConfig(LogReportService.CONFIG_REPORT_DIR, blocked.getAbsolutePath());
        final LocalDateTime hour = LocalDateTime.now().minusHours(1).withMinute(0).withSecond(0).withNano(0);
        this.lines = List.of(line(hour));
        try { this.service.generateHourReport(hour, false, null); fail(); } catch (java.io.IOException expected) { }
        assertEquals(0, this.calls.get());
    }

    @Test public void trustedContextDoesNotIncorporateUnrestrictedConfigStrings() {
        this.config.setConfig("scoutro.access.p2p", "false");
        this.config.setConfig("autocrawl.index.media", "false");
        this.config.setConfig("network.unit.description", "untrusted-operator-string");
        final String context = this.service.operatingContext();
        assertTrue(context.contains("intentionally disabled"));
        assertFalse(context.contains("untrusted-operator-string"));
        assertFalse(context.contains("api_key"));
    }
}
