/*
 *  SessionStore
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Server-side sign-in sessions, kept in memory only (a restart ends them).
 * The cookie carries a 256-bit random id; this store keeps only its SHA-256,
 * so the store itself never holds a usable cookie value.
 */
public final class SessionStore {

    public static final int MAX_PER_USER = 10;

    /** Who a session belongs to. */
    public enum Kind {
        /** a Scoutro account */
        ACCOUNT,
        /** the built-in YaCy administrator, signed in on the login page */
        BUILTIN
    }

    /** One session; immutable except for the last access time. */
    public static final class Session {
        public final String key;
        public final Kind kind;
        public final String username;
        /** account auth version, or the fingerprint of the built-in administrator's credentials */
        public final String version;
        public final String csrf;
        public final long createdAt;
        public final String client;
        public final String agent;
        volatile long lastSeenAt;

        Session(final String key, final Kind kind, final String username, final String version, final String csrf,
                final long now, final String client, final String agent) {
            this.key = key;
            this.kind = kind;
            this.username = username;
            this.version = version;
            this.csrf = csrf;
            this.createdAt = now;
            this.lastSeenAt = now;
            this.client = client == null ? "" : client;
            this.agent = agent == null ? "" : agent;
        }

        public long lastSeenAt() {
            return this.lastSeenAt;
        }

        /** A short public id for lists and revocation (not usable as a cookie). */
        public String publicId() {
            return this.key.substring(0, 16);
        }
    }

    /** A new session and the cookie value that identifies it (returned once). */
    public static final class Created {
        public final Session session;
        public final String cookieValue;

        Created(final Session session, final String cookieValue) {
            this.session = session;
            this.cookieValue = cookieValue;
        }
    }

    private final LongSupplier clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private volatile long idleMillis;
    private volatile long maxMillis;

    public SessionStore(final LongSupplier clock, final long idleMillis, final long maxMillis) {
        this.clock = clock;
        this.idleMillis = idleMillis;
        this.maxMillis = maxMillis;
    }

    public void setLimits(final long idleMillis, final long maxMillis) {
        this.idleMillis = idleMillis;
        this.maxMillis = maxMillis;
    }

    public synchronized Created create(final Kind kind, final String username, final String version, final String client,
            final String agent) {
        final byte[] raw = new byte[32];
        this.random.nextBytes(raw);
        final String cookie = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        final byte[] csrfRaw = new byte[24];
        this.random.nextBytes(csrfRaw);
        final String csrf = Base64.getUrlEncoder().withoutPadding().encodeToString(csrfRaw);
        final long now = this.clock.getAsLong();
        final Session s = new Session(key(cookie), kind, username, version, csrf, now, client,
                agent == null ? "" : agent.substring(0, Math.min(agent.length(), 160)));
        purge(now);
        this.sessions.put(s.key, s);
        // keep at most MAX_PER_USER sessions per identity: the oldest go first
        final List<Session> own = ownSessions(kind, username);
        if (own.size() > MAX_PER_USER) {
            own.sort(Comparator.comparingLong(x -> x.createdAt));
            for (int i = 0; i < own.size() - MAX_PER_USER; i++) {
                this.sessions.remove(own.get(i).key);
            }
        }
        return new Created(s, cookie);
    }

    /** The live session of a cookie value, or null (unknown, idle too long, or too old); refreshes its last access. */
    public synchronized Session find(final String cookieValue) {
        if (cookieValue == null || cookieValue.length() < 40 || cookieValue.length() > 64) {
            return null;
        }
        final Session s = this.sessions.get(key(cookieValue));
        if (s == null) {
            return null;
        }
        final long now = this.clock.getAsLong();
        if (expired(s, now)) {
            this.sessions.remove(s.key);
            return null;
        }
        s.lastSeenAt = now;
        return s;
    }

    public synchronized boolean revoke(final String key) {
        return this.sessions.remove(key) != null;
    }

    /** Ends a session by its public id, only if it belongs to the identity. */
    public synchronized boolean revokePublic(final Kind kind, final String username, final String publicId) {
        for (final Session s : ownSessions(kind, username)) {
            if (s.publicId().equals(publicId)) {
                this.sessions.remove(s.key);
                return true;
            }
        }
        return false;
    }

    /** Ends all sessions of an identity except {@code exceptKey} (may be null); returns the number ended. */
    public synchronized int revokeAll(final Kind kind, final String username, final String exceptKey) {
        int n = 0;
        for (final Iterator<Session> it = this.sessions.values().iterator(); it.hasNext();) {
            final Session s = it.next();
            if (s.kind == kind && s.username.equals(username) && !s.key.equals(exceptKey)) {
                it.remove();
                n++;
            }
        }
        return n;
    }

    public synchronized List<Session> list(final Kind kind, final String username) {
        purge(this.clock.getAsLong());
        final List<Session> own = ownSessions(kind, username);
        own.sort(Comparator.comparingLong((Session x) -> x.lastSeenAt).reversed());
        return own;
    }

    public synchronized int count(final Kind kind, final String username) {
        purge(this.clock.getAsLong());
        return ownSessions(kind, username).size();
    }

    public long idleMillis() {
        return this.idleMillis;
    }

    public long maxMillis() {
        return this.maxMillis;
    }

    /** Remaining lifetime of a session in milliseconds (the earlier of idle and absolute expiry). */
    public long remaining(final Session s) {
        final long now = this.clock.getAsLong();
        return Math.max(0, Math.min(s.lastSeenAt + this.idleMillis, s.createdAt + this.maxMillis) - now);
    }

    private boolean expired(final Session s, final long now) {
        return now - s.lastSeenAt > this.idleMillis || now - s.createdAt > this.maxMillis;
    }

    private List<Session> ownSessions(final Kind kind, final String username) {
        final List<Session> own = new ArrayList<>();
        for (final Session s : this.sessions.values()) {
            if (s.kind == kind && s.username.equals(username)) {
                own.add(s);
            }
        }
        return own;
    }

    private void purge(final long now) {
        this.sessions.values().removeIf(s -> expired(s, now));
    }

    static String key(final String cookieValue) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(cookieValue.getBytes(StandardCharsets.US_ASCII));
            final StringBuilder sb = new StringBuilder(64);
            for (final byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
