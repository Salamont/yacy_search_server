/*
 *  Agent
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * An agent identity with its explicit grant: the action list (a snapshot,
 * never extended by new actions), the data scope (collections) and limits.
 * Instances are immutable; changes create a copy through {@link Builder}.
 */
public final class Agent {

    public enum Kind {
        EXTERNAL("external"), RESEARCH_WORKER("research_worker");

        public final String id;

        Kind(final String id) {
            this.id = id;
        }

        public static Kind of(final String id) throws AgentException {
            for (final Kind k : values()) {
                if (k.id.equals(id)) {
                    return k;
                }
            }
            throw AgentException.invalid("kind", "Kind must be 'external' or 'research_worker'.");
        }
    }

    public enum Status {
        ACTIVE("active"), PAUSED("paused"), REVOKED("revoked");

        public final String id;

        Status(final String id) {
            this.id = id;
        }

        public static Status of(final String id) throws AgentException {
            for (final Status s : values()) {
                if (s.id.equals(id)) {
                    return s;
                }
            }
            throw AgentException.invalid("status", "Status must be 'active', 'paused' or 'revoked'.");
        }
    }

    static final Pattern COLLECTION = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    /** Same DNS name rule as the evidence action: lower case, at least two labels, no IP, port or wildcard. */
    static final Pattern DOMAIN = Pattern.compile(
            "(?=.{3,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    static final Pattern ACTION = Pattern.compile("[a-z]+(\\.[a-z]+)*");
    static final int MAX_COLLECTIONS = 50;
    static final int MAX_DOMAINS = 200;

    /** Data scope: explicit collections, or the explicitly granted complete local index. */
    public static final class Scope {
        public final Set<String> collections;
        public final boolean allCollections;

        public Scope(final Set<String> collections, final boolean allCollections) {
            this.collections = Collections.unmodifiableSet(new LinkedHashSet<>(collections));
            this.allCollections = allCollections;
        }

        public boolean allows(final String collection) {
            return this.allCollections || this.collections.contains(collection);
        }

        void validate() throws AgentException {
            if (!this.allCollections && this.collections.isEmpty()) {
                throw AgentException.invalid("scope", "Select at least one collection or grant the complete index explicitly.");
            }
            if (this.collections.size() > MAX_COLLECTIONS) {
                throw AgentException.invalid("scope", "At most " + MAX_COLLECTIONS + " collections are allowed.");
            }
            for (final String c : this.collections) {
                if (!COLLECTION.matcher(c).matches()) {
                    throw AgentException.invalid("scope", "Collection names consist of letters, digits, '-' and '_' (at most 64).");
                }
            }
        }

        JSONObject toJson() {
            return JsonUtil.obj("collections", new JSONArray(this.collections), "allCollections", this.allCollections);
        }

        static Scope fromJson(final JSONObject o) {
            return new Scope(JsonUtil.stringSet(o == null ? null : o.optJSONArray("collections")),
                    o != null && o.optBoolean("allCollections", false));
        }
    }

    /** Limits for crawls, request rate and worker tasks. */
    public static final class Limits {
        public static final int MAX_DEPTH = 3;
        public static final int MAX_PAGES = 1000;
        public static final int MAX_PARALLEL = 5;
        public static final int MAX_RPM = 600;
        public static final int MAX_TASK_SECONDS = 3600;

        public final List<String> domains;
        public final int maxDepth;
        public final int maxPages;
        public final int maxParallelCrawls;
        public final int requestsPerMinute;
        public final int maxTaskSeconds;
        public final boolean modelAllowed;

        public Limits(final List<String> domains, final int maxDepth, final int maxPages, final int maxParallelCrawls,
                final int requestsPerMinute, final int maxTaskSeconds, final boolean modelAllowed) {
            final List<String> d = new ArrayList<>();
            for (final String s : domains) {
                final String n = s.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty() && !d.contains(n)) {
                    d.add(n);
                }
            }
            this.domains = Collections.unmodifiableList(d);
            this.maxDepth = maxDepth;
            this.maxPages = maxPages;
            this.maxParallelCrawls = maxParallelCrawls;
            this.requestsPerMinute = requestsPerMinute;
            this.maxTaskSeconds = maxTaskSeconds;
            this.modelAllowed = modelAllowed;
        }

        public static Limits defaults() {
            return new Limits(new ArrayList<>(), 1, 50, 1, 60, 300, false);
        }

        /** True when host equals an allowed domain or is a subdomain of one. */
        public boolean allowsHost(final String host) {
            if (host == null) {
                return false;
            }
            final String h = host.toLowerCase(Locale.ROOT);
            for (final String d : this.domains) {
                if (h.equals(d) || h.endsWith("." + d)) {
                    return true;
                }
            }
            return false;
        }

        void validate() throws AgentException {
            if (this.domains.size() > MAX_DOMAINS) {
                throw AgentException.invalid("domains", "At most " + MAX_DOMAINS + " domains are allowed.");
            }
            for (final String d : this.domains) {
                if (!DOMAIN.matcher(d).matches()) {
                    throw AgentException.invalid("domains", "'" + d + "' is not a DNS name such as 'example.com' "
                            + "(no scheme, port, path, IP address or wildcard).");
                }
            }
            range("maxDepth", this.maxDepth, 0, MAX_DEPTH);
            range("maxPages", this.maxPages, 1, MAX_PAGES);
            range("maxParallelCrawls", this.maxParallelCrawls, 1, MAX_PARALLEL);
            range("requestsPerMinute", this.requestsPerMinute, 1, MAX_RPM);
            range("maxTaskSeconds", this.maxTaskSeconds, 30, MAX_TASK_SECONDS);
        }

        private static void range(final String name, final int v, final int min, final int max) throws AgentException {
            if (v < min || v > max) {
                throw AgentException.invalid(name, "'" + name + "' must be between " + min + " and " + max + ".");
            }
        }

        JSONObject toJson() {
            return JsonUtil.obj("domains", new JSONArray(this.domains), "maxDepth", this.maxDepth,
                    "maxPages", this.maxPages, "maxParallelCrawls", this.maxParallelCrawls,
                    "requestsPerMinute", this.requestsPerMinute, "maxTaskSeconds", this.maxTaskSeconds,
                    "modelAllowed", this.modelAllowed);
        }

        static Limits fromJson(final JSONObject o) {
            final Limits d = defaults();
            if (o == null) {
                return d;
            }
            return new Limits(new ArrayList<>(JsonUtil.stringSet(o.optJSONArray("domains"))),
                    o.optInt("maxDepth", d.maxDepth), o.optInt("maxPages", d.maxPages),
                    o.optInt("maxParallelCrawls", d.maxParallelCrawls),
                    o.optInt("requestsPerMinute", d.requestsPerMinute),
                    o.optInt("maxTaskSeconds", d.maxTaskSeconds), o.optBoolean("modelAllowed", false));
        }
    }

