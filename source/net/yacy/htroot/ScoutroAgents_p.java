/*
 *  ScoutroAgents_p
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

package net.yacy.htroot;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.data.TransactionManager;
import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentTokens;
import net.yacy.scoutro.agents.ScoutroAgents;
import net.yacy.scoutro.agents.TokenRecord;
import net.yacy.scoutro.api.AgentAdmin;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;
import net.yacy.server.servletProperties;

/**
 * Administration → Agents &amp; Access: list of agents and the management of
 * one agent (grant, lifecycle, tokens, research worker settings, activity).
 */
public class ScoutroAgents_p {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final servletProperties prop = new servletProperties();
        noStore(prop);
        try {
            prop.put(TransactionManager.TRANSACTION_TOKEN_PARAM, TransactionManager.getTransactionToken(header));
        } catch (final IllegalArgumentException e) {
            // unauthorized: the page is protected anyway
        }
        prop.put("error", 0);
        prop.put("notice", 0);
        prop.put("newToken", 0);
        final ScoutroAgents agents;
        try {
            agents = ScoutroAgents.get();
        } catch (final AgentException e) {
            prop.put("error", 1);
            prop.putHTML("error_message", e.getMessage());
            prop.put("view", 0);
            prop.put("view_agents", 0);
            prop.put("view_audit", 0);
            return prop;
        }

        final String id = post == null ? "" : post.get("agent", "");
        final Agent selected = id.isEmpty() ? null : agents.store.agent(id);
        if (post != null && post.containsKey("op") && selected != null) {
            TransactionManager.checkPostTransaction(header, post);
            try {
                operate(agents, selected, post.get("op", ""), ScoutroAgentWizard_p.form(post), header, prop);
            } catch (final AgentException e) {
                prop.put("error", 1);
                prop.putHTML("error_message", e.getMessage());
            } catch (final IOException e) {
                prop.put("error", 1);
                prop.putHTML("error_message", "The agent store could not be written: " + e.getMessage());
            }
        }

