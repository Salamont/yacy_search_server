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

/**
 * The service search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 13). A question for a provider of a service ("Wer
 * prüft in Hürth, ob eine Wand tragend ist?") names the problem, rarely the service. When its automatic search matched
 * weakly or found no page that names the service, the server derives the service and the place from the question and
 * searches the local index again, before the model is called, whether or not the model would call a tool:
 * <ul>
 * <li>The service: from the problem words of the question (a small table: "tragend", "Statik", "Wanddurchbruch" mean
 * Tragwerksplanung, Statiker, Statik ...), else from the question's own nouns when it asks for a provider. The place:
 * the town after "in", "bei", "aus" or "um", or a postal code.</li>
 * <li>Only with the existing release of {@code search} and within the scope of the request ({@link ToolContext}): the
 * local index, the session's collection, hits of other collections dropped. At most {@link #MAX_VARIANTS} searches
 * ("Tragwerksplanung Hürth", "Statiker Hürth", ...; the service alone last), at most {@link #MAX_PAGES} pages, each of
 * which must name a service; pages that name the place first.</li>
 * <li>The pages go to the answer as the first numbered sources, as search results of the index (their original pages).
 * Job postings are a separate search ({@link JobSearch}, {@code type=job}).</li>
 * </ul>
 */
public final class ServiceSearch {

    public static final int MAX_VARIANTS = 4;
    public static final int MAX_PAGES = 4;
    public static final long MAX_MILLIS = 6000L;
    static final int PER_VARIANT = 6;

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
            final String candidate = m.group(1).replaceAll("[.,;:!?]+$", "");
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
     * no provider in words ("Tragwerksplanung Hürth"), its own nouns as the service. Null without any.
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

    /** What the search found. */
    public static final class Result {
        public final Plan plan;
        public final List<RagRetriever.Scored> pages;
        public final Map<String, Boolean> placeNamed;
        public final List<String> searched;
        public final boolean timedOut;
        public final long millis;

        Result(final Plan plan, final List<RagRetriever.Scored> pages, final Map<String, Boolean> placeNamed, final List<String> searched,
                final boolean timedOut, final long millis) {
            this.plan = plan;
            this.pages = Collections.unmodifiableList(pages);
            this.placeNamed = Collections.unmodifiableMap(placeNamed);
            this.searched = Collections.unmodifiableList(searched);
            this.timedOut = timedOut;
            this.millis = millis;
        }
    }

    public static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known) {
        return run(context, plan, known, System::currentTimeMillis);
    }

    static Result run(final ToolContext context, final Plan plan, final List<RagRetriever.Scored> known, final LongSupplier clock) {
        final long start = clock.getAsLong();
        final Map<String, RagRetriever.Scored> pages = new LinkedHashMap<>();
        final Map<String, Boolean> placeNamed = new LinkedHashMap<>();
        final List<String> searched = new ArrayList<>();
        boolean timedOut = false;
        for (final String variant : plan.variants) {
            if (pages.size() >= MAX_PAGES * 2) break;
            if (clock.getAsLong() - start > MAX_MILLIS) {
                timedOut = true;
                break;
            }
            searched.add(variant);
            final RagRetriever.Result r = context.retrieve(RagQuery.prepare(variant), PER_VARIANT);
            for (final RagRetriever.Scored s : r.selected) {
                if (pages.containsKey(s.candidate.url) || !names(s.candidate, plan.services)) continue;
                pages.put(s.candidate.url, s);
                placeNamed.put(s.candidate.url, plan.place != null && RagCandidate.literal(plan.place, haystack(s.candidate)));
            }
        }
        final List<RagRetriever.Scored> ordered = new ArrayList<>(pages.values());
        // pages that name the place first, the found order otherwise
        ordered.sort((a, b) -> Boolean.compare(placeNamed.get(b.candidate.url), placeNamed.get(a.candidate.url)));
        final List<RagRetriever.Scored> top = new ArrayList<>(ordered.subList(0, Math.min(MAX_PAGES, ordered.size())));
        final Map<String, Boolean> shown = new LinkedHashMap<>();
        for (final RagRetriever.Scored s : top) shown.put(s.candidate.url, placeNamed.get(s.candidate.url));
        return new Result(plan, top, shown, searched, timedOut, clock.getAsLong() - start);
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

    /** The metadata of the search ({@code scoutro-servicesearch}). */
    public static JSONObject meta(final Result result, final int firstId, final String scope, final boolean followUp) {
        final JSONObject meta = new JSONObject(true);
        try {
            meta.put("triggered", true);
            meta.put("followUp", followUp);
            meta.put("scope", scope);
            meta.put("services", new JSONArray(result.plan.services));
            meta.put("place", result.plan.place == null ? JSONObject.NULL : result.plan.place);
            meta.put("searched", new JSONArray(result.searched));
            meta.put("found", result.pages.size());
            final JSONArray named = new JSONArray();
            int id = firstId;
            for (final RagRetriever.Scored s : result.pages) {
                if (Boolean.TRUE.equals(result.placeNamed.get(s.candidate.url))) named.put(id);
                id++;
            }
            meta.put("placeNamedBy", named);
            meta.put("timedOut", result.timedOut);
            meta.put("millis", result.millis);
        } catch (final JSONException e) {
            // plain keys and values
        }
        return meta;
    }
}
