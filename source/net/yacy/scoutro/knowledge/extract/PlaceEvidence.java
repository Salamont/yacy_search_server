/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.vocab.TermMatcher;

/**
 * 0.13.0: the source evidence of a place of business or care that the LLM tier proposes as a facility or a site
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 40), shared by the validation of an answer, the publication of a cached answer and the replay
 * of stored answers, as {@link ServiceEvidence} is for services. A proposed facility stays one only if
 * <ul>
 * <li>its name is a place's name: never a word of a website's sections ({@link #PAGE_LABELS}: Kontakt, Über uns, Jobs, Referenzen,
 * …), never a generic noun alone ({@link #GENERIC}: Gebäude, Haus, Werk, Standort, …) and never the name of an activity ("Hochbau",
 * "Baustatische Prüfung", {@link #ACTIVITY});</li>
 * <li>it is read outside a run of short labels (a navigation); a name the model quoted from the menu is searched on the page, where
 * its own page or a sentence may state it;</li>
 * <li>the context states a place: a place word bound to the name ("Pflegeheim St. Anna", "Seniorenzentrum Haus Linde", "Werk
 * Kassel"), an address in the name's segment ("…, Hauptstraße 5, 34117 Kassel"), or a place verb in its clause ("unsere
 * Niederlassung in Leipzig", "wir betreiben … in", "befindet sich in").</li>
 * </ul>
 * A proper name read outside a navigation without such a sign is {@link Verdict#UNCLEAR}: kept as published and marked. On a
 * project or reference page a named building is the object of a project, not a place of the page's operator, unless the
 * operator states that it runs it. A name of a service of the page needs the place's sign too; nothing is converted or merged.
 * This is knowledge of the language and of websites, not of an industry.
 */
public final class PlaceEvidence {

    /** The decision of the check. */
    public enum Verdict { PLACE, UNCLEAR, REFUSED }

    /** The decision, its reason ({@code entity_page_label}, {@code entity_facility_not_a_place}, …) and the passage that proves a place. */
    public static final class Result {
        public final Verdict verdict;
        public final String reason;
        public final String proof;

        Result(final Verdict verdict, final String reason, final String proof) {
            this.verdict = verdict;
            this.reason = reason;
            this.proof = proof;
        }
    }

    public static final String PAGE_LABEL = "entity_page_label";
    public static final String NOT_A_PLACE = "entity_facility_not_a_place";
    public static final String PROJECT_OBJECT = "entity_facility_project_object";
    public static final String SERVICE_NAME = "entity_facility_service_name";

