/*
 *  AgentAdmin
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

package net.yacy.scoutro.api;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentAuthorizer;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentStore;
import net.yacy.scoutro.agents.TokenRecord;
import net.yacy.search.Switchboard;

/**
 * Logic behind the administrator pages "Agents &amp; Access"
 * (ScoutroAgents_p, ScoutroAgentWizard_p): form parsing, the wizard draft,
 * known collections and the computed connection status. Kept apart from
 * the templates so that it can be tested.
 */
public final class AgentAdmin {

    /** A capabilities handshake older than this does not count as connected. */
    public static final long HANDSHAKE_TTL = 24L * 60 * 60 * 1000;
    /** A runtime heartbeat or Clustro check older than this is not current. */
    public static final long RUNTIME_TTL = 5L * 60 * 1000;

    private static final Pattern CLUSTRO_KEY = Pattern.compile("ak_[0-9a-f]{64}");
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");
    public static final int[] TOKEN_DAYS = {30, 90, 180, 365};

    private AgentAdmin() {
    }

    // ------------------------------------------------------------------
    // form parsing
    // ------------------------------------------------------------------

    /** Agent fields from a form (as a map of single values; checkbox present = on). */
    public static Agent.Builder parseGrant(final Agent.Builder into, final Map<String, String> form) throws AgentException {
        if (form.containsKey("name")) {
            into.name = form.get("name");
        }
        if (form.containsKey("description")) {
            into.description = form.get("description");
        }
        if (form.containsKey("kind")) {
            into.kind = Agent.Kind.of(form.get("kind"));
        }
        if (form.containsKey("scopeForm")) {
            final Set<String> collections = new LinkedHashSet<>();
            for (final Map.Entry<String, String> e : form.entrySet()) {
                if (e.getKey().startsWith("col_") && isOn(e.getValue())) {
                    collections.add(e.getKey().substring(4));
                }
            }
            for (final String c : split(form.get("extraCollections"))) {
                collections.add(c);
            }
            final boolean all = isOn(form.get("allCollections"));
            // confirmation only when the complete index is granted newly, not when an agent keeps it
            final boolean alreadyGranted = into.scope != null && into.scope.allCollections;
            if (all && !alreadyGranted && !isOn(form.get("confirmAllCollections"))) {
                throw AgentException.invalid("allCollections",
                        "Confirm that this agent may read the complete local index.");
            }
            into.scope = new Agent.Scope(all ? new LinkedHashSet<>() : collections, all);
        }
        if (form.containsKey("actionsForm")) {
            final String preset = form.getOrDefault("preset", AgentActionRegistry.PRESET_CUSTOM);
            final Set<String> actions = new LinkedHashSet<>();
            final Set<String> base = AgentActionRegistry.preset(preset);
            if (base != null) {
                actions.addAll(base);
            } else if (!AgentActionRegistry.PRESET_CUSTOM.equals(preset)) {
                throw AgentException.invalid("preset", "Unknown preset.");
            }
            for (final AgentActionRegistry.Action a : AgentActionRegistry.all()) {
                // with a preset only the actions outside presets are added individually
                if (isOn(form.get("act_" + a.id)) && (base == null || !a.presetable)) {
                    actions.add(a.id);
                }
            }
            into.actions = actions;
            into.presetLabel = AgentActionRegistry.presetOf(actions);
        }
        if (form.containsKey("limitsForm")) {
            final Agent.Limits d = into.limits == null ? Agent.Limits.defaults() : into.limits;
            into.limits = new Agent.Limits(split(form.get("domains")),
                    intField(form, "maxDepth", d.maxDepth), intField(form, "maxPages", d.maxPages),
                    intField(form, "maxParallelCrawls", d.maxParallelCrawls),
                    intField(form, "requestsPerMinute", d.requestsPerMinute),
                    intField(form, "maxTaskSeconds", d.maxTaskSeconds),
                    isOn(form.get("modelAllowed")));
        }
        return into;
    }