        final Agent agent = selected == null ? null : agents.store.agent(selected.id); // reload after changes
        if (agent == null) {
            if (!id.isEmpty()) {
                prop.put("error", 1);
                prop.putHTML("error_message", "The agent does not exist.");
            }
            renderList(prop, agents);
        } else {
            renderDetail(prop, agents, agent, header);
        }
        return prop;
    }

    // ------------------------------------------------------------------
    // operations
    // ------------------------------------------------------------------

    private static void operate(final ScoutroAgents agents, final Agent agent, final String op,
            final Map<String, String> form, final RequestHeader header, final serverObjects prop)
            throws AgentException, IOException {
        final String client = header.getRemoteAddr();
        switch (op) {
            case "update": {
                final Agent.Builder b = agent.toBuilder();
                form.put("scopeForm", "1");
                form.put("actionsForm", "1");
                form.put("limitsForm", "1");
                AgentAdmin.parseGrant(b, form);
                final Agent updated = agents.store.updateAgent(agent.id, b);
                agents.audit.record(agent.id, "", "admin.agent.updated", "event", null, updated.scope.collections, 200, client);
                notice(prop, "The grant was saved. It applies to the next request of the agent.");
                break;
            }
            case "pause":
                agents.store.pause(agent.id);
                agents.audit.record(agent.id, "", "admin.agent.paused", "event", null, null, 200, client);
                notice(prop, "The agent is paused. All its requests are refused until it is resumed.");
                break;
            case "resume":
                agents.store.resume(agent.id);
                agents.audit.record(agent.id, "", "admin.agent.resumed", "event", null, null, 200, client);
                notice(prop, "The agent is active again.");
                break;
            case "revoke":
                if (!"on".equals(form.get("confirmRevoke"))) {
                    throw AgentException.invalid("confirmRevoke", "Confirm the revocation; it cannot be undone.");
                }
                agents.store.revoke(agent.id);
                agents.runtimeSecrets.delete(agent.id);
                agents.audit.record(agent.id, "", "admin.agent.revoked", "event", null, null, 200, client);
                notice(prop, "The agent and all its tokens are revoked.");
                break;
            case "rotate": {
                final AgentTokens.Issued t = agents.store.rotateToken(agent.id, AgentAdmin.tokenTtl(form),
                        AgentAdmin.rotationGrace(form));
                if (agent.kind == Agent.Kind.RESEARCH_WORKER) {
                    final JSONObject secret = agents.runtimeSecrets.read(agent.id);
                    if (secret != null) {
                        putJson(secret, "scoutroToken", t.plainText());
                        agents.runtimeSecrets.write(agent.id, secret);
                    }
                }
                agents.audit.record(agent.id, t.publicId, "admin.token.rotated", "event", null, null, 200, client);
                showToken(prop, agents, t, header, agent);
                break;
            }
            case "revokeToken": {
                final String tokenId = form.getOrDefault("token", "");
                if (!AgentTokens.isPublicId(tokenId)) {
                    throw AgentException.invalid("token", "Unknown token.");
                }
                agents.store.revokeToken(agent.id, tokenId);
                agents.audit.record(agent.id, tokenId, "admin.token.revoked", "event", null, null, 200, client);
                notice(prop, "The token is revoked.");
                break;
            }
            case "abandonCrawlStart": {
                final String marker = form.getOrDefault("startMarker", "");
                if (!marker.matches("[0-9a-f]{32}") || !"on".equals(form.get("confirmAbandon"))) {
                    throw AgentException.invalid("confirmAbandon", "Confirm that you checked the crawl monitor and "
                            + "that this start did not lead to a crawl that should be kept.");
                }
                agents.store.abandonCrawlStart(agent.id, marker);
                agents.audit.record(agent.id, "", "admin.crawl.start_abandoned", "event", null, null, 200, client);
                notice(prop, "The unconfirmed crawl start is marked as not started; its Idempotency-Key can start a crawl again.");
                break;
            }
            case "clustro": {
                if (agent.kind != Agent.Kind.RESEARCH_WORKER) {
                    throw AgentException.invalid("op", "Only research workers have Clustro settings.");
                }
                if (agent.status == Agent.Status.REVOKED) {
                    throw new AgentException(409, "agent_revoked", "A revoked agent cannot be changed.");
                }
                final JSONObject existing = agents.runtimeSecrets.read(agent.id);
                final JSONObject secret = AgentAdmin.parseClustro(form, existing);
                putJson(secret, "agentId", agent.id);
                putJson(secret, "scoutroToken", existing == null ? "" : existing.optString("scoutroToken", ""));
                agents.runtimeSecrets.write(agent.id, secret);
                agents.audit.record(agent.id, "", "admin.worker.clustro_updated", "event", null, null, 200, client);
                notice(prop, existing != null && !existing.optString("scoutroToken", "").isEmpty()
                        ? "The Clustro settings were saved."
                        : "The Clustro settings were saved. Rotate the token so that the worker also receives its Scoutro token.");
                break;
            }
            default:
                throw AgentException.invalid("op", "Unknown operation.");
        }
    }

    private static void notice(final serverObjects prop, final String message) {
        prop.put("notice", 1);
        prop.putHTML("notice_message", message);
    }

    private static void showToken(final serverObjects prop, final ScoutroAgents agents, final AgentTokens.Issued t,
            final RequestHeader header, final Agent agent) {
        prop.put("newToken", 1);
        prop.putHTML("newToken_token", t.plainText());
        prop.putHTML("newToken_tokenId", t.publicId);
        prop.putHTML("newToken_baseUrl", baseUrl(header));
        prop.put("newToken_worker", agent.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
        final TokenRecord rec = agents.store.tokens(agent.id).stream()
                .filter(r -> r.publicId.equals(t.publicId)).findFirst().orElse(null);
        prop.putHTML("newToken_expires", rec == null ? "" : iso(rec.expiresAt));
    }

    // ------------------------------------------------------------------
    // list
    // ------------------------------------------------------------------

    private static void renderList(final serverObjects prop, final ScoutroAgents agents) {
        prop.put("view", 0);
        final List<Agent> all = agents.store.agents();
        int i = 0;
        for (final Agent a : all) {
            final String p = "view_agents_" + i + "_";
            prop.putHTML(p + "id", a.id);
            prop.putHTML(p + "name", a.name);
            prop.put(p + "kind", a.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
            prop.putHTML(p + "status", a.status.id);
            prop.putHTML(p + "connection", AgentAdmin.connectionState(a, agents.store));
            prop.putHTML(p + "scope", scopeText(a));
            prop.putHTML(p + "preset", a.presetLabel.isEmpty() ? "custom" : a.presetLabel);
            prop.put(p + "actionCount", a.actions.size());
            long lastUsed = 0;
            long nextExpiry = 0;
            for (final TokenRecord t : agents.store.tokens(a.id)) {
                lastUsed = Math.max(lastUsed, t.lastUsedAt);
                if (t.isUsable(agents.store.now()) && (nextExpiry == 0 || t.expiresAt < nextExpiry)) {
                    nextExpiry = t.expiresAt;
                }
            }
            prop.putHTML(p + "lastUsed", lastUsed == 0 ? "never" : iso(lastUsed));
            prop.putHTML(p + "expires", nextExpiry == 0 ? "no usable token" : iso(nextExpiry));
            i++;
        }
        prop.put("view_agents", i);
        prop.put("view_empty", i == 0 ? 1 : 0);
        audit(prop, "view_audit", agents, null);
    }

    // ------------------------------------------------------------------
    // detail
    // ------------------------------------------------------------------

    private static void renderDetail(final serverObjects prop, final ScoutroAgents agents, final Agent a,
            final RequestHeader header) {
        prop.put("view", 1);
        final String p = "view_";
        prop.putHTML(p + "id", a.id);
        prop.putHTML(p + "name", a.name);
        prop.putHTML(p + "description", a.description);
        prop.put(p + "kind", a.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
        prop.putHTML(p + "status", a.status.id);
        prop.put(p + "active", a.status == Agent.Status.ACTIVE ? 1 : 0);
        prop.put(p + "paused", a.status == Agent.Status.PAUSED ? 1 : 0);
        prop.put(p + "editable", a.status != Agent.Status.REVOKED ? 1 : 0);
        prop.put(p + "revision", a.revision);
        prop.putHTML(p + "connection", AgentAdmin.connectionState(a, agents.store));
        final JSONObject conn = agents.store.connection(a.id);
        prop.putHTML(p + "handshakeAt", conn.optLong("handshakeAt", 0) == 0 ? "never" : iso(conn.optLong("handshakeAt")));
        prop.putHTML(p + "baseUrl", baseUrl(header));
        final String tt = prop.get(TransactionManager.TRANSACTION_TOKEN_PARAM, "");
        for (final String prefix : new String[] {p, p + "editable_", p + "rotatable_", p + "worker_editable_"}) {
            prop.putHTML(prefix + "transactionToken", tt);
            prop.putHTML(prefix + "id", a.id);
        }

        // grant (edit form)
        final String e = p + "editable_";
        prop.putHTML(e + "name", a.name);
        prop.putHTML(e + "description", a.description);
        int i = 0;
        final java.util.Set<String> known = new java.util.LinkedHashSet<>(AgentAdmin.profileCollections());
        known.addAll(AgentAdmin.indexCollections());
        known.addAll(a.scope.collections);
        for (final String c : known) {
            prop.putHTML(e + "cols_" + i + "_name", c);
            prop.put(e + "cols_" + i + "_checked", a.scope.collections.contains(c) ? 1 : 0);
            i++;
        }
        prop.put(e + "cols", i);
        prop.put(e + "all", a.scope.allCollections ? 1 : 0);
        actionList(prop, e + "actions", a, a.actions);
        limitFields(prop, e, a);
        prop.put(e + "worker", a.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
        prop.put(e + "worker_maxTaskSeconds", a.limits.maxTaskSeconds);
        prop.put(e + "worker_modelAllowed", a.limits.modelAllowed ? 1 : 0);
        summary(prop, p + "summary_", a);

        // tokens
        i = 0;
        final long now = agents.store.now();
        for (final TokenRecord t : agents.store.tokens(a.id)) {
            final String tp = p + "tokens_" + i + "_";
            prop.putHTML(tp + "id", t.publicId);
            prop.putHTML(tp + "state", t.state(now));
            prop.putHTML(tp + "created", iso(t.createdAt));
            prop.putHTML(tp + "expires", iso(t.expiresAt));
            prop.putHTML(tp + "lastUsed", t.lastUsedAt == 0 ? "never" : iso(t.lastUsedAt));
            prop.putHTML(tp + "lastIp", t.lastUsedIp);
            prop.put(tp + "revocable", !t.isRevoked(now) && !t.isExpired(now) ? 1 : 0);
            prop.putHTML(tp + "revocable_agent", a.id);
            prop.putHTML(tp + "revocable_id", t.publicId);
            prop.putHTML(tp + "revocable_transactionToken", tt);
            i++;
        }
        prop.put(p + "tokens", i);

        // crawl starts whose outcome is not confirmed (crash or storage fault between start and record)
        i = 0;
        for (final net.yacy.scoutro.agents.CrawlRecord c : agents.store.crawls(a.id)) {
            if (!c.isStarting()) {
                continue;
            }
            final String cp = p + "hasPending_pending_" + i + "_";
            prop.putHTML(cp + "url", c.url);
            prop.putHTML(cp + "collection", c.collection);
            prop.putHTML(cp + "clientRef", c.clientRef.isEmpty() ? "-" : c.clientRef);
            prop.putHTML(cp + "recorded", iso(c.createdAt));
            prop.putHTML(cp + "marker", c.startMarker);
            prop.putHTML(cp + "agent", a.id);
            prop.putHTML(cp + "transactionToken", tt);
            i++;
        }
        prop.put(p + "hasPending_pending", i);
        prop.put(p + "hasPending", i > 0 ? 1 : 0);
        prop.put(p + "rotatable", a.status != Agent.Status.REVOKED ? 1 : 0);

        // research worker runtime
        prop.put(p + "worker", a.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
        if (a.kind == Agent.Kind.RESEARCH_WORKER) {
            final String w = p + "worker_";
            final JSONObject rt = AgentAdmin.runtimeState(a, agents.store);
            prop.put(w + "reporting", rt.optBoolean("reporting") ? 1 : 0);
            prop.putHTML(w + "heartbeatAt", rt.isNull("heartbeatAt") ? "never" : rt.optString("heartbeatAt"));
            prop.put(w + "clustro", rt.optBoolean("clustroReachable") ? 1 : 0);
            prop.put(w + "model", rt.optBoolean("modelConfigured") ? 1 : 0);
            prop.putHTML(w + "version", rt.optString("version"));
            prop.putHTML(w + "lastError", rt.optString("lastError"));
            prop.put(w + "activeRuns", rt.optLong("activeRuns"));
            JSONObject secret = null;
            try {
                secret = agents.runtimeSecrets.read(a.id);
            } catch (final IOException ex) {
                // shown as missing
            }
            prop.put(w + "secret", secret == null ? 0 : 1);
            prop.putHTML(w + "secretFile", "DATA/SETTINGS/agent-runtime/" + a.id + ".secret");
            prop.putHTML(w + "editable_clustroBaseUrl", secret == null ? "" : secret.optString("clustroBaseUrl"));
            prop.putHTML(w + "editable_clustroWorkspaceId", secret == null ? "" : secret.optString("clustroWorkspaceId"));
            prop.putHTML(w + "editable_clustroConnectionId", secret == null ? "" : secret.optString("clustroConnectionId"));
            prop.put(w + "editable_keyStored", secret != null && !secret.optString("clustroAgentKey", "").isEmpty() ? 1 : 0);
            prop.put(w + "editable", a.status != Agent.Status.REVOKED ? 1 : 0);
        }
        audit(prop, p + "audit", agents, a.id);
    }

    private static void audit(final serverObjects prop, final String key, final ScoutroAgents agents, final String agentId) {
        int i = 0;
        for (final JSONObject e : agents.audit.recent(agentId, null, 50)) {
            final String p = key + "_" + i + "_";
            prop.putHTML(p + "ts", iso(e.optLong("ts")));
            final Agent a = agents.store.agent(e.optString("agentId"));
            prop.putHTML(p + "agent", a == null ? (e.optString("agentId").isEmpty() ? "-" : e.optString("agentId")) : a.name);
            prop.putHTML(p + "action", e.optString("action"));
            prop.putHTML(p + "decision", e.optString("decision"));
            prop.putHTML(p + "reason", e.optString("reason"));
            prop.put(p + "status", e.optInt("status"));
            prop.putHTML(p + "client", e.optString("client"));
            i++;
        }
        prop.put(key, i);
    }

    // ------------------------------------------------------------------
    // shared with the wizard
    // ------------------------------------------------------------------

    /** Pages that may show a token must never be cached or leak through the referrer. */
    static void noStore(final servletProperties prop) {
        final ResponseHeader h = new ResponseHeader(200);
        h.put("Cache-Control", "no-store, no-cache, must-revalidate, private");
        h.put("Pragma", "no-cache");
        h.put("Referrer-Policy", "no-referrer");
        prop.setOutgoingHeader(h);
    }

    static void actionList(final serverObjects prop, final String key, final Agent agent, final Set<String> checked) {
        int i = 0;
        for (final AgentActionRegistry.Action a : AgentActionRegistry.all()) {
            if (!a.kinds.contains(agent.kind)) {
                continue;
            }
            final String p = key + "_" + i + "_";
            prop.putHTML(p + "id", a.id);
            prop.putHTML(p + "label", a.label);
            prop.putHTML(p + "description", a.description);
            prop.putHTML(p + "risk", a.risk.id);
            prop.put(p + "checked", checked != null && checked.contains(a.id) ? 1 : 0);
            prop.put(p + "warning", a.presetable ? 0 : 1);
            i++;
        }
        prop.put(key, i);
    }

    static void limitFields(final serverObjects prop, final String p, final Agent a) {
        prop.putHTML(p + "domains", String.join("\n", a.limits.domains));
        prop.put(p + "maxDepth", a.limits.maxDepth);
        prop.put(p + "maxPages", a.limits.maxPages);
        prop.put(p + "maxParallelCrawls", a.limits.maxParallelCrawls);
        prop.put(p + "requestsPerMinute", a.limits.requestsPerMinute);
        prop.put(p + "maxTaskSeconds", a.limits.maxTaskSeconds);
        prop.put(p + "modelAllowed", a.limits.modelAllowed ? 1 : 0);
        prop.put(p + "limitDepth", Agent.Limits.MAX_DEPTH);
        prop.put(p + "limitPages", Agent.Limits.MAX_PAGES);
        prop.put(p + "limitParallel", Agent.Limits.MAX_PARALLEL);
        prop.put(p + "limitRpm", Agent.Limits.MAX_RPM);
    }

    static void summary(final serverObjects prop, final String p, final Agent a) {
        prop.putHTML(p + "name", a.name);
        prop.putHTML(p + "kind", a.kind == Agent.Kind.RESEARCH_WORKER ? "Scoutro research agent (Clustro worker)" : "External access");
        prop.putHTML(p + "scope", scopeText(a));
        prop.putHTML(p + "actions", String.join(", ", a.actions));
        prop.putHTML(p + "domains", a.limits.domains.isEmpty() ? "-" : String.join(", ", a.limits.domains));
        prop.put(p + "maxDepth", a.limits.maxDepth);
        prop.put(p + "maxPages", a.limits.maxPages);
        prop.put(p + "maxParallelCrawls", a.limits.maxParallelCrawls);
        prop.put(p + "requestsPerMinute", a.limits.requestsPerMinute);
    }

    static String scopeText(final Agent a) {
        return a.scope.allCollections ? "complete local index" : String.join(", ", a.scope.collections);
    }

    /** Agent API base URL as seen from this request; agents may reach Scoutro under another host. */
    static String baseUrl(final RequestHeader header) {
        String host = header.get("Host", "");
        if (host.isEmpty() || !host.matches("[A-Za-z0-9.:\\[\\]-]{1,260}")) {
            host = "<scoutro-host>";
        }
        final String proto = "https".equalsIgnoreCase(header.get("X-Forwarded-Proto", "")) || "https".equalsIgnoreCase(header.getScheme()) ? "https" : "http";
        return proto + "://" + host + AgentActionRegistry.BASE_PATH;
    }

    static String iso(final long millis) {
        return Instant.ofEpochMilli(millis).toString();
    }

    static void putJson(final JSONObject o, final String key, final Object value) {
        try {
            o.put(key, value);
        } catch (final JSONException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
