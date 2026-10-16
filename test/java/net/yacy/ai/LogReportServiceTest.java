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
    private volatile boolean failModel;
    private volatile java.util.concurrent.CountDownLatch entered, release;
    private volatile String lastRequest;

    @Before public void setup() throws Exception {
        this.endpoint = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        this.endpoint.createContext("/v1/chat/completions", exchange -> {
            this.calls.incrementAndGet(); this.lastRequest=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            if(entered!=null) entered.countDown();
            if(release!=null) try { release.await(10,java.util.concurrent.TimeUnit.SECONDS); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
            if(failModel) { byte[] error="synthetic model failure".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500,error.length); exchange.getResponseBody().write(error); exchange.close(); return; }

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
    private void event(Instant at, String text) throws Exception {
        java.util.logging.LogRecord record=new java.util.logging.LogRecord(java.util.logging.Level.WARNING,text);
        record.setLoggerName("FIXTURE"); record.setInstant(at); service.statistics().publish(record);
    }
    @Test public void weeklyScheduleMakesOneCallAndDoesNotFanOutIntoHourlyReports() throws Exception {
        LogReportSchedule plan=new LogReportSchedule("weekly",1,"08:00",1,0,"Europe/Berlin");
        Instant end=plan.previous(Instant.now().minusSeconds(8*86400));
        Instant start=plan.previous(end.minusNanos(1));
        config.setConfig(LogReportSchedule.CONFIG,plan.encode());
        service.control().initialize(plan,start.plusSeconds(1),true);
        for(int hour=1;hour<168;hour++) {
            Instant at=start.plusSeconds(hour*3600L); event(at,"Synthetic weekly warning"); service.schedulerTick(at);
            assertEquals("no hourly or daily report is scheduled",0,calls.get());
        }
        service.schedulerTick(end); assertEquals(1,calls.get());
        for(int h=1;h<=24;h++) service.schedulerTick(end.plusSeconds(h*3600));
        assertEquals("one weekly model call, zero hourly fan-out",1,calls.get());
        File file=new File(service.getReportDirectory(),LogReportService.periodReportFilename(start,end));
        String report=Files.readString(file.toPath()); assertTrue(report.contains("167"));
        assertTrue(report.contains("Datenlücke")); assertTrue(report.contains(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm XXX").withZone(plan.zone).format(start)));
        assertEquals(1,service.discoverReports(100).size());
        Files.delete(file.toPath());
        service.control().initialize(plan,end.plusSeconds(86400),false);
        service.schedulerTick(end.plusSeconds(86401)); assertEquals(1,calls.get());
        try { service.generatePeriodReport(start,end,false,"manual",null); fail(); } catch(java.io.IOException expected) { }
        assertEquals(1,calls.get());
        service.generatePeriodReport(start,end,true,"manual",null); assertEquals(2,calls.get());
    }
    @Test public void allSchedulesProduceOneCallPerWholePeriodAndOffProducesNone() throws Exception {
        for(String mode:new String[]{"hourly","daily","weekly","monthly"}) {
            for(int hours:mode.equals("hourly")?new int[]{1,2,4,6,12}:new int[]{1}) {
                LogReportSchedule plan=new LogReportSchedule(mode,hours,"08:15",3,0,"Europe/Berlin");
                Instant end=plan.previous(Instant.now().minusSeconds(86400));
                Instant start=plan.previous(end.minusNanos(1));
                config.setConfig(LogReportSchedule.CONFIG,plan.encode()); config.setConfig(LogReportService.CONFIG_ENABLED,true);
                service.control().initialize(plan,start.plusSeconds(1),true);
                event(start.plusSeconds(60),mode+"-"+hours);
                int before=calls.get(); service.schedulerTick(end); assertEquals(mode,before+1,calls.get());
                service.schedulerTick(end.plusSeconds(1)); assertEquals(before+1,calls.get());
            }
        }
        service.saveSchedule(new LogReportSchedule("off",1,"00:00",1,0,"Europe/Berlin"));
        int before=calls.get(); service.schedulerTick(Instant.now()); assertEquals(before,calls.get());
        Instant end=Instant.now().minusSeconds(120).truncatedTo(java.time.temporal.ChronoUnit.MINUTES), start=end.minusSeconds(3600);
        event(start.plusSeconds(10),"manual independent");
        service.generatePeriodReport(start,end,false,"manual",null); assertEquals(before+1,calls.get());
        serverSwitch restarted=new serverSwitch(tmp.getRoot(),new File("."),"defaults/yacy.init","settings.conf");
        assertEquals("off",LogReportSchedule.load(restarted).mode);
    }
    @Test public void compactStatisticsSurviveRestartAreBoundedAndDescribeGapsWithoutInventingHistory() throws Exception {
        Instant start=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),end=start.plusSeconds(3600);
        LogReportStatistics statistics=service.statistics();
        statistics.checkpoint(start,true); event(start.plusSeconds(1),"api_key=secret-sentinel observed warning");
        statistics.checkpoint(start.plusSeconds(30),false); statistics.checkpoint(start.plusSeconds(60),true);
        LogReportStatistics loaded=new LogReportStatistics(service.getReportDirectory().toPath());
        LogReportStatistics.Snapshot data=loaded.snapshot(start,end);
        assertEquals(1,data.entries); assertEquals(1,data.warnings); assertEquals(60000,data.coveredMillis);
        assertFalse(data.samples.toString().contains("secret-sentinel"));
        assertTrue(data.metadata(start,end,ZoneId.of("Europe/Berlin")).contains("3540"));
        loaded.checkpoint(end,true); loaded.checkpoint(end.plusSeconds(30),true);
        assertEquals("restart gap is not treated as monitored",60000,loaded.snapshot(start,end).coveredMillis);
        loaded.checkpoint(start.plusSeconds(67*86400),true);
        assertEquals(0,loaded.snapshot(start,end).entries);
    }
    @Test public void noAvailableHistoryProducesExplicitGapReportWithoutModelCall() throws Exception {
        Instant end=Instant.now().minusSeconds(86400).truncatedTo(java.time.temporal.ChronoUnit.MINUTES),start=end.minusSeconds(7*86400);
        File file=service.generatePeriodReport(start,end,false,"manual",null);
        assertEquals(0,calls.get()); assertTrue(Files.readString(file.toPath()).contains("Kein LLM-Aufruf"));
    }
    @Test public void scheduleMigrationPreservesLegacySettingsAndReports() throws Exception {
        config.setConfig(LogReportService.CONFIG_ENABLED,true); config.setConfig(LogReportService.CONFIG_PERIOD_MINUTES,240);
        config.setConfig(LogReportControl.DAILY_TIME,"06:30");
        assertEquals(4,service.schedule().hours); assertEquals("Europe/Berlin",service.schedule().zone.getId());
        File directory=service.getReportDirectory(); directory.mkdirs();
        File old=new File(directory,"report-2026-10-01.md"); Files.writeString(old.toPath(),"legacy");
        service.saveSchedule(new LogReportSchedule("monthly",1,"06:30",1,31,"Europe/Berlin"));
        serverSwitch restarted=new serverSwitch(tmp.getRoot(),new File("."),"defaults/yacy.init","settings.conf");
        assertEquals("monthly",LogReportSchedule.load(restarted).mode); assertEquals(31,LogReportSchedule.load(restarted).monthday);
        assertEquals(240,restarted.getConfigLong(LogReportService.CONFIG_PERIOD_MINUTES,0));
        assertEquals("legacy",Files.readString(old.toPath())); assertEquals(1,service.discoverReports(100).size());
    }
    @Test public void delayedSchedulerReportsOnlyLatestWeekWithoutHistoricalCatchUp() throws Exception {
        LogReportSchedule plan=new LogReportSchedule("weekly",1,"08:00",1,0,"Europe/Berlin");
        Instant end=plan.previous(Instant.now().minusSeconds(86400)),start=plan.previous(end.minusNanos(1));
        config.setConfig(LogReportSchedule.CONFIG,plan.encode());
        service.control().initialize(plan,start.minusSeconds(21*86400),true);
        event(start.plusSeconds(60),"only current completed week");
        service.schedulerTick(end.plusSeconds(60)); assertEquals(1,calls.get());
        assertEquals(end.toString(),service.controlValue("last.completed.end"));
        assertEquals(1,service.discoverReports(100).size());
        service.schedulerTick(end.plusSeconds(120)); assertEquals(1,calls.get());
    }
    @Test public void disablingDoesNotWaitForInFlightModelAndPreventsFurtherAutomaticCalls() throws Exception {
        LogReportSchedule plan=new LogReportSchedule("hourly",1,"00:00",1,0,"Europe/Berlin");
        Instant end=plan.previous(Instant.now().minusSeconds(3600)),start=plan.previous(end.minusNanos(1));
        config.setConfig(LogReportSchedule.CONFIG,plan.encode()); service.control().initialize(plan,start.plusSeconds(1),true);
        event(start.plusSeconds(60),"in-flight synthetic warning");
        entered=new java.util.concurrent.CountDownLatch(1); release=new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Future<?> running=workers.submit(()->{try {service.schedulerTick(end);} catch(Exception e){throw new RuntimeException(e);}});
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.Future<?> disabled=workers.submit(()->{try {
                service.saveSchedule(new LogReportSchedule("off",1,"00:00",1,0,"Europe/Berlin"));
            } catch(Exception e){throw new RuntimeException(e);}});
            disabled.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("off",service.schedule().mode); assertEquals(1,calls.get());
            release.countDown(); running.get(5,java.util.concurrent.TimeUnit.SECONDS);
            service.schedulerTick(end.plusSeconds(3600)); assertEquals(1,calls.get());
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test public void failedModelAttemptIsRecordedAndNeverAutomaticallyRetried() throws Exception {
        LogReportSchedule plan=new LogReportSchedule("daily",1,"08:00",1,0,"Europe/Berlin");
        Instant end=plan.previous(Instant.now().minusSeconds(86400)),start=plan.previous(end.minusNanos(1));
        config.setConfig(LogReportSchedule.CONFIG,plan.encode()); service.control().initialize(plan,start.plusSeconds(1),true);
        event(start.plusSeconds(60),"synthetic error"); failModel=true;
        try { service.schedulerTick(end); fail(); } catch(java.io.IOException expected) { }
        assertEquals(1,calls.get()); assertFalse(service.controlValue("last.scheduled.failure").isEmpty());
        failModel=false; service.schedulerTick(end.plusSeconds(60)); assertEquals(1,calls.get());
        try { service.generatePeriodReport(start,end,false,"manual",null); fail(); } catch(java.io.IOException expected) { }
        assertEquals(1,calls.get()); service.generatePeriodReport(start,end,true,"manual",null); assertEquals(2,calls.get());
        assertTrue(lastRequest.contains("Trusted Scoutro configuration")); assertTrue(lastRequest.contains("Datenlücke"));
    }
    @Test public void statisticsSamplesHaveHardLimitsAndPrioritizeErrorsWithinContext() throws Exception {
        Instant at=Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.HOURS);
        for(int i=0;i<1000;i++) {
            java.util.logging.LogRecord record=new java.util.logging.LogRecord(i%3==0?java.util.logging.Level.SEVERE:i%3==1?java.util.logging.Level.WARNING:java.util.logging.Level.INFO,"x".repeat(1000));
            record.setLoggerName("FIXTURE"); record.setInstant(at.plusSeconds(i)); service.statistics().publish(record);
        }
        LogReportStatistics.Snapshot snapshot=service.statistics().snapshot(at,at.plusSeconds(3600));
        assertEquals(1000,snapshot.entries); assertEquals(7,snapshot.samples.size());
        assertTrue(snapshot.samples.get(0).startsWith("E "));
        assertTrue(snapshot.samples.stream().allMatch(v->v.length()<360));
        service.generatePeriodReport(at,at.plusSeconds(3600),false,"manual",null);
        assertEquals(1,calls.get()); assertTrue(lastRequest.length()<10000);
        assertFalse(lastRequest.contains("x".repeat(500)));
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