    public final String id;
    public final String name;
    public final String description;
    public final Kind kind;
    public final Status status;
    public final Scope scope;
    public final Set<String> actions;
    public final String presetLabel;
    public final Limits limits;
    public final long revision;
    public final long createdAt;
    public final long updatedAt;

    private Agent(final Builder b) {
        this.id = b.id;
        this.name = b.name == null ? "" : b.name.trim();
        this.description = b.description == null ? "" : b.description.trim();
        this.kind = b.kind;
        this.status = b.status;
        this.scope = b.scope;
        this.actions = Collections.unmodifiableSet(new LinkedHashSet<>(b.actions));
        this.presetLabel = b.presetLabel == null ? "" : b.presetLabel;
        this.limits = b.limits;
        this.revision = b.revision;
        this.createdAt = b.createdAt;
        this.updatedAt = b.updatedAt;
    }

    public boolean isActive() {
        return this.status == Status.ACTIVE;
    }

    public Builder toBuilder() {
        final Builder b = new Builder();
        b.id = this.id;
        b.name = this.name;
        b.description = this.description;
        b.kind = this.kind;
        b.status = this.status;
        b.scope = this.scope;
        b.actions = new LinkedHashSet<>(this.actions);
        b.presetLabel = this.presetLabel;
        b.limits = this.limits;
        b.revision = this.revision;
        b.createdAt = this.createdAt;
        b.updatedAt = this.updatedAt;
        return b;
    }

    /** Field checks that do not depend on other agents (name uniqueness is checked by the store). */
    void validate() throws AgentException {
        if (this.name.isEmpty() || this.name.length() > 80 || this.name.chars().anyMatch(c -> c < 0x20)) {
            throw AgentException.invalid("name", "The name must have 1 to 80 characters without control characters.");
        }
        if (this.description.length() > 500) {
            throw AgentException.invalid("description", "The description must not exceed 500 characters.");
        }
        if (this.kind == null) {
            throw AgentException.invalid("kind", "The kind is required.");
        }
        if (this.scope == null) {
            throw AgentException.invalid("scope", "The data scope is required.");
        }
        this.scope.validate();
        if (this.limits == null) {
            throw AgentException.invalid("limits", "The limits are required.");
        }
        this.limits.validate();
        for (final String a : this.actions) {
            if (!ACTION.matcher(a).matches()) {
                throw AgentException.invalid("actions", "'" + a + "' is not an action name.");
            }
        }
    }

    JSONObject toJson() {
        return JsonUtil.obj("id", this.id, "name", this.name, "description", this.description, "kind", this.kind.id,
                "status", this.status.id, "scope", this.scope.toJson(), "actions", new JSONArray(this.actions),
                "presetLabel", this.presetLabel, "limits", this.limits.toJson(), "revision", this.revision,
                "createdAt", this.createdAt, "updatedAt", this.updatedAt);
    }

    static Agent fromJson(final JSONObject o) throws AgentException {
        final Builder b = new Builder();
        b.id = o.optString("id", "");
        b.name = o.optString("name", "");
        b.description = o.optString("description", "");
        b.kind = Kind.of(o.optString("kind", ""));
        b.status = Status.of(o.optString("status", ""));
        b.scope = Scope.fromJson(o.optJSONObject("scope"));
        b.actions = JsonUtil.stringSet(o.optJSONArray("actions"));
        b.presetLabel = o.optString("presetLabel", "");
        b.limits = Limits.fromJson(o.optJSONObject("limits"));
        b.revision = o.optLong("revision", 1);
        b.createdAt = o.optLong("createdAt", 0);
        b.updatedAt = o.optLong("updatedAt", 0);
        return b.build();
    }

    /** Mutable draft of an agent. */
    public static final class Builder {
        String id = "";
        long revision;
        long createdAt;
        long updatedAt;
        Status status = Status.ACTIVE;
        public String name;
        public String description;
        public Kind kind = Kind.EXTERNAL;
        public Scope scope = new Scope(new LinkedHashSet<>(), false);
        public Set<String> actions = new LinkedHashSet<>();
        public String presetLabel;
        public Limits limits = Limits.defaults();

        public Agent build() {
            return new Agent(this);
        }
    }
}
