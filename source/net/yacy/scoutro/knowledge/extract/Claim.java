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
 * of the same document or a canonical literal value, with its source location
 * and, from version 2, the extractor's confidence (null: the default of its
 * kind, {@link #defaultConfidence}).
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
    /** 0..1, or null for the default of the kind. */
    public final Double confidence;

    public Claim(final String subject, final String predicate, final String object, final String value, final int tier,
            final int kind, final boolean hedged, final String locator, final String excerpt) {
        this(subject, predicate, object, value, tier, kind, hedged, locator, excerpt, null);
    }

    public Claim(final String subject, final String predicate, final String object, final String value, final int tier,
            final int kind, final boolean hedged, final String locator, final String excerpt, final Double confidence) {
        this.subject = subject;
        this.predicate = predicate;
        this.object = object;
        this.value = value;
        this.tier = tier;
        this.kind = kind;
        this.hedged = hedged;
        this.locator = locator == null || locator.length() <= 200 ? locator : locator.substring(0, 200);
        this.excerpt = excerpt;
        this.confidence = confidence == null || confidence.isNaN() ? null : Math.max(0.0, Math.min(1.0, confidence));
    }

    /**
     * The confidence of a claim without its own: structured data 0.9, page
     * metadata 0.8, deterministic rules 0.7, the language model 0.5; a hedged
     * statement half of that.
     */
    public static double defaultConfidence(final int kind, final boolean hedged) {
        final double c;
        switch (kind) {
            case KIND_JSONLD:
                c = 0.9;
                break;
            case KIND_METADATA:
                c = 0.8;
                break;
            case KIND_RULE:
                c = 0.7;
                break;
            default:
                c = 0.5;
        }
        return hedged ? c / 2.0 : c;
    }

    /** The confidence stored with the evidence. */
    public double effectiveConfidence() {
        return this.confidence != null ? (this.hedged ? Math.min(this.confidence, defaultConfidence(this.kind, true)) : this.confidence)
                : defaultConfidence(this.kind, this.hedged);
    }

    public boolean relation() {
        return this.object != null;
    }

    @Override
    public String toString() {
        return this.subject + " " + this.predicate + " " + (this.object != null ? "->" + this.object : "\"" + this.value + "\"");
    }
}
