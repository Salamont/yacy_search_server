/*
 *  ScoutroApiServlet
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.access.Caller;
import net.yacy.scoutro.access.Permission;
import net.yacy.scoutro.access.SignInClient;
import net.yacy.scoutro.agents.ScoutroAgents;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * Scoutro API v1, mapped to /scoutro/api/* in defaults/web.xml.
 * <p>
 * Authentication is done by the servlet container with YaCy's administrator
 * account (security constraints in defaults/web.xml, HTTP Digest). This
 * servlet additionally checks the administrator role for every protected
 * route, rejects cross-site requests to mutating routes (JSON content type
 * and same-origin check) and answers only in JSON.
 * <p>
 * The agent path {@code /scoutro/api/agent/v1/*} is outside the container
 * constraint; {@link AgentApi} authenticates it with agent tokens (Bearer)
 * and authorizes every call against the agent's grant. It never accepts or
 * grants the administrator account.
 */
public class ScoutroApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");
    private static final int MAX_BODY_BYTES = 16 * 1024;
    /** Body limit of POST /v1/kg/prompt (a prompt text of KnowledgePrompt.MAX_CHARS characters). */
    private static final int PROMPT_BODY_BYTES = 64 * 1024;
    /** A new collection is an ID, a display name and a short description. */
    private static final int MAX_COLLECTION_BODY_BYTES = 4 * 1024;
    private static final String AGENT_PREFIX = "/agent/v1/";

    private final transient ScoutroActions actions = new ScoutroActions();

    @Override
    protected void service(final HttpServletRequest request, final HttpServletResponse response) throws IOException {
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        final String method = request.getMethod().toUpperCase(Locale.ROOT);
        final String path = request.getPathInfo() == null ? "/" : request.getPathInfo();
        try {
            if ("GET".equals(method) && ("/openapi.json".equals(path) || "/actions.json".equals(path))) {
                sendStatic(response, path.substring(1));
                return;
            }
            if (path.startsWith(AGENT_PREFIX) || path.equals("/agent") || path.equals("/agent/v1")) {
                serviceAgent(method, path, request, response);
                return;
            }
            final JSONObject result = route(method, path, request, response);
            final int status = response.getStatus() == 0 ? 200 : response.getStatus();
            auditChange(method, path, request, status, null);
            if (result != null) send(response, status, result); // null: streamed
        } catch (final ApiException e) {
            auditChange(method, path, request, e.status(), e.code());
            if ("too_many_attempts".equals(e.code())) {
                response.setHeader("Retry-After", retryAfter(e));
            }
            send(response, e.status(), e.toJson());
        } catch (final RuntimeException e) {
            LOG.warn("internal error for " + method + " " + path + ": " + e);
            auditChange(method, path, request, 500, "internal_error");
            send(response, 500, new ApiException(500, "internal_error", "Internal error in the Scoutro API.").toJson());
        }
    }

    /**
     * Agent path: authenticated by this servlet with an agent token (no
     * container constraint applies to it, see defaults/web.xml and
     * AdminSecurity). Never grants the administrator role.
     */
    private void serviceAgent(final String method, final String path, final HttpServletRequest request,
            final HttpServletResponse response) throws IOException {
        final ScoutroAgents agents;
        try {
            agents = ScoutroAgents.get();
        } catch (final AgentException e) {
            final ApiException a = AgentApi.toApi(e);
            send(response, a.status(), a.toJson());
            return;
        }
        final String rest = path.startsWith(AGENT_PREFIX) ? path.substring(AGENT_PREFIX.length()) : "";
        final List<String> segments = new ArrayList<>();
        for (final String seg : rest.replaceAll("/+$", "").split("/")) {
            if (!seg.isEmpty()) {
                segments.add(seg);
            }
        }
        final AgentApi.Request r = new AgentApi.Request(method, segments, queryParams(request),
                request.getHeader("Authorization"), RequestHeader.client(request), request.getHeader("Idempotency-Key"),
                () -> {
                    try {
                        return jsonBody(request);
                    } catch (final IOException e) {
                        throw new ApiException(400, "invalid_body", "The request body could not be read.");
                    }
                });
        final AgentApi.Response resp = new AgentApi(agents, new ScopedActions(this.actions, agents.store)).handle(r);
        for (final Map.Entry<String, String> h : resp.headers.entrySet()) {
            response.setHeader(h.getKey(), h.getValue());
        }
        send(response, resp.status, resp.body);
    }

    private JSONObject route(final String method, final String path, final HttpServletRequest request,
            final HttpServletResponse response) throws ApiException, IOException {
        final String[] parts = path.replaceAll("/+$", "").split("/");
        // parts[0] is empty because the path starts with '/'
        if (parts.length < 3 || !"v1".equals(parts[1])) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        final String resource = parts[2];
        // permission and collection scope of each route for people (docs/SCOUTRO_USERS_ACCESS.md, section 3);
        // the administrator keeps every route as before, deny by default for everything not listed here
        final PeopleRoutes people = new PeopleRoutes(Caller.of(request));
        switch (resource) {
            case "auth":
                // sign-in and own account; login, session and logout are reachable without a session
                return access(request, response).auth(method, parts, () -> jsonBody(request));
            case "users":
                return access(request, response).users(method, parts, () -> jsonBody(request));
            case "access":
                return access(request, response).access(method, parts, () -> jsonBody(request));
            case "health":
                expect(method, parts, 3, "GET");
                return this.actions.health();
            case "ui":
                if (parts.length >= 4 && "routes".equals(parts[3])) {
                    if (parts.length == 4) {
                        expect(method, parts, 4, "GET");
                        return UiRoutes.all();
                    }
                    expect(method, parts, 5, "GET");
                    return UiRoutes.get(parts[4]);
                }
                break;
            case "system":
                people.require(Permission.ADMIN);
                if (parts.length == 4 && "questions".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> q = queryParams(request);
                    return SystemQuestions.admin(SystemQuestions.require(q.get("q")), q, this.actions);
                }
                expect(method, parts, 3, "GET");
                return this.actions.system();
            case "discovery":
                // jobs of the identity's collections (by profile); global switches and the export stay administrative
                people.require(Permission.COLLECT);
                return DiscoveryApi.route(method, parts, request, response,
                        "GET".equals(method) ? null : new net.yacy.scoutro.discovery.JsonObject(jsonBody(request)),
                        people.admin() ? null : people.allows());
            case "seo":
                people.require(Permission.READ);
                if (!"GET".equals(method)) throw new ApiException(405, "method_not_allowed", "Host analysis is read-only; use GET.");
                final Map<String, String> seoQuery = queryParams(request);
                return SeoAnalysis.current().route(java.util.Arrays.asList(parts).subList(3, parts.length),
                        seoQuery, people.view(seoQuery));
            case "reports":
                people.require(Permission.COLLECT);
                if (!"GET".equals(method)) throw new ApiException(405, "method_not_allowed", "Crawl reports are read-only; use GET.");
                return ReportApi.current().route(java.util.Arrays.asList(parts).subList(3, parts.length), queryParams(request),
                        people.reportScope());
            case "hosts":
                people.require(Permission.READ);
                if (parts.length == 4 && "resolve".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    return people.hostResolve(this.actions, queryParams(request));
                }
                break;
            case "collections":
                if (parts.length == 3 && "GET".equals(method)) {
                    // the collections a search, chat or crawl may use: guests and every account see their own
                    people.require(Permission.SEARCH);
                    return people.collections(this.actions);
                }
                people.require(Permission.ADMIN);
                if (parts.length == 4 && "DELETE".equals(method)) {
                    // an empty, unused collection created here; same cross-site protection as all mutating calls, body {}
                    final JSONObject body = jsonBody(request, MAX_COLLECTION_BODY_BYTES);
                    if (body.length() > 0) throw ApiException.invalid(body.keys().next(), "Unknown field.");
                    return this.actions.collectionRemove(parts[3]);
                }
                if (parts.length == 5 && !"reassign".equals(parts[3]) && !"decisions".equals(parts[3]) && !"autocorrect".equals(parts[3])
                        && ("hide".equals(parts[4]) || "show".equals(parts[4]) || "delete-pages".equals(parts[4]))) {
                    // 0.10.3: hide (pages stay), show again, delete the pages (confirmed with the id); each audited by its own path
                    expect(method, parts, 5, "POST");
                    final JSONObject body = jsonBody(request, MAX_COLLECTION_BODY_BYTES);
                    if ("delete-pages".equals(parts[4])) return this.actions.collectionDeletePages(parts[3], body);
                    if (body.length() > 0) throw ApiException.invalid(body.keys().next(), "Unknown field.");
                    return "hide".equals(parts[4]) ? this.actions.collectionHide(parts[3]) : this.actions.collectionShow(parts[3]);
                }
                if (parts.length >= 4 && "decisions".equals(parts[3])) {
                    // durable collection decisions (docs/SCOUTRO_COLLECTION_DECISIONS.md); administrator only, never an agent route
                    final String actor = net.yacy.scoutro.access.Caller.of(request).actor();
                    if (parts.length == 4 && "GET".equals(method)) return this.actions.collectionDecisions("list", queryParams(request), null, actor);
                    if (parts.length == 4) {
                        expect(method, parts, 4, "GET", "POST");
                        return this.actions.collectionDecisions("decide", null, jsonBody(request, MAX_COLLECTION_BODY_BYTES), actor);
                    }
                    if (parts.length == 5 && ("apply".equals(parts[4]) || "revoke".equals(parts[4]) || "recover".equals(parts[4]))) {
                        expect(method, parts, 5, "POST");
                        return this.actions.collectionDecisions(parts[4], null, jsonBody(request, MAX_COLLECTION_BODY_BYTES), actor);
                    }
                    throw new ApiException(404, "not_found", "Unknown collection decision route.");
                }
                if (parts.length >= 4 && "autocorrect".equals(parts[3])) {
                    // the correction automation (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md); administrator only, never an agent route
                    final String actor = net.yacy.scoutro.access.Caller.of(request).actor();
                    if (parts.length == 4) {
                        expect(method, parts, 4, "GET");
                        return this.actions.collectionAutocorrect("report", queryParams(request), null, actor);
                    }
                    if (parts.length == 5 && java.util.Set.of("evaluate", "review", "accept", "breaker-reset").contains(parts[4])) {
                        expect(method, parts, 5, "POST");
                        return this.actions.collectionAutocorrect(parts[4], null, jsonBody(request, MAX_BODY_BYTES), actor);
                    }
                    throw new ApiException(404, "not_found", "Unknown correction automation route.");
                }
                if (parts.length == 4 && "reassign".equals(parts[3])) {
                    // preview without "confirm", apply with the preview's token; one domain, same origin, small body
                    expect(method, parts, 4, "POST");
                    return this.actions.collectionReassign(jsonBody(request, MAX_COLLECTION_BODY_BYTES));
                }
                if (parts.length == 3 && "POST".equals(method)) {
                    // small JSON body, same origin (jsonBody); validated and stored by the catalog
                    final JSONObject created = this.actions.collectionCreate(jsonBody(request, MAX_COLLECTION_BODY_BYTES));
                    response.setStatus(201);
                    return created;
                }
                expect(method, parts, 3, "GET", "POST");
                return this.actions.collections();
            case "search":
                people.require(Permission.SEARCH);
                expect(method, parts, 3, "GET");
                return people.search(this.actions, queryParams(request));
            case "index":
                people.require(Permission.READ);
                if (parts.length == 4 && "browse".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> browseQuery = queryParams(request);
                    return IndexBrowse.current().browse(browseQuery, people.view(browseQuery));
                }
                if (parts.length == 4 && "domains".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> domainQuery = queryParams(request);
                    return DomainCandidates.current().page(domainQuery, people.view(domainQuery));
                }
                if (parts.length == 5 && "domains".equals(parts[3]) && "export".equals(parts[4])) {
                    people.require(Permission.EXPORT);
                    expect(method, parts, 5, "GET");
                    final Map<String, String> exportQuery = queryParams(request);
                    exportDomains(exportQuery, people.view(exportQuery), response);
                    return null;
                }
                if (parts.length == 4 && "metrics".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> q = queryParams(request);
                    return IndexMetrics.current().read(q, people.view(q));
                }
                if (parts.length == 3) {
                    expect(method, parts, 3, "GET");
                    return people.index(this.actions);
                }
                if (parts.length == 4 && "lookup".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> q = queryParams(request);
                    return people.allCollections() ? this.actions.indexLookup(q) : this.actions.indexLookup(q, people.view(q));
                }
                if (parts.length == 4 && "evidence".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    final Map<String, String> q = queryParams(request);
                    return people.allCollections() ? this.actions.indexEvidence(q) : this.actions.indexEvidence(q, people.view(q));
                }
                break;
            case "config":
                people.require(Permission.ADMIN);
                if (parts.length == 3) {
                    if ("GET".equals(method)) {
                        return this.actions.configGet();
                    }
                    expect(method, parts, 3, "GET", "PATCH");
                    return this.actions.configSet(jsonBody(request));
                }
                break;
            case "kg":
                if (!people.admin()) {
                    return knowledgeForPeople(people, method, parts, request, response);
                }
                if (parts.length == 5 && "export".equals(parts[3]) && "download".equals(parts[4])) {
                    expect(method, parts, 5, "GET");
                    new KnowledgeApi(KgRuntime::current).download(queryParams(request), (contentType, filename) -> {
                        response.setStatus(200);
                        response.setContentType(contentType);
                        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                        return response.getWriter();
                    });
                    return null;
                }
                if (parts.length == 5 && "backups".equals(parts[3])) {
                    expect(method, parts, 5, "GET");
                    sendBackup(new KnowledgeApi(KgRuntime::current).backupFile(parts[4]), response);
                    return null;
                }
                // a knowledge prompt of up to 8000 characters needs more room than the other bodies (UTF-8, JSON escapes)
                final int maxBody = parts.length == 4 && "prompt".equals(parts[3]) ? PROMPT_BODY_BYTES : MAX_BODY_BYTES;
                return new KnowledgeApi(KgRuntime::current).actor(net.yacy.scoutro.access.Caller.of(request).actor())
                        .route(method, parts, queryParams(request), () -> jsonBody(request, maxBody));
            case "crawls":
                people.require(Permission.COLLECT);
                if (parts.length == 3) {
                    if ("GET".equals(method)) {
                        return people.crawlList(this.actions);
                    }
                    expect(method, parts, 3, "GET", "POST");
                    final JSONObject crawl = people.crawlStart(this.actions, jsonBody(request), request.getHeader("Idempotency-Key"));
                    response.setStatus(crawl.optBoolean("idempotentReplay") ? 200 : 201);
                    response.setHeader("Location", "/scoutro/api/v1/crawls/" + crawl.optString("id"));
                    return crawl;
                }
                if (parts.length == 4) {
                    expect(method, parts, 4, "GET");
                    return people.crawlGet(this.actions, parts[3]);
                }
                if (parts.length == 5 && "stop".equals(parts[4])) {
                    expect(method, parts, 5, "POST");
                    ScoutroActions.checkCrawlId(parts[3]);
                    jsonBody(request); // same cross-site protection as all mutating calls; body may be {}
                    return people.crawlStop(this.actions, parts[3]);
                }
                break;
            default:
                break;
        }
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }

    /**
     * The knowledge graph for an account without the administrator role: the reads within its collections
     * (read right), exports and the change feed (also the export right); status, controls, settings,
     * backups and the LLM tier stay administrative.
     */
    private static JSONObject knowledgeForPeople(final PeopleRoutes people, final String method, final String[] parts,
            final HttpServletRequest request, final HttpServletResponse response) throws ApiException, IOException {
        people.require(Permission.READ);
        if (parts.length == 5 && "export".equals(parts[3]) && "download".equals(parts[4])) {
            people.require(Permission.EXPORT);
            expect(method, parts, 5, "GET");
            final Map<String, String> q = queryParams(request);
            new KnowledgeApi(KgRuntime::current).download(q, people.view(q), true, (contentType, filename) -> {
                response.setStatus(200);
                response.setContentType(contentType);
                response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
                return response.getWriter();
            });
            return null;
        }
        if (parts.length >= 5 && KnowledgeRead.handles(parts[3]) || parts.length == 4 && KnowledgeRead.single(parts[3])) {
            if ("export".equals(parts[3]) || "changes".equals(parts[3])) {
                people.require(Permission.EXPORT);
            }
            final Map<String, String> q = queryParams(request);
            return new KnowledgeRead(KgRuntime::current, KnowledgeRead.ADMIN_BASE, true).route(method,
                    java.util.Arrays.asList(parts).subList(3, parts.length), q, people.view(q), people.permitted());
        }
        people.require(Permission.ADMIN); // 403 for every other knowledge route
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }

    /**
     * Read-only domain export as a download, streamed page by page. Invalid filters and an
     * unavailable index answer with the usual JSON error before anything is written.
     */
    private static void exportDomains(final Map<String, String> query, final List<String> collections,
            final HttpServletResponse response) throws ApiException, IOException {
        final String format = DomainExport.format(query.get("format"));
        final DomainCandidates candidates = DomainCandidates.current();
        final long now = System.currentTimeMillis();
        final DomainCandidates.Filter filter = DomainCandidates.filter(query, collections, java.util.Set.of("q", "collection", "format"));
        candidates.export(query, collections, new DomainCandidates.Sink() {
            private DomainCandidates.Sink sink;
            @Override public void begin(final DomainCandidates.Filter f, final long generatedAt) throws IOException {
                response.setStatus(200);
                response.setContentType(DomainExport.contentType(format));
                response.setHeader("Content-Disposition", "attachment; filename=\"" + DomainExport.filename(filter, format, now) + "\"");
                this.sink = DomainExport.sink(format, response.getWriter());
                this.sink.begin(f, generatedAt);
            }
            @Override public void item(final DomainCandidate c) throws IOException { this.sink.item(c); }
            @Override public void flush() throws IOException { this.sink.flush(); }
            @Override public void end(final long count, final boolean complete) throws IOException { this.sink.end(count, complete); }
        });
    }

    /** A knowledge graph backup as a download: one SQLite file, streamed. */
    private static void sendBackup(final File file, final HttpServletResponse response) throws IOException {
        response.setStatus(200);
        response.setContentType("application/vnd.sqlite3");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
        response.setContentLengthLong(file.length());
        try (OutputStream out = response.getOutputStream()) {
            Files.copy(file.toPath(), out);
        }
    }

    /**
     * Loaded at startup (web.xml) so that crawl report capture also runs without API traffic.
     * The knowledge graph starts here too; it does nothing while disabled and never fails the start.
     */
    @Override
    public void init() throws javax.servlet.ServletException {
        super.init();
        CaptureRuntime.start();
        try {
            KgRuntime.start();
        } catch (final Throwable t) {
            // the knowledge graph must never keep the Scoutro API from starting
            LOG.warn("knowledge graph not started: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void destroy() {
        try {
            KgRuntime.stop();
        } catch (final Throwable t) {
            LOG.warn("knowledge graph stop failed: " + t.getClass().getSimpleName());
        }
        CaptureRuntime.stop();
        net.yacy.scoutro.discovery.DiscoveryService.closeCurrent();
        super.destroy();
    }

    /** 405 unless the method is allowed; also rejects extra path segments. */
    private static void expect(final String method, final String[] parts, final int length, final String... allowed)
            throws ApiException {
        if (parts.length != length) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        for (final String a : allowed) {
            if (a.equals(method)) {
                return;
            }
        }
        throw new ApiException(405, "method_not_allowed", "Method " + method + " is not allowed here. Allowed: "
                + String.join(", ", allowed) + ".", Json.obj("allowed", String.join(", ", allowed)));
    }

    private static String retryAfter(final ApiException e) {
        final JSONObject details = e.toJson().optJSONObject("error").optJSONObject("details");
        final String seconds = details == null ? "" : details.optString("retryAfter", "");
        return seconds.matches("[0-9]{1,6}") ? seconds : "60";
    }

    private static AccessApi access(final HttpServletRequest request, final HttpServletResponse response) {
        final Switchboard sb = Switchboard.getSwitchboard();
        return new AccessApi(request, response, (key, value) -> {
            if (sb != null) {
                sb.setConfig(key, value);
            }
        }, () -> sb != null && sb.isRobinsonMode() && !sb.isPublicRobinson(),
                () -> sb != null && sb.getConfigBool(SwitchboardConstants.ADMIN_ACCOUNT_FOR_LOCALHOST, false));
    }

    /**
     * Audit of people's changing calls (docs/SCOUTRO_USERS_ACCESS.md, section 8): who, which
     * route, which target id, result. Sign-in and user management log their own, more
     * specific entries. Never request bodies or query texts.
     */
    private static void auditChange(final String method, final String path, final HttpServletRequest request, final int status,
            final String code) {
        if (!net.yacy.scoutro.access.WebAuth.unsafeMethod(method)) {
            return;
        }
        final String[] parts = path.replaceAll("/+$", "").split("/");
        if (parts.length < 3 || !"v1".equals(parts[1]) || "auth".equals(parts[2]) || "users".equals(parts[2])
                || "access".equals(parts[2])) {
            return;
        }
        final net.yacy.scoutro.access.ScoutroAccess access = net.yacy.scoutro.access.ScoutroAccess.current();
        if (access == null) {
            return;
        }
        final StringBuilder action = new StringBuilder("api.").append(parts[2]);
        final StringBuilder target = new StringBuilder();
        for (int i = 3; i < parts.length && i < 7; i++) {
            final String seg = parts[i];
            // a collection id is the target, not part of the action (DELETE /v1/collections/{id})
            final boolean collection = i == 3 && "collections".equals(parts[2]) && !"reassign".equals(seg) && !"decisions".equals(seg)
                    && !"autocorrect".equals(seg);
            if (!collection && seg.matches("[a-z][a-z-]{0,31}")) {
                action.append('.').append(seg);
            } else if (seg.matches("[A-Za-z0-9_.:@-]{1,80}")) {
                if (target.length() > 0) target.append('/');
                target.append(seg);
            }
        }
        action.append(' ').append(method);
        final String result = status < 400 ? "ok" : status == 401 || status == 403 ? "denied" : "failed";
        access.audit.record(Caller.of(request, access).actor(), action.toString(), target.toString(), result,
                code == null ? String.valueOf(status) : code, SignInClient.of(request, access.settings));
    }

    /**
     * Body of a mutating call. Cross-site request forgery protection: browsers
     * cannot send an application/json POST/PATCH to another origin without a CORS
     * preflight (which this API never allows), and a present Origin header must
     * match the request host.
     */
    private static JSONObject jsonBody(final HttpServletRequest request) throws ApiException, IOException {
        return jsonBody(request, MAX_BODY_BYTES);
    }

    private static JSONObject jsonBody(final HttpServletRequest request, final int maxBytes) throws ApiException, IOException {
        final String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw new ApiException(415, "unsupported_media_type", "Send the request body as application/json.");
        }
        final String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty() && !"null".equals(origin) && !sameOrigin(origin, request)) {
            throw new ApiException(403, "cross_origin_forbidden", "Cross-origin requests are not allowed.");
        }
        final byte[] body = readLimited(request.getInputStream(), maxBytes);
        final String text = new String(body, StandardCharsets.UTF_8).trim();
        return text.isEmpty() ? new JSONObject() : Json.parseObject(text);
    }

    private static boolean sameOrigin(final String origin, final HttpServletRequest request) {
        try {
            final URI o = new URI(origin);
            final String host = request.getHeader("Host");
            return host != null && o.getAuthority() != null && o.getAuthority().equalsIgnoreCase(host);
        } catch (final Exception e) {
            return false;
        }
    }

    private static byte[] readLimited(final InputStream in, final int maxBytes) throws ApiException, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
            if (out.size() > maxBytes) {
                throw new ApiException(413, "payload_too_large", "The request body must not exceed " + maxBytes + " bytes.");
            }
        }
        return out.toByteArray();
    }

    private static Map<String, String> queryParams(final HttpServletRequest request) {
        final Map<String, String> params = new HashMap<>();
        for (final Map.Entry<String, String[]> e : request.getParameterMap().entrySet()) {
            if (e.getValue() != null && e.getValue().length > 0) {
                params.put(e.getKey(), e.getValue()[0]);
            }
        }
        return params;
    }

    /** openapi.json and actions.json are maintained in htroot/env/scoutro/api/. */
    private static void sendStatic(final HttpServletResponse response, final String name) throws IOException {
        final Switchboard sb = Switchboard.getSwitchboard();
        final File file = sb == null ? null : new File(sb.getAppPath(), "htroot/env/scoutro/api/" + name);
        if (file == null || !file.isFile()) {
            send(response, 404, new ApiException(404, "not_found", name + " is not available.").toJson());
            return;
        }
        final byte[] bytes = Files.readAllBytes(file.toPath());
        response.setStatus(200);
        response.setContentType("application/json;charset=utf-8");
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }

    private static void send(final HttpServletResponse response, final int status, final JSONObject body)
            throws IOException {
        final byte[] bytes = (body.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");
        response.setContentLength(bytes.length);
        try (OutputStream out = response.getOutputStream()) {
            out.write(bytes);
        }
    }
}
