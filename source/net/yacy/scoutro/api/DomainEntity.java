/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entity and contact data of one host in one collection, read from pages that are already in the
 * index. Pure functions: no index access, no network, no model. Every value is copied from one
 * indexed page and the page is named in the evidence; when a value is not clearly there, it stays
 * null.
 * <p>
 * What the index holds (YaCy parser): the visible text of a page ({@code text_t}, including the
 * text of microdata elements and of mailto/tel links), the title, the URL, and {@code publisher_t}
 * from {@code <meta name="copyright">} or {@code DC.publisher}. JSON-LD scripts, the structure of
 * microdata and the targets of mailto/tel links are not stored, so they cannot be used here.
 * <p>
 * Order of the pages: Impressum, contact page, about page, start page, other page (the
 * representative page of the entry). The structured publisher value is used before text rules.
 * Text rules are deliberately narrow (German address and phone formats, general role mailboxes
 * of the site's own domain); they rather miss a value than guess one.
 */
final class DomainEntity {

    private DomainEntity() {
    }

    /** Page kinds in order of preference. */
    enum Kind { IMPRESSUM, CONTACT, ABOUT, START, OTHER }

    /** One indexed page of the entry. */
    static final class Page {
        final String url, title, publisher, text;
        final Kind kind;
        final int depth;

        Page(final String url, final String title, final Integer depth, final String publisher, final String text, final boolean representative) {
            this.url = url; this.title = title; this.publisher = publisher;
            this.text = text == null ? "" : normalize(text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
            this.depth = depth == null ? Integer.MAX_VALUE : depth;
            final Kind k = kind(url, title, depth);
            this.kind = k == Kind.OTHER && !representative ? null : k;
        }
    }

    /** Result: entity values and their evidence (never null; fields may be null). */
    static final class Result {
        final DomainCandidate.Entity entity;
        final DomainCandidate.Evidence evidence;

        Result(final DomainCandidate.Entity entity, final DomainCandidate.Evidence evidence) {
            this.entity = entity; this.evidence = evidence;
        }
    }

    /** Only the beginning of a page is read; Impressum and contact data are near the top. */
    static final int MAX_TEXT = 20_000;

    // ------------------------------------------------------------------ page kinds

    private static final Pattern IMPRESSUM_PATH = Pattern.compile("(?:^|[/_.-])(?:impressum|imprint|legal-notice|legalnotice|anbieterkennzeichnung)(?:$|[/_.-])");
    private static final Pattern CONTACT_PATH = Pattern.compile("(?:^|[/_.-])(?:kontakt|contact|kontaktformular|contact-us)(?:$|[/_.-])");
    private static final Pattern ABOUT_PATH = Pattern.compile("(?:^|/)(?:ueber-uns|uber-uns|über-uns|ueberuns|wir-ueber-uns|wir-über-uns|about|about-us|aboutus|unternehmen|firma|company)(?:$|[/_.-])");
    private static final Pattern IMPRESSUM_TITLE = Pattern.compile("(?iu)(?<!\\p{L})(?:impressum|imprint)(?!\\p{L})");
    private static final Pattern CONTACT_TITLE = Pattern.compile("(?iu)(?<!\\p{L})(?:kontakt|contact)(?!\\p{L})");
    private static final Pattern ABOUT_TITLE = Pattern.compile("(?iu)(?<!\\p{L})(?:über uns|ueber uns|about us)(?!\\p{L})");

    /** Kind of a page from its URL path, title and crawl depth (deterministic, no model). */
    static Kind kind(final String url, final String title, final Integer depth) {
        final String path = path(url);
        final String t = title == null ? "" : title;
        if (IMPRESSUM_PATH.matcher(path).find() || IMPRESSUM_TITLE.matcher(t).find()) return Kind.IMPRESSUM;
        if (CONTACT_PATH.matcher(path).find() || CONTACT_TITLE.matcher(t).find()) return Kind.CONTACT;
        if (ABOUT_PATH.matcher(path).find() || ABOUT_TITLE.matcher(t).find()) return Kind.ABOUT;
        if ((depth != null && depth == 0) || path.isEmpty() || "/".equals(path)) return Kind.START;
        return Kind.OTHER;
    }

    private static String path(final String url) {
        if (url == null) return "";
        String u = url;
        final int scheme = u.indexOf("://");
        if (scheme >= 0) u = u.substring(scheme + 3);
        final int slash = u.indexOf('/');
        u = slash < 0 ? "" : u.substring(slash);
        final int cut = indexOfAny(u, '?', '#');
        if (cut >= 0) u = u.substring(0, cut);
        return u.toLowerCase(Locale.ROOT);
    }

    private static int indexOfAny(final String s, final char a, final char b) {
        final int i = s.indexOf(a), j = s.indexOf(b);
        return i < 0 ? j : j < 0 ? i : Math.min(i, j);
    }

    /** At most one page per kind: the shallowest, then the shortest URL, then the URL. */
    static List<Page> choose(final List<Page> pages) {
        final List<Page> sorted = new ArrayList<>();
        for (final Page p : pages) if (p.kind != null && p.url != null) sorted.add(p);
        sorted.sort(Comparator.comparing((Page p) -> p.kind).thenComparingInt(p -> p.depth)
                .thenComparingInt(p -> p.url.length()).thenComparing(p -> p.url));
        final List<Page> out = new ArrayList<>();
        Kind last = null;
        for (final Page p : sorted) {
            if (p.kind == last) continue;
            out.add(p);
            last = p.kind;
        }
        return out;
    }

    // ------------------------------------------------------------------ extraction

    static Result extract(final String host, final List<Page> candidates) {
        final List<Page> pages = choose(candidates);
        final String domain = DomainCandidate.registrableDomain(host);
        final boolean germanDomain = domain.endsWith(".de");

        // address with street, postal code and city, from the first page that has one
        Address address = null;
        Page addressPage = null;
        for (final Page p : pages) {
            address = address(p.text);
            if (address != null) { addressPage = p; break; }
        }
        // name (high): a name with legal form, from the publisher metadata first, then from the page text
        String name = null, nameMethod = null;
        Page namePage = null;
        for (final Page p : pages) {
            final String v = publisher(p.publisher, host);
            if (v != null && LEGAL.matcher(v).find()) { name = v; nameMethod = "publisher_metadata"; namePage = p; break; }
        }
        if (name == null && addressPage != null) {
            name = legalName(addressPage.text, address.start);
            if (name != null) { nameMethod = "page_text_legal_form"; namePage = addressPage; }
        }
        if (name == null) for (final Page p : pages) {
            name = legalName(p.text, p.text.length());
            if (name != null) { nameMethod = "page_text_legal_form"; namePage = p; break; }
        }
        // name candidate (medium): publisher metadata without legal form, then the titles of the
        // Impressum, the start page, the contact and the about page
        String nameCandidate = name;
        String nameConfidence = name == null ? null : "high";
        if (name == null) {
            for (final Page p : pages) {
                final String v = publisher(p.publisher, host);
                if (v != null && plausibleName(v, MAX_PUBLISHER_CANDIDATE, 8)) { nameCandidate = v; nameMethod = "publisher_metadata"; namePage = p; break; }
            }
            if (nameCandidate == null) for (final Kind kind : new Kind[] {Kind.IMPRESSUM, Kind.START, Kind.CONTACT, Kind.ABOUT}) {
                final Page p = page(pages, kind);
                final String v = p == null ? null : titleCandidate(p.title, domain);
                if (v != null) { nameCandidate = v; nameMethod = TITLE_METHOD[kind.ordinal()]; namePage = p; break; }
            }
            if (nameCandidate != null) nameConfidence = "medium";
        }
        final String country = address == null ? null : address.germanPrefix || address.germanyNamed || germanDomain ? "DE" : null;

        // phone: labelled numbers only; Fax never, mobile numbers only without another number
        String phone = null;
        Page phonePage = null;
        for (final int rank : new int[] {0, 1}) {
            for (final Page p : pages) {
                phone = phone(p.text, rank, "DE".equals(country) || germanDomain);
                if (phone != null) { phonePage = p; break; }
            }
            if (phone != null) break;
        }
        // e-mail: general mailboxes of the site's own domain, the best kind first
        String email = null;
        Page emailPage = null;
        int bestRank = Integer.MAX_VALUE;
        for (final Page p : pages) {
            final Mail m = email(p.text, domain);
            if (m != null && m.rank < bestRank) { email = m.address; emailPage = p; bestRank = m.rank; }
        }

        final DomainCandidate.Entity entity = new DomainCandidate.Entity(name, nameCandidate, nameConfidence,
                address == null ? null : address.street, address == null ? null : address.postalCode,
                address == null ? null : address.city, null, country, phone, email);
        final String addressUrl = addressPage == null ? null : addressPage.url;
        final String nameUrl = namePage == null ? null : namePage.url;
        final String phoneUrl = phonePage == null ? null : phonePage.url, emailUrl = emailPage == null ? null : emailPage.url;
        final DomainCandidate.Evidence evidence = new DomainCandidate.Evidence(addressUrl != null ? addressUrl : nameUrl,
                phoneUrl != null ? phoneUrl : emailUrl, nameUrl, nameMethod, addressUrl, phoneUrl, emailUrl);
        return new Result(entity, evidence);
    }

    /** Text with collapsed white space; line breaks are kept as " \n " separators. */
    static String normalize(final String text) {
        return text.replace(' ', ' ').replaceAll("[ \\t\\x0B\\f]+", " ").replaceAll(" ?[\\r\\n]+ ?", " \n ").trim();
    }

    // ------------------------------------------------------------------ name

    private static final Pattern YEARS = Pattern.compile("(?:19|20)\\d\\d(?:\\s*[-–/]\\s*(?:19|20)?\\d\\d)?");
    private static final Pattern COPYRIGHT_WORDS = Pattern.compile("(?iu)©|\\(c\\)|copyright|all rights reserved|alle rechte vorbehalten|\\bby\\b");
    private static final Pattern HOSTLIKE = Pattern.compile("(?i)^(?:https?://)?(?:www\\.)?[a-z0-9-]+(?:\\.[a-z0-9-]+)*\\.[a-z]{2,}/?$");

    /** A publisher value from the page metadata when it names something other than the host. */
    static String publisher(final String raw, final String host) {
        if (raw == null) return null;
        String v = COPYRIGHT_WORDS.matcher(raw).replaceAll(" ");
        v = YEARS.matcher(v).replaceAll(" ");
        v = v.replaceAll("\\s+", " ").replaceAll("^[\\s,.;:|·–-]+|[\\s,.;:|·–-]+$", "").trim();
        if (v.length() < 2 || v.length() > 100 || v.toLowerCase(Locale.ROOT).contains("http")) return null;
        if (HOSTLIKE.matcher(v).matches() || FOREIGN_NAME.matcher(v).find()) return null;
        if (v.codePoints().filter(Character::isLetter).count() < 2) return null;
        final String h = host == null ? "" : host.toLowerCase(Locale.ROOT);
        final String lower = v.toLowerCase(Locale.ROOT);
        if (lower.equals(h) || lower.equals(DomainCandidate.registrableDomain(h))) return null;
        return v;
    }

    private static final String LEGAL_FORM = "GmbH & Co\\.? KGaA|GmbH & Co\\.? KG|GmbH \\+ Co\\.? KG|gGmbH|GmbH|mbH|KGaA|AG|SE|KG|OHG|oHG|GbR|PartG mbB|PartGmbB|PartG"
            + "|UG \\(haftungsbeschränkt\\)|UG|e\\. ?V\\.|e\\. ?K\\.|e\\. ?Kfm\\.|eG|e\\. ?G\\.|Ltd\\.|Ltd|Inc\\.|LLC";
    private static final Pattern LEGAL = Pattern.compile("(?<![^\\s(-])(?:" + LEGAL_FORM + ")(?![\\p{L}\\d])");
    /** Words that end a name to the left (labels before a name). */
    private static final Set<String> LABELS = Set.of("impressum", "imprint", "anbieter", "betreiber", "herausgeber", "inhaber", "inhaberin",
            "träger", "firma", "name", "angaben", "verantwortlich", "kontakt", "anschrift", "adresse", "unternehmen", "sitz", "von", "mit",
            "durch", "website", "webseite", "homepage", "internetseite", "diese", "dieser", "die", "der", "das");
    /** Labels after which a company is not the site's entity. */
    private static final Pattern FOREIGN_LABEL = Pattern.compile("(?iu)(?:versicherung|versichert|haftpflicht|webdesign|design|hosting|hoster|gehostet|provider"
            + "|realisierung|realisiert|umsetzung|umgesetzt|programmierung|programmiert|entwicklung|entwickelt|erstellt|gestaltet|gestaltung|betreut|betreuung"
            + "|technik|technische|foto|fotos|fotografie|bild|bilder|bildnachweis|bildquellen|quelle|quellen|partner|mitglied|registergericht|amtsgericht"
            + "|aufsicht|aufsichtsbehörde|kammer|bank|konto|kreditinstitut|agentur|konzept|layout|software|cms|shop-system|powered)"
            + "(?:\\s+(?:von|durch|by|mit|bei|über))?\\W*$");
    private static final Set<String> CONNECTORS = Set.of("&", "+", "und", "für", "die", "der", "des", "den", "dem", "von", "vom", "zu", "zum", "zur",
            "am", "im", "in", "an", "an der", "auf", "bei", "-", "–");
    private static final int MAX_NAME_WORDS = 7;

    /**
     * The last company name with a legal form before {@code before} (e.g. the address). A name is
     * one to seven words directly in front of the legal form, starting after a separator.
     */
    static String legalName(final String text, final int before) {
        final boolean anchored = before < text.length();
        final Matcher m = LEGAL.matcher(text);
        String best = null;
        while (m.find() && m.start() < before) {
            if (anchored && m.start() < before - NAME_DISTANCE) continue;
            final String candidate = nameBefore(text, m.start(), m.end());
            if (candidate != null) best = candidate;
            if (best != null && !anchored) break; // without an address the first name of the page counts
        }
        return best;
    }

    /** A name belongs to an address only when it stands at most this many characters before it. */
    private static final int NAME_DISTANCE = 300;

    private static String nameBefore(final String text, final int legalStart, final int legalEnd) {
        final List<int[]> words = new ArrayList<>(); // start, end of each word, right to left
        int end = legalStart;
        if (end > 0 && text.charAt(end - 1) == '-') end--; // "Verwaltungs-GmbH": the word before is joined
        String stop = "";
        while (words.size() < MAX_NAME_WORDS) {
            int e = end;
            while (e > 0 && text.charAt(e - 1) == ' ') e--;
            if (e == 0) break;
            final char c = text.charAt(e - 1);
            if (".:|·•;,\n\"„»(/".indexOf(c) >= 0 && !(c == '.' && abbreviationBefore(text, e - 1))) { stop = text.substring(Math.max(0, e - 40), e); break; }
            int start = e;
            while (start > 0 && text.charAt(start - 1) != ' ' && text.charAt(start - 1) != '\n') start--;
            final String word = text.substring(start, e);
            // symbols ("-", "–", "©", "*") end a name
            if (word.codePoints().noneMatch(Character::isLetterOrDigit)) { stop = text.substring(Math.max(0, start - 40), e); break; }
            if (LABELS.contains(word.toLowerCase(Locale.ROOT))) { stop = text.substring(Math.max(0, start - 40), e); break; }
            words.add(0, new int[] {start, e});
            end = start;
        }
        // "© 2016–2024 Musterbau GmbH": connectors and years in front are not part of the name
        while (!words.isEmpty() && (CONNECTORS.contains(text.substring(words.get(0)[0], words.get(0)[1]))
                || YEARS.matcher(text.substring(words.get(0)[0], words.get(0)[1])).matches())) words.remove(0);
        if (words.isEmpty()) return null;
        if (FOREIGN_LABEL.matcher(stop.replace('\n', ' ').trim().replaceAll("[:\\s]+$", "")).find()) return null;
        final String first = text.substring(words.get(0)[0], words.get(0)[1]);
        if (!Character.isUpperCase(first.codePointAt(0)) && !Character.isDigit(first.codePointAt(0))) return null;
        boolean letters = false;
        for (final int[] w : words) {
            final String word = text.substring(w[0], w[1]);
            if (!CONNECTORS.contains(word) && !word.matches("[\\p{L}\\d&+'’.()-]+")) return null;
            if (word.codePoints().filter(Character::isLetter).count() >= 2) letters = true;
            final String lw = word.toLowerCase(Locale.ROOT);
            if (lw.startsWith("amtsgericht") || lw.startsWith("registergericht") || lw.startsWith("handelsregister")) return null;
        }
        if (!letters) return null;
        final String name = text.substring(words.get(0)[0], legalEnd).replaceAll("\\s+", " ").trim();
        return name.length() > 100 ? null : name;
    }

    /** "Co." inside "GmbH & Co. KG" and similar are not separators. */
    private static boolean abbreviationBefore(final String text, final int dot) {
        int s = dot;
        while (s > 0 && Character.isLetter(text.charAt(s - 1))) s--;
        final String w = text.substring(s, dot);
        return w.equals("Co") || w.equals("St") || w.equals("Dr") || w.equals("Prof");
    }

    // ------------------------------------------------------------------ name candidate from titles

    private static final String[] TITLE_METHOD = {"imprint_title", "contact_title", "about_title", "homepage_title", null};
    static final int MAX_TITLE = 90, MAX_TITLE_PART = 60, MAX_PUBLISHER_CANDIDATE = 80;
    private static final Pattern TITLE_SEPARATOR = Pattern.compile("\\s*\\|\\s*|\\s+[-–—·•»«/]\\s+|\\s*::\\s*|:\\s+");
    private static final Pattern WELCOME = Pattern.compile("(?iu)^(?:herzlich )?(?:willkommen|welcome)(?: (?:bei der|bei|beim|im|in der|in|auf der|auf|zu|zum|zur|an der|am|to))?(?: |$)");
    /** Page and section names that are not a provider name. */
    private static final Set<String> GENERIC_TITLES = Set.of("startseite", "start", "home", "homepage", "home page", "index", "willkommen",
            "herzlich willkommen", "welcome", "impressum", "imprint", "legal notice", "anbieterkennzeichnung", "kontakt", "contact", "contact us",
            "kontaktformular", "anfahrt", "anreise", "über uns", "ueber uns", "wir über uns", "about", "about us", "team", "unser team", "das team",
            "unternehmen", "firma", "profil", "philosophie", "geschichte", "historie", "datenschutz", "datenschutzerklärung", "privacy",
            "privacy policy", "aktuelles", "news", "neuigkeiten", "blog", "leistungen", "unsere leistungen", "dienstleistungen", "angebote",
            "angebot", "service", "services", "produkte", "galerie", "bilder", "fotos", "jobs", "karriere", "stellenangebote", "termine",
            "veranstaltungen", "events", "preise", "faq", "hilfe", "sitemap", "login", "shop", "seite", "page", "untitled", "kein titel",
            "referenzen", "partner", "links", "downloads", "presse", "standort", "standorte", "öffnungszeiten", "sprechzeiten", "agb");
    /** Words of slogans and advertising, not of names. */
    private static final Set<String> SLOGAN_WORDS = Set.of("ihr", "ihre", "ihren", "ihrem", "ihrer", "wir", "unser", "unsere", "unseren",
            "unserem", "sie", "du", "dein", "deine", "jetzt", "hier", "günstig", "günstige", "beste", "bester", "besten", "top", "online", "kaufen",
            "buchen", "bestellen", "kostenlos", "gratis", "mehr", "alles", "neu", "sale", "rabatt", "schnell", "einfach", "professionell",
            "zuverlässig", "kompetent", "willkommen", "welcome", "your", "our", "best", "cheap");
    /** Lower-case words that may stand inside a name ("Haus am See", "Praxis für Physiotherapie"). */
    private static final Set<String> NAME_CONNECTORS = Set.of("am", "an", "auf", "aus", "bei", "beim", "der", "die", "das", "des", "dem", "den",
            "und", "von", "vom", "zu", "zum", "zur", "im", "für", "&", "+", "of", "and", "the", "e.v.", "e.", "v.", "mbh", "gmbh");
    private static final Pattern FOREIGN_NAME = Pattern.compile("(?iu)amtsgericht|registergericht|handelsregister|finanzamt|agentur|webdesign"
            + "|web-design|hosting|wordpress|joomla|typo3|jimdo|wix|webflow|shopify|powered|theme|template");
    private static final Pattern DOMAIN_IN_TEXT = Pattern.compile("(?i)(?:www\\.|[a-z0-9-]+\\.(?:de|com|net|org|eu|info|biz|at|ch|io|online|shop)(?![a-z]))");

    static Page page(final List<Page> pages, final Kind kind) {
        for (final Page p : pages) if (p.kind == kind) return p;
        return null;
    }

    /**
     * A provider name from a page title, or null: the title is split at its separators, page and
     * section names and domain names are dropped, and exactly one plausible part must remain (when
     * several remain, the one that alone shares the most words with the domain name).
     */
    static String titleCandidate(final String title, final String domain) {
        if (title == null) return null;
        final String t = title.replaceAll("\\s+", " ").trim();
        if (t.isEmpty() || t.length() > MAX_TITLE || ADVERTISING.matcher(t).find()) return null;
        final String[] parts = TITLE_SEPARATOR.split(t);
        if (parts.length > 4) return null;
        final List<String> plausible = new ArrayList<>();
        for (final String raw : parts) {
            String part = WELCOME.matcher(raw.trim()).replaceFirst("").trim();
            part = part.replaceAll("^[\\s,.;:|·–—-]+|[\\s,;:|·–—-]+$", "");
            if (part.isEmpty() || generic(part) || DOMAIN_IN_TEXT.matcher(part).find()) continue;
            if (plausibleName(part, MAX_TITLE_PART, 6) && (part.contains(" ") || domainOverlap(part, domain) > 0)) plausible.add(part);
        }
        if (plausible.size() == 1) return plausible.get(0);
        String best = null;
        int bestOverlap = 0;
        boolean tie = false;
        for (final String c : plausible) {
            final int o = domainOverlap(c, domain);
            if (o > bestOverlap) { best = c; bestOverlap = o; tie = false; } else if (o == bestOverlap && o > 0) tie = true;
        }
        return tie ? null : best;
    }

    /** A page or section name, also combined ("Impressum & Datenschutz", "Kontakt und Anfahrt"). */
    static boolean generic(final String part) {
        final String lower = part.toLowerCase(Locale.ROOT).trim();
        if (lower.isEmpty() || GENERIC_TITLES.contains(normalizeTitle(lower))) return true;
        for (final String piece : lower.split("\\s*(?:&|\\+|/)\\s*|\\s+(?:und|and)\\s+")) if (!GENERIC_TITLES.contains(normalizeTitle(piece))) return false;
        return true;
    }

    private static String normalizeTitle(final String s) {
        return s.replaceAll("[\\p{Punct}–—„“\"]+", " ").replaceAll("\\s+", " ").trim();
    }

    /** Advertising in a title (call to action, superlatives, decoration): the whole title is SEO text. */
    private static final Pattern ADVERTISING = Pattern.compile("(?iu)[!✓✔★☆→%]|(?<!\\p{L})(?:jetzt|günstige?|beste[nr]?|top|kostenlos|gratis|kaufen"
            + "|buchen|bestellen|anfragen|sichern|sale|rabatt|angebote?|now|cheap|best)(?!\\p{L})");

    /**
     * True for a short name: capitalized words (digits allowed), only name connectors in lower
     * case, no slogan words, no sentence or decoration characters, no court, register, agency or
     * CMS designation.
     */
    static boolean plausibleName(final String value, final int maxLength, final int maxWords) {
        if (value.length() < 3 || value.length() > maxLength) return false;
        if (FOREIGN_NAME.matcher(value).find() || HOSTLIKE.matcher(value).matches() || DOMAIN_IN_TEXT.matcher(value).find()) return false;
        if (value.codePoints().anyMatch(c -> "?!:;#@%€$*=<>[]{}\"„“".indexOf(c) >= 0 || Character.getType(c) == Character.OTHER_SYMBOL
                || Character.getType(c) == Character.MATH_SYMBOL && c != '+')) return false;
        if (value.matches(".*\\.\\s+\\p{Lu}.*") || generic(value)) return false;
        final String[] words = value.split(" ");
        if (words.length > maxWords || !Character.isUpperCase(value.codePointAt(0)) && !Character.isDigit(value.codePointAt(0))) return false;
        boolean named = false;
        for (final String w : words) {
            final String lw = w.toLowerCase(Locale.ROOT).replaceAll("^[(\"]+|[),.\"]+$", "");
            if (SLOGAN_WORDS.contains(lw)) return false;
            if (lw.isEmpty()) continue;
            if (Character.isUpperCase(w.codePointAt(0)) || Character.isDigit(w.codePointAt(0))) { if (w.codePoints().filter(Character::isLetter).count() >= 2) named = true; }
            else if (!NAME_CONNECTORS.contains(lw)) return false;
        }
        return named;
    }

    /** Words of the candidate that also occur in the domain name ("sonnenhof" in sonnenhof-pflege.de). */
    static int domainOverlap(final String candidate, final String domain) {
        final String label = fold(domain.indexOf('.') < 0 ? domain : domain.substring(0, domain.indexOf('.')));
        final Set<String> tokens = new java.util.HashSet<>(List.of(label.split("-")));
        final String joined = label.replace("-", "");
        int overlap = 0;
        for (final String w : fold(candidate).split("[^a-z0-9]+")) {
            if (w.length() < 3) continue;
            if (tokens.contains(w) || (w.length() >= 4 && joined.contains(w))) overlap++;
        }
        return overlap;
    }

    private static String fold(final String s) {
        return s.toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    // ------------------------------------------------------------------ address

    static final class Address {
        final String street, postalCode, city;
        final int start;
        final boolean germanPrefix, germanyNamed;

        Address(final String street, final String postalCode, final String city, final int start, final boolean germanPrefix, final boolean germanyNamed) {
            this.street = street; this.postalCode = postalCode; this.city = city; this.start = start;
            this.germanPrefix = germanPrefix; this.germanyNamed = germanyNamed;
        }
    }

    private static final Pattern POSTAL_CITY = Pattern.compile(
            "(?<![\\p{L}\\d-])((?:D|DE) ?- ?)?(\\d{5})(?!\\d)\\.? ((?:Bad |Sankt |St\\. )?[A-ZÄÖÜ][\\p{L}ß]+(?:-[A-ZÄÖÜ][\\p{L}ß]+)*"
            + "(?: (?:am|an der|an|im|in der|in|ob der|bei|vor der) [A-ZÄÖÜ][\\p{L}ß]+| \\([A-ZÄÖÜ][\\p{L}ß]+\\))?)");
    private static final Pattern STREET_TAIL = Pattern.compile(
            "((?:[\\p{L}ß'’.-]+ ){0,4}[\\p{L}ß'’.-]+) (\\d{1,4}(?: ?[a-zA-Z](?![\\p{L}]))?(?: ?[-–/] ?\\d{1,4}(?: ?[a-zA-Z](?![\\p{L}]))?)?)$");
    private static final Pattern STREET_SUFFIX = Pattern.compile("(?iu)(?:straße|strasse|str\\.|weg|allee|platz|gasse|ring|damm|ufer|chaussee|steig|stieg|pfad"
            + "|markt|berg|hof|park|garten|feld|graben|wall|tor|brücke|kamp|twiete|wiese|höhe|grund|aue|anger|zeile|promenade|kai|deich|siedlung|winkel"
            + "|bogen|blick|hain|leite|rain|bach|horst|heide|kreuz|eck|ecke|stadt|dorf|holz|busch|land|see|insel|tal|bruch|kuhle|moor|pforte|passage)$");
    private static final Set<String> STREET_PREPOSITIONS = Set.of("am", "an", "auf", "im", "in", "zum", "zur", "unter", "hinter", "vor", "bei", "beim", "auf der",
            "an der", "in der", "unter den", "am alten", "zu");
    /** Addresses after these words belong to someone else (court, authority, insurer, agency). */
    private static final Pattern FOREIGN_CONTEXT = Pattern.compile("(?iu)(?:amtsgericht|registergericht|aufsichtsbehörde|aufsicht|finanzamt|kammer|behörde"
            + "|datenschutzbeauftragte|datenschutzbeauftragter|beauftragte für den datenschutz|landesbeauftragte|schlichtungsstelle|verbraucherzentrale"
            + "|versicherung|haftpflicht|hosting|hoster|provider|webdesign|agentur|bank|postfach|postanschrift|rechnungsanschrift)");
    /** Segments of the text before an address: line breaks, sentence ends and list separators. */
    private static final Pattern SEGMENT = Pattern.compile("\\n|\\. |·|\\||•|;");
    private static final Pattern GERMANY = Pattern.compile("^[\\s,.|·•–-]*(?:Deutschland|Germany|DE)(?![\\p{L}])");

    /** First complete German postal address: street with number, postal code and city. */
    static Address address(final String text) {
        final Matcher m = POSTAL_CITY.matcher(text);
        while (m.find()) {
            final String postal = m.group(2);
            if (postal.startsWith("00")) continue;
            // the street ends right before the postal code, at most one line break and a separator apart
            int end = m.start();
            while (end > 0 && " ,.|·•–-".indexOf(text.charAt(end - 1)) >= 0) end--;
            if (end > 0 && text.charAt(end - 1) == '\n') { end--; while (end > 0 && text.charAt(end - 1) == ' ') end--; }
            int lineStart = end;
            while (lineStart > 0 && text.charAt(lineStart - 1) != '\n' && end - lineStart < 70) lineStart--;
            final Matcher s = STREET_TAIL.matcher(text.substring(lineStart, end));
            if (!s.find()) continue;
            final String streetWords = street(s.group(1));
            if (streetWords == null) continue;
            final int streetStart = lineStart + s.end(1) - streetWords.length();
            if (foreign(text.substring(Math.max(0, streetStart - 160), streetStart))) continue;
            final String city = city(m.group(3));
            if (city == null) continue;
            final boolean germany = GERMANY.matcher(text.substring(m.end(), Math.min(text.length(), m.end() + 20))).find();
            return new Address(streetWords + " " + s.group(2).replaceAll("\\s+", " "), postal, city, streetStart, m.group(1) != null, germany);
        }
        return null;
    }

    /**
     * True when the address belongs to someone else: a court, authority, insurer, agency ... is
     * named in the segment of the street or in one of the two segments before it, before any
     * company name with legal form (that name owns the address).
     */
    private static boolean foreign(final String before) {
        final String[] parts = SEGMENT.split(before, -1);
        for (int i = parts.length - 1, n = 0; i >= 0 && n < 3; i--, n++) {
            if (FOREIGN_CONTEXT.matcher(parts[i]).find()) return true;
            if (LEGAL.matcher(parts[i]).find()) return false;
        }
        return false;
    }

    /** The street part of the words before the house number, or null when it does not look like a street. */
    private static String street(final String wordsText) {
        final String[] words = wordsText.trim().split(" ");
        final int n = words.length;
        final String last = words[n - 1];
        if (last.isEmpty() || !Character.isUpperCase(last.codePointAt(0))) return null;
        final String lowerLast = last.toLowerCase(Locale.ROOT);
        if (lowerLast.startsWith("postfach") || lowerLast.equals("pf")) return null;
        String street = null;
        final Matcher suffix = STREET_SUFFIX.matcher(last);
        if (suffix.find()) {
            final boolean compound = last.length() > suffix.group().length() && !last.contains("-") || last.matches(".+-(?:Straße|Strasse|Str\\.|Weg|Allee|Platz|Ring|Damm)");
            if (compound) street = last;
            else if (n >= 2 && Character.isUpperCase(words[n - 2].codePointAt(0)) && words[n - 2].matches("[\\p{L}ß'’.-]+")) street = words[n - 2] + " " + last;
        }
        if (street == null) {
            // "Am Markt 3", "An der Alster 12", "Zum Hafen 1"
            if (n >= 3 && STREET_PREPOSITIONS.contains((words[n - 3] + " " + words[n - 2]).toLowerCase(Locale.ROOT)))
                street = words[n - 3] + " " + words[n - 2] + " " + last;
            else if (n >= 2 && STREET_PREPOSITIONS.contains(words[n - 2].toLowerCase(Locale.ROOT)) && Character.isUpperCase(words[n - 2].codePointAt(0)))
                street = words[n - 2] + " " + last;
        }
        if (street == null) return null;
        if (LEGAL.matcher(street).find() || street.toLowerCase(Locale.ROOT).matches(".*\\b(?:tel|telefon|fax|e-mail|hrb|hra|ust)\\b.*")) return null;
        return street;
    }

    private static final Set<String> CITY_STOP = Set.of("Tel", "Telefon", "Fax", "Telefax", "Phone", "Mobil", "E", "Email", "E-Mail", "Mail", "Deutschland",
            "Germany", "Web", "Internet", "Www", "Geschäftsführer", "Geschäftsführung", "Inhaber", "Vertreten", "Registergericht", "Amtsgericht",
            "Handelsregister", "Ust", "USt", "Steuernummer", "Kontakt", "Öffnungszeiten", "Telefonnummer");

    private static String city(final String raw) {
        final String first = raw.split("[ (]")[0];
        if (CITY_STOP.contains(first) || first.length() < 2) return null;
        // a trailing word that is a label is not part of the city
        final String[] words = raw.split(" ");
        if (words.length > 1 && CITY_STOP.contains(words[words.length - 1])) return null;
        return raw;
    }

    // ------------------------------------------------------------------ phone

    private static final Pattern PHONE = Pattern.compile(
            "(?iu)(?<![\\p{L}])(telefonnummer|telefon|tel\\.? ?nr\\.?|tel\\.?|fon|phone|zentrale|hotline|t ?:|mobiltelefon|mobil|handy|mobile|telefax|fax)"
            + "(?: ?/ ?(fax|telefax|mobil))?[ .:]{0,4}((?:\\+|00)?[ (]*\\d[\\d ()/.\\-–]{4,24}\\d)");

    /**
     * First labelled phone number. Rank 0: telephone labels; rank 1: mobile labels. Fax numbers
     * and numbers whose label also names fax are never used.
     */
    static String phone(final String text, final int rank, final boolean german) {
        final Matcher m = PHONE.matcher(text);
        while (m.find()) {
            final String label = m.group(1).toLowerCase(Locale.ROOT).replace(" ", "");
            if (label.contains("fax") || m.group(2) != null) continue;
            final boolean mobile = label.startsWith("mobil") || label.equals("handy");
            if (mobile != (rank == 1)) continue;
            final String normalized = normalizePhone(m.group(3), german);
            if (normalized != null) return normalized;
        }
        return null;
    }

    /** E.164 (+49...) when the country is known, otherwise the digits as written. */
    static String normalizePhone(final String raw, final boolean german) {
        String s = raw.replace("(0)", "").trim();
        final boolean plus = s.startsWith("+");
        s = s.replaceAll("[^\\d]", "");
        if (plus) s = "+" + s;
        else if (s.startsWith("00")) s = "+" + s.substring(2);
        else if (s.startsWith("0") && german) s = "+49" + s.substring(1);
        final int digits = s.replace("+", "").length();
        if (digits < 6 || digits > 15) return null;
        if (s.startsWith("+") && digits < 8) return null;
        return s;
    }

    // ------------------------------------------------------------------ e-mail

    static final class Mail {
        final String address;
        final int rank;

        Mail(final String address, final int rank) { this.address = address; this.rank = rank; }
    }

    private static final Pattern MAIL = Pattern.compile("(?<![\\w.+-])([A-Za-z0-9][A-Za-z0-9._%+-]{0,63})@([A-Za-z0-9.-]+\\.[A-Za-z]{2,24})(?![\\w-])");
    private static final Pattern AT = Pattern.compile("(?iu) ?[\\[({] ?(?:at|ät|@) ?[\\])}] ?");
    private static final Pattern DOT = Pattern.compile("(?iu) ?[\\[({] ?(?:dot|punkt) ?[\\])}] ?");
    /** General mailboxes: rank 0 main contact, rank 1 departments, rank 2 technical. */
    private static final Set<String> MAILBOX_MAIN = Set.of("info", "kontakt", "contact", "office", "mail", "post", "hello", "hallo", "service", "team",
            "buero", "büro", "zentrale", "empfang", "rezeption", "reception", "verwaltung", "anfrage", "anfragen", "sekretariat", "praxis", "kanzlei",
            "kundenservice", "kundendienst", "anmeldung", "termin", "termine", "beratung", "hilfe", "support");
    private static final Set<String> MAILBOX_DEPARTMENT = Set.of("verkauf", "vertrieb", "sales", "einkauf", "bestellung", "bestellungen", "auftrag",
            "booking", "reservierung", "pflege", "presse", "press", "marketing", "personal", "bewerbung", "jobs", "karriere", "buchhaltung", "rechnung");
    private static final Set<String> MAILBOX_TECHNICAL = Set.of("datenschutz", "privacy", "dsb", "webmaster", "admin", "impressum");

    /**
     * Best general mailbox of the site's own domain in the text. Addresses of other domains and
     * addresses that look personal (e.g. first.last@) are never returned.
     */
    static Mail email(final String text, final String domain) {
        String t = AT.matcher(text).replaceAll("@");
        t = DOT.matcher(t).replaceAll(".");
        final Matcher m = MAIL.matcher(t);
        Mail best = null;
        while (m.find()) {
            final String local = m.group(1).toLowerCase(Locale.ROOT);
            final String host = m.group(2).toLowerCase(Locale.ROOT).replaceAll("\\.+$", "");
            if (domain.isEmpty() || !(host.equals(domain) || host.endsWith("." + domain))) continue;
            final int rank = MAILBOX_MAIN.contains(local) ? 0 : MAILBOX_DEPARTMENT.contains(local) ? 1
                    : MAILBOX_TECHNICAL.contains(local) ? 2 : local.equals(domain.substring(0, domain.indexOf('.') < 0 ? domain.length() : domain.indexOf('.'))) ? 1 : -1;
            if (rank < 0) continue;
            if (best == null || rank < best.rank) best = new Mail(local + "@" + host, rank);
        }
        return best;
    }
}
