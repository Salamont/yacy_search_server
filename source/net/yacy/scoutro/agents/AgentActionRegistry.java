/*
 *  AgentActionRegistry
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
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The actions an agent can be granted on the agent path
 * {@code /scoutro/api/agent/v1}. Presets are resolved into explicit action
 * lists when an agent is saved, so actions added here later never extend
 * existing agents.
 */
public final class AgentActionRegistry {

    public static final String BASE_PATH = "/scoutro/api/agent/v1";

    /** read: no side effects; write: changes the crawler state; admin: global or configuration data. */
    public enum Risk {
        READ("read"), WRITE("write"), ADMIN("admin");

        public final String id;

        Risk(final String id) {
            this.id = id;
        }
    }

    /** A grantable action. */
    public static final class Action {
        public final String id;
        public final String label;
        public final String description;
        public final Risk risk;
        /** True if the action is limited to the agent's collections. */
        public final boolean scoped;
        public final String method;
        public final String path;
        /** False: never part of a preset, must be granted individually (with a warning in the UI). */
        public final boolean presetable;
        /** Agent kinds that may be granted this action. */
        public final Set<Agent.Kind> kinds;

        Action(final String id, final String label, final String description, final Risk risk, final boolean scoped,
                final String method, final String path, final boolean presetable, final Agent.Kind... kinds) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.risk = risk;
            this.scoped = scoped;
            this.method = method;
            this.path = path;
            this.presetable = presetable;
            this.kinds = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(kinds)));
        }
    }

    /** Implicit actions every valid token may call; they are never stored in a grant. */
    public static final String CAPABILITIES = "agent.capabilities";
    public static final String HEARTBEAT = "agent.heartbeat";

    public static final String PRESET_RESEARCH = "research";
    public static final String PRESET_RESEARCH_CRAWL = "research_crawl";
    public static final String PRESET_CUSTOM = "custom";

    private static final Map<String, Action> ACTIONS = new LinkedHashMap<>();
    private static final Map<String, List<String>> PRESETS = new LinkedHashMap<>();

    static {
        final Agent.Kind ext = Agent.Kind.EXTERNAL;
        final Agent.Kind worker = Agent.Kind.RESEARCH_WORKER;
        add(new Action("search", "Search",
                "Full-text search in the granted collections of the local index.",
                Risk.READ, true, "GET", "/search", true, ext, worker));
        add(new Action("seo.read", "SEO / Host Analysis",
                "Read bounded host and URL metrics in granted collections; reference counts describe the local observed graph.",
                Risk.READ, true, "GET", "/seo/hosts", false, ext, worker));
        add(new Action("index.evidence", "Text evidence",
                "Read URL, title and bounded text excerpts of the indexed pages of one domain.",
                Risk.READ, true, "GET", "/index/evidence", true, ext, worker));
        add(new Action("index.lookup", "Index lookup",
                "Check whether a URL is indexed, or count the documents of a host, in the granted collections.",
                Risk.READ, true, "GET", "/index/lookup", true, ext, worker));
        add(new Action("index.status", "Index size",
                "Number of documents per granted collection.",
                Risk.READ, true, "GET", "/index", true, ext, worker));
        add(new Action("crawl.start", "Start crawls",
                "Start a text-only crawl of an allowed domain into a granted collection, within the crawl limits.",
                Risk.WRITE, true, "POST", "/crawls", true, ext, worker));
        add(new Action("crawl.list", "List own crawls",
                "List the crawls this agent started.",
                Risk.READ, true, "GET", "/crawls", true, ext, worker));
        add(new Action("crawl.status", "Crawl status",
                "Status of a crawl this agent started.",
                Risk.READ, true, "GET", "/crawls/{id}", true, ext, worker));
        add(new Action("crawl.stop", "Stop own crawls",
                "Stop a running crawl this agent started.",
                Risk.WRITE, true, "POST", "/crawls/{id}/stop", true, ext, worker));
        add(new Action("search.network", "Network search",
                "Search other YaCy peers. Results are not limited to the granted collections.",
                Risk.ADMIN, false, "GET", "/search?source=network", false, ext));
        add(new Action("index.status.global", "Global index status",
                "Document counts and crawler queues of the complete index, across all collections.",
                Risk.ADMIN, false, "GET", "/index?global=true", false, ext));
        add(new Action("system.status", "System status",
                "Versions, peer name, memory, disk and crawler queues of this Scoutro instance.",
                Risk.ADMIN, false, "GET", "/system", false, ext));
        add(new Action("config.get", "Read settings",
                "Read the Scoutro settings on the API allowlist (global, not limited to collections).",
                Risk.ADMIN, false, "GET", "/config", false, ext));
        add(new Action("config.set", "Change settings",
                "Change the Scoutro settings on the API allowlist (global, not limited to collections).",
                Risk.ADMIN, false, "PATCH", "/config", false, ext));

        PRESETS.put(PRESET_RESEARCH, Arrays.asList("search", "index.evidence", "index.lookup", "index.status"));
        PRESETS.put(PRESET_RESEARCH_CRAWL, Arrays.asList("search", "index.evidence", "index.lookup", "index.status",
                "crawl.start", "crawl.list", "crawl.status", "crawl.stop"));
    }

    private AgentActionRegistry() {
    }

    private static void add(final Action a) {
        ACTIONS.put(a.id, a);
    }

    public static Action get(final String id) {
        return ACTIONS.get(id);
    }

    public static List<Action> all() {
        return new ArrayList<>(ACTIONS.values());
    }

    public static boolean isImplicit(final String id) {
        return CAPABILITIES.equals(id) || HEARTBEAT.equals(id);
    }

    public static Set<String> presetNames() {
        return Collections.unmodifiableSet(PRESETS.keySet());
    }

    /** Explicit action list of a preset (a copy), or null for an unknown preset. */
    public static Set<String> preset(final String name) {
        final List<String> p = PRESETS.get(name);
        return p == null ? null : new LinkedHashSet<>(p);
    }

    /** Name of the preset whose action list equals the given grant, or "custom". */
    public static String presetOf(final Set<String> actions) {
        for (final Map.Entry<String, List<String>> e : PRESETS.entrySet()) {
            if (new LinkedHashSet<>(e.getValue()).equals(actions)) {
                return e.getKey();
            }
        }
        return PRESET_CUSTOM;
    }

    /**
     * Check a grant before it is stored: every action must exist and be
     * allowed for the kind; crawl actions need a domain allowlist.
     */
    public static void validateGrant(final Agent.Kind kind, final Set<String> actions, final Agent.Limits limits)
            throws AgentException {
        if (actions.isEmpty()) {
            throw AgentException.invalid("actions", "Select at least one action.");
        }
        for (final String id : actions) {
            final Action a = ACTIONS.get(id);
            if (a == null) {
                throw AgentException.invalid("actions", "Unknown action '" + id + "'.");
            }
            if (!a.kinds.contains(kind)) {
                throw AgentException.invalid("actions", "The action '" + id + "' cannot be granted to a "
                        + kind.id.replace('_', ' ') + " agent.");
            }
        }
        if (actions.contains("crawl.start") && limits.domains.isEmpty()) {
            throw AgentException.invalid("domains", "Crawl rights need at least one allowed domain.");
        }
    }
}