    /** Validate everything the store would check, without storing (for the wizard steps). */
    public static void validateDraft(final Agent.Builder b, final int upToStep) throws AgentException {
        final Agent a = b.build();
        if (upToStep >= 1 && (a.name.isEmpty() || a.name.length() > 80)) {
            throw AgentException.invalid("name", "The name must have 1 to 80 characters without control characters.");
        }
        if (upToStep >= 1 && a.description.length() > 500) {
            throw AgentException.invalid("description", "The description must not exceed 500 characters.");
        }
        if (upToStep >= 2 && !a.scope.allCollections && a.scope.collections.isEmpty()) {
            throw AgentException.invalid("scope", "Select at least one collection or grant the complete index explicitly.");
        }
        if (upToStep >= 3 && a.actions.isEmpty()) {
            throw AgentException.invalid("actions", "Select at least one action.");
        }
        if (upToStep >= 4) {
            AgentActionRegistry.validateGrant(a.kind, a.actions, a.limits);
        }
    }

    /** Token lifetime from the form (one of 30/90/180/365 days). */
    public static long tokenTtl(final Map<String, String> form) throws AgentException {
        final int days = intField(form, "expiresInDays", 90);
        for (final int d : TOKEN_DAYS) {
            if (d == days) {
                return days * AgentStore.DAY;
            }
        }
        throw AgentException.invalid("expiresInDays", "Choose 30, 90, 180 or 365 days.");
    }

    /** Grace period for a rotation (0, 15 or 60 minutes). */
    public static long rotationGrace(final Map<String, String> form) throws AgentException {
        final int minutes = intField(form, "graceMinutes", 0);
        if (minutes != 0 && minutes != 15 && minutes != 60) {
            throw AgentException.invalid("graceMinutes", "Choose 0, 15 or 60 minutes.");
        }
        return minutes * 60_000L;
    }

    /**
     * Clustro settings of a research worker. The agent key is only checked
     * for its format; it is stored in the runtime secret and never shown again.
     * An empty key keeps the existing one (if any).
     */
    public static JSONObject parseClustro(final Map<String, String> form, final JSONObject existing) throws AgentException {
        final String base = trim(form.get("clustroBaseUrl"));
        if (base.isEmpty()) {
            throw AgentException.invalid("clustroBaseUrl", "The Clustro base URL is required for a research worker.");
        }
        try {
            ScoutroActions.validateHttpUrl(base, "clustroBaseUrl");
        } catch (final ApiException e) {
            throw AgentException.invalid("clustroBaseUrl", "The Clustro base URL must be an http or https URL without credentials.");
        }
        final String workspace = trim(form.get("clustroWorkspaceId"));
        final String connection = trim(form.get("clustroConnectionId"));
        if (!SAFE_ID.matcher(workspace).matches()) {
            throw AgentException.invalid("clustroWorkspaceId", "The Clustro workspace id is required.");
        }
        if (!SAFE_ID.matcher(connection).matches()) {
            throw AgentException.invalid("clustroConnectionId", "The Clustro connection id is required.");
        }
        String key = trim(form.get("clustroAgentKey"));
        if (key.isEmpty() && existing != null) {
            key = existing.optString("clustroAgentKey", "");
        }
        if (!CLUSTRO_KEY.matcher(key).matches()) {
            throw AgentException.invalid("clustroAgentKey",
                    "The Clustro agent key starts with 'ak_' followed by 64 hexadecimal characters.");
        }
        final JSONObject out = new JSONObject(true);
        Json.put(out, "clustroBaseUrl", base.replaceAll("/+$", ""));
        Json.put(out, "clustroWorkspaceId", workspace);
        Json.put(out, "clustroConnectionId", connection);
        Json.put(out, "clustroAgentKey", key);
        return out;
    }

    // ------------------------------------------------------------------
    // wizard draft (hidden form field)
    // ------------------------------------------------------------------

