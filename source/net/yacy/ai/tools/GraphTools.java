/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.ToolHandler;
import net.yacy.ai.rag.GraphFacts;
import net.yacy.ai.rag.RagContext;
import net.yacy.ai.rag.ToolContext;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The chat's tools over the read layer of the Scoutro knowledge graph ({@link KgReader}): find entities by name,
 * read the facts and relations of one entity, read the evidence of one fact (docs/SCOUTRO_RAG_QUALITY.md, "Tools of
 * the chat").
 * <ul>
 * <li>They only run with the {@link ToolContext} of a chat request. Its viewer comes from the chat session; the tools
 * take no collection argument, and whatever else a call carries is ignored. An entity, fact or evidence row outside
 * the scope answers exactly like an unknown one ({@code not_found}).</li>
 * <li>A fact is given with the page it was read from as a numbered source of the answer, so it is cited like a
 * search result. A fact without visible evidence gets no number and says so.</li>
 * <li>Nothing is written; every read is a bounded lease and every list is short.</li>
 * </ul>
 */
public final class GraphTools {

    public static final String SEARCH = "kg_search";
    public static final String ENTITY = "kg_entity";
    public static final String EVIDENCE = "kg_evidence";

    static final int MAX_ENTITIES = 8;
    static final int MAX_FACTS = 20;
    static final int MAX_INCOMING = 12;
    static final int MAX_EVIDENCE = 5;
    static final int MAX_EXCERPT = 320;
    static final int MAX_VALUE = 240;
    private static final Set<String> TYPES = Set.of("organization", "facility", "site", "place", "service", "job");

    private GraphTools() {
    }

    public static ToolHandler search() {
        return new Tool(SEARCH, 4, "Find entities of the Scoutro knowledge graph (organisations, facilities, sites, places, services, job"
                + " postings) by words of their name or title, within this chat's collections. Every word must occur in the name; use"
                + " few concrete words (a company name, a product, a job title). Returns entity IDs for kg_entity.") {
            @Override
            JSONObject parameters() throws JSONException {
                final JSONObject props = new JSONObject(true);
                props.put("query", param("string", "Words of the name or title, e.g. \"Java Entwickler\"."));
                final JSONObject type = param("string", "Optional entity type.");
                type.put("enum", new JSONArray(List.of("organization", "facility", "site", "place", "service", "job")));
                props.put("type", type);
                return object(props, "query");
            }

            @Override
            JSONObject run(final JSONObject args, final ToolContext context, final KgReader reader, final Viewer viewer) throws Exception {
                final String query = text(args, "query").trim();
                if (query.isEmpty()) throw new IllegalArgumentException("Missing query");
                final String type = text(args, "type").trim();
                final KgReader.EntityQuery q = new KgReader.EntityQuery();
                q.q = query.length() > 200 ? query.substring(0, 200) : query;
                q.type = TYPES.contains(type) ? type : null;
                q.limit = MAX_ENTITIES;
                final JSONObject page = reader.entities(q, viewer);
                final JSONObject out = head(SEARCH, context);
                out.put("query", q.q);
                if (q.type != null) out.put("type", q.type);
                out.put("total", page.optLong("total"));
                final JSONArray entities = new JSONArray();
                final JSONArray items = page.optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    final JSONObject item = items.getJSONObject(i);
                    final JSONObject e = new JSONObject(true);
                    e.put("entity", text(item, "id"));
                    e.put("type", text(item, "type"));
                    e.put("name", name(item));
                    e.put("quality", text(item, "quality"));
                    final JSONObject ctx = item.optJSONObject("context");
                    if (ctx != null) {
                        if (ctx.optJSONArray("hosts") != null && ctx.optJSONArray("hosts").length() > 0) e.put("hosts", first(ctx.optJSONArray("hosts"), 3));
                        final JSONArray providers = ctx.optJSONArray("providers");
                        if (providers != null && providers.length() > 0) {
                            final JSONArray by = new JSONArray();
                            for (int p = 0; p < Math.min(3, providers.length()); p++) {
                                final JSONObject provider = providers.getJSONObject(p);
                                by.put(new JSONObject(true).put("entity", text(provider, "id")).put("name", name(provider)));
                            }
                            e.put("job".equals(text(item, "type")) ? "employer" : "offered_by", by);
                        }
                    }
                    entities.put(e);
                }
                out.put("entities", entities);
                out.put("note", entities.length() == 0
                        ? "No entity with these words in its name in " + context.scopeLabel() + ". The name search matches words of names"
                                + " and titles only: try other or fewer words, another type, or the search tool. If nothing is found,"
                                + " say that the knowledge graph has no such entry."
                        : "A list of names is no evidence. Call kg_entity with an entity ID for its facts with their sources.");
                return out;
            }
        };
    }

    public static ToolHandler entity() {
        return new Tool(ENTITY, 4, "Read one entity of the Scoutro knowledge graph: its facts (for example industry, offers, systems,"
                + " address, employment type) and its relations in both directions (for example the job postings of an employer,"
                + " the employer of a job), each with the page it was read from as a numbered source [n].") {
            @Override
            JSONObject parameters() throws JSONException {
                final JSONObject props = new JSONObject(true);
                props.put("entity", param("string", "Entity ID from kg_search or from a relation (kge_...)."));
                props.put("predicate", param("string", "Optional: only facts and relations of this predicate, e.g. system_signal, offers,"
                        + " hiring_organization."));
                return object(props, "entity");
            }

            @Override
            JSONObject run(final JSONObject args, final ToolContext context, final KgReader reader, final Viewer viewer) throws Exception {
                String id = text(args, "entity").trim();
                if (!KgReader.ENTITY_ID.matcher(id).matches()) throw new IllegalArgumentException("entity must be an entity ID (kge_...)");
                String predicate = text(args, "predicate").trim();
                if (!predicate.matches("[a-z_:]{1,64}")) predicate = null;
                JSONObject entity = reader.entity(id, viewer);
                if (entity.has("redirect")) { // merged into another entity the viewer sees
                    id = entity.getString("redirect");
                    entity = reader.entity(id, viewer);
                }
                final String entityName = name(entity);
                final JSONObject own = reader.entityStatements(id, predicate, false, false, 0, MAX_FACTS, viewer);
                final JSONObject in = reader.entityStatements(id, predicate, true, false, 0, MAX_INCOMING, viewer);
                final List<String> ids = new ArrayList<>();
                collectIds(own, ids);
                collectIds(in, ids);
                final Map<String, JSONArray> evidence = reader.evidenceOf(ids, 1, viewer);
                final JSONObject out = head(ENTITY, context);
                out.put("entity", id);
                out.put("type", text(entity, "type"));
                out.put("name", entityName);
                int room = context.room() - 400;
                final JSONArray facts = new JSONArray();
                final JSONArray relations = new JSONArray();
                int withoutSource = 0;
                for (final JSONObject page : new JSONObject[] {own, in}) {
                    final boolean incoming = page == in;
                    final JSONArray items = page.optJSONArray("items");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        final JSONObject s = items.getJSONObject(i);
                        if (!incoming && "name".equals(text(s, "predicate")) && entityName.equals(s.optJSONObject("object") == null ? null
                                : s.optJSONObject("object").optString("value", null))) {
                            continue; // the name is in the head
                        }
                        final JSONObject fact = fact(s, incoming, entityName, evidence.get(text(s, "id")), context);
                        if (!fact.has("cite")) withoutSource++;
                        final int length = fact.toString().length();
                        if (length > room) break;
                        room -= length;
                        (incoming ? relations : facts).put(fact);
                    }
                }
                out.put("facts", facts);
                out.put("incoming_relations", relations);
                final long more = Math.max(0L, own.optLong("total") + in.optLong("total") - facts.length() - relations.length()
                        - (predicate == null ? 1 : 0));
                if (more > 0) out.put("not_shown", more);
                out.put("note", (facts.length() + relations.length() == 0
                        ? "The knowledge graph holds no" + (predicate == null ? "" : " such") + " fact about this entity in " + context.scopeLabel()
                                + ". Say that it is not recorded; do not state it from your own knowledge."
                        : "Cite a fact with its number [n] only; kg_evidence shows more of its source pages.")
                        + (withoutSource > 0 ? " Facts without \"cite\" have no visible source: name them only as unverified." : "")
                        + (more > 0 ? " More facts exist: ask with a predicate." : ""));
                return out;
            }
        };
    }

    public static ToolHandler evidence() {
        return new Tool(EVIDENCE, 4, "Read the evidence of one fact of the Scoutro knowledge graph: the source pages it was read from,"
                + " with their URL, the date they were loaded and the passage, each as a numbered source [n]. Use it before stating a"
                + " fact that matters, and to check whether a fact is really stated by a page.") {
            @Override
            JSONObject parameters() throws JSONException {
                final JSONObject props = new JSONObject(true);
                props.put("statement", param("string", "Statement ID of a fact from kg_entity (kgs_...)."));
                return object(props, "statement");
            }

            @Override
            JSONObject run(final JSONObject args, final ToolContext context, final KgReader reader, final Viewer viewer) throws Exception {
                final String id = text(args, "statement").trim();
                if (!KgReader.STATEMENT_ID.matcher(id).matches()) throw new IllegalArgumentException("statement must be a statement ID (kgs_...)");
                JSONObject statement = reader.statement(id, viewer);
                String statementId = id;
                if (statement.has("redirect")) {
                    statementId = statement.getString("redirect");
                    statement = reader.statement(statementId, viewer);
                }
                final JSONObject page = reader.evidence(statementId, 0, MAX_EVIDENCE, viewer);
                final JSONObject out = head(EVIDENCE, context);
                out.put("statement", statementId);
                final String subject = text(statement, "subject_name");
                final String sentence = sentence(subject, text(statement, "predicate"), object(statement.optJSONObject("object")));
                out.put("fact", sentence);
                out.put("quality", text(statement, "quality"));
                out.put("total", page.optLong("total"));
                final JSONArray rows = new JSONArray();
                final JSONArray items = page.optJSONArray("items");
                int room = context.room() - 400;
                for (int i = 0; items != null && i < items.length(); i++) {
                    final JSONObject row = row(items.getJSONObject(i), subject, sentence, context);
                    final int length = row.toString().length();
                    if (length > room) break;
                    room -= length;
                    rows.put(row);
                }
                out.put("evidence", rows);
                out.put("note", rows.length() == 0
                        ? "No source page of this fact is visible in " + context.scopeLabel() + ": it is not verified here. Say so."
                        : "Cite a page as [n] only for what its passage states. \"hedged\" means the page states it with reservation;"
                                + " a state other than \"active\" means the page was not reachable at the last visit.");
                return out;
            }
        };
    }

    // ---------------------------------------------------------------- shared

    /** One knowledge graph tool: needs the chat's context and the running graph. */
    private abstract static class Tool implements ToolHandler {
        private final String name;
        private final int maxCalls;
        private final String description;

        Tool(final String name, final int maxCalls, final String description) {
            this.name = name;
            this.maxCalls = maxCalls;
            this.description = description;
        }

        abstract JSONObject parameters() throws JSONException;

        abstract JSONObject run(JSONObject args, ToolContext context, KgReader reader, Viewer viewer) throws Exception;

        @Override
        public JSONObject definition() throws JSONException {
            final JSONObject fn = new JSONObject(true);
            fn.put("name", this.name);
            fn.put("description", this.description);
            fn.put("parameters", parameters());
            return new JSONObject(true).put("type", "function").put("function", fn);
        }

        @Override
        public int maxCallsPerTurn() {
            return this.maxCalls;
        }

        @Override
        public boolean scoped() {
            return true;
        }

        @Override
        public String execute(final String arguments) {
            return ToolHandler.errorJson("Tool not released: " + this.name); // never without the scope of a chat
        }

        @Override
        public String execute(final String arguments, final ToolContext context) {
            if (context == null || !context.graph) return execute(arguments);
            return context.run(this.name, () -> {
                final JSONObject args;
                try {
                    args = arguments == null || arguments.isEmpty() ? new JSONObject(true) : new JSONObject(arguments);
                } catch (final JSONException e) {
                    throw new IllegalArgumentException("Invalid arguments JSON");
                }
                final KgReader reader = context.reader();
                if (reader == null) {
                    return new JSONObject(true).put("tool", this.name).put("error", "unavailable").put("note",
                            "The knowledge graph is not available. Use the search tool or the sources you have, and say that the graph"
                                    + " could not be checked.");
                }
                return run(args, context, reader, context.viewer(reader));
            });
        }
    }

    static JSONObject head(final String tool, final ToolContext context) throws JSONException {
        return new JSONObject(true).put("tool", tool).put("scope", context.scopeLabel());
    }

    static JSONObject param(final String type, final String description) throws JSONException {
        return new JSONObject(true).put("type", type).put("description", description);
    }

    static JSONObject object(final JSONObject props, final String required) throws JSONException {
        return new JSONObject(true).put("type", "object").put("properties", props).put("required", new JSONArray().put(required));
    }

    /** The text of a field; empty for a missing one and for a JSON null. */
    static String text(final JSONObject o, final String key) {
        return o == null || o.isNull(key) ? "" : o.optString(key, "");
    }

    private static JSONArray first(final JSONArray array, final int max) {
        final JSONArray out = new JSONArray();
        for (int i = 0; i < Math.min(max, array.length()); i++) out.put(array.opt(i));
        return out;
    }

    private static void collectIds(final JSONObject page, final List<String> ids) {
        final JSONArray items = page.optJSONArray("items");
        for (int i = 0; items != null && i < items.length(); i++) {
            final String id = items.optJSONObject(i) == null ? "" : text(items.optJSONObject(i), "id");
            if (!id.isEmpty()) ids.add(id);
        }
    }

    /** The name to show: the stated name, else the display name, else the words for an unnamed entity; never an ID. */
    static String name(final JSONObject entity) {
        for (final String key : new String[] {"name", "display_name"}) {
            final String value = text(entity, key);
            if (!value.isEmpty()) return value;
        }
        return "an unnamed " + (text(entity, "type").isEmpty() ? "entry" : text(entity, "type"));
    }

    /** The object of a statement as text: the other entity's name, or the value. */
    static String object(final JSONObject object) {
        if (object == null) return "";
        if (object.has("entity")) return name(object);
        return RagContext.clean(text(object, "value"), MAX_VALUE);
    }

    static String sentence(final String subject, final String predicate, final String object) {
        return subject + " — " + GraphFacts.label(predicate) + ": " + object;
    }

    /** One fact or relation of an entity with its newest visible source page as a numbered source. */
    private static JSONObject fact(final JSONObject s, final boolean incoming, final String entityName, final JSONArray evidence,
            final ToolContext context) throws JSONException {
        final JSONObject fact = new JSONObject(true);
        fact.put("statement", text(s, "id"));
        fact.put("predicate", text(s, "predicate"));
        final JSONObject object = s.optJSONObject("object");
        final String subject = incoming ? text(s, "subject_name") : entityName;
        final String objectText = incoming ? entityName : object(object);
        if (incoming) {
            // a relation pointing to this entity: who states it
            fact.put("subject", new JSONObject(true).put("entity", text(s, "subject")).put("name", subject.isEmpty() ? "an unnamed entry" : subject));
        } else if (object != null && object.has("entity")) {
            fact.put("object", new JSONObject(true).put("entity", text(object, "entity")).put("name", name(object)));
        } else {
            fact.put("value", objectText);
        }
        fact.put("quality", text(s, "quality"));
        if ("hedged".equals(text(s, "certainty"))) fact.put("certainty", "hedged");
        if (!s.isNull("last_confirmed")) fact.put("last_confirmed", text(s, "last_confirmed"));
        final JSONObject row = evidence == null || evidence.length() == 0 ? null : evidence.optJSONObject(0);
        if (row == null || text(row, "url").isEmpty()) {
            fact.put("source", "none visible");
            return fact;
        }
        final String sentence = sentence(subject, text(s, "predicate"), objectText);
        final String excerpt = RagContext.clean(text(row, "excerpt"), MAX_EXCERPT);
        final RagContext.Source source = context.source(GraphFacts.TITLE + (subject.isEmpty() ? entityName : subject), text(row, "url"),
                firstString(row.optJSONArray("collections")), RagContext.Source.KIND_GRAPH, sentence + (excerpt.isEmpty() ? "" : ". " + excerpt));
        fact.put("cite", "[" + source.id + "]");
        fact.put("url", text(row, "url"));
        if (!excerpt.isEmpty()) fact.put("passage", excerpt);
        return fact;
    }

    /** One evidence row of a fact as a numbered source. */
    private static JSONObject row(final JSONObject e, final String subject, final String sentence, final ToolContext context) throws JSONException {
        final JSONObject row = new JSONObject(true);
        final String excerpt = RagContext.clean(text(e, "excerpt"), MAX_EXCERPT);
        final RagContext.Source source = context.source(GraphFacts.TITLE + subject, text(e, "url"), firstString(e.optJSONArray("collections")),
                RagContext.Source.KIND_GRAPH, sentence + (excerpt.isEmpty() ? "" : ". " + excerpt));
        row.put("cite", "[" + source.id + "]");
        row.put("url", text(e, "url"));
        if (e.optJSONArray("collections") != null) row.put("collections", e.optJSONArray("collections"));
        row.put("state", text(e, "state"));
        if (!e.isNull("loaded_at")) row.put("loaded_at", text(e, "loaded_at"));
        row.put("observed_at", text(e, "observed_at"));
        row.put("read_by", text(e, "kind"));
        row.put("certainty", text(e, "certainty"));
        row.put("passage", excerpt.isEmpty() ? "(no passage recorded: read from structured data of the page)" : excerpt);
        return row;
    }

    private static String firstString(final JSONArray array) {
        return array == null || array.length() == 0 ? null : array.optString(0, null);
    }
}
