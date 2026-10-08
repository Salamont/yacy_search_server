/*
 *  KnowledgePrompt
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

import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgJson;

/**
 * The active system prompt of the LLM tier (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.3, "Knowledge prompt"): the compiled-in
 * default ({@link LlmExtractor#SYSTEM_PROMPT}) or a custom version an administrator activated, stored in the graph's
 * {@code kg_meta} ({@link #META_ACTIVE}) together with a history of revisions, hashes, sources and times
 * ({@link #META_HISTORY}, never a prompt text). Scoutro is the source of truth and runs it; the schema stays compiled
 * in, because the validator depends on it.
 * <p>
 * The prompt the tier uses decides the prompt hash ({@link LlmExtractor#promptHash}) and with it the extractor identity
 * and the cache key: another prompt is never answered from the cache of this one. A new prompt reads no document
 * again by itself (only a new extractor {@code VERSION} does); pages are asked with it when they are new or change.
 * <p>
 * A version with {@link #source} {@code default} always means the compiled-in text of the running release, so a new
 * release's default applies at once; a {@code custom} version keeps its own text.
 */
public final class KnowledgePrompt {

    /** kg_meta key of the active version: revision, source, time and, for a custom version only, its text. */
    public static final String META_ACTIVE = "llm_prompt_active";
    /** kg_meta key of the history of activated versions: revision, hash, source, time; no prompt text. */
    public static final String META_HISTORY = "llm_prompt_history";

    public static final String SOURCE_DEFAULT = "default";
    public static final String SOURCE_CUSTOM = "custom";

    /** Bounds of a prompt text, in characters. */
    public static final int MIN_CHARS = 200, MAX_CHARS = 8000;
    /** Entries of the history that are kept, the newest last. */
    public static final int HISTORY_MAX = 50;

    /** Strings that look like a credential: never stored as a prompt (and so never in the history of the graph). */
    private static final Pattern SECRET_LIKE = Pattern.compile("(?i)\\bsk-[A-Za-z0-9_-]{16,}|\\bbearer\\s+[A-Za-z0-9._~+/=-]{16,}"
            + "|\\b(?:api[_-]?key|password|passwd|secret|access[_-]?token|auth[_-]?token)\\s*[:=]\\s*\\S{6,}"
            + "|-----BEGIN [A-Z ]*PRIVATE KEY-----|\\bAKIA[0-9A-Z]{16}\\b|\\bgh[pousr]_[A-Za-z0-9]{30,}\\b|\\bxox[abposr]-[A-Za-z0-9-]{10,}");

    /** The prompt text the tier sends. */
    public final String text;
    /** {@link #SOURCE_DEFAULT} or {@link #SOURCE_CUSTOM}. */
    public final String source;
    /** Counts the activations of this graph, 0 for the default that was never changed. */
    public final int revision;
    /** When this version was activated (ms), 0 for the default that was never changed. */
    public final long modifiedAt;
    /** {@link LlmExtractor#promptHash} of {@link #text}. */
    public final String hash;

    private KnowledgePrompt(final String text, final String source, final int revision, final long modifiedAt) {
        this.text = text;
        this.source = source;
        this.revision = revision;
        this.modifiedAt = modifiedAt;
        this.hash = LlmExtractor.promptHash(text);
    }

    /** The compiled-in default, never changed. */
    public static KnowledgePrompt defaults() {
        return new KnowledgePrompt(LlmExtractor.SYSTEM_PROMPT, SOURCE_DEFAULT, 0, 0L);
    }

    /** True when the tier uses another text than the compiled-in default. */
    public boolean differsFromDefault() {
        return !this.hash.equals(LlmExtractor.PROMPT_HASH);
    }

    /**
     * The version stored in {@link #META_ACTIVE}: null or unreadable is the default; a custom version whose text no
     * longer passes {@link #invalid} (e.g. written by hand) is not used either, the default runs.
     */
    public static KnowledgePrompt fromMeta(final String stored) {
        if (stored == null || stored.isEmpty()) {
            return defaults();
        }
        try {
            final JSONObject o = new JSONObject(stored);
            final int revision = Math.max(0, o.optInt("revision", 0));
            final long at = Math.max(0L, o.optLong("modifiedAt", 0L));
            if (SOURCE_CUSTOM.equals(o.optString("source"))) {
                final String text = o.optString("text", null);
                return text == null || invalid(text) != null ? defaults() : new KnowledgePrompt(text, SOURCE_CUSTOM, revision, at);
            }
            return new KnowledgePrompt(LlmExtractor.SYSTEM_PROMPT, SOURCE_DEFAULT, revision, at);
        } catch (final JSONException e) {
            return defaults();
        }
    }