    /** A word of a name (before the word sets, which are folded with it). */
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}'’-]*");
    /** The words of a website's sections, folded: a facility of such a name is a page label. */
    static final Set<String> PAGE_LABELS = folded("kontakt", "kontaktformular", "uber uns", "ueber uns", "wir uber uns", "team", "jobs", "job",
            "karriere", "stellenangebote", "referenzen", "referenz", "projekte", "projekt", "aktuelles", "news", "neuigkeiten", "blog",
            "presse", "impressum", "datenschutz", "datenschutzerklarung", "home", "startseite", "leistungen", "unsere leistungen",
            "service", "services", "downloads", "download", "anfahrt", "galerie", "unternehmen", "philosophie", "geschichte", "historie",
            "partner", "faq", "sitemap", "login", "suche", "agb", "contact", "contact us", "about", "about us", "career", "careers",
            "references", "projects", "imprint", "legal notice", "privacy", "privacy policy", "company", "history", "gallery", "search");
    /** A generic noun that alone names no place, folded. */
    static final Set<String> GENERIC = folded("gebaude", "haus", "werk", "standort", "standorte", "niederlassung", "filiale", "einrichtung",
            "einrichtungen", "zentrale", "buro", "halle", "gelande", "objekt", "anlage", "building", "buildings", "site", "plant", "office",
            "location", "branch", "facility", "premises");
    /** The ending of a name of an activity or a field of work (German and English): "Hochbau", "Baustatische Prüfung", "Gebäudetechnik". */
    static final Pattern ACTIVITY = Pattern.compile("(?iu)(?:bau|ung|ungen|technik|service|services|wesen|management|arbeiten|leistung"
            + "|leistungen|statik|logistik|consulting|engineering|planning|construction|development|design|maintenance)$");
    /**
     * Words of a place of business or care, folded; a word of the name ending with one is a place word ("Seniorenzentrum",
     * "Pflegeheim", "Tagespflege", "Niederlassung").
     */
    static final List<String> PLACE_WORDS = List.of("heim", "pflegeheim", "zentrum", "residenz", "stift", "tagespflege", "nachtpflege",
            "kurzzeitpflege", "wohngemeinschaft", "wohnpark", "wohnanlage", "hospiz", "klinik", "klinikum", "krankenhaus", "praxis",
            "kita", "kindergarten", "schule", "akademie", "campus", "werk", "werkstatt", "niederlassung", "filiale", "standort", "zentrale",
            "büro", "geschäftsstelle", "lager", "halle", "hotel", "restaurant", "café", "laden", "showroom", "ausstellung", "studio",
            "haus", "hof", "station", "sozialstation", "home", "clinic", "hospital", "practice", "office", "branch", "plant", "factory",
            "warehouse", "center", "centre", "store", "shop", "school");
    /** A street with its number, or a postal code with a place (an address). */
    private static final Pattern ADDRESS = Pattern.compile("(?iu)(?:stra(?:ß|ss)e|str\\.|weg|gasse|platz|allee|ring|damm|ufer|chaussee|markt)\\s*\\d"
            + "|(?<!\\d)\\d{5}\\s+\\p{Lu}\\p{Ll}");
    /** A verb or phrase binding the name to a place in its clause. */
    private static final Pattern PLACE_VERB = Pattern.compile("(?iu)\\b(?:betreiben|betreibt|betrieben|befindet\\s+sich|befinden\\s+sich|liegt|gelegen"
            + "|eröffnet|eröffnen|öffnet|öffnen|located|operate[sd]?|opened|ansässig|sitz\\s+in|zuhause\\s+in|zu\\s+hause\\s+in)\\b"
            + "|\\b(?:unser(?:e[nmrs]?)?|our)\\s+[\\p{L}-]*(?:" + "werk|niederlassung|filiale|standort|haus|heim|zentrum|büro|buero|praxis|klinik"
            + "|einrichtung|tagespflege|office|branch|plant|home|site)\\b[^.!?;:|]{0,40}\\b(?:in|im|am|an\\s+der|at)\\b");
    /**
     * An operator of the place in the passage ("wir betreiben", "unser", "Die Muster Pflege gGmbH betreibt das Haus Lindenhof"): what
     * makes a building of a project page a place of business, not only the object of a project.
     */
    private static final Pattern OWN = Pattern.compile("(?iu)\\b(?:unser(?:e[nmrs]?)?|our|betreib(?:en|t|e)|betrieben|operate[sd]?|operating|we\\s+run)\\b"
            // a named organisation that plans or opens the place ("Die Muster Pflege gGmbH plant ab 2027 das Haus Am See"); "wir planen"
            // is a planner's work
            + "|(?u)\\b(?:gGmbH|GmbH|e\\.\\s?V\\.|AG|KG|eG|gAG|Stiftung|UG)(?![\\p{L}])[^.!?;:|]{0,40}?\\b(?:plant|eröffnet|eröffnen|öffnet)\\b");
    /** A project or reference page by its address: a named building there is the object of a project. */
    private static final Pattern PROJECT_PAGE = Pattern.compile("(?iu)(?:^|/)(?:projekte?|projects?|referenzen|referenz|references?|case-stud(?:y|ies)"
            + "|bauvorhaben|objekte)(?:[./_\\-]|$)");
    /** Separators of the segments of a page text (as {@link LlmExtractor#underHeading} reads them). */
    private static final Pattern SEGMENT = Pattern.compile("[.!?|•·:;\\n\\r*]+|\\s[-–—]\\s");

    private PlaceEvidence() {}

    /** True if the name is one of a website's sections ("Kontakt", "Über uns"). */
    public static boolean pageLabel(final String name) {
        return name != null && PAGE_LABELS.contains(fold(name));
    }

    /**
     * The check of a proposed facility or site.
     *
     * @param raw      the page's text as Solr holds it (or the chunk's; the quote when nothing else is known)
     * @param name     the proposed name
     * @param quote    the model's quote
     * @param url      the page's address, or null
     * @param type     {@code facility} or {@code site}
     * @param serviceOfPage true if the page has a service of the same name (a known entity of the rules)
     */
    public static Result check(final String raw, final String name, final String quote, final String url, final String type,
            final boolean serviceOfPage) {
        final String key = fold(name);
        if (key.isEmpty() || PAGE_LABELS.contains(key)) {
            return new Result(Verdict.REFUSED, PAGE_LABEL, null);
        }
        if (GENERIC.contains(key.replaceFirst("^(?:das|die|der|unser|unsere|the|our)\\s+", ""))) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null);
        }
        final boolean placeWord = placeWord(name);
        if (!placeWord && ACTIVITY.matcher(key.replace(" ", "")).find()) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null);
        }
        final String text = raw == null || raw.isEmpty() ? quote : raw;
        String proof = null, address = null;
        boolean running = false;
        boolean own = false;
        for (final int[] at : occurrences(text, name)) {
            if (navigation(text, at[0], at[1])) {
                continue;
            }
            running = true;
            final int[] seg = segment(text, at[0], at[1]);
            final String passage = text.substring(seg[0], seg[1]);
            own |= OWN.matcher(passage).find();
            final boolean located = ADDRESS.matcher(passage).find();
            if (located && address == null) {
                address = passage.trim();
            }
            if (proof == null && (placeWord && properName(name) || located || PLACE_VERB.matcher(passage).find())) {
                proof = passage.trim();
            }
        }
        if (url != null && PROJECT_PAGE.matcher(path(url)).find() && !own) {
            // the building of a project reference is the object of a project, not a place of the page's operator, unless a passage
            // names who runs it; a site with its address stays a site
            return Vocabulary.SITE.equals(type) && address != null ? new Result(Verdict.PLACE, null, address)
                    : new Result(Verdict.REFUSED, PROJECT_OBJECT, null);
        }
        if (proof != null) {
            return new Result(Verdict.PLACE, null, proof);
        }
        if (!running) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null); // only in a navigation, or not on the page at all
        }
        if (serviceOfPage) {
            return new Result(Verdict.REFUSED, SERVICE_NAME, null); // the name of a service of the page needs a sign of a place
        }
        return new Result(Verdict.UNCLEAR, null, null);
    }

    /** True if a word of the name is a place word ("Seniorenzentrum", "Pflegeheim", "Werk"). */
    static boolean placeWord(final String name) {
        for (final Matcher w = WORD.matcher(name == null ? "" : name); w.find();) {
            final String f = TermMatcher.fold(w.group().replace("-", ""));
            for (final String p : PLACE_WORDS) {
                if (f.endsWith(TermMatcher.fold(p))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True if the name holds more than its place words: a proper part ("Pflegeheim St. Anna", "Haus Linde", "Werk Kassel"). */
    static boolean properName(final String name) {
        int words = 0, proper = 0;
        for (final Matcher w = WORD.matcher(name); w.find();) {
            words++;
            final String f = TermMatcher.fold(w.group().replace("-", ""));
            boolean place = false;
            for (final String p : PLACE_WORDS) {
                place |= f.endsWith(TermMatcher.fold(p));
            }
            if (!place && Character.isUpperCase(w.group().codePointAt(0)) && !GENERIC.contains(f)) {
                proper++;
            }
        }
        return words >= 2 && proper >= 1;
    }

    /** The occurrences of the name in the text, case-insensitive, as words. */
    private static List<int[]> occurrences(final String text, final String name) {
        final List<int[]> out = new ArrayList<>();
        if (text == null || name == null || name.isBlank()) {
            return out;
        }
        final Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(name.trim()) + "(?![\\p{L}\\p{N}])").matcher(text);
        while (m.find() && out.size() < 50) {
            out.add(new int[] {m.start(), m.end()});
        }
        return out;
    }

    /**
     * True if the occurrence stands in a run of short labels: its own segment holds hardly more than the name, and the segments
     * around it are short labels too (at most three words each, at least two of the four neighbours), as a menu reads in page text.
     */
    static boolean navigation(final String text, final int start, final int end) {
        final int[] seg = segment(text, start, end);
        if (words(text.substring(seg[0], seg[1])) > words(text.substring(start, end)) + 1) {
            return false;
        }
        int labels = 0, seen = 0;
        int at = seg[0];
        for (int i = 0; i < 2; i++) {
            final int p = previousContent(text, at);
            if (p < 0) {
                break;
            }
            final int[] prev = segment(text, p, p + 1);
            seen++;
            labels += words(text.substring(prev[0], prev[1])) <= 3 ? 1 : 0;
            at = prev[0];
        }
        at = seg[1];
        for (int i = 0; i < 2; i++) {
            final int n = nextContent(text, at);
            if (n >= text.length()) {
                break;
            }
            final int[] next = segment(text, n, n + 1);
            seen++;
            labels += words(text.substring(next[0], next[1])) <= 3 ? 1 : 0;
            at = next[1];
        }
        return seen > 0 && labels >= 2;
    }

    /** The segment (between two separators) that holds {@code [start, end)}. */
    static int[] segment(final String text, final int start, final int end) {
        int a = start;
        while (a > 0 && !separator(text, a - 1)) {
            a--;
        }
        int b = end;
        while (b < text.length() && !separator(text, b)) {
            b++;
        }
        return new int[] {a, b};
    }

    private static boolean separator(final String text, final int i) {
        final char c = text.charAt(i);
        if (c == '.') {
            // "St. Anna", "z. B.": an abbreviation before the dot ends no segment
            int k = i;
            while (k > 0 && Character.isLetter(text.charAt(k - 1))) {
                k--;
            }
            return i - k > 3 || i - k == 0;
        }
        if (c == '-' || c == '–' || c == '—') {
            return i > 0 && i + 1 < text.length() && text.charAt(i - 1) == ' ' && text.charAt(i + 1) == ' ';
        }
        return c == '!' || c == '?' || c == '|' || c == '•' || c == '·' || c == ':' || c == ';' || c == '\n' || c == '\r' || c == '*';
    }

    /** The last character of content before {@code at}, past separators and white space; -1 if there is none. */
    private static int previousContent(final String text, final int at) {
        int i = at - 1;
        while (i >= 0 && (separator(text, i) || Character.isWhitespace(text.charAt(i)))) {
            i--;
        }
        return i;
    }

    private static int nextContent(final String text, final int at) {
        int i = at;
        while (i < text.length() && (separator(text, i) || Character.isWhitespace(text.charAt(i)))) {
            i++;
        }
        return i;
    }

    private static int words(final String s) {
        int n = 0;
        for (final Matcher w = WORD.matcher(s); w.find();) {
            n++;
        }
        return n;
    }

    private static String path(final String url) {
        try {
            final String p = java.net.URI.create(url).getPath();
            return p == null ? "" : p;
        } catch (final IllegalArgumentException e) {
            return "";
        }
    }

    /** The folded comparison form of a name: words folded and separated by one space. */
    /** The words as {@link #fold} compares them ("impressum" is folded as every name is). */
    private static Set<String> folded(final String... words) {
        final Set<String> out = new java.util.HashSet<>();
        for (final String w : words) {
            out.add(fold(w));
        }
        return java.util.Collections.unmodifiableSet(out);
    }

    static String fold(final String name) {
        final StringBuilder sb = new StringBuilder();
        for (final Matcher w = WORD.matcher(name == null ? "" : name.toLowerCase(Locale.ROOT)); w.find();) {
            sb.append(sb.length() == 0 ? "" : " ").append(TermMatcher.fold(w.group()).replace("ss", "s"));
        }
        return sb.toString().replace("ue", "u").replace("ae", "a").replace("oe", "o");
    }
}
