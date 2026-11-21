/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What a question for job postings or providers asks for (docs/SCOUTRO_RAG_QUALITY.md, sections 12 and 13): their number,
 * a list of them, or a recommendation. The server's search is the same for all three; what goes to the answer differs:
 * <ul>
 * <li>{@link #COUNT}: the number comes from the search. A question that asks for nothing else is answered by the server
 * itself, without a model request ({@link #plainCount}); else the model gets the count and the hits shown;</li>
 * <li>{@link #LIST}: the model gets the hits shown, each with a short citable entry, and the count;</li>
 * <li>{@link #RECOMMEND}: the same hits with their evidence (for a provider its relation to the place and an excerpt of
 * its page); the model weighs them;</li>
 * <li>{@link #OTHER}: a question about something of one posting or provider; the search only helps the normal retrieval.</li>
 * </ul>
 */
public enum QuestionKind {
    COUNT, LIST, RECOMMEND, OTHER;

    private static final Pattern COUNT_WORDS = Pattern.compile("(?<![\\p{L}])(?:wie\\s+viele|wieviele|anzahl|zahl\\s+der|how\\s+many|number\\s+of)"
            + "(?![\\p{L}])");
    private static final Pattern RECOMMEND_WORDS = Pattern.compile("(?<![\\p{L}])(?:wer|wen|wem|empfiehl\\p{L}*|empfehl\\p{L}*|geeignet\\p{L}*"
            + "|beste[rnms]?|passend\\p{L}*|recommend\\p{L}*|suggest\\p{L}*|who|whom|best)(?![\\p{L}])");
    private static final Pattern LIST_WORDS = Pattern.compile("(?<![\\p{L}])(?:alle|allen|aller|samtliche\\p{L}*|welche\\p{L}*|zeig\\p{L}*"
            + "|nenn\\p{L}*|list\\p{L}*|auflist\\p{L}*|such\\p{L}*|find\\p{L}*|gibt\\s+es|ubersicht|show|which|all|every|any)(?![\\p{L}])");
    /** a count question that asks for more than the number: a list, the places, the reasons */
    private static final Pattern MORE_THAN_A_NUMBER = Pattern.compile("(?<![\\p{L}])(?:welche\\p{L}*|nenn\\p{L}*|zeig\\p{L}*|list\\p{L}*"
            + "|auflist\\p{L}*|wo|wer|warum|wieso|weshalb|vergleich\\p{L}*|empfiehl\\p{L}*|empfehl\\p{L}*|beschreib\\p{L}*|erklar\\p{L}*"
            + "|which|show|name|where|who|why|compare|recommend\\p{L}*|describe|explain)(?![\\p{L}])");
    private static final Pattern GERMAN = Pattern.compile("(?<![\\p{L}])(?:wie|wieviele|anzahl|zahl|gibt|es|der|die|das|und|fur|zu|zur|zum|in|im|von"
            + "|stellen\\p{L}*|anbieter)(?![\\p{L}])");
    /** characters of a question the server answers itself */
    static final int PLAIN_CHARS = 160;

    /** The kind of a question for postings or providers. */
    public static QuestionKind of(final String question) {
        final String folded = RagQuery.fold(question == null ? "" : question);
        if (COUNT_WORDS.matcher(folded).find()) return COUNT;
        if (RECOMMEND_WORDS.matcher(folded).find()) return RECOMMEND;
        if (LIST_WORDS.matcher(folded).find()) return LIST;
        return OTHER;
    }

    /**
     * Whether a question asks for a number and nothing else: one short question with a count word, no request for a list, a
     * place of the hits, a reason or a comparison. Only such a question is answered without the model.
     */
    public static boolean plainCount(final String question) {
        if (question == null) return false;
        final String text = question.trim();
        if (text.isEmpty() || text.length() > PLAIN_CHARS || text.indexOf('\n') >= 0) return false;
        if (text.chars().filter(c -> c == '?').count() > 1 || text.indexOf(':') >= 0 || text.indexOf(';') >= 0) return false;
        final String folded = RagQuery.fold(text);
        return COUNT_WORDS.matcher(folded).find() && !MORE_THAN_A_NUMBER.matcher(folded).find();
    }

    /** German unless the question shows no German word. */
    static boolean german(final String question) {
        return GERMAN.matcher(RagQuery.fold(question == null ? "" : question)).find();
    }

    // ------------------------------------------------------------------------------------------ the server's own answer

    private static String scope(final ToolContext context, final boolean german) {
        if (context.collection == null) return german ? "allen Collections des lokalen Index" : "all collections of the local index";
        return (german ? "der Collection " : "the collection ") + context.collection;
    }

    private static String reasons(final List<String> gaps, final boolean german) {
        final List<String> out = new ArrayList<>();
        for (final String g : gaps) {
            if (g.startsWith("time budget")) out.add(german ? "das Zeitbudget der Suche war aufgebraucht (" + g + ")" : "the search's time budget ran out (" + g + ")");
            else out.add(g);
        }
        return String.join("; ", out);
    }

    /** Why the relation to the place is open for some providers, in the language of the answer. */
    static String placeReasons(final ServiceSearch.Result result, final boolean german) {
        final List<String> out = new ArrayList<>();
        final String place = result.plan.place;
        if (result.placeUnread > 0) {
            out.add(german ? "die Seiten von " + result.placeUnread + " Anbietern, die " + place + " nennen, wurden nicht gelesen ("
                    + (result.placeOutOfTime ? "das Zeitbudget der Suche war aufgebraucht" : "Höchstgrenze von " + ServiceSearch.PLACE_HOSTS
                            + " gelesenen Anbietern je Suche erreicht") + ")"
                    : "the pages of " + result.placeUnread + " providers naming " + place + " were not read (" + (result.placeOutOfTime
                            ? "the search's time budget ran out" : "the limit of " + ServiceSearch.PLACE_HOSTS + " providers read per search") + ")");
        }
        if (result.placePartly > 0) {
            out.add(german ? result.placePartly + " Anbieter " + (result.placePartly == 1 ? "hat" : "haben") + " mehr als " + ServiceSearch.PLACE_PAGES
                    + " Seiten zu " + place
                    + ", unter den gelesenen ohne Adresse oder Einsatzgebiet"
                    : result.placePartly + (result.placePartly == 1 ? " provider has" : " providers have") + " more than " + ServiceSearch.PLACE_PAGES
                    + " pages naming " + place
                    + ", none of those read with an address or service area");
        }
        if (result.placeUnattributed > 0) {
            out.add(german ? "bei " + result.placeUnattributed + " Anbietern steht auf der Website eine Adresse in " + place
                    + ", die nicht sicher dem Anbieter selbst gehört (etwa eine Gruppenfirma, eine Adresse ohne Namen oder eine andere Firma"
                    + " als die, für die die Seiten die Leistung nennen)"
                    : result.placeUnattributed + (result.placeUnattributed == 1 ? " provider shows" : " providers show") + " an address in " + place
                    + " on the site that is not shown to be the provider's own (a company of a group, an address without a name, another"
                    + " company than the one the pages name for the service)");
        }
        for (final String g : result.placeGaps) {
            if (g.contains("their relation to it is open")) continue; // said above
            if (german && g.startsWith("time budget")) out.add("das Zeitbudget der Suche war aufgebraucht (" + g + ")");
            else out.add(g);
        }
        return String.join("; ", out);
    }

    /** What the search saw and did not count: postings that only name the employer, pages whose hit belongs to no posting. */
    private static String notCounted(final JobSearch.Result result, final boolean de) {
        final StringBuilder a = new StringBuilder();
        final String employer = result.plan.employer();
        if (employer != null && result.mentions > 0) {
            a.append(de ? " " + result.mentions + (result.mentions == 1 ? " weitere Anzeige nennt " : " weitere Anzeigen nennen ") + employer
                    + ", ohne dass " + employer + " als Arbeitgeber erfasst ist; " + (result.mentions == 1 ? "sie ist" : "sie sind") + " nicht mitgezählt."
                    : " " + result.mentions + " more posting" + (result.mentions == 1 ? " names " : "s name ") + employer + " without " + employer
                    + " being recorded as the employer; not counted.");
        }
        if (result.pageOnly > 0) {
            a.append(de ? " Auf " + result.pageOnly + (result.pageOnly == 1 ? " Seite" : " Seiten") + " mit mehreren Anzeigen kommt das Thema vor, ohne"
                    + " dass es einer bestimmten Anzeige zugeordnet werden kann; " + (result.pageOnly == 1 ? "diese Seite ist" : "diese Seiten sind")
                    + " nicht mitgezählt."
                    : " " + result.pageOnly + " page" + (result.pageOnly == 1 ? "" : "s") + " with several postings name" + (result.pageOnly == 1 ? "s" : "")
                    + " the subject without any single posting showing it; not counted.");
        }
        return a.toString();
    }

    /**
     * The server's answer to a plain count question for job postings: the number of postings found in the scope (a lower
     * bound with its reason when the search could not finish), per subject when there are several, and the postings shown
     * with their numbers. No model writes it; every number is the search's.
     */
    public static String countOfJobs(final String question, final JobSearch.Result result, final List<RagContext.Source> shown,
            final ToolContext context) {
        final boolean de = german(question);
        final String subjects = String.join(", ", result.plan.criteria());
        final String employer = result.plan.employer();
        // "zu X" for subjects, "mit X als Arbeitgeber" for the employer of the question
        final String about = (subjects.isEmpty() ? "" : (de ? " zu " : " for ") + subjects)
                + (employer == null ? "" : (de ? " mit " + employer + " als Arbeitgeber" : " with " + employer + " as the employer"));
        final StringBuilder a = new StringBuilder();
        if (result.found == 0 && !result.lowerBound()) {
            a.append(de ? "In " + scope(context, true) + " wurde keine Stellenanzeige" + about + " gefunden."
                    : "No job posting" + about + " was found in " + scope(context, false) + ".");
            a.append(notCounted(result, de));
            return a.toString();
        }
        if (result.lowerBound()) {
            a.append(de ? "In " + scope(context, true) + " wurden mindestens **" + result.found + "** Stellenanzeigen" + about
                    + " gefunden. Die Zahl ist eine Untergrenze: " + reasons(result.gaps, true) + "."
                    : "At least **" + result.found + "** job postings" + about + " were found in " + scope(context, false)
                    + ". The number is a lower bound: " + reasons(result.gaps, false) + ".");
        } else {
            a.append(de ? "In " + scope(context, true) + " gibt es **" + result.found + "** " + (result.found == 1 ? "Stellenanzeige" : "Stellenanzeigen")
                    + about + ". Durchsucht wurden alle " + result.scanned + " Stellenanzeigen des Bestands; jede Ausschreibung ist einmal gezählt."
                    : "There " + (result.found == 1 ? "is **1** job posting" : "are **" + result.found + "** job postings") + about + " in "
                    + scope(context, false) + ". All " + result.scanned + " postings of the stock were searched; each posting is counted once.");
        }
        if (result.records > result.found) {
            a.append(de ? " " + result.records + " Datensätze passen; " + (result.records - result.found) + " davon " + (result.records - result.found == 1
                    ? "ist dieselbe Ausschreibung" : "sind dieselben Ausschreibungen") + " auf einer weiteren Seite oder ein zweiter Datensatz"
                    + " derselben Seite."
                    : " " + result.records + " records match; " + (result.records - result.found) + " of them repeat a posting on another page or"
                    + " as a second record of its page.");
        }
        a.append(notCounted(result, de));
        if (result.perSubject.size() > 1) {
            final List<String> per = new ArrayList<>();
            for (final Map.Entry<String, Integer> e : result.perSubject.entrySet()) per.add(e.getKey() + " " + e.getValue());
            a.append(de ? " Je Thema: " + String.join(", ", per) + " (eine Anzeige kann mehrere Themen zeigen)."
                    : " Per subject: " + String.join(", ", per) + " (a posting can show several subjects).");
        }
        if (!shown.isEmpty()) {
            a.append("\n\n").append(de ? (shown.size() < result.found ? "Angezeigt werden " + shown.size() + " davon:" : "Das sind:")
                    : (shown.size() < result.found ? shown.size() + " of them are shown:" : "They are:"));
            for (int i = 0; i < shown.size() && i < result.jobs.size(); i++) {
                final JobSearch.Job job = result.jobs.get(i);
                a.append("\n- ").append(RagContext.clean(job.title, 120));
                if (job.employer != null && !job.employer.isEmpty()) a.append(" – ").append(RagContext.clean(job.employer, 80));
                if (job.place != null && !job.place.isEmpty()) a.append(", ").append(RagContext.clean(job.place, 60));
                a.append(" [").append(shown.get(i).id).append(']');
            }
        }
        return a.toString();
    }

    /**
     * The server's answer to a plain count question for providers: the number of providers found in the scope, each once,
     * with a place how many are located there, serve it, only mention it or show no relation to it, and the providers shown.
     * When the search could not read the pages of every provider naming the place, the total stands on its own, the
     * numbers located and serving are lower bounds, and the providers whose relation is open are counted with the reason.
     */
    public static String countOfProviders(final String question, final ServiceSearch.Result result, final List<RagContext.Source> shown,
            final ToolContext context) {
        final boolean de = german(question);
        final String services = String.join(", ", result.plan.services);
        final String place = result.plan.place;
        final StringBuilder a = new StringBuilder();
        if (result.found == 0 && !result.lowerBound()) {
            return de ? "In " + scope(context, true) + " wurde kein Anbieter für " + services + " gefunden."
                    : "No provider of " + services + " was found in " + scope(context, false) + ".";
        }
        if (result.lowerBound()) {
            a.append(de ? "In " + scope(context, true) + " wurden mindestens **" + result.found + "** Anbieter für " + services
                    + " gefunden. Die Zahl ist eine Untergrenze: " + reasons(result.gaps, true) + "."
                    : "At least **" + result.found + "** providers of " + services + " were found in " + scope(context, false)
                    + ". The number is a lower bound: " + reasons(result.gaps, false) + ".");
        } else {
            a.append(de ? "In " + scope(context, true) + " gibt es **" + result.found + "** Anbieter für " + services + " (jeder Anbieter einmal gezählt)."
                    : "There are **" + result.found + "** providers of " + services + " in " + scope(context, false) + " (each provider counted once).");
        }
        if (place != null) {
            final int at = result.perPlace.getOrDefault(ServiceSearch.AT_PLACE, 0);
            final int serves = result.perPlace.getOrDefault(ServiceSearch.SERVES_PLACE, 0);
            final int open = result.perPlace.getOrDefault(ServiceSearch.UNDECIDED_PLACE, 0);
            final int mentions = result.perPlace.getOrDefault(ServiceSearch.MENTIONS_PLACE, 0);
            final int none = result.perPlace.getOrDefault(ServiceSearch.NO_PLACE, 0);
            if (!result.placeOpen()) {
                a.append(de ? " Davon mit belegtem Standort in " + place + ": **" + at + "**, mit " + place + " als Einsatzgebiet: **" + serves
                        + "**. " + mentions + " nennen " + place + " nur beiläufig (alle ihre Seiten zu " + place + " gelesen, kein Beleg für Standort"
                        + " oder Einsatzgebiet), " + none + " zeigen keinen Bezug zu " + place + "."
                        : " Of them located in " + place + " by evidence: **" + at + "**, serving " + place + ": **" + serves + "**. " + mentions
                        + " only mention " + place + " in passing (all their pages naming it read; no evidence of a location or service area), "
                        + none + " show no relation to " + place + ".");
            } else {
                // the count of providers stands; only the relation to the place is not determined for every one of them
                if (!result.lowerBound()) {
                    a.append(de ? " Diese Gesamtzahl ist vollständig; offen ist nur bei einem Teil der Ortsbezug."
                            : " This total is complete; only the relation to the place is open for some of them.");
                }
                a.append(de ? " Mit belegtem Standort in " + place + ": mindestens **" + at + "**, mit " + place + " als Einsatzgebiet: mindestens **"
                        + serves + "**."
                        : " Located in " + place + " by evidence: at least **" + at + "**, serving " + place + ": at least **" + serves + "**.");
                if (open > 0) {
                    a.append(de ? " Bei **" + open + "** Anbietern ist der Ortsbezug offen: ob sie in " + place + " ansässig oder dort tätig sind,"
                            + " ist nicht geprüft; eine bloße Nennung des Orts ist kein Beleg dafür und auch nicht dagegen."
                            : " For **" + open + "** providers the relation is open: whether they are located in or serve " + place + " is not"
                            + " determined; a mere mention of the place is no evidence either way.");
                }
                a.append(de ? " Grund: " + placeReasons(result, true) + "." : " Reason: " + placeReasons(result, false) + ".");
                a.append(de ? " " + mentions + " nennen " + place + " nur beiläufig (alle ihre Seiten zu " + place + " gelesen), " + none
                        + " zeigen keinen Bezug zu " + place + "."
                        : " " + mentions + " only mention " + place + " in passing (all their pages naming it read), " + none
                        + " show no relation to " + place + ".");
            }
        }
        if (!shown.isEmpty()) {
            a.append("\n\n").append(de ? (shown.size() < result.found ? "Angezeigt werden " + shown.size() + " davon:" : "Das sind:")
                    : (shown.size() < result.found ? shown.size() + " of them are shown:" : "They are:"));
            for (int i = 0; i < shown.size() && i < result.providers.size(); i++) {
                final ServiceSearch.Provider p = result.providers.get(i);
                a.append("\n- ").append(RagContext.clean(p.name, 120));
                if (place != null) {
                    final String source = p.placeSource == null || p.placeSource.equals(p.url()) ? ""
                            : (de ? ", Beleg für den Ort: " : ", evidence of the place: ") + p.placeSource;
                    a.append(" (").append(ServiceSearch.AT_PLACE.equals(p.place) ? (de ? "Standort in " + place : "located in " + place) + source
                            : ServiceSearch.SERVES_PLACE.equals(p.place) ? (de ? "Einsatzgebiet " + place : "serves " + place) + source
                            : ServiceSearch.UNDECIDED_PLACE.equals(p.place) ? (de ? "Ortsbezug offen" : "relation to " + place + " open")
                            : ServiceSearch.MENTIONS_PLACE.equals(p.place) ? (de ? "nennt " + place + " nur" : "only mentions " + place)
                            : (de ? "kein Bezug zu " + place : "no relation to " + place)).append(')');
                }
                a.append(" [").append(shown.get(i).id).append(']');
            }
        }
        return a.toString();
    }
}
