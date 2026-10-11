/*
 *  AccessAudit
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

package net.yacy.scoutro.access;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Log of sign-ins and administrative actions of people: a ring buffer of the
 * newest {@value #CAPACITY} entries, appended to a JSON-lines file and
 * compacted when it grows (like the agent audit). An entry names the acting
 * identity, the action, the target, the result and a reason code. It never
 * contains passwords, tokens, cookies, document contents or search texts.
 */
public final class AccessAudit {

    public static final String AUDIT_FILE = "scoutro-user-audit.jsonl";
    static final int CAPACITY = 20_000;
    static final int MAX_FIELD = 200;

    private final Path file;
    private final LongSupplier clock;
    private final Deque<JSONObject> entries = new ArrayDeque<>();
    private int appendedSinceCompaction;

    public AccessAudit(final File settingsDir, final LongSupplier clock) {
        this.file = new File(settingsDir, AUDIT_FILE).toPath();
        this.clock = clock;
        load();
    }

    /**
     * @param actor acting identity, e.g. {@code anna}, {@code builtin:admin}, {@code digest:admin}, {@code local}, or the attempted name of a failed sign-in
     * @param action e.g. {@code auth.login}, {@code user.created}, {@code api.crawls.start}
     * @param target account name, collection, id or route (may be empty)
     * @param result {@code ok}, {@code denied} or {@code failed}
     * @param reason stable reason code or empty
     * @param client client address
     */
    public synchronized void record(final String actor, final String action, final String target, final String result,
            final String reason, final String client) {
        final JSONObject e = Js.obj("ts", this.clock.getAsLong(), "actor", clip(actor), "action", clip(action),
                "target", clip(target), "result", clip(result), "reason", clip(reason), "client", clip(client));
        this.entries.addLast(e);
        while (this.entries.size() > CAPACITY) {
            this.entries.removeFirst();
        }
        append(e);
    }

    /** Newest first, optionally only entries whose actor or target equals {@code who}. */
    public synchronized List<JSONObject> recent(final String who, final int limit) {
        final List<JSONObject> out = new ArrayList<>();
        final Iterator<JSONObject> it = this.entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            final JSONObject e = it.next();
            if (who != null && !who.isEmpty() && !who.equals(e.optString("actor")) && !who.equals(e.optString("target"))) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static String clip(final String s) {
        if (s == null) {
            return "";
        }
        final String t = s.replaceAll("\\p{Cntrl}", " ");
        return t.length() > MAX_FIELD ? t.substring(0, MAX_FIELD) : t;
    }

    private void append(final JSONObject e) {
        try {
            try (OutputStream out = Files.newOutputStream(this.file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write((e.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            Js.ownerOnly(this.file);
            if (++this.appendedSinceCompaction > CAPACITY) {
                compact();
            }
        } catch (final IOException ex) {
            // the in-memory log stays complete; the file is best effort
        }
    }

    private void compact() throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (final JSONObject e : this.entries) {
            sb.append(e.toString()).append('\n');
        }
        Js.writeAtomically(this.file, sb.toString().getBytes(StandardCharsets.UTF_8));
        this.appendedSinceCompaction = 0;
    }

    private void load() {
        if (!Files.isRegularFile(this.file)) {
            return;
        }
        try (BufferedReader r = Files.newBufferedReader(this.file, StandardCharsets.UTF_8)) {
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null) {
                lines++;
                try {
                    this.entries.addLast(new JSONObject(line));
                } catch (final JSONException e) {
                    continue; // a torn last line after a crash
                }
                while (this.entries.size() > CAPACITY) {
                    this.entries.removeFirst();
                }
            }
            this.appendedSinceCompaction = lines;
        } catch (final IOException e) {
            // start with an empty log
        }
    }
}
