/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.scoutro.collections.CollectionDecision;
import net.yacy.scoutro.collections.CollectionDecisions;
import net.yacy.scoutro.collections.CollectionDecisions.Refusal;
import net.yacy.scoutro.knowledge.resolve.Normalizers;

/**
 * Administrator routes of the durable collection decisions (docs/SCOUTRO_COLLECTION_DECISIONS.md):
 * <ul>
 * <li>{@code GET /v1/collections/decisions[?domain=]}: store health and the decisions;</li>
 * <li>{@code POST /v1/collections/decisions}: preview a decision for a scope, store and apply it with the preview's
 * token (a new revision when the scope already has one);</li>
 * <li>{@code POST /v1/collections/decisions/apply}: continue applying a stored decision (pending or partial);</li>
 * <li>{@code POST /v1/collections/decisions/revoke}: stop a decision (pages stay where they are);</li>
 * <li>{@code POST /v1/collections/decisions/recover}: accept the readable decisions after an unreadable store.</li>
 * </ul>
 * Never an agent route. Nothing is written without the preview's token.
 */
final class CollectionDecisionsApi {

    /** Pages one request applies; more continue with /apply. */
    static final int MAX_APPLY = 5000;
    /** Pages a preview looks at. */
    static final int MAX_PREVIEW = 50_000;
    private static final Set<String> FIELDS = Set.of("domain", "host", "pathPrefix", "mode", "collections", "denied", "reason", "note",
            "evidence", "confirm");

    private final CollectionDecisions decisions;
    private final DecisionApply apply;
    private final Predicate<String> known;
    private final int maxApply;

    CollectionDecisionsApi(final CollectionDecisions decisions, final DecisionApply apply, final Predicate<String> known) {
        this(decisions, apply, known, MAX_APPLY);
    }

    /** With another number of pages per request (tests). */
    CollectionDecisionsApi(final CollectionDecisions decisions, final DecisionApply apply, final Predicate<String> known, final int maxApply) {
        this.decisions = decisions;
        this.apply = apply;
        this.known = known;
        this.maxApply = maxApply;
    }

    JSONObject list(final Map<String, String> query) throws ApiException {
        final String domain = query.get("domain");
        final List<CollectionDecision> out = new ArrayList<>();
        for (final CollectionDecision d : this.decisions.all()) {
            if (domain == null || domain.isEmpty() || d.domain.equals(domain.toLowerCase(Locale.ROOT))) out.add(d);
        }
        return Json.obj("store", this.decisions.status(), "decisions", CollectionDecisions.toJson(out));
    }

    /** Preview, or store and apply with {@code confirm}. */
    JSONObject decide(final JSONObject body, final String actor) throws ApiException {
        for (final String key : body.keySet()) {
            if (!FIELDS.contains(key)) throw ApiException.invalid(key, "Unknown field.");
        }
        requireAvailable();
        final CollectionDecision proposed = proposal(body, actor);
        final CollectionDecision existing = this.decisions.byScope(proposed.domain, proposed.host, proposed.pathPrefix);
        final DecisionApply.Result preview = this.apply.preview(proposed, MAX_PREVIEW);
        if (!preview.done) throw new ApiException(422, "decision_too_large", "More than " + MAX_PREVIEW + " pages in this scope.");
        if (proposed.collections.contains(this.decisions.fallback()) && preview.pages == 0) {
            throw new ApiException(422, "fallback_needs_pages", "The fallback collection " + this.decisions.fallback()
                    + " only takes pages already crawled; this scope has none in the index.");
        }
        if (preview.leavingGraph > 0) {
            throw new ApiException(422, "target_not_followed", preview.leavingGraph + " pages would leave the knowledge graph: let the graph"
                    + " follow the target collection first (PATCH /scoutro/api/v1/kg/collections/{collection}).",
                    Json.obj("preview", preview.toJson()));
        }
        final String token = DecisionApply.token(proposed.scopeKey(), proposed.mode.name(), proposed.collections.toString(),
                proposed.denied.toString(), proposed.reason, String.valueOf(proposed.note),
                existing == null ? "" : existing.id + ":" + existing.revision, preview.toJson().toString());
        final String confirm = body.optString("confirm", "");
        final JSONObject out = Json.obj("proposed", proposed.toJson(), "existing", existing == null ? null : existing.toJson(),
                "preview", preview.toJson(), "token", token, "applied", false, "store", this.decisions.status());
        if (confirm.isEmpty()) return out;
        if (!confirm.equals(token)) {
            throw new ApiException(409, "decision_preview_stale", "The scope changed since the preview; preview again and confirm the new token.", out);
        }
        // write-ahead: the decision is durable (and governs new writes) before the index is changed
        final CollectionDecision.Builder b = proposed.toBuilder();
        if (existing != null) {
            b.id = existing.id;
            b.revision = existing.revision + 1;
            final JSONObject ev = CollectionDecision.copyOf(b.evidence);
            Json.put(ev, "supersedes", Json.obj("revision", existing.revision, "collections", new JSONArray(existing.collections),
                    "denied", new JSONArray(existing.denied), "mode", existing.mode.name().toLowerCase(Locale.ROOT)));
            b.evidence = ev;
        }
        b.apply = CollectionDecision.Apply.PENDING;
        b.applyState = Json.obj("startedAt", this.decisions.now());
        final CollectionDecision stored = put(b.build(), existing == null ? "decide" : "revise");
        return Json.put(runAndRecord(stored, null), "applied", true);
    }

