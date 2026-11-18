/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.ToolHandler;
import net.yacy.ai.rag.JobSearch;
import net.yacy.ai.rag.RagContext;
import net.yacy.ai.rag.RagQuery;
import net.yacy.ai.rag.RagRetriever;
import net.yacy.ai.rag.ServiceSearch;
import net.yacy.ai.rag.ToolContext;

/**
 * Two named search tools of the chat (docs/SCOUTRO_RAG_QUALITY.md, sections 12 and 13):
 * <ul>
 * <li>{@code job_search} – the job postings of the knowledge graph ({@code type=job}) by a few short title variants,
 * with employer, dates, the subjects shown and partial matches ({@link JobSearch}).</li>
 * <li>{@code provider_search} – the local index again for a service and a place derived from a request ("Wer prüft in
 * Hürth, ob eine Wand tragend ist?" searches "Tragwerksplanung Hürth", "Statiker Hürth" ...; {@link ServiceSearch}).</li>
 * </ul>
 * The server runs the same searches itself before the answer when the automatic search answered a job or provider
 * question weakly, whether or not the model calls a tool. Their release follows {@code kg_search} and {@code search}
 * unless set on its own ({@code ai.tools.job_search.enabled}, {@code ai.tools.provider_search.enabled}). Both work only
 * within the scope of a chat request; every page they name is a numbered source.
 */
public final class SearchAssistTools {

    public static final String JOB_SEARCH = "job_search";
    public static final String PROVIDER_SEARCH = "provider_search";

    private SearchAssistTools() {
    }

