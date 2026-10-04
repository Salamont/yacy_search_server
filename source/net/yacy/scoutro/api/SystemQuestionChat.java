/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.json.JSONArray;
import org.json.JSONObject;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.ScoutroAgents;

/** System questions are answered before RAG/model selection, through the same protected actions. */
public final class SystemQuestionChat {
    private SystemQuestionChat() { }
    public static boolean handle(final HttpServletRequest request, final HttpServletResponse response,
            final JSONObject body) throws IOException, ServletException {
        final String text = latestUser(body.optJSONArray("messages"));
        if (SystemQuestions.detect(text) == null) return false;
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        final Map<String, String> query = new LinkedHashMap<>();
        query.put("q", text);
        if (body.has("collection")) query.put("collection", body.optString("collection"));
        JSONObject result;
        int status = 200;
        try {
            final String auth = request.getHeader("Authorization");
            if (auth != null && auth.toLowerCase(Locale.ROOT).startsWith("bearer ")) {
                final ScoutroAgents agents = ScoutroAgents.get();
                final AgentApi.Response r = new AgentApi(agents, new ScopedActions(new ScoutroActions(), agents.store)).handle(
                        new AgentApi.Request("GET", Arrays.asList("system", "questions"), query, auth,
                                RequestHeader.client(request), null, () -> new JSONObject()));
                status = r.status;
                result = r.body;
                for (final Map.Entry<String, String> header : r.headers.entrySet()) response.setHeader(header.getKey(), header.getValue());
            } else {
                // No localhost bypass and no implied admin role. Use the container's existing Digest boundary.
                if (!request.isUserInRole(net.yacy.search.SwitchboardConstants.ADMIN_ACCOUNT_ROLE) && !(request.authenticate(response) && request.isUserInRole(net.yacy.search.SwitchboardConstants.ADMIN_ACCOUNT_ROLE))) {
                    if (!response.isCommitted()) write(response, 401, new ApiException(401, "system_auth_required",
                            "System data requires administrator authentication or an authorized agent token; no web answer is substituted.").toJson());
                    return true;
                }
                result = SystemQuestions.admin(SystemQuestions.require(text), query, new ScoutroActions());
            }
        } catch (final ApiException e) { status = e.status(); result = e.toJson(); }
        catch (final AgentException e) { final ApiException a = AgentApi.toApi(e); status = a.status(); result = a.toJson(); }
        catch (final RuntimeException e) { status = 503; result = new ApiException(503, "system_data_unavailable",
                "System data is unavailable; no estimate or web answer is substituted.").toJson(); }
        if (status != 200) { write(response, status, result); return true; }
        final String id = "chatcmpl-scoutro-" + UUID.randomUUID();
        final long created = System.currentTimeMillis() / 1000;
        if (body.optBoolean("stream", false)) {
            response.setContentType("text/event-stream;charset=UTF-8");
            final JSONObject first = chunk(id, created, Json.obj("role", "assistant", "content", result.optString("answer")), JSONObject.NULL);
            Json.put(first, "scoutro", result);
            final String stream = "data: " + first + "\n\ndata: " + chunk(id, created, new JSONObject(), "stop") + "\n\ndata: [DONE]\n\n";
            response.getOutputStream().write(stream.getBytes(StandardCharsets.UTF_8));
        } else write(response, 200, Json.obj("id", id, "object", "chat.completion", "created", created,
                "model", "scoutro-system-actions", "choices", new JSONArray().put(Json.obj("index", 0,
                "message", Json.obj("role", "assistant", "content", result.optString("answer")), "finish_reason", "stop")), "scoutro", result));
        return true;
    }
    static String latestUser(final JSONArray messages) {
        if (messages == null) return "";
        for (int i = messages.length() - 1; i >= 0; i--) {
            final JSONObject m = messages.optJSONObject(i);
            if (m == null || !"user".equals(m.optString("role"))) continue;
            final Object content = m.opt("content");
            if (content instanceof String) return (String) content;
            if (content instanceof JSONArray) {
                final StringBuilder text = new StringBuilder();
                final JSONArray parts = (JSONArray) content;
                for (int j = 0; j < parts.length(); j++) {
                    final JSONObject part = parts.optJSONObject(j);
                    if (part != null && "text".equals(part.optString("type"))) text.append(part.optString("text", "")).append(' ');
                }
                return text.toString().trim();
            }
            return "";
        }
        return "";
    }
    private static JSONObject chunk(final String id, final long created, final JSONObject delta, final Object finish) {
        return Json.obj("id", id, "object", "chat.completion.chunk", "created", created, "model", "scoutro-system-actions",
                "choices", new JSONArray().put(Json.obj("index", 0, "delta", delta, "finish_reason", finish)));
    }
    private static void write(final HttpServletResponse response, final int status, final JSONObject result) throws IOException {
        response.setStatus(status); response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(result.toString().getBytes(StandardCharsets.UTF_8));
    }
}
