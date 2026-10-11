/*
 *  YacyLlmClient
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

package net.yacy.scoutro.knowledge.extract;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.LLM;
import net.yacy.scoutro.knowledge.KgJson;

/**
 * The LLM tier's model from Scoutro's LLM selection: the production model row
 * with the usage {@code knowledge} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3).
 * Endpoint, key and output limit are that row's; nothing is configured twice.
 * <p>
 * Structured output is negotiated per model (docs/SCOUTRO_KNOWLEDGE_GRAPH.md,
 * 6.3). An {@code OLLAMA} model is called natively on {@code /api/chat}
 * ({@link LLM#chatOllamaNative}) with {@code format}: the schema itself, the
 * string {@code "json"} or nothing; Ollama enforces the schema there, while its
 * {@code /v1/chat/completions} was seen to ignore a {@code response_format}.
 * The native request says {@code think: false} unless the model is known not
 * to think ({@link LLM.LLMModel#nativeNoThinking()}): extraction never thinks.
 * Every other service keeps its OpenAI-compatible {@code /v1/chat/completions}
 * ({@link LLM#chatWithResponseFormat}) with a {@code json_schema} (with the
 * {@code name} that protocol requires), a {@code json_object} or no
 * {@code response_format}; there is no switching between the two paths. With
 * the setting {@code auto} the model's "format" capability (a result of the
 * current technical probe, {@link LLM#formatCapability}) decides: supported
 * sends the schema, unsupported (the endpoint refused it) sends nothing
 * (prompt and validator only), ignored and unknown send the schema as before. An endpoint that rejects the format
 * (HTTP 400) is asked again without it and remembered; that fallback is
 * counted and shown. The answer is validated the same way in every mode.
 */
public final class YacyLlmClient implements LlmClient {

    /** Upper bound of the raw HTTP response read into memory. */
    static final int MAX_RESPONSE_CHARS = 1 << 20;
    /** {@code json_schema.name}: required by the OpenAI protocol, ignored where an endpoint does not use it. */
    public static final String SCHEMA_NAME = "scoutro_knowledge_extraction";

    /** Settings of {@code scoutro.kg.llm.structuredOutput}. */
    public static final String AUTO = "auto", JSON_SCHEMA = "json_schema", JSON_OBJECT = "json_object", NONE = "none";
    public static final List<String> SETTINGS = List.of(AUTO, JSON_SCHEMA, JSON_OBJECT, NONE);
    /** The API a model is called on: Ollama's native {@code /api/chat}, or the OpenAI-compatible {@code /v1/chat/completions}. */
    public static final String API_OLLAMA_NATIVE = "ollama_native", API_OPENAI_COMPATIBLE = "openai_compatible";
    /** Modes: what a request asks for and whether the endpoint is reported to enforce it. */
    public static final String SCHEMA_ENFORCED = "schema_enforced", SCHEMA_UNVERIFIED = "schema_unverified",
            JSON_MODE = "json_mode", VALIDATOR_ONLY = "validator_only", FALLBACK = "fallback_after_rejection";

    private final Supplier<LLM.LLMModel> selection;
    /** Endpoint and model ({@code hoststub|service/model}) that rejected a response_format with HTTP 400. */
    private final Set<String> withoutSchema = ConcurrentHashMap.newKeySet();
    private final AtomicLong requestsJsonSchema = new AtomicLong();
    private final AtomicLong requestsJsonObject = new AtomicLong();
    private final AtomicLong requestsWithoutFormat = new AtomicLong();
    private final AtomicLong formatRejections = new AtomicLong();
    private volatile long lastRejectionAt;
    /** What the endpoints reported (tokens are counted where an answer carries them). */
    private final AtomicLong usageReports = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong promptTokensMax = new AtomicLong();
    private final AtomicLong answerTokens = new AtomicLong();
    private final AtomicLong answerTokensMax = new AtomicLong();
    private final AtomicLong answersCutAtLimit = new AtomicLong();
    /** How often the window of an unconfigured Ollama service is read again, and the timeout of that read. */
    static final long DETECT_EVERY_MILLIS = 10L * 60_000L;
    static final int DETECT_TIMEOUT_MILLIS = 3000;
    long detectEveryMillis = DETECT_EVERY_MILLIS;
    /**
     * Per {@code hoststub|model}: when it was read, the loaded window last seen (kept while the server has unloaded the
     * model: not loaded is no other window) and the model's maximum.
     */
    private final Map<String, long[]> detected = new ConcurrentHashMap<>();

