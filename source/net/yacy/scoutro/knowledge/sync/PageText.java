/*
 *  PageText
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

package net.yacy.scoutro.knowledge.sync;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The page text the LLM tier reads (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 27.4). For a page longer than the tier's input,
 * blocks of navigation that recur on the other pages of the same host (a site menu, a footer) are moved behind the
 * page's own content, so the first characters of the input are the page's content instead of the same menu again
 * (for example a site menu of about 4,200 characters before every page's text). Nothing is removed: every line stays, verbatim,
 * and a block is read where the input reaches it. A page that fits the input is read unchanged.
 * <p>
 * A line recurs if it occurs (compared without case, Markdown marks and repeated spaces) on at least two of the
 * compared pages (on the only one, if the host has one other page); only runs of such lines of at least
 * {@link #MIN_BLOCK} characters move, so a single recurring line in the content (a service name, the operator's name)
 * stays where it is. Positions of quotes ({@code at}) are translated back to the stored text with {@link #origin}.
 */
final class PageText {

    /** Characters a run of recurring lines needs before it moves. */
    static final int MIN_BLOCK = 300;
    /** Other pages of the host compared. */
    static final int SIBLINGS = 3;

    /** The text the tier reads. */
    final String text;
    /** Characters moved behind the page's content. */
    final int moved;
    /** Segments: start in {@link #text}, start in the stored text, length. */
    private final int[][] segments;
    /** The blocks moved behind the content, in the stored text: {start, length}, in order; empty if none. */
    final int[][] ranges;
    /** End of the page's own content in {@link #text}: what follows is moved navigation. */
    final int contentEnd;

    private PageText(final String text, final int moved, final int[][] segments, final int[][] ranges, final int contentEnd) {
        this.text = text;
        this.moved = moved;
        this.segments = segments;
        this.ranges = ranges;
        this.contentEnd = contentEnd;
    }

    /** The stored text unchanged. */
    static PageText of(final String text) {
        final String t = text == null ? "" : text;
        return new PageText(t, 0, new int[][] {{0, 0, t.length()}}, new int[0][], t.length());
    }

    /**
     * The layout that moves exactly the given blocks of the stored text ({@link #ranges} of an earlier layout of the same
     * text): it needs neither the other pages of the host nor their current state, so a page is read the same way later.
     */
    static PageText fromRanges(final String text, final int[][] ranges) {
        if (text == null || ranges == null || ranges.length == 0) {
            return of(text);
        }
        final List<int[]> lines = lines(text);
        final boolean[] move = new boolean[lines.size()];
        for (int x = 0; x < move.length; x++) {
            final int[] l = lines.get(x);
            final int end = l[1] < text.length() ? l[1] + 1 : l[1];
            for (final int[] r : ranges) {
                if (l[0] >= r[0] && end <= r[0] + r[1]) {
                    move[x] = true;
                    break;
                }
            }
        }
        return build(text, lines, move);
    }

    /** The blocks of {@link #ranges} that start at or after {@code from} (the part of the page not read yet). */
    int[][] rangesFrom(final int from) {
        final List<int[]> out = new ArrayList<>();
        for (final int[] r : this.ranges) {
            if (r[0] >= from) {
                out.add(r);
            }
        }
        return out.toArray(new int[0][]);
    }

    /**
     * True if the characters {@code [start, end)} of {@link #text} are as contiguous in the stored text: no moved block
     * lies between them, so a quote there is verbatim in the stored page too.
     */
    boolean contiguous(final int start, final int end) {
        return end <= start || origin(end - 1) - origin(start) == end - 1 - start;
    }

    /** The position in the stored text of a position in {@link #text}. */
    int origin(final int at) {
        for (final int[] s : this.segments) {
            if (at >= s[0] && at < s[0] + s[2]) {
                return s[1] + (at - s[0]);
            }
        }
        return at;
    }

