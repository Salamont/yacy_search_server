/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.collections.CollectionDecision;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Routes of the automatic collection correction (WP-H, docs/SCOUTRO_COLLECTION_AUTOCORRECT.md), administrator only:
 * {@code GET /v1/collections/autocorrect} (settings, report), {@code POST .../evaluate} (a domain with its signals),
 * {@code POST .../review} (approve, reject, correct, wrong), {@code POST .../accept} (the statistical acceptance of the
 * apply mode, or its withdrawal) and {@code POST .../breaker-reset}.
 */
final class AutoCorrectApi {

    static final String MODE_KEY = "scoutro.collections.autocorrect.mode";
    static final String MAX_PER_DAY_KEY = "scoutro.collections.autocorrect.maxPerDay";
    static final String MIN_REVIEWED_KEY = "scoutro.collections.autocorrect.minReviewed";
    static final String MIN_LOWER_BOUND_KEY = "scoutro.collections.autocorrect.minLowerBound";
    static final String AUDIT_RATE_KEY = "scoutro.collections.autocorrect.auditRate";
    static final int MAX_SIGNALS = 50;
    /** The acceptance of the 99 % target: 299 error-free sampled reviews give a one-sided 95 % lower bound of 0.99. */
    static final int DEFAULT_MIN_REVIEWED = 299;
    static final double DEFAULT_MIN_LOWER_BOUND = AutoCorrect.TARGET;
    /** Text fit: pages with text the domain needs, and what another collection's vocabulary must show to speak for it. */
    static final int TEXT_FIT_MIN_PAGES = 3;
    static final int TEXT_FIT_MIN_CATEGORIES = 2;
    static final int TEXT_FIT_MIN_CATEGORY_PAGES = 2;

    /** The signals of the knowledge graph for a domain (null: graph off). */
    interface Graph {
        List<AutoCorrect.Signal> structured(String domain, String current);

        /** The text fit of the domain's pages ({@link #textFitSignals}); none by default. */
        default List<AutoCorrect.Signal> textFit(final String domain, final String current) {
            return List.of();
        }

        /**
         * Every signal read from the domain's pages in one reading: the text fit and the plausibility of its offers
         * ({@link #plausibilitySignals}); by default the text fit alone.
         */
        default List<AutoCorrect.Signal> website(final String domain, final String current) {
            return textFit(domain, current);
        }
    }

    /** Offered services (structured or quoted evidence) the plausibility needs at least, on at least this many pages. */
    static final int PLAUSIBILITY_MIN_SERVICES = 3;
    static final int PLAUSIBILITY_MIN_PAGES = 2;

