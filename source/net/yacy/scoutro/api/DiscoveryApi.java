/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.discovery.DiscoveryService;

/** Admin role and JSON/same-origin body are enforced by the parent servlet. */
final class DiscoveryApi {
    private DiscoveryApi() { }
    static JsonObject route(final String method, final String[] parts, final HttpServletRequest request,
            final HttpServletResponse response, final JsonObject body) throws ApiException {
        return route(method, parts, request, response, body, null);
    }

    /**
     * @param allowed for a Scoutro account without the administrator role (docs/SCOUTRO_USERS_ACCESS.md):
     *        the collections it may use; jobs count by the collection of their profile. Such a caller lists,
     *        creates, edits, deletes and runs only these jobs and never changes the global switches or exports
     *        the job store. Null for the administrator (unchanged).
     */
    static JsonObject route(final String method, final String[] parts, final HttpServletRequest request,
            final HttpServletResponse response, final JsonObject body, final Predicate<String> allowed) throws ApiException {
        return route(DiscoveryService.get(), method, parts, revision(request.getHeader("If-Match")), response, body, allowed,
                net.yacy.scoutro.access.Caller.of(request).actor());
    }

    static JsonObject route(final DiscoveryService service, final String method, final String[] parts, final Long revision,
            final HttpServletResponse response, final JsonObject body, final Predicate<String> allowed) throws ApiException {
        return route(service, method, parts, revision, response, body, allowed, "admin");
    }

