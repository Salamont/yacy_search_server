/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
 * counted, each once; service and place must be the same provider's: its own address or area, or that of a facility it
 * operates, never the address of another company, a project or a plot on its site ({@link ProviderPlace}). Texts are loaded only for the at most
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
    /** pages naming the service read per located provider to see whose service it is ({@link #sameCompany}) */
    static final int SERVICE_PAGES = 3;
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
    public static final String MENTIONS_PLACE = "mentioned"; // all its pages naming the place were read: they name it only in passing
    /**
     * its pages name the place, but not all of them were read (the limit of {@link #PLACE_HOSTS} providers or of
     * {@link #PLACE_PAGES} pages each, or the time budget): whether it is located there or serves the place is open
     */
    public static final String UNDECIDED_PLACE = "undecided";
    public static final String NO_PLACE = "none"; // no page of its host names the place, and the graph records no address or area there

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
        /** the page that evidences the place (the provider's address or service area): not always the page naming the service */
        public String placeSource;
        /**
         * what its site says of the place without evidencing its location: an address of another company, of a project or a
         * plot, or one that cannot be shown to be its own (with {@link #UNDECIDED_PLACE} or {@link #MENTIONS_PLACE})
         */
        public String placeNote;
        public String collection;
        /** its host without "www." (of the page of its name, or of its pages in the index) */
        public String host;
        /** pages of its host in the index that name the service */
        public int pageCount;
        long row;
        /** the graph statement that evidences its place, until its page is read for the answer */
        long placeStatement;
        /** the strongest finding of its site about the place that is not its own address or area ({@link ProviderPlace}) */
        int otherPlace = ProviderPlace.NONE;
        /** a page naming the service was read for it */
        boolean read;
        /** whose the address or area of its place is: the words of that company's name, empty when no company is named */
        Set<String> placeCompany = Collections.emptySet();
        String placeCompanyName;
        /** its page states the service for the place itself: both by one sentence */
        boolean placeWithService;
        /** the company of its pages naming the service was compared with the company of its place */
        boolean compared;
        /** the graph's operator of its host is not who its pages name for the service: not shown as that organisation */
        boolean unbound;
        /** the pages naming the service read by the ranked search (without a direct index) */
        final List<RagCandidate> servicePages = new ArrayList<>(2);
        final StringBuilder text = new StringBuilder();
        int order;

        Provider(final String key) {
            this.key = key;
        }

        int placeRank() {
            return AT_PLACE.equals(this.place) ? 3 : SERVES_PLACE.equals(this.place) ? 2 : 0;
        }

        /** for the order of the answer: an address, a service area, an open relation, a mention, no relation */
        int placeOrder() {
            return AT_PLACE.equals(this.place) ? 4 : SERVES_PLACE.equals(this.place) ? 3 : UNDECIDED_PLACE.equals(this.place) ? 2
                    : MENTIONS_PLACE.equals(this.place) ? 1 : 0;
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
        /** why the count of providers is only a lower bound (empty when complete) */
        public final List<String> gaps;
        /**
         * why the relation to the place is not determined for every provider (empty when it is): then the providers located in
         * or serving the place are at least those of {@link #perPlace}, and {@link #UNDECIDED_PLACE} counts the open ones. The
         * count of providers itself is not affected.
         */
        public List<String> placeGaps = Collections.emptyList();
        /** of the open providers: those whose pages naming the place were not read at all, and those read only in part */
        public int placeUnread, placePartly;
        /** of the open providers: those whose site shows an address in the place that is not shown to be their own */
        public int placeUnattributed;
        /** whether the time budget, not the limit of {@link #PLACE_HOSTS}, left pages naming the place unread */
        public boolean placeOutOfTime;
        public final boolean timedOut;
        public final long millis;
        /** the time of each step in milliseconds: graph, index, place, selection, evidence */
        public Map<String, Long> phases = Collections.emptyMap();
        /** pages of the scope that name a service of the plan (the providers are their hosts) */
        public long servicePages;
        /** the keys of all providers found (each once: "e|" and a graph ID, or "h|" and a host), not only of those shown */
        public List<String> keys = Collections.emptyList();
        /** every provider found with its relation to the place, in the order of the answer ({@link #providers} are its first) */
        List<Provider> everyone = Collections.emptyList();

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

        /** whether the relation to the place is open for some providers (see {@link #placeGaps}) */
        public boolean placeOpen() {
            return !this.placeGaps.isEmpty();
        }

        /** the providers whose relation to the place is open */
        public int undecided() {
            return this.perPlace.getOrDefault(UNDECIDED_PLACE, 0);
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
    /** whose a facility or site is: its operator and what is located at it (statements into it), what it is part of */
    private static final List<String> OWNER_INTO = List.of(Vocabulary.OPERATES, Vocabulary.LOCATED_AT);
    private static final List<String> OWNER_OF = List.of(Vocabulary.PART_OF);


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
     * <li><b>place</b> (with a place in the question), by the identity of the provider: an address or service area in the
     * graph that names the place ({@link KgReader#factsNaming}) counts for the provider itself or a facility it operates
     * ({@code operates}, {@code part_of}, {@code located_at}); a host of step 2 is the organisation it belongs to
     * (the declared operator of its domain, {@link KgReader#siteOperators}). Of the providers whose hosts have a page naming the
     * place (at most {@link #PLACE_HOSTS}, providers of the graph and those with the most pages naming the service first)
     * the pages naming the place are read (at most {@link #PLACE_PAGES} per host) and asked whose the address or area on
     * them is ({@link ProviderPlace}): the provider's own, another company's, a project's or plot's, or not shown. A
     * provider whose pages naming the place were all read without an address or area of its own only mentions it; one
     * whose pages were not all read, or whose site shows an address that cannot be shown to be its own, is
     * {@link #UNDECIDED_PLACE}. A host can carry several companies: for a provider known by the pages of its host only,
     * the company its pages name for the service must be the company of the address or area ({@link #sameCompany});</li>
     * <li><b>selection</b> and <b>evidence</b>: located before serving before open before mentioning before the rest; of
     * the providers shown one page naming the service is read for the excerpt, and the organisation of a host without one
     * in step 1.</li>
     * </ol>
     * A provider counts once over its pages; separate entities and hosts stay separate. Every step checks the time budget;
     * a step of the count (graph, index) that could not run or be read to its end makes the count a lower bound with its
     * reason ({@link Result#gaps}). The place step does not change the count: what it could not read leaves the relation
     * to the place open for those providers ({@link Result#placeGaps}), and the numbers located and serving are then at
     * least those evidenced.
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
                    // one provider, not two: a facility or a part of an organisation that is itself among the rows counts for it
                    final Map<Long, Long> owner = new java.util.HashMap<>();
                    for (final KgReader.FactRow f : context.reader().factsInto(viewer, List.of(Vocabulary.OPERATES), rows)) {
                        if (rows.contains(f.subject) && f.subject != f.object) owner.putIfAbsent(f.object, f.subject);
                    }
                    for (final KgReader.FactRow f : context.reader().factsOf(viewer, rows, OWNER_OF)) {
                        if (f.object > 0L && rows.contains(f.object) && f.subject != f.object) owner.putIfAbsent(f.subject, f.object);
                    }
                    final List<KgReader.EntityRef> refs = context.reader().entityRefs(viewer, rows);
                    for (final KgReader.EntityRef ref : refs) {
                        if (owner.containsKey(ref.rowid)) continue;
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
                    for (final KgReader.EntityRef ref : refs) {
                        Long top = owner.get(ref.rowid);
                        for (int hop = 0; top != null && hop < 3 && owner.containsKey(top); hop++) top = owner.get(top);
                        if (top == null) continue;
                        final Provider p = byRow.get(top);
                        if (p == null) continue; // its owner is not visible with a page: not counted on its own either
                        byRow.put(ref.rowid, p);
                        if (!p.pages.contains(ref.url)) p.pages.add(ref.url);
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
        // 3. the place, by the identity of the provider. Service and place must be the same provider's: its own address or
        // service area in the graph, that of a facility it operates, or its own address or area on a page of its site. An
        // address of another company on its site (a directory's entry, a company of a group), of a project or of a plot is
        // not its location; an address that cannot be shown to be its own leaves the relation open. A provider whose pages
        // naming the place were not all read is open too. The count of providers stays as it is.
        final List<String> placeGaps = new ArrayList<>();
        final int[] open = new int[3]; // unread, partly read, an address not shown to be its own
        boolean placeOutOfTime = false;
        if (plan.place != null) {
            boolean decidable = true; // whether the providers without evidence of the place can be told apart
            try {
                if (reader != null && !timedOut) {
                    if (clock.getAsLong() - start > budget) {
                        placeGaps.add("time budget: the graph's addresses and service areas for " + plan.place + " not read");
                    } else {
                        final List<KgReader.FactRow> facts = context.reader().factsNaming(viewer, PLACE_PREDICATES, plan.place, PLACE_FACTS);
                        if (facts.size() >= PLACE_FACTS) placeGaps.add("graph: more than " + PLACE_FACTS + " addresses name " + plan.place + ", not all read");
                        final Set<Long> others = new LinkedHashSet<>();
                        for (final KgReader.FactRow f : facts) if (!byRow.containsKey(f.subject)) others.add(f.subject);
                        final Map<Long, KgReader.EntityRef> refs = new java.util.HashMap<>();
                        final Map<Long, Set<Long>> owners = new java.util.HashMap<>();
                        if (!others.isEmpty()) {
                            for (final KgReader.EntityRef ref : context.reader().entityRefs(viewer, others)) refs.put(ref.rowid, ref);
                            // whose they are: the organisation that operates a facility or that it is part of, what is located at a
                            // site (two steps: the site of a facility of an organisation)
                            Set<Long> frontier = others;
                            for (int hop = 0; hop < 2 && !frontier.isEmpty(); hop++) {
                                final Set<Long> next = new LinkedHashSet<>();
                                for (final KgReader.FactRow f : context.reader().factsInto(viewer, OWNER_INTO, frontier)) {
                                    if (owners.computeIfAbsent(f.object, k -> new LinkedHashSet<>()).add(f.subject)) next.add(f.subject);
                                }
                                for (final KgReader.FactRow f : context.reader().factsOf(viewer, frontier, OWNER_OF)) {
                                    if (f.object > 0L && owners.computeIfAbsent(f.subject, k -> new LinkedHashSet<>()).add(f.object)) next.add(f.object);
                                }
                                next.removeAll(owners.keySet());
                                frontier = next;
                            }
                            // the provider of a host without an entity of step 1 is the organisation the host belongs to
                            final Set<String> hosts = new LinkedHashSet<>();
                            for (final KgReader.EntityRef ref : refs.values()) {
                                final String key = byHost.get(hostKey(ref.url));
                                final Provider p = key == null ? null : providers.get(key);
                                if (p != null && p.entity == null && p.row == 0L) hosts.add(p.host);
                            }
                            identify(context, viewer, providers, byHost, byRow, hosts);
                        }
                        // the facilities of providers that carry the place: by their names
                        final Set<Long> facilities = new LinkedHashSet<>();
                        for (final KgReader.FactRow f : facts) {
                            final Provider p = byRow.get(f.subject);
                            if (p != null && p.row != f.subject) facilities.add(f.subject);
                        }
                        final Map<Long, String> facilityNames = facilities.isEmpty() ? Map.of() : context.reader().entityNames(viewer, facilities);
                        for (final KgReader.FactRow f : facts) {
                            Provider p = byRow.get(f.subject);
                            String through = p == null || p.row == f.subject ? null : facilityNames.get(f.subject) == null ? "a facility of it"
                                    : "its facility " + RagContext.clean(facilityNames.get(f.subject), 80);
                            final KgReader.EntityRef ref = refs.get(f.subject);
                            if (p == null) {
                                // a facility or site of a provider: its operator, or the operator of what is located there
                                final Set<Long> seen = new LinkedHashSet<>(owners.getOrDefault(f.subject, Set.of()));
                                for (final Long owner : new ArrayList<>(seen)) seen.addAll(owners.getOrDefault(owner, Set.of()));
                                for (final Long owner : seen) {
                                    if (byRow.containsKey(owner)) {
                                        p = byRow.get(owner);
                                        through = ref == null ? "a facility of it" : "its facility " + RagContext.clean(ref.name, 80);
                                        break;
                                    }
                                }
                            }
                            if (p != null) {
                                graphPlace(p, f, plan.place, through);
                                continue;
                            }
                            // an entity on a provider's site that is not the provider: another company's or an unrelated address
                            if (ref == null || !placePattern(plan.place).matcher(RagQuery.fold(f.value == null ? "" : f.value)).find()) continue;
                            final String key = byHost.get(hostKey(ref.url));
                            final Provider host = key == null ? null : providers.get(key);
                            if (host == null || Vocabulary.SERVICE_AREA.equals(f.predicate)) continue;
                            final boolean related = Vocabulary.ORGANIZATION.equals(ref.type)
                                    ? ProviderPlace.related(ProviderPlace.core(ref.name), ProviderPlace.core(host.name), plan.services)
                                    : true; // a facility or site on its pages the graph does not link to it
                            final int kind = related ? ProviderPlace.UNSURE : ProviderPlace.FOREIGN;
                            if (kind > host.otherPlace) {
                                host.otherPlace = kind;
                                host.placeNote = (related ? "an address in " + plan.place + " on its site belongs to " + RagContext.clean(ref.name, 80)
                                        + ", which the knowledge graph does not record as the provider's own"
                                        : "its site names " + plan.place + " as the address of another company (" + RagContext.clean(ref.name, 80) + ")")
                                        + ": " + RagContext.clean(f.value, 120);
                                host.placeStatement = f.statement;
                            }
                        }
                    }
                }
                if (!direct) {
                    placeGaps.add("no direct index: the place only from the pages read by ranked search");
                } else if (!timedOut && clock.getAsLong() - start > budget) {
                    decidable = false;
                    placeOutOfTime = true;
                    placeGaps.add("time budget: the pages naming " + plan.place + " not counted");
                } else if (!timedOut) {
                    final List<List<String>> placeWords = List.of(JobSearch.words(plan.place));
                    final ToolContext.HostCount named = context.indexHosts(placeWords);
                    final Map<String, Provider> candidates = new LinkedHashMap<>();
                    for (final Provider p : providers.values()) {
                        if (p.host != null && p.placeRank() < 3 && named.pagesByHost.containsKey(p.host)) candidates.put(p.host, p);
                    }
                    // the likely providers first: one of the graph, then the most pages naming the service, then the most pages
                    // naming the place (an address in every footer), then by host; what the limit leaves unread stays open
                    final List<String> hosts = new ArrayList<>(candidates.keySet());
                    hosts.sort((a, b) -> {
                        final Provider pa = candidates.get(a);
                        final Provider pb = candidates.get(b);
                        if (pa.byGraph != pb.byGraph) return pa.byGraph ? -1 : 1;
                        if (pa.pageCount != pb.pageCount) return pb.pageCount - pa.pageCount;
                        final int na = named.pagesByHost.get(a);
                        final int nb = named.pagesByHost.get(b);
                        return na != nb ? nb - na : a.compareTo(b);
                    });
                    final Map<String, Set<String>> seen = new java.util.HashMap<>();
                    int read = 0;
                    boolean outOfTime = false;
                    for (int i = 0; i < hosts.size() && read < PLACE_HOSTS; i += PLACE_BATCH) {
                        if (clock.getAsLong() - start > budget) {
                            outOfTime = true;
                            break;
                        }
                        final List<String> batch = hosts.subList(i, Math.min(hosts.size(), Math.min(i + PLACE_BATCH, PLACE_HOSTS)));
                        // whose the hosts are: the name an address must carry to be the provider's own
                        if (reader != null) {
                            final Set<String> unnamed = new LinkedHashSet<>();
                            for (final String host : batch) if (candidates.get(host).row == 0L) unnamed.add(host);
                            if (!unnamed.isEmpty()) identify(context, viewer, providers, byHost, byRow, unnamed);
                        }
                        for (final RagCandidate page : context.indexHostPages(placeWords, batch, PLACE_PAGES)) {
                            final String host = hostKey(page.url);
                            final Provider p = candidates.get(host);
                            if (p == null) continue;
                            seen.computeIfAbsent(host, h -> new HashSet<>()).add(page.url);
                            if (p.placeRank() < 3) textPlace(p, page, plan.place, plan.services);
                        }
                        read += batch.size();
                    }
                    // whose service it is, for the providers located or serving by a named company: one company, not two of a host
                    final Map<String, Provider> located = new LinkedHashMap<>();
                    for (final Provider p : providers.values()) if (toCompare(p) && p.host != null) located.put(p.host, p);
                    final List<String> locatedHosts = new ArrayList<>(located.keySet());
                    for (int i = 0; i < locatedHosts.size() && !serviceWords.isEmpty(); i += PLACE_BATCH) {
                        if (clock.getAsLong() - start > budget) break; // left to the check below: open
                        final List<String> batch = locatedHosts.subList(i, Math.min(locatedHosts.size(), i + PLACE_BATCH));
                        final Map<String, List<RagCandidate>> pages = new java.util.HashMap<>();
                        for (final RagCandidate page : context.indexHostPages(serviceWords, batch, SERVICE_PAGES)) {
                            pages.computeIfAbsent(hostKey(page.url), h -> new ArrayList<>()).add(page);
                        }
                        for (final String host : batch) {
                            final Provider p = located.get(host);
                            final List<RagCandidate> got = pages.getOrDefault(host, Collections.emptyList());
                            sameCompany(p, got, p.pageCount <= got.size(), plan, byRow);
                        }
                    }
                    int unread = 0;
                    int partly = 0;
                    for (int i = 0; i < hosts.size(); i++) {
                        final Provider p = candidates.get(hosts.get(i));
                        if (p.placeRank() >= 2) continue; // an address or a service area: evidenced
                        final int pages = named.pagesByHost.get(hosts.get(i));
                        final int pagesRead = seen.getOrDefault(hosts.get(i), Collections.emptySet()).size();
                        if (i >= read || pagesRead < pages) {
                            p.place = UNDECIDED_PLACE;
                            p.placeEvidence = null;
                            if (i < read) partly++;
                            else unread++;
                        } else if (p.otherPlace < ProviderPlace.UNSURE) {
                            p.place = MENTIONS_PLACE; // every page of it naming the place was read: no address or area of its own
                            p.placeEvidence = null;
                        }
                    }
                    open[0] = unread;
                    open[1] = partly;
                    placeOutOfTime = outOfTime && unread > 0;
                    if (unread > 0) {
                        placeGaps.add((outOfTime ? "time budget" : "place: the limit of " + PLACE_HOSTS + " providers read") + ": the pages of " + unread
                                + " providers naming " + plan.place + " not read, their relation to it is open");
                    }
                    if (partly > 0) {
                        placeGaps.add("place: " + partly + " providers have more than " + PLACE_PAGES + " pages naming " + plan.place
                                + " without an address or a service area among those read, their relation to it is open");
                    }
                }
            } catch (final Exception e) {
                decidable = false;
                placeGaps.add("place: " + (e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed"));
            }
            // the located ones whose service was not compared with the company of the place: by the pages the ranked search
            // read, else open (the time budget, a failed read)
            int uncompared = 0;
            for (final Provider p : providers.values()) {
                if (!toCompare(p)) continue;
                if (!p.servicePages.isEmpty()) {
                    sameCompany(p, p.servicePages, p.pageCount <= p.servicePages.size(), plan, byRow);
                } else {
                    p.compared = true;
                    p.place = NO_PLACE;
                    p.otherPlace = ProviderPlace.UNSURE; // open, with the others whose address is not shown to be their own
                    p.placeNote = "an address or service area in " + plan.place + " is evidenced for "
                            + RagContext.clean(p.placeCompanyName == null ? "a company of its site" : p.placeCompanyName, 80)
                            + "; whether its pages name the service for the same company was not read";
                    p.placeEvidence = null;
                    uncompared++;
                }
            }
            if (uncompared > 0) {
                placeGaps.add("place: for " + uncompared + " providers with an address or service area in " + plan.place
                        + " the pages naming the service were not compared with the company of the place (the time budget or a failed read)");
            }
            // an address in the place on its site that is not shown to be its own: open; another company's, a project's or a
            // plot's: named on its site, not its location
            for (final Provider p : providers.values()) {
                if (p.placeRank() >= 2 || UNDECIDED_PLACE.equals(p.place)) {
                    if (p.placeRank() >= 2) p.placeNote = null;
                    continue;
                }
                if (p.otherPlace == ProviderPlace.UNSURE) {
                    p.place = UNDECIDED_PLACE;
                    open[2]++;
                } else if (p.otherPlace == ProviderPlace.FOREIGN) {
                    p.place = MENTIONS_PLACE;
                }
            }
            if (open[2] > 0) {
                placeGaps.add("place: " + open[2] + " providers show an address in " + plan.place + " on their site that is not shown to be their"
                        + " own (a company of a group, an address without a name, another company than the one their pages name for the service),"
                        + " their relation to it is open");
            }
            if (!decidable) {
                // which pages name the place is unknown: no provider without evidence is told to have no relation to it
                for (final Provider p : providers.values()) {
                    if (p.placeRank() < 2 && p.host != null) {
                        p.place = UNDECIDED_PLACE;
                        p.placeEvidence = null;
                    }
                }
            }
        }
        phases.put("place", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        if (timedOut && gaps.stream().noneMatch(g -> g.startsWith("time budget"))) gaps.add("time budget: the search stopped early");
        // 4. the order: the place first (an address, a service area, open, a mention), then graph and text, the pages, the name
        final List<Provider> all = new ArrayList<>();
        for (final Provider p : providers.values()) if (p.byGraph || p.byText) all.add(p);
        all.sort((a, b) -> a.placeOrder() != b.placeOrder() ? b.placeOrder() - a.placeOrder()
                : (a.byGraph && a.byText) != (b.byGraph && b.byText) ? (a.byGraph && a.byText ? -1 : 1)
                : a.pageCount != b.pageCount ? b.pageCount - a.pageCount : a.key.compareTo(b.key));
        final Map<String, Integer> perPlace = new LinkedHashMap<>();
        if (plan.place != null) {
            for (final String k : new String[] {AT_PLACE, SERVES_PLACE, UNDECIDED_PLACE, MENTIONS_PLACE, NO_PLACE}) perPlace.put(k, 0);
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
        result.placeGaps = Collections.unmodifiableList(placeGaps);
        result.placeUnread = open[0];
        result.placePartly = open[1];
        result.placeUnattributed = open[2];
        result.placeOutOfTime = placeOutOfTime;
        final List<String> keys = new ArrayList<>();
        for (final Provider p : all) keys.add(p.key);
        result.keys = Collections.unmodifiableList(keys);
        result.everyone = Collections.unmodifiableList(all);
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
            // where the place can be read: the page of the graph's statement (an address, a service area)
            final Set<Long> statements = new LinkedHashSet<>();
            for (final Provider p : shown) if (p.placeStatement != 0L && p.placeSource == null) statements.add(p.placeStatement);
            if (!statements.isEmpty()) {
                final Map<Long, String> pages = context.reader().statementPages(viewer, statements);
                for (final Provider p : shown) if (p.placeStatement != 0L && p.placeSource == null) p.placeSource = pages.get(p.placeStatement);
            }
            for (final Provider p : shown) {
                if (p.entity != null || p.row != 0L || p.host == null || p.unbound) continue;
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
        if (p.servicePages.size() < SERVICE_PAGES) p.servicePages.add(page);
        if (plan.place != null && p.placeRank() < 3) textPlace(p, page, plan.place, plan.services);
    }

    /**
     * The organisation each of these hosts belongs to, for the providers that are hosts of the index: the declared operator
     * of the host's registrable domain ({@link KgReader#siteOperators}). A host without one declared operator (a directory,
     * a group's site, a site the graph has not read the operator of) has no identity in the graph: nothing on its pages is
     * the provider's own by the graph, and a page must show whose an address is ({@link ProviderPlace}).
     */
    private static void identify(final ToolContext context, final Viewer viewer, final Map<String, Provider> providers, final Map<String, String> byHost,
            final Map<Long, Provider> byRow, final Set<String> hosts) throws Exception {
        if (hosts.isEmpty()) return;
        final Map<Long, Provider> bound = new LinkedHashMap<>();
        for (final Map.Entry<String, Long> e : context.reader().siteOperators(viewer, hosts).entrySet()) {
            final String key = byHost.get(e.getKey());
            final Provider p = key == null ? null : providers.get(key);
            if (p == null || p.row != 0L || byRow.containsKey(e.getValue())) continue;
            p.row = e.getValue();
            byRow.put(p.row, p);
            bound.put(p.row, p);
        }
        if (bound.isEmpty()) return;
        // their names: of those with a current page of their name, else the first name the viewer sees
        for (final KgReader.EntityRef ref : context.reader().entityRefs(viewer, bound.keySet())) {
            final Provider p = bound.remove(ref.rowid);
            if (p == null) continue;
            p.entity = ref.id;
            p.name = ref.name;
        }
        if (!bound.isEmpty()) {
            final Map<Long, String> names = context.reader().entityNames(viewer, bound.keySet());
            for (final Map.Entry<Long, Provider> e : bound.entrySet()) if (names.get(e.getKey()) != null) e.getValue().name = names.get(e.getKey());
        }
    }

    /**
     * The graph's address (location) or service area of a provider in the place: a statement of the provider itself, or
     * ({@code through}) of a facility it operates.
     */
    private static void graphPlace(final Provider p, final KgReader.FactRow f, final String place, final String through) {
        if (f.value == null) return;
        final String value = RagQuery.fold(f.value);
        if (!placePattern(place).matcher(value).find()) return;
        final String of = through == null ? "" : " of " + through;
        if (Vocabulary.ADDRESS.equals(f.predicate) || Vocabulary.LOCALITY.equals(f.predicate) || Vocabulary.POSTAL_CODE.equals(f.predicate)) {
            if (p.placeRank() == 3 && p.placeEvidence != null && p.placeEvidence.startsWith("address")) return; // the address first
            p.place = AT_PLACE;
            p.placeEvidence = (Vocabulary.ADDRESS.equals(f.predicate) ? "address" : "place") + of + " in the graph: " + RagContext.clean(f.value, 120);
            p.placeStatement = f.statement;
            p.placeSource = null;
            placeOf(p, ProviderPlace.core(p.name), p.name, false);
        } else if (Vocabulary.SERVICE_AREA.equals(f.predicate) && p.placeRank() < 2) {
            p.place = SERVES_PLACE;
            p.placeEvidence = "service area" + of + " in the graph: " + RagContext.clean(f.value, 120);
            p.placeStatement = f.statement;
            p.placeSource = null;
            placeOf(p, ProviderPlace.core(p.name), p.name, false);
        }
    }

    /**
     * What a page of the provider's site says of the place for the provider ({@link ProviderPlace}): its own address, its
     * service area, an address not shown to be its own (open), an address of something else, or only a mention.
     */
    private static void textPlace(final Provider p, final RagCandidate page, final String place, final List<String> services) {
        final Set<String> name = p.row != 0L || p.entity != null ? ProviderPlace.core(p.name) : Collections.emptySet();
        final ProviderPlace.Finding found = ProviderPlace.read(page, place, services, name, p.host);
        // whose the place is: the company the page names for it, else the organisation the graph knows the host to belong to
        final boolean known = !found.company.isEmpty() || name.isEmpty();
        if (found.kind == ProviderPlace.OWN) {
            p.place = AT_PLACE;
            p.placeEvidence = found.evidence;
            p.placeSource = found.url;
            p.placeStatement = 0L;
            placeOf(p, known ? found.company : name, known ? found.companyName : p.name, false);
        } else if (found.kind == ProviderPlace.AREA) {
            if (p.placeRank() < 2) {
                p.place = SERVES_PLACE;
                p.placeEvidence = found.evidence;
                p.placeSource = found.url;
                p.placeStatement = 0L;
                placeOf(p, known ? found.company : name, known ? found.companyName : p.name, found.withService);
            }
        } else if (found.kind >= ProviderPlace.FOREIGN) {
            if (found.kind > p.otherPlace) {
                p.otherPlace = found.kind;
                p.placeNote = found.evidence;
                p.placeSource = p.placeRank() >= 2 ? p.placeSource : found.url;
                p.placeStatement = p.placeRank() >= 2 ? p.placeStatement : 0L;
            }
        } else if (found.kind == ProviderPlace.MENTION && p.placeRank() == 0 && NO_PLACE.equals(p.place)) {
            p.place = MENTIONS_PLACE;
            p.placeEvidence = null;
        }
    }

    private static void placeOf(final Provider p, final Set<String> company, final String name, final boolean withService) {
        p.placeCompany = company;
        p.placeCompanyName = name;
        p.placeWithService = withService;
        p.compared = false;
    }

    /** Whether a provider's place is evidenced for a named company while its service is known from pages of its host only. */
    private static boolean toCompare(final Provider p) {
        return p.placeRank() >= 2 && p.byText && !p.byGraph && !p.compared && !p.placeWithService && !p.placeCompany.isEmpty();
    }

    /**
     * Service and place of the same company. A host can carry several companies: a provider known by pages of its host that
     * name the service, whose address or service area is evidenced for a named company (the graph's operator of the host,
     * the company at the address, the company of the page stating the area), is local only when a page naming the service
     * is that company's or names no company at all (it then speaks for its site). When the pages naming the service are
     * another company's ({@link ProviderPlace#speaker}), the provider is the company of the service: the place is the
     * other one's, named on its site (a company of a similar name: open). Not all such pages read: open.
     *
     * @param pages the pages naming the service that were read for the provider
     * @param complete whether these are all its pages naming the service
     */
    private static void sameCompany(final Provider p, final List<RagCandidate> pages, final boolean complete, final Plan plan,
            final Map<Long, Provider> byRow) {
        p.compared = true;
        RagCandidate own = null;
        String other = null;
        boolean similar = false;
        for (final RagCandidate page : pages) {
            final String speaker = ProviderPlace.speaker(page, plan.services);
            final int same = speaker == null ? 2 : ProviderPlace.same(ProviderPlace.core(speaker), p.placeCompany, plan.services);
            if (same == 2) {
                // the company of the place itself first, then a page that names none
                if (own == null || (speaker != null && ProviderPlace.speaker(own, plan.services) == null)) own = page;
            } else {
                if (other == null) other = speaker;
                similar |= same == 1;
            }
        }
        if (own != null) {
            // the page shown for the service is one of the company of the place
            if (!own.url.equals(p.url())) {
                p.pages.remove(own.url);
                p.pages.add(0, own.url);
                p.read = true;
                if (p.entity == null && !own.title.isEmpty()) p.name = own.title;
                if (p.collection == null && !own.collections.isEmpty()) p.collection = own.collections.get(0);
                p.text.setLength(0);
                p.text.append(own.title).append(". ").append(own.text);
            }
            return;
        }
        if (other == null) return; // no page naming the service was read: nothing shows another company
        final String whose = (SERVES_PLACE.equals(p.place) ? "the service area naming " : "the address in ") + plan.place + " is that of "
                + RagContext.clean(p.placeCompanyName == null ? "another company" : p.placeCompanyName, 80);
        final int rest = Math.max(0, p.pageCount - pages.size());
        p.place = NO_PLACE;
        p.placeEvidence = null;
        if (!complete) {
            p.otherPlace = ProviderPlace.UNSURE;
            p.placeNote = "the pages read that name the service are of " + RagContext.clean(other, 80) + " (" + rest + " more not read); " + whose
                    + ", not shown to be the same company";
            return;
        }
        p.otherPlace = similar ? ProviderPlace.UNSURE : ProviderPlace.FOREIGN;
        p.placeNote = "its pages name the service for " + RagContext.clean(other, 80) + "; " + whose
                + (similar ? ", a company of a similar name on the same site, not shown to be the same company" : ", another company on the same site");
        // the provider is the company of the service, not the organisation the graph knows as the site's operator
        if (p.row != 0L) byRow.remove(p.row);
        p.row = 0L;
        p.entity = null;
        p.unbound = true;
        p.name = p.host;
    }

    private static Pattern placePattern(final String place) {
        return ProviderPlace.placePattern(place);
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
                final String place = result.plan.place;
                // the page above names the service; the place has its own source when another page evidences it
                final String where = p.placeSource == null || p.placeSource.equals(p.url()) ? ""
                        : " Source of the place: " + p.placeSource + " (another page than the one naming the service).";
                switch (p.place) {
                    case AT_PLACE:
                        body.append(" Located in ").append(place).append(": ").append(p.placeEvidence).append('.').append(where);
                        placeBy.put(id);
                        break;
                    case SERVES_PLACE:
                        body.append(" Serves ").append(place).append(": ").append(p.placeEvidence).append('.').append(where);
                        placeBy.put(id);
                        break;
                    case MENTIONS_PLACE:
                        body.append(p.placeNote == null ? " Its pages only mention " + place + ": neither its location nor a service area."
                                : " Not located in " + place + " by evidence: " + p.placeNote + "." + where);
                        break;
                    case UNDECIDED_PLACE:
                        body.append(p.placeNote == null ? " Its pages name " + place + ", but not all of them were read: whether it is located in"
                                + " or serves " + place + " is not determined."
                                : " Its relation to " + place + " is open: " + p.placeNote + "." + where);
                        break;
                    default:
                        body.append(" No location in or service area of ").append(place).append(" is recorded.");
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
            if (result.plan.place != null) {
                meta.put("placeComplete", !result.placeOpen()); // the relation to the place determined for every provider
                meta.put("placeGaps", new JSONArray(result.placeGaps));
            }
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
