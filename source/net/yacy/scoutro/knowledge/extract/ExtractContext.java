/*
 *  ExtractContext
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
import java.util.Collections;
import java.util.List;
import java.util.Set;

import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.Nace;

/**
 * What the extractors of vocabulary 2 know about the document besides its
 * content: the business vocabularies of its collections (categories,
 * segments), whether jobs are switched on for one of its collections, and
 * the classification. Built per document; immutable.
 */
public final class ExtractContext {

    public final Categories categories;
    public final Nace nace;
    /** The vocabularies of the document's collections (empty: no category is read). */
    public final List<Categories.Vocab> vocabularies;
    /** True if a collection of the document has jobs switched on ({@code scoutro.kg.jobs.collections}). */
    public final boolean jobs;
    /** The document's followed collections. */
    public final List<String> collections;

    public ExtractContext(final KgVocabularies.Snapshot vocab, final Collection<String> vocabularyNames, final boolean jobs,
            final Collection<String> collections) {
        this.categories = vocab.categories;
        this.nace = vocab.nace;
        final List<Categories.Vocab> v = new ArrayList<>();
        for (final String n : vocabularyNames) {
            final Categories.Vocab x = vocab.categories.vocabulary(n);
            if (x != null && !v.contains(x)) {
                v.add(x);
            }
        }
        this.vocabularies = Collections.unmodifiableList(v);
        this.jobs = jobs;
        this.collections = collections == null ? Collections.emptyList() : List.copyOf(collections);
    }

    /** A context without vocabularies and without jobs (version 1 behaviour, and tests of the base extractors). */
    public static ExtractContext none() {
        return new ExtractContext(KgVocabularies.get(), Set.of(), false, List.of());
    }

    /** Every vocabulary there is: target industries and categories of an audience may lie in another collection's field. */
    public Collection<Categories.Vocab> allVocabularies() {
        return this.categories.vocabularies.values();
    }
}
