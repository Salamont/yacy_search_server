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
 * <li>{@code job_search} – the job postings of the knowledge graph ({@code type=job}) of the chat's scope, by their titles,
 * facts and the text of their pages, counted before the answer's limit, with employer, dates, the subjects shown and partial
 * matches ({@link JobSearch}; the same search as the automatic one).</li>
 * <li>{@code provider_search} – the local index again for a service and a place derived from a request ("Wer hilft in
 * Musterstadt bei Schimmel im Keller?" searches "Schimmelsanierung Musterstadt" ...; {@link ServiceSearch}).</li>
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
                + " collections. Give the subjects and job titles of the request, e.g. \"Java Cloud\" or \"Java Entwickler\";"
                + " the server searches all postings of the collections by their titles, their facts and the text of their pages"
                + " (each subject also on its own). Returns how many postings were found, the most relevant ones with employer,"
                + " the dates they state and their original page as [n], and which subjects each shows (partial matches marked).") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String query = query(args);
                final JobSearch.Plan plan = JobSearch.plan(query);
                if (plan.variants().isEmpty()) throw new IllegalArgumentException("Give words of a job title or a subject");
                final JobSearch.Result result = JobSearch.run(context, plan);
                final JSONObject out = new JSONObject(true).put("tool", JOB_SEARCH).put("scope", context.scopeLabel());
                out.put("subjects", new JSONArray(plan.criteria())).put("searched", new JSONArray(result.searched));
                out.put("found", result.found).put("lower_bound", result.lowerBound()).put("per_subject", new JSONObject(result.perSubject));
                if (result.lowerBound()) out.put("gaps", new JSONArray(result.gaps));
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
                out.put("postings", postings).put("shown", postings.length());
                if (result.error != null) out.put("error", result.error);
                final String found = (result.lowerBound() ? "at least " : "") + result.found;
                out.put("note", postings.length() == 0
                        ? (result.lowerBound() ? "No matching job posting found within the search budget in " + context.scopeLabel() + "; do not"
                                + " say that the index holds none." : "No matching job posting in " + context.scopeLabel() + ". Say that none was found.")
                                + " Never present a company page as a posting."
                        : "Found " + found + " matching postings, shown " + postings.length() + ": say both numbers. Cite each posting as [n];"
                                + " a PARTIAL MATCH shows only some subjects: say which it does not show. The postings count as current; give the"
                                + " dates they state and no remark about missing dates.");
                return out;
            }
        };
    }

    public static ToolHandler providerSearch() {
        return new Tool(PROVIDER_SEARCH, false, "Search this chat's collections for the providers of a service in a place. Give the"
                + " request in its words, e.g. \"Wer hilft in Musterstadt bei Schimmel im Keller?\" or \"Schimmelsanierung Musterstadt\";"
                + " the server derives the service (here Schimmelsanierung) and the place and searches the knowledge graph's offered"
                + " services and all pages of the index that name the service. Returns how many providers were found (each once), the"
                + " most relevant ones as [n], and for each whether it is located in the place, serves it, or only mentions it.") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String query = query(args);
                final ServiceSearch.Plan plan = ServiceSearch.planAny(query);
                if (plan == null) throw new IllegalArgumentException("Give the service or the problem, and the place if there is one");
                final ServiceSearch.Result result = ServiceSearch.run(context, plan, java.util.List.of());
                final JSONObject out = new JSONObject(true).put("tool", PROVIDER_SEARCH).put("scope", context.scopeLabel()).put("local", true);
                out.put("services", new JSONArray(plan.services)).put("place", plan.place == null ? JSONObject.NULL : plan.place);
                out.put("searched", new JSONArray(result.searched));
                out.put("found", result.found).put("lower_bound", result.lowerBound());
                if (result.lowerBound()) out.put("gaps", new JSONArray(result.gaps));
                if (plan.place != null) out.put("per_place", new JSONObject(result.perPlace));
                final JSONArray providers = new JSONArray();
                for (final ServiceSearch.Provider provider : result.providers) {
                    final ServiceSearch.Built one = ServiceSearch.format(new ServiceSearch.Result(plan, java.util.List.of(provider), 1,
                            java.util.Map.of(), result.searched, java.util.List.of(), false, 0L), 1, 2000, context.scopeLabel(), false);
                    if (one.sources.isEmpty()) continue;
                    final RagContext.Source entry = one.sources.get(0);
                    final RagContext.Source source = context.source(entry.title, entry.url, entry.collection, entry.kind, entry.text());
                    final JSONObject p = new JSONObject(true);
                    p.put("cite", "[" + source.id + "]");
                    p.put("name", provider.name);
                    p.put("url", entry.url);
                    p.put("pages", provider.pages.size());
                    p.put("evidence", provider.byGraph && provider.byText ? "graph and page" : provider.byGraph ? "graph" : "page");
                    if (plan.place != null) p.put("place", provider.place);
                    p.put("text", entry.text());
                    providers.put(p);
                }
                out.put("providers", providers).put("shown", providers.length());
                final String found = (result.lowerBound() ? "at least " : "") + result.found;
                out.put("note", providers.length() == 0
                        ? (result.lowerBound() ? "No provider found within the search budget in " + context.scopeLabel() + "; do not say that"
                                + " the index holds none." : "No provider of this service in " + context.scopeLabel() + ". Say that none was found.")
                        : "Found " + found + " providers, shown " + providers.length() + ": say both numbers. Name a provider only with what its"
                                + " source states, cited as [n]." + (plan.place == null ? "" : " place: location or service_area is evidenced;"
                                + " mentioned or none is no evidence of " + plan.place + ": say so."));
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
