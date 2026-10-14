/*
 *  QualityDiagnosis
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

import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;

/**
 * What happened to the reading of one reference page, step by step (docs/SCOUTRO_LLM_QUALITY_CHECK.md, 4.3): what the
 * model answered, what the validator accepted and dropped (every dropped item with its reason), and what the preparation
 * of the publication took. Kept in the check's own result only, for the synthetic reference pages: the knowledge graph
 * never stores an answer. Bounded: an answer, the accepted part and the list of published statements are cut at fixed
 * sizes, and a run has one budget for all its pages; whatever is cut or left out is marked, so an incomplete diagnosis is
 * visible as such. No key: the candidate's key and anything that looks like a bearer token or an API key is replaced.
 */
final class QualityDiagnosis {

    /** The form of the diagnosis; another one is shown as such, never read as this one. */
    static final String VERSION = "1";
    /** Dropped items kept per answer: every one the validator can drop (three lists of at most MAX_ITEMS). */
    static final int SAMPLES = 3 * LlmExtractor.MAX_ITEMS;
    /** Characters of an answer, and of its accepted part, kept per request. */
    static final int MAX_ANSWER_CHARS = 8_000;
    /** Requests of a page with a diagnosis (a reference page fits one; the others are counted). */
    static final int MAX_ANSWERS = 4;
    /** Published statements listed per page. */
    static final int MAX_PUBLISHED = 40;
    /** Characters of answers and accepted parts kept in one run, all pages together. */
    static final int RUN_BUDGET = 320_000;

    private QualityDiagnosis() {
    }

    /** The answer text without a key: the candidate's own, bearer tokens and key-like strings replaced. */
    static String scrub(final String text, final String secret) {
        String m = text == null ? "" : text;
        if (secret != null && secret.length() >= 4) {
            m = m.replace(secret, "[key]");
        }
        return m.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [key]")
                .replaceAll("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{8,}", "[key]")
                .replaceAll("(?i)(\"?(?:api[_-]?key|authorization|token|password)\"?\\s*[:=]\\s*\"?)[^\"\\s,}]+", "$1[key]");
    }

    /** At most {@code max} characters of the text, within what is left of the run's budget. */
    private static String bounded(final String text, final int max, final int[] budget, final JSONArray incomplete, final String what) {
        final int room = Math.max(0, Math.min(max, budget[0]));
        if (text.length() <= room) {
            budget[0] -= text.length();
            return text;
        }
        incomplete.put(what + (room < max ? "_run_budget" : "_cut"));
        budget[0] -= room;
        return text.substring(0, room);
    }

    /**
     * The diagnosis of one page.
     *
     * @param answers the answers of its requests in their order
     * @param ex      the extraction of the accepted answers as the publication would take it (null if none)
     * @param secret  the candidate's key, never stored
     * @param budget  characters left for the run (shared by its pages, decreased here)
     */
    static JSONObject of(final List<QualityEvaluator.Answer> answers, final Extraction ex, final String secret, final int[] budget) {
        final JSONArray incomplete = new JSONArray();
        final JSONArray out = new JSONArray();
        for (int i = 0; i < answers.size() && i < MAX_ANSWERS; i++) {
            final QualityEvaluator.Answer a = answers.get(i);
            final String text = scrub(a.raw, secret);
            final String kept = bounded(text, MAX_ANSWER_CHARS, budget, incomplete, "answer_" + (i + 1));
            final JSONObject o = KgJson.obj("answer", i + 1, "chars", a.raw == null ? 0 : a.raw.length(), "text", a.raw == null ? null : kept,
                    "textComplete", a.raw == null || kept.length() == text.length(), "truncatedByModel", a.truncated,
                    "readable", LlmExtractor.parsed(a.raw) != null);
            final LlmExtractor.Result r = a.result;
            if (r != null) {
                final JSONArray drops = new JSONArray();
                for (final JSONObject s : r.samples) {
                    drops.put(s);
                }
                final boolean complete = r.samples.size() >= r.droppedInvalid + r.droppedUngrounded;
                if (!complete) {
                    incomplete.put("answer_" + (i + 1) + "_drops_cut");
                }
                String accepted = r.accepted == null ? null : scrub(r.accepted.toString(), secret);
                final int acceptedChars = accepted == null ? 0 : accepted.length();
                if (accepted != null) {
                    accepted = bounded(accepted, MAX_ANSWER_CHARS, budget, incomplete, "accepted_" + (i + 1));
                }
                KgJson.put(o, "refused", r.refused);
                KgJson.put(o, "validator", KgJson.obj(
                        "accepted", KgJson.obj("entities", r.entities, "claims", r.claims, "values", r.values),
                        "droppedInvalid", r.droppedInvalid, "droppedUngrounded", r.droppedUngrounded,
                        "byReason", new JSONObject(r.droppedInvalidByReason), "drops", drops, "dropsComplete", complete));
                KgJson.put(o, "accepted", accepted);
                KgJson.put(o, "acceptedComplete", accepted == null || accepted.length() == acceptedChars);
            } else {
                incomplete.put("answer_" + (i + 1) + "_not_validated");
            }
            out.put(o);
        }
        if (answers.size() > MAX_ANSWERS) {
            incomplete.put("answers_omitted_" + (answers.size() - MAX_ANSWERS));
        }
        final List<QualityEvaluator.Actual> published = QualityEvaluator.actual(ex);
        final JSONArray items = new JSONArray();
        for (final QualityEvaluator.Actual a : published) {
            if (items.length() >= MAX_PUBLISHED) {
                incomplete.put("published_cut");
                break;
            }
            items.put(a.json());
        }
        return KgJson.obj("version", VERSION, "complete", incomplete.length() == 0, "incomplete", incomplete,
                "requests", answers.size(), "answers", out,
                "published", KgJson.obj("statements", published.size(), "items", items, "prepared", ex != null));
    }
}
