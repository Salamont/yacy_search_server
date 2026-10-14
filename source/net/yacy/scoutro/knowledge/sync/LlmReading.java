/*
 *  LlmReading
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

package net.yacy.scoutro.knowledge.sync;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import net.yacy.ai.PromptGuard;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.extract.BusinessRules;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * The steps of the LLM tier's reading of one page (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3): what the request says about
 * the page (title, domain, language, the known entities of tiers 1 and 2, the facility kinds), the planned requests, the
 * user prompt, the validation of an answer and the extraction the publication takes. {@link LlmService} reads the queue's
 * pages with them; the model quality check (docs/SCOUTRO_LLM_QUALITY_CHECK.md) reads its example pages with the same
 * steps, so it never has an extraction of its own. Neither the queue, the extraction cache, the progress nor the
 * publication is part of this class.
 */
public final class LlmReading {

    public final String title;
    public final String domain;
    public final String language;
    /** The entities tiers 1 and 2 found on the page (ids {@code k1}, {@code k2}, ...). */
    public final List<LlmExtractor.Known> known;
    /** The facility kinds the model may assign for the page's collections. */
    public final Set<String> kinds;

    private LlmReading(final String title, final String domain, final String language, final List<LlmExtractor.Known> known,
            final Set<String> kinds) {
        this.title = title;
        this.domain = domain;
        this.language = language;
        this.known = known;
        this.kinds = kinds;
    }

    /**
     * The reading of a page with the result of its tiers 1 and 2.
     *
     * @param collections the page's collections the graph holds for it
     */
    public static LlmReading of(final KgConfig cfg, final SolrDoc d, final Collection<String> collections, final Extraction base) {
        return new LlmReading(d.titles.isEmpty() ? null : d.titles.get(0), Normalizers.registrableDomain(BaseTiers.host(d)), d.language,
                LlmExtractor.known(base), cfg.llmKinds(collections));
    }

    /**
     * The budget of the requests (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.3): the model's window (the service's configured
     * {@code ai.service_num_ctx}, else the window an Ollama server reports for the loaded model, else 4096, Ollama's
     * smallest default, assumed), the answer reserve (at most the row's output cap), the system prompt and, where the endpoint
     * may count it as prompt, the schema.
     */
    public static RequestBudget budget(final KgConfig cfg, final KnowledgePrompt p, final LlmClient.Window w) {
        final int window = w == null ? RequestBudget.DEFAULT_WINDOW : w.tokens;
        final int reserve = w == null ? cfg.llmAnswerReserveTokens : Math.min(cfg.llmAnswerReserveTokens, w.maxOutputTokens);
        return new RequestBudget(window, reserve, p.text, w == null || w.schemaInPrompt ? LlmExtractor.SCHEMA : null);
    }

    /** The requests of these chunks of the page. */
    public RequestBudget.Plan plan(final RequestBudget budget, final List<LlmExtractor.Chunk> chunks) {
        return budget.plan(chunks, this.known, this.title, this.domain, this.language, this.kinds);
    }

    /** The user message of one request (the page's chunk in its DATA block, with the known entities it is asked with). */
    public String userPrompt(final RequestBudget.Part part) {
        return LlmExtractor.userPrompt(new PromptGuard(), part.chunk, this.title, this.domain, this.language, part.known, this.kinds);
    }

    /** One request: the system prompt, the user message, the schema and the settings of the tier; every transport is admitted by {@code gate}. */
    public String ask(final LlmClient client, final KgConfig cfg, final KnowledgePrompt p, final RequestBudget.Part part,
            final LlmClient.RequestGate gate) throws IOException {
        return client.complete(p.text, userPrompt(part), LlmExtractor.SCHEMA, cfg.llmTimeoutMillis, cfg.llmStructuredOutput, gate);
    }

    /** The validation of the model's answer to one request. */
    public LlmExtractor.Result check(final String answer, final RequestBudget.Part part) {
        return LlmExtractor.validate(answer, part.chunk, part.known, this.kinds);
    }

    /**
     * What the accepted answers are read with into the extraction: the vocabularies and job policy of the page's collections,
     * and the job titles the rules see as positions of the page (a job they see as a menu entry is none of the model's either).
     */
    public static ExtractContext context(final KgConfig cfg, final SolrDoc d, final String stored) {
        return BaseTiers.context(cfg, d).withJobTitles(BusinessRules.jobTitleKeys(stored, d.url, d.titles));
    }

    // ------------------------------------------------------------ a page given as text

    /**
     * A page given as text, not read from Solr (the model quality check): its tiers 1 and 2, whether the queue would ask the
     * model about it at all, its chunks as a first reading takes them and its reading. The text is laid out as the queue
     * lays out a page without other pages of its host: unchanged.
     */
    public static final class Page {
        public final SolrDoc doc;
        public final String text;
        public final Extraction base;
        /** False: the queue would skip the page without a request ({@link RuleExtractor#candidate}). */
        public final boolean candidate;
        public final List<LlmExtractor.Chunk> chunks;
        public final LlmReading reading;
        /** True if the page's content is longer than one reading takes ({@code extract.maxInputChars}). */
        public final boolean cut;

        Page(final SolrDoc doc, final String text, final Extraction base, final boolean candidate, final List<LlmExtractor.Chunk> chunks,
                final LlmReading reading, final boolean cut) {
            this.doc = doc;
            this.text = text;
            this.base = base;
            this.candidate = candidate;
            this.chunks = chunks;
            this.reading = reading;
            this.cut = cut;
        }

        /**
         * The extraction the publication would take from the accepted answers (each with the known entities it was asked
         * with), bounded by the per-document limit of statements.
         */
        public Extraction extraction(final KgConfig cfg, final List<org.json.JSONObject> accepted,
                final List<List<LlmExtractor.Known>> acceptedKnown) {
            final Extraction ex = new Extraction(cfg.extractMaxStatementsPerDoc);
            final ExtractContext ctx = context(cfg, this.doc, this.text);
            for (int i = 0; i < accepted.size(); i++) {
                LlmExtractor.apply(accepted.get(i), acceptedKnown.get(i), ex, ctx);
            }
            ex.ranTier(LlmExtractor.TIER);
            return ex;
        }
    }

    /** A first reading of a page given as text: tiers 1 and 2 and the chunks of its content, as the queue would read it. */
    public static Page page(final KgConfig cfg, final SolrDoc d, final String text) throws IOException {
        final String stored = text == null ? "" : text;
        final Extraction base = new BaseTiers(cfg).extract(d, BaseTiers.Text.of(stored));
        final PageText pageText = PageText.of(stored);
        final String content = pageText.text.substring(0, pageText.contentEnd);
        final List<LlmExtractor.Chunk> chunks = LlmExtractor.chunks(content, cfg.extractMaxInputChars);
        return new Page(d, stored, base, RuleExtractor.candidate(d.url, d.titles, base), chunks, of(cfg, d, d.collections, base),
                content.length() > cfg.extractMaxInputChars);
    }
}
