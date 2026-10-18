/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.collections;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Automatic correction of collection assignments (data quality programme WP-H, docs/SCOUTRO_COLLECTION_AUTOCORRECT.md).
 * <p>
 * It decides, from <b>independent</b> signals only, whether a domain's specialist collection is wrong, and what follows
 * from the configured mode: {@code off} (the default: nothing), {@code simulate} (recorded for the report only),
 * {@code propose} (a case in the review queue, applied only when an administrator approves it as a manual decision),
 * {@code apply} (an automatic collection decision, only after an accepted statistical acceptance, within the daily
 * limit, while the breaker is closed; everything else becomes a proposal).
 * <p>
 * The rule for an automatic correction: at least two independent origins speak against the current collection, at least
 * one of them with a structured or text-fit signal; no origin speaks for it; an LLM verdict or an origin signal (OSM) is
 * never enough, not even together. Signals of the website itself (structured data, text, an LLM reading the site) are one
 * origin, whatever family they belong to; the absence of an industry or a crawl error is no signal. The target is a
 * specialist collection only when the same rule holds for it, otherwise the fallback collection. A manual decision in
 * scope always wins; a confirmed multiple assignment is never corrected automatically. The same fingerprint (signals,
 * revisions, criteria) never changes anything twice; an automatically decided domain changes again only with a new
 * revision and a new signal set (hysteresis). A case marked wrong opens the breaker.
 */
public final class AutoCorrect {

    public enum Mode { OFF, SIMULATE, PROPOSE, APPLY }

    /** Signal families. */
    public static final String STRUCTURED = "structured";
    public static final String TEXT_FIT = "text_fit";
    public static final String LLM = "llm";
    public static final String ORIGIN = "origin";
    static final Set<String> FAMILIES = Set.of(STRUCTURED, TEXT_FIT, LLM, ORIGIN);
    /** Kinds that state an absence or a failure: never a signal. */
    static final Set<String> NO_SIGNAL = Set.of("missing_industry", "no_industry", "no_category", "crawl_error", "unreachable", "empty",
            "not_found", "timeout");
    /** Kinds of an origin signal that are read from the website itself (an imprint, the site's own page). */
    static final Set<String> WEBSITE_KINDS = Set.of("imprint", "website", "site_text", "own_page");
    public static final String AGAINST = "against";
    public static final String FOR = "for";

    /** Outcomes. */
    public static final String NONE = "none";
    public static final String REVIEW = "review";
    public static final String CORRECT = "correct";

    static final int MAX_CASES = 5000;
    /**
     * Version of the rules and of the signal grouping: an acceptance measured with other rules does not hold for these
     * ({@code acceptance_outdated}).
     */
    public static final String RULES_VERSION = "2";
    /** The quality target of the programme: a lower bound of 99 % (one-sided, 95 % confidence) on the sampled reviews. */
    public static final double TARGET = 0.99;
    /** The interim stage: 95 % (59 error-free reviews); never the operator release. */
    public static final double INTERIM = 0.95;
    /** Sampled applied corrections that may wait for their review before the apply mode only proposes again. */
    static final int MAX_AUDIT_BACKLOG = 20;
    static final Set<String> REVIEWED = Set.of("approved", "rejected", "correct", "wrong");
    static final int MAX_SIGNALS = 50;

    /** One signal about a domain. */
    public static final class Signal {
        public final String family;
        public final String verdict;
        public final String collection;
        /** Where it comes from; every signal derived from the website itself has the origin {@code website:<domain>}. */
        public final String origin;
        public final String kind;
        public final String evidence;
        public final String revision;
        public final String criteria;

        Signal(final String family, final String verdict, final String collection, final String origin, final String kind,
                final String evidence, final String revision, final String criteria) {
            this.family = family;
            this.verdict = verdict;
            this.collection = collection;
            this.origin = origin;
            this.kind = kind;
            this.evidence = evidence;
            this.revision = revision;
            this.criteria = criteria;
        }

