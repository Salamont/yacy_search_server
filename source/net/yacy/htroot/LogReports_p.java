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
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import net.yacy.data.TransactionManager;
import net.yacy.ai.LogReportControl;
import net.yacy.ai.LLM;
import java.util.List;

import net.yacy.ai.LogReportService;
import net.yacy.ai.LogReportService.ReportEntry;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class LogReports_p {

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
                final long period = Long.parseLong(post.get("periodMinutes", "60"));
                if (period < 1 || period > 1440) throw new IllegalArgumentException();
                final String time = post.get("dailyTime", "00:00");
                if (!time.matches("\\d{2}:\\d{2}")) throw new IllegalArgumentException();
                LocalTime.parse(time);
                synchronized (LogReportControl.LOCK) {
                    sb.setConfig(LogReportService.CONFIG_ENABLED, false);
                    sb.setConfig(LogReportService.CONFIG_PERIOD_MINUTES, period);
                    sb.setConfig(LogReportService.CONFIG_DAILY_COMPRESSION_ENABLED, post.containsKey("dailyEnabled"));
                    sb.setConfig(LogReportControl.DAILY_TIME, time);
                    service.resetSchedule(period);
                    sb.setConfig(LogReportService.CONFIG_ENABLED, post.containsKey("automaticEnabled"));
                }
                prop.putHTML("settingsMessage", "Einstellungen gespeichert. Alte Zeiträume werden nicht nachgeholt.");
            } catch (final Exception e) { prop.putHTML("settingsMessage", "Einstellungen nicht aktiviert. Bitte gültiges Intervall und Uhrzeit prüfen; bei Speicherfehlern bleiben automatische Berichte aus."); }
        }
        prop.put("automaticEnabled", sb.getConfigBool(LogReportService.CONFIG_ENABLED, true));
        prop.put("dailyEnabled", sb.getConfigBool(LogReportService.CONFIG_DAILY_COMPRESSION_ENABLED, true));
        prop.putNum("periodMinutes", sb.getConfigLong(LogReportService.CONFIG_PERIOD_MINUTES, 60));
        prop.putHTML("dailyTime", sb.getConfig(LogReportControl.DAILY_TIME, "00:00"));
        prop.putHTML("reportZone", LogReportService.reportZone().toString());
        final LLM.LLMModel reportModel = LLM.llmFromUsageQuiet(LLM.LLMUsage.logreport);
        prop.putHTML("reportModel", reportModel == null ? "Kein Modell zugewiesen" : reportModel.model);
        final boolean active = sb.getConfigBool(LogReportService.CONFIG_ENABLED, true) && reportModel != null && LogReportService.isSchedulerRunning();
        prop.putHTML("hourlyState", active ? "Aktiv" : "Inaktiv");
        prop.putHTML("dailyState", active && sb.getConfigBool(LogReportService.CONFIG_DAILY_COMPRESSION_ENABLED, true) ? "Aktiv" : "Inaktiv");
        for (final String kind : new String[] {"hourly", "daily"}) {
            for (final String event : new String[] {"success", "failure"}) {
                String value = "Noch keine Erstellung erfasst";
                try { final String recorded = service.control().value("last." + kind + "." + event);
                    if (!recorded.isEmpty()) value = java.time.Instant.parse(recorded).atZone(LogReportService.reportZone()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss XXX"));
                } catch (final Exception e) { value = "Status nicht lesbar"; }
                prop.putHTML(kind + "_" + event, value);
            }
            String next = "Nicht geplant";
            try { if (active && (!"daily".equals(kind) || sb.getConfigBool(LogReportService.CONFIG_DAILY_COMPRESSION_ENABLED, true))) {
                final String value = service.control().value("next." + kind);
                next = value.isEmpty() ? "Scheduler nicht bereit" : java.time.Instant.parse(value).atZone(LogReportService.reportZone()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss XXX"));
            } } catch (final Exception e) { next = "Status nicht lesbar"; }
            prop.putHTML(kind + "_next", next);
            try { prop.putHTML(kind + "_message", service.control().value("last." + kind + ".message")); }
            catch (final Exception e) { prop.putHTML(kind + "_message", "Status nicht lesbar"); }
        }
        final LocalDateTime currentHour = LocalDateTime.now().truncatedTo(ChronoUnit.HOURS);
        prop.putHTML("reportHour", currentHour.toString());
        prop.putHTML("reportPeriod", currentHour + " bis " + currentHour.plusHours(1));
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
                final LocalDateTime hour = LocalDateTime.parse(post.get("reportHour", currentHour.toString()));
                if (hour.isAfter(LocalDateTime.now())) throw new IllegalArgumentException();
                service.startManualReportJob(hour.truncatedTo(ChronoUnit.HOURS), post.containsKey("recreateReport"));
            } catch (final RuntimeException e) { prop.putHTML("settingsMessage", "Ungültiger Berichtszeitraum."); }
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
        if (deleteReport.matches("report-\\d{4}-\\d{2}-\\d{2}(-\\d{2})?\\.md")) {
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
            prop.putHTML(p + "type", report.daily ? "daily" : "hourly");
            prop.put(p + "selected", report == selected ? "1" : "0");
        }
        prop.put("hasReports_reports", reports.size());

        prop.put("hasReports_selectedReport", selected == null ? "0" : "1");
        if (selected != null) {
            prop.putHTML("hasReports_selectedReport_filename", selected.filename);
            prop.putHTML("hasReports_selectedReport_title", selected.title);
            prop.putHTML("hasReports_selectedReport_published", selected.published.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            prop.putHTML("hasReports_selectedReport_type", selected.daily ? "daily" : "hourly");
            prop.putNum("hasReports_selectedReport_chars", selected.content.length());
            prop.putHTML("hasReports_selectedReport_content", selected.content);
        }
        prop.putNum("reportCount", reports.size());
        prop.put("hasReports", reports.isEmpty() ? "0" : "1");
        prop.put("noReports", reports.isEmpty() ? "1" : "0");

        return prop;
    }
}
