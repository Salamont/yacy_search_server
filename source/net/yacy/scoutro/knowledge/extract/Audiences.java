/*
 * Copyright 2026 by Scoutro contributors.
 * Scoutro is an independent community project based on YaCy.
 * Licensed under the GNU General Public License, version 2 or (at your option) any later version.
 */

package net.yacy.scoutro.knowledge.extract;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.yacy.scoutro.knowledge.vocab.Categories;
import net.yacy.scoutro.knowledge.vocab.KgVocabularies;
import net.yacy.scoutro.knowledge.vocab.TermMatcher;

/**
 * 0.13.0: the shared reader of a declared audience (docs/SCOUTRO_KNOWLEDGE_GRAPH.md, 39), used by the rules, by the mapping of
 * the LLM tier's values and, at derivation and reading, for stored evidence (as {@link BusinessSignals} is for system signals).
 * <ul>
 * <li><b>The clause of the address.</b> An audience is read in the clause of its marker ("Wir unterstützen", "Zielgruppe",
 * "Lösungen für", "designed for"), as the marker's object; the clause ends at a sentence end, a colon or semicolon, a line, a
 * dash between words or a comma before a subordinate clause. A heading ("Für wen?", "Zielgruppe:") is read in the unit that
 * follows it, a list under it line by line. A goal that names whom the provider serves ("Zufriedene Eigentümer sind unser Ziel")
 * is an address too: its group is read.</li>
 * <li><b>An organisation's word as its head.</b> A customer type, an audience segment, a company size and a word of a field's
 * organisations (the vocabulary's {@code organisations}) name kinds of customers by themselves. A service category's term names
 * an audience only inside a phrase whose head is a word for organisations or for people of a role ({@link #HEADS}: Unternehmen,
 * Betriebe, Einrichtungen, Dienste, Büros, Praxen, Agenturen, Händler, Anbieter, Träger, … and the ending -ereien; companies,
 * firms, providers, agencies, practices, …): the category's term is the word itself or the modifier of the compound or of the
 * next word ("WordPress-Agenturen" → software/cms, "Pflegeeinrichtungen" → care/stationaere_langzeitpflege). This is knowledge
 * of the language, not of an industry.</li>
 * <li><b>Every vocabulary is read</b> for target categories and industries, the page's own too: a software provider may serve
 * software companies; what decides is the named relation. Audience segments are read in the vocabularies of the page's
 * collections, as before.</li>
 * <li><b>No audience:</b> a service or product term without an organisation's head ("Netzwerk", "WordPress", "Cloud",
 * "Sanierung", "Baufinanzierung", "Veränderungsprozesse"), the speaker's own role ("als Ihr Generalunternehmer", "als erfahrener
 * Pflegedienst"; the reader's role stays: "Sie als Pflegedienst"), a denied one ("keine Bauträger"), a website's technology ("Powered
 * by WordPress" is read by {@link WebsiteTechnology}, never here) and anything after the clause's end.</li>
 * <li><b>0.13.2, the speaker's own business, a topic and a referral:</b> since every vocabulary is read, a provider's own word for
 * itself reached the target categories and industries (a care service: "Für wen eignet sich unser Pflegedienst?", "Wir helfen Ihnen
 * als ambulanter Pflegedienst", "Leistungen im Pflegedienst"). For a target category or industry none is an audience: the word after
 * a possessive of the first person ("unser Pflegedienst", "in unserer Pflegeeinrichtung") or after "im" (a place: "im Pflegedienst";
 * after a marker or a preposition of an addressee it stays: "Software für den Einsatz im Pflegeheim"); the clause whose object is the
 * speaker ("speziell für uns"); the speaker's identity ("wir sind ein ambulanter Pflegedienst"); the role after a dative "Ihnen als"
 * in the nominative ("Ihnen als ambulanter Pflegedienst" is the speaker, "Ihnen als ambulantem Pflegedienst" the reader); a topic
 * ("Fragen rund um den ambulanten Pflegedienst", "das Thema Pflegedienst"; "rund um Ihre Pflegeeinrichtung" stays, the reader's
 * own); a referral or a comparison ("vermitteln Ihnen den passenden Energieberater", "Vermittlung eines ambulanten Pflegedienstes",
 * "Leistungsvergleich der Pflegedienste"). A topic, a referral and the identity reach at most five words, not across a comma or an
 * "und", and never past a preposition of an addressee ("Fragen rund um Software für Pflegedienste", "Vermittlung von Fachkräften
 * für Pflegeeinrichtungen"). An offer for the speaker's business is the voice of a client ("Software für unseren Heizungsbetrieb"):
 * its audience stays. Customer types, segments and sizes keep the reading of 0.13.0 ("unsere Bauherren" are the speaker's clients).
 * The services built on a category's own term name no organisation ("Pflegedienstleistungen"; "Pflegedienstleitungen" do).</li>
 * </ul>
 * The version is no part of an extractor identity: stored evidence is read again with the current reader wherever its
 * audience takes effect.
 */
