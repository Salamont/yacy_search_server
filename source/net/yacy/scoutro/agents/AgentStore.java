/*
 *  AgentStore
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Persistent store of agents, their tokens and the crawls they own.
 * <p>
 * Stored as one JSON document ({@value #STORE_FILE}) in the settings
 * directory, written atomically (temporary file + rename) with owner-only
 * permissions. Tokens are stored as public id plus HMAC-SHA256 with a
 * server-side pepper ({@value #PEPPER_FILE}); the plain token is returned
 * once by {@link #issueToken} and never stored. Nothing here is kept in
 * yacy.conf, which the administrator pages can display.
 */
public final class AgentStore {

    public static final String STORE_FILE = "scoutro-agents.json";
    public static final String PEPPER_FILE = "scoutro-agent-pepper";

    public static final long DAY = 24L * 60 * 60 * 1000;
    public static final long MAX_TOKEN_TTL = 365 * DAY;
    public static final long MAX_ROTATION_GRACE = 60L * 60 * 1000;
    /** lastUsedAt is written at most this often per token to avoid a disk write per request. */
    static final long LAST_USED_RESOLUTION = 60L * 1000;
    static final int MAX_CRAWL_RECORDS = 5000;

    private final Path storeFile;
    private final byte[] pepper;
    private final LongSupplier clock;

    private final Map<String, Agent> agents = new LinkedHashMap<>();
    private final Map<String, TokenRecord> tokens = new LinkedHashMap<>();
    private final List<CrawlRecord> crawls = new ArrayList<>();
    /** Per agent: last capabilities handshake (time, fingerprint) and last heartbeat of a runtime. */
    private final Map<String, JSONObject> connections = new LinkedHashMap<>();

    public AgentStore(final File settingsDir, final LongSupplier clock) throws IOException, AgentException {
        if (!settingsDir.isDirectory() && !settingsDir.mkdirs()) {
            throw new IOException("cannot create " + settingsDir);
        }
        this.storeFile = new File(settingsDir, STORE_FILE).toPath();
        this.pepper = loadOrCreatePepper(new File(settingsDir, PEPPER_FILE).toPath());
        this.clock = clock;
        load();
    }

    // ------------------------------------------------------------------
    // authentication
    // ------------------------------------------------------------------

    /** Outcome of a token check: either an agent and token, or a stable reason. */
    public static final class AuthResult {
        public final Agent agent;
        public final TokenRecord token;
        /** null when authenticated; otherwise invalid_token, token_expired, token_revoked, agent_paused, agent_revoked. */
        public final String reason;

        private AuthResult(final Agent agent, final TokenRecord token, final String reason) {
            this.agent = agent;
            this.token = token;
            this.reason = reason;
        }

        public boolean ok() {
            return this.reason == null;
        }

        /** 401 for credential problems, 403 for a known but blocked agent. */
        public int httpStatus() {
            return this.reason == null ? 200 : this.reason.startsWith("agent_") ? 403 : 401;
        }
    }

    public synchronized AuthResult authenticate(final String presented, final String clientIp) {
        final AgentTokens.Presented p = AgentTokens.parse(presented);
        if (p == null) {
            return new AuthResult(null, null, "invalid_token");
        }
        final TokenRecord t = this.tokens.get(p.publicId);
        // hash even when the id is unknown so that both cases take the same time
        final String actual = AgentTokens.hash(this.pepper, p.secret);
        if (t == null || !AgentTokens.matches(t.hash, actual)) {
            return new AuthResult(null, null, "invalid_token");
        }
        final long now = this.clock.getAsLong();
        if (t.isRevoked(now)) {
            return new AuthResult(null, t, "token_revoked");
        }
        if (t.isExpired(now)) {
            return new AuthResult(null, t, "token_expired");
        }
        final Agent a = this.agents.get(t.agentId);
        if (a == null) {
            return new AuthResult(null, t, "invalid_token");
        }
        TokenRecord current = t;
        if (now - t.lastUsedAt >= LAST_USED_RESOLUTION || !t.lastUsedIp.equals(clientIp == null ? "" : clientIp)) {
            current = t.used(now, clientIp);
            this.tokens.put(t.publicId, current);
            saveQuietly();
        }
        if (a.status == Agent.Status.REVOKED) {
            return new AuthResult(a, current, "agent_revoked");
        }
        if (a.status == Agent.Status.PAUSED) {
            return new AuthResult(a, current, "agent_paused");
        }
        return new AuthResult(a, current, null);
    }

