/*
 *  CollectionMigration
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

package net.yacy.scoutro.knowledge;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.store.KgBackup;
import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;

/**
 * The one upgrade step that ended the collection defaults built into Scoutro.
 * Before it, the shipped {@code categories.json} gave four collections of one
 * installation a vocabulary, and the facility kinds of the LLM tier were
 * chosen by the same four names. Now an operator names the collections and
 * gives a collection a vocabulary profile; no collection has one by itself.
 * An installation that may rely on the former defaults keeps them as its own
 * configuration, without any re-extraction:
 * <ul>
 * <li>the former map goes into
 * {@code DATA/SCOUTRO/knowledge/vocabulary/000-migrated-collections.json}
 * ({@link #FILE}), without a collection an operator file already maps: the
 * merged map, the vocabulary version and with them the extractor identity of
 * every page stay the same;</li>
 * <li>where the facility kinds a collection's vocabulary profile gives differ
 * from those it had by its name (its vocabulary was set by the operator, or
 * an operator file maps it), the former kinds are fixed for it in the same
 * file ({@code collection_llm_kinds}): prompts and cache keys of the LLM tier
 * stay the same;</li>
 * <li>{@link KgConfig#COLLECTION_PROFILES_MIGRATION} names the four
 * collections: their per-collection settings are read as before, also while
 * they are not followed (they are part of the extraction identity).</li>
 * </ul>
 * An installation counts as existing when its graph directory holds a graph
 * or a backup, or when one of its {@code scoutro.kg.*} settings names one of
 * the four collections. Any other installation is new and gets nothing but
 * the marker, with an empty value. The marker makes the step run once; the
 * step runs only where the settings can be written (the running peer), and it
 * never changes or deletes a file of the operator.
 */
final class CollectionMigration {

    /** The file the step writes into the operator's vocabulary directory (read like any other vocabulary file). */
    static final String FILE = "000-migrated-collections.json";

    /**
     * The collection map that was built into Scoutro ({@code collections} of
     * {@code defaults/scoutro/knowledge/categories.json}); kept only to carry it over once.
     */
    static final Map<String, String> FORMER_VOCABULARIES;
    /** The facility kinds the same collections had by their names (the former {@code KindHints.START}); kept only to carry them over. */
    static final Map<String, Set<String>> FORMER_KINDS;

    static {
        final Map<String, String> v = new TreeMap<>();
        v.put("edelsenior-web", "care");
        v.put("checkthecoach-web", "coaching");
        v.put("stackfinder-web", "software");
        v.put("bauteamcheck-web", "construction");
        FORMER_VOCABULARIES = Collections.unmodifiableMap(v);
        final Map<String, Set<String>> k = new TreeMap<>();
        k.put("edelsenior-web", set("nursinghome", "assistedliving", "outpatientcare", "adultdaycare", "seniorcenter", "hospital",
                "medicalclinic"));
        k.put("checkthecoach-web", set("professionalservice", "sportsactivitylocation", "exercisegym", "sportsclub"));
        k.put("stackfinder-web", set("professionalservice", "store"));
        k.put("bauteamcheck-web", set("homeandconstructionbusiness", "generalcontractor", "electrician", "plumber", "roofingcontractor",
                "housepainter", "hvacbusiness", "locksmith"));
        FORMER_KINDS = Collections.unmodifiableMap(k);
    }

    private static final Pattern COLLECTION = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    /** What the step found and did. */
    static final class Result {
        /** False for a new installation: nothing was carried over. */
        final boolean existing;
        /** Why the installation counts as existing ({@code graph}, {@code backup} or the setting that names a former collection). */
        final String evidence;
        /** True if a graph exists; its stored extractor identity is compared after the start. */
        final boolean graph;
        /** The map written into {@link #FILE}. */
        final Map<String, String> carried;
        /** The facility kinds fixed in {@link #FILE}. */
        final Map<String, Set<String>> fixedKinds;
        /** True if {@link #FILE} was written now: the vocabularies in force are read again. */
        final boolean written;

