/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a page of a provider's site says about a place, for the provider itself (docs/SCOUTRO_RAG_QUALITY.md, section 13).
 * A page that carries an address in the place does not make its site's owner a provider there: the address may be that of
 * a company a directory lists, of a plot for sale, of a reference project, or of another company of a group. An address
 * counts as the provider's own only when the page shows whose it is:
 * <ul>
 * <li>the name of a company stands at the address (the text before it, up to a legal form such as "GmbH"): it is the
 * provider's when it is the provider's name, another company's when it shares nothing with it, and open when it is a
 * longer name around the provider's ("Kranich Dach Nord GmbH" for "Kranich Dach GmbH": a company of a group);</li>
 * <li>without a company's name: the provider's own name, or a word of its host, stands at the address; or the page is the
 * site's contact page or imprint with one address;</li>
 * <li>an address of a project, a reference or a plot, and an entry of a list of addresses (four or more on a page), are
 * not the provider's;</li>
 * <li>anything else is open: an address in the place that cannot be shown to be the provider's.</li>
 * </ul>
 * A service area ("Einsatzgebiet: Musterstadt und Umgebung", the service stated for the place) counts for the site's owner
 * unless the page is a list of many addresses. A mere mention of the place evidences nothing.
 */
final class ProviderPlace {

    /** the provider's own address in the place */
    static final int OWN = 4;
    /** the provider states the place as its service area */
    static final int AREA = 3;
    /** an address in the place that is not shown to be the provider's own (a company of a group, an unnamed address): open */
    static final int UNSURE = 2;
    /** an address in the place of something else: a project, a plot, another company, an entry of a list */
    static final int FOREIGN = 1;
    /** the page names the place, nothing more */
    static final int MENTION = 0;
    static final int NONE = -1;

    /** characters before an address that name whose it is */
    static final int NAME_WINDOW = 160;
    /** addresses (a postal code and a place) that make a page a list of addresses, whether of one town or of many */
    static final int LISTING = 4;

    /** What one page says about the place for the provider. */
    static final class Finding {
        final int kind;
        /** what the page says, for the answer (never null for a kind above {@link #MENTION}) */
        final String evidence;
        final String url;

        Finding(final int kind, final String evidence, final String url) {
            this.kind = kind;
            this.evidence = evidence;
            this.url = url;
        }
    }

    private static final String LEGAL = "gmbh\\s*&\\s*co\\.?\\s*kg|gmbh|mbh|kgaa|ag|kg|ug|ohg|gbr|se|e\\.\\s?k\\.?";
    private static final Pattern LEGAL_FORM = Pattern.compile("(?<![\\p{L}\\p{N}])(?:" + LEGAL + ")(?![\\p{L}\\p{N}])");
    private static final Set<String> LEGAL_WORDS = Set.of("gmbh", "mbh", "kgaa", "ag", "kg", "ug", "ohg", "gbr", "se", "e", "k", "ek", "co", "und",
            "haftungsbeschrankt");
    /** words of a host or a name that tell no company from another */
    private static final Set<String> COMMON = Set.of("www", "shop", "online", "info", "service", "services", "group", "gruppe", "holding",
            "deutschland", "germany", "team", "portal", "verzeichnis", "branchenbuch", "firma", "firmen", "unternehmen", "niederlassung",
            "standort", "standorte", "buro", "haus", "bau");
    /** a house number right before a postal code; the street is the words before it, back to a delimiter */
    private static final Pattern HOUSE_NUMBER = Pattern.compile("(?<![\\p{L}\\p{N}])\\d{1,4}\\s?[a-z]?\\s*[.,|·•–-]?\\s*$");
    private static final Pattern POSTAL_CODE = Pattern.compile("(?<!\\d)(\\d{5})\\s+\\p{L}{3,}");
    /** an address of a project, a reference or a plot */
    private static final Pattern PROJECT = Pattern.compile("(?<![\\p{L}])(?:grundstuck\\p{L}*|baugrundstuck\\p{L}*|bauplatz\\p{L}*|bauplatze|referenz\\p{L}*"
            + "|projekt\\p{L}*|bauvorhaben|objekt\\p{L}*|filiale|mehrfamilienh\\p{L}*|einfamilienh\\p{L}*|doppelh\\p{L}*|reihenh\\p{L}*|wohnanlage\\p{L}*"
            + "|neubau\\p{L}*|baustelle\\p{L}*|immoscout\\p{L}*|expose|plot|plots|project\\p{L}*|reference\\p{L}*)(?![\\p{L}])");
    private static final Pattern OWN_PAGE = Pattern.compile("(?<![\\p{L}])(?:impressum|imprint|kontakt\\p{L}*|contact\\p{L}*|anfahrt|legal notice|uber uns|about)"
            + "(?![\\p{L}])");
    private static final String DELIMITERS = ".:|#*!?;\n\r()[]•·–—»«\"";

    private ProviderPlace() {
    }

