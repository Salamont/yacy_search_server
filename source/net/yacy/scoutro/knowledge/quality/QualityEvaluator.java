/*
 *  QualityEvaluator
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

package net.yacy.scoutro.knowledge.quality;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Claim;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.Mention;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.quality.QualityCases.Case;
import net.yacy.scoutro.knowledge.quality.QualityCases.Ref;
import net.yacy.scoutro.knowledge.quality.QualityCases.Statement;
import net.yacy.scoutro.knowledge.quality.QualityCases.Value;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Compares what a reading of a reference page states with the page's expectations (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 4).
 * Only what the LLM tier adds is rated (tier 3, as the publication would take it). Tiers 1 and 2 are the same for every
 * model: an expected statement they make already is listed as given by the rules, not rated, and a forbidden one they
 * make is not the model's. Names are compared normalised ({@link Normalizers#key}): an id the model gives is never
 * compared. There is no score: the result lists what is correct, each deviation with the page's passage, the expectation
 * and the actual statement, and the statements neither expected nor forbidden (not rated).
 */
public final class QualityEvaluator {

    /** The rules of this class; a change makes earlier results stale. */
    public static final String VERSION = "1";

    /** Kinds of deviation. */
    public static final String MISSING = "missing", FORBIDDEN = "forbidden", WRONG_TYPE = "wrong_type",
            WRONG_RELATION = "wrong_relation", EVIDENCE = "evidence";
    /** Why a missing statement is missing, where the answers show it. */
    public static final String NOT_STATED = "not_stated", DROPPED_BY_VALIDATOR = "dropped_by_validator",
            DROPPED_BY_PREPARATION = "dropped_by_preparation", ANSWER_REFUSED = "answer_refused";
    /** Characters of the page around a passage shown with a deviation. */
    static final int CONTEXT_CHARS = 90;
    /** Statements neither expected nor forbidden listed per case (they are not rated). */
    static final int MAX_EXTRA = 20;

    /** One statement of the reading, with its names and its evidence. */
    static final class Actual {
        final String kind;
        final String subjectType, subjectName;
        final String predicate;
        final String objectType, objectName;
        final String value;
        final String excerpt;

        Actual(final String kind, final Mention s, final String predicate, final Mention o, final String value, final String excerpt) {
            this.kind = kind;
            this.subjectType = s.type;
            this.subjectName = s.name;
            this.predicate = predicate;
            this.objectType = o == null ? null : o.type;
            this.objectName = o == null ? null : o.name;
            this.value = value;
            this.excerpt = excerpt;
        }

        String label() {
            final String subject = this.subjectType + " “" + this.subjectName + "”";
            switch (this.kind) {
                case Statement.ENTITY:
                    return subject;
                case Statement.RELATION:
                    return subject + " " + this.predicate + " " + this.objectType + " “" + this.objectName + "”";
                default:
                    return subject + " " + this.predicate + " " + this.value;
            }
        }

        JSONObject json() {
            return KgJson.obj("statement", label(), "evidence", this.excerpt);
        }
    }

    /** What one request answered, as far as the evaluation needs it: the answer, its validation and the known entities. */
    public static final class Answer {
        final String raw;
        final LlmExtractor.Result result;
        final List<LlmExtractor.Known> known;

        public Answer(final String raw, final LlmExtractor.Result result, final List<LlmExtractor.Known> known) {
            this.raw = raw;
            this.result = result;
            this.known = known;
        }
    }

    private QualityEvaluator() {
    }

    /** The statements of tier 3 in the extraction the publication would take. */
    static List<Actual> actual(final Extraction ex) {
        return actual(ex, true);
    }

    /** The statements of tier 3 ({@code model}), or of tiers 1 and 2. */
    static List<Actual> actual(final Extraction ex, final boolean model) {
        final List<Actual> out = new ArrayList<>();
        if (ex == null) {
            return out;
        }
        for (final Claim c : ex.claims()) {
            if ((c.tier == LlmExtractor.TIER) != model) {
                continue;
            }
            final Mention s = ex.mention(c.subject);
            if (s == null || s.name == null) {
                continue;
            }
            if (Vocabulary.NAME.equals(c.predicate)) {
                out.add(new Actual(Statement.ENTITY, s, c.predicate, null, c.value, c.excerpt));
            } else if (c.object != null) {
                final Mention o = ex.mention(c.object);
                if (o != null && o.name != null) {
                    out.add(new Actual(Statement.RELATION, s, c.predicate, o, null, c.excerpt));
                }
            } else {
                out.add(new Actual(Statement.VALUE, s, c.predicate, null, c.value, c.excerpt));
            }
        }
        return out;
    }

