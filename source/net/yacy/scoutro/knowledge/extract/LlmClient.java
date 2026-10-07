/*
 *  LlmClient
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

import org.json.JSONObject;

/**
 * The model behind the LLM tier: Scoutro's existing LLM configuration, usage
 * {@code knowledge} (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). No provider, host,
 * model or key of its own.
 */
public interface LlmClient {

    /**
     * The selected model as {@code service/model} (no host, no key: it becomes
     * part of the extractor identity and of the provenance), or null if no model
     * is selected for the usage.
     */
    String model();

    /**
     * One blocking chat completion with the configured output limit of the model.
     *
     * @param schema        JSON schema for the answer (sent as {@code response_format} where negotiated); null for none
     * @param timeoutMillis read timeout of the call
     * @return the assistant's content
     * @throws IOException timeout, connection or HTTP error (a transport failure for the circuit breaker)
     */
    String complete(String system, String user, JSONObject schema, long timeoutMillis) throws IOException;

    /**
     * {@link #complete(String, String, JSONObject, long)} with the setting
     * {@code scoutro.kg.llm.structuredOutput} ({@code auto}, {@code json_schema},
     * {@code json_object}, {@code none}): what the endpoint is asked for is
     * negotiated per model (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3). The answer is
     * validated the same way in every mode.
     */
    default String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis,
            final String structuredOutput) throws IOException {
        return complete(system, user, schema, timeoutMillis);
    }

    /**
     * The structured-output state of the selected model for the status (capability, mode, counters since the
     * start; no answer, host or key), or null where the client negotiates nothing.
     */
    default JSONObject structuredOutput(final String structuredOutput) {
        return null;
    }

    /** No model is ever selected (tests, environments without Scoutro's LLM configuration). */
    LlmClient NONE = new LlmClient() {
        @Override
        public String model() {
            return null;
        }

        @Override
        public String complete(final String system, final String user, final JSONObject schema, final long timeoutMillis)
                throws IOException {
            throw new IOException("no model is selected for the knowledge usage");
        }
    };
}