        /** Validated from JSON; the website's own families get the website origin. Throws IllegalArgumentException(field). */
        public static Signal of(final JSONObject o, final String domain) {
            final String family = o.optString("family", "");
            if (!FAMILIES.contains(family)) throw new IllegalArgumentException("family");
            final String verdict = o.optString("verdict", "");
            if (!AGAINST.equals(verdict) && !FOR.equals(verdict)) throw new IllegalArgumentException("verdict");
            final String collection = o.optString("collection", "");
            if (!CollectionDecision.COLLECTION.matcher(collection).matches()) throw new IllegalArgumentException("collection");
            final String kind = o.optString("kind", "").toLowerCase(Locale.ROOT);
            if (NO_SIGNAL.contains(kind)) throw new IllegalArgumentException("kind");
            final String evidence = o.optString("evidence", "").trim();
            if (evidence.isEmpty() || evidence.length() > 500) throw new IllegalArgumentException("evidence");
            String origin = o.optString("origin", "").trim().toLowerCase(Locale.ROOT);
            if (!ORIGIN.equals(family)) {
                origin = "website:" + domain; // structured data, text and an LLM reading the site are one origin
            } else if (origin.isEmpty() || origin.startsWith("website:") || origin.length() > 120 || origin.indexOf(':') < 1) {
                throw new IllegalArgumentException("origin");
            } else if (origin.contains(domain) || WEBSITE_KINDS.contains(kind)) {
                // an entry read from the site itself (its imprint, its own page) depends on the website: the same origin
                origin = "website:" + domain;
            }
            return new Signal(family, verdict, collection, origin, kind.isEmpty() ? null : kind, evidence,
                    clip(o.optString("revision", null), 80), clip(o.optString("criteria", null), 40));
        }

        JSONObject toJson() {
            return obj("family", this.family, "verdict", this.verdict, "collection", this.collection, "origin", this.origin, "kind",
                    this.kind, "evidence", this.evidence, "revision", this.revision, "criteria", this.criteria);
        }

        static Signal fromJson(final JSONObject o) {
            return new Signal(o.optString("family"), o.optString("verdict"), o.optString("collection"), o.optString("origin"),
                    o.isNull("kind") ? null : o.optString("kind", null), o.optString("evidence"), o.isNull("revision") ? null
                            : o.optString("revision", null), o.isNull("criteria") ? null : o.optString("criteria", null));
        }

        String key() {
            return this.family + "|" + this.verdict + "|" + this.collection + "|" + this.origin + "|" + this.kind + "|"
                    + this.revision + "|" + this.criteria;
        }
    }

    /** What the rules conclude for a domain. */
    public static final class Outcome {
        public final String result;
        public final String reason;
        public final String from;
        public final String target;
        public final String severity;
        public final String fingerprint;
        public final List<Signal> signals;
        public final Set<String> againstOrigins;
        /** Signals that count with another origin than their own: the reason (same source system, same evidence). */
        public final List<String> dependencies = new ArrayList<>();

        Outcome(final String result, final String reason, final String from, final String target, final String severity,
                final String fingerprint, final List<Signal> signals, final Set<String> againstOrigins) {
            this.result = result;
            this.reason = reason;
            this.from = from;
            this.target = target;
            this.severity = severity;
            this.fingerprint = fingerprint;
            this.signals = signals;
            this.againstOrigins = againstOrigins;
        }

        Outcome with(final List<String> dependencies) {
            this.dependencies.addAll(dependencies);
            return this;
        }

        JSONObject toJson() {
            final JSONArray s = new JSONArray();
            for (final Signal x : this.signals) s.put(x.toJson());
            return obj("result", this.result, "reason", this.reason, "from", this.from, "target", this.target, "severity", this.severity,
                    "fingerprint", this.fingerprint, "independentOrigins", new JSONArray(this.againstOrigins), "dependencies",
                    new JSONArray(this.dependencies), "signals", s);
        }
    }

    // ------------------------------------------------------------------------------------------------------- rules

    /**
     * The rules for one domain whose specialist collection is {@code current} ({@code memberships}: every collection of
     * the domain; {@code manual}: a manual decision governs the domain).
     */
    public static Outcome evaluate(final String current, final List<String> memberships, final List<Signal> signals, final String fallback,
            final boolean manual) {
        final List<String> dependencies = new ArrayList<>();
        final Map<Signal, String> group = groups(signals, dependencies);
        return evaluate(current, memberships, signals, fallback, manual, group).with(dependencies);
    }

