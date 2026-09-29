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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import net.yacy.cora.util.ConcurrentLog;
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
 */
public class ScoutroApiServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");
    private static final int MAX_BODY_BYTES = 16 * 1024;

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
            final JSONObject result = route(method, path, request, response);
            send(response, response.getStatus() == 0 ? 200 : response.getStatus(), result);
        } catch (final ApiException e) {
            send(response, e.status(), e.toJson());
        } catch (final RuntimeException e) {
            LOG.warn("internal error for " + method + " " + path + ": " + e);
            send(response, 500, new ApiException(500, "internal_error", "Internal error in the Scoutro API.").toJson());
        }
    }

    private JSONObject route(final String method, final String path, final HttpServletRequest request,
            final HttpServletResponse response) throws ApiException, IOException {
        final String[] parts = path.replaceAll("/+$", "").split("/");
        // parts[0] is empty because the path starts with '/'
        if (parts.length < 3 || !"v1".equals(parts[1])) {
            throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
        }
        final String resource = parts[2];
        switch (resource) {
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
                requireAdmin(request);
                expect(method, parts, 3, "GET");
                return this.actions.system();
            case "search":
                requireAdmin(request);
                expect(method, parts, 3, "GET");
                return this.actions.search(queryParams(request));
            case "index":
                requireAdmin(request);
                if (parts.length == 3) {
                    expect(method, parts, 3, "GET");
                    return this.actions.index();
                }
                if (parts.length == 4 && "lookup".equals(parts[3])) {
                    expect(method, parts, 4, "GET");
                    return this.actions.indexLookup(queryParams(request));
                }
                break;
            case "config":
                requireAdmin(request);
                if (parts.length == 3) {
                    if ("GET".equals(method)) {
                        return this.actions.configGet();
                    }
                    expect(method, parts, 3, "GET", "PATCH");
                    return this.actions.configSet(jsonBody(request));
                }
                break;
            case "crawls":
                requireAdmin(request);
                if (parts.length == 3) {
                    if ("GET".equals(method)) {
                        return this.actions.crawlList();
                    }
                    expect(method, parts, 3, "GET", "POST");
                    final JSONObject crawl = this.actions.crawlStart(jsonBody(request));
                    response.setStatus(201);
                    response.setHeader("Location", "/scoutro/api/v1/crawls/" + crawl.optString("id"));
                    return crawl;
                }
                if (parts.length == 4) {
                    expect(method, parts, 4, "GET");
                    return this.actions.crawlGet(parts[3]);
                }
                if (parts.length == 5 && "stop".equals(parts[4])) {
                    expect(method, parts, 5, "POST");
                    ScoutroActions.checkCrawlId(parts[3]);
                    jsonBody(request); // same cross-site protection as all mutating calls; body may be {}
                    return this.actions.crawlStop(parts[3]);
                }
                break;
            default:
                break;
        }
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
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

    /**
     * The container authenticates protected paths (defaults/web.xml). This check
     * keeps the routes protected even if the web.xml constraint is missing.
     */
    private static void requireAdmin(final HttpServletRequest request) throws ApiException {
        if (!request.isUserInRole(SwitchboardConstants.ADMIN_ACCOUNT_ROLE)) {
            throw new ApiException(401, "unauthorized",
                    "This action requires the Scoutro/YaCy administrator account (HTTP Digest authentication).");
        }
    }

    /**
     * Body of a mutating call. Cross-site request forgery protection: browsers
     * cannot send an application/json POST/PATCH to another origin without a CORS
     * preflight (which this API never allows), and a present Origin header must
     * match the request host.
     */
    private static JSONObject jsonBody(final HttpServletRequest request) throws ApiException, IOException {
        final String contentType = request.getContentType() == null ? "" : request.getContentType().toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw new ApiException(415, "unsupported_media_type", "Send the request body as application/json.");
        }
        final String origin = request.getHeader("Origin");
        if (origin != null && !origin.isEmpty() && !"null".equals(origin) && !sameOrigin(origin, request)) {
            throw new ApiException(403, "cross_origin_forbidden", "Cross-origin requests are not allowed.");
        }
        final byte[] body = readLimited(request.getInputStream());
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

    private static byte[] readLimited(final InputStream in) throws ApiException, IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
            if (out.size() > MAX_BODY_BYTES) {
                throw new ApiException(413, "payload_too_large", "The request body must not exceed " + MAX_BODY_BYTES + " bytes.");
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
