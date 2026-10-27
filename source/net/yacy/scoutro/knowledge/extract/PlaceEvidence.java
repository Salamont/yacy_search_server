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

import net.yacy.scoutro.knowledge.resolve.Normalizers;
import net.yacy.scoutro.knowledge.vocab.TermMatcher;

/**
 * 0.13.0: the source evidence of a place of business or care that the LLM tier proposes as a facility or a site
 * (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 40), shared by the validation of an answer, the publication of a cached answer and the replay
 * of stored answers, as {@link ServiceEvidence} is for services. A proposed facility stays one only if
 * <ul>
 * <li>its name is a place's name: never a word of a website's sections ({@link #PAGE_LABELS}: Kontakt, Über uns, Jobs, Referenzen,
 * …), never a generic noun alone ({@link #GENERIC}: Gebäude, Haus, Werk, Standort, …) and never the name of an activity ("Hochbau",
 * "Baustatische Prüfung", {@link #ACTIVITY});</li>
 * <li>it is read outside a run of short labels (a navigation) for its proof; a name the model quoted from the menu is searched on
 * the page, where its own page or a sentence may state it;</li>
 * <li>the context states a place: a place word bound to the name ("Pflegeheim St. Anna", "Seniorenzentrum Haus Linde", "Werk
 * Kassel"), an address in the name's segment ("…, Hauptstraße 5, 34117 Kassel") or in the lines that follow the name (a street
 * with its number, a postal code), or a place verb in its clause ("unsere Niederlassung in Leipzig", "wir betreiben … in",
 * "befindet sich in").</li>
 * </ul>
 * The text is read without the characters of format a word may hold (a soft hyphen), as the model's quote was checked.
 * A proper name read outside a navigation without such a sign is {@link Verdict#UNCLEAR}: kept as published and marked; so is a
 * name only a navigation lists (a site's menu lists its places too), while anything else there is refused. A quote that gives
 * the place's full address (a street with its number and a postal code) states a place by itself. On a project or reference
 * page a named building is the object of a project, not a place of the page's operator, unless the operator states that it
 * runs it, the name is one of its establishments or begins with the operator's name ("Muster Hamburg" on muster.de); in a field
 * whose providers run the places they name (the vocabulary's {@code operates_places}: care), a project page is no sign of a
 * project at all. A name of a service of the page needs the place's sign too; nothing is converted or merged.
 * This is knowledge of the language and of websites; what a field's providers do is its vocabulary's.
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
    /** A token of a name for its occurrences: letters or digits ("Musterweg", "5", "12345"). */
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final int MAX_TOKENS = 16;
    /**
     * The end of a sentence: a name that ends so, has four words or more and two words of four letters or more in lower case (a
     * verb, an adjective: "Die Gebäude sind vollständig … errichtet.") is a sentence of the page; "J. N. Muster & Söhne.
     * Niederlassung Kassel." and "Geschäftsstelle des Muster e.V." are names.
     */
    private static final Pattern SENTENCE = Pattern.compile("[.!?]$");
    private static final Pattern LOWER_WORD = Pattern.compile("(?<![\\p{L}\\p{N}])\\p{Ll}[\\p{L}-]{3,}");
    /** A postal code with its place: an address of an imprint line ("Muster GmbH • Musterkamp 33 • 22607 Hamburg"). */
    private static final Pattern POSTAL = Pattern.compile("(?<!\\d)\\d{5}\\s+\\p{Lu}\\p{Ll}");
    /**
     * A page of a site's places by its address ({@code /standorte/}, {@code /einrichtungen/}, {@code /locations/}): a proper name in
     * its lists is one of them.
     */
    private static final Pattern PLACES_PAGE = Pattern.compile("(?iu)(?:^|/)(?:unsere-)?(?:standorte?\\d*|locations?|filialen?|niederlassungen"
            + "|einrichtungen|h(?:ae|ä)user|offices|branches)(?:[./_\\-]|$)");
    /** The words of a website's sections, folded: a facility of such a name is a page label. */
    static final Set<String> PAGE_LABELS = folded("kontakt", "kontaktformular", "uber uns", "ueber uns", "wir uber uns", "team", "jobs", "job",
            "karriere", "stellenangebote", "referenzen", "referenz", "projekte", "projekt", "aktuelles", "news", "neuigkeiten", "blog",
            "presse", "impressum", "datenschutz", "datenschutzerklarung", "home", "startseite", "leistungen", "unsere leistungen",
            "service", "services", "downloads", "download", "anfahrt", "galerie", "unternehmen", "philosophie", "geschichte", "historie",
            "partner", "faq", "sitemap", "login", "suche", "agb", "contact", "contact us", "about", "about us", "career", "careers",
            "references", "projects", "imprint", "legal notice", "privacy", "privacy policy", "company", "history", "gallery", "search");
    /**
     * The names of a website's sections that never name an offer either ("Kontakt", "Über uns", "Referenzen", "Jobs"), folded: a
     * service of such a name is a page label too. Labels that may name an offer ("News", "Downloads", "Service", "Partner") are
     * not among them: a service of such a name keeps the proof of its offer (0.12.0).
     */
    static final Set<String> SECTION_LABELS = folded("kontakt", "kontaktformular", "uber uns", "ueber uns", "wir uber uns", "impressum",
            "datenschutz", "datenschutzerklarung", "referenzen", "referenz", "jobs", "karriere", "stellenangebote", "startseite", "home",
            "sitemap", "login", "agb", "anfahrt", "contact", "contact us", "about", "about us", "imprint", "legal notice", "privacy",
            "privacy policy", "references", "career", "careers");
    /** A generic noun that alone names no place, folded. */
    static final Set<String> GENERIC = folded("gebaude", "haus", "werk", "standort", "standorte", "niederlassung", "filiale", "einrichtung",
            "einrichtungen", "zentrale", "hauptsitz", "firmensitz", "buro", "halle", "gelande", "objekt", "anlage", "building", "buildings", "site",
            "plant", "office",
            "location", "branch", "facility", "premises");
    /** The ending of a name of an activity or a field of work (German and English): "Hochbau", "Baustatische Prüfung", "Gebäudetechnik". */
    static final Pattern ACTIVITY = Pattern.compile("(?iu)(?:bau|ung|ungen|technik|service|services|wesen|management|arbeiten|leistung"
            + "|leistungen|statik|logistik|consulting|engineering|planning|construction|development|design|maintenance)$");
    /**
     * Words of a place of business or care, folded; a word of the name ending with one is a place word ("Seniorenzentrum",
     * "Pflegeheim", "Tagespflege", "Niederlassung").
     */
    static final List<String> PLACE_WORDS = List.of("heim", "pflegeheim", "zentrum", "residenz", "stift", "tagespflege", "nachtpflege",
            "kurzzeitpflege", "wohngemeinschaft", "wohnpark", "wohnanlage", "hospiz", "klinik", "kliniken", "klinikum", "krankenhaus", "praxis",
            "kita", "kindergarten", "krippe", "schule", "akademie", "campus", "werk", "werkstatt", "werkstätten", "stätte", "niederlassung", "zweigstelle",
            "filiale", "standort", "zentrale", "hauptsitz", "firmensitz", "unternehmenssitz", "zentren", "heime", "wohngemeinschaften",
            "büro", "geschäftsstelle", "lager", "halle", "hotel", "restaurant", "café", "laden", "showroom", "ausstellung", "studio",
            "haus", "hof", "station", "sozialstation", "home", "clinic", "hospital", "practice", "office", "branch", "plant", "factory",
            "warehouse", "center", "centre", "store", "shop", "school",
            // 0.13.0 (gate A4, run 6): care's places named by their kind
            "wohngruppe", "wohngruppen", "wg", "wgs", "spital", "centrum", "apotheke", "beratungsstelle", "beratungsstellen",
            "pflegedienst", "treff",
            // 0.13.0 (gate A4, run 8): the day group of youth welfare
            "tagesgruppe", "tagesgruppen");
    /** A street with its number, or a postal code with a place (an address). */
    private static final Pattern ADDRESS = Pattern.compile("(?iu)(?:stra(?:ß|ss)e|str\\.|weg|gasse|platz|allee|ring|damm|ufer|chaussee|markt)\\s*\\d"
            + "|(?<!\\d)\\d{5}\\s+\\p{Lu}\\p{Ll}");
    /** A street with its number (with {@link #POSTAL}: a full address). */
    private static final Pattern STREET_NUMBER = Pattern.compile("(?iu)(?:stra(?:ß|ss)e|str\\.|weg|gasse|platz|allee|ring|damm|ufer|chaussee|markt)\\s*\\d");
    /** A number in a name ("Haus 3", "Halle 5"): no generic noun alone, though no proof of a place either ("Halle 7" of a fair). */
    /** A word written as a name ("Paul", "Gerhardt"; not "IT"). */
    private static final Pattern TITLE = Pattern.compile("\\p{Lu}\\p{Ll}[\\p{L}’']*");
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])\\d+[a-z]?(?![\\p{L}\\p{N}])");
    /** A word written as a name with its number ("Mustersonne 3", "Beispielgleis 51"): a name, also where only a menu lists it. */
    private static final Pattern NUMBERED = Pattern.compile("\\p{Lu}\\p{Ll}[\\p{L}’'-]*\\s+\\d+[a-z]?");
    /**
     * Characters of control and format a page's text may hold inside its words (a soft hyphen, a zero-width space): the model's
     * quote is validated against the text without them ({@code Normalizers.text}), so the place is searched without them too.
     */
    private static final Pattern FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}&&[^\\n\\r\\t]]");
    /**
     * An establishment of the page's operator ("Niederlassung Rostock", "Hauptsitz Schöneberg", "Prüfsitz in Kassel mit
     * Zweitniederlassung in Göttingen"): a project is never one.
     */
    private static final Pattern ESTABLISHMENT = Pattern.compile("(?iu)(?<![\\p{L}])(?:(?:zweig|zweit|haupt)?niederlassung|zweigstelle|filiale"
            + "|geschäftsstelle|geschaeftsstelle|(?:haupt|firmen|unternehmens)sitz)(?![\\p{L}])");
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

    /** True if the name is the name of a website's section that never names an offer ({@link #SECTION_LABELS}): no service. */
    public static boolean sectionLabel(final String name) {
        return name != null && SECTION_LABELS.contains(fold(name));
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
        return check(raw, name, quote, url, type, serviceOfPage, false);
    }

    /**
     * {@link #check(String, String, String, String, String, boolean)} on a page whose field's providers may run the places they
     * name ({@link ExtractContext#operatesPlaces}): its project pages are no sign of the object of a project, as its own homes
     * stand there; the name is decided as on any other page.
     */
    public static Result check(final String raw, final String name, final String quote, final String url, final String type,
            final boolean serviceOfPage, final boolean operatesPlaces) {
        final String key = fold(name);
        if (key.isEmpty() || PAGE_LABELS.contains(key)) {
            return new Result(Verdict.REFUSED, PAGE_LABEL, null);
        }
        // a generic noun alone, not with its number ("Haus 3", "Halle 5": the fold drops the number)
        if (!NUMBER.matcher(name).find() && GENERIC.contains(key.replaceFirst("^(?:das|die|der|unser|unsere|the|our)\\s+", ""))) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null);
        }
        final String given = raw == null || raw.isEmpty() ? quote : raw;
        // 0.13.0 (gate A4, run 9): without the characters of format a word may hold ("Muster\u00adstadt"), as the quote was checked
        final String text = given == null ? null : FORMAT.matcher(given).replaceAll("");
        // the name as the page writes it (its words with white space between, or the name's own marks: "Musterweg 5, 12345 Ort"
        // is "Musterweg 5\n12345 Ort" there), else the model's quote, which is grounded in the page, if the name stands in it so
        // (0.14.0: two blocks of the page never become one name)
        List<int[]> found = occurrences(text, name);
        if (found.isEmpty() && quote != null && !quote.isBlank() && !occurrences(FORMAT.matcher(quote).replaceAll(""), name).isEmpty()) {
            found = occurrences(text, quote);
        }
        // the quote's own address, outside a menu line (an imprint's line with bullets is no menu: it holds a postal code)
        final boolean quoteLine = quote != null && quote.indexOf('|') < 0 && (quote.indexOf('•') < 0 || POSTAL.matcher(quote).find());
        if (quoteLine && !found.isEmpty() && STREET_NUMBER.matcher(quote).find() && POSTAL.matcher(quote).find()) {
            // 0.13.0 (gate A4, run 6): a quote with the place's full address (street and number, postal code) states a place, also
            // of an activity's name ("Suchtberatung. Musterweg 28. 12345 Musterstadt.") and on a project page, where the operator's own
            // places are given with their address and a reference names a building with its town
            return new Result(Verdict.PLACE, null, quote.trim());
        }
        final boolean placeWord = placeWord(name);
        // 0.13.0 (gate A4, runs 8 and 9): a street with its number after the name, outside a menu, in its own segment or as the next
        // line of an address ("Muster-Erziehungsberatung\nBeispielweg 15\n12345 Musterstadt"), states a place, also of an activity's
        // name
        boolean addressed = false;
        for (final int[] at : found) {
            if (!(navigation(text, at[0], at[1]) && labelNear(text, at[0], at[1]))) {
                addressed |= addressAfter(text, at, segment(text, at[0], at[1])) != null;
            }
        }
        if (!placeWord && !addressed && !ADDRESS.matcher(name).find() && ACTIVITY.matcher(key.replace(" ", "")).find()) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null);
        }
        if (!addressed && SENTENCE.matcher(name.trim()).find() && words(name) >= 4 && lowerWords(name) >= 2 && !ADDRESS.matcher(name).find()
                && !(quoteLine && ADDRESS.matcher(quote).find())) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null); // a sentence of the page, not a name ("Die Gebäude sind … errichtet.")
        }
        String proof = null, address = null;
        boolean running = false;
        boolean listed = false;
        // an establishment of the operator ("Niederlassung Rostock"), or the operator's name with a place word or a proper name
        // ("Muster Smart Factory", "Muster Hamburg" on muster.de; 0.13.0, gate A4, run 9: the branches a reference names): the
        // operator's own, never the object of a project
        boolean own = ESTABLISHMENT.matcher(name).find() || (placeWord || properName(name)) && operatorsName(name, url);
        for (final int[] at : found) {
            final boolean shortLabels = navigation(text, at[0], at[1]);
            if (shortLabels && labelNear(text, at[0], at[1])) {
                continue; // a site's menu: short labels beside one of its sections ("Leistungen | Sonnenhof | Kontakt")
            }
            // running text, or a list of short entries without a section's label (a site's places, an imprint's line)
            running = true;
            listed |= shortLabels;
            final int[] seg = segment(text, at[0], at[1]);
            final String passage = text.substring(seg[0], seg[1]);
            own |= OWN.matcher(passage).find();
            final String block = addressAfter(text, at, seg);
            final boolean located = block != null || ADDRESS.matcher(passage).find();
            if (located && address == null) {
                address = block != null ? block : passage.trim();
            }
            if (proof == null && (placeWord && properName(name) || located || PLACE_VERB.matcher(passage).find())) {
                proof = passage.trim();
            }
        }
        // the quote's own sign of a place, outside a menu line ("Haus Linde. Lindenweg 3. 34117 Kassel."; "Haus Linde | Kontakt |
        // Lindenweg 3" is a menu with the footer's address)
        if (quoteLine && !found.isEmpty()) {
            own |= OWN.matcher(quote).find();
            final boolean located = ADDRESS.matcher(quote).find();
            if (located && address == null) {
                address = quote.trim();
            }
            if (proof == null && (located || PLACE_VERB.matcher(quote).find())) {
                proof = quote.trim();
            }
        }
        // a name that is an address is a place by itself ("Musterstraße 5, 34117 Kassel"); so is a place word with a proper name
        // ("Pflegeheim St. Anna"), in a list of a site's places too
        final boolean addressName = ADDRESS.matcher(name).find();
        if (addressName && address == null) {
            address = name.trim();
        }
        // 0.13.0 (gate A4, runs 8 and 9): a provider that runs the places it names lists its own homes on its project pages
        // ("Projekte": "Pflegeheim Musterstadt „Am See“ seit 01.01.2025 als neuer Standort"), so they are no sign of a project there
        if (url != null && PROJECT_PAGE.matcher(path(url)).find() && !own && !operatesPlaces) {
            // the building of a project reference is the object of a project, not a place of the page's operator, unless a passage
            // names who runs it; a site with its address stays a site
            return Vocabulary.SITE.equals(type) && address != null ? new Result(Verdict.PLACE, null, address)
                    : new Result(Verdict.REFUSED, PROJECT_OBJECT, null);
        }
        if (proof == null && (addressName || placeWord && properName(name))) {
            proof = quote != null && !quote.isBlank() ? quote.trim() : name.trim();
        }
        if (proof == null && listed && url != null && PLACES_PAGE.matcher(path(url)).find()) {
            // an entry of a list on a page of the site's places ("Unsere Standorte", "Einrichtungen") is one of them
            proof = quote != null && !quote.isBlank() ? quote.trim() : name.trim();
        }
        if (proof != null) {
            return new Result(Verdict.PLACE, null, proof);
        }
        if (found.isEmpty()) {
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null); // not on the page at all
        }
        if (!running && !placeWord && !properName(name) && !NUMBERED.matcher(name.trim()).matches()) {
            // only in a navigation, and no name there ("Leistungen | Newsletter | Kontakt"); a name a menu lists is kept unclear
            // below: a site's menu lists its places too ("Unsere Häuser | Haus Lindenhof | Kontakt", 0.13.0, gate A4, run 6; "Mustersonne
            // 3", run 9)
            return new Result(Verdict.REFUSED, NOT_A_PLACE, null);
        }
        if (serviceOfPage) {
            return new Result(Verdict.REFUSED, SERVICE_NAME, null); // the name of a service of the page needs a sign of a place
        }
        return new Result(Verdict.UNCLEAR, null, null);
    }

    /**
     * The address of the name at {@code at} (in its segment {@code seg}) if the name heads it: a street with its number right after
     * the name in its segment, or the name alone in its segment and a short next segment with a street and its number and a postal
     * code there or in the segment after ("Muster Beratung\nBeispielweg 15\n12345 Musterstadt"); null if there is none. A name
     * inside a longer one heads nothing ("Hochbau" of "Muster Hochbau GmbH\nMusterstraße 5\n34117 Kassel").
     */
    static String addressAfter(final String text, final int[] at, final int[] seg) {
        if (words(text.substring(seg[0], at[0])) > 0) {
            return null;
        }
        final String own = text.substring(seg[0], seg[1]);
        final Matcher street = STREET_NUMBER.matcher(text.substring(at[1], seg[1]));
        if (street.find()) {
            return words(text.substring(at[1], at[1] + street.start())) <= 1 ? own.trim() : null; // the street's own word only
        }
        if (words(text.substring(at[1], seg[1])) > 0) {
            return null;
        }
        final int n = nextContent(text, seg[1]);
        if (n >= text.length()) {
            return null;
        }
        final int[] next = segment(text, n, n + 1);
        final String line = text.substring(next[0], next[1]);
        if (words(line) > 6 || !STREET_NUMBER.matcher(line).find()) {
            return null;
        }
        String block = own.trim() + ", " + line.trim();
        if (!POSTAL.matcher(line).find()) {
            final int m = nextContent(text, next[1]);
            if (m >= text.length()) {
                return null;
            }
            final int[] after = segment(text, m, m + 1);
            final String place = text.substring(after[0], after[1]);
            if (!POSTAL.matcher(place).find()) {
                return null;
            }
            block = block + ", " + place.trim();
        }
        return block;
    }

    /** True if the name begins with the name of the page's registrable domain ("Muster Smart Factory" on www.muster.de). */
    static boolean operatorsName(final String name, final String url) {
        if (url == null || name == null) {
            return false;
        }
        final String host;
        try {
            host = java.net.URI.create(url).getHost();
        } catch (final IllegalArgumentException e) {
            return false;
        }
        final String domain = host == null ? null : Normalizers.registrableDomain(host);
        if (domain == null || domain.indexOf('.') < 0) {
            return false;
        }
        final String label = fold(domain.substring(0, domain.indexOf('.')).replace('-', ' '));
        final String first = label.indexOf(' ') < 0 ? label : label.substring(0, label.indexOf(' '));
        final Matcher w = WORD.matcher(name);
        return first.length() >= 3 && w.find() && fold(w.group().split("-")[0]).equals(first);
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

    /**
     * True if the name holds more than its place word: a proper part ("Pflegeheim St. Anna", "Haus Linde", "Werk Kassel"). The first
     * word ending with a place word is the place word; a later one is a proper name (a town: "Seniorenzentrum Musterheim").
     */
    static boolean properName(final String name) {
        int words = 0, proper = 0;
        boolean placeSeen = false;
        for (final Matcher w = WORD.matcher(name); w.find();) {
            words++;
            final String[] parts = w.group().split("-");
            if (parts.length >= 3 && placeWord(parts[parts.length - 1]) && TITLE.matcher(parts[0]).matches() && TITLE.matcher(parts[1]).matches()) {
                // a person's name joined to a place word: "Paul-Gerhardt-Haus", "Fritz-Muster-Heim"
                words++;
                proper++;
                placeSeen = true;
                continue;
            }
            final String f = TermMatcher.fold(w.group().replace("-", ""));
            boolean place = false;
            for (final String p : PLACE_WORDS) {
                place |= f.endsWith(TermMatcher.fold(p));
            }
            if (place && !placeSeen) {
                placeSeen = true;
            } else if (Character.isUpperCase(w.group().codePointAt(0)) && !GENERIC.contains(f)) {
                proper++;
            }
        }
        return words >= 2 && proper >= 1;
    }

    /**
     * The occurrences of the name in the text, case-insensitive, as words: its words in order, with white space between them
     * (spaces, line breaks, blank lines) or the marks the name itself writes there, so that the model's "Musterweg 5, 12345 Ort" is
     * found where the page writes "Musterweg 5" and "12345 Ort" on two lines (0.13.0, gate A4, run 8). 0.14.0 (the owner's rule of
     * 26 October 2026): only white space joins a name's words; other text, a heading's mark or a sentence's end ends the name, so
     * "Muster parc Ort" is not found where the page writes "Muster parc." and "#### Ort." as two blocks. At most {@link
     * #MAX_TOKENS} words of it are matched.
     */
    static List<int[]> occurrences(final String text, final String name) {
        final List<int[]> out = new ArrayList<>();
        if (text == null || name == null || name.isBlank()) {
            return out;
        }
        final StringBuilder p = new StringBuilder("(?iu)(?<![\\p{L}\\p{N}])");
        int tokens = 0;
        int last = -1;
        for (final Matcher t = TOKEN.matcher(name); t.find() && tokens < MAX_TOKENS; tokens++) {
            if (last >= 0) {
                final String marks = name.substring(last, t.start()).trim();
                if (marks.isEmpty()) {
                    p.append("\\s+");
                } else {
                    // the name's own marks, with any white space around them, or white space alone
                    final StringBuilder own = new StringBuilder();
                    for (final String run : marks.split("\\s+")) {
                        own.append(own.length() == 0 ? "" : "\\s*").append(Pattern.quote(run));
                    }
                    p.append("(?:\\s*").append(own).append("\\s*|\\s+)");
                }
            }
            p.append(Pattern.quote(t.group()));
            last = t.end();
        }
        if (tokens == 0) {
            return out;
        }
        final Matcher m = Pattern.compile(p.append("(?![\\p{L}\\p{N}])").toString()).matcher(text);
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

    /** True if one of the two segments before or after the occurrence is the label of a site's section: a menu. */
    static boolean labelNear(final String text, final int start, final int end) {
        final int[] seg = segment(text, start, end);
        int at = seg[0];
        for (int i = 0; i < 2; i++) {
            final int p = previousContent(text, at);
            if (p < 0) {
                break;
            }
            final int[] prev = segment(text, p, p + 1);
            if (PAGE_LABELS.contains(fold(text.substring(prev[0], prev[1])))) {
                return true;
            }
            at = prev[0];
        }
        at = seg[1];
        for (int i = 0; i < 2; i++) {
            final int n = nextContent(text, at);
            if (n >= text.length()) {
                break;
            }
            final int[] next = segment(text, n, n + 1);
            if (PAGE_LABELS.contains(fold(text.substring(next[0], next[1])))) {
                return true;
            }
            at = next[1];
        }
        return false;
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

    private static int lowerWords(final String s) {
        int n = 0;
        for (final Matcher m = LOWER_WORD.matcher(s); m.find();) {
            n++;
        }
        return n;
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