    // ------------------------------------------------------------------
    // agents
    // ------------------------------------------------------------------

    public synchronized List<Agent> agents() {
        return new ArrayList<>(this.agents.values());
    }

    public synchronized Agent agent(final String id) {
        return this.agents.get(id);
    }

    public synchronized Agent createAgent(final Agent.Builder draft) throws AgentException, IOException {
        final long now = this.clock.getAsLong();
        String id;
        do {
            id = "agt_" + AgentTokens.randomBase32(12);
        } while (this.agents.containsKey(id));
        draft.id = id;
        draft.status = Agent.Status.ACTIVE;
        draft.revision = 1;
        draft.createdAt = now;
        draft.updatedAt = now;
        final Agent agent = draft.build();
        agent.validate();
        AgentActionRegistry.validateGrant(agent.kind, agent.actions, agent.limits);
        checkUniqueName(agent);
        this.agents.put(id, agent);
        save();
        return agent;
    }

    /**
     * Replace name, description, kind-independent grant fields (scope, actions,
     * preset label, limits). The kind and status are not changed here. Every
     * update increases the revision, so a connection handshake becomes stale.
     */
    public synchronized Agent updateAgent(final String id, final Agent.Builder changes) throws AgentException, IOException {
        final Agent old = requireAgent(id);
        if (old.status == Agent.Status.REVOKED) {
            throw new AgentException(409, "agent_revoked", "A revoked agent cannot be changed.");
        }
        final Agent.Builder b = old.toBuilder();
        b.name = changes.name;
        b.description = changes.description;
        b.scope = changes.scope;
        b.actions = changes.actions;
        b.presetLabel = changes.presetLabel;
        b.limits = changes.limits;
        b.revision = old.revision + 1;
        b.updatedAt = this.clock.getAsLong();
        final Agent agent = b.build();
        agent.validate();
        AgentActionRegistry.validateGrant(agent.kind, agent.actions, agent.limits);
        checkUniqueName(agent);
        this.agents.put(id, agent);
        save();
        return agent;
    }

    public synchronized Agent pause(final String id) throws AgentException, IOException {
        return transition(id, Agent.Status.ACTIVE, Agent.Status.PAUSED, "Only an active agent can be paused.");
    }

    /** Resume is only possible from paused; a revoked agent stays revoked. */
    public synchronized Agent resume(final String id) throws AgentException, IOException {
        return transition(id, Agent.Status.PAUSED, Agent.Status.ACTIVE, "Only a paused agent can be resumed.");
    }

    /** Final: the agent and all its tokens are revoked immediately. */
    public synchronized Agent revoke(final String id) throws AgentException, IOException {
        final Agent old = requireAgent(id);
        if (old.status == Agent.Status.REVOKED) {
            throw new AgentException(409, "invalid_transition", "The agent is already revoked.");
        }
        final long now = this.clock.getAsLong();
        for (final Map.Entry<String, TokenRecord> e : this.tokens.entrySet()) {
            if (e.getValue().agentId.equals(id)) {
                e.setValue(e.getValue().revoked(now));
            }
        }
        final Agent.Builder b = old.toBuilder();
        b.status = Agent.Status.REVOKED;
        b.revision = old.revision + 1;
        b.updatedAt = now;
        final Agent agent = b.build();
        this.agents.put(id, agent);
        save();
        return agent;
    }

    private Agent transition(final String id, final Agent.Status from, final Agent.Status to, final String message)
            throws AgentException, IOException {
        final Agent old = requireAgent(id);
        if (old.status != from) {
            throw new AgentException(409, "invalid_transition", message);
        }
        final Agent.Builder b = old.toBuilder();
        b.status = to;
        b.revision = old.revision + 1;
        b.updatedAt = this.clock.getAsLong();
        final Agent agent = b.build();
        this.agents.put(id, agent);
        save();
        return agent;
    }

