/*
 *  YaCyLoopback
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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;

import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;

/**
 * Calls the existing YaCy HTTP APIs of this peer over the loopback interface.
 * <p>
 * Administrator calls use the same mechanism as YaCy's own automation
 * ({@code WorkTables.execAPICalls}) and {@code bin/apicall.sh}: HTTP Basic
 * credentials made of the admin user name and the stored password hash,
 * which YaCy only accepts on connections from localhost. The credential is
 * built in memory for each call and is never logged or returned.
 */
final class YaCyLoopback {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    /** Parameters in insertion order, encoded as application/x-www-form-urlencoded. */
    static final class Params {
        private final Map<String, String> values = new LinkedHashMap<>();

        Params add(final String key, final Object value) {
            if (value != null) {
                this.values.put(key, String.valueOf(value));
            }
            return this;
        }

        String encode() {
            final StringJoiner joiner = new StringJoiner("&");
            for (final Map.Entry<String, String> e : this.values.entrySet()) {
                joiner.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
            return joiner.toString();
        }
    }

    /** GET a YaCy path as the administrator. */
    String getAdmin(final String path, final Params params) throws ApiException {
        return send(path, params, false, true);
    }

    /** POST form parameters to a YaCy path as the administrator. */
    String postAdmin(final String path, final Params params) throws ApiException {
        return send(path, params, true, true);
    }

    /** GET a public YaCy path without credentials. */
    String getPublic(final String path, final Params params) throws ApiException {
        return send(path, params, false, false);
    }

    Document getAdminXml(final String path, final Params params) throws ApiException {
        return parseXml(getAdmin(path, params), path);
    }

    Document getPublicXml(final String path, final Params params) throws ApiException {
        return parseXml(getPublic(path, params), path);
    }

    private String send(final String path, final Params params, final boolean post, final boolean admin)
            throws ApiException {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            throw new ApiException(503, "unavailable", "YaCy is not initialized yet.");
        }
        final String query = params == null ? "" : params.encode();
        final String uri = "http://127.0.0.1:" + sb.getLocalPort() + "/" + path
                + (!post && !query.isEmpty() ? "?" + query : "");
        final HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "Scoutro-API/1");
        if (admin) {
            final String user = sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_USER_NAME, "admin");
            final String hash = sb.getConfig(SwitchboardConstants.ADMIN_ACCOUNT_B64MD5, "");
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + hash).getBytes(StandardCharsets.UTF_8)));
        }
        if (post) {
            request.header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(query, StandardCharsets.UTF_8));
        } else {
            request.GET();
        }
        final HttpResponse<String> response;
        try {
            response = this.client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (final IOException e) {
            throw new ApiException(502, "upstream_unreachable", "YaCy did not answer on the loopback interface.");
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(503, "unavailable", "The request was interrupted.");
        }
        final int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new ApiException(502, "upstream_forbidden", "YaCy refused the internal call to " + path
                    + " (HTTP " + status + ").");
        }
        if (status >= 400) {
            throw new ApiException(502, "upstream_error", "YaCy answered HTTP " + status + " for " + path + ".");
        }
        return response.body();
    }

    private static Document parseXml(final String xml, final String source) throws ApiException {
        try {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (final Exception e) {
            throw new ApiException(502, "upstream_error", "YaCy returned unreadable XML from " + source + ".");
        }
    }
}
