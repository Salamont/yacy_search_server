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
 * knowledge graph with the session's viewer and the local index with the session's collection as a filter. The providers
 * are determined for the whole scope first (graph rows, the hosts with pages naming a service counted by host) and
 * counted, each once; service and place must be the same provider's. Texts are loaded only for the at most
 * {@link #MAX_SHOWN} providers shown.</li>
 * <li>The providers shown go to the answer as numbered sources with a page naming the service. Job postings are a
 * separate search ({@link JobSearch}, {@code type=job}).</li>
 * </ul>
 */
public final class ServiceSearch {

    public static final int MAX_VARIANTS = 4;
    /** providers shown in an answer; the ones found are determined before this limit */
    public static final int MAX_SHOWN = 6;
    public static final long MAX_MILLIS = 6000L;
    /** pages of one ranked search (only without a direct index) */
    static final int PER_VARIANT = 8;
    /** entities of the name index read per service */
    static final int GRAPH_MAX = 5000;
    /** addresses and service areas of the graph read for a place */
    static final int PLACE_FACTS = 5000;
    /** providers whose pages naming the place are read, per run and per question to the index, and the pages per host */
    static final int PLACE_HOSTS = 120;
    static final int PLACE_BATCH = 40;
    static final int PLACE_PAGES = 3;
    /** characters of the excerpt of a provider's page */
    static final int EXCERPT_CHARS = 240;

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
                if (t.phrase || t.weak || !t.noun || JobSearch.countWord(RagQuery.fold(t.text))) continue;
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
        return planAny(request, null);
    }

    /**
     * The plan of a tool's request: a question, or the service and the place in a few words. The place is taken from
     * {@code place} when given, else from "in <place>" or a postal code, else from the request's last capitalized words
     * after the service ("Tragwerksplanung Musterstadt", "Statik Neustadt an der Musterau"); null without a service.
     */
    public static Plan planAny(final String request, final String place) {
        final String given = place == null ? "" : place.trim().replaceAll("^(?i)(?:in|im|bei)\\s+", "").replaceAll("[.,;:!?]+$", "");
        final String text = request == null ? "" : request.trim();
        Plan plan = plan(text);
        String where = given.isEmpty() ? (plan != null && plan.place != null ? plan.place : place(text)) : (given.length() > 80 ? given.substring(0, 80) : given);
        if (where == null) where = barePlace(text);
        if (plan == null) {
            final Set<String> services = new LinkedHashSet<>();
            for (final RagQuery.Term t : RagFollowUp.topics(text)) {
                if (services.size() >= 3) break;
                if (t.phrase || t.weak || JobSearch.countWord(RagQuery.fold(t.text))) continue;
                if (where != null && RagQuery.fold(where).contains(t.stem)) continue;
                if (PROVIDER.matcher(" " + RagQuery.fold(t.text) + " ").find()) continue;
                services.add(t.text);
            }
            if (services.isEmpty()) return null;
            return withPlace(new ArrayList<>(services), where, false);
        }
        return java.util.Objects.equals(where, plan.place) ? plan : withPlace(new ArrayList<>(plan.services), where, plan.derived);
    }

    /** The searches for services and a place. */
    private static Plan withPlace(final List<String> services, final String place, final boolean derived) {
        final List<String> variants = new ArrayList<>();
        for (final String s : services) {
            if (variants.size() >= MAX_VARIANTS) break;
            variants.add(place == null ? s : s + " " + place);
        }
        if (place != null && variants.size() < MAX_VARIANTS) variants.add(services.get(0)); // a provider not naming the town
        return new Plan(services, place, variants, derived);
    }

    /** a place at the end of a request without a preposition: [prefix] Name[-Name] [am|an der|im|ob der|in der Name] */
    private static final Pattern BARE_PLACE = Pattern.compile("(?:^|\\s)((?:(?:Bad|Sankt|St\\.|Neu|Alt|Groß|Klein|Hohen|Ober|Unter|Nieder)\\s+)?"
            + "\\p{Lu}[\\p{L}]+(?:-\\p{Lu}?[\\p{L}]+)*(?:\\s+(?:am|an der|im|ob der|in der|vor der)\\s+\\p{Lu}[\\p{L}]+(?:-\\p{Lu}?[\\p{L}]+)*)?)"
            + "[\\s.,;:!?]*$");
    /** nouns of a request that are no place: parts of a building, kinds of work */
    private static final Set<String> NOT_A_BARE_PLACE = Set.of("keller", "dach", "wand", "wande", "decke", "boden", "bad", "kuche", "haus",
            "wohnung", "garten", "fenster", "heizung", "fassade", "altbau", "neubau", "umbau", "anbau", "sanierung", "gutachten", "treppe",
            "balkon", "garage", "mauer", "gebaude", "hausbau", "planung", "beratung", "pruefung", "prufung", "angebot", "kosten", "preis",
            "notdienst", "hilfe", "firma", "betrieb", "gewerbe", "privat", "sofort", "heute", "morgen");

    /** The place at the end of a request in a few words, after the service; null if there is none. */
    static String barePlace(final String text) {
        if (text == null) return null;
        final Matcher m = BARE_PLACE.matcher(text.trim());
        if (!m.find()) return null;
        final String candidate = m.group(1).trim();
        final String before = text.trim().substring(0, m.start(1)).trim();
        if (before.isEmpty()) return null; // the service comes first; a request of one name is the service
        final String folded = RagQuery.fold(candidate);
        if (NOT_A_PLACE.contains(candidate) || NOT_A_BARE_PLACE.contains(folded) || JobSearch.countWord(folded)) return null;
        if (PROVIDER.matcher(" " + folded + " ").find()) return null;
        for (final Object[] need : NEEDS) if (((Pattern) need[0]).matcher(folded).find()) return null; // a problem word, not a town
        return candidate;
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
        /** its host without "www." (of the page of its name, or of its pages in the index) */
        public String host;
        /** pages of its host in the index that name the service */
        public int pageCount;
        long row;
        /** a page naming the service was read for it */
        boolean read;
        final StringBuilder text = new StringBuilder();
        int order;

        Provider(final String key) {
            this.key = key;
        }

        int placeRank() {
            return AT_PLACE.equals(this.place) ? 3 : SERVES_PLACE.equals(this.place) ? 2 : 0;
        }

        /** for the order of the answer: a mention before no relation at all */
        int placeOrder() {
            return MENTIONS_PLACE.equals(this.place) ? 1 : placeRank();
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
        /** the time of each step in milliseconds: graph, index, place, selection, evidence */
        public Map<String, Long> phases = Collections.emptyMap();
        /** pages of the scope that name a service of the plan (the providers are their hosts) */
        public long servicePages;
        /** the keys of all providers found (each once: "e|" and a graph ID, or "h|" and a host), not only of those shown */
        public List<String> keys = Collections.emptyList();

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

    /** The result of this search computed earlier in this request (the same services, place and scope), or null. */
    public static Result computed(final ToolContext context, final Plan plan) {
        final Object known = context == null ? null : context.remembered(key(plan));
        return known instanceof Result ? (Result) known : null;
    }

    private static String key(final Plan plan) {
        return "providers|" + plan.services + "|" + (plan.place == null ? "" : RagQuery.fold(plan.place));
    }

    /**
     * The search of a request: computed once per request for the same services, place and scope; the automatic search of the
     * answer and a call of {@code provider_search} for the same share the result.
     */
    public static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known) {
        final Result earlier = computed(context, plan);
        if (earlier != null) return earlier;
        final Result result = run(context, plan, known, System::currentTimeMillis, MAX_MILLIS);
        if (context != null) context.remember(key(plan), result);
        return result;
    }

    static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known, final LongSupplier clock) {
        return run(context, plan, known, clock, MAX_MILLIS);
    }

    private static final List<String> PROVIDER_TYPES = List.of(Vocabulary.ORGANIZATION, Vocabulary.FACILITY);
    private static final List<String> PLACE_PREDICATES = List.of(Vocabulary.ADDRESS, Vocabulary.LOCALITY, Vocabulary.POSTAL_CODE,
            Vocabulary.SERVICE_AREA);

    /**
     * The providers of the service within the request's scope, determined and counted before the answer's limit (the
     * automatic search and the tool {@code provider_search} both run this). Which providers there are and why is asked of
     * the indexes first; texts are loaded only for the few shown:
     * <ol>
     * <li><b>graph</b>: the rows of the services, organisations and facilities named like a service of the plan (the name
     * index, {@link KgReader#namedEntities}), the providers that offer those services ({@link KgReader#subjectsOf}), and
     * of all of them the name and a current page ({@link KgReader#entityRefs});</li>
     * <li><b>index</b>: the hosts of the scope with pages that name a service, counted by host in one query
     * ({@link ToolContext#indexHosts}); a host of a provider of the graph counts for that provider, any other host is a
     * provider of its own. This is the number of providers, each once;</li>
     * <li><b>place</b> (with a place in the question): an address or service area in the graph that names the place
     * ({@link KgReader#factsNaming}), for a provider of step 1 or for the organisation of a host of step 2; and the hosts
     * of the scope with a page naming the place, counted by host: of those that are providers the pages naming the place
     * are read (at most {@link #PLACE_PAGES} per host) and tell an address, a service area or a mere mention. Service and
     * place belong to the same provider: its graph entity or its host, on whichever of its pages;</li>
     * <li><b>selection</b> and <b>evidence</b>: located before serving before mentioning before the rest; of the providers
     * shown one page naming the service is read for the excerpt, and the organisation of a host without one in step 1.</li>
     * </ol>
     * A provider counts once over its pages; separate entities and hosts stay separate. Every step checks the time budget;
     * a step that could not run or be read to its end makes the count a lower bound with its reason.
     */
    static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known, final LongSupplier clock,
            final long budget) {
        final long start = clock.getAsLong();
        final Map<String, Provider> providers = new LinkedHashMap<>();
        final Map<String, String> byHost = new LinkedHashMap<>(); // host -> provider key
        final Map<Long, Provider> byRow = new LinkedHashMap<>();
        final List<String> searched = new ArrayList<>();
        final List<String> gaps = new ArrayList<>();
        final Map<String, Long> phases = new LinkedHashMap<>();
        boolean timedOut = false;
        long servicePages = 0L;
        final List<List<String>> serviceWords = new ArrayList<>();
        for (final String service : plan.services) {
            final List<String> words = JobSearch.words(service);
            if (!words.isEmpty()) serviceWords.add(words);
        }
        KgReader reader = null;
        Viewer viewer = null;
        long mark = System.nanoTime();
        // 1. the graph: offered services and named providers, by their rows
        try {
            reader = context.graph ? context.reader() : null;
            if (reader != null) {
                viewer = context.viewer(reader);
                final Set<Long> services = new LinkedHashSet<>();
                final Set<Long> rows = new LinkedHashSet<>();
                final List<String> types = List.of(Vocabulary.SERVICE, Vocabulary.ORGANIZATION, Vocabulary.FACILITY);
                for (final String service : plan.services) {
                    searched.add("graph: " + service);
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        gaps.add("time budget: the graph's entities for " + service + " not read");
                        break;
                    }
                    final Map<Long, String> named = context.reader().namedEntities(viewer, service, false, types, GRAPH_MAX);
                    if (named.size() >= GRAPH_MAX) gaps.add("graph: more than " + GRAPH_MAX + " entities named like " + service + ", not all read");
                    for (final Map.Entry<Long, String> e : named.entrySet()) {
                        if (Vocabulary.SERVICE.equals(e.getValue())) services.add(e.getKey());
                        else rows.add(e.getKey());
                    }
                }
                if (!services.isEmpty() && !timedOut) rows.addAll(context.reader().subjectsOf(viewer, Vocabulary.OFFERS, services));
                if (!rows.isEmpty() && !timedOut) {
                    for (final KgReader.EntityRef ref : context.reader().entityRefs(viewer, rows)) {
                        final Provider p = providers.computeIfAbsent("e|" + ref.id, Provider::new);
                        p.entity = ref.id;
                        p.row = ref.rowid;
                        p.name = ref.name;
                        p.byGraph = true;
                        p.host = hostKey(ref.url);
                        if (!p.pages.contains(ref.url)) p.pages.add(ref.url);
                        byHost.putIfAbsent(p.host, p.key);
                        byRow.put(ref.rowid, p);
                    }
                }
            }
        } catch (final Exception e) {
            gaps.add("graph: " + (e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed"));
        }
        phases.put("graph", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        // 2. the index: the hosts of the scope with pages that name a service of the plan, counted by host
        final boolean direct = context.hasIndex();
        try {
            if (direct) {
                for (final String service : plan.services) searched.add("index: " + service);
                if (clock.getAsLong() - start > budget) {
                    timedOut = true;
                    gaps.add("time budget: the index's pages naming the service not counted");
                } else if (!serviceWords.isEmpty()) {
                    final ToolContext.HostCount hosts = context.indexHosts(serviceWords);
                    servicePages = hosts.pages;
                    for (final Map.Entry<String, Integer> e : hosts.pagesByHost.entrySet()) {
                        final String key = byHost.computeIfAbsent(e.getKey(), h -> "h|" + h);
                        final Provider p = providers.computeIfAbsent(key, Provider::new);
                        if (p.name == null) p.name = e.getKey();
                        if (p.host == null) p.host = e.getKey();
                        p.byText = true;
                        p.pageCount += e.getValue();
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
        phases.put("index", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        // 3. the place: the same provider's address or service area in the graph, or its own pages naming the place
        if (plan.place != null) {
            try {
                if (reader != null && !timedOut) {
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        gaps.add("time budget: the graph's addresses and service areas for " + plan.place + " not read");
                    } else {
                        final List<KgReader.FactRow> facts = context.reader().factsNaming(viewer, PLACE_PREDICATES, plan.place, PLACE_FACTS);
                        if (facts.size() >= PLACE_FACTS) gaps.add("graph: more than " + PLACE_FACTS + " addresses name " + plan.place + ", not all read");
                        // of entities that are no provider of step 1: the organisation of a host that names the service
                        final Set<Long> others = new LinkedHashSet<>();
                        for (final KgReader.FactRow f : facts) if (!byRow.containsKey(f.subject)) others.add(f.subject);
                        if (!others.isEmpty()) {
                            for (final KgReader.EntityRef ref : context.reader().entityRefs(viewer, others)) {
                                if (!PROVIDER_TYPES.contains(ref.type)) continue;
                                final String key = byHost.get(hostKey(ref.url));
                                final Provider p = key == null ? null : providers.get(key);
                                if (p == null || p.entity != null) continue; // no provider's host, or a host of a provider of the graph
                                p.entity = ref.id;
                                p.row = ref.rowid;
                                p.name = ref.name;
                                byRow.put(ref.rowid, p);
                            }
                        }
                        for (final KgReader.FactRow f : facts) {
                            final Provider p = byRow.get(f.subject);
                            if (p != null) graphPlace(p, f.predicate, f.value, plan.place);
                        }
                    }
                }
                if (direct && !timedOut) {
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        gaps.add("time budget: the pages naming " + plan.place + " not read");
                    } else {
                        final List<List<String>> placeWords = List.of(JobSearch.words(plan.place));
                        final ToolContext.HostCount named = context.indexHosts(placeWords);
                        final Map<String, Provider> candidates = new java.util.TreeMap<>();
                        for (final Provider p : providers.values()) {
                            if (p.host != null && p.placeRank() < 3 && named.pagesByHost.containsKey(p.host)) candidates.put(p.host, p);
                        }
                        final List<String> hosts = new ArrayList<>(candidates.keySet());
                        int read = 0;
                        for (int i = 0; i < hosts.size(); i += PLACE_BATCH) {
                            if (read >= PLACE_HOSTS || clock.getAsLong() - start > budget) break;
                            final List<String> batch = hosts.subList(i, Math.min(hosts.size(), Math.min(i + PLACE_BATCH, PLACE_HOSTS)));
                            for (final RagCandidate page : context.indexHostPages(placeWords, batch, PLACE_PAGES)) {
                                final Provider p = candidates.get(hostKey(page.url));
                                if (p != null && p.placeRank() < 3) textPlace(p, page, plan.place, plan.services);
                            }
                            read += batch.size();
                        }
                        // a host of the count names the place: at least a mention, whatever its pages read here said
                        int unread = 0;
                        for (int i = 0; i < hosts.size(); i++) {
                            final Provider p = candidates.get(hosts.get(i));
                            if (NO_PLACE.equals(p.place)) p.place = MENTIONS_PLACE;
                            if (i >= read) unread++;
                        }
                        if (unread > 0) {
                            if (clock.getAsLong() - start > budget) timedOut = true;
                            gaps.add("place: the pages of " + unread + " providers naming " + plan.place + " not read (counted as mentioned)");
                        }
                    }
                }
            } catch (final Exception e) {
                gaps.add("place: " + (e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed"));
            }
        }
        phases.put("place", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        if (timedOut && gaps.stream().noneMatch(g -> g.startsWith("time budget"))) gaps.add("time budget: the search stopped early");
        // 4. the order: the place first (an address, a service area, a mention), then graph and text, the pages, the name
        final List<Provider> all = new ArrayList<>();
        for (final Provider p : providers.values()) if (p.byGraph || p.byText) all.add(p);
        all.sort((a, b) -> a.placeOrder() != b.placeOrder() ? b.placeOrder() - a.placeOrder()
                : (a.byGraph && a.byText) != (b.byGraph && b.byText) ? (a.byGraph && a.byText ? -1 : 1)
                : a.pageCount != b.pageCount ? b.pageCount - a.pageCount : a.key.compareTo(b.key));
        final Map<String, Integer> perPlace = new LinkedHashMap<>();
        if (plan.place != null) {
            for (final String k : new String[] {AT_PLACE, SERVES_PLACE, MENTIONS_PLACE, NO_PLACE}) perPlace.put(k, 0);
            for (final Provider p : all) perPlace.merge(p.place, 1, Integer::sum);
        }
        final List<Provider> shown = new ArrayList<>(all.subList(0, Math.min(MAX_SHOWN, all.size())));
        phases.put("selection", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        // 5. the evidence of the providers shown, and only of them
        try {
            evidence(context, viewer, plan, shown, serviceWords, direct);
        } catch (final Exception e) {
            // the count stands; a provider shown keeps what the search knows of it
        }
        phases.put("evidence", (System.nanoTime() - mark) / 1_000_000L);
        final Result result = new Result(plan, shown, all.size(), perPlace, searched, gaps, timedOut, clock.getAsLong() - start);
        result.phases = Collections.unmodifiableMap(phases);
        result.servicePages = servicePages;
        final List<String> keys = new ArrayList<>();
        for (final Provider p : all) keys.add(p.key);
        result.keys = Collections.unmodifiableList(keys);
        return result;
    }

    /** One page naming the service for each provider shown that has none yet, and the organisation of a host of the index. */
    private static void evidence(final ToolContext context, final Viewer viewer, final Plan plan, final List<Provider> shown,
            final List<List<String>> serviceWords, final boolean direct) throws Exception {
        if (direct && !serviceWords.isEmpty()) {
            final Map<String, Provider> wanted = new LinkedHashMap<>();
            for (final Provider p : shown) if (p.byText && p.host != null && !p.read) wanted.put(p.host, p);
            if (!wanted.isEmpty()) {
                for (final RagCandidate page : context.indexHostPages(serviceWords, wanted.keySet(), 1)) {
                    final Provider p = wanted.get(hostKey(page.url));
                    if (p == null || p.read) continue;
                    p.read = true;
                    if (!p.pages.contains(page.url)) p.pages.add(0, page.url); // the page that names the service first
                    if (p.entity == null && (p.name == null || p.name.equals(p.host)) && !page.title.isEmpty()) p.name = page.title;
                    if (p.collection == null && !page.collections.isEmpty()) {
                        p.collection = context.collection != null ? context.collection : page.collections.get(0);
                    }
                    p.text.setLength(0);
                    p.text.append(page.title).append(". ").append(page.text);
                }
            }
        }
        if (context.graph && viewer != null) {
            for (final Provider p : shown) {
                if (p.entity != null || p.host == null) continue;
                // its organisation in the graph by its host, for its name: the one whose own page is on this host
                final List<Long> rows = context.reader().hostEntityRows(viewer, p.host, PROVIDER_TYPES, 20);
                if (rows.isEmpty()) continue;
                for (final KgReader.EntityRef ref : context.reader().entityRefs(viewer, rows)) {
                    if (!p.host.equals(hostKey(ref.url))) continue;
                    p.entity = ref.id;
                    p.row = ref.rowid;
                    p.name = ref.name;
                    break;
                }
            }
        }
        for (final Provider p : shown) if (p.collection == null) p.collection = context.collection;
    }

    /** A page of the index that names a service: for the provider of its host in the graph, else for its host. */
    private static void textProvider(final RagCandidate page, final Plan plan, final Map<String, Provider> providers, final Map<String, String> byHost) {
        if (page == null || !names(page, plan.services)) return; // the index matched the start of another word
        final String host = hostKey(page.url);
        final String key = byHost.computeIfAbsent(host, h -> "h|" + h);
        final Provider p = providers.computeIfAbsent(key, Provider::new);
        if (p.name == null) p.name = page.title.isEmpty() ? host : page.title;
        if (p.host == null) p.host = host;
        p.byText = true;
        p.read = true;
        if (p.collection == null && !page.collections.isEmpty()) p.collection = page.collections.get(0);
        if (!p.pages.contains(page.url)) {
            p.pages.add(page.url);
            p.pageCount++;
        }
        if (p.text.length() < 2000) p.text.append(' ').append(page.title).append(". ").append(page.text);
        if (plan.place != null && p.placeRank() < 3) textPlace(p, page, plan.place, plan.services);
    }

    /** The graph's address (location) or service area of a provider in the place: one of its statements. */
    private static void graphPlace(final Provider p, final String predicate, final String raw, final String place) {
        if (raw == null) return;
        final String value = RagQuery.fold(raw);
        if (!placePattern(place).matcher(value).find()) return;
        if (Vocabulary.ADDRESS.equals(predicate) || Vocabulary.LOCALITY.equals(predicate) || Vocabulary.POSTAL_CODE.equals(predicate)) {
            p.place = AT_PLACE;
            p.placeEvidence = "address in the graph: " + RagContext.clean(raw, 120);
        } else if (Vocabulary.SERVICE_AREA.equals(predicate) && p.placeRank() < 2) {
            p.place = SERVES_PLACE;
            p.placeEvidence = "service area in the graph: " + RagContext.clean(raw, 120);
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
            final int pages = Math.max(p.pageCount, p.byText ? p.pages.size() : 0);
            if (pages > 1) body.append(" Pages of it naming the service: ").append(pages).append('.');
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
                body.append(" Its page: ").append(RagContext.excerpt(page, words, EXCERPT_CHARS));
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
            meta.put("servicePages", result.servicePages);
            meta.put("phases", new JSONObject(result.phases));
        } catch (final JSONException e) {
            // plain keys and values
        }
        return new Built(sources, text.toString(), meta);
    }
}
