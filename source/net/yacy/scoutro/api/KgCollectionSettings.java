/*
 *  KgCollectionSettings
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

package net.yacy.scoutro.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.search.Switchboard;

/**
 * The knowledge graph settings of each collection (package 6.2), for the
 * administrator only:
 * <ul>
 * <li>{@code GET /v1/kg/collections}: every collection of the collection
 * catalog (YaCy's internal {@code robot_*} never) and every collection the
 * graph's settings or data name, each with whether the graph follows it, its
 * vocabulary (set, default of the vocabulary files, or none) and the graph's
 * status. A new collection of the catalog appears here by itself.</li>
 * <li>{@code PATCH /v1/kg/collections/{collection}} with {@code active}
 * (switch the collection on or off) and/or {@code vocabulary} (a vocabulary
 * name, {@code ""} for none, {@code null} for the default of the vocabulary
 * files).</li>
 * </ul>
 * Only the keys of that one collection change, with their existing meaning:
 * {@code scoutro.kg.collections} (the name added or removed, the other names
 * as they are; {@code *} stays {@code *}), {@code scoutro.kg.collections.inactive}
 * (switched off: not followed, also under {@code *}; its graph data are kept,
 * nothing is deleted) and {@code scoutro.kg.vocab.<collection>}. A change is
 * applied at once: the graph is reopened with the new settings, and the
 * reconcile of every start enqueues the pages a collection switched on already
 * has in the index (the existing backfill). Nothing is written while a backup,
 * restore or rebuild runs; no schema change.
 */
final class KgCollectionSettings {

    /** The settings store (YaCy's configuration). */
    interface Settings {
        String get(String key);

        void set(String key, String value);

        void remove(String key);

        Iterable<String> keys();
    }

    /** Applies the stored settings to the graph; true if a running graph was reopened. */
    interface Apply {
        boolean apply() throws KgException;
    }

    static final Set<String> FIELDS = Set.of("active", "vocabulary");
    private static final Object LOCK = new Object();

    private final Settings settings;
    private final CollectionCatalog catalog;
    private final Supplier<KgRuntime> runtime;
    private final Apply apply;

    KgCollectionSettings(final Settings settings, final CollectionCatalog catalog, final Supplier<KgRuntime> runtime, final Apply apply) {
        this.settings = settings;
        this.catalog = catalog;
        this.runtime = runtime;
        this.apply = apply;
    }