    /** Continues applying a stored decision. */
    JSONObject resume(final JSONObject body) throws ApiException {
        for (final String key : body.keySet()) if (!"id".equals(key)) throw ApiException.invalid(key, "Unknown field.");
        requireAvailable();
        final CollectionDecision d = this.decisions.byId(body.optString("id", ""));
        if (d == null) throw new ApiException(404, "decision_unknown", "No such collection decision.");
        if (!d.active()) throw new ApiException(409, "decision_revoked", "The decision is revoked.");
        // a stored JSON null is no cursor (optString would answer "null")
        final String cursor = d.apply == CollectionDecision.Apply.PARTIAL && !d.applyState.isNull("cursor")
                ? d.applyState.optString("cursor", null) : null;
        return Json.put(runAndRecord(d, cursor), "applied", true);
    }

    private JSONObject runAndRecord(final CollectionDecision d, final String cursor) throws ApiException {
        final DecisionApply.Result r = this.apply.run(d, cursor, this.maxApply);
        final CollectionDecision.Builder b = d.toBuilder();
        final JSONObject st = CollectionDecision.copyOf(d.applyState);
        Json.put(st, "updated", st.optInt("updated", 0) + r.updated);
        Json.put(st, "failed", r.failed.length());
        Json.put(st, "skippedEmpty", st.optInt("skippedEmpty", 0) + r.skippedEmpty);
        Json.put(st, "webgraphUpdated", st.optInt("webgraphUpdated", 0) + r.edges);
        Json.put(st, "cursor", r.cursor);
        Json.put(st, "lastRunAt", this.decisions.now());
        b.applyState = st;
        // a page refused by its version (a crawl wrote it meanwhile) already follows the decision through the hook,
        // but its index state is unconfirmed: the decision stays partial until a run sees no failure
        b.apply = r.done && r.failed.length() == 0 ? CollectionDecision.Apply.APPLIED : CollectionDecision.Apply.PARTIAL;
        final CollectionDecision recorded = put(b.build(), "apply");
        return Json.obj("decision", recorded.toJson(), "result", r.toJson(), "store", this.decisions.status());
    }

    JSONObject revoke(final JSONObject body, final String actor) throws ApiException {
        for (final String key : body.keySet()) if (!"id".equals(key) && !"confirm".equals(key) && !"note".equals(key)) {
            throw ApiException.invalid(key, "Unknown field.");
        }
        requireAvailable();
        final CollectionDecision d = this.decisions.byId(body.optString("id", ""));
        if (d == null) throw new ApiException(404, "decision_unknown", "No such collection decision.");
        if (!d.active()) throw new ApiException(409, "decision_revoked", "The decision is already revoked.");
        final String token = DecisionApply.token("revoke", d.id, Integer.toString(d.revision));
        final JSONObject out = Json.obj("decision", d.toJson(), "token", token, "applied", false,
                "effect", "Pages keep their present collections; later crawls write their crawl profile's collections again.");
        final String confirm = body.optString("confirm", "");
        if (confirm.isEmpty()) return out;
        if (!confirm.equals(token)) throw new ApiException(409, "decision_preview_stale", "The decision changed; preview again.", out);
        final CollectionDecision.Builder b = d.toBuilder();
        b.status = CollectionDecision.Status.REVOKED;
        b.revision = d.revision + 1;
        b.decidedAt = this.decisions.now();
        b.decidedBy = actor;
        if (body.has("note")) b.note = body.optString("note");
        final CollectionDecision revoked = put(b.build(), "revoke");
        return Json.obj("decision", revoked.toJson(), "applied", true, "store", this.decisions.status());
    }