        Result(final boolean existing, final String evidence, final boolean graph, final Map<String, String> carried,
                final Map<String, Set<String>> fixedKinds, final boolean written) {
            this.existing = existing;
            this.evidence = evidence;
            this.graph = graph;
            this.carried = Collections.unmodifiableMap(carried);
            this.fixedKinds = Collections.unmodifiableMap(fixedKinds);
            this.written = written;
        }

        /** One line for the log and the event of the graph. */
        String describe() {
            if (!this.existing) {
                return "new installation: no collection defaults to carry over";
            }
            return "former collection defaults carried over (" + this.evidence + "): vocabularies " + this.carried + (this.fixedKinds.isEmpty()
                    ? "" : ", facility kinds kept for " + this.fixedKinds.keySet()) + " in vocabulary/" + FILE;
        }
    }

    private CollectionMigration() {}

    /**
     * Runs the step unless it ran before.
     *
     * @param dataRoot the YaCy data root (the parent of DATA)
     * @param config   the settings
     * @param settings writes one setting (the marker); null: the step does not run
     * @param keys     the {@code scoutro.kg.*} keys that are set
     * @return what was done; null if the step ran before or does not run here
     * @throws IOException if the file could not be written (nothing else was written; the step runs again at the next start)
     */
    static Result run(final File dataRoot, final Function<String, String> config, final BiConsumer<String, String> settings,
            final Iterable<String> keys) throws IOException {
        if (settings == null || config.apply(KgConfig.COLLECTION_PROFILES_MIGRATION) != null) {
            return null;
        }
        final KgPaths paths = new KgPaths(dataRoot);
        final boolean graph = paths.db.isFile();
        final String evidence = graph ? "graph" : !KgBackup.list(paths.backup).isEmpty() ? "backup" : namedBySettings(config, keys);
        if (evidence == null) {
            settings.accept(KgConfig.COLLECTION_PROFILES_MIGRATION, "");
            return new Result(false, null, false, Map.of(), Map.of(), false);
        }
        final File dir = new File(paths.dir, KgVocabularies.OVERRIDES);
        final File file = new File(dir, FILE);
        Map<String, String> carried = Map.of();
        Map<String, Set<String>> fixed = Map.of();
        boolean written = false;
        // the file exists only complete: a step that wrote it and could not record the marker only records it now
        if (!file.exists()) {
            final Set<String> mapped = new TreeSet<>();
            for (final File f : KgVocabularies.overrideFiles(dir)) {
                if (!f.getName().equals(FILE)) {
                    mapped.addAll(Categories.mappedCollections(KgVocabularies.readOverride(f)));
                }
            }
            carried = new TreeMap<>(FORMER_VOCABULARIES);
            carried.keySet().removeAll(mapped);
            fixed = fixedKinds(KgVocabularies.read(KgVocabularies.defaults(), dir).categories, carried, config, keys);
            if (!carried.isEmpty() || !fixed.isEmpty()) {
                write(dir, file, carried, fixed);
                written = true;
            }
        }
        settings.accept(KgConfig.COLLECTION_PROFILES_MIGRATION, String.join(",", FORMER_VOCABULARIES.keySet()));
        return new Result(true, evidence, graph, carried, fixed, written);
    }