public final class Audiences {

    public static final String VERSION = "3"; // 2: the speaker's own business, a topic and a referral are no audience (0.13.2); 3: the
    // provider naming itself in the third person (0.14.0)

    /** One audience of a phrase: its predicate, its value and the span that names it. */
    public static final class Audience {
        public final String predicate;
        public final String value;
        public final int start;
        public final int end;

        Audience(final String predicate, final String value, final int start, final int end) {
            this.predicate = predicate;
            this.value = value;
            this.start = start;
            this.end = end;
        }

        @Override
        public String toString() {
            return this.predicate + "=" + this.value;
        }
    }

    /** The audiences of one marker: its span, its clause and the end of its sentence (where a need or an area is read). */
    public static final class Address {
        public final int markerStart;
        public final int markerEnd;
        public final int from;
        public final int to;
        public final int sentenceEnd;
        public final List<Audience> audiences;

        Address(final int markerStart, final int markerEnd, final int from, final int to, final int sentenceEnd, final List<Audience> audiences) {
            this.markerStart = markerStart;
            this.markerEnd = markerEnd;
            this.from = from;
            this.to = to;
            this.sentenceEnd = sentenceEnd;
            this.audiences = audiences;
        }
    }

    /** An offer and the audience it is for ("Lösungen für WordPress-Agenturen", "Software für Pflegedienste"). */
    private static final String OFFER = "(?:lösungen|loesungen|software|angebote?|leistungen|produkte|beratung|solutions|services|products)"
            + "\\s+(?:für|fuer|for)";
    /** The markers of an address; at most {@link #MAX_MARKERS} per page. */
    static final Pattern MARKER = Pattern.compile("(?iu)\\b(?:für\\s+wen|fuer\\s+wen|zielgruppen?|wir\\s+unterstützen|wir\\s+unterstuetzen"
            + "|wir\\s+beraten|wir\\s+begleiten|wir\\s+helfen|unser\\s+angebot\\s+richtet\\s+sich\\s+an|richtet\\s+sich\\s+an|unsere\\s+kunden\\s+sind"
            + "|wir\\s+arbeiten\\s+für|speziell\\s+für|our\\s+clients\\s+are|we\\s+help|we\\s+support|designed\\s+for|who\\s+we\\s+serve"
            + "|" + OFFER + ")\\b\\s*:?\\s*");
    /** A marker that heads its audience ("Für wen?", "Zielgruppe:", "Who we serve"): the audience follows in the next unit. */
    private static final Pattern HEADING = Pattern.compile("(?iu)^(?:für\\s+wen|fuer\\s+wen|zielgruppen?|who\\s+we\\s+serve)");
    /**
     * A goal that names whom the provider serves ("Zufriedene Bauherren sind unser größtes Anliegen", "happy patients are our
     * priority"): the group before the goal is the audience (group 1).
     */
    static final Pattern GOAL = Pattern.compile("(?iu)\\b(?:zufriedene|glückliche|glueckliche|satisfied|happy)\\s+([^.!?:;|\\n]{2,80}?)\\s+"
            + "(?:sind|ist|are|is)\\s+(?:unser|unsere|our)\\s+(?:\\p{L}+\\s+)?(?:ziel|anliegen|anspruch|antrieb|goal|aim|priority)\\b");
    static final int MAX_MARKERS = 6;
    /** A clause is read up to this many characters (so that the stored excerpt, at most 200 characters, holds it). */
    static final int MAX_CLAUSE = 150;
    /** A need and a service area are read up to the sentence's end, at most this far. */
    static final int MAX_SENTENCE = 320;