    /** Comparison form of a line: lower case, without Markdown marks, single spaces; empty for a blank line. */
    static String key(final String line) {
        return line.replaceAll("[*#_>`|]", " ").replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    /** The comparison forms of the lines of a text. */
    static Set<String> keys(final String text) {
        final Set<String> out = new HashSet<>();
        if (text != null) {
            for (final String line : text.split("\n")) {
                final String k = key(line);
                if (k.length() >= 3) {
                    out.add(k);
                }
            }
        }
        return out;
    }

    /** Lines that recur on the compared pages: on at least two, or on the only one. */
    static Set<String> recurring(final List<Set<String>> siblings) {
        final Set<String> out = new HashSet<>();
        if (siblings.isEmpty()) {
            return out;
        }
        final int need = siblings.size() >= 2 ? 2 : 1;
        final Map<String, Integer> n = new HashMap<>();
        for (final Set<String> s : siblings) {
            for (final String k : s) {
                n.merge(k, 1, Integer::sum);
            }
        }
        for (final Map.Entry<String, Integer> e : n.entrySet()) {
            if (e.getValue() >= need) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * The page as the tier reads it: unchanged if it fits {@code input} or no block of recurring lines of at least
     * {@link #MIN_BLOCK} characters exists; otherwise the page's own lines in their order, then the moved blocks in
     * theirs.
     */
    static PageText layout(final String text, final Set<String> recurring, final int input) {
        if (text == null || text.length() <= input || recurring.isEmpty()) {
            return of(text);
        }
        final List<int[]> lines = lines(text);
        final int n = lines.size();
        final boolean[] rec = new boolean[n];
        final boolean[] blank = new boolean[n];
        for (int i = 0; i < n; i++) {
            final String k = key(text.substring(lines.get(i)[0], lines.get(i)[1]));
            blank[i] = k.isEmpty();
            rec[i] = !blank[i] && recurring.contains(k);
        }
        // runs of recurring lines (blank lines inside a run belong to it), from a recurring line to a recurring line
        final boolean[] move = new boolean[n];
        int i = 0;
        while (i < n) {
            if (!rec[i]) {
                i++;
                continue;
            }
            int j = i;
            int last = i;
            while (j + 1 < n && (rec[j + 1] || blank[j + 1])) {
                j++;
                if (rec[j]) {
                    last = j;
                }
            }
            final int chars = lines.get(last)[1] - lines.get(i)[0];
            if (chars >= MIN_BLOCK) {
                for (int x = i; x <= last; x++) {
                    move[x] = true;
                }
            }
            i = last + 1;
        }
        return build(text, lines, move);
    }

    /** The lines of a text with their positions: {start, end}, a line ends before its '\n'. */
    private static List<int[]> lines(final String text) {
        final List<int[]> lines = new ArrayList<>();
        int start = 0;
        while (start <= text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = text.length();
            }
            lines.add(new int[] {start, end});
            if (end == text.length()) {
                break;
            }
            start = end + 1;
        }
        return lines;
    }

    /** The page's own lines in their order, then the moved ones in theirs; unchanged if nothing moves. */
    private static PageText build(final String text, final List<int[]> lines, final boolean[] move) {
        final int n = lines.size();
        final StringBuilder content = new StringBuilder();
        final List<int[]> segs = new ArrayList<>();
        final StringBuilder tail = new StringBuilder();
        final List<int[]> tailSegs = new ArrayList<>();
        int moved = 0;
        for (int x = 0; x < n; x++) {
            final int[] l = lines.get(x);
            final boolean hasNewline = l[1] < text.length();
            final int len = l[1] - l[0] + (hasNewline ? 1 : 0);
            if (move[x]) {
                tailSegs.add(new int[] {tail.length(), l[0], len});
                tail.append(text, l[0], l[0] + len);
                moved += len;
            } else {
                segs.add(new int[] {content.length(), l[0], len});
                content.append(text, l[0], l[0] + len);
            }
        }
        if (moved == 0) {
            return of(text);
        }
        if (content.length() > 0 && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }
        final int base = content.length();
        for (final int[] s : tailSegs) {
            segs.add(new int[] {base + s[0], s[1], s[2]});
        }
        content.append(tail);
        // the moved runs of lines in the stored text, for a later identical layout
        final List<int[]> runs = new ArrayList<>();
        for (int x = 0; x < n; x++) {
            if (!move[x]) {
                continue;
            }
            final int[] l = lines.get(x);
            final int end = l[1] < text.length() ? l[1] + 1 : l[1];
            final int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[0] + last[1] == l[0]) {
                last[1] = end - last[0];
            } else {
                runs.add(new int[] {l[0], end - l[0]});
            }
        }
        return new PageText(content.toString(), moved, segs.toArray(new int[0][]), runs.toArray(new int[0][]), base);
    }
}