    /** The first {@code scoutro.kg.*} setting that names a former collection (as the end of its key or in its value), else null. */
    static String namedBySettings(final Function<String, String> config, final Iterable<String> keys) {
        if (keys == null) {
            return null;
        }
        for (final String k : keys) {
            if (k == null || !k.startsWith("scoutro.kg.") || k.equals(KgConfig.COLLECTION_PROFILES_MIGRATION)) {
                continue;
            }
            final String v = config.apply(k);
            for (final String c : FORMER_VOCABULARIES.keySet()) {
                if (k.endsWith("." + c)) {
                    return "setting " + k;
                }
                if (v != null) {
                    for (final String part : v.trim().split("[,\\s]+")) {
                        if (part.equals(c)) {
                            return "setting " + k;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * The collections whose facility kinds would change: those the former defaults gave by the name, compared with the kinds of
     * every vocabulary the collection may have after the step (its {@code scoutro.kg.vocab.<collection>}, its map entry). A
     * collection an operator file fixes kinds for keeps the operator's kinds.
     */
    static Map<String, Set<String>> fixedKinds(final Categories before, final Map<String, String> carried,
            final Function<String, String> config, final Iterable<String> keys) {
        final Set<String> candidates = new TreeSet<>(FORMER_VOCABULARIES.keySet());
        candidates.addAll(before.collections.keySet());
        if (keys != null) {
            for (final String k : keys) {
                if (k != null && k.startsWith(KgConfig.VOCAB_PREFIX) && COLLECTION.matcher(k.substring(KgConfig.VOCAB_PREFIX.length())).matches()) {
                    candidates.add(k.substring(KgConfig.VOCAB_PREFIX.length()));
                }
            }
        }
        final Map<String, Set<String>> out = new TreeMap<>();
        for (final String c : candidates) {
            if (before.collectionLlmKinds.containsKey(c)) {
                continue;
            }
            final Set<String> former = FORMER_KINDS.getOrDefault(c, Collections.emptySet());
            // the map after the step: an operator file's entry, else the carried one (dropped like any unknown vocabulary)
            String mapped = before.collections.get(c);
            if (mapped == null && carried.containsKey(c) && before.vocabularies.containsKey(carried.get(c))) {
                mapped = carried.get(c);
            }
            final List<String> possible = new ArrayList<>();
            possible.add(mapped);
            final String setting = config.apply(KgConfig.VOCAB_PREFIX + c);
            if (setting != null) {
                possible.add(setting.trim().isEmpty() ? null : setting.trim());
            }
            for (final String v : possible) {
                if (!before.llmKinds(v).equals(former)) {
                    out.put(c, former);
                    break;
                }
            }
        }
        return out;
    }

    /** Writes the file at once (a temporary file, then moved into place), with sorted keys. */
    private static void write(final File dir, final File file, final Map<String, String> carried, final Map<String, Set<String>> fixed)
            throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n \"schema\": \"scoutro.kg.categories\",\n \"version\": \"1\",\n \"note\": ").append(JSONObject.quote(
                "Written once by Scoutro when it stopped shipping default collections: the vocabulary each of these collections had by"
                        + " default, and where its vocabulary profile would give other facility kinds of the LLM tier, the kinds it had."
                        + " This file belongs to this installation: change or delete entries like in any other vocabulary file (a"
                        + " changed map takes effect at the next start and re-extracts the pages concerned)."));
        sb.append(",\n \"collections\": {");
        String sep = "\n";
        for (final Map.Entry<String, String> e : carried.entrySet()) {
            sb.append(sep).append("  ").append(JSONObject.quote(e.getKey())).append(": ").append(JSONObject.quote(e.getValue()));
            sep = ",\n";
        }
        sb.append(carried.isEmpty() ? "}" : "\n }");
        if (!fixed.isEmpty()) {
            sb.append(",\n \"collection_llm_kinds\": {");
            sep = "\n";
            for (final Map.Entry<String, Set<String>> e : fixed.entrySet()) {
                sb.append(sep).append("  ").append(JSONObject.quote(e.getKey())).append(": [");
                String s = "";
                for (final String k : e.getValue()) {
                    sb.append(s).append(JSONObject.quote(k));
                    s = ", ";
                }
                sb.append(']');
                sep = ",\n";
            }
            sb.append("\n }");
        }
        sb.append("\n}\n");
        Files.createDirectories(dir.toPath());
        final File tmp = new File(dir, FILE + ".tmp");
        Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static Set<String> set(final String... kinds) {
        final Set<String> s = new TreeSet<>();
        Collections.addAll(s, kinds);
        return Collections.unmodifiableSet(s);
    }
}
