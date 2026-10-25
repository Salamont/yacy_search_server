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
import java.util.List;
import java.util.Map;

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
 * model, and so is the baseline they set, fixed before any answer is looked at: an expected statement they make already
 * is listed as given by the rules and never expected of the model, and a model that states it again repeats it (counted
 * on its own, not rated). A forbidden one they make is not the model's. Names are compared normalised ({@link Normalizers#key}): an id the model gives is never
 * compared. There is no score: the result lists what is correct, what is allowed, each deviation with the page's passage,
 * the expectation and the actual statement, and the statements neither expected, allowed nor forbidden. Those are not rated:
 * the validator accepted them and the publication would take them, so a result with one is never a plain pass
 * ({@link #unrated}).
 */
public final class QualityEvaluator {

    /**
     * The rules of this class; a change makes earlier results stale. 3: the baseline of tiers 1 and 2 is fixed before the
     * answer is looked at (2 counted an expected statement the rules make as the model's whenever the model repeated it),
     * repetitions are counted on their own, and a missing statement is "not stated" only where every answer was checked.
     */
    public static final String VERSION = "3";

    /** Kinds of deviation. */
    public static final String MISSING = "missing", FORBIDDEN = "forbidden", WRONG_TYPE = "wrong_type",
            WRONG_RELATION = "wrong_relation", EVIDENCE = "evidence";
    /** Why a missing statement is missing, where the answers show it. */
    public static final String NOT_STATED = "not_stated", DROPPED_BY_VALIDATOR = "dropped_by_validator",
            DROPPED_BY_PREPARATION = "dropped_by_preparation", ANSWER_REFUSED = "answer_refused",
            STATED_OTHERWISE = "stated_otherwise", CAUSE_UNKNOWN = "cause_unknown";
    /** Characters of the page around a passage shown with a deviation. */
    static final int CONTEXT_CHARS = 90;
    /** Statements neither expected, allowed nor forbidden listed per case (they are not rated). */
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
        /** The model stopped at its output cap: the answer may lack what it would have stated. */
        final boolean truncated;

        public Answer(final String raw, final LlmExtractor.Result result, final List<LlmExtractor.Known> known) {
            this(raw, result, known, false);
        }

        public Answer(final String raw, final LlmExtractor.Result result, final List<LlmExtractor.Known> known, final boolean truncated) {
            this.raw = raw;
            this.result = result;
            this.known = known;
            this.truncated = truncated;
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
            if ((c.tier == LlmExtractor.TIER) != model || Vocabulary.TYPE_UNCONFIRMED.equals(c.predicate)) {
                continue; // 0.13.0: the mark of a place without a place in its context is the validator's, no statement of the model
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
     * Where a missing statement went, as far as the answers show it: a refused answer; stated by the model and dropped by
     * the validator (with the reason of exactly that item, where its sample names it); accepted but not taken while the
     * publication was prepared; stated otherwise (another name, type, assignment or evidence); or not stated. "Not stated"
     * is said only where every answer of the page was read and none was cut off at the output cap; otherwise the cause is
     * unknown, with what is missing. Each item found is checked on its own: the statement, the verbatim evidence on the
     * page, the names the validator requires in the evidence, the entity types and the assignment.
     */
    static JSONObject whereMissing(final Statement s, final String page, final List<Answer> answers, final Extraction ex) {
        if (answers == null || answers.isEmpty()) {
            return KgJson.obj("why", CAUSE_UNKNOWN, "incomplete", new JSONArray().put("no_answer"));
        }
        final JSONArray refused = new JSONArray();
        for (final Answer a : answers) {
            if (a.result != null && a.result.refused != null) {
                refused.put(a.result.refused);
            }
        }
        if (refused.length() > 0) {
            return KgJson.obj("why", ANSWER_REFUSED, "refused", refused.optString(0), "refusedAnswers", refused);
        }
        final JSONArray incomplete = new JSONArray();
        JSONObject otherwise = null;
        for (int i = 0; i < answers.size(); i++) {
            final Answer a = answers.get(i);
            final JSONObject raw = LlmExtractor.parsed(a.raw);
            if (raw == null) {
                incomplete.put("answer_" + (i + 1) + "_unreadable");
                continue;
            }
            if (a.truncated) {
                incomplete.put("answer_" + (i + 1) + "_truncated");
            }
            final Map<String, String[]> ids = ids(raw, a.known);
            final JSONObject stated = stated(s, raw, ids, false);
            if (stated == null) {
                if (otherwise == null) {
                    final JSONObject o = stated(s, raw, ids, true);
                    if (o != null) {
                        otherwise = KgJson.obj("answer", i + 1, "stated", o, "checks", checks(s, o, page, false));
                    }
                }
                continue;
            }
            final JSONObject checks = checks(s, stated, page, true);
            final JSONObject accepted = a.result == null ? null : a.result.accepted;
            final boolean kept = accepted != null && stated(s, accepted, ids(accepted, a.known), false) != null;
            if (kept) {
                // the validator kept it: the extraction did not take it (e.g. a job of a menu entry, jobs off)
                return KgJson.obj("why", DROPPED_BY_PREPARATION, "answer", i + 1, "stated", stated, "checks", checks);
            }
            final JSONObject drop = drop(stated, a.result);
            return KgJson.obj("why", DROPPED_BY_VALIDATOR, "answer", i + 1, "stated", stated, "checks", checks,
                    "reasons", drop.optJSONArray("reasons"), "reasonAttributed", drop.optBoolean("attributed"),
                    "drop", drop.optJSONObject("sample"), "answerReasons", drop.optJSONArray("answerReasons"),
                    "samplesComplete", drop.optBoolean("samplesComplete"));
        }
        if (otherwise != null) {
            KgJson.put(otherwise, "why", STATED_OTHERWISE);
            if (incomplete.length() > 0) {
                KgJson.put(otherwise, "incomplete", incomplete);
            }
            return otherwise;
        }
        if (incomplete.length() > 0) {
            return KgJson.obj("why", CAUSE_UNKNOWN, "incomplete", incomplete, "checkedAnswers", answers.size());
        }
        return KgJson.obj("why", NOT_STATED, "checkedAnswers", answers.size());
    }

    /**
     * The validator's reason for exactly this item: the sample of the same kind at the same position of its list in the
     * answer (the validator carries the position into each sample). Kind, predicate and quote are no identity: two items
     * may share them, and a sample keeps only the start of a quote. Exactly one sample at that position attributes its
     * reason; none (samples are bounded) or more than one leaves the reason not attributed, and the answer's reasons are
     * then listed apart, never as this item's.
     */
    private static JSONObject drop(final JSONObject stated, final LlmExtractor.Result r) {
        final JSONArray reasons = new JSONArray();
        final JSONArray answerReasons = new JSONArray();
        JSONObject sample = null;
        boolean complete = false;
        if (r != null) {
            final String item = stated.optString("item");
            final int index = stated.optInt("index", -1);
            final List<JSONObject> same = new ArrayList<>();
            for (final JSONObject x : r.samples) {
                if (index >= 0 && item.equals(x.optString("item")) && x.has("index") && x.optInt("index", -2) == index) {
                    same.add(x);
                }
            }
            if (same.size() == 1) {
                sample = same.get(0);
                reasons.put(sample.optString("reason"));
            }
            for (final String k : r.droppedInvalidByReason.keySet()) {
                answerReasons.put(k);
            }
            if (r.droppedUngrounded > 0) {
                answerReasons.put("ungrounded");
            }
            complete = r.samples.size() >= r.droppedInvalid + r.droppedUngrounded;
        }
        return KgJson.obj("reasons", reasons, "attributed", reasons.length() > 0, "sample", sample,
                "answerReasons", answerReasons, "samplesComplete", complete);
    }

    /**
     * The checks of an item the model stated, each on its own: the statement (predicate and, for a value, the expected
     * passage in its evidence), the evidence verbatim on the page, the names the validator requires in the evidence, the
     * types and the assignment to the expected entities.
     */
    static JSONObject checks(final Statement s, final JSONObject stated, final String page, final boolean assigned) {
        final String quote = stated.optString("quote", null);
        final JSONObject names = new JSONObject();
        for (final String f : List.of("name", "subject", "object")) {
            final String n = stated.optString(f, null);
            if (n != null && !n.isEmpty()) {
                KgJson.put(names, n, LlmExtractor.nameInQuote(quote, n));
            }
        }
        Boolean type = null;
        if (Statement.ENTITY.equals(s.kind)) {
            type = typed(s.subject, stated.optString("type", null));
        } else {
            type = typed(s.subject, stated.optString("subjectType", null))
                    && (s.object == null || typed(s.object, stated.optString("objectType", null)));
        }
        Boolean statement = Statement.VALUE.equals(s.kind) ? (s.evidence == null ? null : contains(quote, s.evidence))
                : Boolean.valueOf(s.predicate.equals(stated.optString("predicate", s.predicate)));
        return KgJson.obj("statement", statement, "quoteOnPage", LlmExtractor.quoteInText(page, quote), "namesInQuote", names,
                "type", type, "assignment", assigned, "evidencePassage", s.evidence == null ? null : contains(quote, s.evidence));
    }

    private static boolean typed(final Ref r, final String type) {
        return r == null || QualityCases.ANY.equals(r.type) || r.type.equals(type);
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

    /**
     * The item of an answer that makes this statement (types not compared: a wrong type is reported on its own), or null.
     * {@code otherwise}: an item that makes it in part instead (the expected passage in its evidence, or the predicate with
     * one of the two entities): the statement under another name or assignment.
     */
    private static JSONObject stated(final Statement s, final JSONObject answer, final Map<String, String[]> ids, final boolean otherwise) {
        if (Statement.ENTITY.equals(s.kind)) {
            final JSONArray entities = answer.optJSONArray("entities");
            for (int i = 0; entities != null && i < entities.length(); i++) {
                final JSONObject e = entities.optJSONObject(i);
                if (e == null) {
                    continue;
                }
                final boolean named = sameName(s.subject, e.optString("name"));
                final boolean partly = !named && s.evidence != null && contains(e.optString("quote", null), s.evidence);
                if (otherwise ? partly : named) {
                    return KgJson.obj("item", "entity", "index", i, "type", e.optString("type"), "name", e.optString("name"),
                            "quote", e.optString("quote", null));
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
            final boolean subjectNamed = subject != null && sameName(s.subject, subject[1]);
            if (Statement.RELATION.equals(s.kind)) {
                final String[] object = ids.get(c.optString("object"));
                final boolean objectNamed = object != null && (s.object.names.isEmpty() || sameName(s.object, object[1]));
                final boolean match = subjectNamed && objectNamed;
                final boolean partly = !match && (subjectNamed || objectNamed
                        || s.evidence != null && contains(c.optString("quote", null), s.evidence));
                if (otherwise ? partly : match) {
                    return KgJson.obj("item", "claim", "index", i, "subject", subject == null ? c.optString("subject") : subject[1],
                            "subjectType", subject == null ? null : subject[0], "predicate", s.predicate,
                            "object", object == null ? c.optString("object") : object[1], "objectType", object == null ? null : object[0],
                            "quote", c.optString("quote", null));
                }
                continue;
            }
            final boolean partly = !subjectNamed && s.evidence != null && contains(c.optString("quote", null), s.evidence);
            if (otherwise ? partly : subjectNamed) {
                return KgJson.obj("item", "value", "index", i, "subject", subject == null ? c.optString("subject") : subject[1],
                        "subjectType", subject == null ? null : subject[0], "predicate", s.predicate, "quote", c.optString("quote", null));
            }
        }
        return null;
    }

    private static JSONObject deviation(final String kind, final Statement s, final String role, final Actual a, final String source,
            final JSONObject where) {
        return KgJson.obj("kind", kind, role, KgJson.obj("statement", s.label(), "reason", s.reason, "evidence", s.evidence),
                "actual", a == null ? null : a.json(), "source", source, "where", where);
    }

    /** True if tiers 1 and 2 make this statement as well: the same entity, relation or value under the same types. */
    static boolean repeatsRule(final Actual a, final List<Actual> rules) {
        for (final Actual r : rules) {
            if (r.kind.equals(a.kind) && r.subjectType.equals(a.subjectType) && same(r.subjectName, a.subjectName)
                    && java.util.Objects.equals(r.predicate, a.predicate) && java.util.Objects.equals(r.objectType, a.objectType)
                    && same(r.objectName, a.objectName) && java.util.Objects.equals(r.value, a.value)) {
                return true;
            }
        }
        return false;
    }

    private static boolean same(final String x, final String y) {
        return x == null ? y == null : y != null && java.util.Objects.equals(Normalizers.key(x), Normalizers.key(y));
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
        final JSONArray repeats = new JSONArray();
        final JSONArray deviations = new JSONArray();
        int missing = 0, forbidden = 0, wrongType = 0, wrongRelation = 0, evidence = 0, modelExpected = 0;
        // the baseline: what tiers 1 and 2 state of the expected statements, fixed before the answer is looked at
        final List<Statement> baseline = new ArrayList<>();
        for (final Statement s : c.expected) {
            for (final Actual a : rules) {
                if (matches(s, a)) {
                    baseline.add(s);
                    byRules.put(KgJson.obj("expected", s.label(), "actual", a.json()));
                    break;
                }
            }
        }
        for (final Statement s : c.expected) {
            final List<Actual> hits = new ArrayList<>();
            for (final Actual a : actual) {
                if (matches(s, a)) {
                    hits.add(a);
                }
            }
            if (baseline.contains(s)) {
                // the rules state it: the same for every model, not rated; a model that states it again repeats it
                for (final Actual a : hits) {
                    rated.add(a);
                    repeats.put(KgJson.obj("statement", a.label(), "expected", s.label(), "evidence", a.excerpt));
                }
                continue;
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
            deviations.put(deviation(MISSING, s, "expected", null, source(c.text, s.evidence, s.subject.names),
                    whereMissing(s, c.text, answers, ex)));
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
        // what the page states as well: rated as allowed, never required
        final JSONArray allowed = new JSONArray();
        for (final Actual a : actual) {
            if (rated.contains(a)) {
                continue;
            }
            for (final Statement s : c.allowed) {
                if (matches(s, a)) {
                    rated.add(a);
                    allowed.put(KgJson.obj("allowed", s.label(), "actual", a.json()));
                    break;
                }
            }
        }
        // a statement the rules make as well (not an expected one): a repetition, not rated either
        for (final Actual a : actual) {
            if (!rated.contains(a) && repeatsRule(a, rules)) {
                rated.add(a);
                repeats.put(KgJson.obj("statement", a.label(), "evidence", a.excerpt));
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
                        "evidence", evidence, "allowed", allowed.length(), "notRated", extras, "repeatsRules", repeats.length()),
                "correct", correct, "givenByRules", byRules, "repeatsRules", repeats, "allowed", allowed, "deviations", deviations,
                "notRated", extra);
    }

    /**
     * The statements of a case's reading that are neither expected, allowed nor forbidden: accepted by the validator, taken
     * by the publication, and not rated. A result with one is no plain pass ({@link ModelQualityCheck#PASSED_UNRATED}).
     */
    public static int unrated(final JSONObject evaluation) {
        final JSONObject counts = evaluation == null ? null : evaluation.optJSONObject("counts");
        return counts == null ? 0 : counts.optInt("notRated");
    }

    /** True if the evaluation of a case found any deviation. */
    public static boolean deviates(final JSONObject evaluation) {
        return evaluation != null && evaluation.optJSONArray("deviations") != null && evaluation.optJSONArray("deviations").length() > 0;
    }
}
