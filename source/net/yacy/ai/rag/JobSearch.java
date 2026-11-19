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
 * The job search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 12). For a question about job postings whose automatic
 * search matched only weakly or not at all, the server searches the job postings of the knowledge graph ({@code type=job})
 * before the model answers, whether or not the model would call a tool; the tool {@code job_search} runs the same search.
 * <ul>
 * <li>Only with the existing release of {@code kg_search} and within the scope of the chat request ({@link ToolContext}:
 * the session's collection, read with its viewer). Nothing outside it is read.</li>
 * <li>The whole released stock: the name index for a few title variants, every posting of the scope with its facts and
 * excerpts ({@link KgReader#jobDigests}), and the indexed text of the postings' pages. A posting counts by its title, a
 * fact or the text of its own page; a company page is no posting. The matching postings are determined and deduplicated
 * before the answer's limit of {@link #MAX_JOBS}; the result says how many were found and shown, and whether the count
 * is only a lower bound, and why ({@link Result#gaps}: the time of {@link #MAX_MILLIS} ms, or no direct index).</li>
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
    /** postings read per page of the scan of the scope (it reads to the end of the stock or of the time) */
    static final int SCAN_PAGE = 250;
    /** posting pages asked per query of the direct index */
    static final int URL_BATCH = 200;
    /** hits of the name index read per variant, in pages */
    static final int NAME_HITS = 500;
    static final int NAME_PAGE = 100;
    /** pages of one ranked text search (only without a direct index) */
    static final int TEXT_HITS = 50;
    static final int MAX_CHARS = 2600;
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

        Plan(final List<Criterion> criteria, final List<Variant> variants) {
            this.criteria = criteria;
            this.variants = variants;
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

    public static Plan plan(final String goal) {
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

        public Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis) {
            this(plan, jobs, searched, timedOut, error, millis, jobs.size(), new LinkedHashMap<>(), jobs.size(), !timedOut && error == null,
                    new ArrayList<>());
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

    public static Result run(final ToolContext context, final Plan plan) {
        return run(context, plan, System::currentTimeMillis);
    }

    /**
     * The postings of the request's scope that match the plan, determined before the output is limited (the automatic job
     * search and the tool {@code job_search} both run this):
     * <ol>
     * <li>the name index ({@code type=job}, every page of hits of each title variant);</li>
     * <li>every posting of the scope with its facts and excerpts ({@link KgReader#jobDigests}): titles, job facts
     * (employer, field, signals of the advertisement) and the advertisement's text around them;</li>
     * <li>the indexed text of the pages (the local index within the scope): a page counts only when it is the page of a
     * posting of the scope, never a company page on its own; a host that has postings is searched once more by
     * {@code site:}, as the index gives one page per host.</li>
     * </ol>
     * Duplicates (one entity, one page, or one title of one employer at one place) count once. The postings found are
     * ranked by the subjects they show and how, the shown ones keep one of every subject that has one. Dates neither
     * exclude nor rank: the stock of the graph counts as current.
     */
    static Result run(final ToolContext context, final Plan plan, final LongSupplier clock) {
        return run(context, plan, clock, MAX_MILLIS);
    }

    static Result run(final ToolContext context, final Plan plan, final LongSupplier clock, final long budget) {
        final long start = clock.getAsLong();
        final List<String> searched = new ArrayList<>();
        final List<String> gaps = new ArrayList<>(); // why the count is only a lower bound
        final Map<String, KgReader.JobDigest> digests = new LinkedHashMap<>(); // id -> posting
        final Map<String, Map<String, Integer>> hits = new LinkedHashMap<>(); // id -> subject -> how
        boolean timedOut = false;
        String error = null;
        try {
            final KgReader reader = context.reader();
            if (reader == null) return new Result(plan, new ArrayList<>(), searched, false, "unavailable", 0L);
            final Viewer viewer = context.viewer(reader);
            // 1. the name index: every page of hits of each title variant (it also finds names the scan below reaches late)
            for (final Variant v : plan.variants) {
                if (clock.getAsLong() - start > budget) {
                    timedOut = true;
                    break;
                }
                searched.add(v.text);
                for (int offset = 0; offset < NAME_HITS; offset += NAME_PAGE) {
                    final KgReader.EntityQuery q = new KgReader.EntityQuery();
                    q.q = v.text;
                    q.type = "job";
                    q.wholeWords = v.wholeWords;
                    q.offset = offset;
                    q.limit = NAME_PAGE;
                    final JSONObject page = reader.entities(q, viewer);
                    final JSONArray items = page.optJSONArray("items");
                    for (int i = 0; items != null && i < items.length(); i++) {
                        final String id = text(items.getJSONObject(i), "id");
                        if (!id.isEmpty()) hits.computeIfAbsent(id, k -> new LinkedHashMap<>());
                    }
                    if (items == null || items.length() < NAME_PAGE || offset + NAME_PAGE >= page.optLong("total", 0L)) break;
                    if (clock.getAsLong() - start > budget) {
                        timedOut = true;
                        break;
                    }
                }
            }
            // 2. every posting of the scope, with its facts and excerpts, page by page to the end of the stock
            long after = 0L;
            boolean more = true;
            while (more && !timedOut) {
                if (clock.getAsLong() - start > budget) {
                    timedOut = true;
                    break;
                }
                final KgReader.JobPage page = reader.jobDigests(viewer, null, after, SCAN_PAGE);
                for (final KgReader.JobDigest d : page.items) digests.putIfAbsent(d.id, d);
                after = page.last;
                more = page.more;
            }
            if (more) gaps.add("time budget: " + digests.size() + " postings of the scope read, not all");
            // the name hits the scan did not reach
            final List<String> unread = new ArrayList<>();
            for (final String id : hits.keySet()) if (!digests.containsKey(id)) unread.add(id);
            if (!unread.isEmpty() && clock.getAsLong() - start <= budget) {
                for (final KgReader.JobDigest d : reader.jobDigests(viewer, unread, 0L, unread.size()).items) digests.putIfAbsent(d.id, d);
            }
            // 3. the indexed text of the postings' own pages: the direct index, by the known addresses of the postings that
            // do not show the subject by title or fact; each posting's page is asked, whichever page of its host ranks first
            final Map<String, String> byUrl = new LinkedHashMap<>();
            for (final KgReader.JobDigest d : digests.values()) if (d.url != null) byUrl.putIfAbsent(pageKey(d.url), d.id);
            if (!plan.criteria.isEmpty() && !timedOut) {
                if (context.hasIndex()) {
                    text:
                    for (final Criterion c : plan.criteria) {
                        final List<String> urls = new ArrayList<>();
                        for (final KgReader.JobDigest d : digests.values()) {
                            if (d.url != null && d.name != null && !shownByData(d, c)) urls.add(d.url);
                        }
                        for (final Variant v : c.variants) {
                            if (v.filter != c) continue; // a subject's own words; the joint variants are no subject of their own
                            final List<String> words = words(v.text);
                            if (words.isEmpty() || urls.isEmpty()) continue;
                            searched.add("index: " + v.text + " (" + urls.size() + " posting pages)");
                            for (int i = 0; i < urls.size(); i += URL_BATCH) {
                                if (clock.getAsLong() - start > budget) {
                                    timedOut = true;
                                    gaps.add("time budget: the text of " + (urls.size() - i) + " posting pages not searched for " + v.text);
                                    break text;
                                }
                                final List<String> batch = urls.subList(i, Math.min(urls.size(), i + URL_BATCH));
                                final ToolContext.IndexPage found = context.indexPages(words, batch, 0, batch.size());
                                textHits(found.pages, c, byUrl, hits, new LinkedHashSet<>());
                            }
                        }
                    }
                } else {
                    // no direct index: the ranked search, which gives one page per host and at most TEXT_HITS
                    gaps.add("no direct index: the text of posting pages searched by ranked search only (" + TEXT_HITS
                            + " pages, one per host)");
                    for (final Criterion c : plan.criteria) {
                        final String words = c.variants.get(0).text;
                        searched.add("text: " + words);
                        textHits(context.pages(words, TEXT_HITS), c, byUrl, hits, new LinkedHashSet<>());
                    }
                }
            }
        } catch (final Exception e) {
            error = e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed";
            gaps.add("error: " + error);
        }
        if (timedOut && gaps.stream().noneMatch(g -> g.startsWith("time budget"))) gaps.add("time budget: the search stopped early");
        // every posting read: which subjects it shows, and how
        final List<Job> matching = new ArrayList<>();
        final Set<String> seen = new LinkedHashSet<>();
        int order = 0;
        for (final KgReader.JobDigest d : digests.values()) {
            if (d.url == null || d.name == null) continue; // no page of this scope behind it: nothing to cite
            final Job job = job(d);
            job.order = order++;
            final String title = RagQuery.fold(d.name);
            final String folded = factText(d);
            final Map<String, Integer> text = hits.getOrDefault(d.id, Collections.emptyMap());
            for (final Criterion c : plan.criteria) {
                final int how = c.shownBy.test(title) ? BY_TITLE : c.shownBy.test(folded) ? BY_FACT : text.getOrDefault(c.label, 0);
                if (how > 0) {
                    job.met.add(c.label);
                    job.how.put(c.label, how);
                } else {
                    job.missing.add(c.label);
                }
            }
            if (!plan.criteria.isEmpty() && job.met.isEmpty()) continue;
            // duplicates: one page, or one title of one employer at one place
            final String key = RagQuery.fold(job.title) + "|" + RagQuery.fold(value(job.employer, "")) + "|" + RagQuery.fold(value(job.place, ""));
            if (!seen.add("u|" + pageKey(job.url)) | !seen.add("k|" + key)) continue;
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
        return new Result(plan, shown, searched, timedOut, error, clock.getAsLong() - start, matching.size(), perSubject, digests.size(),
                gaps.isEmpty(), gaps);
    }

    /** The facts and excerpts of a posting, folded (without its name). */
    private static String factText(final KgReader.JobDigest d) {
        final StringBuilder facts = new StringBuilder();
        for (final String[] f : d.facts) if (!Vocabulary.NAME.equals(f[0])) facts.append(' ').append(f[1]);
        for (final String x : d.excerpts) facts.append(' ').append(x);
        return RagQuery.fold(facts.toString());
    }

    /** Whether a posting shows a subject by its title or its facts already (its page's text is then not asked). */
    private static boolean shownByData(final KgReader.JobDigest d, final Criterion c) {
        return c.shownBy.test(RagQuery.fold(d.name)) || c.shownBy.test(factText(d));
    }

    /** The words of a variant for the direct index. */
    static List<String> words(final String text) {
        final List<String> out = new ArrayList<>();
        for (final String w : (text == null ? "" : text).split("[^\\p{L}\\p{N}]+")) if (!w.isEmpty()) out.add(w);
        return out;
    }

    /** The pages of a text search that are pages of postings: their subject {@code c}, by text. */
    private static void textHits(final List<RagCandidate> pages, final Criterion c, final Map<String, String> byUrl,
            final Map<String, Map<String, Integer>> hits, final Set<String> hostsHit) {
        for (final RagCandidate page : pages) {
            final String id = byUrl.get(pageKey(page.url));
            if (id == null) continue; // not the page of a posting of this scope (a company page names no posting)
            hostsHit.add(host(page.url));
            if (!c.shownBy.test(RagQuery.fold(page.title + " " + page.description + " " + page.text))) continue; // other words of it matched
            hits.computeIfAbsent(id, k -> new LinkedHashMap<>()).merge(c.label, BY_TEXT, Math::max);
        }
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
        final int room = Math.min(MAX_CHARS, Math.max(0, maxChars));
        for (final Job job : result.jobs) {
            final int id = firstId + sources.size();
            final String title = RagContext.clean(job.title, RagContext.MAX_TITLE_CHARS - GraphFacts.TITLE.length() - 14);
            final StringBuilder entry = new StringBuilder();
            entry.append('[').append(id).append("] ").append(GraphFacts.TITLE).append("job posting ").append(title).append('\n');
            entry.append("URL: ").append(job.url).append('\n');
            if (job.collection != null) entry.append("Collection: ").append(job.collection).append('\n');
            final StringBuilder body = new StringBuilder("A job posting the Scoutro knowledge graph recorded from this page, not model knowledge. ");
            body.append("Title: ").append(title).append(". Employer: ").append(value(job.employer, "not recorded")).append('.');
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
                if (!byText.isEmpty()) body.append(" Shown in the text of its page: ").append(String.join(", ", byText)).append('.');
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
            meta.put("scanned", result.scanned);
            meta.put("gaps", new JSONArray(result.gaps)); // why the count is only a lower bound
            meta.put("full", full);
            meta.put("partial", partial);
            meta.put("timedOut", result.timedOut);
            meta.put("error", result.error == null ? JSONObject.NULL : result.error);
            meta.put("millis", result.millis);
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
