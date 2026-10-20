/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * The plausibility scan of the indexed domains (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md, "Plausibility scan"): a step of at
 * most 200 domains on an administrator's call, no daemon, no model, nothing written to the graph. Each domain is assessed
 * in each of its specialist collections from the graph's rules (structured data, text fit, plausibility), the stored
 * Discovery classifications and the home page's reading by the model; the result is kept per domain, collection and source
 * in the correction state, and a domain with a signal against one of its collections is submitted to the correction
 * automation once, through its own rules (a scan has only the website's origin and the OSM signals of the domain's last
 * case: it never makes a correction that would not be made without it).
 * <p>
 * Two fingerprints per domain and collection: the rules' (pages, vocabularies, rules version, collections, the domain's
 * collection decision) decides whether the pages are read again, the input's (the rules' plus the Discovery classification,
 * the home page's reading and the case's OSM signals) whether the domain is assessed again at all. A step pauses when the
 * graph does not run, is paused or a gate is closed; the cursor is kept, the next call goes on from it.
 */
final class PlausibilityScan {

    static final int MAX_DOMAINS = 200;
    static final int DEFAULT_DOMAINS = 50;
    /** Rule signals kept per assessment (the ones against first). */
    static final int MAX_RULE_SIGNALS = 10;

    /** The knowledge graph as the scan reads it ({@link KgRuntime}); read only. */
    interface Graph {
        /** Why the scan may not read now (graph off or paused, a gate closed), or null. */
        String busy();

        SortedMap<String, KgRuntime.DomainPages> domains();

        String pagesFingerprint(String domain, Collection<String> hosts);

        String vocabularyVersion();

        JSONObject textFit(String domain, Collection<String> collections, Collection<String> hosts);

        JSONObject offerProfile(String domain, Collection<String> collections, Collection<String> hosts);

        List<String[]> structuredIndustries(String domain);

        Map<String, Set<String>> collectionNace(Collection<String> collections);

        /** The signals of the home page's reading by the model that are current for the domain (none by default). */
        default List<AutoCorrect.Signal> homeSignals(final String domain, final JSONObject assessments, final Collection<String> hosts) {
            return List.of();
        }
    }

    /** Submits a domain to the correction automation in the configured mode; returns its answer. */
    interface Submitter {
        JSONObject submit(String domain, String current, List<String> memberships, List<AutoCorrect.Signal> signals, boolean manual) throws IOException;
    }

    /** The collection decisions of a domain as the fingerprint sees them, and whether one is manual. */
    interface Decisions {
        /** {id:revision:decidedAt, ...} of the domain's active decisions, sorted. */
        List<String> revisions(String domain);

        boolean manual(String domain);
    }

    private final AutoCorrect engine;
    private final Graph graph;
    private final Supplier<Map<String, DomainEnrichment.DomainState>> discovery;
    private final Decisions decisions;
    private final Supplier<Collection<String>> catalog;
    private final String fallback;
    private final Submitter submitter;
    private final boolean submitting;
    private final LongSupplier clock;

    PlausibilityScan(final AutoCorrect engine, final Graph graph, final Supplier<Map<String, DomainEnrichment.DomainState>> discovery,
            final Decisions decisions, final Supplier<Collection<String>> catalog, final String fallback, final Submitter submitter,
            final boolean submitting, final LongSupplier clock) {
        this.engine = engine;
        this.graph = graph;
        this.discovery = discovery;
        this.decisions = decisions;
        this.catalog = catalog;
        this.fallback = fallback;
        this.submitter = submitter;
        this.submitting = submitting;
        this.clock = clock;
    }

    /** Thrown when the scan may not run now; the cursor stays. */
    static final class Busy extends Exception {
        private static final long serialVersionUID = 1L;
        final String reason;

        Busy(final String reason) {
            super(reason);
            this.reason = reason;
        }
    }

