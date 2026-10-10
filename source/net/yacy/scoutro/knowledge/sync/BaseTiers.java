/*
 *  BaseTiers
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
import java.net.URI;

import java.util.List;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.extract.ExtractContext;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.MetadataExtractor;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * Tiers 1 and 2 of one Solr document (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.1):
 * the sync publishes them, the LLM tier reads them again as the known
 * entities of the page. Deterministic for equal input.
 */
final class BaseTiers {

    private final KgConfig cfg;
    private final JsonLdExtractor jsonld;
    private final MetadataExtractor metadata;
    private final RuleExtractor rules;

    BaseTiers(final KgConfig cfg) {
        this.cfg = cfg;
        this.jsonld = new JsonLdExtractor(cfg.extractMaxExcerptChars);
        this.metadata = new MetadataExtractor(cfg.extractMaxExcerptChars);
        this.rules = new RuleExtractor(cfg.extractMaxExcerptChars, cfg.extractMaxRuleInputChars);
    }

    /** The document's text, read at most once and only when needed. */
    static final class Text {
        private final SolrSource solr;
        private final String id;
        private boolean read;
        private String value;

        Text(final SolrSource solr, final String id) {
            this.solr = solr;
            this.id = id;
        }

        String get() throws IOException {
            if (!this.read) {
                this.value = this.solr.text(this.id);
                this.read = true;
            }
            return this.value;
        }
    }

    /** Tiers 1 and 2; globally relevant signals also occur outside career/service pages. */
    Extraction extract(final SolrDoc d, final Text text) throws IOException {
        final Extraction ex = new Extraction(this.cfg.extractMaxStatementsPerDoc);
        final String host = host(d);
        final ExtractContext ctx = context(this.cfg, d);
        this.jsonld.extract(d.ldJson, d.url, host, d.language, ex, ctx);
        this.metadata.extract(d.publisher, d.coordinates(), ex);
        this.rules.extract(text.get(), d.url, host, d.language, ex, ctx, d.titles, d.outbound);
        net.yacy.scoutro.knowledge.extract.BusinessFacts.industriesFromServices(ex, ctx, 2, this.cfg.extractMaxExcerptChars);
        return ex;
    }

    /** The document's vocabularies and job extraction policy for its followed collections. */
    static ExtractContext context(final KgConfig cfg, final SolrDoc d) {
        final List<String> followed = d.followed(cfg);
        final KgVocabularies.Snapshot vocab = KgVocabularies.get();
        return new ExtractContext(vocab, cfg.vocabulariesOf(followed, vocab.categories.collections), cfg.jobsFor(followed), followed);
    }

    static String host(final SolrDoc d) {
        if (d.host != null) {
            return d.host;
        }
        try {
            return d.url == null ? null : new URI(d.url).getHost();
        } catch (final Exception e) {
            return null;
        }
    }
}
