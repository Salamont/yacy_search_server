/*
 *  ScoutroAgentWizard_p
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;

import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.ResponseHeader;
import net.yacy.data.TransactionManager;
import net.yacy.scoutro.agents.Agent;
import net.yacy.scoutro.agents.AgentActionRegistry;
import net.yacy.scoutro.agents.AgentException;
import net.yacy.scoutro.agents.AgentTokens;
import net.yacy.scoutro.agents.ScoutroAgents;
import net.yacy.scoutro.api.AgentAdmin;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;
import net.yacy.server.servletProperties;

/**
 * Administration → Agents &amp; Access → new agent. Six steps: identity and
 * kind, data scope, actions, limits, access, connection. The draft travels
 * in a hidden field and is validated again on every step and before it is
 * stored; the token is shown once, in the answer to the final POST, with
 * caching disabled.
 */
public class ScoutroAgentWizard_p {

    static final int STEPS = 6;

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final servletProperties prop = new servletProperties();
        ScoutroAgents_p.noStore(prop);
        try {
            prop.put(TransactionManager.TRANSACTION_TOKEN_PARAM, TransactionManager.getTransactionToken(header));
        } catch (final IllegalArgumentException e) {
            // unauthorized: the page is protected anyway
        }
        final ScoutroAgents agents;
        try {
            agents = ScoutroAgents.get();
        } catch (final AgentException e) {
            prop.put("unavailable", 1);
            prop.putHTML("unavailable_message", e.getMessage());
            prop.put("step", 0);
            return prop;
        }
        prop.put("unavailable", 0);