    /**
     * The independent origin of each signal (the dependency check of the signals' sources): every signal of the website is
     * {@code website:<domain>}; an origin signal counts with its source system ({@code osm:way/1} and {@code osm:node/7} are
     * both OpenStreetMap, {@code osm}); a signal whose evidence another origin already states is a copy and counts with that
     * origin. {@code dependencies} gets the reason for each signal that counts with another origin than its own.
     */
    static Map<Signal, String> groups(final List<Signal> signals, final List<String> dependencies) {
        final Map<Signal, String> out = new java.util.IdentityHashMap<>();
        final Map<String, String> byEvidence = new java.util.HashMap<>();
        final Map<String, Set<String>> systems = new TreeMap<>();
        for (final Signal s : signals) {
            final boolean website = s.origin.startsWith("website:");
            String g = website ? s.origin : s.origin.substring(0, s.origin.indexOf(':'));
            if (!website) systems.computeIfAbsent(g, k -> new TreeSet<>()).add(s.origin);
            final String evidence = s.evidence.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
            final String first = byEvidence.putIfAbsent(evidence, g);
            if (first != null && !first.equals(g)) {
                dependencies.add(s.origin + " counts as " + first + ": the same evidence");
                g = first;
            }
            out.put(s, g);
        }
        for (final Map.Entry<String, Set<String>> e : systems.entrySet()) {
            if (e.getValue().size() > 1) dependencies.add(String.join(", ", e.getValue()) + " count once as " + e.getKey() + ": one source system");
        }
        return out;
    }

    private static Outcome evaluate(final String current, final List<String> memberships, final List<Signal> signals, final String fallback,
            final boolean manual, final Map<Signal, String> group) {
        final String fingerprint = fingerprint(current, signals);
        final List<Signal> about = new ArrayList<>(signals);
        if (manual) return new Outcome(NONE, "manual_decision", current, null, null, fingerprint, about, Set.of());
        if (current == null || current.equals(fallback)) {
            return new Outcome(NONE, "no_specialist_collection", current, null, null, fingerprint, about, Set.of());
        }
        final Set<String> specialist = new TreeSet<>(memberships);
        specialist.remove(fallback);
        // origin -> stance about the current collection
        final Map<String, Set<String>> against = new TreeMap<>(), forCurrent = new TreeMap<>();
        for (final Signal s : signals) {
            if (!s.collection.equals(current)) continue;
            (AGAINST.equals(s.verdict) ? against : forCurrent).computeIfAbsent(group.get(s), k -> new LinkedHashSet<>()).add(s.family);
        }
        if (against.isEmpty()) return new Outcome(NONE, "no_signal_against", current, null, null, fingerprint, about, Set.of());
        if (!forCurrent.isEmpty()) {
            return new Outcome(REVIEW, "contradicting_signals", current, null, null, fingerprint, about, against.keySet());
        }
        if (specialist.size() > 1) {
            return new Outcome(REVIEW, "multiple_assignment", current, null, null, fingerprint, about, against.keySet());
        }
        if (!independent(against)) {
            return new Outcome(REVIEW, against.size() < 2 ? "one_origin_only" : "no_structured_or_text_signal", current, null, null,
                    fingerprint, about, against.keySet());
        }
        // the target: a specialist collection for which the same rule holds, else the fallback
        final Map<String, Map<String, Set<String>>> forTarget = new TreeMap<>(), againstTarget = new TreeMap<>();
        for (final Signal s : signals) {
            if (s.collection.equals(current)) continue;
            (FOR.equals(s.verdict) ? forTarget : againstTarget).computeIfAbsent(s.collection, k -> new TreeMap<>())
                    .computeIfAbsent(group.get(s), k -> new LinkedHashSet<>()).add(s.family);
        }
        String target = null;
        for (final Map.Entry<String, Map<String, Set<String>>> e : forTarget.entrySet()) {
            if (e.getKey().equals(fallback) || againstTarget.containsKey(e.getKey()) || !independent(e.getValue())) continue;
            if (target != null) {
                return new Outcome(REVIEW, "several_targets", current, null, null, fingerprint, about, against.keySet());
            }
            target = e.getKey();
        }
        return new Outcome(CORRECT, target == null ? "fallback_target" : "specialist_target", current, target == null ? fallback : target,
                target == null ? "medium" : "high", fingerprint, about, against.keySet());
    }

    /** Two independent origins, one of them with a structured or text-fit signal; LLM and origin signals never suffice. */
    static boolean independent(final Map<String, Set<String>> byOrigin) {
        if (byOrigin.size() < 2) return false;
        for (final Set<String> families : byOrigin.values()) {
            if (families.contains(STRUCTURED) || families.contains(TEXT_FIT)) return true;
        }
        return false;
    }

    static String fingerprint(final String current, final List<Signal> signals) {
        final TreeSet<String> keys = new TreeSet<>();
        for (final Signal s : signals) keys.add(s.key());
        return sha(current + "\n" + String.join("\n", keys));
    }

