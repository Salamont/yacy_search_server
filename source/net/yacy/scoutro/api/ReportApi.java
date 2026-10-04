/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.report.ReportService;

/**
 * Read-only crawl report routes below {@code /reports}. The administrator sees every
 * collection; an agent with {@code report.read} sees only its data scope: a foreign
 * collection is refused with 403, a job is visible only if all of its collections are
 * in scope (otherwise 404, like a missing job). No route fetches, crawls or writes.
 */
final class ReportApi {
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");

    /** The collections a caller may read; null for the administrator. */
    static final class Scope {
        final boolean all;
        final Set<String> collections;

        Scope(final boolean all, final Set<String> collections) { this.all = all; this.collections = collections; }

        static Scope of(final Agent agent) { return new Scope(agent.scope.allCollections, agent.scope.collections); }

        boolean allows(final String collection) { return this.all || this.collections.contains(collection); }

        /** A job with no recorded collection is visible only to agents with the complete index. */
        boolean allowsAll(final JsonArray jobCollections) {
            if (this.all) return true;
            if (jobCollections.isEmpty()) return false;
            for (final Object c : jobCollections) if (!this.collections.contains(String.valueOf(c))) return false;
            return true;
        }
    }

    private final ReportService reports;

    ReportApi(final ReportService reports) {
        this.reports = reports;
    }

    static ReportApi current() throws ApiException {
        final ReportService reports = CaptureRuntime.reports();
        if (reports == null)
            throw new ApiException(503, "report_unavailable", "Crawl reports are unavailable: capture is disabled or the index is not ready.");
        return new ReportApi(reports);
    }

    /** @param path segments after {@code reports} */
    JSONObject route(final List<String> path, final Map<String, String> query, final Scope scope) throws ApiException {
        final int n = path.size();
        try {
            if (n == 1 && "jobs".equals(path.get(0))) {
                parameters(query);
                final JsonObject jobs = this.reports.jobs();
                if (scope == null) return jobs;
                final JsonArray visible = new JsonArray();
                for (final Object job : jobs.getJSONArray("jobs"))
                    if (scope.allowsAll(((JsonObject) job).getJSONArray("collections"))) visible.put(job);
                return jobs.put("jobs", visible);
            }
            if (n == 2 && "jobs".equals(path.get(0))) {
                parameters(query, "from", "to");
                final String id = job(path.get(1));
                final LocalDate from = day(query, "from"), to = day(query, "to");
                final JsonObject job;
                try {
                    job = this.reports.job(id, from, to);
                } catch (final IllegalArgumentException range) {
                    throw ApiException.invalid("to", "Use from <= to and a range of at most three years.");
                }
                if (scope != null && !scope.allowsAll(job.getJSONArray("collections"))) throw notFound();
                return job;
            }
            if ((n == 2 || n == 3) && "collections".equals(path.get(0)) && (n == 2 || "hosts".equals(path.get(2)))) {
                final String collection = collection(path.get(1));
                visible(scope, collection);
                if (n == 2) {
                    parameters(query);
                    return this.reports.collection(collection);
                }
                parameters(query, "filter", "limit", "offset");
                final String filter = query.getOrDefault("filter", "all");
                if (!ReportService.HOST_FILTERS.contains(filter))
                    throw ApiException.invalid("filter", "Use one of " + String.join(", ", ReportService.HOST_FILTERS) + ".");
                return this.reports.hosts(collection, filter, number(query, "offset", 0, 0, ReportService.MAX_HOST_OFFSET),
                        number(query, "limit", 50, 1, ReportService.MAX_HOST_LIMIT));
            }
            if (n == 2 && "hosts".equals(path.get(0))) {
                parameters(query, "collection");
                final String host = HostInput.parse(path.get(1)).host;
                final String raw = query.get("collection");
                if (raw == null || raw.isEmpty()) throw ApiException.invalid("collection", "Parameter 'collection' is required.");
                final String collection = collection(raw);
                visible(scope, collection);
                final JsonObject report = this.reports.host(host, collection);
                // The host link graph knows no collections: only the complete index may see it.
                if (scope != null && !scope.all) report.put("referring_hosts", JsonObject.NULL).put("referring_hosts_scope", "complete_index_required");
                return report;
            }
        } catch (final IOException e) {
            LOG.warn("crawl report unavailable: " + e.getClass().getSimpleName());
            throw new ApiException(503, "report_unavailable", "The crawl report is temporarily unavailable; retry later.");
        } catch (final IllegalArgumentException e) {
            throw ApiException.invalid("request", "Invalid report request.");
        }
        throw new ApiException(404, "not_found", "Unknown crawl report path.");
    }

    private static ApiException notFound() {
        return new ApiException(404, "not_found", "No visible crawl report was found.");
    }

    private static void visible(final Scope scope, final String collection) throws ApiException {
        if (scope != null && !scope.allows(collection))
            throw new ApiException(403, "collection_not_in_scope", "The collection is outside the data scope of this agent.",
                    Json.obj("collection", collection));
    }

    private static void parameters(final Map<String, String> query, final String... allowed) throws ApiException {
        final Set<String> names = Set.of(allowed);
        for (final String key : query.keySet())
            if (!names.contains(key)) throw ApiException.invalid(key, "Unsupported report parameter.");
    }

    private static String collection(final String value) throws ApiException {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,64}"))
            throw ApiException.invalid("collection", "Collection must match [A-Za-z0-9_-]{1,64}.");
        return value;
    }

    private static String job(final String value) throws ApiException {
        try {
            if (value != null && java.util.UUID.fromString(value).toString().equals(value)) return value;
        } catch (final IllegalArgumentException invalid) {
            // fall through
        }
        throw ApiException.invalid("job", "Job must be a lower-case UUID.");
    }

    private static LocalDate day(final Map<String, String> query, final String key) throws ApiException {
        final String value = query.get(key);
        if (value == null || value.isEmpty()) return null;
        try {
            final LocalDate day = LocalDate.parse(value);
            if (!day.toString().equals(value)) throw new DateTimeParseException("format", value, 0);
            return day;
        } catch (final DateTimeParseException e) {
            throw ApiException.invalid(key, "Use a day as YYYY-MM-DD.");
        }
    }

    private static int number(final Map<String, String> query, final String key, final int dflt, final int min, final int max)
            throws ApiException {
        final String value = query.get(key);
        if (value == null || value.isEmpty()) return dflt;
        try {
            final int n = Integer.parseInt(value);
            if (n >= min && n <= max) return n;
        } catch (final NumberFormatException e) {
            // fall through
        }
        throw ApiException.invalid(key, "Parameter '" + key + "' must be between " + min + " and " + max + ".");
    }
}
