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
    /** Admission refusal, distinct from a failed model transport. */
    class Deferred extends IOException {
        private static final long serialVersionUID = 1L;
        public final String reason;

        public Deferred(final String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /** Admission immediately before each actual transport, including format fallbacks. */
    @FunctionalInterface interface RequestGate { RequestPermit start() throws IOException; }
    @FunctionalInterface interface RequestPermit extends AutoCloseable { @Override void close(); }
    RequestGate UNLIMITED=()->()->{};

    /** Simple clients make one request; negotiating clients must override and gate every transport. */
    default String complete(String system,String user,JSONObject schema,long timeoutMillis,String structuredOutput,RequestGate gate)throws IOException {
        try(RequestPermit permit=gate.start()){return complete(system,user,schema,timeoutMillis,structuredOutput);}
    }

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

    /**
     * The context window the requests of the selected model are budgeted against (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.3).
     */
    final class Window {
        /** Where {@link #tokens} comes from. */
        public static final String CONFIGURED = "configured", DETECTED = "detected", ASSUMED = "assumed_default";

        /** Tokens of prompt and answer together. */
        public final int tokens;
        /**
         * True if the request carries this window as {@code num_ctx}: a window configured for the service
         * ({@code ai.service_num_ctx}), and any window on the OpenAI-compatible path, which sends it always.
         */
        public final boolean configured;
        /** The row's output cap ({@code max_tokens}, Ollama's {@code num_predict}). */
        public final int maxOutputTokens;
        /** True if the endpoint may put the answer schema into the prompt (OpenAI-compatible); Ollama applies it as a grammar. */
        public final boolean schemaInPrompt;
        /**
         * {@link #CONFIGURED} (sent as {@code num_ctx}), {@link #DETECTED} (the window the server reports for the loaded
         * model, not sent) or {@link #ASSUMED} (4096, Ollama's smallest default).
         */
        public final String source;
        /** The longest context the model itself supports, as the server reports it; null if unknown. */
        public final Integer modelMaxTokens;

        public Window(final int tokens, final boolean configured, final int maxOutputTokens, final boolean schemaInPrompt) {
            this(tokens, configured, maxOutputTokens, schemaInPrompt, configured ? CONFIGURED : ASSUMED, null);
        }

        public Window(final int tokens, final boolean configured, final int maxOutputTokens, final boolean schemaInPrompt, final String source,
                final Integer modelMaxTokens) {
            this.tokens = tokens;
            this.configured = configured;
            this.maxOutputTokens = maxOutputTokens;
            this.schemaInPrompt = schemaInPrompt;
            this.source = source;
            this.modelMaxTokens = modelMaxTokens;
        }
    }

    /** The window of the selected model, or null if unknown (then 4096 tokens, Ollama's smallest default, are assumed). */
    default Window window() {
        return null;
    }

    /** {@link #window()} as last read, without asking the server (the status). */
    default Window lastWindow() {
        return window();
    }

    /** What the endpoint counted for the requests since the start (tokens, answers cut at a limit), or null. */
    default JSONObject usage() {
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
