/*
 *  Nace
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

package net.yacy.scoutro.knowledge.vocab;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The statistical classification of economic activities NACE Rev. 2.1
 * (Commission Delegated Regulation (EU) 2023/137), on which the German
 * WZ 2025 is built (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 23, part F): sections
 * (one letter), divisions (two digits), groups ({@code 43.2}) and classes
 * ({@code 43.22}), the finest level the graph uses. Read from
 * {@code defaults/scoutro/knowledge/nace-2.1.csv}; labels are the official
 * English titles (the German WZ 2025 titles are not bundled).
 */
public final class Nace {

    public static final String SCHEME = "nace";
    /** A section letter, a division, a group or a class. */
    public static final Pattern CODE = Pattern.compile("^(?:[A-V]|[0-9]{2}(?:\\.[0-9](?:[0-9])?)?)$");

    public static final int SECTION = 1;
    public static final int DIVISION = 2;
    public static final int GROUP = 3;
    public static final int CLASS = 4;

    /** One code of the classification. */
    public static final class Code {
        public final String code;
        public final int level;
        public final String section;
        public final String label;

        Code(final String code, final int level, final String section, final String label) {
            this.code = code;
            this.level = level;
            this.section = section;
            this.label = label;
        }
    }

    private final Map<String, Code> codes;

    private Nace(final Map<String, Code> codes) {
        this.codes = Collections.unmodifiableMap(codes);
    }

    static final Nace EMPTY = new Nace(new LinkedHashMap<>());

    /** Reads the CSV ({@code Section,Division,Group,Class,Activity}); a malformed file is an {@link IOException}. */
    public static Nace read(final InputStream in) throws IOException {
        final Map<String, Code> out = new LinkedHashMap<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            final String header = r.readLine();
            if (header == null || !header.replace("﻿", "").trim().equals("Section,Division,Group,Class,Activity")) {
                throw new IOException("unexpected NACE header");
            }
            String section = null;
            String line;
            int n = 1;
            while ((line = r.readLine()) != null) {
                n++;
                if (line.isEmpty()) {
                    continue;
                }
                final List<String> f = csv(line);
                if (f.size() != 5) {
                    throw new IOException("NACE line " + n + ": expected 5 fields");
                }
                final String label = f.get(4).trim();
                final Code c;
                if (!f.get(0).isEmpty()) {
                    section = f.get(0).trim();
                    c = new Code(section, SECTION, section, label);
                } else if (!f.get(1).isEmpty()) {
                    c = new Code(f.get(1).trim(), DIVISION, section, label);
                } else if (!f.get(2).isEmpty()) {
                    c = new Code(f.get(2).trim(), GROUP, section, label);
                } else if (!f.get(3).isEmpty()) {
                    c = new Code(f.get(3).trim(), CLASS, section, label);
                } else {
                    throw new IOException("NACE line " + n + ": no code");
                }
                if (section == null || !CODE.matcher(c.code).matches() || label.isEmpty()) {
                    throw new IOException("NACE line " + n + ": invalid code '" + c.code + "'");
                }
                out.put(c.code, c);
            }
        }
        if (out.size() < 100) {
            throw new IOException("NACE file has only " + out.size() + " codes");
        }
        return new Nace(out);
    }

    /** Minimal CSV: fields separated by commas, a field in double quotes may contain commas and doubled quotes. */
    private static List<String> csv(final String line) {
        final List<String> out = new ArrayList<>();
        final StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char ch = line.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        sb.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    sb.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                out.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        out.add(sb.toString());
        return out;
    }

    public int size() {
        return this.codes.size();
    }

    public Code get(final String code) {
        return code == null ? null : this.codes.get(code);
    }

    public boolean valid(final String code) {
        return get(code) != null;
    }

    public String label(final String code) {
        final Code c = get(code);
        return c == null ? null : c.label;
    }

    /** The code one level up ({@code 43.22 -> 43.2 -> 43 -> F}); null for a section or an unknown code. */
    public String parent(final String code) {
        final Code c = get(code);
        if (c == null) {
            return null;
        }
        switch (c.level) {
            case CLASS:
                return code.substring(0, 4);
            case GROUP:
                return code.substring(0, 2);
            case DIVISION:
                return c.section;
            default:
                return null;
        }
    }

    /** True if {@code ancestor} is {@code code} or one of its parents. */
    public boolean within(final String code, final String ancestor) {
        for (String c = code; c != null; c = parent(c)) {
            if (c.equals(ancestor)) {
                return true;
            }
        }
        return false;
    }

    /** The finest code that contains both (the safe common level), or null if they share no section. */
    public String common(final String a, final String b) {
        for (String c = a; c != null; c = parent(c)) {
            if (within(b, c)) {
                return c;
            }
        }
        return null;
    }

    /** The path from the section down to {@code code}, e.g. [F, 43, 43.2, 43.22]. */
    public List<String> path(final String code) {
        final List<String> out = new ArrayList<>();
        for (String c = code; c != null; c = parent(c)) {
            out.add(0, c);
        }
        return out;
    }
}