    /** The settings of the running peer: YaCy's configuration, the collection catalog, the running graph. */
    static KgCollectionSettings current() {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            return null;
        }
        return new KgCollectionSettings(new Settings() {
            @Override
            public String get(final String key) {
                return sb.getConfig(key, null);
            }

            @Override
            public void set(final String key, final String value) {
                sb.setConfig(key, value);
            }

            @Override
            public void remove(final String key) {
                sb.removeConfig(key);
                sb.saveConfigUI();
            }

            @Override
            public Iterable<String> keys() {
                final List<String> keys = new ArrayList<>();
                for (final java.util.Iterator<String> it = sb.configKeys(); it.hasNext();) {
                    final String k = it.next();
                    if (k != null && k.startsWith("scoutro.kg.")) {
                        keys.add(k);
                    }
                }
                return keys;
            }
        }, CollectionCatalog.current(), KgRuntime::current, KgRuntime::reopen);
    }

    private KgConfig config() {
        return KgConfig.read(this.settings::get, this.settings.keys());
    }

    /** {@code GET /v1/kg/collections}. */
    JSONObject list() {
        final KgConfig cfg = config();
        final KgVocabularies.Snapshot v = KgVocabularies.get();
        final Map<String, JSONObject> graph = graphStates();
        final Map<String, CollectionCatalog.Entry> entries = catalogEntries();
        final TreeMap<String, Boolean> names = new TreeMap<>(CollectionCatalog.ORDER);
        for (final String c : entries.keySet()) {
            names.put(c, Boolean.TRUE);
        }
        for (final String c : named(cfg, graph)) {
            names.putIfAbsent(c, Boolean.TRUE);
        }
        final JSONArray rows = new JSONArray();
        for (final String c : names.keySet()) {
            rows.put(row(c, entries.get(c), cfg, v, graph.get(c)));
        }
        final KgRuntime r = this.runtime.get();
        return Json.obj("enabled", cfg.enabled, "valid", cfg.valid(), "state", r == null ? "stopped" : r.state().name().toLowerCase(),
                "followAll", cfg.allCollections, "vocabularies", new JSONArray(new java.util.TreeSet<>(v.categories.vocabularies.keySet())),
                "collections", rows, "note", "switching a collection off keeps its graph data; switching it on picks up the pages it already"
                        + " has in the index; a change reopens the graph at once");
    }

    /** {@code PATCH /v1/kg/collections/{collection}}. */
    JSONObject update(final String collection, final JSONObject body) throws ApiException {
        if (collection == null || !CollectionCatalog.ID.matcher(collection).matches()) {
            throw new ApiException(400, "collection_id_invalid", "Invalid collection name (letters, digits, '_' and '-', at most 64).",
                    Json.obj("field", "collection"));
        }
        if (CollectionCatalog.isInternal(collection)) {
            throw new ApiException(400, "collection_reserved", "This is one of YaCy's internal collections; the knowledge graph never follows it.",
                    Json.obj("field", "collection"));
        }
        if (body == null || body.length() == 0) {
            throw ApiException.invalid("body", "Give 'active' and/or 'vocabulary'.");
        }
        for (final String k : body.keySet()) {
            if (!FIELDS.contains(k)) {
                throw ApiException.invalid(k, "Unknown field '" + k + "'. Allowed: active, vocabulary.");
            }
        }
        final Object a = body.opt("active");
        if (a != null && !(a instanceof Boolean)) {
            throw ApiException.invalid("active", "Field 'active' must be true or false.");
        }
        final Boolean active = (Boolean) a;
        final boolean setVocabulary = body.has("vocabulary");
        final Object vo = body.opt("vocabulary");
        if (setVocabulary && !(vo instanceof String) && vo != JSONObject.NULL) {
            throw ApiException.invalid("vocabulary", "Field 'vocabulary' must be a vocabulary name, \"\" for none or null for the default.");
        }
        final String vocabulary = vo instanceof String ? (String) vo : null;
        final KgVocabularies.Snapshot v = KgVocabularies.get();
        if (vocabulary != null && !vocabulary.isEmpty() && !v.categories.vocabularies.containsKey(vocabulary)) {
            throw new ApiException(400, "vocabulary_unknown", "Unknown vocabulary '" + clip(vocabulary) + "'. Known: "
                    + String.join(", ", new java.util.TreeSet<>(v.categories.vocabularies.keySet())) + ".", Json.obj("field", "vocabulary"));
        }
        synchronized (LOCK) {
            final KgConfig before = config();
            final Map<String, JSONObject> graph = graphStates();
            final Map<String, CollectionCatalog.Entry> entries = catalogEntries();
            if (!entries.containsKey(collection) && !named(before, graph).contains(collection)) {
                throw new ApiException(404, "collection_unknown", "Unknown collection: choose one of GET /scoutro/api/v1/kg/collections.");
            }
            final KgRuntime r = this.runtime.get();
            if (r != null && r.state() == KgRuntime.State.RUNNING) {
                try {
                    r.requireQuiet(); // before anything is written
                } catch (final KgException e) {
                    throw KnowledgeApi.toApi(e, KnowledgeRead.ADMIN_BASE + "/export");
                }
            }
            final Map<String, String> writes = new TreeMap<>();
            final List<String> removes = new ArrayList<>();
            if (active != null) {
                final String list = this.settings.get(KgConfig.COLLECTIONS);
                final String inactive = this.settings.get(KgConfig.INACTIVE_COLLECTIONS);
                final String newInactive = active ? without(inactive, collection) : with(inactive, collection);
                // under * every collection is followed: the list stays as it is, the switch is the list of those switched off
                final String newList = before.allCollections ? list : active ? with(list, collection) : without(list, collection);
                if (!same(list, newList)) {
                    writes.put(KgConfig.COLLECTIONS, newList);
                }
                if (!same(inactive, newInactive)) {
                    if (newInactive.isEmpty() && inactive != null) {
                        removes.add(KgConfig.INACTIVE_COLLECTIONS);
                    } else {
                        writes.put(KgConfig.INACTIVE_COLLECTIONS, newInactive);
                    }
                }
            }
            if (setVocabulary) {
                final String key = KgConfig.VOCAB_PREFIX + collection;
                final String old = this.settings.get(key);
                if (vocabulary == null) {
                    if (old != null) {
                        removes.add(key);
                    }
                } else if (!vocabulary.equals(old == null ? null : old.trim())) {
                    writes.put(key, vocabulary);
                }
            }
            for (final Map.Entry<String, String> w : writes.entrySet()) {
                this.settings.set(w.getKey(), w.getValue());
            }
            for (final String k : removes) {
                this.settings.remove(k);
            }
            final boolean changed = !writes.isEmpty() || !removes.isEmpty();
            final KgConfig after = config();
            boolean applied = false;
            String applyError = null;
            if (changed && after.enabled) {
                try {
                    applied = this.apply.apply();
                } catch (final KgException e) {
                    applyError = e.code(); // stored; it counts at the next start of the graph
                }
            }
            final JSONObject out = Json.obj("collection", row(collection, catalogEntries().get(collection), after, v, graphStates().get(collection)),
                    "changed", changed, "applied", applied, "backfill", changed && !before.follows(collection) && after.follows(collection),
                    "kept", changed && before.follows(collection) && after.holds(collection),
                    "reextract", changed && !before.extractionKey().equals(after.extractionKey()), "keys", new JSONArray(changedKeys(writes, removes)));
            Json.put(out, "applyError", applyError);
            return out;
        }
    }

    /** One collection: in the catalog or not, followed or switched off, its vocabulary and the graph's view of it. */
    private JSONObject row(final String c, final CollectionCatalog.Entry e, final KgConfig cfg, final KgVocabularies.Snapshot v,
            final JSONObject graph) {
        final String raw = this.settings.get(KgConfig.VOCAB_PREFIX + c);
        final String setting = raw == null ? null : raw.trim();
        final String dflt = v.categories.collections.get(c);
        final String vocabulary = setting != null ? (setting.isEmpty() ? null : setting) : dflt;
        final boolean known = vocabulary == null || v.categories.vocabularies.containsKey(vocabulary);
        final boolean active = cfg.follows(c);
        final boolean inactive = cfg.holds(c);
        final Object documents = graph == null ? null : graph.opt("documents");
        final String state = inactive ? "inactive" : !active ? "not_followed" : !known ? "unknown_vocabulary"
                : documents instanceof Number && ((Number) documents).longValue() == 0L ? "waiting" : "following";
        final JSONObject o = Json.obj("collection", c, "name", e == null ? c : e.name, "inCatalog", e != null, "indexDocuments",
                e == null ? null : e.documents, "active", active, "inactive", inactive, "followedBy", inactive ? "inactive"
                        : cfg.allCollections ? "all" : cfg.collections.contains(c) ? "list" : "none",
                "vocabulary", vocabulary, "vocabularySetting", setting, "defaultVocabulary", dflt, "vocabularySource", setting != null ? "setting"
                        : dflt != null ? "vocabulary_files" : "none", "vocabularyKnown", known, "graphDocuments", documents, "state", state);
        Json.put(o, "jobs", graph == null ? null : graph.opt("jobs"));
        Json.put(o, "llm", graph == null ? null : graph.opt("llm"));
        return o;
    }

    /** The catalog's collections without YaCy's internal ones; empty if the index cannot be read (the settings still count). */
    private Map<String, CollectionCatalog.Entry> catalogEntries() {
        final Map<String, CollectionCatalog.Entry> out = new TreeMap<>(CollectionCatalog.ORDER);
        if (this.catalog == null) {
            return out;
        }
        try {
            for (final CollectionCatalog.Entry e : this.catalog.entries(false)) {
                if (e.selectable()) {
                    out.put(e.id, e);
                }
            }
        } catch (final ApiException | RuntimeException e) {
            // entries(false) does not fail for the index; the named collections below still count
        }
        return out;
    }

    /** The collections the graph's settings or data name (never an internal one). */
    private static Set<String> named(final KgConfig cfg, final Map<String, JSONObject> graph) {
        final Set<String> out = new java.util.TreeSet<>(cfg.collections);
        out.addAll(cfg.inactiveCollections);
        out.addAll(cfg.vocabOverrides.keySet());
        out.addAll(graph.keySet());
        out.removeIf(c -> c == null || CollectionCatalog.isInternal(c) || !CollectionCatalog.ID.matcher(c).matches());
        return out;
    }

    /** The graph's view of each collection (documents, jobs, LLM tier), empty while it is not running. */
    private Map<String, JSONObject> graphStates() {
        final KgRuntime r = this.runtime.get();
        if (r == null || r.state() != KgRuntime.State.RUNNING) {
            return Map.of();
        }
        try {
            return r.collectionStates();
        } catch (final RuntimeException e) {
            return Map.of();
        }
    }

    // ------------------------------------------------------------- the lists

    private static List<String> names(final String raw) {
        final List<String> out = new ArrayList<>();
        if (raw != null) {
            for (final String part : raw.trim().split("[,\\s]+")) {
                if (!part.isEmpty() && !out.contains(part)) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    /** The list with the name added at its end; the other names as they are. */
    static String with(final String raw, final String name) {
        final List<String> l = names(raw);
        if (l.contains(name)) {
            return raw == null ? name : raw.trim();
        }
        l.add(name);
        return String.join(",", l);
    }

    /** The list without the name; the other names as they are. */
    static String without(final String raw, final String name) {
        final List<String> l = names(raw);
        if (!l.contains(name)) {
            return raw == null ? "" : raw.trim();
        }
        l.remove(name);
        return String.join(",", l);
    }

    private static boolean same(final String a, final String b) {
        return (a == null ? "" : a.trim()).equals(b == null ? "" : b.trim());
    }

    private static List<String> changedKeys(final Map<String, String> writes, final List<String> removes) {
        final List<String> out = new ArrayList<>(writes.keySet());
        out.addAll(removes);
        java.util.Collections.sort(out);
        return out;
    }

    private static String clip(final String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }
}
