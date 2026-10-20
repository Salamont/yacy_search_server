/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.collections.AutoCorrect;
import net.yacy.scoutro.knowledge.sync.CollectionFit;

/**
 * Where the LLM tier hands a home page's collection fit (docs/SCOUTRO_COLLECTION_AUTOCORRECT.md, "Home page reading"): one
 * assessment per assigned collection (source {@code kg_home}) in the correction state, never a statement of the graph;
 * with a mismatch and the automation on, the domain is assessed once more by the plausibility scan (targeted), which
 * submits it through the unchanged rules. Every failure stays here: the LLM tier only counts it.
 */
final class CollectionFitRecorder implements CollectionFit.Sink {

    private static final ConcurrentLog LOG = new ConcurrentLog("SCOUTRO-API");

    private final Supplier<AutoCorrect> engine;
    /** The targeted scan of one domain (mode not off), or null to record only. */
    private final Function<String, Object> rescan;
    private final Supplier<Boolean> active;
    private final LongSupplier clock;

    CollectionFitRecorder(final Supplier<AutoCorrect> engine, final Function<String, Object> rescan, final Supplier<Boolean> active,
            final LongSupplier clock) {
        this.engine = engine;
        this.rescan = rescan;
        this.active = active;
        this.clock = clock;
    }

    /** The recorder of the running Scoutro: the correction state of ScoutroActions, the scan through its route. */
    static CollectionFitRecorder running() {
        return new CollectionFitRecorder(() -> {
            try {
                return ScoutroActions.autocorrectEngine();
            } catch (final ApiException e) {
                return null;
            }
        }, domain -> {
            try {
                return new ScoutroActions().collectionAutocorrect("scan", Map.of(), Json.obj("domains", new org.json.JSONArray(List.of(domain))),
                        "kg_home");
            } catch (final ApiException e) {
                return e.code(); // scan_busy and the like: the next pass of the scan takes it
            }
        }, () -> {
            final net.yacy.search.Switchboard sb = net.yacy.search.Switchboard.getSwitchboard();
            final String mode = sb == null ? null : sb.getConfig(AutoCorrectApi.MODE_KEY, null);
            return mode != null && !mode.isBlank() && !"off".equalsIgnoreCase(mode.trim());
        }, System::currentTimeMillis);
    }

    @Override
    public boolean needed(final String domain, final String collection, final String contentHash, final String criteria) {
        final AutoCorrect e = this.engine.get();
        if (e == null || e.unavailable()) return false; // nowhere to keep a verdict: not asked
        final org.json.JSONObject entry = e.assessments(domain).optJSONObject(collection);
        final org.json.JSONObject home = entry == null ? null : entry.optJSONObject(AutoCorrect.KG_HOME);
        return home == null || contentHash == null || !contentHash.equalsIgnoreCase(home.optString("contentHash"))
                || !String.valueOf(criteria).equals(home.optString("criteriaVersion"));
    }

    @Override
    public void verdict(final String domain, final String docId, final String contentHash, final String model, final String criteria,
            final CollectionFit.Verdict v) {
        final AutoCorrect e = this.engine.get();
        if (e == null || e.unavailable()) throw new IllegalStateException("autocorrect_unavailable");
        final long now = this.clock.getAsLong();
        boolean mismatch = false;
        for (final Map.Entry<String, String> x : v.verdicts.entrySet()) {
            e.assess(domain, x.getKey(), AutoCorrect.KG_HOME, Json.obj("result", x.getValue(), "best", v.best, "quote", v.quote, "model", model,
                    "criteriaVersion", criteria, "contentHash", contentHash, "docId", docId, "status", v.status, "signal",
                    !CollectionFit.UNCERTAIN.equals(x.getValue()), "at", now));
            mismatch |= CollectionFit.MISMATCH.equals(x.getValue());
        }
        try {
            e.persist();
        } catch (final IOException io) {
            throw new IllegalStateException(io);
        }
        if (mismatch && this.rescan != null && Boolean.TRUE.equals(this.active.get())) {
            final Object r = this.rescan.apply(domain);
            if (r instanceof String) LOG.info("collection fit of " + domain + " recorded; the scan waits: " + r);
        }
    }
}
