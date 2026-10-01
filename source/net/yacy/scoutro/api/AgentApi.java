/*
 *  AgentApi
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentAuthorizer;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.ScoutroAgents;
import net.yacy.scoutro.agents.TokenRecord;

/**
 * The agent path {@code /scoutro/api/agent/v1/*}: Bearer authentication with
 * an agent token, one authorization decision per request, audit, and the
 * dispatch to the scoped actions. This path never grants the administrator
 * role; Digest or Basic credentials are refused here.
 */
final class AgentApi {

    /** Container-independent request, so that the agent path can be tested without a servlet engine. */
    static final class Request {
        final String method;
        /** Path segments after {@code /agent/v1}, e.g. ["crawls", "abc", "stop"]. */
        final List<String> path;
        final Map<String, String> query;
        final String authorization;
        final String client;
        final String idempotencyKey;
        final BodySupplier body;

        Request(final String method, final List<String> path, final Map<String, String> query,
                final String authorization, final String client, final String idempotencyKey, final BodySupplier body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.authorization = authorization;
            this.client = client;
            this.idempotencyKey = idempotencyKey;
            this.body = body;
        }
    }

    /** Reads and checks the JSON body of a mutating request (same CSRF rules as the admin API). */
    interface BodySupplier {
        JSONObject get() throws ApiException;
    }

    /** Result of an agent request. */
    static final class Response {
        final int status;
        final JSONObject body;
        final Map<String, String> headers = new LinkedHashMap<>();

        Response(final int status, final JSONObject body) {
            this.status = status;
            this.body = body;
        }
    }

    /** An authenticated and authorized call. */
    static final class Call {
        final Agent agent;
        final TokenRecord token;
        final Request request;

        Call(final Agent agent, final TokenRecord token, final Request request) {
            this.agent = agent;
            this.token = token;
            this.request = request;
        }
    }

    private static final String BEARER = "bearer ";

    private final ScoutroAgents agents;
    private final ScopedActions scoped;

    AgentApi(final ScoutroAgents agents, final ScopedActions scoped) {
        this.agents = agents;
        this.scoped = scoped;
    }

    Response handle(final Request r) {
        try {
            return dispatch(r);
        } catch (final ApiException e) {
            return new Response(e.status(), e.toJson());
        }
    }

    private Response dispatch(final Request r) throws ApiException {
        if (r.query.containsKey("access_token") || r.query.containsKey("token")) {
            return error(r, null, null, "-", 400, "token_in_url",
                    "Send the token in the Authorization header, never in the URL.");
        }
        if (this.agents.authorizer.authBlocked(r.client)) {
            return error(r, null, null, "-", 429, "too_many_failures",
                    "Too many failed authentications from this client; try again in a minute.");
        }
        final String auth = r.authorization == null ? "" : r.authorization.trim();
        if (auth.isEmpty()) {
            return unauthorized(r, "missing_bearer", "This path needs 'Authorization: Bearer <agent token>'.");
        }
        if (!auth.toLowerCase(Locale.ROOT).startsWith(BEARER)) {
            return unauthorized(r, "bearer_required",
                    "The agent path accepts only agent tokens (Bearer); administrator credentials are not used here.");
        }
        final AgentStore.AuthResult auth2 = this.agents.store.authenticate(auth.substring(BEARER.length()).trim(), r.client);
        if (!auth2.ok()) {
            if ("invalid_token".equals(auth2.reason)) {
                this.agents.authorizer.recordAuthFailure(r.client);
            }
            final String agentId = auth2.agent == null ? null : auth2.agent.id;
            final String tokenId = auth2.token == null ? null : auth2.token.publicId;
            final String message;
            switch (auth2.reason) {
                case "token_expired": message = "The agent token has expired."; break;
                case "token_revoked": message = "The agent token has been revoked."; break;
                case "agent_paused": message = "This agent is paused by the administrator."; break;
                case "agent_revoked": message = "This agent has been revoked."; break;
                default: message = "The agent token is not valid.";
            }
            final Response resp = error(r, agentId, tokenId, "-", auth2.httpStatus(), auth2.reason, message);
            if (auth2.httpStatus() == 401) {
                resp.headers.put("WWW-Authenticate", "Bearer realm=\"scoutro-agent\", error=\"invalid_token\"");
            }
            return resp;
        }
        final Agent agent = auth2.agent;
        final TokenRecord token = auth2.token;
        final Route route = refine(route(r.method, r.path), r.query);
        if (route.error != null) {
            return audited(r, agent, token, "-", new Response(route.error.status(), route.error.toJson()),
                    route.error.code());
        }
        final List<String> collections = requestedCollections(r);
        final AgentAuthorizer.Decision d = this.agents.authorizer.authorize(agent, token.publicId, route.action, collections);
        if (!d.allowed) {
            final Response resp = new Response(d.status, new ApiException(d.status, d.reason, d.message).toJson());
            return audited(r, agent, token, route.action, resp, d.reason);
        }
        try {
            final Response resp = execute(route, new Call(agent, token, r));
            return audited(r, agent, token, route.action, resp, null);
        } catch (final ApiException e) {
            return audited(r, agent, token, route.action, new Response(e.status(), e.toJson()), e.code());
        } catch (final AgentException e) {
            final ApiException a = toApi(e);
            return audited(r, agent, token, route.action, new Response(a.status(), a.toJson()), e.code());
        }
    }

