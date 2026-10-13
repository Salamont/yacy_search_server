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
    /**
     * True if a collection of the document reads job advertisements for software and system hints
     * ({@code scoutro.kg.jobs.signals}, 0.10.2): with {@link #jobs} on the job objects, without them on the declared employer.
     */
    public final boolean jobSignals;
    /** The document's followed collections. */
    public final List<String> collections;
    /**
     * The job titles of the page as keys ({@link BusinessRules#jobKey}, 0.10.5): the positions it advertises and the other
     * titles (menu entries, teaser links), as the rules read them ({@link BusinessRules#jobTitleKeys}); empty if not known.
     */
    public final Set<String> postingTitles;
    public final Set<String> otherTitles;

    /** A context whose job hints follow the job objects (the behaviour before 0.10.2). */
    public ExtractContext(final KgVocabularies.Snapshot vocab, final Collection<String> vocabularyNames, final boolean jobs,
            final Collection<String> collections) {
        this(vocab, vocabularyNames, jobs, jobs, collections);
    }

    public ExtractContext(final KgVocabularies.Snapshot vocab, final Collection<String> vocabularyNames, final boolean jobs,
            final boolean jobSignals, final Collection<String> collections) {
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
        this.jobSignals = jobSignals;
        this.collections = collections == null ? Collections.emptyList() : List.copyOf(collections);
        this.postingTitles = Set.of();
        this.otherTitles = Set.of();
    }

    private ExtractContext(final ExtractContext c, final Set<String> postingTitles, final Set<String> otherTitles) {
        this.categories = c.categories;
        this.nace = c.nace;
        this.vocabularies = c.vocabularies;
        this.jobs = c.jobs;
        this.jobSignals = c.jobSignals;
        this.collections = c.collections;
        this.postingTitles = Set.copyOf(postingTitles);
        this.otherTitles = Set.copyOf(otherTitles);
    }

    /** This context with the job titles of the page: {postings, others} of {@link BusinessRules#jobTitleKeys}. */
    public ExtractContext withJobTitles(final List<Set<String>> keys) {
        return keys == null || keys.size() < 2 ? this : new ExtractContext(this, keys.get(0), keys.get(1));
    }

    /** True if a job of this name is a position the page advertises by the rules (its title, or the end of one). */
    public boolean posting(final String name) {
        final String key = BusinessRules.jobKey(name);
        if (key == null) {
            return false;
        }
        for (final String posting : this.postingTitles) {
            if (posting.equals(key) || posting.endsWith(" " + key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if a job of this name is no position of the page by the rules: its title, or the end of a title (a title that
     * absorbed the menu words before it), is one the page has only as a menu entry or teaser link, never as an advertisement.
     */
    public boolean noPosting(final String name) {
        final String key = BusinessRules.jobKey(name);
        if (key == null || posting(name)) {
            return false;
        }
        for (final String other : this.otherTitles) {
            if (other.equals(key) || other.endsWith(" " + key)) {
                return true;
            }
        }
        return false;
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
