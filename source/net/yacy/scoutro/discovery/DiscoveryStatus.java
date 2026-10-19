/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.util.List;
import java.util.Set;

/** Read-only projection; worker activity is never evidence of an active batch. */
public final class DiscoveryStatus {
    private DiscoveryStatus() { }
    public static Object batch(final JsonObject run) {
        if (run == null) return JsonObject.NULL;
        final JsonObject out = new JsonObject();
        for (final String field : List.of("id", "job_id", "phase", "started_at", "finished_at", "error", "report", "collection"))
            if (run.has(field)) out.put(field, run.get(field));
        final JsonObject job = run.optJSONObject("job");
        out.put("job_name", job == null ? run.opt("job_name") : job.opt("name"));
        if (!out.has("collection")) out.put("collection", JsonObject.NULL);
        if (run.optLong("finished_at", 0) > 0) out.put("phase", "completed");
        final JsonArray attempts = run.optJSONArray("attempts");
        out.put("attempt_count", attempts == null ? run.optInt("attempt_count", 0) : attempts.length());
        int deferred = run.optInt("deferred_count", 0), automatic = run.optInt("automatic_deferred_count", 0), removed = run.optInt("profile_removed_count", 0);
        if (attempts != null) {
            deferred = 0; automatic = 0; removed = 0;
            final JsonArray review = new JsonArray();
            for (final Object value : attempts) {
                final JsonObject attempt = (JsonObject) value;
                final String state = attempt.getString("state");
                if ("deferred".equals(state)) {
                    deferred++;
                    if (attempt.optJSONObject("decision") != null && attempt.getJSONObject("decision").optBoolean("automatic")) automatic++;
                }
                if (DiscoveryService.PROFILE_REMOVED.equals(attempt.optString("outcome"))) removed++;
                // the open crawl starts an administrator can resolve (docs/SCOUTRO_DISCOVERY_AUTOMATION_V1.md, section 16); without a
                // decision the coordinator defers each of them itself after the grace period (section 16.2)
                if ("needs_review".equals(run.optString("phase")) && DiscoveryService.UNRESOLVED.contains(state)) {
                    final int reconciled = attempt.optInt("reconciled", 0);
                    final JsonObject item = new JsonObject().put("attempt_id", attempt.getString("id")).put("domain", attempt.getString("domain"))
                            .put("state", state).put("submitted_at", attempt.get("submitted_at")).put("reconciled", reconciled)
                            .put("auto_defer_after", Math.max(0, DiscoveryService.AUTO_DEFER_RECONCILIATIONS - reconciled))
                            .put("auto_defer_min_age_ms", DiscoveryService.AUTO_DEFER_MIN_AGE_MS);
                    if (attempt.has("auto_defer_error")) item.put("auto_defer_error", attempt.getString("auto_defer_error"));
                    review.put(item);
                }
            }
            if (!review.isEmpty()) out.put("review", review);
        }
        if (deferred > 0) out.put("deferred_count", deferred);
        if (automatic > 0) out.put("automatic_deferred_count", automatic);
        if (removed > 0) out.put("profile_removed_count", removed);
        return out;
    }
    public static JsonObject project(final JsonObject root, final JsonObject status) {
        final boolean enabled = root.getBoolean("enabled"), paused = root.getBoolean("paused");
        final String automation = !enabled ? "disabled" : paused ? "paused" : "active";
        final JsonObject run = root.optJSONObject("active_run");
        final JsonObject batch = run == null ? null : (JsonObject) batch(run);
        final String phase = batch == null ? "idle" : batch.optString("phase", "unknown");
        final boolean running = batch != null && Set.of("reserved", "running", "waiting_for_crawler").contains(phase);
        final JsonArray actions = new JsonArray();
        if (!enabled) actions.put("enable");
        else {
            actions.put(paused ? "resume" : "pause").put("disable");
            final JsonObject heartbeat = status.optJSONObject("heartbeat");
            if (heartbeat != null && !heartbeat.optBoolean("enabled")) actions.put("enable"); // explicit heartbeat repair
        }
        String reason = status.optString("waiting_reason", "idle");
        if (batch == null && !"active".equals(automation)) reason = automation;
        if (batch != null && !running && batch.opt("error") instanceof String && !batch.getString("error").isBlank()) reason = batch.getString("error");
        status.put("automation_status", automation).put("running", running)
                .put("active_batch", batch == null ? JsonObject.NULL : batch)
                .put("job_name", batch == null ? JsonObject.NULL : batch.opt("job_name"))
                .put("collection", batch == null ? JsonObject.NULL : batch.opt("collection"))
                .put("phase", phase).put("started_at", batch == null ? JsonObject.NULL : batch.opt("started_at"))
                .put("waiting_reason", reason).put("allowed_actions", actions);
        return status;
    }
}
