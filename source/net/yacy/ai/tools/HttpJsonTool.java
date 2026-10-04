/**
 *  HttpJsonTool
 *  Copyright 2026 by Michael Peter Christen
 *  First released 06.02.2026 at https://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.ai.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import net.yacy.ai.ToolHandler;
import net.yacy.cora.protocol.ClientIdentification;

/**
 * Read JSON from a public http(s) endpoint. In the chat this tool is read-only: only GET is
 * executed, mutating methods (POST, PUT, PATCH, DELETE) are refused, and every target and
 * redirect passes {@link OutboundUrlPolicy} (no local, private, cluster-internal or metadata
 * addresses).
 */
public class HttpJsonTool implements ToolHandler {

    private static final String NAME = "http_json";
    /** headers the model may not set: transport, proxy/forwarding identity and cookies */
    private static final Set<String> BLOCKED_HEADERS = new HashSet<>(Arrays.asList(
            "host", "content-length", "connection", "transfer-encoding", "te", "upgrade", "keep-alive",
            "proxy-authorization", "proxy-connection", "forwarded", "x-forwarded-for", "x-forwarded-host",
            "x-forwarded-proto", "x-real-ip", "cookie"));
    private static final int DEFAULT_TIMEOUT_MS = 15000;
    private static final int MAX_TIMEOUT_MS = 60000;
    private static final int DEFAULT_MAX_BYTES = 1_000_000;
    private static final int MAX_ALLOWED_BYTES = 2_000_000;

    private final PinnedHttpGet http;

    public HttpJsonTool() {
        this(new PinnedHttpGet(OutboundUrlPolicy.DEFAULT, agent()));
    }

    HttpJsonTool(final PinnedHttpGet http) {
        this.http = http;
    }

    static String agent() {
        return ClientIdentification.yacyInternetCrawlerAgent == null ? null : ClientIdentification.yacyInternetCrawlerAgent.userAgent();
    }

    @Override
    public JSONObject definition() throws JSONException {
        JSONObject tool = new JSONObject(true);
        tool.put("type", "function");
        JSONObject fn = new JSONObject(true);
        fn.put("name", NAME);
        fn.put("description", "Read a public HTTP JSON endpoint with GET and return the parsed JSON response.");

        JSONObject params = new JSONObject(true);
        params.put("type", "object");
        JSONObject props = new JSONObject(true);

        JSONObject url = new JSONObject(true);
        url.put("type", "string");
        url.put("description", "Absolute public http or https URL.");
        props.put("url", url);

        JSONObject method = new JSONObject(true);
        method.put("type", "string");
        method.put("description", "HTTP method. Only GET is allowed.");
        props.put("method", method);

        JSONObject headers = new JSONObject(true);
        headers.put("type", "object");
        headers.put("description", "Optional request headers (string values).");
        props.put("headers", headers);

        JSONObject timeout = new JSONObject(true);
        timeout.put("type", "integer");
        timeout.put("description", "Timeout in milliseconds (max 60000).");
        props.put("timeout_ms", timeout);

        JSONObject maxBytes = new JSONObject(true);
        maxBytes.put("type", "integer");
        maxBytes.put("description", "Maximum response size in bytes (max 2000000).");
        props.put("max_bytes", maxBytes);

        params.put("properties", props);
        params.put("required", new JSONArray().put("url"));
        fn.put("parameters", params);
        tool.put("function", fn);
        return tool;
    }

    
    public int maxCallsPerTurn() {
        return 1;
    }

    
    public String execute(String arguments) {
        final JSONObject args;
        try {
            args = (arguments == null || arguments.isEmpty()) ? new JSONObject(true) : new JSONObject(arguments);
        } catch (JSONException e) {
            return ToolHandler.errorJson("Invalid arguments JSON. don't try again");
        }

        final String urlRaw = args.optString("url", "").trim();
        if (urlRaw.isEmpty()) return ToolHandler.errorJson("Missing url. don't try again");

        final String method = args.optString("method", "GET").trim().toUpperCase(Locale.ROOT);
        if (!"GET".equals(method)) {
            return ToolHandler.errorJson("Method " + method + " is not allowed: http_json is read-only (GET). don't try again");
        }
        if (args.has("body")) return ToolHandler.errorJson("A request body is not allowed: http_json is read-only (GET). don't try again");
        final int timeoutMs = clampPositive(args.optInt("timeout_ms", DEFAULT_TIMEOUT_MS), DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);
        final int maxBytes = clampPositive(args.optInt("max_bytes", DEFAULT_MAX_BYTES), DEFAULT_MAX_BYTES, MAX_ALLOWED_BYTES);

        final Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        final JSONObject requested = args.optJSONObject("headers");
        if (requested != null) {
            for (String key : requested.keySet()) {
                if (key == null) continue;
                final String normalized = key.trim();
                if (normalized.isEmpty() || BLOCKED_HEADERS.contains(normalized.toLowerCase(Locale.ROOT))) continue;
                headers.put(normalized, requested.optString(key, ""));
            }
        }

        try {
            final PinnedHttpGet.Result response = this.http.get(urlRaw, headers, timeoutMs, maxBytes);
            final String text = new String(response.body, StandardCharsets.UTF_8);
            if (text.trim().isEmpty()) {
                return ToolHandler.errorJson("Empty response body. don't try again");
            }

            final Object parsed;
            try {
                parsed = new JSONTokener(text).nextValue();
            } catch (JSONException e) {
                return ToolHandler.errorJson("Response is not valid JSON. don't try again");
            }
            if (!(parsed instanceof JSONObject) && !(parsed instanceof JSONArray)) {
                return ToolHandler.errorJson("Response is not JSON object/array. don't try again");
            }

            JSONObject result = new JSONObject(true);
            result.put("url", response.url);
            result.put("method", method);
            result.put("status", response.status);
            result.put("content_type", response.contentType);
            result.put("json", parsed);
            return result.toString();
        } catch (OutboundUrlPolicy.Blocked e) {
            return ToolHandler.errorJson("URL not allowed (" + e.reason + "): only public http(s) addresses can be fetched. don't try again");
        } catch (IOException e) {
            return ToolHandler.errorJson("Fetch error: " + e.getMessage() + ". don't try again");
        } catch (JSONException e) {
            return ToolHandler.errorJson("Failed to build tool response. don't try again");
        }
    }

    private static int clampPositive(int value, int defaultValue, int maxValue) {
        if (value <= 0) return defaultValue;
        if (value > maxValue) return maxValue;
        return value;
    }
}