    /** The words of a company's name that tell it from another: folded, without legal forms. */
    static Set<String> core(final String name) {
        final Set<String> out = new LinkedHashSet<>();
        for (final String w : RagQuery.fold(name == null ? "" : name).split("[^\\p{L}\\p{N}]+")) {
            if (!w.isEmpty() && !LEGAL_WORDS.contains(w)) out.add(w);
        }
        return out;
    }

    /** The words of a host that may name its owner: its registrable label, without common words and the words of the services. */
    static Set<String> hostWords(final String host, final List<String> services) {
        final Set<String> out = new LinkedHashSet<>();
        if (host == null) return out;
        final String[] labels = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "").split("\\.");
        final String label = labels.length >= 2 ? labels[labels.length - 2] : labels[0];
        for (final String w : RagQuery.fold(label).split("[^\\p{L}\\p{N}]+")) {
            if (w.length() >= 4 && distinctive(w, services)) out.add(w);
        }
        return out;
    }

    private static boolean distinctive(final String word, final List<String> services) {
        if (word.length() < 3 || COMMON.contains(word)) return false;
        for (final String s : services) {
            final String stem = RagQuery.stem(RagQuery.fold(s));
            if (stem.length() >= 4 && (word.startsWith(stem) || stem.startsWith(word))) return false;
        }
        return true;
    }

    /** Whether two names share a word that tells a company from another. */
    static boolean related(final Set<String> a, final Set<String> b, final List<String> services) {
        for (final String w : a) if (w.length() >= 4 && distinctive(w, services) && b.contains(w)) return true;
        return false;
    }

    /**
     * What a page says about the place for its provider.
     *
     * @param name the words of the provider's name ({@link #core}), empty when the graph does not know who the host belongs to
     * @param host the provider's host without "www."
     */
    static Finding read(final RagCandidate page, final String place, final List<String> services, final Set<String> name, final String host) {
        final String head = RagQuery.fold(page.title + " " + page.url.replaceAll("[/._?=&-]+", " "));
        final String text = RagQuery.fold(page.title + ". " + page.description + ". " + page.text);
        final String quoted = Pattern.quote(RagQuery.fold(place));
        int addresses = 0;
        final Matcher code = POSTAL_CODE.matcher(text);
        while (addresses < LISTING && code.find()) addresses++;
        final boolean listing = addresses >= LISTING;
        final boolean single = addresses <= 1;
        final Set<String> hostWords = hostWords(host, services);
        Finding best = null;
        final Matcher address = Pattern.compile("(?<!\\d)\\d{5}\\s+" + quoted + "(?![\\p{L}])").matcher(text);
        while (address.find() && (best == null || best.kind < OWN)) {
            final int start = streetStart(text, address.start());
            final String window = text.substring(Math.max(0, start - NAME_WINDOW), start);
            // what the address is the address of: the words of its own sentence before it ("Mehrfamilienhaus – 12345 …"), and
            // what follows it at once ("12345 …. Grundstücksgröße"); text further away says nothing about this address
            int sentence = start;
            while (sentence > Math.max(0, start - 80) && ".|#*;:!?\n\r".indexOf(text.charAt(sentence - 1)) < 0) sentence--;
            final String around = text.substring(sentence, address.start()) + " "
                    + text.substring(address.end(), Math.min(text.length(), address.end() + 25));
            final String excerpt = RagContext.clean(text.substring(Math.max(0, start - 60), Math.min(text.length(), address.end() + 40)), 150);
            final Finding found;
            if (PROJECT.matcher(around).find()) {
                found = new Finding(FOREIGN, "its site names " + place + " as the address of a project, a reference or a plot: " + excerpt, page.url);
            } else {
                final String company = companyAt(window);
                if (company != null) {
                    final Set<String> other = core(company);
                    if (!name.isEmpty() && other.equals(name)) {
                        found = new Finding(OWN, "its address on its page: " + excerpt, page.url);
                    } else if (!name.isEmpty()) {
                        found = related(other, name, services)
                                ? new Finding(UNSURE, "an address in " + place + " of a company of a similar name (" + RagContext.clean(company, 80)
                                        + "), not shown to be its own: " + excerpt, page.url)
                                : new Finding(FOREIGN, "its site names " + place + " as the address of another company (" + RagContext.clean(company, 80)
                                        + "): " + excerpt, page.url);
                    } else if (listing) {
                        found = new Finding(FOREIGN, "its site lists " + RagContext.clean(company, 80) + " with an address in " + place + ": " + excerpt,
                                page.url);
                    } else if (single || related(other, hostWords, services)) {
                        // the one company a site names with its one address, or a company named like the host: the site's owner
                        found = new Finding(OWN, "the address of " + RagContext.clean(company, 80) + " on its page: " + excerpt, page.url);
                    } else {
                        found = new Finding(UNSURE, "an address in " + place + " of " + RagContext.clean(company, 80)
                                + ", not shown to be the site owner's: " + excerpt, page.url);
                    }
                } else if (named(window, name) || (name.isEmpty() && !listing && named(window, hostWords))) {
                    found = new Finding(OWN, "its address on its page: " + excerpt, page.url);
                } else if (single && OWN_PAGE.matcher(head).find()) {
                    found = new Finding(OWN, "the address on its contact page: " + excerpt, page.url);
                } else if (listing) {
                    found = new Finding(FOREIGN, "its site names " + place + " in an entry of a list of addresses: " + excerpt, page.url);
                } else if (PROJECT.matcher(head).find()) {
                    found = new Finding(FOREIGN, "its site names " + place + " as the address of a project, a reference or a plot: " + excerpt,
                            page.url);
                } else {
                    found = new Finding(UNSURE, "an address in " + place + " on its site that is not shown to be its own: " + excerpt, page.url);
                }
            }
            if (best == null || found.kind > best.kind) best = found;
        }
        if (best != null && best.kind == OWN) return best;
        if (!listing) {
            final Matcher area = Pattern.compile("(?:einsatzgebiet|einzugsgebiet|einsatzorte?|tatig in|im raum|in und um|rund um|umgebung von|region|"
                    + "service area|serving)\\W+(?:\\p{L}+\\W+){0,3}" + quoted + "(?![\\p{L}])|" + quoted + "\\s+und\\s+umgebung").matcher(text);
            if (area.find()) return new Finding(AREA, "service area on its page: " + excerpt(text, area.start()), page.url);
            // the service stated for the place ("Tragwerksplanung in Musterstadt", "Schimmelsanierung für Musterstadt")
            for (final String service : services) {
                final String stem = Pattern.quote(RagQuery.stem(RagQuery.fold(service)));
                final Matcher offered = Pattern.compile(stem + "\\p{L}*(?:\\W+\\p{L}+){0,5}?\\W+(?:in|fur|im raum|rund um)\\s+" + quoted + "(?![\\p{L}])")
                        .matcher(text);
                if (offered.find()) {
                    return new Finding(AREA, "the service stated for the place on its page: " + excerpt(text, offered.start()), page.url);
                }
            }
        }
        if (best != null) return best;
        return placePattern(place).matcher(text).find() ? new Finding(MENTION, null, page.url) : new Finding(NONE, null, page.url);
    }

    /**
     * Where the address before a postal code starts: at its street ("Hafenstraße 3", "Justus-Muster-Str. 16") when a house
     * number stands right before the postal code, else at the postal code. The street is the words before the number, back
     * to a delimiter; the name of a company before it is not part of it.
     */
    static int streetStart(final String text, final int postalCode) {
        final int from = Math.max(0, postalCode - 70);
        final Matcher number = HOUSE_NUMBER.matcher(text.substring(from, postalCode));
        if (!number.find()) return postalCode;
        final int at = from + number.start(); // the house number
        int start = at;
        while (start > from && text.charAt(start - 1) == ' ') start--;
        if (start > from && text.charAt(start - 1) == '.') {
            // the dot of an abbreviated street ("str. 16"); any other dot ends a sentence: a number without a street
            if (!text.substring(from, start - 1).matches("(?s).*(?:str|st)")) return postalCode;
            start--;
        }
        // the words of the street: letters, hyphens and single spaces
        while (start > from) {
            final char ch = text.charAt(start - 1);
            if (!(Character.isLetter(ch) || ch == '-' || ch == ' ')) break;
            start--;
        }
        while (start < at && text.charAt(start) == ' ') start++;
        int letters = 0;
        for (int i = start; i < at; i++) if (Character.isLetter(text.charAt(i))) letters++;
        return letters >= 3 ? start : postalCode;
    }

    /** The name of a company right before an address: the words before the last legal form of the window, back to a delimiter. */
    static String companyAt(final String window) {
        final Matcher m = LEGAL_FORM.matcher(window);
        int start = -1;
        int end = -1;
        while (m.find()) {
            start = m.start();
            end = m.end();
        }
        if (start < 0) return null;
        int from = start;
        while (from > 0 && DELIMITERS.indexOf(window.charAt(from - 1)) < 0) from--;
        final String name = window.substring(from, start).trim();
        if (name.isEmpty() || core(name).isEmpty()) return null;
        final List<String> words = new ArrayList<>(Arrays.asList(name.split("\\s+")));
        final String kept = String.join(" ", words.subList(Math.max(0, words.size() - 6), words.size()));
        return (kept + " " + window.substring(start, end)).trim();
    }

    /** Whether every telling word of a name stands in the text before an address. */
    private static boolean named(final String window, final Set<String> name) {
        boolean any = false;
        for (final String w : name) {
            if (w.length() < 3 || COMMON.contains(w)) continue;
            if (!Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(w)).matcher(window).find()) return false;
            any = true;
        }
        return any;
    }

    static Pattern placePattern(final String place) {
        return Pattern.compile("(?<![\\p{L}])" + Pattern.quote(RagQuery.fold(place)) + "(?![\\p{L}])");
    }

    private static String excerpt(final String text, final int at) {
        return RagContext.clean(text.substring(Math.max(0, at - 30), Math.min(text.length(), at + 90)), 140);
    }
}
