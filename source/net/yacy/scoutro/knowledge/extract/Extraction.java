/*
 *  Extraction
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
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw, unresolved result of the extraction tiers for one document: mentions
 * and claims. Bounded: at most {@code maxClaims} claims are kept; duplicates
 * (same subject, predicate and object in the same tier) are dropped.
 */
public final class Extraction {

    private final int maxClaims;
    private final Map<String, Mention> mentions = new LinkedHashMap<>();
    private final List<Claim> claims = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private int tiers;
    private int dropped;
    private int invalidBlocks;

    public Extraction(final int maxClaims) {
        this.maxClaims = maxClaims;
    }

    public Mention mention(final String ref) {
        return this.mentions.get(ref);
    }

    public Mention add(final Mention m) {
        final Mention old = this.mentions.putIfAbsent(m.ref, m);
        return old == null ? m : old;
    }

    /** Adds a claim if its subject (and object) are known mentions and the limit is not reached. */
    public boolean add(final Claim c) {
        if (c == null || !this.mentions.containsKey(c.subject) || (c.object != null && !this.mentions.containsKey(c.object))) {
            return false;
        }
        if (c.object == null && (c.value == null || c.value.isEmpty())) {
            return false;
        }
        final String key = c.tier + "\u0000" + c.subject + "\u0000" + c.predicate + "\u0000" + (c.object != null ? "@" + c.object : c.value);
        if (!this.seen.add(key)) {
            return false;
        }
        if (this.claims.size() >= this.maxClaims) {
            this.dropped++;
            return false;
        }
        this.claims.add(c);
        return true;
    }

    public void ranTier(final int tier) {
        this.tiers |= 1 << (tier - 1);
    }

    public int tiers() {
        return this.tiers;
    }

    public void invalidBlock() {
        this.invalidBlocks++;
    }

    public int invalidBlocks() {
        return this.invalidBlocks;
    }

    public int droppedClaims() {
        return this.dropped;
    }

    public List<Mention> mentions() {
        return Collections.unmodifiableList(new ArrayList<>(this.mentions.values()));
    }

    public List<Claim> claims() {
        return Collections.unmodifiableList(this.claims);
    }

    /** Mentions of the given tier that are organisations or facilities (rule targets). */
    public List<Mention> subjects(final int tier) {
        final List<Mention> out = new ArrayList<>();
        for (final Mention m : this.mentions.values()) {
            if (m.tier == tier && (Vocabulary.ORGANIZATION.equals(m.type) || Vocabulary.FACILITY.equals(m.type))) {
                out.add(m);
            }
        }
        return out;
    }
}
