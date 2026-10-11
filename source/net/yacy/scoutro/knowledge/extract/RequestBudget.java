/*
 *  RequestBudget
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.json.JSONObject;

import net.yacy.ai.PromptGuard;

/**
 * The token budget of one model request of the LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): the whole request,
 * system prompt, answer schema (where the endpoint may count it as prompt), the user message with the page's site,
 * language, title, facility kinds, known entities and the chunk, plus a reserve for the answer, must fit the model's
 * context window. Ollama keeps prompt and answer in one window and otherwise drops the start of the prompt, so a request
 * that does not fit is never sent.
 * <p>
 * Tokens are estimated from characters with ratios on the safe side for the tokenizers of the models in use (Qwen,
 * Llama): English instructions 4.0, page text and data 3.5, JSON 3.2 characters per token, plus a frame for the chat
 * template. When a chunk with all known entities does not fit, the request keeps only the known entities whose name
 * occurs in the chunk (job postings always): the validator accepts a claim or value about a known entity only with a
 * verbatim quote that contains its name, so the others cannot be used in that chunk's answer. If even these do not fit,
 * the chunk is split (at most twice); if a part still does not fit, the document is refused visibly instead of sent.
 */
public final class RequestBudget {

    /** Characters per token: English instructions, page text and data, JSON. */
    static final double PROSE = 4.0, TEXT = 3.5, JSON = 3.2;
    /** Tokens of the chat template around system and user message. */
    static final int FRAME_TOKENS = 64;
    /** Ollama's context length when the service has none configured. */
    public static final int DEFAULT_WINDOW = 4096;
    /** A chunk is split at most this often (into four parts), and never below this many characters. */
    static final int MAX_SPLITS = 2, MIN_SPLIT_CHARS = 800;

    public final int window;
    public final int reserve;
    /** System prompt, schema (where counted) and frame. */
    public final int fixedTokens;

    /**
     * @param window          tokens of the context window
     * @param reserve         tokens kept for the answer (at most the output cap)
     * @param system          the system prompt
     * @param schema          the answer schema, or null where the endpoint does not count it as prompt
     */
    public RequestBudget(final int window, final int reserve, final String system, final JSONObject schema) {
        this.window = window;
        this.reserve = reserve;
        this.fixedTokens = tokens(system == null ? 0 : system.length(), PROSE) + (schema == null ? 0 : tokens(schema.toString().length(), JSON))
                + FRAME_TOKENS;
    }

    static int tokens(final int chars, final double perToken) {
        return (int) Math.ceil(chars / perToken);
    }

    /** Estimated prompt tokens of a request with this user message. */
    public int prompt(final String user) {
        return this.fixedTokens + tokens(user.length(), TEXT);
    }

    /** True if a request with this user message leaves the reserve for the answer. */
    public boolean fits(final String user) {
        return prompt(user) + this.reserve <= this.window;
    }

    /** One request of a document: the chunk and the known entities it is asked with. */
    public static final class Part {
        public final LlmExtractor.Chunk chunk;
        public final List<LlmExtractor.Known> known;
        /** Estimated prompt tokens. */
        public final int promptTokens;

        Part(final LlmExtractor.Chunk chunk, final List<LlmExtractor.Known> known, final int promptTokens) {
            this.chunk = chunk;
            this.known = known;
            this.promptTokens = promptTokens;
        }
    }

    /** The requests of one document, or why it cannot be asked. */
    public static final class Plan {
        public final List<Part> parts = new ArrayList<>();
        /** Known entities left out of requests (not named in their chunk). */
        public int knownTrimmed;
        /** Chunks split to fit. */
        public int splits;
        /** True if a part does not fit even split: the document is not asked. */
        public boolean refused;
        /** The largest estimated prompt of the plan. */
        public int maxPromptTokens;
    }

    /**
     * Plans the requests of the given chunks. Every chunk that fits with all known entities is asked as before (the cache
     * key stays the same); only a chunk that does not fit is asked with the known entities named in it, or split.
     */
    public Plan plan(final List<LlmExtractor.Chunk> chunks, final List<LlmExtractor.Known> known, final String title, final String domain,
            final String language, final Collection<String> kinds) {
        final Plan plan = new Plan();
        final PromptGuard guard = new PromptGuard(); // markers of a fixed length: the estimate equals the request's length
        for (final LlmExtractor.Chunk chunk : chunks) {
            if (!add(plan, guard, chunk, known, title, domain, language, kinds, 0)) {
                plan.refused = true;
                return plan;
            }
        }
        return plan;
    }

    private boolean add(final Plan plan, final PromptGuard guard, final LlmExtractor.Chunk chunk, final List<LlmExtractor.Known> known,
            final String title, final String domain, final String language, final Collection<String> kinds, final int depth) {
        final String all = LlmExtractor.userPrompt(guard, chunk, title, domain, language, known, kinds);
        if (fits(all)) {
            plan.parts.add(new Part(chunk, known, prompt(all)));
            plan.maxPromptTokens = Math.max(plan.maxPromptTokens, prompt(all));
            return true;
        }
        final List<LlmExtractor.Known> needed = LlmExtractor.needed(known, chunk);
        final String trimmed = LlmExtractor.userPrompt(guard, chunk, title, domain, language, needed, kinds);
        if (fits(trimmed)) {
            plan.knownTrimmed += known.size() - needed.size();
            plan.parts.add(new Part(chunk, needed, prompt(trimmed)));
            plan.maxPromptTokens = Math.max(plan.maxPromptTokens, prompt(trimmed));
            return true;
        }
        if (depth >= MAX_SPLITS || chunk.text.length() < 2 * MIN_SPLIT_CHARS) {
            return false;
        }
        final int middle = chunk.text.length() / 2;
        int cut = chunk.text.lastIndexOf(' ', middle);
        if (cut < MIN_SPLIT_CHARS) {
            cut = middle;
        }
        plan.splits++;
        return add(plan, guard, new LlmExtractor.Chunk(chunk.text.substring(0, cut), chunk.offset), known, title, domain, language, kinds,
                depth + 1)
                && add(plan, guard, new LlmExtractor.Chunk(chunk.text.substring(cut), chunk.offset + cut), known, title, domain, language,
                        kinds, depth + 1);
    }
}
