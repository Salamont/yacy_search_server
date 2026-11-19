/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The service search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 13). A question for a provider of a service ("Wer
 * hilft in Musterstadt bei Schimmel im Keller?") names the problem, rarely the service. When its automatic search matched
 * weakly or found no page that names the service, the server derives the service and the place from the question and
 * searches the local index again, before the model is called, whether or not the model would call a tool:
 * <ul>
 * <li>The service: from the problem words of the question (a small table: "tragend", "Statik", "Wanddurchbruch" mean
 * Tragwerksplanung, Statiker, Statik ...), else from the question's own nouns when it asks for a provider. The place:
 * the town after "in", "bei", "aus" or "um", or a postal code.</li>
 * <li>Only with the existing release of {@code search} and within the scope of the request ({@link ToolContext}): the
 * local index, the session's collection, hits of other collections dropped. At most {@link #MAX_VARIANTS} searches
 * ("Schimmelsanierung Musterstadt", ...; the service alone last), at most {@link #MAX_PAGES} pages, each of
 * which must name a service; pages that name the place first.</li>
 * <li>The pages go to the answer as the first numbered sources, as search results of the index (their original pages).
 * Job postings are a separate search ({@link JobSearch}, {@code type=job}).</li>
 * </ul>
 */
public final class ServiceSearch {

    public static final int MAX_VARIANTS = 4;
    /** providers shown in an answer; the ones found are determined before this limit */
    public static final int MAX_SHOWN = 6;
    /** kept for the context room of an answer: providers given as sources */
    public static final int MAX_PAGES = MAX_SHOWN;
    public static final long MAX_MILLIS = 6000L;
    /** pages of one ranked search (only without a direct index) */
    static final int PER_VARIANT = 8;
    static final int GRAPH_PAGE = 100;
    static final int INDEX_PAGE = 100;
    /** hosts of providers of the index's pages only whose organisation is looked up in the graph */
    static final int MAX_HOST_LOOKUPS = 50;

    /** problem words (folded) and the services they ask for */
    private static final List<Object[]> NEEDS = List.of(
            new Object[] {Pattern.compile("tragend|tragfahig|tragwerk|statik|statisch|statiker|wanddurchbruch|deckendurchbruch|durchbruch"
                    + "|wand\\p{L}* (?:entfern|abreiss|rausnehm|versetz)|standsicher"), List.of("Tragwerksplanung", "Statiker", "Statik")},
            new Object[] {Pattern.compile("schimmel"), List.of("Schimmelsanierung", "Bausachverständiger")},
            new Object[] {Pattern.compile("feucht|nasse[rn]? (?:keller|wand)|abdicht"), List.of("Abdichtung", "Bausachverständiger")},
            new Object[] {Pattern.compile("energieausweis|energieberat|warmedamm|dammung"), List.of("Energieberatung", "Energieausweis")},
            new Object[] {Pattern.compile("asbest|schadstoff"), List.of("Schadstoffsanierung", "Asbestsanierung")},
            new Object[] {Pattern.compile("gutacht|sachverstandig|baumangel|bauschad|mangel am bau"), List.of("Bausachverständiger", "Baugutachter")});

    /** a question for a provider without a known problem word: "Wer baut ...", "Anbieter für ...", "eine Firma, die ..." */
    private static final Pattern PROVIDER = Pattern.compile("(?:^|[^\\p{L}])(?:wer\\s+(?:pruft|macht|baut|repariert|saniert|bietet|hilft|berat|"
            + "ubernimmt|erstellt|plant|installiert|reinigt|liefert|kann)|anbieter|dienstleister|handwerker|fachbetrieb|fachmann|fachfrau|"
            + "experte|expertin|ingenieurburo|firma,?\\s+die|betrieb,?\\s+der)(?:$|[^\\p{L}])");
    /** the place: a town after a preposition (one or two capitalized words), or a postal code */
    private static final Pattern PLACE = Pattern.compile("(?:^|\\s)(?:in|bei|aus|um|nahe|rund um|im Raum)\\s+((?:Bad |Sankt |St\\. )?\\p{Lu}[\\p{L}-]+"
            + "(?:\\s+(?:am|an der|im|bei|ob der)\\s+\\p{Lu}[\\p{L}-]+)?)");
    private static final Pattern POSTAL = Pattern.compile("(?<!\\d)\\d{5}(?!\\d)");
    private static final Set<String> NOT_A_PLACE = Set.of("Deutschland", "Germany", "Ordnung", "Zukunft", "Frage", "Kürze", "Zusammenarbeit",
            "Berufung", "Bezug", "Planung");

    private ServiceSearch() {
    }

    /** The service and the place of a question, and the searches for them. */
    public static final class Plan {
        public final List<String> services;
        public final String place;
        public final List<String> variants;
        /** derived from a problem word of the table (not only the question's own nouns) */
        public final boolean derived;

        Plan(final List<String> services, final String place, final List<String> variants, final boolean derived) {
            this.services = Collections.unmodifiableList(services);
            this.place = place;
            this.variants = Collections.unmodifiableList(variants);
            this.derived = derived;
        }
    }

    /** The plan of a question for a provider of a service; null for any other question. */
    public static Plan plan(final String goal) {
        if (goal == null || goal.trim().isEmpty()) return null;
        final String folded = RagQuery.fold(goal);
        final Set<String> services = new LinkedHashSet<>();
        for (final Object[] need : NEEDS) {
            if (((Pattern) need[0]).matcher(folded).find()) {
                @SuppressWarnings("unchecked")
                final List<String> list = (List<String>) need[1];
                services.addAll(list);
            }
        }
        final boolean derived = !services.isEmpty();
        final String place = place(goal);
        if (!derived) {
            if (!PROVIDER.matcher(folded).find()) return null;
            // the question's own nouns, without the place and the words about providers
            for (final RagQuery.Term t : RagFollowUp.topics(goal)) {
                if (services.size() >= 2) break;
                if (t.phrase || t.weak || !t.noun) continue;
                if (place != null && RagQuery.fold(place).contains(t.stem)) continue;
                if (PROVIDER.matcher(" " + t.text + " ").find()) continue;
                services.add(t.text);
            }
            if (services.isEmpty()) return null;
        }
        final List<String> variants = new ArrayList<>();
        for (final String s : services) {
            if (variants.size() >= MAX_VARIANTS) break;
            variants.add(place == null ? s : s + " " + place);
        }
        if (place != null && variants.size() < MAX_VARIANTS) variants.add(services.iterator().next()); // a provider not naming the town
        return new Plan(new ArrayList<>(services), place, variants, derived);
    }

    /** The town or postal code of a question, or null. */
    static String place(final String text) {
        final Matcher m = PLACE.matcher(text);
        while (m.find()) {
            String candidate = m.group(1).replaceAll("[.,;:!?]+$", "");
            // "Neustadt bei Coburg" is a town, "Musterstadt bei Schimmel" a town and the problem of the question
            final Matcher two = Pattern.compile("^(.+?)\\s+(?:am|an der|im|bei|ob der)\\s+(.+)$").matcher(candidate);
            if (two.find()) {
                final String tail = RagQuery.fold(two.group(2));
                for (final Object[] need : NEEDS) if (((Pattern) need[0]).matcher(tail).find()) candidate = two.group(1);
            }
            if (!NOT_A_PLACE.contains(candidate)) return candidate;
        }
        final Matcher p = POSTAL.matcher(text);
        return p.find() ? p.group() : null;
    }

    /** Whether the automatic search answers the question weakly: stage any/none/no-terms, or no hit names a service. */
    public static boolean weak(final RagRetriever.Result result, final Plan plan) {
        if (result == null || result.selected.isEmpty() || "any".equals(result.stage) || "none".equals(result.stage)
                || "no-terms".equals(result.stage)) {
            return true;
        }
        for (final RagRetriever.Scored s : result.selected) if (names(s.candidate, plan.services)) return false;
        return true;
    }

    /**
     * Whether the search may run: the release of the tool {@code provider_search} (which follows {@code search} while not
     * set on its own) within the request's scope.
     */
    public static boolean released(final boolean indexTools, final ToolContext context) {
        return context != null && net.yacy.ai.ToolProvider.allowedCallsPerTurn("provider_search", indexTools, context) > 0;
    }

    /**
     * The plan of a request the model gives the tool {@code provider_search}: as {@link #plan(String)}, or, when it asks for
     * no provider in words ("Schimmelsanierung Musterstadt"), its own nouns as the service. Null without any.
     */
    public static Plan planAny(final String request) {
        final Plan plan = plan(request);
        if (plan != null) return plan;
        final String place = place(request);
        final Set<String> services = new LinkedHashSet<>();
        for (final RagQuery.Term t : RagFollowUp.topics(request)) {
            if (services.size() >= 3) break;
            if (t.phrase || t.weak) continue;
            if (place != null && RagQuery.fold(place).contains(t.stem)) continue;
            services.add(t.text);
        }
        if (services.isEmpty()) return null;
        final List<String> variants = new ArrayList<>();
        for (final String s : services) if (variants.size() < MAX_VARIANTS) variants.add(place == null ? s : s + " " + place);
        if (place != null && variants.size() < MAX_VARIANTS) variants.add(services.iterator().next());
        return new Plan(new ArrayList<>(services), place, variants, false);
    }

    /** How a provider relates to the place of the question. */
    public static final String AT_PLACE = "location"; // an address of the provider in the place (graph or its page)
    public static final String SERVES_PLACE = "service_area"; // a service area that names the place (graph or its page)
    public static final String MENTIONS_PLACE = "mentioned"; // its pages name the place only in passing: no evidence
    public static final String NO_PLACE = "none";

    /** One provider: a graph entity or, without one, a host; its pages and how it shows the service and the place. */
    public static final class Provider {
        public final String key;
        public String entity;
        public String name;
        public final List<String> pages = new ArrayList<>();
        /** the service shown by the graph (an offered service or the provider's name) */
        public boolean byGraph;
        /** the service named by one of its pages in the index */
        public boolean byText;
        public String place = NO_PLACE;
        public String placeEvidence;
        public String collection;
        final StringBuilder text = new StringBuilder();
        int order;

        Provider(final String key) {
            this.key = key;
        }

        int placeRank() {
            return AT_PLACE.equals(this.place) ? 3 : SERVES_PLACE.equals(this.place) ? 2 : 0;
        }

        public String url() {
            return this.pages.isEmpty() ? null : this.pages.get(0);
        }
    }

    /** What the search found. */
    public static final class Result {
        public final Plan plan;
        /** the providers shown (at most {@link #MAX_SHOWN}) */
        public final List<Provider> providers;
        /** the providers determined in the scope, each once (before the limit of {@link #providers}) */
        public final int found;
        /** per relation to the place: how many of {@link #found} */
        public final Map<String, Integer> perPlace;
        public final List<String> searched;
        /** why the count is only a lower bound (empty when complete) */
        public final List<String> gaps;
        public final boolean timedOut;
        public final long millis;

        public Result(final Plan plan, final List<Provider> providers, final int found, final Map<String, Integer> perPlace, final List<String> searched,
                final List<String> gaps, final boolean timedOut, final long millis) {
            this.plan = plan;
            this.providers = Collections.unmodifiableList(providers);
            this.found = found;
            this.perPlace = Collections.unmodifiableMap(perPlace);
            this.searched = Collections.unmodifiableList(searched);
            this.gaps = Collections.unmodifiableList(gaps);
            this.timedOut = timedOut;
            this.millis = millis;
        }

        public boolean lowerBound() {
            return !this.gaps.isEmpty();
        }
    }

    public static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known) {
        return run(context, plan, known, System::currentTimeMillis, MAX_MILLIS);
    }

    static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known, final LongSupplier clock) {
        return run(context, plan, known, clock, MAX_MILLIS);
    }

    /**
     * The providers of the service within the request's scope, determined before the answer's limit (the automatic search
     * and the tool {@code provider_search} both run this):
     * <ol>
     * <li>the graph: services named like the service ({@code type=service}, every page of hits) and the providers that offer
     * them ({@code offers}), and organisations and facilities whose own name names it; their addresses, service areas and
     * pages ({@link KgReader#entityDigests});</li>
     * <li>the direct index: every page of the scope that names the service (all pages of hits, by every service of the plan),
     * a page of a host of a provider of the graph counted for that provider, any other page for its host.</li>
     * </ol>
     * A provider counts once over its pages and duplicates; separate entities and hosts stay separate. With a place, a
     * provider is at the place by an address (graph, or a postal code and the place on its page), serves it by a service
     * area (graph, or an area phrase on its page), or only mentions it, which evidences nothing.
     */
    static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known, final LongSupplier clock,
            final long budget) {
        final long start = clock.getAsLong();
        final Map<String, Provider> providers = new LinkedHashMap<>();
        final Map<String, String> byHost = new LinkedHashMap<>(); // host -> provider key
        final List<String> searched = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();
        boolean timedOut = false;
        // 1. the graph: offered services and named providers
        try {
            final KgReader reader = context.graph ? context.reader() : null;
            if (reader != null) {
                final Viewer viewer = context.viewer(reader);
                final Set<String> ids = new LinkedHashSet<>();
                graph:
                for (final String service : plan.services) {
                    for (final String type : new String[] {"service", "organization", "facility"}) {
                        searched.add("graph " + type + ": " + service);
                        for (int offset = 0;; offset += GRAPH_PAGE) {
                            if (clock.getAsLong() - start > budget) {
                                timedOut = true;
                                gaps.add("time budget: the graph's " + type + " entities for " + service + " not all read");
                                break graph;
                            }
                            final KgReader.EntityQuery q = new KgReader.EntityQuery();
                            q.q = service;
                            q.type = type;
                            q.offset = offset;
                            q.limit = GRAPH_PAGE;
                            final JSONObject page = reader.entities(q, viewer);
                            final JSONArray items = page.optJSONArray("items");
                            for (int i = 0; items != null && i < items.length(); i++) {
                                final String id = items.getJSONObject(i).optString("id", "");
                                if (id.isEmpty()) continue;
                                if ("service".equals(type)) offerers(reader, viewer, id, ids, clock, start, budget);
                                else ids.add(id);
                            }
                            if (items == null || items.length() < GRAPH_PAGE || offset + GRAPH_PAGE >= page.optLong("total", 0L)) break;
                        }
                    }
                }
                final List<String> list = new ArrayList<>(ids);
                for (int i = 0; i < list.size() && !timedOut; i += GRAPH_PAGE) {
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        gaps.add("time budget: " + (list.size() - i) + " providers of the graph not read");
                        break;
                    }
                    final List<String> batch = list.subList(i, Math.min(list.size(), i + GRAPH_PAGE));
                    for (final KgReader.JobDigest d : reader.entityDigests(viewer, null, batch, 0L, batch.size()).items) {
                        if (d.name == null || d.url == null) continue; // no page of this scope behind it
                        final Provider p = providers.computeIfAbsent("e|" + d.id, Provider::new);
                        p.entity = d.id;
                        p.name = d.name;
                        p.byGraph = true;
                        p.collection = d.collection;
                        if (!p.pages.contains(d.url)) p.pages.add(d.url);
                        byHost.putIfAbsent(hostKey(d.url), p.key);
                        if (plan.place != null) graphPlace(p, d, plan.place);
                    }
                }
            }
        } catch (final Exception e) {
            gaps.add("graph: " + (e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed"));
        }
        // 2. the index: every page of the scope that names a service of the plan
        try {
            if (context.hasIndex()) {
                index:
                for (final String service : plan.services) {
                    final List<String> words = JobSearch.words(service);
                    if (words.isEmpty()) continue;
                    searched.add("index: " + service);
                    for (int offset = 0;; offset += INDEX_PAGE) {
                        if (clock.getAsLong() - start > budget) {
                            timedOut = true;
                            gaps.add("time budget: the index's pages for " + service + " read from " + offset + " on not read");
                            break index;
                        }
                        final ToolContext.IndexPage page = context.indexPages(words, null, offset, INDEX_PAGE);
                        for (final RagCandidate c : page.pages) textProvider(c, plan, providers, byHost);
                        if (page.pages.isEmpty() || offset + INDEX_PAGE >= page.total) break;
                    }
                }
            } else {
                gaps.add("no direct index: pages searched by ranked search only (" + PER_VARIANT + " per search, one per host)");
                for (final String variant : plan.variants) {
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        break;
                    }
                    searched.add(variant);
                    for (final RagRetriever.Scored s : context.retrieve(RagQuery.prepare(variant), PER_VARIANT).selected) {
                        textProvider(s.candidate, plan, providers, byHost);
                    }
                }
            }
        } catch (final Exception e) {
            gaps.add("index: failed");
        }
        // 3. a provider of the index's pages only: its organisation in the graph by its host (address, service area, name)
        try {
            final KgReader reader = context.graph ? context.reader() : null;
            if (reader != null && !timedOut) {
                final Viewer viewer = context.viewer(reader);
                int looked = 0;
                for (final Provider p : new ArrayList<>(providers.values())) {
                    if (p.entity != null || p.url() == null) continue;
                    if (looked >= MAX_HOST_LOOKUPS || clock.getAsLong() - start > budget) {
                        gaps.add("graph: the organisations of " + providers.values().stream().filter(x -> x.entity == null).count()
                                + " hosts of pages not all looked up (their place only from their pages)");
                        break;
                    }
                    looked++;
                    final JSONArray items = reader.hostEntities(new java.net.URI(p.url()).getHost(), 0, 20, viewer).optJSONArray("items");
                    String id = null;
                    for (int i = 0; items != null && i < items.length() && id == null; i++) {
                        final JSONObject item = items.getJSONObject(i);
                        final String type = item.optString("type", "");
                        if ("organization".equals(type) || "facility".equals(type)) id = item.optString("id", null);
                    }
                    if (id == null) continue;
                    for (final KgReader.JobDigest d : reader.entityDigests(viewer, null, List.of(id), 0L, 1).items) {
                        p.entity = d.id;
                        if (d.name != null) p.name = d.name;
                        if (plan.place != null && p.placeRank() < 3) {
                            final String before = p.place;
                            final String evidence = p.placeEvidence;
                            graphPlace(p, d, plan.place);
                            if (p.placeRank() == 0) {
                                p.place = before;
                                p.placeEvidence = evidence;
                            }
                        }
                    }
                }
            }
        } catch (final Exception e) {
            gaps.add("graph: the organisations of the pages' hosts not read");
        }
        if (timedOut && gaps.stream().noneMatch(g -> g.startsWith("time budget"))) gaps.add("time budget: the search stopped early");
        // the order: the place first (an address, then a service area), then by graph and text, then as found
        final List<Provider> all = new ArrayList<>(providers.values());
        int order = 0;
        for (final Provider p : all) p.order = order++;
        all.sort((a, b) -> a.placeRank() != b.placeRank() ? b.placeRank() - a.placeRank()
                : (a.byGraph && a.byText) != (b.byGraph && b.byText) ? (a.byGraph && a.byText ? -1 : 1) : a.order - b.order);
        final Map<String, Integer> perPlace = new LinkedHashMap<>();
        if (plan.place != null) {
            for (final String k : new String[] {AT_PLACE, SERVES_PLACE, MENTIONS_PLACE, NO_PLACE}) perPlace.put(k, 0);
            for (final Provider p : all) perPlace.merge(p.place, 1, Integer::sum);
        }
        return new Result(plan, new ArrayList<>(all.subList(0, Math.min(MAX_SHOWN, all.size()))), all.size(), perPlace, searched, gaps,
                timedOut, clock.getAsLong() - start);
    }

    /** The providers that offer a service ({@code offers} into it), every page of them. */
    private static void offerers(final KgReader reader, final Viewer viewer, final String service, final Set<String> ids, final LongSupplier clock,
            final long start, final long budget) throws Exception {
        for (int offset = 0; clock.getAsLong() - start <= budget; offset += GRAPH_PAGE) {
            final JSONObject page;
            try {
                page = reader.entityStatements(service, Vocabulary.OFFERS, true, false, offset, GRAPH_PAGE, viewer);
            } catch (final KgReader.NotFound e) {
                return;
            }
            final JSONArray items = page.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                final String subject = items.getJSONObject(i).optString("subject", "");
                if (!subject.isEmpty()) ids.add(subject);
            }
            if (items == null || items.length() < GRAPH_PAGE || offset + GRAPH_PAGE >= page.optLong("total", 0L)) return;
        }
    }

    /** A page of the index that names a service: for the provider of its host in the graph, else for its host. */
    private static void textProvider(final RagCandidate page, final Plan plan, final Map<String, Provider> providers, final Map<String, String> byHost) {
        if (page == null || !names(page, plan.services)) return; // the index matched the start of another word
        final String host = hostKey(page.url);
        final String key = byHost.computeIfAbsent(host, h -> "h|" + h);
        final Provider p = providers.computeIfAbsent(key, Provider::new);
        if (p.name == null) p.name = page.title.isEmpty() ? host : page.title;
        p.byText = true;
        if (p.collection == null && !page.collections.isEmpty()) p.collection = page.collections.get(0);
        if (!p.pages.contains(page.url)) p.pages.add(page.url);
        if (p.text.length() < 2000) p.text.append(' ').append(page.title).append(". ").append(page.text);
        if (plan.place != null && p.placeRank() < 3) textPlace(p, page, plan.place, plan.services);
    }

    /** The graph's address (location) or service area of a provider in the place. */
    private static void graphPlace(final Provider p, final KgReader.JobDigest d, final String place) {
        final Pattern named = placePattern(place);
        for (final String[] f : d.facts) {
            final String value = RagQuery.fold(f[1]);
            if ((Vocabulary.ADDRESS.equals(f[0]) || Vocabulary.LOCALITY.equals(f[0]) || Vocabulary.POSTAL_CODE.equals(f[0]))
                    && named.matcher(value).find()) {
                p.place = AT_PLACE;
                p.placeEvidence = "address in the graph: " + RagContext.clean(f[1], 120);
                return;
            }
            if (Vocabulary.SERVICE_AREA.equals(f[0]) && named.matcher(value).find() && p.placeRank() < 2) {
                p.place = SERVES_PLACE;
                p.placeEvidence = "service area in the graph: " + RagContext.clean(f[1], 120);
            }
        }
    }

    /** A page's evidence of the place: an address (postal code and place), an area phrase, or only a mention. */
    private static void textPlace(final Provider p, final RagCandidate page, final String place, final List<String> services) {
        final String text = RagQuery.fold(page.title + " " + page.description + " " + page.text);
        final String name = Pattern.quote(RagQuery.fold(place));
        final Matcher address = Pattern.compile("(?<!\\d)\\d{5}\\s+" + name + "(?![\\p{L}])").matcher(text);
        if (address.find()) {
            p.place = AT_PLACE;
            p.placeEvidence = "address on its page: " + excerpt(text, address.start());
            return;
        }
        final Matcher area = Pattern.compile("(?:einsatzgebiet|einzugsgebiet|einsatzorte?|tatig in|im raum|in und um|rund um|umgebung von|region|"
                + "service area|serving)\\W+(?:\\p{L}+\\W+){0,3}" + name + "(?![\\p{L}])|" + name + "\\s+und\\s+umgebung").matcher(text);
        if (area.find()) {
            if (p.placeRank() < 2) {
                p.place = SERVES_PLACE;
                p.placeEvidence = "service area on its page: " + excerpt(text, area.start());
            }
            return;
        }
        // the service stated for the place ("Tragwerksplanung in Musterstadt", "Schimmelsanierung für Musterstadt")
        for (final String service : services) {
            final String stem = Pattern.quote(RagQuery.stem(RagQuery.fold(service)));
            final Matcher offered = Pattern.compile(stem + "\\p{L}*(?:\\W+\\p{L}+){0,5}?\\W+(?:in|fur|im raum|rund um)\\s+" + name + "(?![\\p{L}])")
                    .matcher(text);
            if (offered.find()) {
                if (p.placeRank() < 2) {
                    p.place = SERVES_PLACE;
                    p.placeEvidence = "the service stated for the place on its page: " + excerpt(text, offered.start());
                }
                return;
            }
        }
        if (p.placeRank() == 0 && placePattern(place).matcher(text).find()) {
            p.place = MENTIONS_PLACE;
            p.placeEvidence = null;
        }
    }

    private static Pattern placePattern(final String place) {
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(RagQuery.fold(place)) + "(?![\\p{L}])");
    }

    private static String excerpt(final String text, final int at) {
        return RagContext.clean(text.substring(Math.max(0, at - 30), Math.min(text.length(), at + 90)), 140);
    }

    /** A host without scheme and "www.", one provider per host for pages without a provider of the graph. */
    static String hostKey(final String url) {
        try {
            final String h = new java.net.URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
        } catch (final Exception e) {
            return "";
        }
    }

    /** Whether a page names one of the services (as a word or the start of a compound: "Statiker", "Tragwerksplanung"). */
    static boolean names(final RagCandidate page, final List<String> services) {
        final String text = haystack(page);
        for (final String s : services) {
            final String stem = RagQuery.stem(s.toLowerCase(Locale.ROOT));
            if (stem.length() >= 4 && text.contains(stem)) return true;
        }
        return false;
    }

    private static String haystack(final RagCandidate page) {
        return RagQuery.fold(page.title + "\n" + page.description + "\n" + page.text);
    }

    /** The numbered providers of one answer. */
    public static final class Built {
        public final List<RagContext.Source> sources;
        public final String text;
        public final JSONObject meta;

        Built(final List<RagContext.Source> sources, final String text, final JSONObject meta) {
            this.sources = Collections.unmodifiableList(sources);
            this.text = text;
            this.meta = meta;
        }
    }

    /** Numbers the providers shown from {@code firstId}, within {@code maxChars}; one source each (its first page). */
    public static Built format(final Result result, final int firstId, final int maxChars, final String scope, final boolean followUp) {
        final List<RagContext.Source> sources = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        final JSONArray placeBy = new JSONArray();
        final RagQuery words = RagQuery.prepare(String.join(" ", result.plan.services) + (result.plan.place == null ? "" : " " + result.plan.place));
        for (final Provider p : result.providers) {
            final int id = firstId + sources.size();
            final String name = RagContext.clean(p.name, RagContext.MAX_TITLE_CHARS - 12);
            final StringBuilder entry = new StringBuilder();
            entry.append('[').append(id).append("] Provider ").append(name).append('\n');
            entry.append("URL: ").append(p.url()).append('\n');
            if (p.collection != null) entry.append("Collection: ").append(p.collection).append('\n');
            final StringBuilder body = new StringBuilder("A provider of ").append(String.join(", ", result.plan.services)).append(": ");
            body.append(p.byGraph && p.byText ? "the knowledge graph records it and its page names the service."
                    : p.byGraph ? "the knowledge graph records the service of it (its pages in the index do not name it)."
                    : "its page names the service (no provider of it in the knowledge graph).");
            if (p.pages.size() > 1) body.append(" Pages of it in the index: ").append(p.pages.size()).append('.');
            if (result.plan.place != null) {
                switch (p.place) {
                    case AT_PLACE:
                        body.append(" Located in ").append(result.plan.place).append(": ").append(p.placeEvidence).append('.');
                        placeBy.put(id);
                        break;
                    case SERVES_PLACE:
                        body.append(" Serves ").append(result.plan.place).append(": ").append(p.placeEvidence).append('.');
                        placeBy.put(id);
                        break;
                    case MENTIONS_PLACE:
                        body.append(" Its pages only mention ").append(result.plan.place).append(": neither its location nor a service area.");
                        break;
                    default:
                        body.append(" No location in or service area of ").append(result.plan.place).append(" is recorded.");
                        break;
                }
            }
            if (p.text.length() > 0) {
                final RagCandidate page = new RagCandidate(p.url(), p.name, "", p.text.toString(), "", List.of(), 0);
                body.append(" Its page: ").append(RagContext.excerpt(page, words, 360));
            }
            final int separator = text.length() == 0 ? 0 : 2;
            if (text.length() + separator + entry.length() + 6 + body.length() > Math.max(0, maxChars)) break;
            if (separator > 0) text.append("\n\n");
            text.append(entry).append("Text: ").append(body);
            sources.add(new RagContext.Source(id, "Provider " + name, p.url(), hostKey(p.url()), p.collection,
                    p.byText ? null : RagContext.Source.KIND_GRAPH, body.toString()));
        }
        final JSONObject meta = new JSONObject(true);
        try {
            meta.put("triggered", true);
            meta.put("followUp", followUp);
            meta.put("scope", scope);
            meta.put("services", new JSONArray(result.plan.services));
            meta.put("place", result.plan.place == null ? JSONObject.NULL : result.plan.place);
            meta.put("searched", new JSONArray(result.searched));
            meta.put("found", result.found); // providers determined in the scope, each once
            meta.put("lowerBound", result.lowerBound());
            meta.put("gaps", new JSONArray(result.gaps));
            meta.put("shown", sources.size());
            meta.put("perPlace", new JSONObject(result.perPlace));
            meta.put("placeNamedBy", placeBy); // the numbers with an evidenced location or service area
            meta.put("timedOut", result.timedOut);
            meta.put("millis", result.millis);
        } catch (final JSONException e) {
            // plain keys and values
        }
        return new Built(sources, text.toString(), meta);
    }
}