    /**
     * The heads of a phrase that names organisations, folded ({@link TermMatcher#fold}): a word ends with one of them
     * ("Pflegeeinrichtungen", "WordPress-Agenturen", "Cloud-Anbieter") or is one after the category's word ("WordPress Agenturen",
     * "cloud providers"), or comes before the field it is from ("Unternehmen aus dem E-Commerce"). Such a word alone names no field
     * ("Hersteller", "Unternehmen": the customer types read those).
     */
    static final List<String> HEADS = List.of(
            "unternehmen", "unternehmer", "firmen", "firma", "betriebe", "betrieb", "einrichtungen", "einrichtung", "dienstleister",
            "bueros", "buero", "praxen", "praxis", "kanzleien", "kanzlei", "agenturen", "agentur", "haendler", "hersteller", "anbieter",
            "traeger", "verwaltungen", "verwaltung", "kommunen", "gemeinden", "vereine", "verbaende", "werkstaetten", "kliniken", "klinik",
            "schulen", "behoerden", "institute", "konzerne", "gesellschaften", "genossenschaften", "organisationen", "stiftungen",
            "versorger", "berater", "planer", "ingenieure", "companies", "company", "firms", "businesses", "providers", "agencies",
            "agency", "practices", "vendors", "manufacturers", "organisations", "organizations", "operators", "retailers", "suppliers",
            "contractors", "consultants", "engineers", "clinics", "hospitals");
    /** People of a role of a field: the word alone may be the category's own term ("Architekten", "Steuerberater"). */
    static final List<String> ROLES = List.of("architekten", "handwerker", "aerzte", "zahnaerzte", "anwaelte", "steuerberater", "makler",
            "therapeuten", "apotheker", "gastronomen", "landwirte", "bauherren", "architects", "dentists", "lawyers");
    /** Words for clients that name organisations only before the field they are from ("Kunden aus dem Bereich Logistik"). */
    static final List<String> CLIENTS = List.of("kunden", "kundinnen", "auftraggeber", "mandanten", "customers", "clients");
    /**
     * An organisation's word and the field it is from, before a category's term: "Händler aus dem E-Commerce", "Kunden aus dem
     * Bereich Logistik", "Betriebe aus der regionalen Lebensmittelbranche", "clients from the energy sector"; at most three words between, none of them a preposition (group 1: the word, group 2: the words between).
     */
    private static final Pattern FIELD_OF = Pattern.compile("(?iu)(?:^|[^\\p{L}])(\\p{L}+)\\s+(?:aus|from)\\s+(?:(?:dem|den|the)\\s+"
            + "(?:bereichen?|branchen?|sektoren|sektor|sector|industry|field)\\s+|(?:der|dem|den|the)\\s+)?((?:[\\p{L}\\p{N}&/.-]+"
            + "(?:\\s*,\\s*|\\s+und\\s+|\\s+and\\s+|\\s+&\\s+|\\s+)){0,3})$");
    private static final Pattern PREPOSITION = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:bei|mit|in|im|am|an|auf|für|fuer|von|vom|zu|zum|zur"
            + "|durch|über|ueber|unter|nach|seit|for|with|at|on|by|of|to)(?:[^\\p{L}]|$)");
    /**
     * Words that name an organisation only as the end of a category's own term ("Pflegedienst", "Pflegeheim"), never as the head of
     * another one ("Cloud-Dienste" are services). Words such as "-haus", "-werk" or "-zentrum" end products and places too
     * ("Fertighaus", "Netzwerk", "Rechenzentrum") and are none.
     */
    static final List<String> TERM_HEADS = List.of("dienst", "dienste", "heim", "heime");
    /** 0.13.2: the services of a category's organisation, a word built on its term ("Pflegedienstleistungen"). */
    private static final Pattern SERVICES = Pattern.compile("leistung(?:en)?$");
    /** A word of the German plural "-ereien" (Druckereien, Schreinereien) names businesses. */
    private static final Pattern EREIEN = Pattern.compile("\\p{L}{3,}ereien$");
    /** The word before "als" and up to three words after it, at the end of the text before a phrase. */
    private static final Pattern ROLE = Pattern.compile("(?iu)(?:(?:^|[^\\p{L}])(\\p{L}+)\\s+|^\\s*)als\\s+((?:\\p{L}+\\s+){0,3})$");
    private static final Pattern READER = Pattern.compile("(?iu)^(?:sie|ihnen|euch|dich|dir|you)$");
    /** "mehr als", "sowohl … als auch": no role. */
    private static final Pattern NOT_ROLE = Pattern.compile("(?iu)^(?:mehr|weniger|sowohl|anders|andere[nmrs]?|lieber|eher|more|rather)$");
    /** A possessive after "Sie als": the speaker as the reader's partner ("Sie als Ihr Steuerberater"), not the reader's role. */
    private static final Pattern POSSESSIVE = Pattern.compile("(?iu)^(?:ihr|ihre|ihren|ihrem|ihrer|your|unser|unsere|unseren|unserem|our)$");
    private static final Pattern NEGATION = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:nicht|keine?[nmrs]?|kein|not|no|never)(?:[^\\p{L}]|$)(?!\\s*(?:nur|only)\\b)");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)*");
    /** 0.13.2: the clause's object is the speaker ("speziell für uns programmiert"). */
    private static final Pattern SPEAKER_OBJECT = Pattern.compile("(?iu)^\\s*(?:uns|mich|us|me)(?:[^\\p{L}]|$)");
    /** 0.13.2: the word after a possessive of the first person ("unser Pflegedienst", "in unserer modernen Einrichtung"). */
    private static final Pattern OWN = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:unser|unsere|unserem|unseren|unserer|unseres|our)\\s+"
            + "(?:[\\p{L}-]+\\s+){0,2}$");
    /**
     * 0.13.2: an offer for the speaker's business ("Software für unseren Heizungsbetrieb") is the voice of a client: the offer is for
     * such businesses.
     */
    private static final Pattern OFFER_TO_OWN = Pattern.compile("(?iu)" + OFFER + "\\s*:?\\s*$");
    private static final Pattern OWN_FIRST = Pattern.compile("(?iu)^\\s*(?:unser|unsere|unserem|unseren|unserer|unseres|our)\\s");
    /** 0.13.2: a place, not an addressee ("Leistungen im Pflegedienst"). */
    private static final Pattern LOCATIVE = Pattern.compile("(?iu)(?:^|[^\\p{L}])im\\s+(?:[\\p{L}-]+\\s+)?$");
    /** 0.13.2: the reader's own ("rund um Ihre Pflegeeinrichtung"): a topic of the reader's business stays its audience. */
    private static final Pattern READERS = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:ihr|ihre|ihren|ihrem|ihrer|ihres|your)\\s+"
            + "(?:[\\p{L}-]+\\s+){0,2}$");
    /** 0.13.2: a topic ("Fragen rund um den ambulanten Pflegedienst", "das Thema Pflegedienst"). */
    private static final Pattern TOPIC = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:thema|themen|themenbereich|frage|fragen|rund\\s+um|topic|topics"
            + "|questions)(?:[^\\p{L}]|$)");
    /**
     * 0.13.2: a referral or a comparison ("vermitteln Ihnen den passenden …", "Vermittlung eines …", "Leistungsvergleich der …");
     * not a word that only holds it ("Wissensvermittlung" is teaching).
     */
    private static final Pattern REFERRAL = Pattern.compile("(?iu)(?:(?:^|[^\\p{L}])(?:vermittl\\p{L}*|vermitteln|refer\\p{L}*)|\\p{L}*vergleich\\p{L}*)"
            + "(?:[^\\p{L}]|$)");
    /** 0.13.2: the speaker's identity ("wir sind eines der ersten …"). */
    private static final Pattern IDENTITY = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:wir\\s+sind|ich\\s+bin|we\\s+are)(?:[^\\p{L}]|$)");
    /**
     * 0.13.2: the preposition of an addressee between a topic or a referral and the word ("Fragen rund um Software für Pflegedienste",
     * "die Vermittlung von Fachkräften für Pflegeeinrichtungen"): the word is the audience again; after the speaker's identity also
     * "auf" and "an" ("wir sind spezialisiert auf Pflegedienste").
     */
    private static final Pattern FOR = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:für|fuer|for)(?:[^\\p{L}]|$)");
    /** 0.13.2: a comma or a conjunction ends the reach of a topic, a referral or the speaker's identity. */
    private static final Pattern BREAK = Pattern.compile("(?iu),|(?:^|[^\\p{L}])(?:und|oder|sowie|and|or)(?:[^\\p{L}]|$)");
    private static final Pattern FOR_IDENTITY = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:für|fuer|for|auf|an|on|to)(?:[^\\p{L}]|$)");
    /** 0.13.2: a dative pronoun of the reader: a role after it in the nominative is the speaker's ("Ihnen als ambulanter Pflegedienst"). */
    private static final Pattern READER_DATIVE = Pattern.compile("(?iu)^(?:ihnen|euch|dir)$");

    /** 0.14.0 (item B6): the clause starts with an article before the word ("Der Pflegedienst Muster …", "Das ambulante …"). */
    private static final Pattern SUBJECT_ARTICLE = Pattern.compile("(?iu)^[\\s*#_\"„“]*(?:der|die|das|the)\\s+(?:[\\p{L}-]+\\s+){0,2}$");
    /** 0.14.0 (item B6): the clause starts with the reader's possessive before the word ("Ihr häuslicher Pflegedienst …"). */
    private static final Pattern READER_OWN_START = Pattern.compile("(?iu)^[\\s*#_\"„“]*(?:ihr|ihre|your)\\s+(?:[\\p{L}-]+\\s+){0,2}$");
    /** 0.14.0: after the word, a proper name ("… Pflegedienst Muster unterstützt"), not the reader ("… Ihrer Region"). */
    private static final Pattern NAME_AFTER = Pattern.compile("(?u)^[\\p{L}-]*\\s+[„\"]?(?!(?:Ihr|Ihre|Ihrem|Ihren|Ihrer|Ihres|Sie|Ihnen|Euch|Euer|Eure)\\b)"
            + "\\p{Lu}[\\p{L}-]+");
    /** 0.14.0: after the word, its place ("… Pflegedienst in Musterstadt", "… für Musterstadt und Umgebung"). */
    private static final Pattern PLACE_AFTER = Pattern.compile("(?iu)^[\\p{L}-]*\\s+(?:in|im|aus|für|fuer|am|an\\s+der|bei|rund\\s+um|vor\\s+ort\\s+in)"
            + "\\s+(?-i:\\p{Lu})");

    private Audiences() {}

    // ------------------------------------------------------------------------------------------------------------- reading

    /**
     * The addresses of a page's text with their audiences (the rule tier): the clause of every marker, at most
     * {@link #MAX_MARKERS}. Target categories and industries come from every vocabulary, segments from {@code ctx}'s.
     */
    public static List<Address> read(final String text, final ExtractContext ctx) {
        final List<Address> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        final Matcher m = MARKER.matcher(text);
        int markers = 0;
        while (m.find() && markers < MAX_MARKERS) {
            markers++;
            int from = m.end();
            int to = clauseEnd(text, from, Math.min(text.length(), from + MAX_CLAUSE));
            if (HEADING.matcher(m.group()).find()) {
                // a heading: its audience is the next unit, or the list below it
                if (text.substring(from, to).replaceAll("[\\s*_#]+", "").isEmpty()) {
                    from = nextUnit(text, to);
                }
                to = listOrClauseEnd(text, from);
            }
            final int sentence = sentenceEnd(text, from, Math.min(text.length(), from + MAX_SENTENCE));
            out.add(new Address(m.start(), m.end(), from, Math.max(from, to), Math.max(to, sentence), phrases(text, from, to, ctx, true)));
        }
        // a goal that names whom the provider serves: its group, with the goal in the excerpt so that stored evidence reads again
        final Matcher g = GOAL.matcher(text);
        while (g.find() && markers < MAX_MARKERS) {
            markers++;
            final List<Audience> named = new ArrayList<>();
            for (final Audience a : phrases(text, g.start(1), g.end(1), ctx, true)) {
                named.add(new Audience(a.predicate, a.value, a.start, g.end()));
            }
            final int sentence = sentenceEnd(text, g.end(), Math.min(text.length(), g.end() + MAX_SENTENCE));
            out.add(new Address(g.start(), g.start(1), g.start(1), g.end(1), Math.max(g.end(), sentence), named));
        }
        return out;
    }

    /**
     * The audiences a quote names without a marker: the LLM tier's values, where the model states that the quote is the page's
     * audience; the reader still demands the phrase that names organisations. Every clause of the quote is read.
     */
    public static List<Audience> named(final String quote, final ExtractContext ctx) {
        final List<Audience> out = new ArrayList<>();
        if (quote == null || quote.isEmpty()) {
            return out;
        }
        int at = 0;
        while (at < quote.length()) {
            final int end = clauseEnd(quote, at, quote.length());
            out.addAll(phrases(quote, at, end, ctx, true));
            at = Math.max(end + 1, at + 1);
        }
        return out;
    }

    /**
     * True if the stored evidence still gives {@code value} for {@code predicate}, read again with the current reader: tiers 1
     * and 4 (structured data, an operator's statement) always do; the rule tier's excerpt is read with its markers, the LLM
     * tier's quote ({@link #named}) without. Segments are read in every vocabulary here: the value names its own.
     */
    public static boolean confirms(final String predicate, final String value, final String excerpt, final int tier) {
        if (tier != 2 && tier != 3) {
            return true;
        }
        if (value == null || excerpt == null) {
            return false;
        }
        final ExtractContext all = all();
        final String text = excerpt.replace("[…]", " ");
        if (tier == 3) {
            for (final Audience a : named(text, all)) {
                if (a.predicate.equals(predicate) && a.value.equals(value)) {
                    return true;
                }
            }
            return false;
        }
        for (final Address a : read(text, all)) {
            for (final Audience x : a.audiences) {
                if (x.predicate.equals(predicate) && x.value.equals(value)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True for the predicates of a declared audience the reader decides. */
    public static boolean audiencePredicate(final String predicate) {
        return Vocabulary.TARGET_CATEGORY.equals(predicate) || Vocabulary.TARGET_INDUSTRY.equals(predicate)
                || Vocabulary.AUDIENCE_SEGMENT.equals(predicate) || Vocabulary.CUSTOMER_TYPE.equals(predicate)
                || Vocabulary.COMPANY_SIZE.equals(predicate);
    }

    private static volatile Object[] allContext;

    /** A context with every vocabulary as the page's own, for re-reading stored evidence (cached per vocabulary snapshot). */
    static ExtractContext all() {
        final KgVocabularies.Snapshot s = KgVocabularies.get();
        final Object[] c = allContext;
        if (c != null && c[0] == s) {
            return (ExtractContext) c[1];
        }
        final ExtractContext ctx = new ExtractContext(s, s.categories.vocabularies.keySet(), false, List.of());
        allContext = new Object[] {s, ctx};
        return ctx;
    }

    // ------------------------------------------------------------------------------------------------------------- phrases

    /** The audiences named in {@code text[from, to)}. */
    static List<Audience> phrases(final String text, final int from, final int to, final ExtractContext ctx, final boolean segments) {
        final List<Audience> out = new ArrayList<>();
        if (to <= from) {
            return out;
        }
        final Set<String> done = new LinkedHashSet<>();
        for (final TermMatcher.Hit<Categories.Entry> h : ctx.categories.customerTypes(text, from, to)) {
            if (addressed(text, from, h, false) && done.add("ct:" + h.entry.id)) {
                out.add(new Audience(Vocabulary.CUSTOMER_TYPE, h.entry.id, h.start, h.end));
            }
        }
        if (segments) {
            for (final Categories.Vocab v : ctx.vocabularies) {
                for (final TermMatcher.Hit<Categories.Entry> h : v.segments(text, from, to)) {
                    if (addressed(text, from, h, false) && done.add("seg:" + h.entry.id)) {
                        out.add(new Audience(Vocabulary.AUDIENCE_SEGMENT, h.entry.id, h.start, h.end));
                    }
                }
            }
        }
        for (final TermMatcher.Hit<Categories.Entry> h : ctx.categories.companySizes(text, from, to)) {
            if (addressed(text, from, h, false) && done.add("size:" + h.entry.id)) {
                out.add(new Audience(Vocabulary.COMPANY_SIZE, h.entry.id, h.start, h.end));
            }
        }
        for (final Categories.Vocab v : ctx.allVocabularies()) {
            for (final TermMatcher.Hit<Categories.Entry> h : v.organisations(text, from, to)) {
                if (addressed(text, from, h, true) && ctx.nace.valid(h.entry.nace) && done.add("nace:" + h.entry.nace)) {
                    out.add(new Audience(Vocabulary.TARGET_INDUSTRY, h.entry.nace, h.start, h.end));
                }
            }
            for (final TermMatcher.Hit<Categories.Entry> h : v.categories(text, from, to)) {
                if (!organisationHeaded(text, h, to) || !addressed(text, from, h, true) || !done.add("cat:" + h.entry.id)) {
                    continue;
                }
                out.add(new Audience(Vocabulary.TARGET_CATEGORY, h.entry.id, h.start, h.end));
                if (h.entry.nace != null && ctx.nace.valid(h.entry.nace) && done.add("nace:" + h.entry.nace)) {
                    out.add(new Audience(Vocabulary.TARGET_INDUSTRY, h.entry.nace, h.start, h.end));
                }
            }
        }
        out.sort(java.util.Comparator.comparingInt((Audience a) -> a.start).thenComparing(a -> a.predicate));
        return out;
    }

    /**
     * True if the hit is addressed: not the speaker's own role, not denied before it in the clause; for an organisation or a category
     * ({@code organisation}, 0.13.2) also not the speaker's own business or identity, no topic and no referral.
     */
    private static boolean addressed(final String text, final int from, final TermMatcher.Hit<Categories.Entry> h, final boolean organisation) {
        final String before = text.substring(from, h.start);
        if (NEGATION.matcher(before).find()) {
            return false;
        }
        if (organisation && (ownBusiness(text, from, before) || thirdPerson(text, before, h.end))) {
            return false;
        }
        final Matcher role = ROLE.matcher(before);
        if (role.find() && (role.group(1) == null || !NOT_ROLE.matcher(role.group(1)).matches())) {
            // "Sie als (ambulanter) Pflegedienst" is the reader in its role; "als Ihr Generalunternehmer", "als erfahrener Pflegedienst"
            // and "Sie als Ihr Steuerberater" are the speaker; for an organisation so is "Ihnen als ambulanter Pflegedienst" (the
            // nominative after a dative, 0.13.2)
            final String[] after = role.group(2).trim().isEmpty() ? new String[0] : role.group(2).trim().split("\\s+");
            if (organisation && role.group(1) != null && READER_DATIVE.matcher(role.group(1)).matches() && after.length > 0
                    && after[0].toLowerCase(Locale.ROOT).matches("\\p{L}+(?:er|es)")) {
                return false;
            }
            return role.group(1) != null && READER.matcher(role.group(1)).matches() && !(after.length > 0 && POSSESSIVE.matcher(after[0]).matches());
        }
        return true;
    }

    /**
     * 0.14.0 (item B6): true if the provider names itself in the third person: the word as the clause's subject with its own name
     * ("Der Pflegedienst Muster unterstützt Sie", "Das Pflegeheim Sonnenhof bietet …"), or presented to the reader at its place
     * ("Ihr häuslicher Pflegedienst in Musterstadt"). An addressee is none of them ("Lösungen für Ihren Pflegedienst", "Die
     * Pflegeheime Ihrer Region").
     */
    private static boolean thirdPerson(final String text, final String before, final int end) {
        final String after = text.substring(end, Math.min(text.length(), end + 80));
        return SUBJECT_ARTICLE.matcher(before).find() && NAME_AFTER.matcher(after).find()
                || READER_OWN_START.matcher(before).find() && PLACE_AFTER.matcher(after).find();
    }

    /** 0.13.2: true if the organisation or category before which {@code before} ends is the speaker's own, a topic or a referral. */
    private static boolean ownBusiness(final String text, final int from, final String before) {
        if (SPEAKER_OBJECT.matcher(before).find()) {
            return true;
        }
        if (OWN.matcher(before).find()
                && !(OWN_FIRST.matcher(before).find() && OFFER_TO_OWN.matcher(text.substring(Math.max(0, from - 24), from)).find())) {
            return true;
        }
        // a place after "im" ("Leistungen im Pflegedienst"); after a marker or a preposition of an addressee it is where the audience
        // works ("Software für den Einsatz im Pflegeheim")
        if (LOCATIVE.matcher(before).find() && !FOR.matcher(text.substring(Math.max(0, from - 8), from)).find() && !FOR.matcher(before).find()) {
            return true;
        }
        return unaddressedAfter(IDENTITY, before, FOR_IDENTITY) || unaddressedAfter(REFERRAL, before, FOR)
                || unaddressedAfter(TOPIC, before, FOR) && !READERS.matcher(before).find();
    }

    /**
     * 0.13.2: true if {@code p} occurs in {@code before} close to the word: at most five words after its last occurrence, no comma, no
     * "und" or "oder" and no preposition of an addressee ({@code to}) between.
     */
    private static boolean unaddressedAfter(final Pattern p, final String before, final Pattern to) {
        int end = -1;
        for (final Matcher m = p.matcher(before); m.find();) {
            end = m.end();
        }
        if (end < 0) {
            return false;
        }
        final String between = before.substring(end);
        return !to.matcher(between).find() && !BREAK.matcher(between).find() && between.trim().split("\\s+").length <= 5;
    }

    /**
     * True if the category's term at {@code [start, end)} stands in a phrase whose head is a word for organisations or people of a
     * role: the word that holds the term ends with a head ("Pflegeeinrichtungen", "WordPress-Agenturen", "Architekturbüros") and is
     * more than the head, or is a role ("Architekten"), or the next word is a head ("WordPress Agenturen", "cloud providers"), or the
     * category's own term ends with a word that names its organisation ("Pflegedienst", "Pflegeheim").
     */
    static boolean organisationHeaded(final String text, final TermMatcher.Hit<Categories.Entry> h, final int to) {
        int a = h.start;
        while (a > 0 && (Character.isLetterOrDigit(text.charAt(a - 1)) || text.charAt(a - 1) == '-' && a > 1 && Character.isLetterOrDigit(text.charAt(a - 2)))) {
            a--;
        }
        int b = h.end;
        while (b < to && (Character.isLetterOrDigit(text.charAt(b)) || text.charAt(b) == '-' && b + 1 < to && Character.isLetterOrDigit(text.charAt(b + 1)))) {
            b++;
        }
        final String word = TermMatcher.fold(text.substring(a, b).replace("-", ""));
        if (EREIEN.matcher(word).find() || ends(word, ROLES) || ends(word, HEADS) && !HEADS.contains(word)) {
            return true;
        }
        final Matcher field = FIELD_OF.matcher(text.substring(Math.max(0, a - 90), a));
        if (field.find() && !PREPOSITION.matcher(field.group(2)).find()) {
            final String head = TermMatcher.fold(field.group(1));
            if (HEADS.contains(head) || CLIENTS.contains(head)) {
                return true;
            }
        }
        for (final String term : h.entry.terms) {
            final String t = TermMatcher.fold(term.replaceAll("[\\s-]+", ""));
            // 0.13.2: not the services built on the term ("Pflegedienstleistungen")
            if (ends(t, TERM_HEADS) && word.startsWith(t.substring(0, Math.max(1, t.length() - 2))) && !SERVICES.matcher(word).find()) {
                return true; // the category's own term names the organisation
            }
        }
        final Matcher next = WORD.matcher(text).region(b, to);
        if (next.find() && text.substring(b, next.start()).matches("[ \\t]{1,2}")) {
            final String n = TermMatcher.fold(next.group().replace("-", ""));
            return HEADS.contains(n) || ROLES.contains(n);
        }
        return false;
    }

    private static boolean ends(final String folded, final List<String> heads) {
        for (final String h : heads) {
            if (folded.endsWith(TermMatcher.fold(h))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------------------ clauses

    /**
     * Where the clause from {@code from} ends: a sentence end, a colon or semicolon, a question or exclamation mark, a line, a bar or
     * bullet, a dash between words, and a comma before a subordinate or contrasting clause ("…, wenn Sie …", "…, die …"); a list of
     * names keeps its commas. At most {@code max}.
     */
    static int clauseEnd(final String text, final int from, final int max) {
        for (int i = from; i < max; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == ';' || c == ':' || c == '?' || c == '!' || c == '|' || c == '•' || c == '·') {
                return i;
            }
            if (c == '.' && sentenceDot(text, i)) {
                return i;
            }
            if ((c == '–' || c == '—' || c == '-') && i > from && Character.isWhitespace(text.charAt(i - 1)) && i + 1 < text.length()
                    && Character.isWhitespace(text.charAt(i + 1))) {
                return i;
            }
            if (c == ',' && SUBORDINATE.matcher(text).region(i + 1, Math.min(text.length(), i + 30)).lookingAt()) {
                return i;
            }
        }
        return max;
    }

    // "aber auch" and "sondern auch" continue the audience ("nicht nur Arztpraxen, sondern auch …"): no end
    private static final Pattern SUBORDINATE = Pattern.compile("(?iu)\\s*(?:wenn|weil|damit|dass|daß|ob|obwohl|um|denn|während|sodass"
            + "|so\\s+dass|falls|sofern|die|der|das|deren|dessen|welche[nmrs]?|was|wo|wie|which|that|who|where|when|while|because|but|if|to)\\b");

    /** The end of the sentence from {@code from}: a sentence end, a semicolon, a line; at most {@code max}. */
    static int sentenceEnd(final String text, final int from, final int max) {
        for (int i = from; i < max; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == ';' || c == '?' || c == '!' || c == '|') {
                return i;
            }
            if (c == '.' && sentenceDot(text, i)) {
                return i;
            }
        }
        return max;
    }

    private static final Set<String> ABBREVIATIONS = Set.of("z", "b", "u", "a", "bzw", "ca", "ggf", "inkl", "exkl", "zzgl", "evtl", "st",
            "dr", "prof", "nr", "str", "co", "e", "v", "gem", "gebr", "usw", "etc", "vgl", "max", "min", "mio", "mrd", "bspw", "sog");

    /** True if the period at {@code i} ends a sentence: white space or the end follows, and no abbreviation ("z. B.", "St.") precedes. */
    private static boolean sentenceDot(final String text, final int i) {
        if (i + 1 < text.length() && !Character.isWhitespace(text.charAt(i + 1)) && text.charAt(i + 1) != '*') {
            return false;
        }
        int k = i;
        while (k > 0 && Character.isLetter(text.charAt(k - 1))) {
            k--;
        }
        final String word = text.substring(k, i).toLowerCase(Locale.ROOT);
        return word.isEmpty() ? !(k > 0 && Character.isDigit(text.charAt(k - 1))) : !ABBREVIATIONS.contains(word);
    }

    /** The start of the unit after a heading's end: past its mark, white space and Markdown. */
    private static int nextUnit(final String text, final int at) {
        int i = Math.min(text.length(), at + 1);
        while (i < text.length() && (Character.isWhitespace(text.charAt(i)) || text.charAt(i) == '*' || text.charAt(i) == '_'
                || text.charAt(i) == '#')) {
            i++;
        }
        return i;
    }

    private static final Pattern LIST_MARK = Pattern.compile("[ \\t]*(?:[-*•·]|\\d{1,2}[.)])[ \\t]+");

    /**
     * The end of the list that starts at {@code from} ("- Architekturbüros\n- Bauträger", also as the stored excerpt writes it on
     * one line, "- Architekturbüros - Bauträger"): a line that is no item, a sentence end, a colon or a bar ends it; or else the
     * end of the clause.
     */
    private static int listOrClauseEnd(final String text, final int from) {
        final int max = Math.min(text.length(), from + MAX_CLAUSE);
        if (!LIST_MARK.matcher(text).region(from, text.length()).lookingAt()) {
            return clauseEnd(text, from, max);
        }
        for (int i = from; i < max; i++) {
            final char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                if (!LIST_MARK.matcher(text).region(i + 1, text.length()).lookingAt()) {
                    return i;
                }
            } else if (c == ';' || c == ':' || c == '?' || c == '!' || c == '|' || c == '.' && sentenceDot(text, i)) {
                return i;
            }
        }
        return max;
    }

    /** The audiences of the addresses, flat (for tests and probes). */
    public static List<Audience> audiences(final Collection<Address> addresses) {
        final List<Audience> out = new ArrayList<>();
        for (final Address a : addresses) {
            out.addAll(a.audiences);
        }
        return out;
    }
}