        int step = 1;
        Agent.Builder draft = new Agent.Builder();
        if (post != null && post.containsKey("step")) {
            TransactionManager.checkPostTransaction(header, post);
            final Map<String, String> form = form(post);
            final int submitted = Math.max(1, Math.min(5, post.getInt("step", 1)));
            try {
                draft = AgentAdmin.decodeDraft(form.get("draft"));
                if (post.containsKey("back")) {
                    step = Math.max(1, submitted - 1);
                } else {
                    AgentAdmin.parseGrant(draft, form);
                    normalizeForKind(draft);
                    AgentAdmin.validateDraft(draft, submitted);
                    if (submitted < 5) {
                        step = submitted + 1;
                    } else {
                        step = create(agents, draft, form, header, prop);
                    }
                }
            } catch (final AgentException e) {
                step = submitted;
                prop.put("error", 1);
                prop.putHTML("error_message", e.getMessage());
                prop.putHTML("error_field", e.field() == null ? "" : e.field());
            }
        }
        if (!prop.containsKey("error")) {
            prop.put("error", 0);
        }
        render(prop, draft, step);
        return prop;
    }

    /** The model flag only applies to research workers. */
    private static void normalizeForKind(final Agent.Builder draft) {
        if (draft.kind == Agent.Kind.EXTERNAL && draft.limits.modelAllowed) {
            final Agent.Limits l = draft.limits;
            draft.limits = new Agent.Limits(l.domains, l.maxDepth, l.maxPages, l.maxParallelCrawls,
                    l.requestsPerMinute, l.maxTaskSeconds, false);
        }
    }

    private static int create(final ScoutroAgents agents, final Agent.Builder draft, final Map<String, String> form,
            final RequestHeader header, final serverObjects prop) throws AgentException {
        final long ttl = AgentAdmin.tokenTtl(form);
        final JSONObject clustro = draft.kind == Agent.Kind.RESEARCH_WORKER ? AgentAdmin.parseClustro(form, null) : null;
        final Agent agent;
        final AgentTokens.Issued token;
        try {
            agent = agents.store.createAgent(draft);
            token = agents.store.issueToken(agent.id, ttl);
            if (clustro != null) {
                ScoutroAgents_p.putJson(clustro, "agentId", agent.id);
                ScoutroAgents_p.putJson(clustro, "scoutroToken", token.plainText());
                agents.runtimeSecrets.write(agent.id, clustro);
            }
        } catch (final IOException e) {
            throw new AgentException(500, "agent_store_unavailable", "The agent could not be stored: " + e.getMessage());
        }
        agents.audit.record(agent.id, token.publicId, "admin.agent.created", "event", null,
                agent.scope.collections, 200, header.getRemoteAddr());
        prop.putHTML("step_created_id", agent.id);
        prop.putHTML("step_created_name", agent.name);
        prop.putHTML("step_created_token", token.plainText());
        prop.putHTML("step_created_tokenId", token.publicId);
        prop.putHTML("step_created_expires", java.time.Instant.ofEpochMilli(agents.store.now() + ttl).toString());
        prop.putHTML("step_created_baseUrl", ScoutroAgents_p.baseUrl(header));
        prop.put("step_created_worker", agent.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
        prop.putHTML("step_created_worker_secretFile", "DATA/SETTINGS/agent-runtime/" + agent.id + ".secret");
        prop.putHTML("step_created_worker_id", agent.id);
        prop.putHTML("step_created_state", AgentAdmin.connectionState(agent, agents.store));
        return 6;
    }

    // ------------------------------------------------------------------
    // rendering
    // ------------------------------------------------------------------

    private static void render(final serverObjects prop, final Agent.Builder draft, final int step) {
        final Agent d = draft.build();
        prop.put("step", step);
        prop.put("stepNumber", step);
        prop.putHTML("draft", step == 6 ? "" : AgentAdmin.encodeDraft(draft));
        final String p = "step_";
        prop.put("progress", STEPS);
        for (int i = 0; i < STEPS; i++) {
            prop.put("progress_" + i + "_n", i + 1);
            prop.put("progress_" + i + "_active", i + 1 == step ? 1 : 0);
            prop.put("progress_" + i + "_label", i);
        }

        // step 1
        prop.putHTML(p + "name", d.name);
        prop.putHTML(p + "description", d.description);
        prop.put(p + "kindExternal", d.kind == Agent.Kind.EXTERNAL ? 1 : 0);
        prop.put(p + "kindWorker", d.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);

        // step 2
        final Set<String> known = new LinkedHashSet<>(AgentAdmin.profileCollections());
        final List<String> index = AgentAdmin.indexCollections();
        known.addAll(index);
        known.addAll(d.scope.collections);
        int i = 0;
        for (final String c : known) {
            prop.putHTML(p + "cols_" + i + "_name", c);
            prop.put(p + "cols_" + i + "_checked", d.scope.collections.contains(c) ? 1 : 0);
            prop.put(p + "cols_" + i + "_indexed", index.contains(c) ? 1 : 0);
            i++;
        }
        prop.put(p + "cols", i);
        prop.put(p + "all", d.scope.allCollections ? 1 : 0);

        // step 3
        final String preset = d.presetLabel.isEmpty() ? AgentActionRegistry.PRESET_RESEARCH : d.presetLabel;
        prop.put(p + "presetResearch", AgentActionRegistry.PRESET_RESEARCH.equals(preset) ? 1 : 0);
        prop.put(p + "presetResearchCrawl", AgentActionRegistry.PRESET_RESEARCH_CRAWL.equals(preset) ? 1 : 0);
        prop.put(p + "presetCustom", AgentActionRegistry.PRESET_CUSTOM.equals(preset) ? 1 : 0);
        ScoutroAgents_p.actionList(prop, p + "actions", d, d.actions.isEmpty() && d.presetLabel.isEmpty()
                ? AgentActionRegistry.preset(AgentActionRegistry.PRESET_RESEARCH) : d.actions);

        // step 4
        ScoutroAgents_p.limitFields(prop, p, d);

        // step 5: summary
        ScoutroAgents_p.summary(prop, p + "summary_", d);
        prop.put(p + "worker", d.kind == Agent.Kind.RESEARCH_WORKER ? 1 : 0);
    }

    static Map<String, String> form(final serverObjects post) {
        final Map<String, String> form = new LinkedHashMap<>();
        for (final Map.Entry<String, String> e : post.entrySet()) {
            form.put(e.getKey(), e.getValue());
        }
        return form;
    }
}
