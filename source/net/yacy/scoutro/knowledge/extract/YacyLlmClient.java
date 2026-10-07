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
 * 6.3). Every service is spoken to through its OpenAI-compatible
 * {@code /v1/chat/completions} ({@link LLM#chatWithResponseFormat}), so the
 * request can carry a {@code json_schema} (with the {@code name} that protocol
 * requires), a {@code json_object} or no {@code response_format} at all. With
 * the setting {@code auto} the model's "format" capability decides: supported
 * sends the schema, unsupported sends nothing (prompt and validator only),
 * unknown sends the schema as before. An endpoint that rejects the format
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

    @Override
    public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis,
            final String structuredOutput) throws IOException {
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
            return call(m, system, user, format, timeout);
        } catch (final IOException e) {
            if (format != null && e.getMessage() != null && e.getMessage().contains("response code 400")) {
                this.formatRejections.incrementAndGet();
                this.lastRejectionAt = System.currentTimeMillis();
                final String answer = call(m, system, user, null, timeout);
                this.withoutSchema.add(key);
                return answer;
            }
            throw e;
        }
    }

    /**
     * The negotiation: the setting first, then (with {@code auto}) the model's "format" capability; an endpoint that
     * rejected a format before is asked without one. Unknown keeps the former behaviour (the schema, with the 400
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
        return KgJson.obj("setting", setting, "capability", name == null ? null : LLM.capabilityStatus(m.formatCapability),
                "mode", plan == null ? null : plan.mode, "request", plan == null ? null : plan.request,
                "reason", plan == null ? null : plan.reason, "withoutSchema", rejected,
                "requests", KgJson.obj(JSON_SCHEMA, this.requestsJsonSchema.get(), JSON_OBJECT, this.requestsJsonObject.get(),
                        NONE, this.requestsWithoutFormat.get()),
                "rejections", this.formatRejections.get(),
                "lastRejection", at == 0L ? null : KgJson.obj("code", "http_400", "at", at));
    }

    private String call(final LLM.LLMModel m, final String system, final String user, final JSONObject format,
            final int timeout) throws IOException {
        final LLM.Context context;
        try {
            context = new LLM.Context(system);
            context.addPrompt(user);
        } catch (final JSONException e) {
            throw new IOException(e.getMessage());
        }
        (format == null ? this.requestsWithoutFormat
                : JSON_OBJECT.equals(format.optString("type")) ? this.requestsJsonObject : this.requestsJsonSchema).incrementAndGet();
        return m.llm.chatWithResponseFormat(m.model, context, format, m.llm.max_tokens, timeout, MAX_RESPONSE_CHARS);
    }
}