    private Agent requireAgent(final String id) throws AgentException {
        final Agent a = id == null ? null : this.agents.get(id);
        if (a == null) {
            throw AgentException.notFound("The agent '" + id + "'");
        }
        return a;
    }

    private void checkUniqueName(final Agent agent) throws AgentException {
        final String n = agent.name.toLowerCase(Locale.ROOT);
        for (final Agent other : this.agents.values()) {
            if (!other.id.equals(agent.id) && other.status != Agent.Status.REVOKED
                    && other.name.toLowerCase(Locale.ROOT).equals(n)) {
                throw new AgentException(409, "name_taken", "Another agent already uses this name.", "name");
            }
        }
    }

    // ------------------------------------------------------------------
    // tokens
    // ------------------------------------------------------------------

    public synchronized List<TokenRecord> tokens(final String agentId) {
        final List<TokenRecord> out = new ArrayList<>();
        for (final TokenRecord t : this.tokens.values()) {
            if (t.agentId.equals(agentId)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Issue an additional token; the returned plain text is the only copy. */
    public synchronized AgentTokens.Issued issueToken(final String agentId, final long ttlMillis)
            throws AgentException, IOException {
        final Agent a = requireAgent(agentId);
        if (a.status == Agent.Status.REVOKED) {
            throw new AgentException(409, "agent_revoked", "A revoked agent cannot get new tokens.");
        }
        if (ttlMillis < DAY || ttlMillis > MAX_TOKEN_TTL) {
            throw AgentException.invalid("expiresInDays", "A token must be valid for 1 to 365 days.");
        }
        final long now = this.clock.getAsLong();
        AgentTokens.Issued issued;
        do {
            issued = AgentTokens.generate();
        } while (this.tokens.containsKey(issued.publicId));
        this.tokens.put(issued.publicId, new TokenRecord(issued.publicId, agentId,
                AgentTokens.hash(this.pepper, issued.secret), now, now + ttlMillis, 0, "", 0));
        save();
        return issued;
    }

    /**
     * Issue a new token and end all other usable tokens of the agent after the
     * grace period (0 = immediately, at most one hour).
     */
    public synchronized AgentTokens.Issued rotateToken(final String agentId, final long ttlMillis, final long graceMillis)
            throws AgentException, IOException {
        if (graceMillis < 0 || graceMillis > MAX_ROTATION_GRACE) {
            throw AgentException.invalid("graceMinutes", "The grace period must be between 0 and 60 minutes.");
        }
        final long now = this.clock.getAsLong();
        final List<String> previous = new ArrayList<>();
        for (final TokenRecord t : this.tokens.values()) {
            if (t.agentId.equals(agentId) && t.isUsable(now)) {
                previous.add(t.publicId);
            }
        }
        final AgentTokens.Issued issued = issueToken(agentId, ttlMillis);
        for (final String id : previous) {
            this.tokens.put(id, this.tokens.get(id).revoked(now + graceMillis));
        }
        save();
        return issued;
    }

    public synchronized TokenRecord revokeToken(final String agentId, final String publicId)
            throws AgentException, IOException {
        final TokenRecord t = this.tokens.get(publicId);
        if (t == null || !t.agentId.equals(agentId)) {
            throw AgentException.notFound("The token '" + publicId + "'");
        }
        final TokenRecord revoked = t.revoked(this.clock.getAsLong());
        this.tokens.put(publicId, revoked);
        save();
        return revoked;
    }

    // ------------------------------------------------------------------
    // crawl ownership
    // ------------------------------------------------------------------

    public synchronized void recordCrawl(final CrawlRecord crawl) throws IOException {
        final List<CrawlRecord> before = new ArrayList<>(this.crawls);
        this.crawls.add(crawl);
        while (this.crawls.size() > MAX_CRAWL_RECORDS) {
            this.crawls.remove(0);
        }
        try {
            save();
        } catch (final IOException e) {
            this.crawls.clear(); // memory never claims what the file does not hold
            this.crawls.addAll(before);
            throw e;
        }
    }

    public synchronized List<CrawlRecord> crawls(final String agentId) {
        final List<CrawlRecord> out = new ArrayList<>();
        for (final CrawlRecord c : this.crawls) {
            if (c.agentId.equals(agentId)) {
                out.add(c);
            }
        }
        return out;
    }

    public synchronized List<CrawlRecord> allCrawls() {
        return new ArrayList<>(this.crawls);
    }

    /** A started crawl of the agent by its YaCy id (never a pending or refused attempt). */
    public synchronized CrawlRecord crawl(final String agentId, final String crawlId) {
        if (crawlId == null || crawlId.isEmpty()) {
            return null;
        }
        for (final CrawlRecord c : this.crawls) {
            if (c.agentId.equals(agentId) && c.isStarted() && c.crawlId.equals(crawlId)) {
                return c;
            }
        }
        return null;
    }

    /**
     * The attempt that holds an idempotency key: the newest record with this
     * key that is started or still starting; rejected and abandoned attempts
     * release the key.
     */
    public synchronized CrawlRecord crawlByClientRef(final String agentId, final String clientRef) {
        if (clientRef == null || clientRef.isEmpty()) {
            return null;
        }
        for (int i = this.crawls.size() - 1; i >= 0; i--) {
            final CrawlRecord c = this.crawls.get(i);
            if (c.agentId.equals(agentId) && c.clientRef.equals(clientRef) && (c.isStarted() || c.isStarting())) {
                return c;
            }
        }
        return null;
    }

    /** Replace the record of a start attempt (identified by its marker) with a new state. */
    public synchronized CrawlRecord updateCrawl(final String agentId, final String startMarker, final String state,
            final String crawlId) throws IOException, AgentException {
        for (int i = 0; i < this.crawls.size(); i++) {
            final CrawlRecord c = this.crawls.get(i);
            if (c.agentId.equals(agentId) && !startMarker.isEmpty() && startMarker.equals(c.startMarker)) {
                final CrawlRecord updated = c.withState(state, crawlId);
                this.crawls.set(i, updated);
                try {
                    save();
                } catch (final IOException e) {
                    this.crawls.set(i, c);
                    throw e;
                }
                return updated;
            }
        }
        throw AgentException.notFound("The crawl start '" + startMarker + "'");
    }

    /**
     * Administrator decision for an unconfirmed start: it did not run (or is
     * no longer wanted), so its idempotency key may start a new crawl.
     */
    public synchronized CrawlRecord abandonCrawlStart(final String agentId, final String startMarker)
            throws IOException, AgentException {
        for (final CrawlRecord c : this.crawls) {
            if (c.agentId.equals(agentId) && startMarker.equals(c.startMarker) && !c.isStarting()) {
                throw new AgentException(409, "invalid_transition", "Only an unconfirmed crawl start can be abandoned.");
            }
        }
        return updateCrawl(agentId, startMarker, CrawlRecord.ABANDONED, "");
    }

    // ------------------------------------------------------------------
    // connection state (handshake and runtime heartbeat)
    // ------------------------------------------------------------------

    /** Record a successful capabilities handshake with the fingerprint of the grant the agent saw. */
    public synchronized void recordHandshake(final String agentId, final String fingerprint) throws IOException {
        final JSONObject c = connectionObject(agentId);
        JsonUtil.put(c, "handshakeAt", this.clock.getAsLong());
        JsonUtil.put(c, "fingerprint", fingerprint);
        save();
    }

    /** Record a runtime heartbeat (already validated); persisted at most once per minute. */
    public synchronized void recordHeartbeat(final String agentId, final JSONObject heartbeat) {
        final JSONObject c = connectionObject(agentId);
        final long now = this.clock.getAsLong();
        final long previous = c.optLong("heartbeatAt", 0);
        JsonUtil.put(c, "heartbeatAt", now);
        JsonUtil.put(c, "heartbeat", heartbeat);
        if (now - previous >= LAST_USED_RESOLUTION) {
            saveQuietly();
        }
    }

    /** Copy of the connection state of an agent (empty object if none). */
    public synchronized JSONObject connection(final String agentId) {
        final JSONObject c = this.connections.get(agentId);
        return c == null ? new JSONObject(true) : JsonUtil.copy(c);
    }

    private JSONObject connectionObject(final String agentId) {
        return this.connections.computeIfAbsent(agentId, k -> new JSONObject(true));
    }

    public long now() {
        return this.clock.getAsLong();
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    private void load() throws IOException, AgentException {
        if (!Files.isRegularFile(this.storeFile)) {
            return;
        }
        final JSONObject root = JsonUtil.parse(new String(Files.readAllBytes(this.storeFile), StandardCharsets.UTF_8));
        final JSONArray a = root.optJSONArray("agents");
        for (int i = 0; a != null && i < a.length(); i++) {
            final Agent agent = Agent.fromJson(a.optJSONObject(i));
            this.agents.put(agent.id, agent);
        }
        final JSONArray t = root.optJSONArray("tokens");
        for (int i = 0; t != null && i < t.length(); i++) {
            final TokenRecord token = TokenRecord.fromJson(t.optJSONObject(i));
            this.tokens.put(token.publicId, token);
        }
        final JSONArray c = root.optJSONArray("crawls");
        for (int i = 0; c != null && i < c.length(); i++) {
            this.crawls.add(CrawlRecord.fromJson(c.optJSONObject(i)));
        }
        final JSONObject conn = root.optJSONObject("connections");
        if (conn != null) {
            for (final String key : conn.keySet()) {
                final JSONObject v = conn.optJSONObject(key);
                if (v != null && this.agents.containsKey(key)) {
                    this.connections.put(key, v);
                }
            }
        }
    }

    private int savesBeforeFault;
    private int failingSaves;

    /**
     * Test hook (storage fault injection): after {@code skip} successful
     * writes, the next {@code n} writes of the store fail with an IOException.
     */
    public synchronized void failSaves(final int skip, final int n) {
        this.savesBeforeFault = skip;
        this.failingSaves = n;
    }

    private void save() throws IOException {
        if (this.failingSaves > 0) {
            if (this.savesBeforeFault > 0) {
                this.savesBeforeFault--;
            } else {
                this.failingSaves--;
                throw new IOException("injected storage fault");
            }
        }
        final JSONArray a = new JSONArray();
        for (final Agent agent : this.agents.values()) {
            a.put(agent.toJson());
        }
        final JSONArray t = new JSONArray();
        for (final TokenRecord token : this.tokens.values()) {
            t.put(token.toJson());
        }
        final JSONArray c = new JSONArray();
        for (final CrawlRecord crawl : this.crawls) {
            c.put(crawl.toJson());
        }
        final JSONObject conn = new JSONObject(true);
        for (final Map.Entry<String, JSONObject> e : this.connections.entrySet()) {
            JsonUtil.put(conn, e.getKey(), e.getValue());
        }
        final JSONObject root = JsonUtil.obj("version", 1, "agents", a, "tokens", t, "crawls", c, "connections", conn);
        writeAtomically(this.storeFile, root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void saveQuietly() {
        try {
            save();
        } catch (final IOException e) {
            // lastUsedAt is informational; the request itself must not fail
        }
    }

    static void writeAtomically(final Path target, final byte[] content) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, content);
        ownerOnly(tmp);
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static void ownerOnly(final Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (final UnsupportedOperationException | IOException e) {
            // not a POSIX file system (Windows): rely on the directory permissions
        }
    }

    private static byte[] loadOrCreatePepper(final Path file) throws IOException {
        if (Files.isRegularFile(file)) {
            final String hex = new String(Files.readAllBytes(file), StandardCharsets.US_ASCII).trim();
            if (hex.matches("[0-9a-f]{64}")) {
                return AgentTokens.unhex(hex);
            }
            throw new IOException(file + " is damaged; restore it from a backup (all agent tokens depend on it)");
        }
        final byte[] pepper = new byte[32];
        new SecureRandom().nextBytes(pepper);
        writeAtomically(file, AgentTokens.hex(pepper).getBytes(StandardCharsets.US_ASCII));
        return pepper;
    }

    /** For tests: the stored file content must never contain a secret. */
    String rawStoreContent() throws IOException {
        return Files.isRegularFile(this.storeFile)
                ? new String(Files.readAllBytes(this.storeFile), StandardCharsets.UTF_8) : "";
    }
}