    /** What one request asks for, and why. */
    static final class Plan {
        final String mode;
        /** {@code json_schema}, {@code json_object} or {@code none}. */
        final String request;
        final String reason;

        Plan(final String mode, final String request, final String reason) {
            this.mode = mode;
            this.request = request;
            this.reason = reason;
        }
    }

    /** The model selected for {@link LLM.LLMUsage#knowledge}, read at every call (a changed selection applies at once). */
    public YacyLlmClient() {
        this(() -> LLM.llmFromUsageQuiet(LLM.LLMUsage.knowledge));
    }

    public YacyLlmClient(final Supplier<LLM.LLMModel> selection) {
        this.selection = selection;
    }

    @Override
    public String model() {
        return name(this.selection.get());
    }

    static String name(final LLM.LLMModel m) {
        if (m == null || m.llm == null || m.model == null || m.model.isEmpty()) {
            return null;
        }
        final String n = m.llm.type + "/" + m.model;
        return n.length() <= 200 ? n : n.substring(0, 200);
    }

    @Override
    public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
            throws IOException {
        return complete(system, user, schema, timeoutMillis, AUTO);
    }

    /**
     * The window the requests are planned against, the row's output cap, and whether the endpoint may count the schema
     * as prompt (the native Ollama path applies it as a grammar). The window is the service's configured
     * {@code ai.service_num_ctx} (sent as {@code num_ctx}); for an unconfigured Ollama service the window the server
     * reports for the loaded model ({@code /api/ps}, read at most every ten minutes; the last one seen is kept while the
     * model is unloaded; not sent, so the server is asked exactly as before); else 4096, Ollama's smallest default, is assumed.
     */
    @Override
    public Window window() {
        final LLM.LLMModel m = this.selection.get();
        if (name(m) == null) {
            return null;
        }
        final boolean nativeApi = API_OLLAMA_NATIVE.equals(api(m));
        final long[] d = nativeApi ? detect(m) : null;
        final Integer max = d == null || d[2] <= 0 ? null : (int) d[2];
        if (m.llm.num_ctx_configured) {
            return new Window(m.llm.num_ctx, true, m.llm.max_tokens, !nativeApi, Window.CONFIGURED, max);
        }
        if (d != null && d[1] > 0) {
            return new Window((int) (max == null ? d[1] : Math.min(d[1], max)), false, m.llm.max_tokens, false, Window.DETECTED, max);
        }
        return new Window(m.llm.num_ctx, false, m.llm.max_tokens, !nativeApi, Window.ASSUMED, max);
    }

    /** {@code {readAt, loaded window or 0, model maximum or 0}}, read again after {@link #detectEveryMillis}. */
    private long[] detect(final LLM.LLMModel m) {
        final String key = m.llm.hoststub + "|" + m.model;
        final long now = System.currentTimeMillis();
        final long[] last = this.detected.get(key);
        if (last != null && now - last[0] < this.detectEveryMillis) {
            return last;
        }
        final Integer loaded = m.llm.ollamaLoadedContext(m.model, DETECT_TIMEOUT_MILLIS);
        final Integer max = last != null && last[2] > 0 ? Integer.valueOf((int) last[2]) : m.llm.ollamaModelContext(m.model, DETECT_TIMEOUT_MILLIS);
        final long[] read = {now, loaded != null ? loaded : last != null ? last[1] : 0L, max != null ? max : 0L};
        this.detected.put(key, read);
        return read;
    }

    private void record(final JSONObject u) {
        this.usageReports.incrementAndGet();
        if (u.has("promptTokens")) {
            final long p = u.optLong("promptTokens");
            this.promptTokens.addAndGet(p);
            this.promptTokensMax.accumulateAndGet(p, Math::max);
        }
        if (u.has("answerTokens")) {
            final long a = u.optLong("answerTokens");
            this.answerTokens.addAndGet(a);
            this.answerTokensMax.accumulateAndGet(a, Math::max);
        }
        if ("length".equals(u.optString("stop"))) {
            this.answersCutAtLimit.incrementAndGet();
        }
    }

