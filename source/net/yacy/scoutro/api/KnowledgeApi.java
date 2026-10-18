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
import net.yacy.scoutro.knowledge.read.KgExport;
import net.yacy.scoutro.knowledge.read.KgReader;

/**
 * Administrator routes of the knowledge graph: the read routes of
 * {@link KnowledgeRead} (filtered by the optional {@code collection}),
 * {@code GET /v1/kg/status} and {@code POST /v1/kg/control} with the actions
 * {@code pause}, {@code resume}, {@code reconcile}, {@code confirm_reconcile} and
 * {@code llm_retry}, the streamed download {@code GET /v1/kg/export/download}
 * ({@link #download}), and (package 6.2) the settings of each collection
 * {@code GET /v1/kg/collections} and {@code PATCH /v1/kg/collections/{collection}}
 * ({@link KgCollectionSettings}), and the knowledge prompt {@code GET /v1/kg/prompt}
 * and {@code POST /v1/kg/prompt} with the actions {@code validate},
 * {@code activate} and {@code reset} ({@link #prompt}); agents never get these.
 * The servlet checks the administrator role before calling this class; the
 * control body goes through the servlet's cross-site checks.
 */
final class KnowledgeApi {

    /** The request body of a mutating call, read only after path and method are valid. */
    interface Body {
        JSONObject get() throws ApiException, IOException;
    }

    /** Where the download is written; opened only after the first export page was read. */
    interface Download {
        java.io.Writer open(String contentType, String filename) throws IOException;
    }

    /** Records per read lease of the download. */
    static final int DOWNLOAD_PAGE = 200;

    /** Allowed values of {@code action} of {@code POST /v1/kg/prompt}. */
    static final java.util.List<String> PROMPT_ACTIONS = java.util.List.of("validate", "activate", "reset");

    /** Allowed values of {@code action}. */
    static final java.util.List<String> ACTIONS = java.util.List.of("pause", "resume", "reconcile", "confirm_reconcile",
            "llm_retry", "backup", "restore", "rebuild", "rebuild_cancel", "rebuild_confirm", "derive", "delete_backup");

    private final Supplier<KgRuntime> runtime;
    private final Supplier<KgCollectionSettings> settings;
    private final Supplier<KgLlmSettings> llmSettings;

    KnowledgeApi(final Supplier<KgRuntime> runtime) {
        this(runtime, KgCollectionSettings::current);
    }

    KnowledgeApi(final Supplier<KgRuntime> runtime, final Supplier<KgCollectionSettings> settings) {
        this(runtime, settings, KgLlmSettings::current);
    }

    KnowledgeApi(
            final Supplier<KgRuntime> runtime,
            final Supplier<KgCollectionSettings> settings,
            final Supplier<KgLlmSettings> llmSettings) {
        this.runtime = runtime;
        this.settings = settings;
        this.llmSettings = llmSettings;
    }

    /** Who calls (the audit of assessments); null: unknown. */
    private String actor;

    KnowledgeApi actor(final String actor) {
        this.actor = actor;
        return this;
    }

    JSONObject route(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        return route(method, parts, java.util.Collections.emptyMap(), body);
    }

