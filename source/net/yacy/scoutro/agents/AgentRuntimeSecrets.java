/*
 *  AgentRuntimeSecrets
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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.regex.Pattern;

import org.json.JSONObject;

/**
 * Credentials a Scoutro research worker needs at run time, one file per
 * agent in {@code DATA/SETTINGS/agent-runtime/<agentId>.secret} (directory
 * 0700, files 0600). A stored token hash cannot authenticate an outgoing
 * call, so the worker's own Scoutro token and its Clustro agent key are kept
 * here in plain text for the worker process only. They are written by the
 * administrator pages and never shown again or logged.
 */
public final class AgentRuntimeSecrets {

    public static final String DIRECTORY = "agent-runtime";
    private static final Pattern AGENT_ID = Pattern.compile("agt_[a-z2-7]{12}");

    private final File dir;

    public AgentRuntimeSecrets(final File dir) {
        this.dir = dir;
    }

    public File directory() {
        return this.dir;
    }

    public synchronized void write(final String agentId, final JSONObject secret) throws IOException {
        final Path file = path(agentId);
        if (!this.dir.isDirectory() && !this.dir.mkdirs()) {
            throw new IOException("cannot create " + this.dir);
        }
        try {
            Files.setPosixFilePermissions(this.dir.toPath(), PosixFilePermissions.fromString("rwx------"));
        } catch (final UnsupportedOperationException e) {
            // not a POSIX file system
        }
        AgentStore.writeAtomically(file, secret.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The stored secret, or null if none exists. */
    public synchronized JSONObject read(final String agentId) throws IOException {
        final Path file = path(agentId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        } catch (final org.json.JSONException e) {
            throw new IOException("runtime secret of " + agentId + " is damaged");
        }
    }

    public synchronized boolean exists(final String agentId) {
        return Files.isRegularFile(path(agentId));
    }

    public synchronized void delete(final String agentId) throws IOException {
        Files.deleteIfExists(path(agentId));
    }

    private Path path(final String agentId) {
        if (agentId == null || !AGENT_ID.matcher(agentId).matches()) {
            throw new IllegalArgumentException("invalid agent id");
        }
        return new File(this.dir, agentId + ".secret").toPath();
    }
}
