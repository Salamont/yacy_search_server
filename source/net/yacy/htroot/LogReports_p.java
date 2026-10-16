/**
 *  LogReports_p
 *  Copyright 2026 by contributors to the YaCy project
 *  First released 27.06.2026 at https://yacy.net
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 */

package net.yacy.htroot;

import java.io.File;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import net.yacy.data.TransactionManager;
import net.yacy.ai.LogReportSchedule;
import java.time.Instant;
import java.time.ZoneId;
import net.yacy.ai.LLM;
import java.util.List;

import net.yacy.ai.LogReportService;
import net.yacy.ai.LogReportService.ReportEntry;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class LogReports_p {
    private static String displayInstant(String value, ZoneId zone) {
        if(value.isEmpty()) return "Noch nicht erfasst";
        try { return Instant.parse(value).atZone(zone).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm XXX")); }
        catch(RuntimeException e) { return "Status nicht lesbar"; }
    }


    public static serverObjects respond(@SuppressWarnings("unused") final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final net.yacy.server.servletProperties prop = new net.yacy.server.servletProperties();
        final net.yacy.cora.protocol.ResponseHeader responseHeaders = new net.yacy.cora.protocol.ResponseHeader(200);
        responseHeaders.put("Cache-Control", "no-store");
        prop.setOutgoingHeader(responseHeaders);
        if (header == null || !sb.verifyAuthentication(header)) { prop.authenticationRequired(); return prop; }
        prop.put(TransactionManager.TRANSACTION_TOKEN_PARAM, TransactionManager.getTransactionToken(header));
        final boolean mutation = post != null && (post.containsKey("runReportNow") || post.containsKey("saveReportSettings") || post.containsKey("deleteReport"));
        if (mutation) AiSettingsTransaction.check(header, post);
        final LogReportService service = new LogReportService(sb);
        prop.putHTML("settingsMessage", "");
        if (post != null && post.containsKey("saveReportSettings")) {
            try {
                final LogReportSchedule plan=new LogReportSchedule(post.get("scheduleMode","off"),
                        Integer.parseInt(post.get("scheduleHours","1")),post.get("scheduleTime","00:00"),
                        Integer.parseInt(post.get("scheduleWeekday","1")),Integer.parseInt(post.get("scheduleMonthday","0")),
                        post.get("scheduleZone","Europe/Berlin"));
                service.saveSchedule(plan);
                prop.putHTML("settingsMessage", "Einstellungen gespeichert. Alte Zeiträume werden nicht nachgeholt.");
            } catch (final Exception e) { prop.putHTML("settingsMessage", "Ungültiger Zeitplan oder Speicherfehler. Die bisherigen Einstellungen bleiben erhalten; Status prüfen."); }
        }
        final LogReportSchedule plan=service.schedule();
        prop.putHTML("scheduleMode",plan.mode); prop.putNum("scheduleHours",plan.hours);
        prop.putHTML("scheduleTime",plan.time.toString()); prop.putNum("scheduleWeekday",plan.weekday);
        prop.putNum("scheduleMonthday",plan.monthday); prop.putHTML("reportZone",plan.zone.getId());
        prop.put("migrationNotice",sb.getConfig(LogReportSchedule.CONFIG,"").isEmpty() ||
                !service.controlValue("migration.legacy").isEmpty());
        final LLM.LLMModel reportModel=LLM.llmFromUsageQuiet(LLM.LLMUsage.logreport);
        prop.putHTML("reportModel",reportModel==null?"Kein Modell zugewiesen":reportModel.model);
        final boolean enabled=!plan.mode.equals("off") && sb.getConfigBool(LogReportService.CONFIG_ENABLED,true);
        final boolean active=enabled && reportModel!=null && LogReportService.isSchedulerRunning();
        prop.putHTML("scheduleState",active?"Aktiv":!enabled?"Ausgeschaltet":"Nicht verfügbar (Modell oder Scheduler fehlt)");
        for(String event:new String[]{"success","failure"}) {
            prop.putHTML("scheduled_"+event,displayInstant(service.controlValue("last.scheduled."+event),plan.zone));
        }
        prop.putHTML("scheduled_next",active?displayInstant(service.controlValue("next.scheduled"),plan.zone):"Nicht geplant");
        prop.putHTML("scheduled_message",service.controlValue("last.scheduled.message"));
        final String completedStart=service.controlValue("last.completed.start"), completedEnd=service.controlValue("last.completed.end");
        prop.putHTML("completedPeriod",completedStart.isEmpty()?"Noch kein Lagebericht abgeschlossen":displayInstant(completedStart,plan.zone)+" bis "+displayInstant(completedEnd,plan.zone));
        final Instant manualEnd=Instant.now().truncatedTo(ChronoUnit.MINUTES);
        final Instant manualStart=plan.mode.equals("off")?manualEnd.minus(1,ChronoUnit.HOURS):plan.previous(manualEnd.minusNanos(1));
        prop.putHTML("reportStart",manualStart.atZone(plan.zone).toLocalDateTime().toString());
        prop.putHTML("reportEnd",manualEnd.atZone(plan.zone).toLocalDateTime().toString());
        prop.putHTML("reportPeriod",manualStart.atZone(plan.zone)+" bis "+manualEnd.atZone(plan.zone));
        final File reportDirectory = service.getReportDirectory();

        // fixed display limit; the navigation column shows at most this many reports
        final int maxEntries = 100;

        sb.setConfig("ui.LogReports_p.visited", "true");

        // "run report now" starts the computation asynchronously (the LLM call can take
        // minutes and would run into the request timeout); the page polls itself while
        // LogReportService reports the job as running and shows the result afterwards
        prop.put("runReportResult", "0");
        prop.putHTML("runReportResult_runReportFile", "");
        prop.putNum("runReportResult_runReportDurationSeconds", 0);
        if (post != null && post.containsKey("runReportNow")) {
            try {
                if(post.containsKey("reportHour") && !post.containsKey("reportStart")) {
                    // Compatibility for existing explicitly triggered one-hour clients.
                    final LocalDateTime hour=LocalDateTime.parse(post.get("reportHour"));
                    if(hour.isAfter(LocalDateTime.now())) throw new IllegalArgumentException();
                    service.startManualReportJob(hour.truncatedTo(ChronoUnit.HOURS),post.containsKey("recreateReport"));
                } else {
                    final Instant start=LocalDateTime.parse(post.get("reportStart",manualStart.atZone(plan.zone).toLocalDateTime().toString())).atZone(plan.zone).toInstant();
                    final Instant end=LocalDateTime.parse(post.get("reportEnd",manualEnd.atZone(plan.zone).toLocalDateTime().toString())).atZone(plan.zone).toInstant();
                    service.startManualReportJob(start,end,post.containsKey("recreateReport"));
                }
            } catch (final RuntimeException e) {
                throw new net.yacy.http.servlets.TemplateProcessingException(
                        "Ungültiger Berichtszeitraum. Vergangenen Zeitraum von höchstens 65 Tagen mit Minutengenauigkeit wählen.",400);
            }
        }
        final boolean reportRunning = LogReportService.isManualReportJobRunning();
        prop.put("reportRunning", reportRunning ? "1" : "0");
        prop.putNum("reportRunning_elapsedSeconds", LogReportService.manualReportJobElapsedSeconds());
        if (!reportRunning) {
            final LogReportService.ManualJobResult result = LogReportService.manualReportJobResult();
            if (result != null) {
                prop.put("runReportResult", result.outcome);
                prop.putHTML("runReportResult_runReportFile", result.message);
                prop.putNum("runReportResult_runReportDurationSeconds", result.durationSeconds);
            }
        }

        // delete a report when requested from the navigation column (immediate, no
        // confirmation); the filename is strictly validated against the report
        // filename patterns, which excludes any path traversal
        final String deleteReport = post == null ? "" : post.get("deleteReport", "");
        if (deleteReport.matches("report-(?:\\d{4}-\\d{2}-\\d{2}(-\\d{2})?|period-\\d{1,12}-\\d{1,12})\\.md")) {
            final File deleteFile = new File(reportDirectory, deleteReport);
            if (deleteFile.isFile() && !deleteFile.delete()) {
                net.yacy.cora.util.ConcurrentLog.warn("LogReports", "could not delete log report " + deleteFile.getAbsolutePath());
            }
        }

        final List<ReportEntry> reports = service.discoverReports(maxEntries);

        prop.put("modelConfigured", LogReportService.hasConfiguredLogReportModel() ? "1" : "0");
        prop.putHTML("reportdir", reportDirectory.getAbsolutePath());
        prop.put("reportdirExists", reportDirectory.isDirectory() ? "1" : "0");
        prop.put("reportdirMissing", reportDirectory.isDirectory() ? "0" : "1");
        prop.put("jsonFeed", "api/logreports.json?count=" + maxEntries);
        prop.put("rssFeed", "api/logreports.rss?count=" + maxEntries);

        // the report to show in the main view is selected by filename from the navigation
        // column; matching against the discovered list only prevents path traversal.
        // default is the newest report (first entry, list is sorted newest first)
        final String requestedReport = post == null ? "" : post.get("report", "");
        ReportEntry selected = null;
        for (final ReportEntry report : reports) {
            if (report.filename.equals(requestedReport)) {
                selected = report;
                break;
            }
        }
        if (selected == null && !reports.isEmpty()) selected = reports.get(0);

        // navigation list and selected report live inside the #(hasReports)# alternative,
        // therefore all template keys carry the "hasReports_" prefix
        for (int i = 0; i < reports.size(); i++) {
            final ReportEntry report = reports.get(i);
            final String p = "hasReports_reports_" + i + "_";
            prop.putHTML(p + "filename", report.filename);
            prop.put(p + TransactionManager.TRANSACTION_TOKEN_PARAM, prop.get(TransactionManager.TRANSACTION_TOKEN_PARAM));
            prop.putHTML(p + "title", report.title);
            prop.putHTML(p + "date", report.published.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
            prop.putHTML(p + "type", report.filename.startsWith("report-period-") ? "Lagebericht" : report.daily ? "daily" : "hourly");
            prop.put(p + "selected", report == selected ? "1" : "0");
        }
        prop.put("hasReports_reports", reports.size());

        prop.put("hasReports_selectedReport", selected == null ? "0" : "1");
        if (selected != null) {
            prop.putHTML("hasReports_selectedReport_filename", selected.filename);
            prop.putHTML("hasReports_selectedReport_title", selected.title);
            prop.putHTML("hasReports_selectedReport_published", selected.published.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            prop.putHTML("hasReports_selectedReport_type", selected.filename.startsWith("report-period-") ? "Lagebericht" : selected.daily ? "daily" : "hourly");
            prop.putNum("hasReports_selectedReport_chars", selected.content.length());
            prop.putHTML("hasReports_selectedReport_content", selected.content);
        }
        prop.putNum("reportCount", reports.size());
        prop.put("hasReports", reports.isEmpty() ? "0" : "1");
        prop.put("noReports", reports.isEmpty() ? "1" : "0");

        return prop;
    }
}
