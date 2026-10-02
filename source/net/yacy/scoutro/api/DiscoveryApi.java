/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.discovery.DiscoveryService;

/** Admin role and JSON/same-origin body are enforced by the parent servlet. */
final class DiscoveryApi {
    private DiscoveryApi() { }
    static JsonObject route(final String method, final String[] parts, final HttpServletRequest request,
            final HttpServletResponse response, final JsonObject body) throws ApiException {
        final DiscoveryService service = DiscoveryService.get();
        final String path = parts.length > 3 ? parts[3] : "";
        final Long revision = revision(request.getHeader("If-Match"));
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
        throw new ApiException(404, "not_found", "Unknown discovery endpoint or method.");
    }
    private static Long revision(final String value) throws ApiException {
        if (value == null) return null;
        if (!value.matches("(?:[0-9]{1,18}|\"[0-9]{1,18}\")")) throw ApiException.invalid("If-Match", "Expected numeric store revision.");
        return Long.parseLong(value.replace("\"", ""));
    }
}
