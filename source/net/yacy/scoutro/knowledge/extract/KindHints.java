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

import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.vocab.Categories;

/**
 * The facility kinds the LLM tier may assign (open point O2,
 * docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 4.1). They come with the vocabulary profile
 * of a collection ({@code llm_kinds} of a vocabulary in the vocabulary files,
 * {@link Categories#llmKinds}) and are replaced per collection by
 * {@code scoutro.kg.llm.kinds.<collection>}; no collection has kinds because
 * of its name. Kinds are entity attributes ({@code kg_entity.subkind}), so a
 * new kind needs no schema change; two facilities of different kinds never
 * merge.
 */
public final class KindHints {

    public static final String KEY_PREFIX = "scoutro.kg.llm.kinds.";
    public static final Pattern KIND = Categories.LLM_KIND;
    public static final int MAX_KINDS = Categories.MAX_LLM_KINDS;

    private KindHints() {}
}