    /**
     * Status, control and (package 3) the read routes, for the administrator; {@code collection}
     * filters the reads.
     */
    JSONObject route(
            final String method,
            final String[] parts,
            final java.util.Map<String, String> query,
            final Body body)
            throws ApiException, IOException {
        if (parts.length >= 5 && KnowledgeRead.handles(parts[3])
                || parts.length == 4 && KnowledgeRead.single(parts[3])) {
            return new KnowledgeRead(this.runtime).route(method, java.util.Arrays.asList(parts).subList(3, parts.length), query,
                    SeoAnalysis.adminCollections(query), null); // administrator grant; collection remains only the view filter
        }
        if ((parts.length == 4 || parts.length == 5) && "collections".equals(parts[3])) {
            return collections(method, parts, query, body);
        }
        if (parts.length == 4 && "tier3-review".equals(parts[3])) {
            return tier3Review(method, query, body);
        }
        if (parts.length >= 4 && "assessments".equals(parts[3]) && (parts.length == 4 || parts.length == 6 && "revoke".equals(parts[5]))) {
            return assessments(method, parts, query, body);
        }
        if ((parts.length == 4 || parts.length == 5) && "llm-quality".equals(parts[3])) {
            // the quality check of a candidate model (docs/SCOUTRO_LLM_QUALITY_CHECK.md): writes no graph and no setting
            return LlmQualityApi.route(LlmQualityApi.check(), method, parts, query, body);
        }
        if (parts.length != 4) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        switch (parts[3]) {
            case "llm-schedule":
                if (!"GET".equals(method)) allow(method, "PUT");
                noParameters(query);
                final KgLlmSettings ls = this.llmSettings.get();
                if (ls == null)
                    throw new ApiException(
                            503, "settings_unavailable", "The settings cannot be read.");
                return "GET".equals(method) ? ls.read() : ls.update(body.get());
            case "llm-run":
                allow(method, "POST");
                noParameters(query);
                return llmRun(body.get());
            case "llm-refusals":
                allow(method, "GET");
                return llmRefusals(query);
            case "status":
                allow(method, "GET");
                return status();
            case "backups":
                allow(method, "GET");
                if (!query.isEmpty()) {
                    throw ApiException.invalid(query.keySet().iterator().next(), "This route takes no parameters.");
                }
                return backups();
            case "control":
                allow(method, "POST");
                return control(body.get());
            case "prompt":
                if ("GET".equals(method)) {
                    if (!query.isEmpty()) {
                        throw ApiException.invalid(query.keySet().iterator().next(), "This route takes no parameters.");
                    }
                    return prompt(null);
                }
                allow(method, "POST");
                return prompt(body.get());
            default:
                throw new ApiException(
                        404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
    }

    /** Fields of POST /v1/kg/assessments. */
    static final java.util.Set<String> ASSESSMENT_FIELDS = java.util.Set.of("action", "scope", "predicate", "value", "subject", "tier",
            "targetPredicate", "reason", "token");

    /**
     * Assessments of graph facts (WP-E, docs/SCOUTRO_KG_ASSESSMENTS.md): GET lists them ({@code status}, {@code limit});
     * POST without {@code token} previews, with the preview's token stores and applies; POST .../{id}/revoke revokes.
     */
    private JSONObject assessments(final String method, final String[] parts, final java.util.Map<String, String> query, final Body body)
            throws ApiException, IOException {
        final KgRuntime r = this.runtime.get();
        if (parts.length == 6) {
            allow(method, "POST");
            noParameters(query);
            if (!parts[4].matches("^kga_[a-z2-7]{20}$")) throw ApiException.invalid("id", "An assessment ID (kga_...).");
            final JSONObject b = body.get();
            for (final Iterator<?> keys = b.keys(); keys.hasNext();) {
                throw ApiException.invalid(String.valueOf(keys.next()), "This route takes no fields.");
            }
            if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
            try {
                return r.assessmentRevoke(parts[4], this.actor);
            } catch (final KgException e) {
                throw toApi(e);
            }
        }
        if ("GET".equals(method)) {
            String status = "active";
            int limit = 100;
            for (final java.util.Map.Entry<String, String> q : query.entrySet()) {
                if ("status".equals(q.getKey())) {
                    if (!java.util.Set.of("active", "revoked", "all").contains(q.getValue())) {
                        throw ApiException.invalid("status", "active, revoked or all.");
                    }
                    status = "all".equals(q.getValue()) ? null : q.getValue();
                } else if ("limit".equals(q.getKey())) {
                    try {
                        limit = Integer.parseInt(q.getValue());
                    } catch (final NumberFormatException e) {
                        limit = -1;
                    }
                    if (limit < 1 || limit > 500) throw ApiException.invalid("limit", "1 to 500.");
                } else {
                    throw ApiException.invalid(q.getKey(), "Unknown parameter; status and limit are allowed.");
                }
            }
            if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
            try {
                return r.assessments(status, limit);
            } catch (final KgException e) {
                throw toApi(e);
            }
        }
        allow(method, "POST");
        noParameters(query);
        final JSONObject b = body.get();
        for (final Iterator<?> keys = b.keys(); keys.hasNext();) {
            final String key = String.valueOf(keys.next());
            if (!ASSESSMENT_FIELDS.contains(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        final net.yacy.scoutro.knowledge.publish.Assessments.Draft d = draft(b);
        d.actor = this.actor;
        if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
        try {
            if (b.isNull("token") || b.optString("token").isEmpty()) {
                final JSONObject preview = r.assessmentPreview(d);
                return Json.obj("preview", true, "assessment", net.yacy.scoutro.knowledge.publish.Assessments.draft(d).toJson(), "changes",
                        preview, "token", preview.opt("token"));
            }
            return r.assessmentCreate(d, b.optString("token"));
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    /**
     * The rule-based review of stored LLM facts (WP-G, docs/SCOUTRO_KG_TIER3_REVIEW.md): GET the progress; POST one bounded
     * step, {@code apply} false (default) a dry run, true withdraws the findings by automatic assessments.
     */
    private JSONObject tier3Review(final String method, final java.util.Map<String, String> query, final Body body) throws ApiException, IOException {
        final KgRuntime r = this.runtime.get();
        noParameters(query);
        if ("GET".equals(method)) {
            if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
            try {
                return r.tier3ReviewStatus();
            } catch (final KgException e) {
                throw toApi(e);
            }
        }
        allow(method, "POST");
        final JSONObject b = body.get();
        for (final Iterator<?> keys = b.keys(); keys.hasNext();) {
            final String key = String.valueOf(keys.next());
            if (!java.util.Set.of("apply", "maxDocuments", "restart").contains(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        final Object apply = b.opt("apply"), restart = b.opt("restart"), max = b.opt("maxDocuments");
        if (apply != null && !(apply instanceof Boolean)) throw ApiException.invalid("apply", "true or false.");
        if (restart != null && !(restart instanceof Boolean)) throw ApiException.invalid("restart", "true or false.");
        if (max != null && (!(max instanceof Integer) || (Integer) max < 1 || (Integer) max > net.yacy.scoutro.knowledge.publish.Tier3Review.MAX_DOCUMENTS)) {
            throw ApiException.invalid("maxDocuments", "1 to " + net.yacy.scoutro.knowledge.publish.Tier3Review.MAX_DOCUMENTS + ".");
        }
        if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
        try {
            return r.tier3ReviewStep(Boolean.TRUE.equals(apply), max == null ? 1000 : (Integer) max, Boolean.TRUE.equals(restart));
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    /** The validated draft of a POST body. */
    static net.yacy.scoutro.knowledge.publish.Assessments.Draft draft(final JSONObject b) throws ApiException {
        final net.yacy.scoutro.knowledge.publish.Assessments.Draft d = new net.yacy.scoutro.knowledge.publish.Assessments.Draft();
        d.action = b.optString("action", null);
        final JSONObject scope = b.optJSONObject("scope");
        if (scope == null || scope.length() != 1) {
            throw ApiException.invalid("scope", "An object with exactly one of source (document ID), host or domain.");
        }
        final String kind = String.valueOf(scope.keys().next());
        final String value = scope.optString(kind, "").trim().toLowerCase(java.util.Locale.ROOT);
        switch (kind) {
            case "source":
                if (!net.yacy.scoutro.knowledge.KgIds.isDocId(scope.optString(kind))) {
                    throw ApiException.invalid("scope", "source is a document ID of the graph (12 characters).");
                }
                d.scope = scope.optString(kind);
                break;
            case "host":
            case "domain":
                if (!value.matches("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$") || value.length() > 253) {
                    throw ApiException.invalid("scope", kind + " is a host name.");
                }
                if ("domain".equals(kind) && !value.equals(net.yacy.scoutro.knowledge.resolve.Normalizers.registrableDomain(value))) {
                    throw ApiException.invalid("scope", "domain is a registrable domain (example.de, not www.example.de); use host for one host.");
                }
                d.scope = value;
                break;
            default:
                throw ApiException.invalid("scope", "One of source, host or domain.");
        }
        d.scopeKind = kind;
        d.predicate = b.optString("predicate", null);
        d.value = b.isNull("value") ? null : b.optString("value", null);
        d.subjectId = b.isNull("subject") ? null : b.optString("subject", null);
        if (b.has("tier") && !b.isNull("tier")) {
            final Object t = b.opt("tier");
            if (!(t instanceof Integer)) throw ApiException.invalid("tier", "1, 2 or 3.");
            d.tier = (Integer) t;
        }
        d.targetPredicate = b.isNull("targetPredicate") ? null : b.optString("targetPredicate", null);
        d.reason = b.optString("reason", null);
        final String[] invalid = d.validate();
        if (invalid != null) throw ApiException.invalid(invalid[0], invalid[1]);
        return d;
    }

    private static void noParameters(java.util.Map<String, String> query) throws ApiException {
        if (!query.isEmpty())
            throw ApiException.invalid(
                    query.keySet().iterator().next(), "This global route takes no parameters.");
    }

    /** Samples of dropped items and refused answers ({@link KgRuntime#llmRefusals}): {@code reason}, {@code limit} (1-100, 50). */
    private JSONObject llmRefusals(final java.util.Map<String, String> query) throws ApiException {
        for (final String key : query.keySet()) {
            if (!"reason".equals(key) && !"limit".equals(key)) {
                throw ApiException.invalid(key, "Unknown parameter; reason and limit are allowed.");
            }
        }
        final String reason = query.get("reason");
        if (reason != null && !reason.matches("^[a-z_]{1,64}$")) {
            throw ApiException.invalid("reason", "A reason code (lower-case letters and underscores).");
        }
        int limit = 50;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (final NumberFormatException e) {
                limit = -1;
            }
            if (limit < 1 || limit > 100) {
                throw ApiException.invalid("limit", "1 to 100.");
            }
        }
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw toApi(new KgException(KgException.DISABLED, "not started"));
        }
        return r.llmRefusals(reason, limit);
    }

    private JSONObject llmRun(JSONObject body) throws ApiException {
        final String action = body.optString("action", "");
        if (!java.util.Set.of("start", "stop").contains(action))
            throw ApiException.invalid("action", "start or stop required.");
        for (Iterator<?> keys = body.keys(); keys.hasNext(); ) {
            String key = String.valueOf(keys.next());
            if (!("action".equals(key)
                    || "start".equals(action)
                            && java.util.Set.of("maxDocuments", "maxRequests").contains(key)))
                throw ApiException.invalid(key, "Unknown manual-run field.");
        }
        int documents = net.yacy.scoutro.knowledge.sync.LlmTiming.DEFAULT_DOCUMENTS,
                starts = net.yacy.scoutro.knowledge.sync.LlmTiming.DEFAULT_REQUESTS;
        try {
            if (body.has("maxDocuments"))
                documents =
                        net.yacy.scoutro.knowledge.sync.LlmSchedule.integer(
                                body.opt("maxDocuments"), "maxDocuments", 1, 100);
            if (body.has("maxRequests"))
                starts =
                        net.yacy.scoutro.knowledge.sync.LlmSchedule.integer(
                                body.opt("maxRequests"), "maxRequests", 1, 1000);
        }catch(IllegalArgumentException e){throw ApiException.invalid("limits",e.getMessage());}
        KgRuntime r=this.runtime.get();
        if (r == null) throw toApi(new KgException(KgException.DISABLED, "not started"));
        try{return "start".equals(action)?r.llmManualRun(documents,starts):r.llmManualStop();}catch(KgException e){throw toApi(e);}
    }

    /** The settings of each collection (package 6.2): GET the list, PATCH one collection. */
    private JSONObject collections(final String method, final String[] parts, final java.util.Map<String, String> query, final Body body)
            throws ApiException, IOException {
        if (parts.length == 4) {
            allow(method, "GET");
        } else {
            allow(method, "PATCH");
        }
        if (!query.isEmpty()) {
            throw ApiException.invalid(query.keySet().iterator().next(), "This route takes no parameters.");
        }
        final KgCollectionSettings s = this.settings.get();
        if (s == null) {
            throw new ApiException(503, "settings_unavailable", "The settings cannot be read.");
        }
        return parts.length == 4 ? s.list() : s.update(parts[4], body.get());
    }

    /**
     * The whole export as a download ({@code format=ndjson|json}, {@code include=evidence},
     * {@code collection}): NDJSON lines header, entities, statements, trailer
     * (discriminator {@code record}), or the same as one JSON object. Errors
     * before the first page answer as JSON; a failure later ends the stream with
     * a trailer {@code complete:false}.
     */
    void download(final java.util.Map<String, String> q, final Download target) throws ApiException, IOException {
        checkDownload(q);
        download(q, SeoAnalysis.adminCollections(q), false, target);
    }

    /**
     * The download within {@code collections} (null: the whole graph, administrator only); a reader that is not
     * the administrator gets the agents' view (no model names). The caller has checked the collections.
     */
    void download(final java.util.Map<String, String> q, final java.util.List<String> collections, final boolean restricted,
            final Download target) throws ApiException, IOException {
        checkDownload(q);
        final String format = q.get("format") == null || q.get("format").isEmpty() ? "ndjson" : q.get("format");
        final String include = q.get("include");
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw toApi(new KgException(KgException.DISABLED, "not started"));
        }
        final String collection = collections == null || collections.size() != 1 ? null : collections.get(0);
        final String filename = "scoutro-knowledge-" + (collection == null ? "all" : collection) + "-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.now()) + "." + format;
        final boolean json = "json".equals(format);
        try {
            final KgReader reader = restricted ? r.reader().forAgents() : r.reader();
            final KgExport.Sink sink=new KgExport.Sink() {
                private java.io.Writer out;
                private long n;

                @Override
                public void begin(final JSONObject header) throws IOException {
                    this.out = target.open(json ? "application/json;charset=utf-8" : "application/x-ndjson;charset=utf-8", filename);
                    this.out.write(json ? "{\"header\":" + header + ",\"items\":[" : header + "\n");
                }

                @Override
                public void record(final JSONObject record) throws IOException {
                    this.out.write(json ? (this.n == 0 ? "\n" : ",\n") + record : record + "\n");
                    if (++this.n % DOWNLOAD_PAGE == 0) {
                        this.out.flush();
                    }
                }

                @Override
                public void end(final JSONObject trailer) throws IOException {
                    this.out.write(json ? "\n],\"trailer\":" + trailer + "}\n" : trailer + "\n");
                    this.out.flush();
                }
            };
            if("history".equals(include))new net.yacy.scoutro.knowledge.read.ObservationHistory(reader).stream(reader.viewer(collections),collection,DOWNLOAD_PAGE,sink);
            else new KgExport(reader).stream(reader.viewer(collections),"evidence".equals(include),collection,DOWNLOAD_PAGE,sink);
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    private static void checkDownload(final java.util.Map<String, String> q) throws ApiException {
        for (final String k : q.keySet()) {
            if (!java.util.Set.of("format", "include", "collection").contains(k)) {
                throw ApiException.invalid(k, "Unknown parameter '" + k + "'. Allowed: format, include, collection.");
            }
        }
        final String format = q.get("format") == null || q.get("format").isEmpty() ? "ndjson" : q.get("format");
        if (!"ndjson".equals(format) && !"json".equals(format)) {
            throw ApiException.invalid("format", "Field 'format' must be one of: json, ndjson.");
        }
        final String include = q.get("include");
        if (include != null && !include.isEmpty() && !"evidence".equals(include)&&!"history".equals(include)) {
            throw ApiException.invalid("include", "Field 'include' must be one of: evidence, history.");
        }
    }

    private static void allow(final String method, final String allowed) throws ApiException {
        if (!allowed.equals(method)) {
            throw new ApiException(405, "method_not_allowed", "Method " + method + " is not allowed here. Allowed: "
                    + allowed + ".", Json.obj("allowed", allowed));
        }
    }

    /** The backups in the graph's DATA directory with their metadata. */
    private JSONObject backups() throws ApiException {
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw toApi(new KgException(KgException.DISABLED, "not started"));
        }
        try {
            return r.backups();
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    /** The backup file {@code name} for a download (validated against the backup names); 404 otherwise. */
    java.io.File backupFile(final String name) throws ApiException {
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw toApi(new KgException(KgException.DISABLED, "not started"));
        }
        try {
            final java.io.File f = r.backupFile(name);
            if (f == null) {
                throw toApi(new KgException(KgException.BACKUP_NOT_FOUND, name));
            }
            return f;
        } catch (final KgException e) {
            throw toApi(e);
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

    /**
     * The knowledge prompt: GET (body null) reads the active version and the default; POST validates a draft
     * ({@code text}), activates it ({@code text}, optional {@code expectedRevision}) or resets to the compiled-in
     * default (optional {@code expectedRevision}). An activation changes the prompt hash and the cache key but starts
     * no re-extraction and deletes nothing.
     */
    private JSONObject prompt(final JSONObject body) throws ApiException {
        String action = null;
        String text = null;
        Integer expected = null;
        if (body != null) {
            action = body.optString("action", "");
            if (!PROMPT_ACTIONS.contains(action)) {
                throw ApiException.invalid("action", "Field 'action' must be one of: " + String.join(", ", PROMPT_ACTIONS) + ".");
            }
            final boolean withText = !"reset".equals(action);
            final boolean withRevision = !"validate".equals(action);
            final Iterator<?> keys = body.keys();
            while (keys.hasNext()) {
                final String k = String.valueOf(keys.next());
                if (!"action".equals(k) && !("text".equals(k) && withText) && !("expectedRevision".equals(k) && withRevision)) {
                    throw ApiException.invalid(k, "Unknown field '" + k + "'. Allowed: action" + (withText ? ", text" : "")
                            + (withRevision ? ", expectedRevision" : "") + ".");
                }
            }
            if (withText) {
                final Object t = body.opt("text");
                if (!(t instanceof String)) {
                    throw ApiException.invalid("text", "Field 'text' must be the prompt text (a string).");
                }
                text = (String) t;
            }
            if (body.has("expectedRevision")) {
                final Object r = body.opt("expectedRevision");
                if (!(r instanceof Integer) || (Integer) r < 0) {
                    throw ApiException.invalid(
                            "expectedRevision",
                            "Field 'expectedRevision' must be the activeVersion read before (an"
                                + " integer >= 0).");
                }
                expected = (Integer) r;
            }
        }
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw new ApiException(409, KgException.DISABLED,
                    "The knowledge graph is disabled. Set " + KgConfig.ENABLED + "=true and restart Scoutro.");
        }
        try {
            if (action == null) {
                return r.prompt();
            }
            switch (action) {
                case "validate":
                    return r.promptValidate(text);
                case "activate":
                    return r.promptActivate(text, expected);
                default:
                    return r.promptActivate(null, expected);
            }
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    private JSONObject control(final JSONObject body) throws ApiException {
        final String action = body.optString("action", "");
        final Iterator<?> keys = body.keys();
        while (keys.hasNext()) {
            final String k = String.valueOf(keys.next());
            final boolean named = "restore".equals(action) || "delete_backup".equals(action);
            if (!"action".equals(k) && !("backup".equals(k) && named)) {
                throw ApiException.invalid(k, "Unknown field '" + k + "'. Allowed: action" + (named ? ", backup." : "."));
            }
        }
        if (!ACTIONS.contains(action)) {
            throw ApiException.invalid("action", "Field 'action' must be one of: " + String.join(", ", ACTIONS) + ".");
        }
        final String backup = body.optString("backup", "");
        // a backup is named, never given as a path: only the names of GET /kg/backups match
        if (("restore".equals(action) || "delete_backup".equals(action)) && !net.yacy.scoutro.knowledge.store.KgBackup.NAME.matcher(backup).matches()) {
            throw ApiException.invalid("backup", "Field 'backup' must name a backup file of GET /scoutro/api/v1/kg/backups.");
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
                case "backup":
                    return r.backup();
                case "restore":
                    return r.restore(backup);
                case "delete_backup":
                    return r.deleteBackup(backup);
                case "rebuild":
                    return r.rebuild();
                case "rebuild_cancel":
                    return r.rebuildCancel();
                case "rebuild_confirm":
                    return r.rebuildConfirm();
                case "derive":
                    return r.derive();
                default:
                    return r.confirmReconcile();
            }
        } catch (final KgException e) {
            throw toApi(e);
        }
    }

    static ApiException toApi(final KgException e) {
        return toApi(e, KnowledgeRead.ADMIN_BASE + "/export");
    }

    /** @param fullSync the export route a consumer restarts with after 410 */
    static ApiException toApi(final KgException e, final String fullSync) {
        switch (e.code()) {
            case KgException.OPERATION_RUNNING:
                return new ApiException(
                        409,
                        KgException.OPERATION_RUNNING,
                        "A backup, restore or rebuild is running; see GET"
                            + " /scoutro/api/v1/kg/status.",
                        Json.obj("reason", e.reason() == null ? "running" : e.reason()));
            case KgException.BACKUP_NOT_FOUND:
                return new ApiException(404, KgException.BACKUP_NOT_FOUND, "No such backup; see GET /scoutro/api/v1/kg/backups.");
            case KgException.BACKUP_INVALID:
                return new ApiException(422, KgException.BACKUP_INVALID, "The backup is not usable: " + e.getMessage() + ". Nothing was changed.",
                        Json.obj("reason", e.reason()));
            case KgException.RESTORE_FAILED:
                return new ApiException(503, KgException.RESTORE_FAILED, "The restore failed: " + e.getMessage() + ".",
                        Json.obj("reason", e.reason()));
            case KgException.BACKUP_DELETE_FAILED:
                return new ApiException(503, KgException.BACKUP_DELETE_FAILED, "The backup could not be deleted: " + e.getMessage() + ".");
            case KgException.PROMPT_INVALID:
                return new ApiException(
                        422,
                        KgException.PROMPT_INVALID,
                        "The prompt cannot be activated ("
                                + e.reason()
                                + "); POST {\"action\":\"validate\"} names the rule. Nothing was"
                                + " changed.",
                        Json.obj("reason", e.reason()));
            case KgException.PROMPT_REVISION_CONFLICT:
                return new ApiException(409, KgException.PROMPT_REVISION_CONFLICT, "The active prompt changed in the meantime; read"
                        + " GET /scoutro/api/v1/kg/prompt again. Nothing was changed.", Json.obj("activeVersion", Integer.parseInt(e.reason())));
            case KgException.ASSESSMENT:
                switch (e.reason()) {
                    case "assessment_not_found":
                        return new ApiException(404, e.reason(), "No such assessment; see GET /scoutro/api/v1/kg/assessments?status=all.");
                    case "assessment_not_active":
                        return new ApiException(409, e.reason(), "The assessment is revoked already. Nothing was changed.");
                    case "assessment_too_large":
                        return new ApiException(409, e.reason(), "The assessment matches more evidence than one change may move ("
                                + net.yacy.scoutro.knowledge.publish.Assessments.MAX_APPLY + "); narrow its scope, value, subject or tier."
                                + " Nothing was changed.");
                    default:
                        return new ApiException(409, "assessment_preview_stale", "The graph changed since the preview; preview again"
                                + " and confirm with the new token. Nothing was changed.");
                }
            case KgException.NO_REBUILD:
                return new ApiException(409, KgException.NO_REBUILD, "No identity rebuild is running or waiting for confirmation.");
            case KgException.INVALID_CURSOR:
                return new ApiException(400, KgException.INVALID_CURSOR, "The cursor is not valid: " + e.getMessage() + ".",
                        Json.obj("field", "cursor"));
            case KgException.CURSOR_EXPIRED:
                return new ApiException(410, KgException.CURSOR_EXPIRED, "The cursor has expired: " + e.getMessage() + ".",
                        Json.obj("full_sync", fullSync));
            case KgException.EPOCH_CHANGED:
                return new ApiException(410, KgException.EPOCH_CHANGED, "The knowledge graph was reset; start with a full export.",
                        Json.obj("full_sync", fullSync));
            case KgException.DISABLED:
                return new ApiException(409, KgException.DISABLED,
                        "The knowledge graph is disabled. Set " + KgConfig.ENABLED + "=true and restart Scoutro.");
            case KgException.UNAVAILABLE:
                return new ApiException(503, KgException.UNAVAILABLE, "The knowledge graph is not running.",
                        Json.obj("reason", e.reason()));
            case KgException.NOTHING_TO_CONFIRM:
                return new ApiException(
                        409,
                        KgException.NOTHING_TO_CONFIRM,
                        "No reconcile is waiting for confirmation; see sync.reconcile in GET"
                            + " /scoutro/api/v1/kg/status.");
            case KgException.SYNC_UNAVAILABLE:
                return new ApiException(503, KgException.SYNC_UNAVAILABLE,
                        "The knowledge graph does not follow the embedded Solr index here.");
            case KgException.LLM_UNAVAILABLE:
                return new ApiException(409, KgException.LLM_UNAVAILABLE,
                        "The LLM tier is off: set " + KgConfig.LLM_COLLECTIONS + " and select a model for the usage knowledge.");
            case KgException.DERIVED_UNAVAILABLE:
                return new ApiException(409, KgException.DERIVED_UNAVAILABLE,
                        "The derived layer is off: set " + KgConfig.DERIVED_ENABLED + "=true; it needs the embedded Solr synchronisation.");
            case KgException.WRITE_REFUSED:
                return new ApiException(
                        503,
                        "kg_write_refused",
                        "The knowledge graph cannot write right now; see GET"
                            + " /scoutro/api/v1/kg/status.",
                        Json.obj("reason", e.reason()));
            default:
                return new ApiException(503, KgException.UNAVAILABLE, "The knowledge graph store failed.",
                        Json.obj("reason", e.code()));
        }
    }
}
