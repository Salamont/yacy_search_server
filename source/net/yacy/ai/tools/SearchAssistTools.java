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
                + " (each subject also on its own). For the postings of one employer name it in \"employer\" (or write"
                + " \"Stellenanzeigen von <employer>\"): only postings the graph records that employer for are counted. Returns how"
                + " many postings were found, the most relevant ones with employer, the dates they state and their original page as"
                + " [n], and which subjects each shows (partial matches marked).") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String employer = args.isNull("employer") ? null : args.optString("employer", "").trim();
                final String query = employer == null || employer.isEmpty() ? query(args) : args.optString("query", "").trim();
                final JobSearch.Plan plan = JobSearch.plan(query, employer);
                if (plan.variants().isEmpty() && plan.employer() == null) {
                    throw new IllegalArgumentException("Give words of a job title or a subject, or the employer");
                }
                // the same subjects in the same scope as a search of this request already made: its result, not a second search
                final boolean reused = JobSearch.computed(context, plan) != null;
                final JobSearch.Result result = JobSearch.run(context, plan);
                final JSONObject out = new JSONObject(true).put("tool", JOB_SEARCH).put("scope", context.scopeLabel());
                if (reused) out.put("reused", true);
                out.put("subjects", new JSONArray(plan.criteria())).put("searched", new JSONArray(result.searched));
                out.put("found", result.found).put("lower_bound", result.lowerBound()).put("per_subject", new JSONObject(result.perSubject));
                if (result.records > result.found) out.put("records", result.records); // a posting on two pages counts once
                if (plan.employer() != null) {
                    // found: the postings the graph records this employer for; a posting that only names it is not counted
                    out.put("employer", plan.employer()).put("mentions_not_counted", result.mentions);
                }
                if (result.pageOnly > 0) out.put("pages_not_attributed", result.pageOnly); // overview pages naming a subject, no posting of them
                if (result.lowerBound()) out.put("gaps", new JSONArray(result.gaps));
                final java.util.List<Entry> entries = new java.util.ArrayList<>();
                for (final JobSearch.Job job : result.jobs) {
                    final JobSearch.Built one = JobSearch.format(new JobSearch.Result(result.plan, java.util.List.of(job), result.searched,
                            result.timedOut, result.error, result.millis), 1, 2000, context.scopeLabel(), false);
                    if (one.sources.isEmpty()) continue;
                    final RagContext.Source entry = one.sources.get(0);
                    entries.add(new Entry(entry.title, entry.url, entry.collection, RagContext.Source.KIND_GRAPH, entry.text(),
                            new JSONObject(true).put("url", entry.url)));
                }
                if (result.error != null) out.put("error", result.error);
                final String found = (result.lowerBound() ? "at least " : "") + result.found;
                fit(context, out, "postings", entries, reused ? REUSED_LEVEL : 0, shown -> shown == 0
                        ? (result.found > 0 ? "Found " + found + " matching postings; none fits into this answer's room: say the number"
                                + " and suggest a narrower request." : (result.lowerBound() ? "No matching job posting found within the"
                                + " search budget in " + context.scopeLabel() + "; do not say that the index holds none." : "No matching job"
                                + " posting in " + context.scopeLabel() + ". Say that none was found.") + " Never present a company page as a posting.")
                        : "Found " + found + " matching postings, shown " + shown + ": say both numbers. Cite each posting as [n];"
                                + " a PARTIAL MATCH shows only some subjects: say which it does not show. The postings count as current; give the"
                                + " dates they state and no remark about missing dates." + (plan.employer() == null ? "" : " Counted are the"
                                + " postings the graph records " + plan.employer() + " as the employer of; " + result.mentions + " more name it"
                                + " otherwise and are not postings of it.") + (result.pageOnly == 0 ? "" : " " + result.pageOnly + " overview pages"
                                + " name a subject without any single posting on them showing it: not counted, say so if asked for the number."));
                return out;
            }

            @Override
            JSONObject properties() throws JSONException {
                return super.properties().put("employer", new JSONObject(true).put("type", "string")
                        .put("description", "Optional: the employer whose postings are asked for, e.g. \"Tapir Consulting\"."));
            }
        };
    }

    public static ToolHandler providerSearch() {
        return new Tool(PROVIDER_SEARCH, false, "Search this chat's collections for the providers of a service in a place. Give the"
                + " request in its words, e.g. \"Wer hilft in Musterstadt bei Schimmel im Keller?\", or the service and the place,"
                + " e.g. \"Schimmelsanierung Musterstadt\" or \"Schimmelsanierung in Musterstadt\" (the same search); name the place"
                + " in \"place\" when the words leave it unclear. The server derives the service (here Schimmelsanierung) and searches"
                + " the knowledge graph's offered services and all pages of the index that name the service. Returns how many providers"
                + " were found (each once), the most relevant ones as [n], and for each whether it is located in the place, serves it,"
                + " or only mentions it.") {
            @Override
            JSONObject run(final JSONObject args, final ToolContext context) throws Exception {
                final String query = query(args);
                final String place = args.isNull("place") ? null : args.optString("place", "").trim();
                final ServiceSearch.Plan plan = ServiceSearch.planAny(query, place == null || place.isEmpty() ? null : place);
                if (plan == null) throw new IllegalArgumentException("Give the service or the problem, and the place if there is one");
                final boolean reused = ServiceSearch.computed(context, plan) != null;
                final ServiceSearch.Result result = ServiceSearch.run(context, plan, java.util.List.of());
                final JSONObject out = new JSONObject(true).put("tool", PROVIDER_SEARCH).put("scope", context.scopeLabel()).put("local", true);
                if (reused) out.put("reused", true);
                out.put("services", new JSONArray(plan.services)).put("place", plan.place == null ? JSONObject.NULL : plan.place);
                out.put("searched", new JSONArray(result.searched));
                out.put("found", result.found).put("lower_bound", result.lowerBound());
                if (result.lowerBound()) out.put("gaps", new JSONArray(result.gaps));
                if (plan.place != null) {
                    out.put("per_place", new JSONObject(result.perPlace));
                    // the total above stands; only the relation to the place may be open for some providers
                    out.put("place_complete", !result.placeOpen());
                    if (result.placeOpen()) out.put("place_gaps", new JSONArray(result.placeGaps));
                }
                final java.util.List<Entry> entries = new java.util.ArrayList<>();
                for (final ServiceSearch.Provider provider : result.providers) {
                    final ServiceSearch.Built one = ServiceSearch.format(new ServiceSearch.Result(plan, java.util.List.of(provider), 1,
                            java.util.Map.of(), result.searched, java.util.List.of(), false, 0L), 1, 2000, context.scopeLabel(), false);
                    if (one.sources.isEmpty()) continue;
                    final RagContext.Source entry = one.sources.get(0);
                    final JSONObject p = new JSONObject(true);
                    p.put("name", provider.name);
                    p.put("url", entry.url);
                    p.put("pages", Math.max(provider.pageCount, provider.pages.size()));
                    p.put("evidence", provider.byGraph && provider.byText ? "graph and page" : provider.byGraph ? "graph" : "page");
                    if (plan.place != null) {
                        p.put("place", provider.place);
                        // where the place is evidenced or named: not always the page that names the service
                        if (provider.placeSource != null) p.put("place_source", provider.placeSource);
                        if (provider.placeNote != null && !ServiceSearch.AT_PLACE.equals(provider.place)
                                && !ServiceSearch.SERVES_PLACE.equals(provider.place)) p.put("place_note", provider.placeNote);
                    }
                    entries.add(new Entry(entry.title, entry.url, entry.collection, entry.kind, entry.text(), p));
                }
                final String found = (result.lowerBound() ? "at least " : "") + result.found;
                fit(context, out, "providers", entries, reused ? REUSED_LEVEL : 0, shown -> shown == 0
                        ? (result.found > 0 ? "Found " + found + " providers; none fits into this answer's room: say the number and"
                                + " suggest a narrower request." : result.lowerBound() ? "No provider found within the search budget in "
                                + context.scopeLabel() + "; do not say that the index holds none." : "No provider of this service in "
                                + context.scopeLabel() + ". Say that none was found.")
                        : "Found " + found + " providers, shown " + shown + ": say both numbers. Name a provider only with what its"
                                + " source states, cited as [n]." + (plan.place == null ? "" : " place: location or service_area is evidenced;"
                                + " mentioned or none is no evidence of " + plan.place + ": say so. A provider counts as located or serving only"
                                + " by its own address or area (place_source names the page); an address of another company, a project or a plot"
                                + " on its site is none (place_note)." + (result.placeOpen() ? " The relation to "
                                + plan.place + " is open for " + result.undecided() + " providers (place undecided, see place_gaps): the numbers"
                                + " located and serving are at least those of per_place; say that the rest is not determined, and do not count"
                                + " an open one as located, serving or merely mentioning." : "")));
                return out;
            }

            @Override
            JSONObject properties() throws JSONException {
                return super.properties().put("place", new JSONObject(true).put("type", "string")
                        .put("description", "Optional: the town or area, if the request's words leave it unclear, e.g. \"Musterstadt\"."));
            }
        };
    }

    /** One result entry of a tool before it is fitted to the room: its source and its other fields. */
    static final class Entry {
        final String title, url, collection, kind, text;
        final JSONObject fields;

        Entry(final String title, final String url, final String collection, final String kind, final String text, final JSONObject fields) {
            this.title = title;
            this.url = url;
            this.collection = collection;
            this.kind = kind;
            this.text = text == null ? "" : text;
            this.fields = fields;
        }
    }

    /** the lengths a result entry's text is shortened to, in this order, before entries are left out */
    static final int[] TEXT_LEVELS = {2000, 900, 500, 260, 140};
    /** the level a result starts at that repeats a search of this request: its entries are sources of the answer already */
    static final int REUSED_LEVEL = 4;
    /** room kept free for the longer citation numbers and the escaping of the result */
    static final int FIT_MARGIN = 80;

    /** The text cut at a word boundary to at most {@code max} characters (with an ellipsis). */
    static String shorten(final String text, final int max) {
        if (text.length() <= max) return text;
        int cut = Math.max(0, max - 1);
        final int space = text.lastIndexOf(' ', cut);
        if (space > max / 2) cut = space;
        return text.substring(0, cut).trim() + "…";
    }

    /**
     * Puts the entries under {@code key} so that the whole result stays within the tool room of the answer: first the texts
     * are shortened, then entries are left out from the end. Totals in {@code out} are kept; {@code shown}, the note and,
     * when anything was shortened or left out, {@code shortened} and {@code not_shown} tell what the answer holds. Only the
     * entries kept become numbered sources. Returns how many were kept.
     */
    static int fit(final ToolContext context, final JSONObject out, final String key, final java.util.List<Entry> entries,
            final int firstLevel, final java.util.function.IntFunction<String> note) throws JSONException {
        final int room = Math.max(0, context.room() - FIT_MARGIN);
        int keep = entries.size();
        int level = Math.max(0, Math.min(TEXT_LEVELS.length - 1, firstLevel));
        while (true) {
            render(out, key, entries, keep, TEXT_LEVELS[level], note, null);
            if (size(out) <= room) break;
            if (level < TEXT_LEVELS.length - 1) level++;
            else if (keep > 0) keep--;
            else if (out.opt("searched") instanceof JSONArray) {
                // nothing but the totals fits: the list of searches becomes its number
                out.put("searched_count", out.getJSONArray("searched").length());
                out.remove("searched");
            } else break;
        }
        render(out, key, entries, keep, TEXT_LEVELS[level], note, context);
        return keep;
    }

    private static void render(final JSONObject out, final String key, final java.util.List<Entry> entries, final int keep,
            final int textChars, final java.util.function.IntFunction<String> note, final ToolContext register) throws JSONException {
        final JSONArray list = new JSONArray();
        boolean shortened = false;
        for (int i = 0; i < keep; i++) {
            final Entry e = entries.get(i);
            final String text = shorten(e.text, textChars);
            shortened |= text.length() < e.text.length();
            // while measuring, a citation number as long as any this answer can have; registered only when kept
            final String cite = register == null ? "[999]" : "[" + register.source(e.title, e.url, e.collection, e.kind, text).id + "]";
            final JSONObject one = new JSONObject(true).put("cite", cite);
            for (final String k : orderedNames(e.fields)) one.put(k, e.fields.get(k));
            one.put("text", text);
            list.put(one);
        }
        out.put(key, list).put("shown", keep);
        out.remove("shortened");
        out.remove("not_shown");
        if (shortened || keep < entries.size()) {
            out.put("shortened", true);
            if (keep < entries.size()) out.put("not_shown", entries.size() - keep);
        }
        out.put("note", note.apply(keep) + (shortened || keep < entries.size()
                ? " The texts were shortened" + (keep < entries.size() ? " and " + (entries.size() - keep) + " entries left out" : "")
                + " to fit this answer's room." : ""));
    }

    private static String[] orderedNames(final JSONObject o) {
        final java.util.List<String> names = new java.util.ArrayList<>();
        final java.util.Iterator<String> it = o.keys();
        while (it.hasNext()) names.add(it.next());
        return names.toArray(new String[0]);
    }

    /** the size of a result as the tool context measures it */
    static int size(final JSONObject out) {
        return net.yacy.ai.PromptGuard.neutralize(out.toString()).length();
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

        /** the parameters of the tool; query is required */
        JSONObject properties() throws JSONException {
            return new JSONObject(true).put("query", new JSONObject(true).put("type", "string")
                    .put("description", "The request in its own words, or its subjects and the place."));
        }

        @Override
        public JSONObject definition() throws JSONException {
            final JSONObject props = properties();
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
