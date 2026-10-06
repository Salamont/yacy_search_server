/*
 *  KnowledgeRead
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.json.JSONObject;

import net.yacy.scoutro.knowledge.KgException;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.read.BusinessGraph;
import net.yacy.scoutro.knowledge.read.BusinessView;
import net.yacy.scoutro.knowledge.read.KgExport;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * Read routes of the knowledge graph (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 8.2),
 * shared by the administrator routes {@code /v1/kg/...} and the agent routes
 * {@code /agent/v1/kg/...}:
 * <ul>
 * <li>{@code GET entities?q&type&host&quality&offset&limit}</li>
 * <li>{@code GET entities/{id}}, {@code GET entities/{id}/statements?predicate&direction&include&offset&limit}</li>
 * <li>{@code GET statements/{id}}, {@code GET statements/{id}/evidence?offset&limit}</li>
 * <li>{@code GET hosts/{host}/entities?offset&limit}</li>
 * <li>{@code GET sources/{docId}?offset&limit}</li>
 * <li>{@code GET export?cursor&limit&include=evidence} and {@code GET changes?cursor&limit&expand} (8.3)</li>
 * <li>vocabulary 2 (package 6): {@code GET entities/{id}/business?include=hidden_jobs} (the business view),
 * {@code GET entities/{id}/neighborhood?depth&limit&offset&types&weak&derived&suggested&values&include=stale},
 * {@code GET compare?category&limit}, {@code GET derived?kind&entity&offset&limit}, {@code GET facets}; and the
 * filters {@code industry}, {@code category}, {@code audience} of {@code GET entities}</li>
 * </ul>
 * The caller decides the collections ({@code null} = all, for the
 * administrator without filter); everything is computed over the evidence of
 * those collections only. Unknown parameters are refused; an invisible object
 * is {@code 404 not_found} like a missing one.
 */
final class KnowledgeRead {

    static final Set<String> QUALITIES = Set.of("supported", "uncertain", "stale");
    /** Base of the administrator routes; the agent routes use {@link #AGENT_BASE}. */
    static final String ADMIN_BASE = "/scoutro/api/v1/kg";
    static final String AGENT_BASE = "/scoutro/api/agent/v1/kg";
    private static final int MAX_CURSOR = 80;

    private final Supplier<KgRuntime> runtime;
    private final String base;

    KnowledgeRead(final Supplier<KgRuntime> runtime) {
        this(runtime, ADMIN_BASE);
    }

    /** @param base the route base, for the full-sync hint of an expired cursor */
    KnowledgeRead(final Supplier<KgRuntime> runtime, final String base) {
        this.runtime = runtime;
        this.base = base;
    }

    /** True for the read resources ({@code parts[0]} after {@code kg}). */
    static boolean handles(final String resource) {
        return "entities".equals(resource) || "statements".equals(resource) || "hosts".equals(resource) || "sources".equals(resource)
                || "export".equals(resource) || "changes".equals(resource) || "compare".equals(resource) || "derived".equals(resource)
                || "facets".equals(resource);
    }

    /** Resources without a sub path (for the administrator router). */
    static boolean single(final String resource) {
        return "entities".equals(resource) || "export".equals(resource) || "changes".equals(resource) || "compare".equals(resource)
                || "derived".equals(resource) || "facets".equals(resource);
    }