    /** The collections a request names explicitly; the scoped actions add the scope where none is named. */
    private static List<String> requestedCollections(final Request r) {
        final List<String> out = new ArrayList<>();
        final String q = r.query.get("collection");
        if (q != null && !q.trim().isEmpty()) {
            out.add(q.trim());
        }
        return out;
    }

    // ------------------------------------------------------------------
    // routing
    // ------------------------------------------------------------------

    static final class Route {
        final String action;
        final String id;
        final ApiException error;

        Route(final String action, final String id, final ApiException error) {
            this.action = action;
            this.id = id;
            this.error = error;
        }
    }

    private static Route ok(final String action) {
        return new Route(action, null, null);
    }

    private static Route notFound() {
        return new Route(null, null, new ApiException(404, "not_found",
                "Unknown agent API path. See " + AgentActionRegistry.BASE_PATH + "/capabilities."));
    }

    private static Route method(final String method, final String... allowed) {
        return new Route(null, null, new ApiException(405, "method_not_allowed", "Method " + method
                + " is not allowed here. Allowed: " + String.join(", ", allowed) + ".",
                Json.obj("allowed", String.join(", ", allowed))));
    }

    /**
     * Requests whose parameters select a broader action: network search and
     * the global index status are separate grants, never part of a scope.
     */
    static Route refine(final Route route, final Map<String, String> query) {
        if ("search".equals(route.action) && "network".equals(query.get("source"))) {
            return ok("search.network");
        }
        if ("index.status".equals(route.action) && "true".equals(query.get("global"))) {
            return ok("index.status.global");
        }
        return route;
    }

    static Route route(final String method, final List<String> p) {
        final int n = p.size();
        final String first = n == 0 ? "" : p.get(0);
        switch (first) {
            case "capabilities":
                if (n != 1) return notFound();
                return "GET".equals(method) ? ok(AgentActionRegistry.CAPABILITIES) : method(method, "GET");
            case "heartbeat":
                if (n != 1) return notFound();
                return "POST".equals(method) ? ok(AgentActionRegistry.HEARTBEAT) : method(method, "POST");
            case "search":
                if (n != 1) return notFound();
                return "GET".equals(method) ? ok("search") : method(method, "GET");
            case "system":
                if (n != 1) return notFound();
                return "GET".equals(method) ? ok("system.status") : method(method, "GET");
            case "config":
                if (n != 1) return notFound();
                if ("GET".equals(method)) return ok("config.get");
                return "PATCH".equals(method) ? ok("config.set") : method(method, "GET", "PATCH");
            case "index":
                if (n == 1) return "GET".equals(method) ? ok("index.status") : method(method, "GET");
                if (n == 2 && "lookup".equals(p.get(1))) return "GET".equals(method) ? ok("index.lookup") : method(method, "GET");
                if (n == 2 && "evidence".equals(p.get(1))) return "GET".equals(method) ? ok("index.evidence") : method(method, "GET");
                return notFound();
            case "crawls":
                if (n == 1) {
                    if ("GET".equals(method)) return ok("crawl.list");
                    return "POST".equals(method) ? ok("crawl.start") : method(method, "GET", "POST");
                }
                if (n == 2) return "GET".equals(method) ? new Route("crawl.status", p.get(1), null) : method(method, "GET");
                if (n == 3 && "stop".equals(p.get(2))) {
                    return "POST".equals(method) ? new Route("crawl.stop", p.get(1), null) : method(method, "POST");
                }
                return notFound();
            default:
                return notFound();
        }
    }

    // ------------------------------------------------------------------
    // execution
    // ------------------------------------------------------------------

    private Response execute(final Route route, final Call call) throws ApiException, AgentException {
        switch (route.action) {
            case AgentActionRegistry.CAPABILITIES:
                return new Response(200, capabilities(call));
            case AgentActionRegistry.HEARTBEAT:
                return new Response(200, heartbeat(call));
            default:
                return this.scoped.execute(route.action, route.id, call);
        }
    }

