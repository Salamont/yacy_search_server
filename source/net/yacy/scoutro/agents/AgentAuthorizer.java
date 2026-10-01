/*
 *  AgentAuthorizer
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * The single authorization decision for agent requests. Deny by default; the
 * checks run in a fixed order and the first failure gives a stable reason:
 * <ol>
 * <li>agent active ({@code agent_paused}, {@code agent_revoked})</li>
 * <li>action known ({@code unknown_action})</li>
 * <li>action granted ({@code action_not_granted}); implicit actions are always allowed</li>
 * <li>action allowed for the agent kind ({@code action_not_allowed_for_kind})</li>
 * <li>requested collections inside the scope ({@code collection_not_in_scope})</li>
 * <li>request rate ({@code rate_limited})</li>
 * </ol>
 * The grant is read from the agent object of the current request, so changes
 * apply to the next call. Action-specific limits (crawl depth, domains, ...)
 * are checked with {@link #checkLimit} by the scoped actions.
 */
public final class AgentAuthorizer {

    /** Result of an authorization. */
    public static final class Decision {
        public final boolean allowed;
        public final String reason;
        public final int status;
        public final String message;

        private Decision(final boolean allowed, final String reason, final int status, final String message) {
            this.allowed = allowed;
            this.reason = reason;
            this.status = status;
            this.message = message;
        }

        static Decision allow() {
            return new Decision(true, null, 200, null);
        }

        static Decision deny(final String reason, final int status, final String message) {
            return new Decision(false, reason, status, message);
        }

        public AgentException toException() {
            return new AgentException(this.status, this.reason, this.message);
        }
    }

    /** Failed authentications per client address before 429 (brute-force brake). */
    static final int MAX_AUTH_FAILURES_PER_MINUTE = 30;
    private static final long MINUTE = 60_000L;

    private final LongSupplier clock;
    private final Map<String, Deque<Long>> requestsPerToken = new HashMap<>();
    private final Map<String, Deque<Long>> failuresPerClient = new HashMap<>();

    public AgentAuthorizer(final LongSupplier clock) {
        this.clock = clock;
    }

    public Decision authorize(final Agent agent, final String tokenId, final String actionId,
            final Collection<String> requestedCollections) {
        if (agent.status == Agent.Status.REVOKED) {
            return Decision.deny("agent_revoked", 403, "This agent has been revoked.");
        }
        if (agent.status == Agent.Status.PAUSED) {
            return Decision.deny("agent_paused", 403, "This agent is paused by the administrator.");
        }
        final boolean implicit = AgentActionRegistry.isImplicit(actionId);
        final AgentActionRegistry.Action action = AgentActionRegistry.get(actionId);
        if (!implicit && action == null) {
            return Decision.deny("unknown_action", 403, "Unknown action '" + actionId + "'.");
        }
        if (!implicit && !agent.actions.contains(actionId)) {
            return Decision.deny("action_not_granted", 403,
                    "The action '" + actionId + "' is not granted to this agent.");
        }
        if (!implicit && !action.kinds.contains(agent.kind)) {
            return Decision.deny("action_not_allowed_for_kind", 403,
                    "The action '" + actionId + "' is not available for this kind of agent.");
        }
        if (requestedCollections != null) {
            for (final String c : requestedCollections) {
                if (!agent.scope.allows(c)) {
                    return Decision.deny("collection_not_in_scope", 403,
                            "The collection '" + c + "' is outside the data scope of this agent.");
                }
            }
        }
        if (!takeRequest(tokenId, agent.limits.requestsPerMinute)) {
            return Decision.deny("rate_limited", 429, "Too many requests; at most "
                    + agent.limits.requestsPerMinute + " per minute are allowed for this agent.");
        }
        return Decision.allow();
    }

    /** An action-specific limit; null if within the limit. */
    public static Decision checkLimit(final boolean within, final String limit, final String message) {
        return within ? null : Decision.deny("limit_exceeded:" + limit, 403, message);
    }

    /** Record a failed authentication; true while the client is below the failure brake. */
    public synchronized boolean recordAuthFailure(final String client) {
        return take(this.failuresPerClient, client == null ? "" : client, MAX_AUTH_FAILURES_PER_MINUTE);
    }

    /** True if the client has exceeded the failure brake (checked before authenticating). */
    public synchronized boolean authBlocked(final String client) {
        final Deque<Long> q = this.failuresPerClient.get(client == null ? "" : client);
        if (q == null) {
            return false;
        }
        expire(q, this.clock.getAsLong());
        return q.size() >= MAX_AUTH_FAILURES_PER_MINUTE;
    }

    private synchronized boolean takeRequest(final String tokenId, final int perMinute) {
        return take(this.requestsPerToken, tokenId == null ? "" : tokenId, perMinute);
    }

    private boolean take(final Map<String, Deque<Long>> map, final String key, final int perMinute) {
        final long now = this.clock.getAsLong();
        final Deque<Long> q = map.computeIfAbsent(key, k -> new ArrayDeque<>());
        expire(q, now);
        if (q.size() >= perMinute) {
            return false;
        }
        q.addLast(now);
        if (map.size() > 10_000) { // drop idle keys
            map.values().removeIf(d -> {
                expire(d, now);
                return d.isEmpty();
            });
        }
        return true;
    }

    private static void expire(final Deque<Long> q, final long now) {
        while (!q.isEmpty() && now - q.peekFirst() >= MINUTE) {
            q.removeFirst();
        }
    }

    /**
     * Fingerprint of the grant as presented in the capabilities: changes when
     * the agent, its revision, the token or the action list change.
     */
    public static String fingerprint(final Agent agent, final String tokenId) {
        final List<String> actions = new ArrayList<>(agent.actions);
        Collections.sort(actions);
        final String material = agent.id + "|" + agent.revision + "|" + tokenId + "|" + String.join(",", actions);
        try {
            return AgentTokens.hex(MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8)))
                    .substring(0, 32);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