    private static Set<String> revisions(final List<Signal> signals) {
        final TreeSet<String> out = new TreeSet<>();
        for (final Signal s : signals) if (s.revision != null) out.add(s.origin + "@" + s.revision);
        return out;
    }

    // ------------------------------------------------------------------------------------------------------ state

    /** What applies a correction: a collection decision (automatic, or manual when approved by an administrator). */
    public interface Applier {
        JSONObject apply(String domain, String from, String target, JSONObject evidence, boolean automatic, String actor) throws Exception;
    }

    private final File file;
    private final LongSupplier clock;
    private final java.util.Random random;
    private JSONObject state;
    private String problem;

    public AutoCorrect(final File file, final LongSupplier clock) {
        this(file, clock, new java.security.SecureRandom());
    }

    /** With a given random source (tests); the sample and the audit draw from it. */
    AutoCorrect(final File file, final LongSupplier clock, final java.util.Random random) {
        this.file = file;
        this.clock = clock;
        this.random = random;
        load();
    }

    private synchronized void load() {
        this.problem = null;
        if (!this.file.exists()) {
            this.state = empty();
            return;
        }
        try {
            final JSONObject o = new JSONObject(new String(Files.readAllBytes(this.file.toPath()), StandardCharsets.UTF_8));
            if (o.optInt("schema") != 1 || o.optJSONObject("cases") == null) throw new JSONException("schema");
            this.state = o;
        } catch (final IOException | JSONException e) {
            // fail-safe: an unreadable state never applies anything, it reports itself
            this.state = empty();
            this.problem = "state_unreadable: " + e.getMessage();
        }
    }

    private static JSONObject empty() {
        return obj("schema", 1, "cases", new JSONObject(), "breaker", obj("open", false), "day", obj("day", "", "applied", 0),
                "acceptance", obj("accepted", false));
    }

