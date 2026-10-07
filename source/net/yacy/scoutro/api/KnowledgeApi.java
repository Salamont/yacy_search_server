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
 * ({@link KgCollectionSettings}); agents never get these.
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

    /** Allowed values of {@code action}. */
    static final java.util.List<String> ACTIONS = java.util.List.of("pause", "resume", "reconcile", "confirm_reconcile",
            "llm_retry", "backup", "restore", "rebuild", "rebuild_cancel", "rebuild_confirm", "derive");

    private final Supplier<KgRuntime> runtime;
    private final Supplier<KgCollectionSettings> settings;

    KnowledgeApi(final Supplier<KgRuntime> runtime) {
        this(runtime, KgCollectionSettings::current);
    }

    KnowledgeApi(final Supplier<KgRuntime> runtime, final Supplier<KgCollectionSettings> settings) {
        this.runtime = runtime;
        this.settings = settings;
    }

    JSONObject route(final String method, final String[] parts, final Body body) throws ApiException, IOException {
        return route(method, parts, java.util.Collections.emptyMap(), body);
    }

    /** Status, control and (package 3) the read routes, for the administrator; {@code collection} filters the reads. */
    JSONObject route(final String method, final String[] parts, final java.util.Map<String, String> query, final Body body)
            throws ApiException, IOException {
        if (parts.length >= 5 && KnowledgeRead.handles(parts[3])
                || parts.length == 4 && KnowledgeRead.single(parts[3])) {
            return new KnowledgeRead(this.runtime).route(method, java.util.Arrays.asList(parts).subList(3, parts.length), query,
                    SeoAnalysis.adminCollections(query));
        }
        if ((parts.length == 4 || parts.length == 5) && "collections".equals(parts[3])) {
            return collections(method, parts, query, body);
        }
        if (parts.length != 4) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        switch (parts[3]) {
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
            default:
                throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
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
        if (include != null && !include.isEmpty() && !"evidence".equals(include)) {
            throw ApiException.invalid("include", "Field 'include' must be one of: evidence.");
        }
        final java.util.List<String> collections = SeoAnalysis.adminCollections(q);
        final KgRuntime r = this.runtime.get();
        if (r == null) {
            throw toApi(new KgException(KgException.DISABLED, "not started"));
        }
        final String collection = collections == null ? null : collections.get(0);
        final String filename = "scoutro-knowledge-" + (collection == null ? "all" : collection) + "-"
                + java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(java.time.ZoneOffset.UTC)
                        .format(java.time.Instant.now()) + "." + format;
        final boolean json = "json".equals(format);
        try {
            final KgReader reader = r.reader();
            new KgExport(reader).stream(reader.viewer(collections), "evidence".equals(include), collection, DOWNLOAD_PAGE, new KgExport.Sink() {
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
            });
        } catch (final KgException e) {
            throw toApi(e);
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

    private JSONObject control(final JSONObject body) throws ApiException {
        final String action = body.optString("action", "");
        final Iterator<?> keys = body.keys();
        while (keys.hasNext()) {
            final String k = String.valueOf(keys.next());
            if (!"action".equals(k) && !("backup".equals(k) && "restore".equals(action))) {
                throw ApiException.invalid(k, "Unknown field '" + k + "'. Allowed: action" + ("restore".equals(action) ? ", backup." : "."));
            }
        }
        if (!ACTIONS.contains(action)) {
            throw ApiException.invalid("action", "Field 'action' must be one of: " + String.join(", ", ACTIONS) + ".");
        }
        final String backup = body.optString("backup", "");
        if ("restore".equals(action) && !net.yacy.scoutro.knowledge.store.KgBackup.NAME.matcher(backup).matches()) {
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
                return new ApiException(409, KgException.OPERATION_RUNNING, "A backup, restore or rebuild is running; see GET /scoutro/api/v1/kg/status.",
                        Json.obj("reason", e.reason() == null ? "running" : e.reason()));
            case KgException.BACKUP_NOT_FOUND:
                return new ApiException(404, KgException.BACKUP_NOT_FOUND, "No such backup; see GET /scoutro/api/v1/kg/backups.");
            case KgException.BACKUP_INVALID:
                return new ApiException(422, KgException.BACKUP_INVALID, "The backup is not usable: " + e.getMessage() + ". Nothing was changed.",
                        Json.obj("reason", e.reason()));
            case KgException.RESTORE_FAILED:
                return new ApiException(503, KgException.RESTORE_FAILED, "The restore failed: " + e.getMessage() + ".",
                        Json.obj("reason", e.reason()));
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
                return new ApiException(409, KgException.NOTHING_TO_CONFIRM,
                        "No reconcile is waiting for confirmation; see sync.reconcile in GET /scoutro/api/v1/kg/status.");
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
                return new ApiException(503, "kg_write_refused",
                        "The knowledge graph cannot write right now; see GET /scoutro/api/v1/kg/status.",
                        Json.obj("reason", e.reason()));
            default:
                return new ApiException(503, KgException.UNAVAILABLE, "The knowledge graph store failed.",
                        Json.obj("reason", e.code()));
        }
    }
}