    static boolean named(final Ref r, final String type, final String name) {
        if (r == null) {
            return false;
        }
        if (!QualityCases.ANY.equals(r.type) && !r.type.equals(type)) {
            return false;
        }
        if (r.names.isEmpty()) {
            return true; // anyone
        }
        final String key = Normalizers.key(name);
        for (final String n : r.names) {
            if (key != null && key.equals(Normalizers.key(n))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameName(final Ref r, final String name) {
        return r != null && named(new Ref(QualityCases.ANY, r.names.toArray(new String[0])), null, name);
    }

    /** True if the stated value is the one the statement means (or, for a forbidden "other amount", one it forbids). */
    static boolean valueMatches(final Value v, final String predicate, final String stated) {
        if (v == null || v.any) {
            return true;
        }
        if (v.code != null) {
            return v.code.equals(stated);
        }
        final JSONObject p = Vocabulary.PRICE.equals(predicate) || Vocabulary.SALARY.equals(predicate) ? json(stated) : null;
        if (p == null) {
            return false;
        }
        final BigDecimal amount = amount(p);
        if (v.except != null) {
            for (final String e : v.except) {
                if (amount != null && amount.compareTo(new BigDecimal(e)) == 0) {
                    return false;
                }
            }
            return true;
        }
        if (v.amount != null && (amount == null || amount.compareTo(new BigDecimal(v.amount)) != 0)) {
            return false;
        }
        if (v.currency != null && !v.currency.equals(p.optString("currency", null))) {
            return false;
        }
        if (v.unit != null && !v.unit.equals(p.optString("unit", null))) {
            return false;
        }
        return v.vat == null || v.vat.equals(p.optString("vat", null));
    }

    private static JSONObject json(final String s) {
        try {
            return s != null && s.startsWith("{") ? new JSONObject(s) : null;
        } catch (final org.json.JSONException e) {
            return null;
        }
    }

    /** The amount of a price (the lower one of a range). */
    private static BigDecimal amount(final JSONObject p) {
        for (final String f : List.of("amount", "min", "value")) {
            final String a = p.optString(f, null);
            if (a != null && !a.isEmpty()) {
                try {
                    return new BigDecimal(a);
                } catch (final NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    static boolean matches(final Statement s, final Actual a) {
        if (!s.kind.equals(a.kind) || !named(s.subject, a.subjectType, a.subjectName)) {
            return false;
        }
        switch (s.kind) {
            case Statement.ENTITY:
                return true;
            case Statement.RELATION:
                return s.predicate.equals(a.predicate) && named(s.object, a.objectType, a.objectName);
            default:
                return s.predicate.equals(a.predicate) && valueMatches(s.value, s.predicate, a.value);
        }
    }

    private static boolean contains(final String excerpt, final String passage) {
        final String e = Normalizers.key(excerpt);
        final String p = Normalizers.key(passage);
        return e != null && p != null && e.contains(p);
    }

    /** The page around a passage (or around the first of the names), as shown with a deviation; null if the page has none. */
    static String source(final String text, final String passage, final List<String> names) {
        int at = passage == null ? -1 : text.indexOf(passage);
        int len = passage == null ? 0 : passage.length();
        if (at < 0 && names != null) {
            for (final String n : names) {
                at = text.indexOf(n);
                if (at >= 0) {
                    len = n.length();
                    break;
                }
            }
        }
        if (at < 0) {
            return null;
        }
        final int from = Math.max(0, at - CONTEXT_CHARS);
        final int to = Math.min(text.length(), at + len + CONTEXT_CHARS);
        return (from > 0 ? "…" : "") + text.substring(from, to).replaceAll("\\s+", " ").trim() + (to < text.length() ? "…" : "");
    }

    /**
     * Where a missing statement went: stated by the model and dropped by the validator (with its reasons), accepted but
     * dropped while the publication was prepared, a refused answer, or not stated at all.
     */
    static JSONObject whereMissing(final Statement s, final List<Answer> answers, final Extraction ex) {
        for (final Answer a : answers) {
            if (a.result != null && a.result.refused != null) {
                return KgJson.obj("why", ANSWER_REFUSED, "refused", a.result.refused);
            }
        }
        for (final Answer a : answers) {
            final JSONObject raw = json(a.raw == null ? null : a.raw.trim());
            if (raw == null) {
                continue;
            }
            final Map<String, String[]> ids = ids(raw, a.known);
            final JSONObject stated = stated(s, raw, ids);
            if (stated == null) {
                continue;
            }
            final JSONObject accepted = a.result == null ? null : a.result.accepted;
            final boolean kept = accepted != null && stated(s, accepted, ids(accepted, a.known)) != null;
            if (kept) {
                // the validator kept it: the extraction did not take it (e.g. a job of a menu entry, jobs off)
                return KgJson.obj("why", DROPPED_BY_PREPARATION, "stated", stated);
            }
            final Set<String> reasons = new LinkedHashSet<>();
            if (a.result != null) {
                final String excerpt = LlmExtractor.redact(stated.optString("quote", null));
                for (final JSONObject sample : a.result.samples) {
                    if (excerpt != null && excerpt.equals(sample.optString("excerpt", null))) {
                        reasons.add(sample.optString("reason"));
                    }
                }
                if (reasons.isEmpty()) {
                    reasons.addAll(a.result.droppedInvalidByReason.keySet());
                    if (a.result.droppedUngrounded > 0) {
                        reasons.add("ungrounded");
                    }
                }
            }
            return KgJson.obj("why", DROPPED_BY_VALIDATOR, "stated", stated, "reasons", new JSONArray(reasons));
        }
        return KgJson.obj("why", NOT_STATED);
    }

    /** id -> {type, name} of the entities of an answer and the known entities it was asked with. */
    private static Map<String, String[]> ids(final JSONObject answer, final List<LlmExtractor.Known> known) {
        final Map<String, String[]> out = new HashMap<>();
        if (known != null) {
            for (final LlmExtractor.Known k : known) {
                out.put(k.id, new String[] {k.mention.type, k.mention.name});
            }
        }
        final JSONArray entities = answer.optJSONArray("entities");
        for (int i = 0; entities != null && i < entities.length(); i++) {
            final JSONObject e = entities.optJSONObject(i);
            if (e != null) {
                out.putIfAbsent(e.optString("id"), new String[] {e.optString("type"), e.optString("name")});
            }
        }
        return out;
    }

    /** The item of an answer that makes this statement (types not compared: a wrong type is reported on its own), or null. */
    private static JSONObject stated(final Statement s, final JSONObject answer, final Map<String, String[]> ids) {
        if (Statement.ENTITY.equals(s.kind)) {
            final JSONArray entities = answer.optJSONArray("entities");
            for (int i = 0; entities != null && i < entities.length(); i++) {
                final JSONObject e = entities.optJSONObject(i);
                if (e != null && sameName(s.subject, e.optString("name"))) {
                    return KgJson.obj("type", e.optString("type"), "name", e.optString("name"), "quote", e.optString("quote", null));
                }
            }
            return null;
        }
        final JSONArray items = answer.optJSONArray(Statement.RELATION.equals(s.kind) ? "claims" : "values");
        for (int i = 0; items != null && i < items.length(); i++) {
            final JSONObject c = items.optJSONObject(i);
            if (c == null || !s.predicate.equals(c.optString("predicate"))) {
                continue;
            }
            final String[] subject = ids.get(c.optString("subject"));
            if (subject == null || !sameName(s.subject, subject[1])) {
                continue;
            }
            if (Statement.RELATION.equals(s.kind)) {
                final String[] object = ids.get(c.optString("object"));
                if (object == null || !sameName(s.object, object[1]) && !s.object.names.isEmpty()) {
                    continue;
                }
                return KgJson.obj("subject", subject[1], "predicate", s.predicate, "object", object[1], "quote", c.optString("quote", null));
            }
            return KgJson.obj("subject", subject[1], "predicate", s.predicate, "quote", c.optString("quote", null));
        }
        return null;
    }

    private static JSONObject deviation(final String kind, final Statement s, final String role, final Actual a, final String source,
            final JSONObject where) {
        return KgJson.obj("kind", kind, role, KgJson.obj("statement", s.label(), "reason", s.reason, "evidence", s.evidence),
                "actual", a == null ? null : a.json(), "source", source, "where", where);
    }

    /**
     * The evaluation of one reference page.
     *
     * @param base    the page's tiers 1 and 2 (the same for every model)
     * @param ex      the extraction of the reading as the publication would take it; null if the model was not asked or did not answer
     * @param answers the answers of the requests in their order (for the statements the validator dropped)
     */
    public static JSONObject evaluate(final Case c, final Extraction base, final Extraction ex, final List<Answer> answers) {
        final List<Actual> actual = actual(ex);
        final List<Actual> rules = actual(base, false);
        final List<Actual> rated = new ArrayList<>();
        final JSONArray correct = new JSONArray();
        final JSONArray byRules = new JSONArray();
        final JSONArray deviations = new JSONArray();
        int missing = 0, forbidden = 0, wrongType = 0, wrongRelation = 0, evidence = 0, modelExpected = 0;
        for (final Statement s : c.expected) {
            final List<Actual> hits = new ArrayList<>();
            for (final Actual a : actual) {
                if (matches(s, a)) {
                    hits.add(a);
                }
            }
            if (hits.isEmpty()) {
                Actual given = null;
                for (final Actual a : rules) {
                    if (matches(s, a)) {
                        given = a;
                        break;
                    }
                }
                if (given != null) {
                    // tiers 1 and 2 state it already: the same for every model, not rated
                    byRules.put(KgJson.obj("expected", s.label(), "actual", given.json()));
                    continue;
                }
            }
            modelExpected++;
            if (!hits.isEmpty()) {
                rated.addAll(hits);
                Actual fitting = null;
                for (final Actual a : hits) {
                    if (s.evidence == null || contains(a.excerpt, s.evidence)) {
                        fitting = a;
                        break;
                    }
                }
                if (fitting != null) {
                    correct.put(KgJson.obj("expected", s.label(), "actual", fitting.json()));
                } else {
                    evidence++;
                    deviations.put(deviation(EVIDENCE, s, "expected", hits.get(0), source(c.text, s.evidence, s.subject.names), null));
                }
                continue;
            }
            // the statement under another type, or the two entities under another relation
            Actual other = null;
            for (final Actual a : actual) {
                if (Statement.ENTITY.equals(s.kind) && Statement.ENTITY.equals(a.kind) && sameName(s.subject, a.subjectName)) {
                    other = a;
                    break;
                }
                if (Statement.RELATION.equals(s.kind) && Statement.RELATION.equals(a.kind)
                        && (sameName(s.subject, a.subjectName) && sameName(s.object, a.objectName)
                                || sameName(s.subject, a.objectName) && sameName(s.object, a.subjectName))) {
                    other = a;
                    break;
                }
            }
            if (other != null) {
                rated.add(other);
                if (Statement.ENTITY.equals(s.kind)) {
                    wrongType++;
                    deviations.put(deviation(WRONG_TYPE, s, "expected", other, source(c.text, s.evidence, s.subject.names), null));
                } else {
                    wrongRelation++;
                    deviations.put(deviation(WRONG_RELATION, s, "expected", other, source(c.text, s.evidence, s.subject.names), null));
                }
                continue;
            }
            missing++;
            deviations.put(deviation(MISSING, s, "expected", null, source(c.text, s.evidence, s.subject.names), whereMissing(s, answers, ex)));
        }
        for (final Statement s : c.forbidden) {
            for (final Actual a : actual) {
                if (matches(s, a)) {
                    rated.add(a);
                    forbidden++;
                    deviations.put(deviation(FORBIDDEN, s, "forbidden", a, source(c.text, a.excerpt, s.subject.names), null));
                }
            }
        }
        final JSONArray extra = new JSONArray();
        int extras = 0;
        for (final Actual a : actual) {
            if (!rated.contains(a)) {
                extras++;
                if (extra.length() < MAX_EXTRA) {
                    extra.put(a.json());
                }
            }
        }
        return KgJson.obj("case", c.id, "evaluated", true, "statements", actual.size(),
                // an empty reading of a page with statements expected of the model is a deviation (each one missing), never a pass
                "emptyReading", actual.isEmpty() && modelExpected > 0,
                "counts", KgJson.obj("expected", modelExpected, "givenByRules", byRules.length(), "correct", correct.length(),
                        "missing", missing, "forbidden", forbidden, "wrongType", wrongType, "wrongRelation", wrongRelation,
                        "evidence", evidence, "notRated", extras),
                "correct", correct, "givenByRules", byRules, "deviations", deviations, "notRated", extra);
    }

    /** True if the evaluation of a case found any deviation. */
    public static boolean deviates(final JSONObject evaluation) {
        return evaluation != null && evaluation.optJSONArray("deviations") != null && evaluation.optJSONArray("deviations").length() > 0;
    }
}
