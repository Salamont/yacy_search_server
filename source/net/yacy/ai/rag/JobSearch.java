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

import net.yacy.scoutro.knowledge.read.KgReader;
import net.yacy.scoutro.knowledge.store.KgChangeLog.Viewer;

/**
 * The job search of the chat (docs/SCOUTRO_RAG_QUALITY.md, section 12). For a question about job postings whose automatic
 * search matched only weakly or not at all, the server searches the job postings of the knowledge graph ({@code type=job})
 * before the model answers, whether or not the model would call a tool:
 * <ul>
 * <li>Only with the existing release of {@code kg_search} and within the scope of the chat request ({@link ToolContext}:
 * the session's collection, read with its viewer). Nothing outside it is read.</li>
 * <li>A few short title variants: each subject of the question searched on its own (a configured subject group of
 * {@link #SUBJECTS_KEY} also by its synonyms), first together when the question names several.
 * At most {@link #MAX_VARIANTS} name searches, {@link #MAX_JOBS} postings, {@link #MAX_MILLIS} ms.</li>
 * <li>Each posting goes to the answer as a numbered source with the page it was read from, its employer and dates, the
 * subjects of the question it shows and, as a PARTIAL MATCH, those it does not. Whether it is still open is not checked:
 * a separate question, answered only from the dates of the source.</li>
 * </ul>
 */
public final class JobSearch {

    public static final int MAX_VARIANTS = 6;
    public static final int MAX_JOBS = 6;
    public static final long MAX_MILLIS = 4000L;
    static final int PER_VARIANT = 12;
    static final int MAX_CANDIDATES = 12;
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

    /** Whether a question asks for job postings. */
    public static boolean intent(final String text) {
        return text != null && INTENT.matcher(RagQuery.fold(text)).find();
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
            if (f.isEmpty() || intentWord(f) || RagFollowUp.noTopic(f)) continue;
            own.append(own.length() == 0 ? "" : " ").append(w);
        }
        for (final RagQuery.Term t : RagFollowUp.topics(own.toString())) {
            if (topics >= 3) break;
            if (t.phrase || t.weak) continue;
            final String folded1 = RagQuery.fold(t.text);
            if (intentWord(folded1) || ROLES.contains(folded1)) continue; // a word about jobs, or a role (searched with a subject)
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
        String status;
        boolean ended;
        final List<String> met = new ArrayList<>();
        final List<String> missing = new ArrayList<>();
        final StringBuilder haystack = new StringBuilder();
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
    }

    /** What the search found. */
    public static final class Result {
        public final Plan plan;
        public final List<Job> jobs;
        public final List<String> searched;
        public final boolean timedOut;
        public final String error;
        public final long millis;
        /** the postings considered (at most {@link #MAX_CANDIDATES}) */
        public final int candidates;