    /**
     * One step: the next {@code max} domains after the cursor (of {@code collection} only, if given), or exactly
     * {@code domains} (the cursor stays), or from the start with {@code restart}. Writes the correction state once.
     */
    JSONObject step(final int max, final String collection, final boolean restart, final List<String> domains) throws Busy, IOException {
        final String busy = this.graph.busy();
        if (busy != null) throw new Busy(busy);
        final SortedMap<String, KgRuntime.DomainPages> all = this.graph.domains();
        final JSONObject scan = this.engine.scanState();
        String cursor = restart ? null : scan.optString("cursor", null);
        if (cursor != null && cursor.isEmpty()) cursor = null;
        final List<String> todo = new ArrayList<>();
        final boolean targeted = domains != null;
        if (targeted) {
            todo.addAll(domains);
        } else {
            for (final Map.Entry<String, KgRuntime.DomainPages> e : (cursor == null ? all : all.tailMap(cursor + "\u0000")).entrySet()) {
                if (todo.size() >= max) break;
                if (!specialist(e.getValue().collections).isEmpty() && (collection == null || e.getValue().collections.contains(collection))) {
                    todo.add(e.getKey());
                }
            }
        }
        final Map<String, Integer> counters = new TreeMap<>();
        final Map<String, Integer> actions = new TreeMap<>();
        final Map<String, String> notFound = new LinkedHashMap<>();
        final long now = this.clock.getAsLong();
        final Map<String, DomainEnrichment.DomainState> states = this.discovery.get();
        for (final String domain : todo) {
            final KgRuntime.DomainPages pages = all.get(domain);
            if (pages == null) {
                notFound.put(domain, "not_in_graph");
                counters.merge("notInGraph", 1, Integer::sum);
                continue;
            }
            final String action = assess(domain, pages, states == null ? null : states.get(domain), now, counters);
            if (action != null) actions.merge(action, 1, Integer::sum);
        }
        boolean done = false;
        if (!targeted) {
            final String last = todo.isEmpty() ? null : todo.get(todo.size() - 1);
            final boolean more = last != null && hasMore(all, last, collection);
            final JSONObject next = Json.obj("cursor", more ? last : "", "pass", scan.optInt("pass", 1) + (restart ? 1 : 0), "startedAt",
                    restart || !scan.has("startedAt") || cursor == null ? now : scan.optLong("startedAt"), "updatedAt", now, "collection", collection,
                    "domains", (restart || cursor == null ? 0 : scan.optInt("domains")) + todo.size());
            if (!more) {
                done = true;
                Json.put(next, "completedAt", now);
                Json.put(next, "pass", next.optInt("pass") + 1); // the next call begins the next pass
            }
            this.engine.scanState(next);
        }
        this.engine.persist();
        return Json.obj("domains", todo.size(), "counters", new JSONObject(counters), "actions", new JSONObject(actions), "notFound",
                new JSONObject(notFound), "done", done, "targeted", targeted, "scan", this.engine.scanState());
    }

    private boolean hasMore(final SortedMap<String, KgRuntime.DomainPages> all, final String last, final String collection) {
        for (final Map.Entry<String, KgRuntime.DomainPages> e : all.tailMap(last + "\u0000").entrySet()) {
            if (!specialist(e.getValue().collections).isEmpty() && (collection == null || e.getValue().collections.contains(collection))) return true;
        }
        return false;
    }

    private List<String> specialist(final Collection<String> collections) {
        final List<String> out = new ArrayList<>(new TreeSet<>(collections));
        out.remove(this.fallback);
        return out;
    }

