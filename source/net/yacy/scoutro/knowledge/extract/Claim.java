/*
 *  Claim
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

/**
 * One statement about a mention, as extracted: a relation to another mention
 * of the same document or a canonical literal value, with its source location.
 */
public final class Claim {

    public static final int KIND_JSONLD = 1;
    public static final int KIND_METADATA = 2;
    public static final int KIND_RULE = 3;
    public static final int KIND_LLM = 4;

    public final String subject;
    public final String predicate;
    /** Mention ref of a relation's object, or null. */
    public final String object;
    /** Canonical literal value, or null for a relation. */
    public final String value;
    public final int tier;
    public final int kind;
    public final boolean hedged;
    public final String locator;
    public final String excerpt;

    public Claim(final String subject, final String predicate, final String object, final String value, final int tier,
            final int kind, final boolean hedged, final String locator, final String excerpt) {
        this.subject = subject;
        this.predicate = predicate;
        this.object = object;
        this.value = value;
        this.tier = tier;
        this.kind = kind;
        this.hedged = hedged;
        this.locator = locator == null || locator.length() <= 200 ? locator : locator.substring(0, 200);
        this.excerpt = excerpt;
    }

    public boolean relation() {
        return this.object != null;
    }

    @Override
    public String toString() {
        return this.subject + " " + this.predicate + " " + (this.object != null ? "->" + this.object : "\"" + this.value + "\"");
    }
}
