/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.cora.util.LogRedaction;

/**
 * A model endpoint answered with an HTTP status other than 200 (0.10.7). The message keeps its old form, "Request failed
 * with response code N", and adds what the provider said about it, bounded and cleaned: its error code or type, the
 * parameter it rejected, the upstream provider a router names, and at most {@link #MAX_DETAIL_CHARS} characters of its
 * message. Before, the error body was not read and a rejected parameter, an exceeded context window or a missing model
 * looked the same. Never part of it: the request, the prompt, the whole error body, a router's raw upstream answer, an
 * API key or a bearer token.
 */
public final class LlmHttpError extends IOException {

    private static final long serialVersionUID = 1L;

    /** Bytes of an error body read at most; the rest is not read. */
    public static final int MAX_BODY_BYTES = 16 * 1024;
    /** Characters of the provider's message kept. */
    public static final int MAX_DETAIL_CHARS = 300;

    /** The HTTP status. */
    public final int status;
    /** The provider's error code or type ({@code context_length_exceeded}, {@code invalid_request_error}), or null. */
    public final String code;
    /** The request parameter the provider names as rejected ({@code max_tokens}, {@code messages}), or null. */
    public final String param;
    /** The upstream provider a router names in its error ({@code metadata.provider_name}), or null. */
    public final String provider;
    /** The provider's message, cleaned and at most {@link #MAX_DETAIL_CHARS} characters, or null. */
    public final String detail;
    /** Bytes of the error body read (at most {@link #MAX_BODY_BYTES}). */
    public final int bodyBytes;

    private LlmHttpError(final int status, final String code, final String param, final String provider, final String detail,
            final int bodyBytes) {
        super(message(status, code, param, provider, detail));
        this.status = status;
        this.code = code;
        this.param = param;
        this.provider = provider;
        this.detail = detail;
        this.bodyBytes = bodyBytes;
    }

    private static String message(final int status, final String code, final String param, final String provider, final String detail) {
        final String fields = fields(code, param, provider, detail);
        return "Request failed with response code " + status + (fields.isEmpty() ? "" : " (" + fields + ")");
    }

    private static String fields(final String code, final String param, final String provider, final String detail) {
        final StringBuilder b = new StringBuilder();
        append(b, "code", code);
        append(b, "param", param);
        append(b, "provider", provider);
        if (detail != null) {
            append(b, "detail", "\"" + detail.replace("\"", "'") + "\"");
        }
        return b.toString();
    }

    private static void append(final StringBuilder b, final String key, final String value) {
        if (value != null) {
            b.append(b.length() == 0 ? "" : " ").append(key).append('=').append(value);
        }
    }

    /** The diagnostic fields without the status, for a log line ({@code providerCode=… providerParam=…}). */
    public String fields() {
        final StringBuilder b = new StringBuilder("httpStatus=").append(this.status);
        b.append(" providerCode=").append(this.code == null ? "-" : this.code);
        b.append(" providerParam=").append(this.param == null ? "-" : this.param);
        b.append(" provider=").append(this.provider == null ? "-" : this.provider);
        b.append(" providerMessage=\"").append(this.detail == null ? "-" : this.detail.replace("\"", "'")).append('"');
        b.append(" errorBodyBytes=").append(this.bodyBytes);
        return b.toString();
    }

    /** Reads at most {@link #MAX_BODY_BYTES} of the connection's error body; the key is masked wherever it appears. */
    public static LlmHttpError read(final int status, final HttpURLConnection conn, final String apiKey) {
        byte[] body = new byte[0];
        try (InputStream in = conn.getErrorStream()) {
            if (in != null) {
                body = in.readNBytes(MAX_BODY_BYTES);
            }
        } catch (final IOException | RuntimeException e) {
            // no body: the status alone
        }
        return of(status, new String(body, StandardCharsets.UTF_8), body.length, apiKey);
    }

    /**
     * The error of a status and its (already bounded) body. The body is read as the error object of the OpenAI API and of
     * routers that follow it ({@code {"error":{"message","type","param","code","metadata":{"provider_name"}}}}), as Ollama's
     * ({@code {"error":"…"}}) or as plain text.
     */
    public static LlmHttpError of(final int status, final String body, final int bodyBytes, final String apiKey) {
        String code = null;
        String param = null;
        String provider = null;
        String detail = null;
        final String text = body == null ? "" : body.trim();
        if (text.startsWith("{")) {
            try {
                final JSONObject o = new JSONObject(text);
                final Object error = o.opt("error");
                if (error instanceof JSONObject) {
                    final JSONObject e = (JSONObject) error;
                    final Object c = e.opt("code");
                    code = token(c == null || c == JSONObject.NULL ? e.optString("type", null) : String.valueOf(c));
                    final Object p = e.opt("param");
                    param = p == null || p == JSONObject.NULL ? null : token(String.valueOf(p));
                    final JSONObject meta = e.optJSONObject("metadata");
                    provider = meta == null ? null : name(meta.optString("provider_name", null));
                    detail = clean(e.optString("message", null), apiKey);
                } else if (error instanceof String) {
                    detail = clean((String) error, apiKey);
                } else {
                    detail = clean(o.optString("message", o.optString("detail", null)), apiKey);
                }
            } catch (final JSONException | RuntimeException e) {
                detail = clean(text, apiKey);
            }
        } else if (!text.isEmpty()) {
            detail = clean(TAG.matcher(text).replaceAll(" "), apiKey);
        }
        return new LlmHttpError(status, code, param, provider, detail, bodyBytes);
    }

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_.:/-]{1,64}");
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_. :/-]{1,64}");
    private static final Pattern TAG = Pattern.compile("<[^>]{0,200}>");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bbearer\\s+[^\\s\"',;]+");
    private static final Pattern KEY_LIKE = Pattern.compile("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{8,}");
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");

    /** A code, a parameter or a provider name: one short token, else none. */
    private static String token(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return TOKEN.matcher(t).matches() ? t : null;
    }

    /** A provider's name: letters, digits and a few marks, at most 64 characters, else none. */
    private static String name(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return NAME.matcher(t).matches() ? t : null;
    }

    /** The provider's message on one line, without keys, tokens or e-mail addresses, at most {@link #MAX_DETAIL_CHARS}. */
    static String clean(final String s, final String apiKey) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s;
        if (apiKey != null && apiKey.length() >= 4) {
            t = t.replace(apiKey, "[key]");
        }
        t = BEARER.matcher(t).replaceAll("Bearer [redacted]");
        t = KEY_LIKE.matcher(t).replaceAll("[key]");
        t = EMAIL.matcher(t).replaceAll("[e-mail]");
        t = LogRedaction.redact(t);
        t = t.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
        return t.length() > MAX_DETAIL_CHARS ? t.substring(0, MAX_DETAIL_CHARS) + "…" : t;
    }
}
