/*
 *  Scope
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

package net.yacy.scoutro.access;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The collections an identity may see and change: all collections, or an
 * explicit set. "All collections" always means all collections of this
 * identity; a narrower scope never turns into the whole index.
 */
public final class Scope {

    /** Valid collection names, the same rule as the agent scope and the chat. */
    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** Alphabetical regardless of case, then by code point (the order of the collection catalog). */
    public static final Comparator<String> ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    public static final Scope ALL = new Scope(true, Collections.emptyList());
    public static final Scope NONE = new Scope(false, Collections.emptyList());

    private final boolean all;
    private final List<String> collections;

    private Scope(final boolean all, final List<String> collections) {
        this.all = all;
        this.collections = collections;
    }

    /** An explicit scope; invalid names are dropped, order is alphabetical. */
    public static Scope of(final Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return NONE;
        }
        final TreeSet<String> set = new TreeSet<>(ORDER);
        for (final String n : names) {
            if (n != null && NAME.matcher(n.trim()).matches()) {
                set.add(n.trim());
            }
        }
        return set.isEmpty() ? NONE : new Scope(false, Collections.unmodifiableList(new ArrayList<>(set)));
    }

    public boolean isAll() {
        return this.all;
    }

    public boolean isEmpty() {
        return !this.all && this.collections.isEmpty();
    }

    /** The explicit collections (empty for {@link #ALL}). */
    public List<String> collections() {
        return this.collections;
    }

    public boolean allows(final String collection) {
        if (collection == null || !NAME.matcher(collection).matches()) {
            return false;
        }
        if (this.all) {
            return true;
        }
        for (final String c : this.collections) {
            if (c.equals(collection)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The effective collections of a request.
     *
     * @param requested an optional single collection named by the client
     * @return {@code null} for the whole index (only with {@link #ALL} and no
     *         request), otherwise the requested collection or all explicit
     *         collections of this scope
     * @throws CollectionNotAllowed when the requested name is invalid or not in the scope
     */
    public List<String> view(final String requested) throws CollectionNotAllowed {
        if (requested != null && !requested.trim().isEmpty()) {
            final String c = requested.trim();
            if (!allows(c)) {
                throw new CollectionNotAllowed();
            }
            return Collections.singletonList(c);
        }
        return this.all ? null : this.collections;
    }

    /** Only the names of {@code names} that this scope allows, in their order. */
    public List<String> filter(final Collection<String> names) {
        final List<String> out = new ArrayList<>();
        if (names != null) {
            for (final String n : names) {
                if (allows(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return this.all ? "*" : String.join(",", this.collections);
    }

    /** A collection outside the scope (or an invalid name); never says whether it exists. */
    public static final class CollectionNotAllowed extends Exception {
        private static final long serialVersionUID = 1L;

        public CollectionNotAllowed() {
            super("collection_not_allowed");
        }
    }
}