    public static String encodeDraft(final Agent.Builder b) {
        final Agent a = b.build();
        final JSONObject o = Json.obj("name", a.name, "description", a.description, "kind", a.kind.id,
                "collections", new JSONArray(a.scope.collections), "allCollections", a.scope.allCollections,
                "actions", new JSONArray(a.actions), "presetLabel", a.presetLabel,
                "domains", new JSONArray(a.limits.domains), "maxDepth", a.limits.maxDepth, "maxPages", a.limits.maxPages,
                "maxParallelCrawls", a.limits.maxParallelCrawls, "requestsPerMinute", a.limits.requestsPerMinute,
                "maxTaskSeconds", a.limits.maxTaskSeconds, "modelAllowed", a.limits.modelAllowed);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(o.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The draft is client-side state: everything is validated again before it is stored. */
    public static Agent.Builder decodeDraft(final String encoded) throws AgentException {
        final Agent.Builder b = new Agent.Builder();
        if (encoded == null || encoded.isEmpty()) {
            return b;
        }
        final JSONObject o;
        try {
            o = new JSONObject(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
        } catch (final IllegalArgumentException | JSONException e) {
            throw AgentException.invalid("draft", "The wizard state is damaged; please start again.");
        }
        b.name = o.optString("name", "");
        b.description = o.optString("description", "");
        b.kind = Agent.Kind.of(o.optString("kind", "external"));
        b.scope = new Agent.Scope(strings(o.optJSONArray("collections")), o.optBoolean("allCollections", false));
        b.actions = strings(o.optJSONArray("actions"));
        b.presetLabel = o.optString("presetLabel", "");
        final Agent.Limits d = Agent.Limits.defaults();
        b.limits = new Agent.Limits(new ArrayList<>(strings(o.optJSONArray("domains"))), o.optInt("maxDepth", d.maxDepth),
                o.optInt("maxPages", d.maxPages), o.optInt("maxParallelCrawls", d.maxParallelCrawls),
                o.optInt("requestsPerMinute", d.requestsPerMinute), o.optInt("maxTaskSeconds", d.maxTaskSeconds),
                o.optBoolean("modelAllowed", false));
        return b;
    }

    // ------------------------------------------------------------------
    // collections
    // ------------------------------------------------------------------

    /** Collections that occur in the index (Solr facet), sorted; empty if the index cannot be read. */
    public static List<String> indexCollections() {
        try {
            final String body = new YaCyLoopback().getAdmin("solr/select", new YaCyLoopback.Params()
                    .add("q", "*:*").add("rows", 0).add("wt", "json").add("facet", "true")
                    .add("facet.field", "collection_sxt").add("facet.limit", 500).add("facet.mincount", 1));
            final JSONObject facets = new JSONObject(body).optJSONObject("facet_counts");
            final JSONArray values = facets == null || facets.optJSONObject("facet_fields") == null ? null
                    : facets.optJSONObject("facet_fields").optJSONArray("collection_sxt");
            final Set<String> out = new TreeSet<>();
            for (int i = 0; values != null && i < values.length(); i += 2) {
                final String c = values.optString(i);
                if (!c.startsWith("robot_")) { // YaCy's internal snippet fetch collections
                    out.add(c);
                }
            }
            return new ArrayList<>(out);
        } catch (final ApiException | JSONException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /** Collections of the discovery portal profiles (collection and legacy collections). */
    public static List<String> profileCollections() {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) {
            return new ArrayList<>();
        }
        return profileCollections(new File(sb.getAppPath(), "tools/scoutro/discovery/profiles.json"));
    }

    static List<String> profileCollections(final File profiles) {
        final Set<String> out = new LinkedHashSet<>();
        try {
            final JSONObject p = new JSONObject(new String(Files.readAllBytes(profiles.toPath()), StandardCharsets.UTF_8))
                    .optJSONObject("profiles");
            if (p != null) {
                for (final String name : p.keySet()) {
                    final JSONObject prof = p.optJSONObject(name);
                    if (prof != null) {
                        if (!prof.optString("collection", "").isEmpty()) {
                            out.add(prof.optString("collection"));
                        }
                        out.addAll(strings(prof.optJSONArray("legacy_collections")));
                    }
                }
            }
        } catch (final java.io.IOException | JSONException e) {
            // no profiles available
        }
        return new ArrayList<>(out);
    }

    // ------------------------------------------------------------------
    // connection status
    // ------------------------------------------------------------------

    /**
     * Status computed from facts, never stored as a claim:
     * revoked, paused, no_token, expired, never_connected, connected, stale.
     * "connected" means: a usable token fetched the capabilities of the
     * current grant within the last 24 hours.
     */
    public static String connectionState(final Agent agent, final AgentStore store) {
        if (agent.status == Agent.Status.REVOKED) {
            return "revoked";
        }
        if (agent.status == Agent.Status.PAUSED) {
            return "paused";
        }
        final long now = store.now();
        final List<TokenRecord> tokens = store.tokens(agent.id);
        boolean usable = false;
        boolean used = false;
        final Set<String> currentFingerprints = new LinkedHashSet<>();
        for (final TokenRecord t : tokens) {
            used |= t.lastUsedAt > 0;
            if (t.isUsable(now)) {
                usable = true;
                currentFingerprints.add(AgentAuthorizer.fingerprint(agent, t.publicId));
            }
        }
        if (!usable) {
            return tokens.isEmpty() ? "no_token" : "expired";
        }
        final JSONObject c = store.connection(agent.id);
        final long handshakeAt = c.optLong("handshakeAt", 0);
        if (handshakeAt == 0) {
            return used ? "stale" : "never_connected";
        }
        if (now - handshakeAt <= HANDSHAKE_TTL && currentFingerprints.contains(c.optString("fingerprint"))) {
            return "connected";
        }
        return "stale";
    }

    /** Runtime state of a research worker: not_reporting, reporting; with Clustro reachability. */
    public static JSONObject runtimeState(final Agent agent, final AgentStore store) {
        final long now = store.now();
        final JSONObject c = store.connection(agent.id);
        final long at = c.optLong("heartbeatAt", 0);
        final JSONObject hb = c.optJSONObject("heartbeat");
        final boolean reporting = at > 0 && now - at <= RUNTIME_TTL;
        final boolean clustro = reporting && hb != null && hb.optBoolean("clustroReachable", false)
                && now - hb.optLong("clustroCheckedAt", 0) <= RUNTIME_TTL;
        return Json.obj("reporting", reporting, "heartbeatAt", at == 0 ? null : AgentApi.iso(at),
                "clustroReachable", clustro,
                "modelConfigured", hb != null && hb.optBoolean("modelConfigured", false),
                "version", hb == null ? "" : hb.optString("version", ""),
                "lastError", hb == null ? "" : hb.optString("lastError", ""),
                "activeRuns", hb == null ? 0 : hb.optLong("activeRuns", 0));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    static boolean isOn(final String v) {
        return v != null && (v.equals("on") || v.equals("true") || v.equals("1"));
    }

    static List<String> split(final String s) {
        final List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        for (final String part : s.split("[\\s,;]+")) {
            final String t = part.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static Set<String> strings(final JSONArray a) {
        final Set<String> out = new LinkedHashSet<>();
        for (int i = 0; a != null && i < a.length(); i++) {
            final String s = a.optString(i, "");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    private static int intField(final Map<String, String> form, final String name, final int dflt) throws AgentException {
        final String v = trim(form.get(name));
        if (v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v);
        } catch (final NumberFormatException e) {
            throw AgentException.invalid(name, "'" + name + "' must be a whole number.");
        }
    }

    private static String trim(final String s) {
        return s == null ? "" : s.trim();
    }
}