    /** @param actor the acting identity recorded with a recovery decision (as in the people audit log) */
    static JsonObject route(final DiscoveryService service, final String method, final String[] parts, final Long revision,
            final HttpServletResponse response, final JsonObject body, final Predicate<String> allowed, final String actor) throws ApiException {
        final String path = parts.length > 3 ? parts[3] : "";
        if (allowed != null) {
            return restricted(service, method, parts, path, revision, response, body, allowed);
        }
        if (parts.length == 4 && "GET".equals(method)) {
            switch (path) {
                case "catalog": return service.catalogJson();
                case "status": return service.status();
                case "jobs": return service.jobs(null);
                case "export": return service.export();
                default: break;
            }
        }
        if (parts.length == 4 && "POST".equals(method)) {
            if ("jobs".equals(path)) {
                final JsonObject result = service.create(body, revision);
                response.setStatus(201);
                response.setHeader("Location", "/scoutro/api/v1/discovery/jobs/" + result.getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id"));
                return result;
            }
            if (java.util.Set.of("enable", "disable", "pause", "resume").contains(path)) {
                net.yacy.scoutro.discovery.JobSchema.keys(body);
                return service.global(path, revision);
            }
        }
        if (parts.length == 5 && "jobs".equals(path)) {
            if ("GET".equals(method)) return service.jobs(parts[4]);
            if ("PATCH".equals(method)) return service.edit(parts[4], body, revision);
            if ("DELETE".equals(method)) {
                net.yacy.scoutro.discovery.JobSchema.keys(body);
                return service.delete(parts[4], revision);
            }
        }
        if (parts.length == 6 && "jobs".equals(path) && "run".equals(parts[5]) && "POST".equals(method)) {
            net.yacy.scoutro.discovery.JobSchema.keys(body, "request_id");
            final String id = body.optString("request_id");
            if (id.isBlank()) throw ApiException.invalid("request_id", "Supply a unique request id.");
            response.setStatus(202);
            return service.requestRun(parts[4], id);
        }
        // recovery of an unconfirmed crawl start: defer it, outcome stays unknown (administrator only)
        if (parts.length == 6 && "attempts".equals(path) && "defer".equals(parts[5]) && "POST".equals(method)) {
            net.yacy.scoutro.discovery.JobSchema.keys(body, "run_id");
            return service.deferAttempt(parts[4], body.optString("run_id", null), revision, actor);
        }
        throw new ApiException(404, "not_found", "Unknown discovery endpoint or method.");
    }
    /** The routes of a caller without the administrator role, limited to the jobs of its collections. */
    private static JsonObject restricted(final DiscoveryService service, final String method, final String[] parts,
            final String path, final Long revision, final HttpServletResponse response, final JsonObject body,
            final Predicate<String> allowed) throws ApiException {
        final Map<String, String> profiles = profileCollections(service);
        if (parts.length == 4 && "GET".equals(method)) {
            switch (path) {
                case "catalog": return catalog(service.catalogJson(), allowed);
                case "status": return status(service.status(), visibleJobs(service.jobs(null), profiles, allowed), profiles, allowed);
                case "jobs": return visibleJobs(service.jobs(null), profiles, allowed);
                case "export": throw adminOnly();
                default: break;
            }
        }
        if (parts.length == 4 && "POST".equals(method)) {
            if ("jobs".equals(path)) {
                requireProfile(body.optString("profile", ""), profiles, allowed);
                final JsonObject result = service.create(body, revision);
                response.setStatus(201);
                response.setHeader("Location", "/scoutro/api/v1/discovery/jobs/" + result.getJSONArray("jobs").getJSONObject(0).getJSONObject("definition").getString("id"));
                return result;
            }
            if (java.util.Set.of("enable", "disable", "pause", "resume").contains(path)) throw adminOnly();
        }
        if ("attempts".equals(path)) throw adminOnly();
        if (parts.length == 5 && "jobs".equals(path)) {
            requireJob(service, parts[4], profiles, allowed);
            if ("GET".equals(method)) return service.jobs(parts[4]);
            if ("PATCH".equals(method)) {
                if (body.has("profile")) requireProfile(String.valueOf(body.opt("profile")), profiles, allowed);
                return service.edit(parts[4], body, revision);
            }
            if ("DELETE".equals(method)) {
                net.yacy.scoutro.discovery.JobSchema.keys(body);
                return visibleJobs(service.delete(parts[4], revision), profiles, allowed);
            }
        }
        if (parts.length == 6 && "jobs".equals(path) && "run".equals(parts[5]) && "POST".equals(method)) {
            requireJob(service, parts[4], profiles, allowed);
            net.yacy.scoutro.discovery.JobSchema.keys(body, "request_id");
            final String id = body.optString("request_id");
            if (id.isBlank()) throw ApiException.invalid("request_id", "Supply a unique request id.");
            response.setStatus(202);
            return service.requestRun(parts[4], id);
        }
        throw new ApiException(404, "not_found", "Unknown discovery endpoint or method.");
    }

    private static ApiException adminOnly() {
        return new ApiException(403, "forbidden", "Only an administrator may use this Discovery function.");
    }

    /** Profile id to its collection; empty when the runtime configuration cannot be read. */
    private static Map<String, String> profileCollections(final DiscoveryService service) {
        final Map<String, String> out = new HashMap<>();
        try {
            for (final Object p : service.catalogJson().getJSONArray("profiles")) {
                final JsonObject profile = (JsonObject) p;
                out.put(profile.getString("id"), profile.getString("collection"));
            }
        } catch (final ApiException | RuntimeException e) {
            // no profile is visible without a readable configuration
        }
        return out;
    }

    private static boolean visible(final JsonObject definition, final Map<String, String> profiles, final Predicate<String> allowed) {
        final String collection = profiles.get(definition.optString("profile", ""));
        return collection != null && allowed.test(collection);
    }

    private static void requireProfile(final String profile, final Map<String, String> profiles, final Predicate<String> allowed)
            throws ApiException {
        final String collection = profiles.get(profile);
        if (collection == null || !allowed.test(collection)) {
            throw new ApiException(403, "collection_not_allowed", "This profile writes into a collection that is not available to you.",
                    Json.obj("field", "profile"));
        }
    }

    /** A job of a foreign collection answers like a missing one, so job ids cannot be probed. */
    private static void requireJob(final DiscoveryService service, final String id, final Map<String, String> profiles,
            final Predicate<String> allowed) throws ApiException {
        final JsonArray jobs = service.jobs(id).getJSONArray("jobs");
        if (jobs.isEmpty() || !visible(jobs.getJSONObject(0).getJSONObject("definition"), profiles, allowed)) {
            throw new ApiException(404, "job_not_found", "Discovery job does not exist.");
        }
    }

    private static JsonObject visibleJobs(final JsonObject all, final Map<String, String> profiles, final Predicate<String> allowed) {
        final JsonArray jobs = new JsonArray();
        for (final Object row : all.getJSONArray("jobs")) {
            if (visible(((JsonObject) row).getJSONObject("definition"), profiles, allowed)) jobs.put(row);
        }
        return all.put("jobs", jobs);
    }

    private static JsonObject catalog(final JsonObject catalog, final Predicate<String> allowed) {
        final JsonArray profiles = new JsonArray();
        final java.util.Set<String> ids = new java.util.HashSet<>();
        for (final Object p : catalog.getJSONArray("profiles")) {
            final JsonObject profile = (JsonObject) p;
            if (allowed.test(profile.getString("collection"))) {
                profiles.put(profile);
                ids.add(profile.getString("id"));
            }
        }
        catalog.put("profiles", profiles);
        for (final Object s : catalog.getJSONArray("sources")) {
            final JsonObject source = (JsonObject) s;
            final JsonArray supported = new JsonArray();
            final JsonArray all = source.optJSONArray("supported_profiles");
            if (all != null) for (final Object id : all) if (ids.contains(String.valueOf(id))) supported.put(id);
            source.put("supported_profiles", supported);
        }
        return catalog;
    }

    /**
     * The global state without the details of foreign batches: a batch of another collection shows only that
     * something runs; the backlog snapshot keeps the visible profiles; no global action is offered.
     */
    private static JsonObject status(final JsonObject status, final JsonObject visible, final Map<String, String> profiles,
            final Predicate<String> allowed) {
        for (final String key : new String[] {"active_run", "last_run", "active_batch"}) {
            final Object batch = status.opt(key);
            if (batch instanceof JsonObject && !allowed.test(String.valueOf(((JsonObject) batch).opt("collection")))) {
                status.put(key, JsonObject.NULL);
            }
        }
        final Object collection = status.opt("collection");
        if (collection instanceof String && !allowed.test((String) collection)) {
            status.put("collection", JsonObject.NULL).put("job_name", JsonObject.NULL).put("started_at", JsonObject.NULL);
            final String reason = status.optString("waiting_reason", "");
            if (!java.util.Set.of("idle", "disabled", "paused", "heartbeat_disabled_or_duplicate").contains(reason)) {
                status.put("waiting_reason", "busy");
            }
        }
        final JsonObject state = status.optJSONObject("state");
        if (state != null) {
            final JsonObject kept = new JsonObject().put("available", state.optBoolean("available"));
            final JsonObject all = state.optJSONObject("profiles");
            if (all != null) {
                final JsonObject mine = new JsonObject();
                for (final String id : all.keySet()) {
                    final String c = profiles.get(id);
                    if (c != null && allowed.test(c)) mine.put(id, all.get(id));
                }
                kept.put("profiles", mine);
            }
            status.put("state", kept);
        }
        status.put("job_count", visible.getJSONArray("jobs").length()).put("allowed_actions", new JsonArray());
        status.remove("config_error");
        return status;
    }

    private static Long revision(final String value) throws ApiException {
        if (value == null) return null;
        if (!value.matches("(?:[0-9]{1,18}|\"[0-9]{1,18}\")")) throw ApiException.invalid("If-Match", "Expected numeric store revision.");
        return Long.parseLong(value.replace("\"", ""));
    }
}
