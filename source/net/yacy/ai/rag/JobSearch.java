/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai.rag;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The job search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 12). For a question for job postings (their number, a
 * list of them, or any job question whose automatic search matched only weakly) the server searches the job postings of
 * the knowledge graph ({@code type=job}) before the model answers, whether or not the model would call a tool; the tool
 * {@code job_search} runs the same search, and within one request the same search is made once.
 * <ul>
 * <li>Only with the existing release of {@code kg_search} and within the scope of the chat request ({@link ToolContext}:
 * the session's collection, read with its viewer). Nothing outside it is read.</li>
 * <li>The whole released stock, the matching postings first: the title and the statements of every posting of the scope
 * ({@link KgReader#entityScan}, no evidence text), and which of the postings' pages match in the direct index (addresses
 * only). A posting counts by its title, a fact or the text of its own page; a company page is no posting. On a page of
 * several postings the text counts for the posting whose part of the page shows the subject ({@link #attribute}); a hit
 * of the page that belongs to no posting raises no count ({@link Result#pageOnly}). With an employer in the question
 * ("Stellenanzeigen von X") only the postings the graph records X as the employer of count ({@link Plan#employer}). The
 * matching postings are determined, deduplicated and counted before the answer's limit of {@link #MAX_JOBS}; only those
 * shown are read in full. The result says how many were found and shown, and whether the count is only a lower bound, and
 * why ({@link Result#gaps}: the time of {@link #MAX_MILLIS} ms, a failed read, or no direct index).</li>
 * <li>Each posting shown goes to the answer as a numbered source with the page it was read from, its employer, the
 * dates it states, the subjects of the question it shows and, as a PARTIAL MATCH, those it does not. The stock of the
 * graph counts as current: a missing date neither excludes nor ranks a posting.</li>
 * </ul>
 */
public final class JobSearch {

    public static final int MAX_VARIANTS = 6;
    /** postings shown in an answer; the ones found are determined before this limit */
    public static final int MAX_JOBS = 6;
    /** time of one search (name index, all postings of the scope, text) */
    public static final long MAX_MILLIS = 6000L;
    /** postings of one read of the scan (it reads to the end of the stock or of the time), and of a read that timed out */
    static final int SCAN_CHUNK = 2000;
    static final int SCAN_CHUNK_MIN = 100;
    /** posting pages of one question to the direct index */
    static final int TEXT_BATCH = 1000;
    /** characters of the excerpt of a page that shows a subject by its text */
    static final int EXCERPT_CHARS = 200;
    /** characters after a posting's title on a page of several postings that count as its part of the page */
    static final int SECTION_CHARS = 1500;
    /** pages of several postings read with their text in one search, and in one question to the index */
    static final int SHARED_PAGES = 60;
    static final int SHARED_BATCH = 20;
    /** pages of one ranked text search (only without a direct index) */
    static final int TEXT_HITS = 50;
    static final int MAX_CHARS = 2600;
    /** for {@link #format}: every posting shown, whatever its length (the server's own answer needs no model context) */
    public static final int ALL_SHOWN = Integer.MAX_VALUE;
    static final int MAX_VALUE = 120;

    /** words of a question about jobs (folded) */
    private static final Pattern INTENT = Pattern.compile("(?<![\\p{L}])(?:stelle|stellen|stellenanzeige\\p{L}*|stellenangebot\\p{L}*"
            + "|stellenausschreibung\\p{L}*|stellenmarkt|stellenbezeichnung\\p{L}*|job|jobs|jobangebot\\p{L}*|jobanzeige\\p{L}*|jobsuche"
            + "|karriere\\p{L}*|vakanz\\p{L}*|ausschreibung\\p{L}*|arbeitgeber\\p{L}*|bewerb\\p{L}*|vacanc\\p{L}*|hiring|career\\p{L}*"
            + "|recruit\\p{L}*|job\\s*posting\\p{L}*|position\\p{L}*)(?![\\p{L}])|\\(m\\s*/\\s*w\\s*/\\s*d\\)");
    private static final String[] INTENT_STEMS = {"stelle", "stellen", "job", "karriere", "vakanz", "ausschreibung", "arbeitgeb",
            "bewerb", "vacanc", "hiring", "career", "recruit", "position", "posting", "anzeige", "angebot", "bezeichnung",
            "veroffentlich", "datum", "aktuell", "offen", "unternehm", "firma", "firmen", "beratungsunternehm", "industrieunternehm",
            "titel", "nenn", "zeig", "markt", "analyse", "marktanalys", "angab", "fehl", "ausdrucklich", "beschaftig", "einsetz"};
    private static final Set<String> ROLES = Set.of("berater", "beraterin", "consultant", "entwickler", "entwicklerin", "developer",
            "architekt", "architektin", "architect", "ingenieur", "ingenieurin", "engineer", "manager", "administrator", "analyst",
            "projektleiter", "techniker", "werkstudent", "trainee", "praktikant", "praktikum", "ausbildung");

    private JobSearch() {
    }

    // ------------------------------------------------------------------------------------------ the plan

    /** One subject of the question: how a posting shows it, and the name searches for it. */
    static final class Criterion {
        final String label;
        final Predicate<String> shownBy;
        final List<Variant> variants;
        /** where the question names it (folded question; for the order of the subjects) */
        java.util.function.ToIntFunction<String> at = f -> Integer.MAX_VALUE;
        /** a configured subject group: how a text names it */
        Pattern pattern = null;

        Criterion(final String label, final Predicate<String> shownBy, final List<Variant> variants) {
            this.label = label;
            this.shownBy = shownBy;
            this.variants = variants;
        }
    }

    /** One name search: words of a job title. */
    static final class Variant {
        final String text;
        final boolean wholeWords;
        /** the posting's title must show this subject (null: every hit counts) */
        final Criterion filter;

        Variant(final String text, final boolean wholeWords, final Criterion filter) {
            this.text = text;
            this.wholeWords = wholeWords;
            this.filter = filter;
        }

        @Override
        public boolean equals(final Object o) {
            return o instanceof Variant && ((Variant) o).text.equalsIgnoreCase(this.text);
        }

        @Override
        public int hashCode() {
            return this.text.toLowerCase(Locale.ROOT).hashCode();
        }
    }

    private static final String START = "(?<![\\p{L}\\p{N}])";
    private static final String END = "(?![\\p{L}\\p{N}])";

    /** Setting: subject groups searched each on its own, "Label: synonym, synonym; Label: synonym" (empty: none). */
    public static final String SUBJECTS_KEY = "scoutro.chat.jobs.subjects";

    /**
     * A term as a pattern on folded text: a short term (up to three letters) only as a whole word, one of up to five at the
     * start of a word, a longer one also within a compound ("Robotik" in "Prozessrobotik").
     */
    private static String termPattern(final String term) {
        final String folded = RagQuery.fold(term).trim();
        if (folded.isEmpty()) return null;
        final String quoted = Pattern.quote(folded).replace(" ", "\\E\\s+\\Q");
        return folded.length() <= 3 ? START + quoted + END : folded.length() <= 5 ? START + quoted : quoted;
    }

    /**
     * The configured subject groups ({@link #SUBJECTS_KEY}): each a subject of its own with its synonyms, shown by any of
     * them and searched by each; a short name (up to three letters) only as a whole word.
     */
    static List<Criterion> groups(final String setting) {
        final List<Criterion> out = new ArrayList<>();
        if (setting == null) return out;
        for (final String group : setting.split(";")) {
            final int colon = group.indexOf(':');
            final String label = (colon < 0 ? group : group.substring(0, colon)).trim();
            if (label.isEmpty() || out.size() >= 12) continue;
            final List<String> terms = new ArrayList<>();
            terms.add(label);
            if (colon >= 0) {
                for (final String t : group.substring(colon + 1).split(",")) {
                    final String term = t.trim();
                    if (!term.isEmpty() && terms.size() < 6) terms.add(term);
                }
            }
            final StringBuilder regex = new StringBuilder();
            for (final String t : terms) {
                final String one = termPattern(t);
                if (one != null) regex.append(regex.length() == 0 ? "" : "|").append(one);
            }
            if (regex.length() == 0) continue;
            final List<Variant> variants = new ArrayList<>();
            final Pattern shown = Pattern.compile(regex.toString());
            final Criterion c = new Criterion(label, f -> shown.matcher(f).find(), variants);
            c.pattern = shown;
            c.at = f -> {
                final java.util.regex.Matcher m = shown.matcher(f);
                return m.find() ? m.start() : Integer.MAX_VALUE;
            };
            for (final String t : terms) variants.add(new Variant(t, RagQuery.fold(t).trim().length() <= 3, c));
            out.add(c);
        }
        return out;
    }

    private static List<Criterion> groups() {
        return groups(net.yacy.ai.ToolProvider.setting(SUBJECTS_KEY, ""));
    }

    /** words of a question for all results or their number (folded) */
    private static final Pattern ALL = Pattern.compile("(?<![\\p{L}])(?:alle|allen|aller|samtliche\\p{L}*|gesamt\\p{L}*|vollstandig\\p{L}*|komplett\\p{L}*"
            + "|wie\\s+viele|wieviele|anzahl|zahl\\s+der|all|every|complete|total|how\\s+many|number\\s+of)(?![\\p{L}])");

    /** Whether a question asks for all results or their number ("alle Stellen", "wie viele Anbieter"): no first hits suffice. */
    public static boolean wantsAll(final String text) {
        return text != null && ALL.matcher(RagQuery.fold(text)).find();
    }

    /** Whether a question asks for job postings. */
    public static boolean intent(final String text) {
        return text != null && INTENT.matcher(RagQuery.fold(text)).find();
    }

    /**
     * Words of a question for all results or their number (folded, whole words): never a subject. "Wie viele Stellen zu X"
     * and "Zeige alle Stellen zu X" have the same subjects; a subject "viele" would match "vielseitig" in a posting's text.
     */
    private static final Set<String> COUNT_WORDS = Set.of("wie", "viel", "viele", "vielen", "vieler", "wieviel", "wieviele", "anzahl",
            "zahl", "gesamt", "gesamte", "gesamten", "insgesamt", "alle", "allen", "aller", "alles", "samtliche", "samtlichen",
            "samtlicher", "vollstandig", "vollstandige", "vollstandigen", "komplett", "komplette", "kompletten", "liste", "auflisten",
            "aufzahlen", "how", "many", "much", "number", "total", "all", "every", "complete", "list", "count");

    /** Whether a word (folded) only asks for all results or their number. */
    public static boolean countWord(final String folded) {
        return folded != null && COUNT_WORDS.contains(folded.trim());
    }

    /** A stem of a word about jobs or about the answer, never a subject (folded). */
    static boolean intentWord(final String stem) {
        for (final String p : INTENT_STEMS) if (stem.startsWith(p)) return true;
        return false;
    }

    /**
     * Whether the automatic search answers a question about jobs only weakly: it matched weakly or not at all (stage
     * {@code any}, {@code none}, {@code no-terms}), or none of its hits is a job page by its title or address (a company's
     * home page matching the subjects is no posting).
     */
    public static boolean weak(final RagRetriever.Result result) {
        if (result == null || result.selected.isEmpty() || "any".equals(result.stage) || "none".equals(result.stage)
                || "no-terms".equals(result.stage)) {
            return true;
        }
        for (final RagRetriever.Scored s : result.selected) if (jobPage(s.candidate)) return false;
        return true;
    }

    /** A page that is a job posting or a list of them by its title or address. */
    static boolean jobPage(final RagCandidate page) {
        return intent(page.title + " " + page.url.replaceAll("[/._?=&-]+", " "));
    }

    /** The subjects and the name searches of a goal. */
    public static final class Plan {
        final List<Criterion> criteria;
        final List<Variant> variants;
        /**
         * the employer the question asks for ("Stellenanzeigen von X"), as the question writes it, or null: a posting then
         * counts only when the graph records X as its employer; a posting that names X elsewhere is a mention, not counted
         */
        String employer;
        /** how an employer's name shows {@link #employer} (folded, whole words) */
        Pattern employerPattern;
        /** {@link #employer} as a subject, for the postings that only mention it */
        Criterion employerTopic;

        Plan(final List<Criterion> criteria, final List<Variant> variants) {
            this.criteria = criteria;
            this.variants = variants;
        }

        public String employer() {
            return this.employer;
        }

        public List<String> criteria() {
            final List<String> out = new ArrayList<>();
            for (final Criterion c : this.criteria) out.add(c.label);
            return out;
        }

        public List<String> variants() {
            final List<String> out = new ArrayList<>();
            for (final Variant v : this.variants) out.add(v.text);
            return out;
        }
    }

    /**
     * The employer of a job question: a name after "von", "bei", "at", "by" or "from" ("Stellenanzeigen von Kranich",
     * "Stellen bei der Firma Kranich Consulting"). A name starts with a capital or a digit and is no word about jobs, the
     * count or a role ("bei Beratungsunternehmen" names none).
     */
    private static final Pattern EMPLOYER = Pattern.compile("(?<![\\p{L}\\p{N}])(?i:von|vom|bei|beim|at|by|from)\\s+(?:(?i:der|dem|den|the)\\s+)?"
            + "(?:(?i:firma|unternehmen|arbeitgeber|company|employer)\\s+)?([\\p{Lu}\\p{N}][\\p{L}\\p{N}&.+\\-]*"
            + "(?:\\s+[\\p{Lu}\\p{N}&][\\p{L}\\p{N}&.+\\-]*){0,3})");

    /** The span of the employer's name in a goal ({start, end} of the whole phrase, {nameStart, nameEnd}), or null. */
    static int[] employerSpan(final String goal) {
        if (goal == null) return null;
        final java.util.regex.Matcher m = EMPLOYER.matcher(goal);
        while (m.find()) {
            int end = m.end(1);
            while (end > m.start(1) && ".-&+".indexOf(goal.charAt(end - 1)) >= 0) end--;
            // the name ends before a word about jobs, the count or the answer ("von Kranich Stellen")
            final String[] words = goal.substring(m.start(1), end).split("\\s+");
            int keep = 0;
            int at = m.start(1);
            int nameEnd = at;
            for (final String w : words) {
                final String f = RagQuery.fold(w.replaceAll("[^\\p{L}\\p{N}]+", " ").trim());
                boolean generic = f.isEmpty() || countWord(f) || ROLES.contains(f) || RagFollowUp.noTopic(f);
                for (final String part : f.split(" ")) generic |= intentWord(part);
                if (generic) break;
                keep++;
                nameEnd = goal.indexOf(w, at) + w.length();
                at = nameEnd;
            }
            if (keep > 0) return new int[] {m.start(), nameEnd, m.start(1), nameEnd};
        }
        return null;
    }

    /** The pattern of an employer's name on folded text: its words side by side as whole words. */
    static Pattern employerPattern(final String name) {
        final String words = RagQuery.fold(name == null ? "" : name).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
        if (words.isEmpty()) return null;
        return Pattern.compile(START + Pattern.quote(words).replace(" ", "\\E[^\\p{L}\\p{N}]+\\Q") + END);
    }

    public static Plan plan(final String goal) {
        return plan(goal, null);
    }

    /**
     * The plan of a goal; {@code employer} names the employer when the caller knows it (the tool's argument), else it is
     * read from the goal ({@link #employerSpan}). The employer's name is no subject: "Stellenanzeigen von X" asks for the
     * postings X is the employer of, "Stellenanzeigen zu X" for those that show X.
     */
    public static Plan plan(final String question, final String employer) {
        String named = employer == null || employer.trim().isEmpty() ? null : employer.trim();
        String goal = question == null ? "" : question;
        final int[] span = employerSpan(goal);
        if (span != null) {
            if (named == null) named = goal.substring(span[2], span[3]);
            goal = goal.substring(0, span[0]) + " " + goal.substring(span[1]);
        }
        final Plan plan = topics(goal);
        final Pattern pattern = employerPattern(named);
        if (pattern != null) {
            plan.employer = named;
            plan.employerPattern = pattern;
            final List<Variant> mine = new ArrayList<>();
            final Criterion c = new Criterion(named, f -> pattern.matcher(f).find(), mine);
            mine.add(new Variant(named, true, c));
            plan.employerTopic = c;
        }
        return plan;
    }

    private static Plan topics(final String goal) {
        final String folded = RagQuery.fold(goal == null ? "" : goal);
        final List<Criterion> criteria = new ArrayList<>();
        String rest = folded; // the question without the words of its subject groups
        for (final Criterion c : groups()) {
            if (!c.shownBy.test(folded)) continue;
            criteria.add(c);
            rest = c.pattern.matcher(rest).replaceAll(" ");
        }
        // any other subject: its strongest words (no words about jobs, sources or the search itself), at most 3
        int topics = 0;
        // without words about jobs or the answer first: a long question keeps only its strongest words (RagQuery.MAX_TERMS)
        final StringBuilder own = new StringBuilder();
        for (final String w : (goal == null ? "" : goal).split("\\s+")) {
            final String f = RagQuery.fold(w.replaceAll("[^\\p{L}\\p{N}]+", ""));
            if (f.isEmpty() || intentWord(f) || countWord(f) || RagFollowUp.noTopic(f)) continue;
            own.append(own.length() == 0 ? "" : " ").append(w);
        }
        for (final RagQuery.Term t : RagFollowUp.topics(own.toString())) {
            if (topics >= 3) break;
            if (t.phrase || t.weak) continue;
            final String folded1 = RagQuery.fold(t.text);
            if (intentWord(folded1) || countWord(folded1) || ROLES.contains(folded1)) continue; // about jobs, the count, or a role
            if (!java.util.regex.Pattern.compile(START + Pattern.quote(folded1) + END).matcher(rest).find()) continue; // a word of a group
            final RagQuery.Term term = t;
            final List<Variant> mine = new ArrayList<>();
            final String word = RagQuery.fold(t.text);
            String label = t.text; // as the question writes it
            for (final String w : (goal == null ? "" : goal).split("[^\\p{L}\\p{N}]+")) {
                if (RagQuery.fold(w).equals(word)) {
                    label = w;
                    break;
                }
            }
            final Criterion c = new Criterion(label, f -> RagCandidate.matches(term, f), mine);
            c.at = f -> {
                final int i = f.indexOf(word);
                return i < 0 ? Integer.MAX_VALUE : i;
            };
            mine.add(new Variant(label, word.length() <= 3, c));
            criteria.add(c);
            topics++;
        }
        criteria.sort(java.util.Comparator.comparingInt(c -> c.at.applyAsInt(folded))); // in the order of the question
        String role = null;
        for (final String word : folded.split("[^\\p{L}]+")) {
            if (ROLES.contains(word)) {
                role = word;
                break;
            }
        }
        final Set<Variant> variants = new LinkedHashSet<>();
        if (criteria.size() > 1) {
            final StringBuilder all = new StringBuilder();
            for (final Criterion c : criteria) all.append(all.length() == 0 ? "" : " ").append(c.variants.get(0).text);
            variants.add(new Variant(all.toString(), true, null));
        }
        final String ownRole = role;
        if (ownRole != null && !criteria.isEmpty() && criteria.stream().noneMatch(c -> c.shownBy.test(ownRole))) {
            variants.add(new Variant(criteria.get(0).variants.get(0).text + " " + ownRole, false, criteria.get(0)));
        }
        for (final Criterion c : criteria) variants.add(c.variants.get(0));
        // the question's own compounds of a subject ("Prozessrobotik"): a name search matches only the start of a word
        for (final String word : (goal == null ? "" : goal).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() < 6) continue;
            for (final Criterion c : criteria) {
                if (!c.label.equalsIgnoreCase(word) && c.shownBy.test(RagQuery.fold(word))) variants.add(new Variant(word, false, c));
            }
        }
        for (final Criterion c : criteria) for (final Variant v : c.variants) variants.add(v);
        if (ownRole != null && criteria.isEmpty()) variants.add(new Variant(ownRole, false, null));
        final List<Variant> list = new ArrayList<>(variants);
        return new Plan(criteria, list.subList(0, Math.min(MAX_VARIANTS, list.size())));
    }

    // ------------------------------------------------------------------------------------------ the search

    /** One posting found. */
    /** How a posting shows a subject: in its title, in its other facts or excerpts, or only in its page's indexed text. */
    static final int BY_TITLE = 3;
    static final int BY_FACT = 2;
    static final int BY_TEXT = 1;

    public static final class Job {
        public final String id;
        public final String title;
        String employer;
        String url;
        String collection;
        String loadedAt;
        String employmentType;
        String place;
        String datePosted;
        String validThrough;
        /** a short excerpt of its page for a subject it shows by the text only */
        String excerpt;
        final List<String> met = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        /** the strongest way it shows each subject it shows ({@link #BY_TITLE}, {@link #BY_FACT}, {@link #BY_TEXT}) */
        final Map<String, Integer> how = new LinkedHashMap<>();
        int order;

        Job(final String id, final String title) {
            this.id = id;
            this.title = title;
        }

        public boolean partial() {
            return !this.missing.isEmpty();
        }

        public String url() {
            return this.url;
        }

        int strength() {
            int sum = 0;
            for (final int v : this.how.values()) sum += v;
            return sum;
        }
    }

    public static final class Result {
        public final Plan plan;
        /** the postings shown (at most {@link #MAX_JOBS}) */
        public final List<Job> jobs;
        public final List<String> searched;
        public final boolean timedOut;
        public final String error;
        public final long millis;
        /** the matching postings determined in the scope, without duplicates (before the limit of {@link #jobs}) */
        public final int found;
        /** per subject of the question: how many of {@link #found} show it */
        public final Map<String, Integer> perSubject;
        /** the postings of the scope read (all of them unless {@link #complete} is false) */
        public final int scanned;
        /**
         * whether the whole released stock was searched: false when the time, the number of postings read or of pages of
         * the text search ran out; {@link #found} is then a lower bound
         */
        public final boolean complete;
        /** why the count is only a lower bound (empty when complete) */
        public final List<String> gaps;
        /** the matching posting records before duplicates were counted once ({@link #found} is the number of postings) */
        public int records;
        /** the time of each step of the search in milliseconds: scan, names, facts, text, selection, evidence */
        public Map<String, Long> phases = Collections.emptyMap();
        /** the graph IDs of all postings found (each once), not only of those shown */
        public List<String> ids = Collections.emptyList();
        /**
         * pages of several postings whose text names a subject without any posting of the page showing it (the subject is
         * outside every posting's part of the page, or the page could not be read): a hit of the page, of no posting; never
         * part of {@link #found}
         */
        public int pageOnly;
        /** with an employer in the question: the postings (each once) that name it without the graph recording it as their employer */
        public int mentions;

        public Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis) {
            this(plan, jobs, searched, timedOut, error, millis, jobs.size(), new LinkedHashMap<>(), jobs.size(), !timedOut && error == null,
                    new ArrayList<>());
            this.records = jobs.size();
        }

        Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis,
                final int found, final Map<String, Integer> perSubject, final int scanned, final boolean complete, final List<String> gaps) {
            this.gaps = Collections.unmodifiableList(gaps);
            this.plan = plan;
            this.jobs = Collections.unmodifiableList(jobs);
            this.searched = Collections.unmodifiableList(searched);
            this.timedOut = timedOut;
            this.error = error;
            this.millis = millis;
            this.found = found;
            this.perSubject = Collections.unmodifiableMap(perSubject);
            this.scanned = scanned;
            this.complete = complete;
        }

        /** whether {@link #found} is only a lower bound */
        public boolean lowerBound() {
            return !this.complete;
        }
    }

    /**
     * Whether the search may run for this request: the release of the tool {@code job_search} (which follows
     * {@code kg_search} while not set on its own) within the request's scope.
     */
    public static boolean released(final boolean indexTools, final ToolContext context) {
        return context != null && context.graph && net.yacy.ai.ToolProvider.allowedCallsPerTurn("job_search", indexTools, context) > 0;
    }

    /** The result of this search computed earlier in this request (the same subjects, searches and scope), or null. */
    public static Result computed(final ToolContext context, final Plan plan) {
        final Object known = context == null ? null : context.remembered(key(plan));
        return known instanceof Result ? (Result) known : null;
    }

    private static String key(final Plan plan) {
        return "jobs|" + plan.criteria() + "|" + plan.variants() + "|" + (plan.employer == null ? "" : RagQuery.fold(plan.employer));
    }

    /**
     * The search of a request: computed once per request for the same subjects, searches and scope; the automatic search of
     * the answer and a call of {@code job_search} for the same share the result.
     */
    public static Result run(final ToolContext context, final Plan plan) {
        final Result known = computed(context, plan);
        if (known != null) return known;
        final Result result = run(context, plan, System::currentTimeMillis);
        if (context != null) context.remember(key(plan), result);
        return result;
    }

    /** One posting of the scan, as far as counting needs it: what it shows and how, and what tells it from another. */
    private static final class Posting {
        final KgReader.EntityRef ref;
        final int[] how;
        /** per subject: the statements of the scan that show it, until they are checked for the viewer */
        final List<List<Long>> facts = new ArrayList<>();
        /** the statements that record the employer of the question as this posting's employer, until they are checked */
        final List<Long> employerFacts = new ArrayList<>();
        /** the graph records the employer of the question as this posting's employer (a current statement the viewer sees) */
        boolean employerHit;
        String employer;
        String place;
        /** its part of a page of several postings that shows a subject */
        String excerpt;
        private Set<String> words;

        /** the words of its title that tell postings apart */
        Set<String> titleWords() {
            if (this.words == null) this.words = new LinkedHashSet<>(JobSearch.titleWords(this.ref.name));
            return this.words;
        }

        Posting(final KgReader.EntityRef ref, final int subjects) {
            this.ref = ref;
            this.how = new int[subjects];
            for (int i = 0; i < subjects; i++) this.facts.add(new ArrayList<>());
        }
    }

    /**
     * The postings of the request's scope that match the plan, determined and counted before the output is limited (the
     * automatic job search and the tool {@code job_search} both run this):
     * <ol>
     * <li><b>scan</b>: every posting of the scope the viewer sees with a current page, in chunks of {@link #SCAN_CHUNK} by
     * the graph's indexes ({@link KgReader#entityScan}): its title and its statements (employer, place, field, signals), a
     * related entity by its name ({@link KgReader#entityNames}). No evidence text is read. A posting shows a subject by its
     * title or by one of its facts;</li>
     * <li><b>facts</b>: the statements that alone show a subject are checked for the viewer
     * ({@link KgReader#currentStatements}); only those, not every statement of every posting;</li>
     * <li><b>text</b>: per subject, the pages of the postings that do not show it yet are asked in the direct index for the
     * subject's words: which addresses match ({@link ToolContext#indexMatching}). A page counts only as the page of a
     * posting of the scope, never a company page on its own. A page of one posting is that posting; a page of several
     * postings is read (at most {@link #SHARED_PAGES}) and the hit goes to the posting whose part of the page shows the
     * subject ({@link #attribute}), else to no posting ({@link Result#pageOnly});</li>
     * <li><b>selection</b>: with an employer in the question only its postings; duplicates count once (one title of one
     * employer at one place over its pages, and the records of one page whose titles are one title; different postings of
     * one address stay apart); the postings found are ranked by the subjects they show and how, the shown ones keep one of
     * every subject that has one;</li>
     * <li><b>evidence</b>: only the postings shown are read in full ({@link KgReader#jobDigests}), and the page of a posting
     * that shows a subject by its text only gives a short excerpt.</li>
     * </ol>
     * Every step checks the time budget; when it runs out the count is a lower bound with its reason. Dates neither
     * exclude nor rank: the stock of the graph counts as current.
     */
    static Result run(final ToolContext context, final Plan plan, final LongSupplier clock) {
        return run(context, plan, clock, MAX_MILLIS);
    }

    static Result run(final ToolContext context, final Plan plan, final LongSupplier clock, final long budget) {
        final long start = clock.getAsLong();
        final List<String> searched = new ArrayList<>();
        final List<String> gaps = new ArrayList<>(); // why the count is only a lower bound
        final Map<String, Long> phases = new LinkedHashMap<>();
        final List<Posting> postings = new ArrayList<>();
        final int subjects = plan.criteria.size();
        // every subject, and after them the employer of the question as a subject (for the postings that only mention it)
        final List<Criterion> all = new ArrayList<>(plan.criteria);
        if (plan.employerTopic != null) all.add(plan.employerTopic);
        final int slots = all.size();
        int pageOnly = 0;
        boolean timedOut = false;
        String error = null;
        int scanned = 0;
        KgReader reader = null;
        Viewer viewer = null;
        long mark = System.nanoTime();
        try {
            reader = context.reader();
            if (reader == null) return new Result(plan, new ArrayList<>(), searched, false, "unavailable", 0L);
            viewer = context.viewer(reader);
            // 1. the scan: titles and statements of every posting of the scope, a related entity by its name
            for (final Variant v : plan.variants) if (!searched.contains(v.text)) searched.add(v.text);
            final Map<Long, String> names = new java.util.HashMap<>();
            long namesNanos = 0L;
            long after = 0L;
            boolean more = true;
            int chunk = SCAN_CHUNK;
            while (more) {
                if (clock.getAsLong() - start > budget) {
                    timedOut = true;
                    gaps.add("time budget: " + scanned + " postings of the scope read, not all");
                    break;
                }
                final KgReader.Scan scan;
                try {
                    scan = context.reader().entityScan(viewer, Vocabulary.JOB, after, chunk);
                } catch (final net.yacy.scoutro.knowledge.KgException e) {
                    // a chunk that does not fit one read (a cold or busy store): smaller chunks, the same place
                    if (!net.yacy.scoutro.knowledge.KgException.READ_TIMEOUT.equals(e.code()) || chunk <= SCAN_CHUNK_MIN) throw e;
                    chunk = Math.max(SCAN_CHUNK_MIN, chunk / 4);
                    continue;
                }
                after = scan.last;
                more = scan.more;
                scanned += scan.entities.size();
                final Set<Long> related = new LinkedHashSet<>();
                for (final KgReader.FactRow f : scan.facts) if (f.object > 0L && !names.containsKey(f.object)) related.add(f.object);
                if (!related.isEmpty()) {
                    final long n0 = System.nanoTime();
                    final Map<Long, String> named = context.reader().entityNames(viewer, related);
                    for (final Long row : related) names.put(row, named.get(row)); // null: no name the viewer sees
                    namesNanos += System.nanoTime() - n0;
                }
                final Map<Long, Posting> read = new LinkedHashMap<>();
                for (final KgReader.EntityRef ref : scan.entities) {
                    final Posting posting = new Posting(ref, slots);
                    final String title = RagQuery.fold(ref.name);
                    for (int i = 0; i < slots; i++) if (all.get(i).shownBy.test(title)) posting.how[i] = BY_TITLE;
                    read.put(ref.rowid, posting);
                }
                for (final KgReader.FactRow f : scan.facts) {
                    final Posting posting = read.get(f.subject);
                    if (posting == null || Vocabulary.NAME.equals(f.predicate)) continue;
                    final String value = f.object > 0L ? names.get(f.object) : f.value;
                    if (value == null || value.isEmpty()) continue;
                    if (Vocabulary.HIRING_ORGANIZATION.equals(f.predicate) && posting.employer == null) posting.employer = value;
                    if (Vocabulary.JOB_LOCATION.equals(f.predicate) && posting.place == null) posting.place = value;
                    if (slots == 0) continue;
                    final String folded = RagQuery.fold(value);
                    // the employer of the question: only the statement of the posting's employer shows it
                    final boolean employerFact = plan.employerPattern != null && Vocabulary.HIRING_ORGANIZATION.equals(f.predicate)
                            && plan.employerPattern.matcher(folded).find();
                    if (employerFact) posting.employerFacts.add(f.statement);
                    for (int i = 0; i < slots; i++) {
                        if (employerFact && i >= subjects) continue; // its employer is no mere mention of the employer
                        if (posting.how[i] < BY_TITLE && all.get(i).shownBy.test(folded)) posting.facts.get(i).add(f.statement);
                    }
                }
                postings.addAll(read.values());
            }
            phases.put("scan", (System.nanoTime() - mark - namesNanos) / 1_000_000L);
            phases.put("names", namesNanos / 1_000_000L);
            mark = System.nanoTime();
            // 2. the facts that alone show a subject: checked for the viewer (visible, with a current source)
            final Set<Long> candidates = new LinkedHashSet<>();
            for (final Posting p : postings) {
                for (int i = 0; i < slots; i++) if (p.how[i] < BY_TITLE) candidates.addAll(p.facts.get(i));
                candidates.addAll(p.employerFacts);
            }
            if (!candidates.isEmpty()) {
                if (clock.getAsLong() - start > budget) {
                    timedOut = true;
                    gaps.add("time budget: the facts of the postings not checked (" + candidates.size() + " statements)");
                } else {
                    final Set<Long> current = context.reader().currentStatements(viewer, candidates);
                    for (final Posting p : postings) {
                        for (final Long statement : p.employerFacts) p.employerHit |= current.contains(statement);
                        for (int i = 0; i < slots; i++) {
                            if (p.how[i] >= BY_TITLE) continue;
                            for (final Long statement : p.facts.get(i)) {
                                if (current.contains(statement)) {
                                    p.how[i] = BY_FACT;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            phases.put("facts", (System.nanoTime() - mark) / 1_000_000L);
            mark = System.nanoTime();
            // 3. the indexed text of the postings' own pages: which of the pages that do not show a subject yet match its words.
            // A page of one posting is that posting. On a page of several postings the text counts for the posting whose part
            // of the page shows the subject (the text after its title, up to the next posting's title); what the page says
            // outside every posting's part is a hit of the page and of no posting.
            if (slots > 0 && !timedOut) {
                final Map<String, List<Posting>> byPage = new LinkedHashMap<>();
                for (final Posting p : postings) byPage.computeIfAbsent(pageKey(p.ref.url), k -> new ArrayList<>()).add(p);
                int sharedRead = 0;
                if (context.hasIndex()) {
                    text:
                    for (int i = 0; i < slots; i++) {
                        final Criterion c = all.get(i);
                        final Map<String, String> urls = new LinkedHashMap<>(); // page -> its address, of the pages with a posting to decide
                        for (final Posting p : postings) if (p.how[i] == 0) urls.putIfAbsent(pageKey(p.ref.url), p.ref.url);
                        final Map<String, String> shared = new LinkedHashMap<>(); // matching pages of several postings, with the words that matched
                        for (final Variant v : c.variants) {
                            if (v.filter != c) continue; // a subject's own words; the joint variants are no subject of their own
                            final List<String> words = words(v.text);
                            if (words.isEmpty() || urls.isEmpty()) continue;
                            searched.add("index: " + v.text + " (" + urls.size() + " posting pages)");
                            final List<String> list = new ArrayList<>(urls.values());
                            for (int k = 0; k < list.size(); k += TEXT_BATCH) {
                                if (clock.getAsLong() - start > budget) {
                                    timedOut = true;
                                    gaps.add("time budget: the text of " + (list.size() - k) + " posting pages not searched for " + v.text);
                                    break text;
                                }
                                for (final String url : context.indexMatching(words, words.size() > 1, list.subList(k, Math.min(list.size(), k + TEXT_BATCH)))) {
                                    final String page = pageKey(url);
                                    final List<Posting> on = byPage.get(page);
                                    if (on == null) continue;
                                    if (onePosting(on)) {
                                        for (final Posting p : on) if (p.how[i] == 0) p.how[i] = BY_TEXT;
                                    } else {
                                        shared.putIfAbsent(page, v.text);
                                    }
                                }
                            }
                        }
                        // the pages of several postings: read, and each posting's part of the page asked for the subject
                        final List<String> pages = new ArrayList<>(shared.keySet());
                        for (int k = 0; k < pages.size(); k += SHARED_BATCH) {
                            final List<String> batch = pages.subList(k, Math.min(pages.size(), k + SHARED_BATCH));
                            if (sharedRead + batch.size() > SHARED_PAGES || clock.getAsLong() - start > budget) {
                                final boolean time = clock.getAsLong() - start > budget;
                                timedOut |= time;
                                gaps.add((time ? "time budget" : "text: more than " + SHARED_PAGES + " pages of several postings") + ": "
                                        + (pages.size() - k) + " pages naming " + c.label + " not read, their postings not decided");
                                if (i < subjects) pageOnly += pages.size() - k;
                                break;
                            }
                            sharedRead += batch.size();
                            final Map<String, RagCandidate> texts = new LinkedHashMap<>();
                            final Map<String, List<String>> byWords = new LinkedHashMap<>();
                            for (final String page : batch) byWords.computeIfAbsent(shared.get(page), w -> new ArrayList<>()).add(urls.get(page));
                            for (final Map.Entry<String, List<String>> e : byWords.entrySet()) {
                                for (final RagCandidate page : context.indexPages(words(e.getKey()), e.getValue(), 0, e.getValue().size()).pages) {
                                    texts.put(pageKey(page.url), page);
                                }
                            }
                            for (final String page : batch) {
                                final List<Posting> on = byPage.get(page);
                                final RagCandidate read = texts.get(page);
                                boolean shown = false;
                                if (read != null) {
                                    final List<String> titles = new ArrayList<>();
                                    for (final Posting p : on) titles.add(p.ref.name);
                                    final String[] parts = attribute(read.text, titles, c.shownBy, RagQuery.fold(words(shared.get(page)).get(0)));
                                    for (int n = 0; n < on.size(); n++) {
                                        final Posting p = on.get(n);
                                        if (parts[n] != null && p.how[i] == 0) {
                                            p.how[i] = BY_TEXT;
                                            if (p.excerpt == null) p.excerpt = parts[n];
                                        }
                                    }
                                }
                                for (final Posting p : on) shown |= p.how[i] > 0;
                                if (!shown && i < subjects) pageOnly++; // the page names the subject, no posting of it does
                            }
                        }
                    }
                } else {
                    // no direct index: the ranked search, which gives one page per host and at most TEXT_HITS
                    gaps.add("no direct index: the text of posting pages searched by ranked search only (" + TEXT_HITS
                            + " pages, one per host)");
                    for (int i = 0; i < slots; i++) {
                        final Criterion c = all.get(i);
                        final String words = c.variants.get(0).text;
                        searched.add("text: " + words);
                        for (final RagCandidate page : context.pages(words, TEXT_HITS)) {
                            final List<Posting> on = byPage.get(pageKey(page.url));
                            if (on == null) continue; // not the page of a posting of this scope
                            if (!c.shownBy.test(RagQuery.fold(page.title + " " + page.description + " " + page.text))) continue;
                            if (onePosting(on)) {
                                for (final Posting p : on) if (p.how[i] == 0) p.how[i] = BY_TEXT;
                                continue;
                            }
                            final List<String> titles = new ArrayList<>();
                            for (final Posting p : on) titles.add(p.ref.name);
                            final String[] parts = attribute(page.text, titles, c.shownBy, RagQuery.fold(words(words).get(0)));
                            boolean shown = false;
                            for (int n = 0; n < on.size(); n++) {
                                final Posting p = on.get(n);
                                if (parts[n] != null && p.how[i] == 0) {
                                    p.how[i] = BY_TEXT;
                                    if (p.excerpt == null) p.excerpt = parts[n];
                                }
                                shown |= p.how[i] > 0;
                            }
                            if (!shown && i < subjects) pageOnly++;
                        }
                    }
                }
            }
            phases.put("text", (System.nanoTime() - mark) / 1_000_000L);
        } catch (final Exception e) {
            error = e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed";
            gaps.add("error: " + error);
        }
        mark = System.nanoTime();
        if (timedOut && gaps.stream().noneMatch(g -> g.startsWith("time budget"))) gaps.add("time budget: the search stopped early");
        // 4. every posting read: which subjects it shows, and how; duplicates once. With an employer in the question only the
        // postings the graph records it as the employer of; one that names it elsewhere is a mention and is not counted.
        final List<Job> matching = new ArrayList<>();
        final Duplicates seen = new Duplicates();
        final Duplicates mentioned = new Duplicates();
        int order = 0;
        int records = 0;
        int mentions = 0;
        for (final Posting p : postings) {
            if (plan.employer != null && !p.employerHit) {
                if (p.how[subjects] > 0 && mentioned.add(p)) mentions++;
                continue;
            }
            final Job job = new Job(p.ref.id, p.ref.name);
            job.url = p.ref.url;
            job.employer = p.employer;
            job.place = p.place;
            job.collection = context.collection;
            job.excerpt = p.excerpt;
            job.order = order++;
            for (int i = 0; i < subjects; i++) {
                final String label = plan.criteria.get(i).label;
                if (p.how[i] > 0) {
                    job.met.add(label);
                    job.how.put(label, p.how[i]);
                } else {
                    job.missing.add(label);
                }
            }
            if (subjects > 0 && job.met.isEmpty()) continue;
            records++;
            if (!seen.add(p)) continue;
            matching.add(job);
        }
        final java.util.Comparator<Job> relevance = (a, b) -> a.met.size() != b.met.size() ? b.met.size() - a.met.size()
                : a.strength() != b.strength() ? b.strength() - a.strength() : a.order - b.order;
        matching.sort(relevance);
        final Map<String, Integer> perSubject = new LinkedHashMap<>();
        for (final Criterion c : plan.criteria) perSubject.put(c.label, 0);
        for (final Job job : matching) for (final String s : job.met) perSubject.merge(s, 1, Integer::sum);
        // the postings shown: the subjects in turn, each with its most relevant posting not shown yet (an or-question keeps
        // every subject that has postings), then the most relevant rest
        final List<Job> shown = new ArrayList<>();
        for (boolean added = true; added && shown.size() < MAX_JOBS;) {
            added = false;
            for (final Criterion c : plan.criteria) {
                if (shown.size() >= MAX_JOBS) break;
                final Job next = matching.stream().filter(j -> j.met.contains(c.label) && !shown.contains(j)).findFirst().orElse(null);
                if (next != null) {
                    shown.add(next);
                    added = true;
                }
            }
        }
        for (final Job job : matching) if (shown.size() < MAX_JOBS && !shown.contains(job)) shown.add(job);
        // by the number of subjects shown; within it the subjects stay in turn, so that a short answer room keeps them all
        shown.sort(java.util.Comparator.comparingInt((Job j) -> -j.met.size()));
        phases.put("selection", (System.nanoTime() - mark) / 1_000_000L);
        mark = System.nanoTime();
        // 5. the evidence of the postings shown, and only of them: their facts in full, an excerpt of a page that shows a subject
        try {
            if (reader != null && !shown.isEmpty()) evidence(context, viewer, plan, shown);
        } catch (final Exception e) {
            // the count stands; a posting shown keeps what the scan read of it
        }
        phases.put("evidence", (System.nanoTime() - mark) / 1_000_000L);
        final Result result = new Result(plan, shown, searched, timedOut, error, clock.getAsLong() - start, matching.size(), perSubject, scanned,
                gaps.isEmpty(), gaps);
        result.records = records;
        result.pageOnly = pageOnly;
        result.mentions = mentions;
        result.phases = Collections.unmodifiableMap(phases);
        final List<String> ids = new ArrayList<>();
        for (final Job job : matching) ids.add(job.id);
        result.ids = Collections.unmodifiableList(ids);
        return result;
    }

    /** a gender marker of a title: "(m/w/d)", "m|w|d", "(all genders)", "*in" */
    private static final Pattern GENDER = Pattern.compile("\\(?\\s*(?<![\\p{L}\\p{N}])[mwdfx]\\s*[/|*]\\s*[mwdfx](?:\\s*[/|*]\\s*[mwdfx])?(?![\\p{L}\\p{N}])\\s*\\)?"
            + "|\\(\\s*all\\s+genders\\s*\\)|(?<=\\p{L})[*:_]in(?:nen)?(?![\\p{L}])|(?<=\\p{L})/-?in(?:nen)?(?![\\p{L}])");

    /** A text as words: folded, every run of other characters one space. */
    static String wordsOf(final String text) {
        return RagQuery.fold(text == null ? "" : text).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** The words of a posting's title that tell postings apart: folded, without gender markers. */
    static List<String> titleWords(final String title) {
        final String words = wordsOf(GENDER.matcher(RagQuery.fold(title == null ? "" : title)).replaceAll(" "));
        return words.isEmpty() ? new ArrayList<>() : new ArrayList<>(java.util.Arrays.asList(words.split(" ")));
    }

    /**
     * Whether two titles of one page are one posting: the same words, or the words of one within the other ("Planer
     * (m/w/d)" and "Planer Hochbau (m/w/d)" are two records of one posting, read from its heading and its structured data).
     */
    static boolean sameTitle(final Set<String> a, final Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.size() <= b.size() ? b.containsAll(a) : a.containsAll(b);
    }

    /** Whether the postings of a page are one posting (one record, or records whose titles are one title). */
    private static boolean onePosting(final List<Posting> on) {
        for (int a = 0; a < on.size(); a++) {
            for (int b = a + 1; b < on.size(); b++) if (!sameTitle(on.get(a).titleWords(), on.get(b).titleWords())) return false;
        }
        return true;
    }

    /**
     * The postings counted so far, each once: one title of one employer at one place counts once over its pages (the same
     * posting on two addresses), and so do the records of one page whose titles are one title. Different postings of one
     * page (an overview of several) stay apart.
     */
    private static final class Duplicates {
        private final Set<String> keys = new LinkedHashSet<>();
        private final Map<String, List<Set<String>>> byPage = new LinkedHashMap<>();

        boolean add(final Posting p) {
            final String key = RagQuery.fold(p.ref.name) + "|" + RagQuery.fold(value(p.employer, "")) + "|" + RagQuery.fold(value(p.place, ""));
            final List<Set<String>> onPage = this.byPage.computeIfAbsent(pageKey(p.ref.url), k -> new ArrayList<>());
            boolean known = !this.keys.add(key);
            for (final Set<String> other : onPage) known |= sameTitle(other, p.titleWords());
            onPage.add(p.titleWords());
            return !known;
        }
    }

    /**
     * Which postings of a page of several postings show a subject in their part of the page's text: the text after an
     * occurrence of the posting's title, up to the next title of a posting of the page and at most {@link #SECTION_CHARS}
     * characters. A title of words about jobs only ("Karriere", "Stellenangebote") has no part of its own: it is the page's
     * furniture. Returns per title an excerpt of the part that shows the subject, or null.
     *
     * @param word a folded word of the subject, for the place of the excerpt
     */
    static String[] attribute(final String text, final List<String> titles, final Predicate<String> shownBy, final String word) {
        final String[] out = new String[titles.size()];
        // the page as words, without gender markers: a title "Monteur (m/w/d) Anlagenbau" is found as "monteur anlagenbau"
        final String page = " " + wordsOf(GENDER.matcher(RagQuery.fold(text == null ? "" : text)).replaceAll(" ")) + " ";
        final List<int[]> marks = new ArrayList<>(); // start, end, title
        for (int t = 0; t < titles.size(); t++) {
            final List<String> words = titleWords(titles.get(t));
            boolean own = false;
            for (final String w : words) own |= !intentWord(w) && w.length() > 1;
            final String needle = " " + String.join(" ", words) + " ";
            if (!own || needle.length() < 6) continue;
            for (int at = page.indexOf(needle); at >= 0; at = page.indexOf(needle, at + 1)) marks.add(new int[] {at, at + needle.length() - 1, t});
        }
        marks.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
        for (int m = 0; m < marks.size(); m++) {
            final int[] mark = marks.get(m);
            if (out[mark[2]] != null) continue;
            int end = Math.min(page.length(), mark[1] + SECTION_CHARS);
            for (int n = m + 1; n < marks.size(); n++) {
                if (marks.get(n)[0] >= mark[1]) {
                    end = Math.min(end, marks.get(n)[0] + 1);
                    break;
                }
            }
            final String part = page.substring(mark[1], Math.max(mark[1], end));
            if (!shownBy.test(part)) continue;
            final int at = word == null || word.isEmpty() ? -1 : part.indexOf(word);
            final int from = Math.max(0, (at < 0 ? 0 : at) - EXCERPT_CHARS / 3);
            out[mark[2]] = RagContext.clean(titles.get(mark[2]) + ": " + (from > 0 ? "… " : "")
                    + part.substring(from, Math.min(part.length(), from + EXCERPT_CHARS)).trim(), EXCERPT_CHARS + 80);
        }
        return out;
    }

    /** Reads the postings shown in full (employer, type, place, dates, collection) and a short excerpt for a subject shown by text. */
    private static void evidence(final ToolContext context, final Viewer viewer, final Plan plan, final List<Job> shown) throws Exception {
        final List<String> ids = new ArrayList<>();
        for (final Job job : shown) ids.add(job.id);
        final Map<String, KgReader.JobDigest> digests = new LinkedHashMap<>();
        for (final KgReader.JobDigest d : context.reader().jobDigests(viewer, ids, 0L, ids.size()).items) digests.put(d.id, d);
        for (final Job job : shown) {
            final KgReader.JobDigest d = digests.get(job.id);
            if (d == null) continue;
            final Job full = job(d);
            if (full.employer != null) job.employer = full.employer;
            if (full.place != null) job.place = full.place;
            job.employmentType = full.employmentType;
            job.datePosted = full.datePosted;
            job.validThrough = full.validThrough;
            job.loadedAt = full.loadedAt;
            if (d.collection != null) job.collection = d.collection;
        }
        if (!context.hasIndex()) return;
        for (final Criterion c : plan.criteria) {
            final List<String> urls = new ArrayList<>();
            for (final Job job : shown) if (job.how.getOrDefault(c.label, 0) == BY_TEXT && job.excerpt == null) urls.add(job.url);
            if (urls.isEmpty()) continue;
            for (final Variant v : c.variants) {
                if (v.filter != c || urls.isEmpty()) continue;
                final List<String> words = words(v.text);
                if (words.isEmpty()) continue;
                final RagQuery query = RagQuery.prepare(v.text);
                for (final RagCandidate page : context.indexPages(words, urls, 0, urls.size()).pages) {
                    for (final Job job : shown) {
                        if (job.excerpt == null && pageKey(job.url).equals(pageKey(page.url))) {
                            job.excerpt = RagContext.clean(RagContext.excerpt(page, query, EXCERPT_CHARS), EXCERPT_CHARS);
                        }
                    }
                }
            }
        }
    }

    /** The words of a variant for the direct index. */
    static List<String> words(final String text) {
        final List<String> out = new ArrayList<>();
        for (final String w : (text == null ? "" : text).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** A posting from its digest: employer, type, place and dates of its facts. */
    private static Job job(final KgReader.JobDigest d) {
        final Job job = new Job(d.id, d.name);
        job.url = d.url;
        job.collection = d.collection;
        job.loadedAt = d.loadedAt;
        for (final String[] f : d.facts) {
            switch (f[0]) {
                case Vocabulary.HIRING_ORGANIZATION:
                    if (job.employer == null) job.employer = f[1];
                    break;
                case Vocabulary.EMPLOYMENT_TYPE:
                    job.employmentType = job.employmentType == null ? f[1] : job.employmentType + ", " + f[1];
                    break;
                case Vocabulary.JOB_LOCATION:
                    if (job.place == null) job.place = f[1];
                    break;
                case Vocabulary.DATE_POSTED:
                    job.datePosted = f[1];
                    break;
                case Vocabulary.VALID_THROUGH:
                    job.validThrough = f[1];
                    break;
                default:
                    break;
            }
        }
        return job;
    }

    /** A page's address without scheme, "www." and trailing slash, for matching a search hit to a posting's page. */
    static String pageKey(final String url) {
        if (url == null) return "";
        String u = url.trim().toLowerCase(Locale.ROOT).replaceFirst("^https?://", "").replaceFirst("^www\\.", "");
        final int hash = u.indexOf('#');
        if (hash >= 0) u = u.substring(0, hash);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    // ------------------------------------------------------------------------------------------ the answer

    /** The numbered postings of one answer. */
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

    /** Numbers the postings from {@code firstId}, within {@code maxChars}. */
    public static Built format(final Result result, final int firstId, final int maxChars, final String scope, final boolean followUp) {
        final List<RagContext.Source> sources = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
        int full = 0;
        int partial = 0;
        final int room = maxChars == ALL_SHOWN ? Integer.MAX_VALUE : Math.min(MAX_CHARS, Math.max(0, maxChars));
        for (final Job job : result.jobs) {
            final int id = firstId + sources.size();
            final String title = RagContext.clean(job.title, RagContext.MAX_TITLE_CHARS - GraphFacts.TITLE.length() - 14);
            final StringBuilder entry = new StringBuilder();
            entry.append('[').append(id).append("] ").append(GraphFacts.TITLE).append("job posting ").append(title).append('\n');
            entry.append("URL: ").append(job.url).append('\n');
            if (job.collection != null) entry.append("Collection: ").append(job.collection).append('\n');
            final StringBuilder body = new StringBuilder("A job posting the Scoutro knowledge graph recorded from this page, not model knowledge. ");
            body.append("Title: ").append(title).append(". Employer: ").append(value(job.employer, "not recorded")).append('.');
            if (result.plan.employer != null) body.append(" The knowledge graph records ").append(RagContext.clean(result.plan.employer, 80))
                    .append(" as its employer.");
            if (job.employmentType != null) body.append(" Employment type: ").append(value(job.employmentType, "")).append('.');
            if (job.place != null) body.append(" Place: ").append(value(job.place, "")).append('.');
            // the dates it states, if any: the stock of the graph counts as current, a missing date is no remark
            if (job.datePosted != null) body.append(" Posted: ").append(value(job.datePosted, "")).append('.');
            if (job.validThrough != null) body.append(" Valid through: ").append(value(job.validThrough, "")).append('.');
            if (!result.plan.criteria.isEmpty()) {
                body.append(job.met.isEmpty() ? " Shows none of the question's subjects in its recorded data."
                        : " Subjects of the question it shows: " + String.join(", ", job.met) + ".");
                final List<String> byText = new ArrayList<>();
                for (final String m : job.met) if (job.how.getOrDefault(m, 0) == BY_TEXT) byText.add(m);
                if (!byText.isEmpty()) {
                    body.append(" Shown in the text of its page: ").append(String.join(", ", byText)).append('.');
                    if (job.excerpt != null && !job.excerpt.isEmpty()) body.append(" Its page: ").append(job.excerpt);
                }
                if (job.partial()) body.append(" PARTIAL MATCH: not shown for ").append(String.join(", ", job.missing)).append('.');
            }
            final int separator = text.length() == 0 ? 0 : 2;
            if (text.length() + separator + entry.length() + "Text: ".length() + body.length() > room) break;
            if (separator > 0) text.append("\n\n");
            text.append(entry).append("Text: ").append(body);
            sources.add(new RagContext.Source(id, GraphFacts.TITLE + "job posting " + title, job.url, host(job.url), job.collection,
                    RagContext.Source.KIND_GRAPH, body.toString()));
            if (job.partial()) partial++; else full++;
        }
        final JSONObject meta = new JSONObject(true);
        try {
            meta.put("triggered", true);
            meta.put("followUp", followUp);
            meta.put("scope", scope);
            meta.put("criteria", new JSONArray(result.plan.criteria()));
            meta.put("searched", new JSONArray(result.searched));
            meta.put("found", result.found); // the matching postings determined in the scope
            meta.put("lowerBound", result.lowerBound()); // the search ran out of time or reading budget: at least this many
            meta.put("shown", sources.size());
            meta.put("notShown", result.jobs.size() - sources.size()); // selected, but no room left in the context
            meta.put("perSubject", new JSONObject(result.perSubject));
            meta.put("records", result.records); // matching records; found counts a posting on two pages once
            meta.put("pageOnly", result.pageOnly); // pages of several postings naming a subject that no posting of them shows: not counted
            if (result.plan.employer != null) {
                meta.put("employer", result.plan.employer); // found: the postings the graph records it as the employer of
                meta.put("mentions", result.mentions); // postings naming it otherwise: not counted
            }
            meta.put("scanned", result.scanned);
            meta.put("gaps", new JSONArray(result.gaps)); // why the count is only a lower bound
            meta.put("full", full);
            meta.put("partial", partial);
            meta.put("timedOut", result.timedOut);
            meta.put("error", result.error == null ? JSONObject.NULL : result.error);
            meta.put("millis", result.millis);
            meta.put("phases", new JSONObject(result.phases));
        } catch (final JSONException e) {
            // plain keys and values
        }
        return new Built(sources, text.toString(), meta);
    }

    private static String value(final String v, final String none) {
        return v == null || v.trim().isEmpty() ? none : RagContext.clean(v, MAX_VALUE);
    }

    private static String text(final JSONObject o, final String key) {
        return o == null || o.isNull(key) ? "" : o.optString(key, "");
    }

    private static String firstNonEmpty(final String... values) {
        for (final String v : values) if (v != null && !v.isEmpty()) return v;
        return "";
    }

    private static String host(final String url) {
        try {
            final String h = new java.net.URI(url).getHost();
            return h == null ? "" : h;
        } catch (final Exception e) {
            return "";
        }
    }
}