    JSONObject recover(final JSONObject body, final String actor) throws ApiException {
        for (final String key : body.keySet()) if (!"confirm".equals(key)) throw ApiException.invalid(key, "Unknown field.");
        final JSONObject status = this.decisions.status();
        final String token = DecisionApply.token("recover", status.toString().replaceAll("\"(refused|decided)Writes[A-Za-z]*\":[^,}]*,?", ""));
        final JSONObject out = Json.obj("store", status, "decisions", CollectionDecisions.toJson(this.decisions.all()), "token", token,
                "applied", false, "effect", "The readable decisions above become the committed state; newer ones are lost and must be decided again.");
        if (this.decisions.available()) throw new ApiException(409, "decisions_available", "The collection decisions are readable; nothing to recover.", out);
        final String confirm = body.optString("confirm", "");
        if (confirm.isEmpty()) return out;
        if (!confirm.equals(token)) throw new ApiException(409, "decision_preview_stale", "The store changed; preview again.", out);
        try {
            this.decisions.recover("accepted by " + (actor == null ? "administrator" : actor));
        } catch (final Refusal r) {
            throw new ApiException(r.status, r.code, r.getMessage());
        }
        return Json.obj("store", this.decisions.status(), "applied", true);
    }

    private CollectionDecision put(final CollectionDecision d, final String op) throws ApiException {
        try {
            return this.decisions.put(d, op);
        } catch (final Refusal r) {
            throw new ApiException(r.status, r.code, r.getMessage());
        }
    }

    private void requireAvailable() throws ApiException {
        if (!this.decisions.available()) {
            throw new ApiException(503, "decisions_unavailable", "Collection decisions cannot be read reliably; inspect and recover them first.",
                    Json.obj("store", this.decisions.status()));
        }
    }

    private CollectionDecision proposal(final JSONObject body, final String actor) throws ApiException {
        final CollectionDecision.Builder b = new CollectionDecision.Builder();
        b.id = CollectionDecisions.newId();
        b.domain = lower(body.optString("domain", ""));
        if (b.domain.isEmpty() || !b.domain.equals(Normalizers.registrableDomain(b.domain))) {
            throw ApiException.invalid("domain", "Use exactly one registrable domain such as example.com.");
        }
        b.host = body.has("host") ? lower(body.optString("host", "")) : null;
        b.pathPrefix = body.has("pathPrefix") ? body.optString("pathPrefix", "") : null;
        final String mode = body.optString("mode", "exact");
        if (!"exact".equals(mode) && !"transform".equals(mode)) throw ApiException.invalid("mode", "mode is exact or transform.");
        b.mode = "exact".equals(mode) ? CollectionDecision.Mode.EXACT : CollectionDecision.Mode.TRANSFORM;
        b.collections = names(body, "collections");
        b.denied = names(body, "denied");
        b.reason = body.optString("reason", "");
        b.note = body.has("note") ? body.optString("note", "") : null;
        b.evidence = body.optJSONObject("evidence");
        b.basis = CollectionDecision.Basis.MANUAL;
        b.decidedAt = this.decisions.now();
        b.decidedBy = actor;
        try {
            return b.build();
        } catch (final IllegalArgumentException e) {
            throw ApiException.invalid(e.getMessage(), "Invalid " + e.getMessage() + " (see docs/SCOUTRO_COLLECTION_DECISIONS.md).");
        }
    }

    private List<String> names(final JSONObject body, final String field) throws ApiException {
        final JSONArray a = body.optJSONArray(field);
        final List<String> out = new ArrayList<>();
        if (a == null) {
            if (body.has(field)) throw ApiException.invalid(field, field + " must be a list of collections.");
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            final String c = a.optString(i, "");
            if (CollectionCatalog.isInternal(c) || !this.known.test(c)) {
                throw new ApiException(400, "collection_unknown", "No selectable collection " + c + "; create it first.",
                        Json.obj("field", field, "collection", c));
            }
            out.add(c);
        }
        return out;
    }

    private static String lower(final String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