    public static ToolHandler jobSearch() {
        return new Tool(JOB_SEARCH, true, "Search the job postings (job advertisements) of the Scoutro knowledge graph within this chat's"
                + " collections. Give the subjects and job titles of the request, e.g. \"SAP KI Automatisierung\" or \"SAP Berater\";"
                + " the server searches short title variants itself (each subject also on its own). Returns postings with employer,"
                + " dates, their original page as [n], and which subjects each shows (partial matches marked). It does not check"
                + " whether a posting is still open.") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String query = query(args);
                final JobSearch.Plan plan = JobSearch.plan(query);
                if (plan.variants().isEmpty()) throw new IllegalArgumentException("Give words of a job title or a subject");
                final JobSearch.Result result = JobSearch.run(context, plan);
                final JSONObject out = new JSONObject(true).put("tool", JOB_SEARCH).put("scope", context.scopeLabel());
                out.put("subjects", new JSONArray(plan.criteria())).put("searched", new JSONArray(result.searched));
                final JSONArray postings = new JSONArray();
                for (final JobSearch.Job job : result.jobs) {
                    final JobSearch.Built one = JobSearch.format(new JobSearch.Result(result.plan, java.util.List.of(job), result.searched,
                            result.timedOut, result.error, result.millis), 1, 2000, context.scopeLabel(), false);
                    if (one.sources.isEmpty()) continue;
                    final RagContext.Source entry = one.sources.get(0);
                    final RagContext.Source source = context.source(entry.title, entry.url, entry.collection, RagContext.Source.KIND_GRAPH,
                            entry.text());
                    final JSONObject p = new JSONObject(true);
                    p.put("cite", "[" + source.id + "]");
                    p.put("url", entry.url);
                    p.put("text", entry.text());
                    postings.put(p);
                }
                out.put("postings", postings);
                if (result.error != null) out.put("error", result.error);
                out.put("note", postings.length() == 0
                        ? "No job posting with these title words in " + context.scopeLabel() + ". Say that none was found; never present a"
                                + " company page as a posting."
                        : "Cite each posting as [n]; a PARTIAL MATCH shows only some subjects: say which it does not show. Whether a posting"
                                + " is still open is a separate question, not checked.");
                return out;
            }
        };
    }

    public static ToolHandler providerSearch() {
        return new Tool(PROVIDER_SEARCH, false, "Search this chat's collections again for providers of a service in a place. Give the"
                + " request in its words, e.g. \"Wer prüft in Hürth, ob eine Wand tragend ist?\" or \"Tragwerksplanung Hürth\"; the"
                + " server derives the service (here Tragwerksplanung, Statiker, Statik) and the place and runs a few targeted searches."
                + " Returns only pages that name the service, as [n], and which of them name the place.") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String query = query(args);
                final ServiceSearch.Plan plan = ServiceSearch.planAny(query);
                if (plan == null) throw new IllegalArgumentException("Give the service or the problem, and the place if there is one");
                final ServiceSearch.Result result = ServiceSearch.run(context, plan, java.util.List.of());
                final JSONObject out = new JSONObject(true).put("tool", PROVIDER_SEARCH).put("scope", context.scopeLabel()).put("local", true);
                out.put("services", new JSONArray(plan.services)).put("place", plan.place == null ? JSONObject.NULL : plan.place);
                out.put("searched", new JSONArray(result.searched));
                final RagQuery words = RagQuery.prepare(String.join(" ", plan.services) + (plan.place == null ? "" : " " + plan.place));
                final JSONArray pages = new JSONArray();
                for (final RagRetriever.Scored s : result.pages) {
                    final String label = context.collection != null ? context.collection
                            : s.candidate.collections.isEmpty() ? null : s.candidate.collections.get(0);
                    final String excerpt = RagContext.excerpt(s.candidate, words, 420);
                    final String title = RagContext.clean(s.candidate.title.isEmpty() ? s.candidate.url : s.candidate.title, 160);
                    final RagContext.Source source = context.source(title, s.candidate.url, label, null, excerpt);
                    final JSONObject p = new JSONObject(true);
                    p.put("cite", "[" + source.id + "]");
                    p.put("title", title);
                    p.put("url", s.candidate.url);
                    if (label != null) p.put("collection", label);
                    if (plan.place != null) p.put("names_place", Boolean.TRUE.equals(result.placeNamed.get(s.candidate.url)));
                    p.put("text", excerpt);
                    pages.put(p);
                }
                out.put("sources", pages);
                out.put("note", pages.length() == 0
                        ? "No page in " + context.scopeLabel() + " names this service. Say that no matching provider was found."
                        : "Name a provider only with what its page states, cited as [n]." + (plan.place == null ? ""
                                : " A page with names_place false does not name " + plan.place + ": say so."));
                return out;
            }
        };
    }

    /** A named search tool of a chat request. */
    private abstract static class Tool implements ToolHandler {
        private final String name;
        private final boolean graph;
        private final String description;

        Tool(final String name, final boolean graph, final String description) {
            this.name = name;
            this.graph = graph;
            this.description = description;
        }

        abstract JSONObject run(JSONObject args, ToolContext context) throws Exception;

        static String query(final JSONObject args) {
            final String q = args.isNull("query") ? "" : args.optString("query", "").trim();
            if (q.isEmpty()) throw new IllegalArgumentException("Missing query");
            return q.length() > 400 ? q.substring(0, 400) : q;
        }

        @Override
        public JSONObject definition() throws JSONException {
            final JSONObject props = new JSONObject(true).put("query", new JSONObject(true).put("type", "string")
                    .put("description", "The request in its own words, or its subjects and the place."));
            final JSONObject fn = new JSONObject(true).put("name", this.name).put("description", this.description)
                    .put("parameters", new JSONObject(true).put("type", "object").put("properties", props).put("required", new JSONArray().put("query")));
            return new JSONObject(true).put("type", "function").put("function", fn);
        }

        @Override
        public int maxCallsPerTurn() {
            return 2;
        }

        @Override
        public boolean scoped() {
            return true;
        }

        @Override
        public boolean requiresGraph() {
            return this.graph;
        }

        @Override
        public String execute(final String arguments) {
            return ToolHandler.errorJson("Tool not released: " + this.name); // never without the scope of a chat
        }

        @Override
        public String execute(final String arguments, final ToolContext context) {
            if (context == null || (this.graph && !context.graph)) return execute(arguments);
            return context.run(this.name, () -> {
                final JSONObject args;
                try {
                    args = arguments == null || arguments.isEmpty() ? new JSONObject(true) : new JSONObject(arguments);
                } catch (final JSONException e) {
                    throw new IllegalArgumentException("Invalid arguments JSON");
                }
                return run(args, context);
            });
        }
    }
}