    /** The value for {@link #META_ACTIVE}: the text only for a custom version (a default version is the compiled-in one). */
    public String toMeta() {
        final JSONObject o = KgJson.obj("revision", this.revision, "source", this.source, "modifiedAt", this.modifiedAt);
        if (SOURCE_CUSTOM.equals(this.source)) {
            KgJson.put(o, "text", this.text);
        }
        return o.toString();
    }

    /**
     * Why a draft cannot be a prompt, or null: {@code prompt_missing}, {@code prompt_too_short}, {@code prompt_too_long}
     * ({@link #MIN_CHARS} to {@link #MAX_CHARS} characters), {@code prompt_control_characters} (only tab and line breaks
     * besides printable text), {@code prompt_secret_like} (something that looks like a key, token or password).
     */
    public static String invalid(final String draft) {
        if (draft == null || draft.trim().isEmpty()) {
            return "prompt_missing";
        }
        if (draft.length() < MIN_CHARS) {
            return "prompt_too_short";
        }
        if (draft.length() > MAX_CHARS) {
            return "prompt_too_long";
        }
        for (int i = 0; i < draft.length(); i++) {
            final char c = draft.charAt(i);
            if (c < 0x20 && c != '\n' && c != '\r' && c != '\t' || c == 0x7f || c >= 0x80 && c < 0xa0 || c == ' ' || c == ' '
                    || Character.getType(c) == Character.SURROGATE && !validSurrogate(draft, i)) {
                return "prompt_control_characters";
            }
        }
        if (SECRET_LIKE.matcher(draft).find()) {
            return "prompt_secret_like";
        }
        return null;
    }

    private static boolean validSurrogate(final String s, final int i) {
        final char c = s.charAt(i);
        return Character.isHighSurrogate(c) ? i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))
                : i > 0 && Character.isHighSurrogate(s.charAt(i - 1));
    }

    /** The next version: {@code text} equal to the default is the default, anything else custom. */
    public static KnowledgePrompt next(final String text, final int revision, final long now) {
        return LlmExtractor.SYSTEM_PROMPT.equals(text) ? new KnowledgePrompt(LlmExtractor.SYSTEM_PROMPT, SOURCE_DEFAULT, revision, now)
                : new KnowledgePrompt(text, SOURCE_CUSTOM, revision, now);
    }

    /** The history with this version appended (bounded, the newest last); no text. */
    public String historyWith(final String stored) {
        JSONArray h;
        try {
            h = stored == null ? new JSONArray() : new JSONArray(stored);
        } catch (final JSONException e) {
            h = new JSONArray();
        }
        h.put(KgJson.obj("revision", this.revision, "hash", this.hash, "source", this.source, "activatedAt", this.modifiedAt));
        final JSONArray out = new JSONArray();
        for (int i = Math.max(0, h.length() - HISTORY_MAX); i < h.length(); i++) {
            out.put(h.opt(i));
        }
        return out.toString();
    }

    /** The highest revision of this version and the stored history: the next activation counts on from it. */
    public static int lastRevision(final KnowledgePrompt active, final String history) {
        int max = active.revision;
        try {
            final JSONArray h = history == null ? new JSONArray() : new JSONArray(history);
            for (int i = 0; i < h.length(); i++) {
                final JSONObject e = h.optJSONObject(i);
                if (e != null) {
                    max = Math.max(max, e.optInt("revision", 0));
                }
            }
        } catch (final JSONException e) {
            // an unreadable history starts again from the active revision
        }
        return max;
    }

    /** The metadata of this version (no text): what the status and an administration surface show. */
    public JSONObject json() {
        return KgJson.obj("activeVersion", this.revision, "activeHash", this.hash, "source", this.source,
                "modifiedAt", this.modifiedAt == 0L ? null : this.modifiedAt, "differsFromDefault", differsFromDefault());
    }
}
