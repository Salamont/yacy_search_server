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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.LLM;

/**
 * The LLM tier's model from Scoutro's LLM selection: the production model row
 * with the usage {@code knowledge} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3).
 * Endpoint, key and output limit are that row's; nothing is configured twice.
 * <p>
 * An endpoint that rejects the {@code response_format} schema (HTTP 400) is
 * asked again without it and remembered; the answer is validated the same way.
 */
public final class YacyLlmClient implements LlmClient {

    /** Upper bound of the raw HTTP response read into memory. */
    static final int MAX_RESPONSE_CHARS = 1 << 20;

    private final Supplier<LLM.LLMModel> selection;
    private final Set<String> withoutSchema = ConcurrentHashMap.newKeySet();

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
        final LLM.LLMModel m = this.selection.get();
        final String name = name(m);
        if (name == null) {
            throw new IOException("no model is selected for the knowledge usage");
        }
        final int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeoutMillis));
        final boolean useSchema = schema != null && !this.withoutSchema.contains(m.llm.hoststub + "|" + name);
        try {
            return call(m, system, user, useSchema ? schema : null, timeout);
        } catch (final IOException e) {
            if (useSchema && e.getMessage() != null && e.getMessage().contains("response code 400")) {
                final String answer = call(m, system, user, null, timeout);
                this.withoutSchema.add(m.llm.hoststub + "|" + name);
                return answer;
            }
            throw e;
        }
    }

    private static String call(final LLM.LLMModel m, final String system, final String user, final JSONObject schema,
            final int timeout) throws IOException {
        final LLM.Context context;
        try {
            context = new LLM.Context(system);
            context.addPrompt(user);
        } catch (final JSONException e) {
            throw new IOException(e.getMessage());
        }
        return m.llm.chat(m.model, context, schema, m.llm.max_tokens, timeout, MAX_RESPONSE_CHARS);
    }
}
