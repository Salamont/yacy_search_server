/*
 *  JsonLdBlocks
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

import java.util.Locale;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

/**
 * Validation of JSON-LD blocks from crawled pages (untrusted input), shared by
 * the capture in the parser and the tier-1 extractor
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 6.2): a block is kept only if it is valid
 * JSON with at most {@value #MAX_DEPTH} levels and {@value #MAX_NODES} nodes
 * and names a relevant {@code @type}. Depth and size are checked by a linear
 * scan before parsing, so deeply nested input cannot exhaust the stack of the
 * recursive JSON parser.
 */
public final class JsonLdBlocks {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 500;

    private JsonLdBlocks() {}

    /**
     * Parses a block if it is within the limits; null otherwise.
     *
     * @return a {@link JSONObject} or {@link JSONArray}
     */
    public static Object parse(final String block) {
        if (block == null || block.isEmpty() || !withinLimits(block)) {
            return null;
        }
        try {
            final Object v = new JSONTokener(stripComments(block)).nextValue();
            return v instanceof JSONObject || v instanceof JSONArray ? v : null;
        } catch (final org.json.JSONException | RuntimeException e) {
            return null;
        }
    }

    /** True if the block parses within the limits and contains a relevant type. */
    public static boolean accept(final String block) {
        final Object v = parse(block);
        return v != null && relevant(v, 0);
    }

    /** Linear scan: nesting depth and node count (values and containers) outside strings. */
    static boolean withinLimits(final String s) {
        int depth = 0;
        int nodes = 1;
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (inString) {
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    break;
                case '{':
                case '[':
                    if (++depth > MAX_DEPTH) {
                        return false;
                    }
                    break;
                case '}':
                case ']':
                    depth--;
                    break;
                case ',':
                case ':':
                    if (++nodes > MAX_NODES) {
                        return false;
                    }
                    break;
                default:
                    break;
            }
        }
        return depth == 0 && !inString;
    }

    /** HTML pages sometimes wrap JSON-LD in comment markers; they are removed. */
    private static String stripComments(final String s) {
        String t = s.trim();
        if (t.startsWith("<!--")) {
            t = t.substring(4);
        }
        if (t.endsWith("-->")) {
            t = t.substring(0, t.length() - 3);
        }
        return t;
    }

    private static boolean relevant(final Object v, final int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (v instanceof JSONArray) {
            final JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) {
                if (relevant(a.opt(i), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (!(v instanceof JSONObject)) {
            return false;
        }
        final JSONObject o = (JSONObject) v;
        for (final String t : types(o)) {
            if (Vocabulary.typeOf(t) != null || pageType(t)) {
                return true;
            }
        }
        for (final String key : o.keySet()) {
            final Object child = o.opt(key);
            if ((child instanceof JSONObject || child instanceof JSONArray) && relevant(child, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** The {@code @type} values of a node (a string or an array of strings). */
    public static java.util.List<String> types(final JSONObject o) {
        final java.util.List<String> out = new java.util.ArrayList<>(2);
        final Object t = o.opt("@type");
        if (t instanceof String) {
            out.add((String) t);
        } else if (t instanceof JSONArray) {
            final JSONArray a = (JSONArray) t;
            for (int i = 0; i < a.length() && i < 8; i++) {
                final Object x = a.opt(i);
                if (x instanceof String) {
                    out.add((String) x);
                }
            }
        }
        return out;
    }

    /** WebSite and WebPage types: they carry the publisher or provider of the site. */
    public static boolean pageType(final String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim();
        final int slash = Math.max(t.lastIndexOf('/'), t.lastIndexOf(':'));
        if (slash >= 0) {
            t = t.substring(slash + 1);
        }
        t = t.toLowerCase(Locale.ROOT);
        return "website".equals(t) || t.endsWith("page");
    }
}