    /** Assesses one domain; returns the action of its submission, or null if it was not submitted. */
    private String assess(final String domain, final KgRuntime.DomainPages pages, final DomainEnrichment.DomainState state, final long now,
            final Map<String, Integer> counters) throws IOException {
        final List<String> memberships = new ArrayList<>(new TreeSet<>(pages.collections));
        final List<String> specialist = specialist(memberships);
        this.engine.memberships(domain, memberships);
        if (specialist.isEmpty()) {
            counters.merge("fallbackOnly", 1, Integer::sum);
            return null;
        }
        final TreeSet<String> ids = new TreeSet<>(this.catalog.get());
        ids.addAll(memberships);
        final List<String> ordered = new ArrayList<>(ids);
        final String base = this.graph.pagesFingerprint(domain, pages.hosts) + "|" + this.graph.vocabularyVersion() + "|" + AutoCorrect.RULES_VERSION
                + "|" + String.join(",", ids) + "|" + String.join(",", this.decisions.revisions(domain));
        final JSONObject stored = this.engine.assessments(domain);
        final List<AutoCorrect.Signal> origin = this.engine.originSignals(domain);
        final List<AutoCorrect.Signal> discovery = discoverySignals(domain, state);
        final List<AutoCorrect.Signal> home = this.graph.homeSignals(domain, stored, pages.hosts);
        final Map<String, String> rulesFp = new TreeMap<>(), inputFp = new TreeMap<>();
        boolean rulesChanged = false, inputChanged = false;
        for (final String c : specialist) {
            rulesFp.put(c, sha(base + "|" + c));
            inputFp.put(c, sha(rulesFp.get(c) + "|" + records(state) + "|" + keys(home) + "|" + keys(origin)));
            final JSONObject entry = stored.optJSONObject(c);
            final JSONObject rules = entry == null ? null : entry.optJSONObject(AutoCorrect.RULES);
            if (rules == null || !rulesFp.get(c).equals(rules.optString("rulesFingerprint"))) rulesChanged = true;
            if (entry == null || !inputFp.get(c).equals(entry.optString("inputFingerprint"))) inputChanged = true;
        }
        if (!rulesChanged && !inputChanged) {
            counters.merge("unchanged", 1, Integer::sum);
            return null;
        }
        // the rules' signals: read again only with a new fingerprint of the rules (the pages, never a model)
        final Map<String, List<AutoCorrect.Signal>> rules = new TreeMap<>();
        if (rulesChanged) {
            counters.merge("read", 1, Integer::sum);
            final JSONObject fit = this.graph.textFit(domain, ordered, pages.hosts);
            final JSONObject offers = this.graph.offerProfile(domain, ordered, pages.hosts);
            final List<String[]> industries = this.graph.structuredIndustries(domain);
            final Map<String, Set<String>> nace = this.graph.collectionNace(ordered);
            for (final String c : specialist) {
                final List<AutoCorrect.Signal> sig = new ArrayList<>(AutoCorrectApi.structuredSignals(domain, c, industries, nace));
                sig.addAll(AutoCorrectApi.textFitSignals(domain, c, fit));
                sig.addAll(AutoCorrectApi.plausibilitySignals(domain, c, offers, fit));
                rules.put(c, sig);
                final JSONObject mine = fit.optJSONObject("collections") == null ? null : fit.optJSONObject("collections").optJSONObject(c);
                final JSONArray kept = new JSONArray(), evidence = new JSONArray();
                for (final AutoCorrect.Signal s : againstFirst(sig)) {
                    if (kept.length() < MAX_RULE_SIGNALS) kept.put(json(s));
                    if (AutoCorrect.AGAINST.equals(s.verdict) && c.equals(s.collection) && evidence.length() < 3) evidence.put(s.evidence);
                }
                this.engine.assess(domain, c, AutoCorrect.RULES, Json.obj("result", AutoCorrectApi.assess(c, sig, fit), "rulesFingerprint",
                        rulesFp.get(c), "rulesVersion", AutoCorrect.RULES_VERSION, "signals", kept, "evidence", evidence, "offered",
                        mine == null ? null : mine.optInt("offered"), "topics", mine == null ? null : mine.optInt("topics"), "pages", fit.optInt("pages"),
                        "offers", offers.optJSONArray("services") == null ? 0 : offers.optJSONArray("services").length(), "at", now));
            }
        } else {
            counters.merge("reused", 1, Integer::sum);
            for (final String c : specialist) {
                final List<AutoCorrect.Signal> sig = new ArrayList<>();
                final JSONArray kept = stored.optJSONObject(c).optJSONObject(AutoCorrect.RULES).optJSONArray("signals");
                for (int i = 0; kept != null && i < kept.length(); i++) {
                    try {
                        sig.add(AutoCorrect.Signal.of(kept.optJSONObject(i), domain));
                    } catch (final IllegalArgumentException e) {
                        // a stored signal no longer valid is no signal
                    }
                }
                rules.put(c, sig);
            }
        }
        // the Discovery classification per collection (also one without a signal: an error or the heuristic changes the
        // fingerprint and is shown, but speaks for nothing)
        if (state != null) {
            for (final Object[] r : state.classifications.values()) {
                final String c = (String) r[3];
                if (c == null || !memberships.contains(c) || r.length < 10) continue;
                this.engine.assess(domain, c, AutoCorrect.DISCOVERY, Json.obj("result", r[0], "confidence", r[1], "profile", r[2], "backend", r[5],
                        "error", r[6], "criteriaVersion", r[7], "classifiedAt", r[9], "signal", usable(r), "at", now));
            }
        }
        final List<AutoCorrect.Signal> signals = new ArrayList<>();
        for (final List<AutoCorrect.Signal> sig : rules.values()) signals.addAll(sig);
        signals.addAll(discovery);
        signals.addAll(home);
        final List<AutoCorrect.Signal> unique = unique(signals);
        for (final String c : specialist) this.engine.inputFingerprint(domain, c, inputFp.get(c));
        // one submission per domain: the first specialist collection with a signal against it
        String current = null;
        for (final String c : specialist) {
            if (current == null && against(unique, c)) current = c;
        }
        if (current == null) {
            counters.merge("noSignalAgainst", 1, Integer::sum);
            return null;
        }
        counters.merge("suspect", 1, Integer::sum);
        if (!this.submitting) return null;
        final List<AutoCorrect.Signal> submitted = new ArrayList<>(againstFirst(unique));
        submitted.addAll(origin);
        final List<AutoCorrect.Signal> bounded = submitted.size() > AutoCorrect.MAX_SIGNALS_PUBLIC ? submitted.subList(0, AutoCorrect.MAX_SIGNALS_PUBLIC)
                : submitted;
        final JSONObject answer = this.submitter.submit(domain, current, memberships, new ArrayList<>(bounded), this.decisions.manual(domain));
        counters.merge("submitted", 1, Integer::sum);
        return answer == null ? "none" : answer.optString("action", "none");
    }