    private JSONObject capabilities(final Call call) throws AgentException {
        final Agent a = call.agent;
        final JSONArray actions = new JSONArray();
        for (final AgentActionRegistry.Action action : AgentActionRegistry.all()) {
            if (!a.actions.contains(action.id) || !action.kinds.contains(a.kind)) {
                continue; // list only what this agent can actually use
            }
            actions.put(Json.obj("name", action.id, "description", action.description, "risk", action.risk.id,
                    "scoped", action.scoped, "http", Json.obj("method", action.method,
                            "path", AgentActionRegistry.BASE_PATH + action.path)));
        }
        final String fingerprint = AgentAuthorizer.fingerprint(a, call.token.publicId);
        try {
            this.agents.store.recordHandshake(a.id, fingerprint);
        } catch (final java.io.IOException e) {
            throw new AgentException(503, "agent_store_unavailable", "The handshake could not be stored.");
        }
        final JSONArray collections = new JSONArray(a.scope.collections);
        return Json.obj(
                "agent", Json.obj("id", a.id, "name", a.name, "kind", a.kind.id, "status", a.status.id,
                        "revision", a.revision),
                "token", Json.obj("id", call.token.publicId, "expiresAt", iso(call.token.expiresAt)),
                "scope", Json.obj("collections", collections, "allCollections", a.scope.allCollections,
                        "networkSearch", a.actions.contains("search.network")),
                "limits", Json.obj("domains", new JSONArray(a.limits.domains), "maxDepth", a.limits.maxDepth,
                        "maxPages", a.limits.maxPages, "maxParallelCrawls", a.limits.maxParallelCrawls,
                        "requestsPerMinute", a.limits.requestsPerMinute, "maxTaskSeconds", a.limits.maxTaskSeconds,
                        "modelAllowed", a.limits.modelAllowed),
                "actions", actions,
                "fingerprint", fingerprint,
                "apiVersion", ScoutroActions.API_VERSION);
    }

    private JSONObject heartbeat(final Call call) throws ApiException {
        final JSONObject body = call.request.body.get();
        final JSONObject clean = new JSONObject(true);
        for (final String key : body.keySet()) {
            final Object v = body.opt(key);
            switch (key) {
                case "version":
                case "lastError":
                case "status":
                    if (!(v instanceof String) || ((String) v).length() > 300) {
                        throw ApiException.invalid(key, "'" + key + "' must be a string of at most 300 characters.");
                    }
                    Json.put(clean, key, ((String) v).replaceAll("[\\p{Cc}\\p{Cf}]", " "));
                    break;
                case "clustroReachable":
                case "modelConfigured":
                    if (!(v instanceof Boolean)) {
                        throw ApiException.invalid(key, "'" + key + "' must be true or false.");
                    }
                    Json.put(clean, key, v);
                    break;
                case "clustroCheckedAt":
                case "lastPollAt":
                case "activeRuns":
                    if (!(v instanceof Number) || ((Number) v).longValue() < 0) {
                        throw ApiException.invalid(key, "'" + key + "' must be a non-negative number.");
                    }
                    Json.put(clean, key, ((Number) v).longValue());
                    break;
                default:
                    throw ApiException.invalid(key, "Unknown heartbeat field '" + key + "'. Allowed: version, status, "
                            + "lastError, clustroReachable, clustroCheckedAt, modelConfigured, lastPollAt, activeRuns.");
            }
        }
        this.agents.store.recordHeartbeat(call.agent.id, clean);
        return Json.obj("status", "ok", "receivedAt", iso(this.agents.store.now()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Response unauthorized(final Request r, final String code, final String message) {
        final Response resp = error(r, null, null, "-", 401, code, message);
        resp.headers.put("WWW-Authenticate", "Bearer realm=\"scoutro-agent\"");
        return resp;
    }

    private Response error(final Request r, final String agentId, final String tokenId, final String action,
            final int status, final String code, final String message) {
        this.agents.audit.record(agentId, tokenId, action, "deny", code, null, status, r.client);
        return new Response(status, new ApiException(status, code, message).toJson());
    }

    private Response audited(final Request r, final Agent agent, final TokenRecord token, final String action,
            final Response resp, final String reason) {
        final boolean ok = resp.status < 400;
        final List<String> collections = new ArrayList<>();
        final String c = r.query.get("collection");
        if (c != null && !c.isEmpty()) {
            collections.add(c);
        }
        this.agents.audit.record(agent.id, token.publicId, action, ok ? "allow" : "deny", reason,
                collections.isEmpty() ? Collections.emptyList() : collections, resp.status, r.client);
        return resp;
    }

    static ApiException toApi(final AgentException e) {
        return new ApiException(e.status(), e.code(), e.getMessage(),
                e.field() == null ? null : Json.obj("field", e.field()));
    }

    static String iso(final long millis) {
        return java.time.Instant.ofEpochMilli(millis).toString();
    }
}