        public Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis) {
            this(plan, jobs, searched, timedOut, error, millis, jobs.size());
        }

        Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis,
                final int candidates) {
            this.candidates = candidates;
            this.plan = plan;
            this.jobs = Collections.unmodifiableList(jobs);
            this.searched = Collections.unmodifiableList(searched);
            this.timedOut = timedOut;
            this.error = error;
            this.millis = millis;
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

    static Result run(final ToolContext context, final Plan plan, final LongSupplier clock) {
        final long start = clock.getAsLong();
        final Map<String, Job> found = new LinkedHashMap<>();
        final List<String> searched = new ArrayList<>();
        boolean timedOut = false;
        String error = null;
        try {
            final KgReader reader = context.reader();
            if (reader == null) return new Result(plan, new ArrayList<>(), searched, false, "unavailable", 0L);
            final Viewer viewer = context.viewer(reader);
            // the candidate places are shared by the subjects: each subject's searches (Variant.filter; null: the joint ones)
            // fill their own share first, so that one subject cannot use up the places before the others were searched
            final Map<Criterion, List<Job>> shares = new LinkedHashMap<>();
            for (final Variant v : plan.variants) shares.putIfAbsent(v.filter, new ArrayList<>());
            final int share = Math.max(1, (MAX_CANDIDATES + shares.size() - 1) / shares.size());
            final Map<String, Job> hits = new LinkedHashMap<>(); // every hit once, the first search that found it decides its share
            for (final Variant v : plan.variants) {
                final List<Job> own = shares.get(v.filter);
                if (hits.size() >= MAX_CANDIDATES && own.size() >= share) continue; // nothing this search could still add
                if (clock.getAsLong() - start > MAX_MILLIS) {
                    timedOut = true;
                    break;
                }
                final KgReader.EntityQuery q = new KgReader.EntityQuery();
                q.q = v.text;
                q.type = "job";
                q.wholeWords = v.wholeWords;
                q.limit = PER_VARIANT;
                searched.add(v.text);
                final JSONArray items = reader.entities(q, viewer).optJSONArray("items");
                for (int i = 0; items != null && i < items.length(); i++) {
                    final JSONObject item = items.getJSONObject(i);
                    final String id = text(item, "id");
                    final String title = firstNonEmpty(text(item, "name"), text(item, "display_name"));
                    if (id.isEmpty() || title.isEmpty() || hits.containsKey(id)) continue;
                    if (v.filter != null && !v.filter.shownBy.test(RagQuery.fold(title))) continue; // a word that only starts a title word
                    final Job job = new Job(id, title);
                    final JSONObject ctx = item.optJSONObject("context");
                    final JSONArray providers = ctx == null ? null : ctx.optJSONArray("providers");
                    if (providers != null && providers.length() > 0) job.employer = text(providers.getJSONObject(0), "name");
                    hits.put(id, job);
                    own.add(job);
                }
            }
            // the candidates: the shares in turn, each in the order its searches ranked them, at most MAX_CANDIDATES
            for (int round = 0; found.size() < MAX_CANDIDATES; round++) {
                boolean more = false;
                for (final List<Job> own : shares.values()) {
                    if (round >= own.size()) continue;
                    more = true;
                    final Job job = own.get(round);
                    if (found.size() < MAX_CANDIDATES && !found.containsKey(job.id)) {
                        job.order = found.size();
                        found.put(job.id, job);
                    }
                }
                if (!more) break;
            }
            details(reader, viewer, found, clock, start);
        } catch (final Exception e) {
            error = e instanceof net.yacy.scoutro.knowledge.KgException ? ((net.yacy.scoutro.knowledge.KgException) e).code() : "failed";
        }
        final String today = Instant.ofEpochMilli(clock.getAsLong()).atZone(ZoneOffset.UTC).toLocalDate().toString();
        final List<Job> jobs = new ArrayList<>();
        for (final Job job : found.values()) {
            if (job.url == null) continue; // no page of this scope behind it: nothing to cite
            final String folded = RagQuery.fold(job.title + " " + (job.employer == null ? "" : job.employer) + " " + job.haystack);
            for (final Criterion c : plan.criteria) (c.shownBy.test(folded) ? job.met : job.missing).add(c.label);
            if (job.validThrough != null && job.validThrough.length() >= 10 && job.validThrough.substring(0, 10).compareTo(today) < 0) job.ended = true;
            if (job.status != null && job.status.toLowerCase(Locale.ROOT).matches(".*(ended|closed|expired|filled|besetzt|beendet).*")) job.ended = true;
            jobs.add(job);
        }
        final java.util.Comparator<Job> relevance = (a, b) -> a.met.size() != b.met.size() ? b.met.size() - a.met.size()
                : a.ended != b.ended ? (a.ended ? 1 : -1)
                : !String.valueOf(b.datePosted).equals(String.valueOf(a.datePosted)) ? String.valueOf(b.datePosted).compareTo(String.valueOf(a.datePosted))
                : a.order - b.order;
        jobs.sort(relevance);
        // the postings shown: the most relevant one of every subject that has one first (a question for several subjects
        // also gets postings of the later ones), then the most relevant of the rest; shown by relevance
        final List<Job> shown = new ArrayList<>();
        for (final Criterion c : plan.criteria) {
            if (shown.size() >= MAX_JOBS || shown.stream().anyMatch(j -> j.met.contains(c.label))) continue;
            jobs.stream().filter(j -> j.met.contains(c.label) && !shown.contains(j)).findFirst().ifPresent(shown::add);
        }
        for (final Job job : jobs) if (shown.size() < MAX_JOBS && !shown.contains(job)) shown.add(job);
        shown.sort(relevance);
        return new Result(plan, shown, searched, timedOut, error, clock.getAsLong() - start, found.size());
    }

    /** The facts of each posting and the newest page of its title (or of another of its facts) in this scope. */
    private static void details(final KgReader reader, final Viewer viewer, final Map<String, Job> found, final LongSupplier clock,
            final long start) throws Exception {
        final Map<String, String> statementOf = new LinkedHashMap<>(); // statement -> job
        final Map<String, String> nameStatement = new LinkedHashMap<>(); // job -> its name statement
        for (final Job job : found.values()) {
            if (clock.getAsLong() - start > MAX_MILLIS) break;
            final JSONArray items = reader.entityStatements(job.id, null, false, false, 0, 30, viewer).optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                final JSONObject s = items.getJSONObject(i);
                final String predicate = text(s, "predicate");
                final JSONObject object = s.optJSONObject("object");
                final String value = object == null ? "" : firstNonEmpty(text(object, "name"), text(object, "display_name"), text(object, "value"));
                statementOf.put(text(s, "id"), job.id);
                if ("name".equals(predicate)) nameStatement.putIfAbsent(job.id, text(s, "id"));
                job.haystack.append(' ').append(value);
                switch (predicate) {
                    case "hiring_organization":
                        if (job.employer == null || job.employer.isEmpty()) job.employer = value;
                        break;
                    case "employment_type":
                        job.employmentType = value;
                        break;
                    case "job_location":
                        job.place = value;
                        break;
                    case "date_posted":
                        job.datePosted = value;
                        break;
                    case "valid_through":
                        job.validThrough = value;
                        break;
                    case "job_status":
                        job.status = value;
                        break;
                    default:
                        break;
                }
            }
        }
        if (statementOf.isEmpty()) return;
        final Map<String, JSONArray> evidence = reader.evidenceOf(statementOf.keySet(), 1, viewer);
        for (final Map.Entry<String, JSONArray> e : evidence.entrySet()) {
            final Job job = found.get(statementOf.get(e.getKey()));
            final JSONObject row = e.getValue().optJSONObject(0);
            if (job == null || row == null || text(row, "url").isEmpty()) continue;
            job.haystack.append(' ').append(text(row, "excerpt"));
            final boolean ofName = e.getKey().equals(nameStatement.get(job.id));
            if (job.url == null || ofName) {
                job.url = text(row, "url");
                final JSONArray collections = row.optJSONArray("collections");
                job.collection = collections == null || collections.length() == 0 ? null : collections.optString(0, null);
                job.loadedAt = text(row, "loaded_at");
            }
        }
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
            body.append(" Posted: ").append(value(job.datePosted, "not stated")).append('.');
            if (job.validThrough != null) body.append(" Valid through: ").append(value(job.validThrough, "")).append('.');
            if (!result.plan.criteria.isEmpty()) {
                body.append(job.met.isEmpty() ? " Shows none of the question's subjects in its recorded data."
                        : " Subjects of the question it shows: " + String.join(", ", job.met) + ".");
                if (job.partial()) body.append(" PARTIAL MATCH: not shown for ").append(String.join(", ", job.missing)).append('.');
            }
            body.append(" Application status: not checked; whether the posting is still open is a separate question");
            if (job.loadedAt != null && !job.loadedAt.isEmpty()) body.append("; the page was last loaded on ").append(job.loadedAt, 0, Math.min(10, job.loadedAt.length()));
            if (job.ended) body.append("; the posting's recorded end date or status says it has ended");
            body.append('.');
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
            meta.put("found", sources.size());
            meta.put("notShown", result.jobs.size() - sources.size()); // found, but no room left in the context
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