    /**
     * @param parts       the path after {@code kg}, e.g. {@code [entities, kge_..., statements]}
     * @param collections the viewer's collections, null for all
     */
    JSONObject route(final String method, final List<String> parts, final Map<String, String> q, final List<String> collections)
            throws ApiException {
        if (!"GET".equals(method)) {
            throw new ApiException(405, "method_not_allowed", "Knowledge graph reads use GET.", Json.obj("allowed", "GET"));
        }
        final String resource = parts.get(0);
        if (!known(parts)) {
            throw notFound(); // before the state check: an unknown path is 404 also while the graph is off
        }
        try {
            final KgRuntime r = this.runtime.get();
            if (r == null) {
                throw new KgException(KgException.DISABLED, "not started");
            }
            final boolean agent = AGENT_BASE.equals(this.base);
            // agents: evidence without model names, and no graph-wide backlog (it counts every collection)
            final KgReader reader = agent ? r.reader().forAgents() : r.reader();
            final Viewer viewer = reader.viewer(collections);
            final JSONObject out;
            switch (resource) {
                case "entities":
                    if (parts.size() == 1) {
                        allow(q, "q", "type", "host", "quality", "offset", "limit", "collection", "industry", "category", "audience");
                        final KgReader.EntityQuery eq = new KgReader.EntityQuery();
                        eq.q = text(q, "q", 200);
                        eq.industry = code(q, "industry", net.yacy.scoutro.knowledge.vocab.Nace.CODE);
                        eq.category = code(q, "category", CODE);
                        eq.audience = code(q, "audience", CODE);
                        eq.type = oneOf(q, "type", Vocabulary.TYPES);
                        eq.host = q.get("host") == null || q.get("host").isEmpty() ? null : SeoAnalysis.host(q.get("host"));
                        eq.quality = oneOf(q, "quality", QUALITIES);
                        eq.offset = intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET);
                        eq.limit = intParam(q, "limit", 25, 1, KgReader.MAX_LIMIT);
                        out = reader.entities(eq, viewer);
                    } else if (parts.size() == 2) {
                        allow(q, "collection");
                        out = reader.entity(id(parts.get(1), KgReader.ENTITY_ID, "entity"), viewer);
                    } else if (parts.size() == 3 && "business".equals(parts.get(2))) {
                        allow(q, "include", "collection");
                        out = new BusinessView(reader).entity(id(parts.get(1), KgReader.ENTITY_ID, "entity"), viewer,
                                oneOf(q, "include", Set.of("hidden_jobs")) != null);
                    } else if (parts.size() == 3 && "neighborhood".equals(parts.get(2))) {
                        allow(q, "depth", "limit", "offset", "types", "weak", "derived", "suggested", "values", "include", "collection");
                        final BusinessGraph.Query nq = new BusinessGraph.Query();
                        nq.depth = intParam(q, "depth", 1, 1, BusinessGraph.MAX_DEPTH);
                        nq.limit = intParam(q, "limit", 50, 1, BusinessGraph.MAX_NODES);
                        nq.offset = intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET);
                        nq.types = types(q);
                        nq.weak = bool(q, "weak", false);
                        nq.derived = bool(q, "derived", true);
                        nq.suggested = bool(q, "suggested", false);
                        nq.values = bool(q, "values", true);
                        nq.stale = oneOf(q, "include", Set.of("stale")) != null;
                        out = new BusinessGraph(reader).neighborhood(id(parts.get(1), KgReader.ENTITY_ID, "entity"), nq, viewer);
                    } else if (parts.size() == 3 && "statements".equals(parts.get(2))) {
                        allow(q, "predicate", "direction", "include", "offset", "limit", "collection");
                        final String direction = oneOf(q, "direction", Set.of("in", "out"));
                        final String include = oneOf(q, "include", Set.of("stale"));
                        out = reader.entityStatements(id(parts.get(1), KgReader.ENTITY_ID, "entity"),
                                oneOf(q, "predicate", Vocabulary.PREDICATES.keySet()), "in".equals(direction), include != null,
                                intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET), intParam(q, "limit", 25, 1, KgReader.MAX_LIMIT), viewer);
                    } else {
                        throw notFound();
                    }
                    break;
                case "statements":
                    if (parts.size() == 2) {
                        allow(q, "collection");
                        out = reader.statement(id(parts.get(1), KgReader.STATEMENT_ID, "statement"), viewer);
                    } else if (parts.size() == 3 && "evidence".equals(parts.get(2))) {
                        allow(q, "offset", "limit", "collection");
                        out = reader.evidence(id(parts.get(1), KgReader.STATEMENT_ID, "statement"),
                                intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET),
                                intParam(q, "limit", 10, 1, KgReader.MAX_EVIDENCE_LIMIT), viewer);
                    } else {
                        throw notFound();
                    }
                    break;
                case "hosts":
                    if (parts.size() != 3 || !"entities".equals(parts.get(2))) {
                        throw notFound();
                    }
                    allow(q, "offset", "limit", "collection");
                    out = reader.hostEntities(SeoAnalysis.host(parts.get(1)), intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET),
                            intParam(q, "limit", 25, 1, KgReader.MAX_LIMIT), viewer);
                    break;
                case "sources":
                    if (parts.size() != 2) {
                        throw notFound();
                    }
                    allow(q, "offset", "limit", "collection");
                    out = reader.source(id(parts.get(1), KgReader.DOC_ID, "document"), intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET),
                            intParam(q, "limit", 50, 1, KgReader.MAX_LIMIT), viewer);
                    break;
                case "export":
                    if (parts.size() != 1) {
                        throw notFound();
                    }
                    allow(q, "cursor", "limit", "include", "collection");
                    out = new KgExport(reader).page(cursor(q), intParam(q, "limit", 100, 1, KgExport.MAX_LIMIT),
                            oneOf(q, "include", Set.of("evidence")) != null, viewer);
                    break;
                case "compare": {
                    allow(q, "category", "limit", "collection");
                    final String category = code(q, "category", CODE);
                    if (category == null) {
                        throw ApiException.invalid("category", "A service category code is required (e.g. care/kurzzeitpflege).");
                    }
                    out = new BusinessGraph(reader).compare(category, intParam(q, "limit", 50, 1, KgReader.MAX_LIMIT), viewer);
                    break;
                }
                case "derived":
                    allow(q, "kind", "entity", "offset", "limit", "collection");
                    out = new BusinessGraph(reader).derived(oneOf(q, "kind", Set.of(Vocabulary.LINKED_TO, Vocabulary.SAME_OPERATOR,
                            Vocabulary.SUGGESTED_CUSTOMER, Vocabulary.SUGGESTED_PARTNER)),
                            q.get("entity") == null || q.get("entity").isEmpty() ? null : id(q.get("entity"), KgReader.ENTITY_ID, "entity"),
                            intParam(q, "offset", 0, 0, KgReader.MAX_OFFSET), intParam(q, "limit", 25, 1, KgReader.MAX_LIMIT), viewer);
                    break;
                case "facets":
                    allow(q, "collection");
                    out = new BusinessGraph(reader).facets(viewer);
                    break;
                case "changes": {
                    if (parts.size() != 1) {
                        throw notFound();
                    }
                    allow(q, "cursor", "limit", "expand", "collection");
                    final boolean expand = "true".equals(oneOf(q, "expand", Set.of("true", "false")));
                    out = new KgExport(reader).changes(cursor(q), intParam(q, "limit", 100, 1,
                            expand ? KgExport.MAX_EXPANDED_CHANGES : KgExport.MAX_CHANGES_LIMIT), expand, viewer);
                    break;
                }
                default:
                    throw notFound();
            }
            final JSONObject lag = agent ? null : r.lag();
            if (lag != null) {
                KgJson.put(out, "lag", lag);
            }
            return out;
        } catch (final KgReader.NotFound e) {
            throw new ApiException(404, "not_found", "No such knowledge graph object in the visible collections.");
        } catch (final KgException e) {
            throw KnowledgeApi.toApi(e, this.base + "/export");
        }
    }

    private static String cursor(final Map<String, String> q) throws ApiException {
        final String v = q.get("cursor");
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.length() > MAX_CURSOR) {
            throw new ApiException(400, KgException.INVALID_CURSOR, "The cursor is not valid.", Json.obj("field", "cursor"));
        }
        return v;
    }

    /** True for the path shapes of the read routes. */
    static boolean known(final List<String> p) {
        final int n = p.size();
        switch (p.get(0)) {
            case "entities":
                return n == 1 || n == 2 || n == 3 && ("statements".equals(p.get(2)) || "business".equals(p.get(2))
                        || "neighborhood".equals(p.get(2)));
            case "statements":
                return n == 2 || n == 3 && "evidence".equals(p.get(2));
            case "hosts":
                return n == 3 && "entities".equals(p.get(2));
            case "sources":
                return n == 2;
            case "export":
            case "changes":
            case "compare":
            case "derived":
            case "facets":
                return n == 1;
            default:
                return false;
        }
    }

    private static ApiException notFound() {
        return new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }

    private static void allow(final Map<String, String> q, final String... names) throws ApiException {
        final Set<String> allowed = Set.of(names);
        for (final String k : q.keySet()) {
            if (!allowed.contains(k)) {
                throw ApiException.invalid(k, "Unknown parameter '" + k + "'. Allowed: " + String.join(", ", names) + ".");
            }
        }
    }

    private static String id(final String value, final java.util.regex.Pattern pattern, final String what) throws ApiException {
        if (value == null || !pattern.matcher(value).matches()) {
            throw ApiException.invalid("id", "Invalid " + what + " ID.");
        }
        return value;
    }

    /** A vocabulary code ({@code care/tagespflege}, {@code b2b}, {@code construction.tga}). */
    static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("^[a-z][a-z0-9_]{0,31}(?:[/.][a-z][a-z0-9_]{0,47})?$");

    private static String code(final Map<String, String> q, final String name, final java.util.regex.Pattern pattern) throws ApiException {
        final String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (v.length() > 80 || !pattern.matcher(v).matches()) {
            throw ApiException.invalid(name, "Field '" + name + "' is not a valid code.");
        }
        return v;
    }

    private static boolean bool(final Map<String, String> q, final String name, final boolean dflt) throws ApiException {
        final String v = oneOf(q, name, Set.of("true", "false"));
        return v == null ? dflt : "true".equals(v);
    }

    /** Edge types of a neighbourhood: predicates of relations and values, derived kinds; comma-separated. */
    private static Set<String> types(final Map<String, String> q) throws ApiException {
        final String v = q.get("types");
        if (v == null || v.isEmpty()) {
            return null;
        }
        final Set<String> allowed = new java.util.TreeSet<>();
        for (final Vocabulary.Predicate p : Vocabulary.PREDICATES.values()) {
            if (p.relation) {
                allowed.add(p.name);
            }
        }
        allowed.addAll(BusinessGraph.VALUE_EDGES);
        allowed.addAll(Vocabulary.DERIVED_KINDS.subList(1, Vocabulary.DERIVED_KINDS.size()));
        final Set<String> out = new java.util.TreeSet<>();
        for (final String t : v.split(",")) {
            if (!allowed.contains(t.trim())) {
                throw ApiException.invalid("types", "Unknown edge type '" + t.trim() + "'. Allowed: " + String.join(", ", allowed) + ".");
            }
            out.add(t.trim());
        }
        return out;
    }

    private static String text(final Map<String, String> q, final String name, final int max) throws ApiException {
        final String v = q.get(name);
        if (v == null || v.trim().isEmpty()) {
            return null;
        }
        if (v.length() > max) {
            throw ApiException.invalid(name, "At most " + max + " characters.");
        }
        return v.trim();
    }

    private static String oneOf(final Map<String, String> q, final String name, final Set<String> values) throws ApiException {
        final String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return null;
        }
        if (!values.contains(v)) {
            throw ApiException.invalid(name, "Field '" + name + "' must be one of: " + String.join(", ", new java.util.TreeSet<>(values)) + ".");
        }
        return v;
    }

    private static int intParam(final Map<String, String> q, final String name, final int dflt, final int min, final int max)
            throws ApiException {
        final String v = q.get(name);
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            final int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw ApiException.invalid(name, "Field '" + name + "' must be between " + min + " and " + max + ".");
            }
            return n;
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(name, "Field '" + name + "' must be a whole number.");
        }
    }
}
