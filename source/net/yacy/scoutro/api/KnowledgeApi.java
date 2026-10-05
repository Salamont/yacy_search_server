/*
 *  KnowledgeApi
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

import java.io.IOException;
import java.util.Iterator;
import java.util.function.Supplier;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgRuntime;

/**
 * Administrator routes of the knowledge graph: the read routes of
 * {@link KnowledgeRead} (filtered by the optional {@code collection}),
 * {@code GET /v1/kg/status} and {@code POST /v1/kg/control} with the actions
 * {@code pause}, {@code resume}, {@code reconcile}, {@code confirm_reconcile} and
 * {@code llm_retry}.
 * The servlet checks the administrator role before calling this class; the
 * control body goes through the servlet's cross-site checks.
 */
final class KnowledgeApi {

    /** The request body of a mutating call, read only after path and method are valid. */
    interface Body {
        JSONObject get() throws ApiException, IOException;
    }

    /** Allowed values of {@code action}. */
    static final java.util.List<String> ACTIONS = java.util.List.of("pause", "resume", "reconcile", "confirm_reconcile",
            "llm_retry");

    private final Supplier<KgRuntime> runtime;

    KnowledgeApi(final Supplier<KgRuntime> runtime) {
        this.runtime = runtime;
    }

    JSONObject route(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        return route(method, parts, java.util.Collections.emptyMap(), body);
    }

    /** Status, control and (package 3) the read routes, for the administrator; {@code collection} filters the reads. */
    JSONObject route(final String method, final String[] parts, final java.util.Map<String, String> query, final Body body)
            throws ApiException, IOException {
        if (parts.length >= 5 && KnowledgeRead.handles(parts[3]) || parts.length == 4 && "entities".equals(parts[3])) {
            return new KnowledgeRead(this.runtime).route(method, java.util.Arrays.asList(parts).subList(3, parts.length), query,
                    SeoAnalysis.adminCollections(query));
        }
        if (parts.length != 4) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        switch (parts[3]) {
            case "status":
                allow(method, "GET");
                return status();
            case "control":
                allow(method, "POST");
                return control(body.get());
            default:
                throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
    }

    private static void allow(final String method, final String allowed) throws ApiException {
        if (!allowed.equals(method)) {
            throw new ApiException(405, "method_not_allowed", "Method " + method + " is not allowed here. Allowed: "
                    + allowed + ".", Json.obj("allowed", allowed));
        }
    }

    /** Status of the running instance; before start (or when Scoutro runs without it) the disabled status. */
    private JSONObject status() {
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            return Json.obj("schema", KgRuntime.STATUS_SCHEMA, "enabled", false, "state", "disabled",
                    "reason", "not_started");
        }
        return r.status();
    }

    private JSONObject control(final JSONObject body) throws ApiException {
        final Iterator<?> keys = body.keys();
        while (keys.hasNext()) {
            final String k = String.valueOf(keys.next());
            if (!"action".equals(k)) {
                throw ApiException.invalid(k, "Unknown field '" + k + "'. Allowed: action.");
            }
        }
        final String action = body.optString("action", "");
        if (!ACTIONS.contains(action)) {
            throw ApiException.invalid("action", "Field 'action' must be one of: " + String.join(", ", ACTIONS) + ".");
        }
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw new ApiException(409, KgException.DISABLED,
                    "The knowledge graph is disabled. Set " + KgConfig.ENABLED + "=true and restart Scoutro.");
        }
        try {
            switch (action) {
                case "pause":
                    return r.pause();
                case "resume":
                    return r.resume();
                case "reconcile":
                    return r.reconcile();
                case "llm_retry":
                    return r.llmRetry();
                default:
                    return r.confirmReconcile();
            }
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    static ApiException toApi(final KgException e) {
        switch (e.code()) {
            case KgException.DISABLED:
                return new ApiException(409, KgException.DISABLED,
                        "The knowledge graph is disabled. Set " + KgConfig.ENABLED + "=true and restart Scoutro.");
            case KgException.UNAVAILABLE:
                return new ApiException(503, KgException.UNAVAILABLE, "The knowledge graph is not running.",
                        Json.obj("reason", e.reason()));
            case KgException.NOTHING_TO_CONFIRM:
                return new ApiException(409, KgException.NOTHING_TO_CONFIRM,
                        "No reconcile is waiting for confirmation; see sync.reconcile in GET /scoutro/api/v1/kg/status.");
            case KgException.SYNC_UNAVAILABLE:
                return new ApiException(503, KgException.SYNC_UNAVAILABLE,
                        "The knowledge graph does not follow the embedded Solr index here.");
            case KgException.LLM_UNAVAILABLE:
                return new ApiException(409, KgException.LLM_UNAVAILABLE,
                        "The LLM tier is off: set " + KgConfig.LLM_COLLECTIONS + " and select a model for the usage knowledge.");
            case KgException.WRITE_REFUSED:
                return new ApiException(503, "kg_write_refused",
                        "The knowledge graph cannot write right now; see GET /scoutro/api/v1/kg/status.",
                        Json.obj("reason", e.reason()));
            default:
                return new ApiException(503, KgException.UNAVAILABLE, "The knowledge graph store failed.",
                        Json.obj("reason", e.code()));
        }
    }
}
