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
    static final int MAX_SIGNALS = 50;

    /** The structured signals of the knowledge graph for a domain (null: graph off). */
    interface Graph {
        List<AutoCorrect.Signal> structured(String domain, String current);
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

    AutoCorrectApi(final AutoCorrect engine, final CollectionDecisions decisions, final CollectionDecisionsApi decide,
            final Function<String, String> config, final java.util.function.Predicate<String> known, final Graph graph) {
        this.engine = engine;
        this.decisions = decisions;
        this.decide = decide;
        this.config = config;
        this.known = known;
        this.graph = graph;
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
            return v == null ? 0.95 : Math.max(0.5, Math.min(0.9999, Double.parseDouble(v.trim())));
        } catch (final NumberFormatException e) {
            return 0.95;
        }
    }

    JSONObject settings() {
        return Json.obj("mode", mode().name().toLowerCase(Locale.ROOT), "maxPerDay", intSetting(MAX_PER_DAY_KEY, 10, 0, 1000), "minReviewed",
                intSetting(MIN_REVIEWED_KEY, 59, 1, 100000), "minLowerBound", lowerBoundSetting(), "fallback", this.decisions.fallback());
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
            if (!Set.of("domain", "collection", "memberships", "signals", "graph").contains(k)) throw ApiException.invalid(k, "Unknown field.");
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
        boolean manual = false;
        for (final CollectionDecision d : this.decisions.activeOf(domain)) manual |= d.basis == CollectionDecision.Basis.MANUAL;
        try {
            return this.engine.submit(domain, current, memberships, signals, this.decisions.fallback(), manual, mode(),
                    intSetting(MAX_PER_DAY_KEY, 10, 0, 1000), this::apply, actor);
        } catch (final IOException e) {
            throw new ApiException(503, "autocorrect_unavailable", "The correction state could not be written: " + e.getMessage());
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
            final JSONObject a = this.engine.accept(intSetting(MIN_REVIEWED_KEY, 59, 1, 100000), lowerBoundSetting(), actor);
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
