/*
 *  KindHints
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * The collection-specific facility kinds the LLM tier may assign (open point
 * O2, docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.1): a small start vocabulary for the
 * Scoutro collections, replaced per collection by
 * {@code scoutro.kg.llm.kinds.<collection>}. Kinds are entity attributes
 * ({@code kg_entity.subkind}), so a new kind needs no schema change; two
 * facilities of different kinds never merge.
 */
public final class KindHints {

    public static final String KEY_PREFIX = "scoutro.kg.llm.kinds.";
    public static final Pattern KIND = Pattern.compile("^[a-z]{2,64}$");
    public static final int MAX_KINDS = 40;

    /** Start vocabulary; lower-case schema.org type names where one exists. */
    static final Map<String, Set<String>> START;
    static {
        final Map<String, Set<String>> m = new LinkedHashMap<>();
        m.put("edelsenior-web", set("nursinghome", "assistedliving", "outpatientcare", "adultdaycare", "seniorcenter", "hospital",
                "medicalclinic"));
        m.put("checkthecoach-web", set("professionalservice", "sportsactivitylocation", "exercisegym", "sportsclub"));
        m.put("stackfinder-web", set("professionalservice", "store"));
        m.put("bauteamcheck-web", set("homeandconstructionbusiness", "generalcontractor", "electrician", "plumber",
                "roofingcontractor", "housepainter", "hvacbusiness", "locksmith"));
        START = Collections.unmodifiableMap(m);
    }

    private KindHints() {}

    /** The start kinds of a collection (empty if it has none). */
    public static Set<String> start(final String collection) {
        final Set<String> s = START.get(collection);
        return s == null ? Collections.emptySet() : s;
    }

    private static Set<String> set(final String... kinds) {
        final Set<String> s = new TreeSet<>();
        Collections.addAll(s, kinds);
        return Collections.unmodifiableSet(s);
    }
}
