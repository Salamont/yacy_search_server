/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import org.json.JSONObject;

/**
 * Streaming writers of the domain export: JSON ({@code scoutro.domains.v1}) and CSV (RFC 4180,
 * UTF-8, comma, header row). Items are written as they arrive; nothing is buffered beyond one
 * page. The JSON envelope ends with the number of written items and {@code complete}; a CSV
 * export that stops early ends with the line {@code #incomplete}.
 */
final class DomainExport {

    private DomainExport() { }

    static String contentType(final String format) {
        return "csv".equals(format) ? "text/csv; charset=UTF-8" : "application/json; charset=UTF-8";
    }

    static String filename(final DomainCandidates.Filter filter, final String format, final long now) {
        final String scope = filter.collection() == null ? "all" : filter.collection();
        final String day = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(now));
        return "scoutro-domains-" + scope + "-" + day + ("csv".equals(format) ? ".csv" : ".json");
    }

    static String format(final String value) throws ApiException {
        final String f = value == null || value.isEmpty() ? "json" : value;
        if (!"json".equals(f) && !"csv".equals(f)) throw ApiException.invalid("format", "Export format json or csv.");
        return f;
    }

    static DomainCandidates.Sink sink(final String format, final Writer out) {
        return "csv".equals(format) ? new CsvSink(out) : new JsonSink(out);
    }

    /** {"schema", "generated_at", "filter", "collection", "items": [...], "count", "complete"} */
    static final class JsonSink implements DomainCandidates.Sink {
        private final Writer out;
        private boolean first = true;
        JsonSink(final Writer out) { this.out = out; }

        @Override public void begin(final DomainCandidates.Filter filter, final long generatedAt) throws IOException {
            final JSONObject filterJson = new JSONObject(true);
            Json.put(filterJson, "q", filter.host);
            Json.put(filterJson, "collection", filter.collection());
            this.out.write("{\"schema\":" + JSONObject.quote(DomainCandidate.SCHEMA)
                    + ",\"generated_at\":" + JSONObject.quote(DomainCandidate.iso(generatedAt))
                    + ",\"filter\":" + filterJson
                    + ",\"collection\":" + (filter.collection() == null ? "null" : JSONObject.quote(filter.collection()))
                    + ",\"items\":[");
        }

        @Override public void item(final DomainCandidate candidate) throws IOException {
            if (!this.first) this.out.write(',');
            this.first = false;
            this.out.write('\n');
            this.out.write(candidate.toJson().toString());
        }

        @Override public void flush() throws IOException { this.out.flush(); }

        @Override public void end(final long count, final boolean complete) throws IOException {
            this.out.write("\n],\"count\":" + count + ",\"complete\":" + complete + "}\n");
            this.out.flush();
        }
    }

    static final class CsvSink implements DomainCandidates.Sink {
        private final Writer out;
        CsvSink(final Writer out) { this.out = out; }

        @Override public void begin(final DomainCandidates.Filter filter, final long generatedAt) throws IOException {
            this.out.write(DomainCandidate.csvHeader());
            this.out.write("\r\n");
        }

        @Override public void item(final DomainCandidate candidate) throws IOException {
            this.out.write(candidate.csvRow());
            this.out.write("\r\n");
        }

        @Override public void flush() throws IOException { this.out.flush(); }

        @Override public void end(final long count, final boolean complete) throws IOException {
            if (!complete) this.out.write("#incomplete\r\n");
            this.out.flush();
        }
    }
}