    /**
     * The signals of the home page's reading by the model (source {@code kg_home}) that are current: the page's content and
     * the profiles' criteria version unchanged. Per collection: {@code mismatch} against it, {@code match} for it,
     * {@code uncertain} nothing. The best fitting collection the model names is never a signal of its own, only a hint for
     * the reviewer in the evidence (an uncertain or unnamed collection spoken for would block a correction as
     * {@code contradicting_signals} or steer its target). Family {@code llm} (the website's origin), kind
     * {@code kg_home_fit}, revision the content hash, criteria the criteria version.
     *
     * @param contentHash the current content hash of a page (doc id -> hex), null if it is gone
     */
    static List<AutoCorrect.Signal> homeSignals(final String domain, final JSONObject assessments, final java.util.function.Function<String, String> contentHash,
            final String criteria) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        if (assessments == null || criteria == null) return out;
        for (final String c : new TreeSet<>(assessments.keySet())) {
            final JSONObject entry = assessments.optJSONObject(c);
            final JSONObject home = entry == null || entry.optBoolean("stale") ? null : entry.optJSONObject(AutoCorrect.KG_HOME);
            if (home == null || !criteria.equals(home.optString("criteriaVersion"))) continue;
            final String now = contentHash.apply(home.optString("docId", null));
            if (now == null || !now.equalsIgnoreCase(home.optString("contentHash"))) continue; // outdated: the page changed or is gone
            final String verdict = home.optString("result");
            if (!CollectionFitVerdicts.MISMATCH.equals(verdict) && !CollectionFitVerdicts.MATCH.equals(verdict)) continue; // uncertain: none
            final String best = home.optString("best", "none");
            final String hint = "none".equals(best) || best.equals(c) ? "" : " (best fitting collection named by the model, a hint only: " + best + ")";
            add(out, domain, CollectionFitVerdicts.MATCH.equals(verdict) ? AutoCorrect.FOR : AutoCorrect.AGAINST, c,
                    clip("Home page read by the model (" + home.optString("model") + "): " + home.optString("quote") + hint, 500),
                    home.optString("contentHash"), criteria);
        }
        return out;
    }

    private static void add(final List<AutoCorrect.Signal> out, final String domain, final String verdict, final String collection, final String evidence,
            final String revision, final String criteria) {
        try {
            out.add(AutoCorrect.Signal.of(Json.obj("family", AutoCorrect.LLM, "verdict", verdict, "collection", collection, "kind", "kg_home_fit",
                    "evidence", evidence, "revision", clip(revision, 80), "criteria", clip(criteria, 40)), domain));
        } catch (final IllegalArgumentException e) {
            // no valid signal (an unknown collection name): none
        }
    }

    /** The verdicts of the home page's reading ({@code CollectionFit}), as the scan reads them. */
    static final class CollectionFitVerdicts {
        static final String MATCH = "match", MISMATCH = "mismatch";

        private CollectionFitVerdicts() {}
    }

    /** Signals of a stored Discovery classification, as scoutro_automation.autocorrect_signals maps them: the model's PASS or FAIL only. */
    static List<AutoCorrect.Signal> discoverySignals(final String domain, final DomainEnrichment.DomainState state) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        if (state == null) return out;
        for (final Object[] r : new TreeMap<>(state.classifications).values()) {
            if (!usable(r) || r[3] == null) continue;
            final double confidence = r[1] instanceof Double ? (Double) r[1] : 0.0;
            final String why = r[8] == null ? (String) r[0] : (String) r[8];
            final String evidence = String.format(Locale.ROOT, "Discovery %s %s (%.2f): %s", r[2], r[0], confidence, why);
            try {
                out.add(AutoCorrect.Signal.of(Json.obj("family", AutoCorrect.LLM, "verdict", "PASS".equals(r[0]) ? AutoCorrect.FOR : AutoCorrect.AGAINST,
                        "collection", r[3], "kind", "discovery_classification", "evidence", evidence.length() > 500 ? evidence.substring(0, 500) : evidence,
                        "revision", clip((String) r[9], 80), "criteria", clip((String) r[7], 40)), domain));
            } catch (final IllegalArgumentException e) {
                // a record that is no valid signal (an unknown collection name) is no signal
            }
        }
        return out;
    }

    /**
     * The stored Discovery classifications of a domain as they are, also those without a signal (an error, the heuristic,
     * UNSURE): a new classification is a new input even when it speaks for nothing.
     */
    private static String records(final DomainEnrichment.DomainState state) {
        if (state == null) return "";
        final TreeSet<String> out = new TreeSet<>();
        for (final Object[] r : state.classifications.values()) out.add(java.util.Arrays.toString(r));
        return String.join("\n", out);
    }

    private static boolean usable(final Object[] r) {
        return r.length >= 10 && "llm".equals(r[5]) && (r[6] == null || ((String) r[6]).isEmpty()) && ("PASS".equals(r[0]) || "FAIL".equals(r[0]));
    }

    private static boolean against(final List<AutoCorrect.Signal> signals, final String collection) {
        for (final AutoCorrect.Signal s : signals) {
            if (collection.equals(s.collection) && AutoCorrect.AGAINST.equals(s.verdict) && !AutoCorrect.ORIGIN.equals(s.family)) return true;
        }
        return false;
    }

    private static List<AutoCorrect.Signal> againstFirst(final List<AutoCorrect.Signal> signals) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        for (final AutoCorrect.Signal s : signals) if (AutoCorrect.AGAINST.equals(s.verdict)) out.add(s);
        for (final AutoCorrect.Signal s : signals) if (!AutoCorrect.AGAINST.equals(s.verdict)) out.add(s);
        return out;
    }

    /** The signals without repetitions (the same signal read for two collections of the domain). */
    static List<AutoCorrect.Signal> unique(final List<AutoCorrect.Signal> signals) {
        final Map<String, AutoCorrect.Signal> out = new LinkedHashMap<>();
        for (final AutoCorrect.Signal s : signals) out.putIfAbsent(s.family + "|" + s.verdict + "|" + s.collection + "|" + s.origin + "|" + s.kind + "|"
                + s.revision + "|" + s.criteria + "|" + s.evidence, s);
        return new ArrayList<>(out.values());
    }

    private static String keys(final List<AutoCorrect.Signal> signals) {
        final TreeSet<String> k = new TreeSet<>();
        for (final AutoCorrect.Signal s : signals) k.add(s.family + "|" + s.verdict + "|" + s.collection + "|" + s.origin + "|" + s.kind + "|" + s.revision
                + "|" + s.criteria + "|" + s.evidence);
        return String.join("\n", k);
    }

    static JSONObject json(final AutoCorrect.Signal s) {
        return Json.obj("family", s.family, "verdict", s.verdict, "collection", s.collection, "origin", s.origin, "kind", s.kind, "evidence",
                s.evidence, "revision", s.revision, "criteria", s.criteria);
    }

    private static String clip(final String s, final int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }

    static String sha(final String s) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 12; i++) hex.append(String.format("%02x", h[i]));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The domains of a request: registrable domains, at most {@link #MAX_DOMAINS}, without repetition. */
    static List<String> domains(final JSONArray in) {
        if (in == null) return null;
        if (in.length() > MAX_DOMAINS) throw new IllegalArgumentException("domains");
        final TreeSet<String> out = new TreeSet<>();
        for (int i = 0; i < in.length(); i++) {
            final String d = in.optString(i, "").trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty() || !d.equals(Normalizers.registrableDomain(d))) throw new IllegalArgumentException("domains");
            out.add(d);
        }
        return new ArrayList<>(out);
    }

    static JSONObject parse(final String s) {
        try {
            return new JSONObject(s);
        } catch (final JSONException e) {
            return new JSONObject();
        }
    }
}
