/*
 *  LoginThrottle
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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Sign-in limits: per user name, after {@value #ACCOUNT_FAILURES} failures
 * within the window further attempts wait until the window after the last
 * failure has passed (no permanent lock); per client address, after
 * {@value #CLIENT_FAILURES} failures within the window. Kept in memory.
 */
public final class LoginThrottle {

    public static final int ACCOUNT_FAILURES = 5;
    public static final int CLIENT_FAILURES = 30;
    public static final long WINDOW = 15L * 60 * 1000;
    static final int MAX_KEYS = 10_000;

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> accounts = new HashMap<>();
    private final Map<String, Deque<Long>> clients = new HashMap<>();

    public LoginThrottle(final LongSupplier clock) {
        this.clock = clock;
    }

    /** Seconds to wait before the next attempt for this name or client, 0 when an attempt is allowed. */
    public synchronized long waitSeconds(final String username, final String client) {
        final long now = this.clock.getAsLong();
        final long a = blockedUntil(this.accounts.get(key(username)), ACCOUNT_FAILURES, now);
        final long c = blockedUntil(this.clients.get(client == null ? "" : client), CLIENT_FAILURES, now);
        final long until = Math.max(a, c);
        return until <= now ? 0 : (until - now + 999) / 1000;
    }

    public synchronized void failure(final String username, final String client) {
        final long now = this.clock.getAsLong();
        add(this.accounts, key(username), now);
        add(this.clients, client == null ? "" : client, now);
    }

    public synchronized void success(final String username) {
        this.accounts.remove(key(username));
    }

    private static long blockedUntil(final Deque<Long> failures, final int limit, final long now) {
        if (failures == null) {
            return 0;
        }
        while (!failures.isEmpty() && now - failures.peekFirst() > WINDOW) {
            failures.removeFirst();
        }
        return failures.size() >= limit ? failures.peekLast() + WINDOW : 0;
    }

    private static void add(final Map<String, Deque<Long>> map, final String key, final long now) {
        if (map.size() >= MAX_KEYS && !map.containsKey(key)) {
            map.entrySet().removeIf(e -> e.getValue().isEmpty() || now - e.getValue().peekLast() > WINDOW);
            if (map.size() >= MAX_KEYS) {
                map.clear(); // under a flood keep memory bounded; the per-client limit refills quickly
            }
        }
        final Deque<Long> d = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        d.addLast(now);
        while (d.size() > CLIENT_FAILURES + 1) {
            d.removeFirst();
        }
    }

    private static String key(final String username) {
        return AccountStore.normalize(username);
    }
}