    /**
     * The plausibility signal of a domain (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md): against the current collection
     * ({@code foreign_offers}) only with a positive finding, never for an absence alone; all of these hold:
     * <ol>
     * <li>the graph holds at least {@link #PLAUSIBILITY_MIN_SERVICES} different offered services or products on at least
     * {@link #PLAUSIBILITY_MIN_PAGES} pages with structured evidence (JSON-LD, tier 1) or the model's quoted offer (tier 3),
     * never a rule's reading alone ({@code KgRuntime.offerProfile});</li>
     * <li>none of them has a category of the current collection's vocabulary;</li>
     * <li>the text fit reads neither an offered category nor a topic of that vocabulary on the pages (role words such as
     * "Hersteller" are no topic of it), on at least one page with text;</li>
     * <li>the current collection has a vocabulary.</li>
     * </ol>
     * Otherwise no signal: the domain is uncertain.
     */
    static List<AutoCorrect.Signal> plausibilitySignals(final String domain, final String current, final JSONObject offers, final JSONObject fit) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        if (offers == null || fit == null || fit.optInt("pages") < 1) return out;
        final JSONObject mineFit = fit.optJSONObject("collections") == null ? null : fit.optJSONObject("collections").optJSONObject(current);
        final JSONObject mineOffers = offers.optJSONObject("collections") == null ? null : offers.optJSONObject("collections").optJSONObject(current);
        if (mineFit == null || mineOffers == null) return out; // no vocabulary: never judged
        if (mineFit.optInt("offered") > 0 || mineFit.optInt("topics") > 0 || mineOffers.optInt("services") > 0) return out;
        final JSONArray services = offers.optJSONArray("services");
        if (services == null || services.length() < PLAUSIBILITY_MIN_SERVICES || offers.optInt("offerPages") < PLAUSIBILITY_MIN_PAGES) return out;
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < services.length() && names.size() < 5; i++) {
            final JSONObject svc = services.optJSONObject(i);
            final JSONArray tiers = svc.optJSONArray("tiers");
            final List<String> t = new ArrayList<>();
            for (int j = 0; tiers != null && j < tiers.length(); j++) t.add(String.valueOf(tiers.opt(j)));
            names.add(svc.optString("name") + " (tier " + String.join("/", t) + ")");
        }
        final String evidence = services.length() + " offered services or products on " + offers.optInt("offerPages") + " pages, none of the vocabulary "
                + mineFit.optString("vocabulary") + " and no word of it on " + fit.optInt("pages") + " pages: " + String.join(", ", names);
        out.add(AutoCorrect.Signal.of(Json.obj("family", AutoCorrect.PLAUSIBILITY, "verdict", AutoCorrect.AGAINST, "collection", current, "kind",
                "foreign_offers", "evidence", evidence.length() > 500 ? evidence.substring(0, 500) : evidence, "criteria", "plausibility/1"), domain));
        return out;
    }

    /** Results of the assessment of a domain in one collection (the report, never a rule of the automation). */
    static final String MATCH = "match", SUSPECT = "suspect", UNCERTAIN = "uncertain";

    /**
     * The assessment of a domain in {@code collection} from its signals and its text fit: {@link #SUSPECT} with a signal
     * against it, {@link #MATCH} with an offered category of its vocabulary or a structured signal for it and nothing against,
     * else {@link #UNCERTAIN} (too little to judge: the cases the model may be asked about).
     */
    static String assess(final String collection, final List<AutoCorrect.Signal> signals, final JSONObject fit) {
        boolean against = false, structuredFor = false;
        for (final AutoCorrect.Signal sig : signals) {
            if (!collection.equals(sig.collection)) continue;
            if (AutoCorrect.AGAINST.equals(sig.verdict) && !AutoCorrect.ORIGIN.equals(sig.family)) against = true;
            if (AutoCorrect.FOR.equals(sig.verdict) && AutoCorrect.STRUCTURED.equals(sig.family)) structuredFor = true;
        }
        if (against) return SUSPECT;
        final JSONObject mine = fit == null || fit.optJSONObject("collections") == null ? null : fit.optJSONObject("collections").optJSONObject(collection);
        return structuredFor || mine != null && mine.optInt("offered") > 0 ? MATCH : UNCERTAIN;
    }

    /**
     * The text-fit signals of a domain (WP-H) from {@code KgRuntime.textFit}: the categories the rules read as offered on its
     * pages, per collection vocabulary. A collection whose vocabulary has offers on the pages is spoken for; the current one
     * is spoken against only when its vocabulary has no offer on at least {@link #TEXT_FIT_MIN_PAGES} pages with text
     * <b>and</b> another collection's vocabulary has at least {@link #TEXT_FIT_MIN_CATEGORIES} categories on at least
     * {@link #TEXT_FIT_MIN_CATEGORY_PAGES} pages: a fit elsewhere, never an absence alone. Collections without a vocabulary
     * are never judged; fewer pages are no signal.
     */
    static List<AutoCorrect.Signal> textFitSignals(final String domain, final String current, final JSONObject fit) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        final int pages = fit == null ? 0 : fit.optInt("pages");
        final JSONObject by = fit == null ? null : fit.optJSONObject("collections");
        if (pages < TEXT_FIT_MIN_PAGES || by == null) return out;
        final List<String> elsewhere = new ArrayList<>();
        for (final String c : new TreeSet<>(by.keySet())) {
            final JSONObject f = by.optJSONObject(c);
            final JSONArray cats = f.optJSONArray("categories");
            final int n = cats == null ? 0 : cats.length(), on = f.optInt("pages");
            final List<String> first = new ArrayList<>();
            for (int i = 0; i < n && i < 6; i++) first.add(cats.optString(i));
            final String names = String.join(", ", first);
            if (c.equals(current)) {
                if (n > 0) out.add(textSignal(domain, AutoCorrect.FOR, c, "offers_vocabulary_categories", n + " categories of the vocabulary "
                        + f.optString("vocabulary") + " offered on " + on + " of " + pages + " pages: " + names));
            } else if (n >= TEXT_FIT_MIN_CATEGORIES && on >= TEXT_FIT_MIN_CATEGORY_PAGES) {
                out.add(textSignal(domain, AutoCorrect.FOR, c, "offers_vocabulary_categories", n + " categories of the vocabulary "
                        + f.optString("vocabulary") + " offered on " + on + " of " + pages + " pages: " + names));
                elsewhere.add(c + " (" + names + ")");
            }
        }
        final JSONObject mine = by.optJSONObject(current);
        if (mine != null && mine.optJSONArray("categories").length() == 0 && !elsewhere.isEmpty()) {
            out.add(textSignal(domain, AutoCorrect.AGAINST, current, "fits_other_vocabulary", "no offer of the vocabulary "
                    + mine.optString("vocabulary") + " on " + pages + " pages; offers of " + String.join("; ", elsewhere)));
        }
        return out;
    }

    private static AutoCorrect.Signal textSignal(final String domain, final String verdict, final String collection, final String kind,
            final String evidence) {
        return AutoCorrect.Signal.of(Json.obj("family", AutoCorrect.TEXT_FIT, "verdict", verdict, "collection", collection, "kind", kind,
                "evidence", evidence.length() > 500 ? evidence.substring(0, 500) : evidence, "criteria", "text_fit/2"), domain);
    }

    /**
     * The structured signals of a domain's JSON-LD types (F1): each explicit NACE code the graph read from a {@code @type}
     * speaks for the collections whose vocabulary has a category of that code (the same division, or the same section
     * for a section code) and against the current collection if its vocabulary has codes and none of them fits. A
     * collection without codes is never judged; no code is no signal.
     *
     * @param industries {NACE code, document ID} pairs of the domain
     * @param naceOf the NACE codes of each collection's vocabulary
     */
    static List<AutoCorrect.Signal> structuredSignals(final String domain, final String current, final List<String[]> industries,
            final Map<String, Set<String>> naceOf) {
        final List<AutoCorrect.Signal> out = new ArrayList<>();
        final Set<String> seen = new TreeSet<>();
        for (final String[] i : industries) {
            final String code = i[0];
            if (code == null || !seen.add(code)) continue;
            boolean currentFits = false;
            final List<String> fits = new ArrayList<>();
            for (final Map.Entry<String, Set<String>> e : naceOf.entrySet()) {
                if (fits(code, e.getValue())) {
                    if (e.getKey().equals(current)) currentFits = true;
                    else fits.add(e.getKey());
                }
            }
            final String evidence = "JSON-LD @type -> NACE " + code + " (" + i[1] + ")";
            final Set<String> currentCodes = naceOf.get(current);
            if (currentFits) {
                out.add(signal(domain, AutoCorrect.FOR, current, evidence));
            } else if (currentCodes != null && !currentCodes.isEmpty()) {
                out.add(signal(domain, AutoCorrect.AGAINST, current, evidence));
            }
            for (final String c : fits) out.add(signal(domain, AutoCorrect.FOR, c, evidence));
        }
        return out;
    }

    static boolean fits(final String code, final Set<String> codes) {
        if (codes == null) return false;
        final boolean section = code.length() == 1 && Character.isLetter(code.charAt(0));
        for (final String c : codes) {
            if (c == null) continue;
            if (section ? c.equals(code) : division(c) != null && division(c).equals(division(code))) return true;
        }
        return false;
    }

    private static String division(final String code) {
        return code.length() >= 2 && Character.isDigit(code.charAt(0)) && Character.isDigit(code.charAt(1)) ? code.substring(0, 2) : null;
    }

    private static AutoCorrect.Signal signal(final String domain, final String verdict, final String collection, final String evidence) {
        return AutoCorrect.Signal.of(Json.obj("family", AutoCorrect.STRUCTURED, "verdict", verdict, "collection", collection, "kind",
                "jsonld_type", "evidence", evidence), domain);
    }

    private final AutoCorrect engine;
    private final CollectionDecisions decisions;
    private final CollectionDecisionsApi decide;
    private final Function<String, String> config;
    private final java.util.function.Predicate<String> known;
    private final Graph graph;
    /** The plausibility scan's view of the graph, of Discovery's state and of the catalog; null without a running graph. */
    private PlausibilityScan.Graph scanGraph;
    private java.util.function.Supplier<Map<String, DomainEnrichment.DomainState>> discovery = Map::of;
    private java.util.function.Supplier<java.util.Collection<String>> catalog = List::of;
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    AutoCorrectApi(final AutoCorrect engine, final CollectionDecisions decisions, final CollectionDecisionsApi decide,
            final Function<String, String> config, final java.util.function.Predicate<String> known, final Graph graph) {
        this.engine = engine;
        this.decisions = decisions;
        this.decide = decide;
        this.config = config;
        this.known = known;
        this.graph = graph;
    }

    /** The scan's sources (the routes scan and assessments); without them the scan answers 503 scan_busy. */
    AutoCorrectApi withScan(final PlausibilityScan.Graph scanGraph, final java.util.function.Supplier<Map<String, DomainEnrichment.DomainState>> discovery,
            final java.util.function.Supplier<java.util.Collection<String>> catalog, final java.util.function.LongSupplier clock) {
        this.scanGraph = scanGraph;
        this.discovery = discovery;
        this.catalog = catalog;
        this.clock = clock;
        return this;
    }

    AutoCorrect.Mode mode() {
        final String m = this.config.apply(MODE_KEY);
        try {
            return m == null || m.isEmpty() ? AutoCorrect.Mode.OFF : AutoCorrect.Mode.valueOf(m.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return AutoCorrect.Mode.OFF; // an unknown mode is off
        }
    }

    private int intSetting(final String key, final int def, final int min, final int max) {
        try {
            final String v = this.config.apply(key);
            return v == null ? def : Math.max(min, Math.min(max, Integer.parseInt(v.trim())));
        } catch (final NumberFormatException e) {
            return def;
        }
    }

    private double lowerBoundSetting() {
        try {
            final String v = this.config.apply(MIN_LOWER_BOUND_KEY);
            return v == null ? DEFAULT_MIN_LOWER_BOUND : Math.max(0.5, Math.min(0.9999, Double.parseDouble(v.trim())));
        } catch (final NumberFormatException e) {
            return DEFAULT_MIN_LOWER_BOUND;
        }
    }

    private double auditRateSetting() {
        try {
            final String v = this.config.apply(AUDIT_RATE_KEY);
            return v == null ? AutoCorrect.DEFAULT_AUDIT_RATE : Math.max(0.0, Math.min(1.0, Double.parseDouble(v.trim())));
        } catch (final NumberFormatException e) {
            return AutoCorrect.DEFAULT_AUDIT_RATE;
        }
    }

    JSONObject settings() {
        return Json.obj("mode", mode().name().toLowerCase(Locale.ROOT), "maxPerDay", intSetting(MAX_PER_DAY_KEY, 10, 0, 1000), "minReviewed",
                intSetting(MIN_REVIEWED_KEY, DEFAULT_MIN_REVIEWED, 1, 100000), "minLowerBound", lowerBoundSetting(), "auditRate", auditRateSetting(),
                "target", AutoCorrect.TARGET, "interim", AutoCorrect.INTERIM, "rulesVersion", AutoCorrect.RULES_VERSION, "fallback",
                this.decisions.fallback());
    }

    JSONObject report(final Map<String, String> query) throws ApiException {
        for (final String k : query.keySet()) if (!"limit".equals(k)) throw ApiException.invalid(k, "Unknown parameter; limit is allowed.");
        int limit = 200;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (final NumberFormatException e) {
                limit = -1;
            }
            if (limit < 0 || limit > 5000) throw ApiException.invalid("limit", "0 to 5000.");
        }
        return Json.put(this.engine.report(limit), "settings", settings());
    }

    JSONObject evaluate(final JSONObject body, final String actor) throws ApiException {
        for (final String k : body.keySet()) {
            if (!Set.of("domain", "collection", "memberships", "signals", "graph", "textFit").contains(k)) throw ApiException.invalid(k, "Unknown field.");
        }
        final String domain = body.optString("domain", "").trim().toLowerCase(Locale.ROOT);
        if (domain.isEmpty() || !domain.equals(Normalizers.registrableDomain(domain))) {
            throw ApiException.invalid("domain", "One registrable domain such as example.com.");
        }
        final String current = body.optString("collection", "");
        if (!this.known.test(current)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + current);
        final List<String> memberships = new ArrayList<>();
        final JSONArray m = body.optJSONArray("memberships");
        for (int i = 0; m != null && i < m.length(); i++) {
            final String c = m.optString(i, "");
            if (!this.known.test(c)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + c);
            if (!memberships.contains(c)) memberships.add(c);
        }
        if (!memberships.contains(current)) memberships.add(current);
        final List<AutoCorrect.Signal> signals = new ArrayList<>();
        final JSONArray in = body.optJSONArray("signals");
        if (in != null && in.length() > MAX_SIGNALS) throw ApiException.invalid("signals", "At most " + MAX_SIGNALS + " signals.");
        for (int i = 0; in != null && i < in.length(); i++) {
            final JSONObject s = in.optJSONObject(i);
            if (s == null) throw ApiException.invalid("signals", "Signal " + i + " is no object.");
            try {
                final AutoCorrect.Signal sig = AutoCorrect.Signal.of(s, domain);
                if (!this.known.test(sig.collection)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + sig.collection);
                signals.add(sig);
            } catch (final IllegalArgumentException e) {
                throw ApiException.invalid("signals", "Signal " + i + ": invalid " + e.getMessage()
                        + (("kind".equals(e.getMessage())) ? " (an absence or a crawl error is no signal)" : "") + ".");
            }
        }
        if (body.optBoolean("graph", true) && this.graph != null) {
            signals.addAll(this.graph.structured(domain, current));
        }
        if (body.optBoolean("textFit", true) && this.graph != null && mode() != AutoCorrect.Mode.OFF) {
            signals.addAll(this.graph.website(domain, current)); // reads the domain's pages: only when the automation is on
        }
        boolean manual = false;
        for (final CollectionDecision d : this.decisions.activeOf(domain)) manual |= d.basis == CollectionDecision.Basis.MANUAL;
        try {
            return this.engine.submit(domain, current, memberships, signals, this.decisions.fallback(), manual, mode(),
                    intSetting(MAX_PER_DAY_KEY, 10, 0, 1000), auditRateSetting(), this::apply, actor);
        } catch (final IOException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written: " + e.getMessage());
        }
    }

    /**
     * One step of the plausibility scan (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md): {@code maxDomains} (1 to 200, 50) domains
     * after the cursor, of {@code collection} only, from the start with {@code restart}, or exactly {@code domains} (at most
     * 200, the cursor stays). No model, nothing written to the graph; 503 scan_busy while the graph is off or paused or a
     * gate is closed.
     */
    JSONObject scan(final JSONObject body, final String actor) throws ApiException {
        for (final String k : body.keySet()) {
            if (!Set.of("maxDomains", "collection", "restart", "domains").contains(k)) throw ApiException.invalid(k, "Unknown field.");
        }
        final Object m = body.opt("maxDomains");
        if (m != null && (!(m instanceof Integer) || (Integer) m < 1 || (Integer) m > PlausibilityScan.MAX_DOMAINS)) {
            throw ApiException.invalid("maxDomains", "1 to " + PlausibilityScan.MAX_DOMAINS + ".");
        }
        final String collection = body.has("collection") ? body.optString("collection", "") : null;
        if (collection != null && !this.known.test(collection)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + collection);
        final Object restart = body.opt("restart");
        if (restart != null && !(restart instanceof Boolean)) throw ApiException.invalid("restart", "true or false.");
        final List<String> domains;
        try {
            domains = body.has("domains") ? PlausibilityScan.domains(body.optJSONArray("domains")) : null;
        } catch (final IllegalArgumentException e) {
            throw ApiException.invalid("domains", "At most " + PlausibilityScan.MAX_DOMAINS + " registrable domains such as example.com.");
        }
        if (body.has("domains") && body.optJSONArray("domains") == null) throw ApiException.invalid("domains", "An array of domains.");
        if (this.engine.unavailable()) throw new ApiException(503, "autocorrect_unavailable", "The correction state is unreadable.");
        if (this.scanGraph == null) throw new ApiException(503, "scan_busy", "The knowledge graph does not run.", Json.obj("reason", "graph_off"));
        final AutoCorrect.Mode mode = mode();
        final PlausibilityScan scan = new PlausibilityScan(this.engine, this.scanGraph, this.discovery, new PlausibilityScan.Decisions() {
            @Override
            public List<String> revisions(final String domain) {
                final List<String> out = new ArrayList<>();
                for (final CollectionDecision d : AutoCorrectApi.this.decisions.activeOf(domain)) out.add(d.id + ":" + d.revision + ":" + d.decidedAt);
                java.util.Collections.sort(out);
                return out;
            }

            @Override
            public boolean manual(final String domain) {
                for (final CollectionDecision d : AutoCorrectApi.this.decisions.activeOf(domain)) if (d.basis == CollectionDecision.Basis.MANUAL) return true;
                return false;
            }
        }, this.catalog, this.decisions.fallback(), (domain, current, memberships, signals, manual) -> this.engine.submit(domain, current, memberships,
                signals, this.decisions.fallback(), manual, mode, intSetting(MAX_PER_DAY_KEY, 10, 0, 1000), auditRateSetting(), this::apply, actor),
                mode != AutoCorrect.Mode.OFF, this.clock);
        try {
            return Json.put(scan.step(m == null ? PlausibilityScan.DEFAULT_DOMAINS : (Integer) m, collection, Boolean.TRUE.equals(restart), domains),
                    "mode", mode.name().toLowerCase(Locale.ROOT));
        } catch (final PlausibilityScan.Busy e) {
            throw new ApiException(503, "scan_busy", "The scan waits: " + e.reason + ". The cursor is kept.", Json.obj("reason", e.reason));
        } catch (final IOException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written: " + e.getMessage());
        } catch (final IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state is unreadable.");
        }
    }

    /** The assessments of the plausibility scan: counts per collection and source, and the pairs of {@code result}, {@code collection}, {@code source}. */
    JSONObject assessments(final Map<String, String> query) throws ApiException {
        for (final String k : query.keySet()) {
            if (!Set.of("result", "collection", "source", "limit").contains(k)) throw ApiException.invalid(k, "Unknown parameter.");
        }
        final String result = query.get("result");
        if (result != null && !Set.of(MATCH, SUSPECT, UNCERTAIN).contains(result)) throw ApiException.invalid("result", "match, suspect or uncertain.");
        final String source = query.get("source");
        if (source != null && !Set.of(AutoCorrect.RULES, AutoCorrect.KG_HOME, AutoCorrect.DISCOVERY).contains(source)) {
            throw ApiException.invalid("source", "rules, kg_home or discovery.");
        }
        final String collection = query.get("collection");
        if (collection != null && !this.known.test(collection)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + collection);
        int limit = 200;
        if (query.containsKey("limit")) {
            try {
                limit = Integer.parseInt(query.get("limit"));
            } catch (final NumberFormatException e) {
                limit = -1;
            }
            if (limit < 0 || limit > 5000) throw ApiException.invalid("limit", "0 to 5000.");
        }
        try {
            return this.engine.assessmentReport(result, collection, source, limit);
        } catch (final IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state is unreadable.");
        }
    }

    JSONObject review(final JSONObject body, final String actor) throws ApiException {
        for (final String k : body.keySet()) {
            if (!Set.of("domain", "verdict", "target", "note").contains(k)) throw ApiException.invalid(k, "Unknown field.");
        }
        final String target = body.has("target") ? body.optString("target", "") : null;
        if (target != null && !this.known.test(target)) throw new ApiException(400, "collection_unknown", "Unknown collection: " + target);
        try {
            return Json.obj("case", this.engine.review(body.optString("domain", "").toLowerCase(Locale.ROOT), body.optString("verdict", ""),
                    target, body.has("note") ? body.optString("note", "") : null, this::apply, actor), "stats", this.engine.stats());
        } catch (final IllegalArgumentException e) {
            final String code = e.getMessage();
            throw new ApiException("case_unknown".equals(code) ? 404 : "verdict".equals(code) ? 400 : 409, code == null ? "invalid" : code,
                    "The review cannot be recorded: " + code + ".");
        } catch (final IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state is unreadable.");
        } catch (final ApiException e) {
            throw e;
        } catch (final Exception e) {
            throw new ApiException(503, "autocorrect_unavailable", "The review could not be applied: " + e.getMessage());
        }
    }

    JSONObject accept(final JSONObject body, final String actor) throws ApiException {
        for (final String k : body.keySet()) if (!"revoke".equals(k)) throw ApiException.invalid(k, "Unknown field.");
        try {
            if (body.optBoolean("revoke", false)) {
                this.engine.revokeAcceptance(actor);
                return report(Map.of());
            }
            final JSONObject a = this.engine.accept(intSetting(MIN_REVIEWED_KEY, DEFAULT_MIN_REVIEWED, 1, 100000), lowerBoundSetting(), actor);
            if (!a.optBoolean("accepted")) {
                throw new ApiException(409, "acceptance_not_met", "The reviewed corrections do not meet the acceptance: "
                        + a.optJSONArray("missing") + ". The apply mode stays a proposal mode.", a);
            }
            return a;
        } catch (final IOException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written: " + e.getMessage());
        } catch (final IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state is unreadable.");
        }
    }

    /** Draws a random sample of open corrections for the acceptance ({@code size} 1 to 1000). */
    JSONObject sample(final JSONObject body, final String actor) throws ApiException {
        for (final String k : body.keySet()) if (!"size".equals(k)) throw ApiException.invalid(k, "Unknown field.");
        final Object size = body.opt("size");
        if (!(size instanceof Integer) || (Integer) size < 1 || (Integer) size > 1000) throw ApiException.invalid("size", "1 to 1000.");
        try {
            return Json.obj("draw", this.engine.sample((Integer) size, actor), "stats", this.engine.stats());
        } catch (final IOException | IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written.");
        }
    }

    JSONObject breakerReset(final JSONObject body, final String actor) throws ApiException {
        if (body.length() > 0) throw ApiException.invalid(body.keys().next(), "Unknown field.");
        try {
            return Json.obj("breaker", this.engine.resetBreaker(actor));
        } catch (final IOException | IllegalStateException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written.");
        }
    }

    /** A correction as a collection decision: the current collection replaced by the target (preview, then confirm). */
    private JSONObject apply(final String domain, final String from, final String target, final JSONObject evidence, final boolean automatic,
            final String actor) throws Exception {
        final JSONObject body = Json.obj("domain", domain, "mode", "transform", "collections", new JSONArray(List.of(target)), "denied",
                new JSONArray(List.of(from)), "reason", automatic ? "autocorrect" : "autocorrect_review", "evidence", evidence);
        final CollectionDecision.Basis basis = automatic ? CollectionDecision.Basis.AUTOMATIC : CollectionDecision.Basis.MANUAL;
        final JSONObject preview = this.decide.decide(body, actor, basis);
        Json.put(body, "confirm", preview.optString("token"));
        final JSONObject done = this.decide.decide(body, actor, basis);
        final JSONObject d = done.optJSONObject("decision");
        return Json.obj("id", d == null ? null : d.optString("id"), "basis", basis.name().toLowerCase(Locale.ROOT), "apply",
                d == null ? null : d.optString("apply"));
    }
}
