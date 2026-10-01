/*
 *  AgentAudit
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

package net.yacy.scoutro.agents;

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
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Activity log of agent requests and management changes: a ring buffer of
 * the newest {@value #CAPACITY} entries, appended to a JSON-lines file and
 * compacted when it grows. Entries name the agent, the token's public id,
 * the action, the decision and its reason; they never contain request
 * bodies, query text, page text or secrets.
 */
public final class AgentAudit {

    public static final String AUDIT_FILE = "scoutro-agent-audit.jsonl";
    static final int CAPACITY = 20_000;

    private final Path file;
    private final LongSupplier clock;
    private final Deque<JSONObject> entries = new ArrayDeque<>();
    private int appendedSinceCompaction;

    public AgentAudit(final File settingsDir, final LongSupplier clock) {
        this.file = new File(settingsDir, AUDIT_FILE).toPath();
        this.clock = clock;
        load();
    }

    /**
     * @param agentId agent id or empty for unauthenticated requests
     * @param tokenId public token id or empty
     * @param action action id, or a management event such as {@code admin.token.issued}
     * @param decision allow, deny or event
     * @param reason stable reason code or null
     * @param collections collections the request referred to (may be null)
     * @param status HTTP status of the answer
     * @param client client address
     */
    public synchronized void record(final String agentId, final String tokenId, final String action,
            final String decision, final String reason, final Collection<String> collections, final int status,
            final String client) {
        final JSONObject e = JsonUtil.obj("ts", this.clock.getAsLong(), "agentId", agentId == null ? "" : agentId,
                "tokenId", tokenId == null ? "" : tokenId, "action", action, "decision", decision,
                "reason", reason == null ? "" : reason,
                "collections", collections == null ? new JSONArray() : new JSONArray(collections),
                "status", status, "client", client == null ? "" : client);
        this.entries.addLast(e);
        while (this.entries.size() > CAPACITY) {
            this.entries.removeFirst();
        }
        append(e);
    }

    /** Newest first, optionally filtered by agent id and decision. */
    public synchronized List<JSONObject> recent(final String agentId, final String decision, final int limit) {
        final List<JSONObject> out = new ArrayList<>();
        final Iterator<JSONObject> it = this.entries.descendingIterator();
        while (it.hasNext() && out.size() < limit) {
            final JSONObject e = it.next();
            if (agentId != null && !agentId.isEmpty() && !agentId.equals(e.optString("agentId"))) {
                continue;
            }
            if (decision != null && !decision.isEmpty() && !decision.equals(e.optString("decision"))) {
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private void append(final JSONObject e) {
        try {
            try (OutputStream out = Files.newOutputStream(this.file, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                out.write((e.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            }
            AgentStore.ownerOnly(this.file);
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
        AgentStore.writeAtomically(this.file, sb.toString().getBytes(StandardCharsets.UTF_8));
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
