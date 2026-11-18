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
 * <li>A few short title variants: the subjects SAP, KI and Automatisierung each searched on its own (with "Künstliche
 * Intelligenz", "AI" and "Automation"), first together when the question names several; any other subject by its words.
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

    private static Predicate<String> pattern(final String regex) {
        final Pattern p = Pattern.compile(regex);
        return folded -> p.matcher(folded).find();
    }

    private static final String START = "(?<![\\p{L}\\p{N}])";
    private static final String END = "(?![\\p{L}\\p{N}])";

    /** SAP, KI and Automatisierung: subjects of their own, searched separately; a question may name them in many ways. */
    private static List<Criterion> concepts() {
        final List<Criterion> out = new ArrayList<>();
        final List<Variant> sap = new ArrayList<>();
        final Criterion c1 = new Criterion("SAP", pattern(START + "sap" + END), sap);
        sap.add(new Variant("SAP", true, c1));
        out.add(c1);
        final List<Variant> ki = new ArrayList<>();
        final Criterion c2 = new Criterion("KI", pattern(START + "(?:ki|ai)" + END + "|kunstlich\\p{L}* intelligenz|artificial intelligence"
                + "|machine learning|maschinell\\p{L}* lernen"), ki);
        ki.add(new Variant("KI", true, c2));
        ki.add(new Variant("Intelligenz", false, c2)); // "Künstliche Intelligenz", "Data Science & Künstliche Intelligenz"
        ki.add(new Variant("AI", true, c2));
        out.add(c2);
        final List<Variant> auto = new ArrayList<>();
        final Criterion c3 = new Criterion("Automatisierung", pattern("automatisier|automation|" + START + "rpa" + END), auto);
        auto.add(new Variant("Automatisierung", false, c3));
        auto.add(new Variant("Automation", false, c3));
        out.add(c3);
        return out;
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
        for (final Criterion c : concepts()) if (c.shownBy.test(folded)) criteria.add(c);
        String role = null;
        for (final String word : folded.split("[^\\p{L}]+")) {
            if (ROLES.contains(word)) {
                role = word;
                break;
            }
        }
        final Set<Variant> variants = new LinkedHashSet<>();
        if (!criteria.isEmpty()) {
            if (criteria.size() > 1) {
                final StringBuilder all = new StringBuilder();
                for (final Criterion c : criteria) all.append(all.length() == 0 ? "" : " ").append(c.variants.get(0).text);
                variants.add(new Variant(all.toString(), true, null));
            }
            if (role != null) variants.add(new Variant(criteria.get(0).variants.get(0).text + " " + role, false, criteria.get(0)));
            for (final Criterion c : criteria) variants.add(c.variants.get(0));
            // the question's own compounds of a subject ("Prozessautomatisierung"): a name search matches only the start of a word
            for (final String word : (goal == null ? "" : goal).split("[^\\p{L}\\p{N}]+")) {
                if (word.length() < 6) continue;
                for (final Criterion c : criteria) {
                    if (c.shownBy.test(RagQuery.fold(word))) variants.add(new Variant(word, false, c));
                }
            }
            for (final Criterion c : criteria) for (final Variant v : c.variants) variants.add(v);
        } else {
            // any other subject: its strongest words (no words about jobs, sources or the search itself)
            for (final RagQuery.Term t : RagFollowUp.topics(goal)) {
                if (criteria.size() >= 3) break;
                if (t.phrase || t.weak) continue;
                final RagQuery.Term term = t;
                final List<Variant> own = new ArrayList<>();
                final Criterion c = new Criterion(t.text, f -> RagCandidate.matches(term, f), own);
                own.add(new Variant(t.text, false, null));
                criteria.add(c);
                variants.add(own.get(0));
            }
            final String ownRole = role;
            if (ownRole != null && criteria.stream().noneMatch(c -> c.label.equals(ownRole))) {
                variants.add(new Variant(ownRole, false, null));
            }
        }
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

        Result(final Plan plan, final List<Job> jobs, final List<String> searched, final boolean timedOut, final String error, final long millis) {
            this.plan = plan;
            this.jobs = Collections.unmodifiableList(jobs);
            this.searched = Collections.unmodifiableList(searched);
            this.timedOut = timedOut;
            this.error = error;
            this.millis = millis;
        }
    }

    /** Whether the search may run for this request: the release of kg_search within the request's scope. */
    public static boolean released(final boolean indexTools, final ToolContext context) {
        return context != null && context.graph && net.yacy.ai.ToolProvider.allowedCallsPerTurn("kg_search", indexTools, context) > 0;
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
            for (final Variant v : plan.variants) {
                if (found.size() >= MAX_CANDIDATES) break;
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
                for (int i = 0; items != null && i < items.length() && found.size() < MAX_CANDIDATES; i++) {
                    final JSONObject item = items.getJSONObject(i);
                    final String id = text(item, "id");
                    final String title = firstNonEmpty(text(item, "name"), text(item, "display_name"));
                    if (id.isEmpty() || title.isEmpty() || found.containsKey(id)) continue;
                    if (v.filter != null && !v.filter.shownBy.test(RagQuery.fold(title))) continue; // a word that only starts a title word
                    final Job job = new Job(id, title);
                    job.order = found.size();
                    final JSONObject ctx = item.optJSONObject("context");
                    final JSONArray providers = ctx == null ? null : ctx.optJSONArray("providers");
                    if (providers != null && providers.length() > 0) job.employer = text(providers.getJSONObject(0), "name");
                    found.put(id, job);
                }
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
        jobs.sort((a, b) -> a.met.size() != b.met.size() ? b.met.size() - a.met.size()
                : a.ended != b.ended ? (a.ended ? 1 : -1)
                : !String.valueOf(b.datePosted).equals(String.valueOf(a.datePosted)) ? String.valueOf(b.datePosted).compareTo(String.valueOf(a.datePosted))
                : a.order - b.order);
        return new Result(plan, new ArrayList<>(jobs.subList(0, Math.min(MAX_JOBS, jobs.size()))), searched, timedOut, error,
                clock.getAsLong() - start);
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