    private void save() throws IOException {
        final File tmp = new File(this.file.getParentFile(), this.file.getName() + ".tmp");
        this.file.getParentFile().mkdirs();
        Files.write(tmp.toPath(), this.state.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), this.file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /** True while the state cannot be read: nothing is recorded or applied. */
    public synchronized boolean unavailable() {
        return this.problem != null;
    }

    /**
     * Evaluates a domain and acts as {@code mode} says; returns the outcome and the action ({@code none}, {@code recorded},
     * {@code proposed}, {@code applied}, {@code unchanged}, {@code refused:<why>}).
     */
    public synchronized JSONObject submit(final String domain, final String current, final List<String> memberships, final List<Signal> signals,
            final String fallback, final boolean manual, final Mode mode, final int maxPerDay, final Applier applier, final String actor)
            throws IOException {
        return submit(domain, current, memberships, signals, fallback, manual, mode, maxPerDay, DEFAULT_AUDIT_RATE, applier, actor);
    }

    /** The share of automatic corrections drawn for the audit when nothing else is configured. */
    public static final double DEFAULT_AUDIT_RATE = 0.1;

    /** {@link #submit} with the share of automatic corrections that is drawn for the audit ({@code auditRate}, 0 to 1). */
    public synchronized JSONObject submit(final String domain, final String current, final List<String> memberships, final List<Signal> signals,
            final String fallback, final boolean manual, final Mode mode, final int maxPerDay, final double auditRate, final Applier applier,
            final String actor) throws IOException {
        final Outcome o = evaluate(current, memberships, signals, fallback, manual);
        final JSONObject out = obj("domain", domain, "mode", mode.name().toLowerCase(Locale.ROOT), "outcome", o.toJson());
        if (mode == Mode.OFF) return put(out, "action", "none");
        if (this.problem != null) return put(out, "action", "refused:state_unreadable");
        final JSONObject cases = this.state.optJSONObject("cases");
        final JSONObject before = cases.optJSONObject(domain);
        if (before != null && o.fingerprint.equals(before.optString("fingerprint"))) return put(out, "action", "unchanged");
        if (before != null && "applied".equals(before.optString("status")) && o.result.equals(CORRECT)) {
            // hysteresis: an automatically decided domain changes again only with a new revision and a new signal set
            final Set<String> was = new TreeSet<>();
            final JSONArray r = before.optJSONArray("revisions");
            for (int i = 0; r != null && i < r.length(); i++) was.add(r.optString(i));
            if (was.equals(revisions(signals))) return put(out, "action", "unchanged");
        }
        if (before != null && NONE.equals(o.result)) return put(out, "action", "unchanged"); // nothing to do never replaces a case
        if (cases.length() >= MAX_CASES && before == null) return put(out, "action", "refused:too_many_cases");
        if (before != null && REVIEWED.contains(before.optString("status"))) {
            // a reviewed case is a measurement of the acceptance: kept in the history, never overwritten
            final JSONArray history = this.state.optJSONArray("history") == null ? new JSONArray() : this.state.optJSONArray("history");
            history.put(before);
            while (history.length() > MAX_CASES) history.remove(0);
            put(this.state, "history", history);
        }
        final JSONObject c = obj("domain", domain, "from", o.from, "target", o.target, "result", o.result, "reason", o.reason,
                "severity", o.severity, "fingerprint", o.fingerprint, "revisions", new JSONArray(revisions(signals)), "signals",
                o.toJson().optJSONArray("signals"), "at", this.clock.getAsLong(), "mode", mode.name().toLowerCase(Locale.ROOT));
        String action;
        if (mode == Mode.SIMULATE || NONE.equals(o.result)) {
            put(c, "status", "simulated");
            action = "recorded";
        } else if (mode == Mode.PROPOSE || REVIEW.equals(o.result)) {
            put(c, "status", "proposed");
            action = "proposed";
        } else {
            final String why = refusal(maxPerDay);
            if (why != null) {
                put(c, "status", "proposed");
                put(c, "held", why);
                action = "proposed";
            } else {
                try {
                    final JSONObject decision = applier.apply(domain, o.from, o.target, evidence(o), true, "autocorrect");
                    put(c, "status", "applied");
                    put(c, "decision", decision);
                    // the audit: a random share of the automatic corrections goes to the review (a wrong one opens the breaker)
                    if (this.random.nextDouble() < auditRate) {
                        put(c, "sample", obj("at", this.clock.getAsLong(), "by", "audit", "audit", true));
                    }
                    final JSONObject day = this.state.optJSONObject("day");
                    put(day, "applied", day.optInt("applied") + 1);
                    action = "applied";
                } catch (final Exception e) {
                    put(c, "status", "proposed");
                    put(c, "held", "apply_failed: " + clip(String.valueOf(e.getMessage()), 200));
                    action = "proposed";
                }
            }
        }
        put(cases, domain, c);
        save();
        return put(put(out, "action", action), "case", c);
    }

    /** Why the apply mode may not act now, or null. */
    private String refusal(final int maxPerDay) {
        if (this.state.optJSONObject("breaker").optBoolean("open")) return "breaker_open";
        final JSONObject acceptance = this.state.optJSONObject("acceptance");
        if (!acceptance.optBoolean("accepted")) return "not_accepted";
        if (!RULES_VERSION.equals(acceptance.optString("rulesVersion"))) return "acceptance_outdated";
        if (stats().optJSONObject("audit").optInt("pending") >= MAX_AUDIT_BACKLOG) return "audit_backlog";
        final JSONObject day = this.state.optJSONObject("day");
        final String today = java.time.Instant.ofEpochMilli(this.clock.getAsLong()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
        if (!today.equals(day.optString("day"))) {
            put(day, "day", today);
            put(day, "applied", 0);
        }
        if (day.optInt("applied") >= maxPerDay) return "daily_limit";
        return null;
    }

    private static JSONObject evidence(final Outcome o) {
        return obj("autocorrect", obj("fingerprint", o.fingerprint, "reason", o.reason, "severity", o.severity, "origins",
                new JSONArray(o.againstOrigins), "signals", o.toJson().optJSONArray("signals")));
    }

    /**
     * An administrator's verdict on a case: {@code approve} or {@code reject} a proposal (approve applies it as a manual
     * decision, to {@code target} if given), {@code correct} or {@code wrong} for an applied one ({@code wrong} opens the
     * breaker).
     */
    public synchronized JSONObject review(final String domain, final String verdict, final String target, final String note, final Applier applier,
            final String actor) throws Exception {
        if (this.problem != null) throw new IllegalStateException("state_unreadable");
        final JSONObject c = this.state.optJSONObject("cases").optJSONObject(domain);
        if (c == null) throw new IllegalArgumentException("case_unknown");
        final String status = c.optString("status");
        switch (verdict) {
            case "approve":
            case "reject":
                if (!"proposed".equals(status) && !"simulated".equals(status)) throw new IllegalArgumentException("case_not_open");
                if ("approve".equals(verdict)) {
                    if (!CORRECT.equals(c.optString("result")) && !REVIEW.equals(c.optString("result"))) throw new IllegalArgumentException("nothing_to_apply");
                    // the administrator may name the target (a case for review has none)
                    final String to = target != null ? target : c.isNull("target") ? null : c.optString("target", null);
                    if (to == null) throw new IllegalArgumentException("target_needed");
                    put(c, "target", to);
                    put(c, "decision", applier.apply(domain, c.optString("from"), to, obj("autocorrect", obj("fingerprint",
                            c.optString("fingerprint"), "reviewedBy", actor, "signals", c.optJSONArray("signals"))), false, actor));
                    put(c, "status", "approved");
                } else {
                    put(c, "status", "rejected");
                }
                break;
            case "correct":
            case "wrong":
                // a judgement: of an applied correction, or of a recorded or proposed one without applying it (the sample)
                if (!"applied".equals(status) && !"simulated".equals(status) && !"proposed".equals(status)) {
                    throw new IllegalArgumentException("case_not_open");
                }
                if (!CORRECT.equals(c.optString("result")) && !"applied".equals(status)) throw new IllegalArgumentException("nothing_to_judge");
                put(c, "status", verdict);
                if ("wrong".equals(verdict) && "applied".equals(status)) {
                    put(this.state, "breaker", obj("open", true, "reason", "case_marked_wrong", "domain", domain, "at", this.clock.getAsLong(),
                            "by", actor));
                }
                break;
            default:
                throw new IllegalArgumentException("verdict");
        }
        put(c, "reviewedBy", actor);
        put(c, "reviewedAt", this.clock.getAsLong());
        if (note != null) put(c, "note", clip(note, 300));
        save();
        return c;
    }

    /** Closes the breaker again (an administrator, after looking at the wrong case). */
    public synchronized JSONObject resetBreaker(final String actor) throws IOException {
        if (this.problem != null) throw new IllegalStateException("state_unreadable");
        put(this.state, "breaker", obj("open", false, "resetBy", actor, "at", this.clock.getAsLong()));
        save();
        return this.state.optJSONObject("breaker");
    }

    // -------------------------------------------------------------------------------------------- acceptance

    /** Every case, current and kept in the history. */
    private List<JSONObject> allCases() {
        final List<JSONObject> all = new ArrayList<>();
        final JSONObject cases = this.state.optJSONObject("cases");
        for (final String domain : cases.keySet()) all.add(cases.optJSONObject(domain));
        final JSONArray history = this.state.optJSONArray("history");
        for (int i = 0; history != null && i < history.length(); i++) all.add(history.optJSONObject(i));
        return all;
    }

    private static boolean right(final JSONObject c) {
        return "approved".equals(c.optString("status")) || "correct".equals(c.optString("status"));
    }

    private static boolean wrong(final JSONObject c) {
        return "rejected".equals(c.optString("status")) || "wrong".equals(c.optString("status"));
    }

    /**
     * Precision of a set of corrections: reviewed, right, its one-sided 95 % Clopper-Pearson lower bound, errors by severity,
     * the cases still to review, and how many error-free reviews more the target {@link #TARGET} needs.
     */
    private static JSONObject precision(final List<JSONObject> corrections) {
        int reviewed = 0, right = 0, pending = 0;
        final Map<String, Integer> errors = new TreeMap<>(Map.of("medium", 0, "high", 0));
        for (final JSONObject c : corrections) {
            final boolean ok = right(c), bad = wrong(c);
            if (ok || bad) reviewed++;
            else pending++;
            if (ok) right++;
            if (bad) errors.merge(c.optString("severity", "medium"), 1, Integer::sum);
        }
        final double lb = reviewed == 0 ? 0.0 : clopperPearsonLower(right, reviewed, 0.05);
        return obj("reviewed", reviewed, "right", right, "pending", pending, "precision", reviewed == 0 ? null : (double) right / reviewed,
                "lowerBound95", reviewed == 0 ? null : lb, "errorsBySeverity", new JSONObject(errors),
                "reviewsForTarget", reviewsNeeded(right, reviewed, TARGET), "reviewsForInterim", reviewsNeeded(right, reviewed, INTERIM),
                "stage", lb >= TARGET ? "target_99" : lb >= INTERIM ? "interim_95" : "none");
    }

    /**
     * Error-free reviews still needed until the lower bound reaches {@code target}, assuming every further review is right;
     * -1 if more than 100 000 (with many errors the target is out of reach).
     */
    public static int reviewsNeeded(final int right, final int reviewed, final double target) {
        if (reviewed > 0 && clopperPearsonLower(right, reviewed, 0.05) >= target) return 0;
        int lo = 1, hi = 1;
        while (clopperPearsonLower(right + hi, reviewed + hi, 0.05) < target) {
            hi *= 2;
            if (hi > 100_000) return -1;
        }
        while (lo < hi) {
            final int mid = (lo + hi) / 2;
            if (clopperPearsonLower(right + mid, reviewed + mid, 0.05) >= target) hi = mid;
            else lo = mid + 1;
        }
        return lo;
    }

    /**
     * Statistics of the corrections: {@code all} reviewed ones (also chosen by hand, so never the acceptance), the random
     * {@code sample} the acceptance is measured on, the {@code audit} of automatic corrections after the acceptance, and
     * coverage (corrections among all evaluated cases). Top-level fields repeat {@code all} for older readers.
     */
    public synchronized JSONObject stats() {
        int evaluated = 0, review = 0;
        final List<JSONObject> corrections = new ArrayList<>(), sample = new ArrayList<>(), audit = new ArrayList<>();
        for (final JSONObject c : allCases()) {
            evaluated++;
            if (REVIEW.equals(c.optString("result"))) review++;
            if (!CORRECT.equals(c.optString("result"))) continue;
            corrections.add(c);
            final JSONObject drawn = c.optJSONObject("sample");
            if (drawn != null) (drawn.optBoolean("audit") ? audit : sample).add(c);
        }
        final JSONObject all = precision(corrections);
        final JSONObject out = obj("evaluated", evaluated, "corrections", corrections.size(), "review", review, "reviewed", all.optInt("reviewed"),
                "right", all.optInt("right"), "precision", all.opt("precision"), "lowerBound95", all.opt("lowerBound95"),
                "errorsBySeverity", all.optJSONObject("errorsBySeverity"), "coverage", evaluated == 0 ? null : (double) corrections.size() / evaluated,
                "all", all, "sample", precision(sample), "audit", precision(audit), "target", TARGET, "interim", INTERIM,
                "rulesVersion", RULES_VERSION);
        return out;
    }

    /**
     * Draws {@code size} corrections at random (uniform, without replacement) from those not drawn and not reviewed yet: the
     * sample the acceptance is measured on, so that no one chooses the cases that are reviewed. Returns the draw.
     */
    public synchronized JSONObject sample(final int size, final String actor) throws IOException {
        if (this.problem != null) throw new IllegalStateException("state_unreadable");
        final List<JSONObject> open = new ArrayList<>();
        final JSONObject cases = this.state.optJSONObject("cases");
        for (final String domain : new TreeSet<>(cases.keySet())) {
            final JSONObject c = cases.optJSONObject(domain);
            if (CORRECT.equals(c.optString("result")) && c.optJSONObject("sample") == null && !right(c) && !wrong(c)
                    && !"applied".equals(c.optString("status"))) {
                open.add(c);
            }
        }
        java.util.Collections.shuffle(open, this.random);
        final int n = Math.max(0, Math.min(size, open.size()));
        final JSONArray drawn = new JSONArray();
        final long now = this.clock.getAsLong();
        for (final JSONObject c : open.subList(0, n)) {
            put(c, "sample", obj("at", now, "by", actor, "population", open.size()));
            drawn.put(c.optString("domain"));
        }
        final JSONObject draw = obj("at", now, "by", actor, "population", open.size(), "drawn", n, "domains", drawn);
        final JSONArray draws = this.state.optJSONArray("draws") == null ? new JSONArray() : this.state.optJSONArray("draws");
        draws.put(draw);
        while (draws.length() > 100) draws.remove(0);
        put(this.state, "draws", draws);
        save();
        return draw;
    }

    /**
     * Accepts the apply mode if the <b>random sample</b> meets the thresholds: every drawn case reviewed (no case chosen or
     * left out by hand), at least {@code minReviewed} reviewed, a one-sided 95 % lower bound of at least {@code minLowerBound},
     * no error of high severity at all. The acceptance holds for {@link #RULES_VERSION}. A bound below {@link #TARGET} is
     * recorded as the interim stage, never as the operator release. Otherwise nothing changes.
     */
    public synchronized JSONObject accept(final int minReviewed, final double minLowerBound, final String actor) throws IOException {
        if (this.problem != null) throw new IllegalStateException("state_unreadable");
        final JSONObject st = stats();
        final JSONObject s = st.optJSONObject("sample");
        final List<String> missing = new ArrayList<>();
        if (s.optInt("pending") > 0) missing.add(s.optInt("pending") + " drawn cases not reviewed");
        if (s.optInt("reviewed") < minReviewed) missing.add("reviewed in the sample " + s.optInt("reviewed") + " < " + minReviewed);
        final double lb = s.isNull("lowerBound95") ? 0.0 : s.optDouble("lowerBound95");
        if (lb < minLowerBound) missing.add("lower bound " + String.format(Locale.ROOT, "%.4f", lb) + " < " + minLowerBound);
        if (st.optJSONObject("all").optJSONObject("errorsBySeverity").optInt("high") > 0) missing.add("errors of high severity");
        final String stage = lb >= TARGET ? "target_99" : lb >= INTERIM ? "interim_95" : "none";
        final JSONObject a = obj("accepted", missing.isEmpty(), "at", this.clock.getAsLong(), "by", actor, "stats", st, "minReviewed",
                minReviewed, "minLowerBound", minLowerBound, "stage", stage, "operatorRelease", missing.isEmpty() && lb >= TARGET,
                "rulesVersion", RULES_VERSION, "missing", new JSONArray(missing));
        if (missing.isEmpty()) {
            put(this.state, "acceptance", a);
            save();
        }
        return a;
    }

    /** Withdraws the acceptance (the apply mode proposes again). */
    public synchronized void revokeAcceptance(final String actor) throws IOException {
        if (this.problem != null) throw new IllegalStateException("state_unreadable");
        put(this.state, "acceptance", obj("accepted", false, "revokedBy", actor, "at", this.clock.getAsLong()));
        save();
    }

    /** The whole state for the report: settings are added by the caller. */
    public synchronized JSONObject report(final int maxCases) {
        final JSONArray items = new JSONArray();
        final JSONObject cases = this.state.optJSONObject("cases");
        final List<JSONObject> all = new ArrayList<>();
        for (final String domain : cases.keySet()) all.add(cases.optJSONObject(domain));
        all.sort((a, b) -> Long.compare(b.optLong("at"), a.optLong("at")));
        final Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (final JSONObject c : all) {
            byStatus.merge(c.optString("status"), 1, Integer::sum);
            if (items.length() < maxCases) items.put(c);
        }
        return obj("available", this.problem == null, "problem", this.problem, "breaker", this.state.optJSONObject("breaker"),
                "acceptance", this.state.optJSONObject("acceptance"), "today", this.state.optJSONObject("day"), "stats", stats(), "byStatus",
                new JSONObject(byStatus), "cases", items);
    }

    // ----------------------------------------------------------------------------------------------------- stats

    /**
     * The one-sided lower confidence bound of a binomial proportion (Clopper-Pearson): the p with
     * P(X >= k | n, p) = alpha; 0 for k = 0. Bisection on the binomial tail, exact to 1e-9.
     */
    public static double clopperPearsonLower(final int k, final int n, final double alpha) {
        if (n <= 0 || k <= 0) return 0.0;
        if (k == n) return Math.pow(alpha, 1.0 / n);
        double lo = 0.0, hi = 1.0;
        for (int i = 0; i < 200 && hi - lo > 1e-12; i++) {
            final double mid = (lo + hi) / 2;
            if (upperTail(k, n, mid) < alpha) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** P(X >= k) for X ~ Binomial(n, p), in log space; each term from the one before (O(n)). */
    static double upperTail(final int k, final int n, final double p) {
        if (p <= 0.0) return k <= 0 ? 1.0 : 0.0;
        if (p >= 1.0) return 1.0;
        final double lp = Math.log(p), lq = Math.log1p(-p);
        double log = logChoose(n, k) + k * lp + (n - k) * lq;
        double sum = 0.0;
        for (int i = k; i <= n; i++) {
            sum += Math.exp(log);
            if (i < n) log += Math.log(n - i) - Math.log(i + 1) + lp - lq;
        }
        return Math.min(1.0, sum);
    }

    private static double logChoose(final int n, final int k) {
        double s = 0.0;
        for (int i = 1; i <= k; i++) s += Math.log(n - k + i) - Math.log(i);
        return s;
    }

    // --------------------------------------------------------------------------------------------------- helpers

    static JSONObject obj(final Object... kv) {
        final JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) put(o, (String) kv[i], kv[i + 1]);
        return o;
    }

    static JSONObject put(final JSONObject o, final String k, final Object v) {
        try {
            o.put(k, v == null ? JSONObject.NULL : v);
        } catch (final JSONException e) {
            throw new IllegalArgumentException(k, e);
        }
        return o;
    }

    private static String clip(final String s, final int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    private static String sha(final String s) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