    /**
     * Measured since the start, per answer the endpoint reported on: prompt tokens (for Ollama the tokens it evaluated;
     * a prefix served from its prompt cache is not counted, so this is a lower bound), answer tokens, and answers cut
     * at the output or context limit ({@code done_reason}/{@code finish_reason} {@code length}).
     */
    @Override
    public JSONObject usage() {
        final long n = this.usageReports.get();
        return KgJson.obj("answers", n, "promptTokensMax", this.promptTokensMax.get(), "promptTokensAverage",
                n == 0 ? null : this.promptTokens.get() / n, "answerTokensMax", this.answerTokensMax.get(), "answerTokensAverage",
                n == 0 ? null : this.answerTokens.get() / n, "answersCutAtLimit", this.answersCutAtLimit.get());
    }

    @Override
    public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis,
            final String structuredOutput) throws IOException {
        return complete(system,user,schema,timeoutMillis,structuredOutput,LlmClient.UNLIMITED);
    }

    @Override
    public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis,
            final String structuredOutput,final RequestGate gate) throws IOException {
        final LLM.LLMModel m = this.selection.get();
        final String name = name(m);
        if (name == null) {
            throw new IOException("no model is selected for the knowledge usage");
        }
        final int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMillis));
        final String key = m.llm.hoststub + "|" + name;
        final Plan plan = plan(structuredOutput, m.formatCapability, this.withoutSchema.contains(key), schema != null);
        final JSONObject format = responseFormat(plan.request, schema);
        try {
            return call(m, system, user, format, timeout,gate);
        } catch (final IOException e) {
            if (format != null && e.getMessage() != null && e.getMessage().contains("response code 400")) {
                this.formatRejections.incrementAndGet();
                this.lastRejectionAt = System.currentTimeMillis();
                this.withoutSchema.add(key);
                // Remember rejection before admission: a deferred fallback must not repeat the rejected request.
                try {
                    final String value = call(m, system, user, null, timeout, gate);
                    this.withoutSchema.add(key);
                    return value;
                } catch (final IOException fallback) {
                    if (fallback instanceof LlmClient.Deferred) this.withoutSchema.add(key);
                    else this.withoutSchema.remove(key); // unchanged policy if the endpoint fails without format too
                    throw fallback;
                }
            }
            throw e;
        }
    }

    /**
     * The negotiation: the setting first, then (with {@code auto}) the model's "format" capability; an endpoint that
     * rejected a format before is asked without one. Unknown and ignored keep the former behaviour (the schema, with the 400
     * fallback), but is not shown as enforced.
     */
    static Plan plan(final String setting, final String capability, final boolean rejected, final boolean schema) {
        final String s = setting == null || !SETTINGS.contains(setting) ? AUTO : setting;
        final String cap = LLM.capabilityStatus(capability);
        if (!schema || NONE.equals(s)) {
            return new Plan(VALIDATOR_ONLY, NONE, schema ? "setting" : "no_schema");
        }
        if (rejected) {
            return new Plan(FALLBACK, NONE, "http_400");
        }
        if (JSON_OBJECT.equals(s)) {
            return new Plan(JSON_MODE, JSON_OBJECT, "setting");
        }
        if (JSON_SCHEMA.equals(s)) {
            return new Plan("supported".equals(cap) ? SCHEMA_ENFORCED : SCHEMA_UNVERIFIED, JSON_SCHEMA, "setting");
        }
        switch (cap) {
            case "supported":
                return new Plan(SCHEMA_ENFORCED, JSON_SCHEMA, "capability_supported");
            case "unsupported":
                return new Plan(VALIDATOR_ONLY, NONE, "capability_unsupported");
            case "ignored":
                // accepted, but not followed in the probe: sending it costs nothing, it is not shown as enforced
                return new Plan(SCHEMA_UNVERIFIED, JSON_SCHEMA, "capability_ignored");
            default:
                return new Plan(SCHEMA_UNVERIFIED, JSON_SCHEMA, "capability_unknown");
        }
    }

    /** The OpenAI {@code response_format} of a request, or null for none. */
    static JSONObject responseFormat(final String request, final JSONObject schema) throws IOException {
        try {
            if (JSON_SCHEMA.equals(request) && schema != null) {
                final JSONObject jsonSchema = new JSONObject(true);
                jsonSchema.put("name", SCHEMA_NAME);
                jsonSchema.put("strict", true);
                jsonSchema.put("schema", schema);
                return new JSONObject(true).put("type", JSON_SCHEMA).put("json_schema", jsonSchema);
            }
            if (JSON_OBJECT.equals(request)) {
                return new JSONObject(true).put("type", JSON_OBJECT);
            }
            return null;
        } catch (final JSONException e) {
            throw new IOException(e.getMessage());
        }
    }

    @Override
    public JSONObject structuredOutput(final String structuredOutput) {
        final LLM.LLMModel m = this.selection.get();
        final String name = name(m);
        final String setting = structuredOutput == null || !SETTINGS.contains(structuredOutput) ? AUTO : structuredOutput;
        final boolean rejected = name != null && this.withoutSchema.contains(m.llm.hoststub + "|" + name);
        final Plan plan = name == null ? null : plan(setting, m.formatCapability, rejected, true);
        final long at = this.lastRejectionAt;
        final boolean nativeApi = name != null && API_OLLAMA_NATIVE.equals(api(m));
        return KgJson.obj("setting", setting, "api", name == null ? null : api(m), "capability", name == null ? null : LLM.capabilityStatus(m.formatCapability),
                // the native path only: the model's thinking capability of the native probe, and think: false when it is sent
                "thinking", nativeApi ? m.thinkingCapability : null, "think", nativeApi && m.nativeNoThinking() ? Boolean.FALSE : null,
                "mode", plan == null ? null : plan.mode, "request", plan == null ? null : plan.request,
                "reason", plan == null ? null : plan.reason, "withoutSchema", rejected,
                "requests", KgJson.obj(JSON_SCHEMA, this.requestsJsonSchema.get(), JSON_OBJECT, this.requestsJsonObject.get(),
                        NONE, this.requestsWithoutFormat.get()),
                "rejections", this.formatRejections.get(),
                "lastRejection", at == 0L ? null : KgJson.obj("code", "http_400", "at", at));
    }

    private String call(final LLM.LLMModel m, final String system, final String user, final JSONObject format,
            final int timeout,final RequestGate gate) throws IOException {
        final LLM.Context context;
        try {
            context = new LLM.Context(system);
            context.addPrompt(user);
        } catch (final JSONException e) {
            throw new IOException(e.getMessage());
        }
        try(RequestPermit permit=gate.start()) {
        (format == null ? this.requestsWithoutFormat
                : JSON_OBJECT.equals(format.optString("type")) ? this.requestsJsonObject : this.requestsJsonSchema).incrementAndGet();
        if (API_OLLAMA_NATIVE.equals(api(m))) {
            // the same request natively: never a fallback to /v1/chat/completions
            // think: false unless the native probe found the model does not think: extraction never thinks
            // num_ctx only where the service's window is configured: the budget is planned against it, and an unconfigured
            // server keeps its own context length (no blanket change of a production limit)
            return m.llm.chatOllamaNative(m.model, context, ollamaFormat(format), m.llm.max_tokens, m.nativeNoThinking(), timeout,
                    MAX_RESPONSE_CHARS, m.llm.num_ctx_configured ? m.llm.num_ctx : 0, this::record);
        }
        return m.llm.chatWithResponseFormat(m.model, context, format, m.llm.max_tokens, timeout, MAX_RESPONSE_CHARS, this::record);
        }
    }

    /** The API of a model: native for {@link LLM.LLMType#OLLAMA}, OpenAI-compatible for every other service. */
    static String api(final LLM.LLMModel m) {
        return m != null && m.llm != null && m.llm.type == LLM.LLMType.OLLAMA ? API_OLLAMA_NATIVE : API_OPENAI_COMPATIBLE;
    }

    /**
     * Ollama's native {@code format} for a negotiated OpenAI {@code response_format}: the schema itself for
     * {@code json_schema}, {@code "json"} for {@code json_object}, null for none.
     */
    static Object ollamaFormat(final JSONObject responseFormat) {
        if (responseFormat == null) {
            return null;
        }
        if (JSON_OBJECT.equals(responseFormat.optString("type"))) {
            return "json";
        }
        final JSONObject jsonSchema = responseFormat.optJSONObject("json_schema");
        return jsonSchema == null ? null : jsonSchema.optJSONObject("schema");
    }
}
