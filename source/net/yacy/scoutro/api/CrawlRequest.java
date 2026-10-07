/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import net.yacy.scoutro.agents.CrawlRecord;
import org.json.JSONObject;

/** One validated Scoutro crawl contract. Caller permissions add stricter limits. */
final class CrawlRequest {
    final String url, collection, scope, host;
    final int depth;
    final Integer maxPages;
    private CrawlRequest(final String url, final String collection, final String scope, final int depth, final Integer pages) {
        this.url = url; this.collection = collection; this.scope = scope; this.depth = depth; this.maxPages = pages;
        this.host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
    }
    static CrawlRequest parse(final JSONObject body) throws ApiException {
        return parse(body, ScoutroActions.DEFAULT_DEPTH, null);
    }
    static CrawlRequest parse(final JSONObject body, final int defaultDepth, final Integer defaultPages) throws ApiException {
        for (final String key : body.keySet()) if (!List.of("url", "collection", "scope", "depth", "maxPages").contains(key))
            throw ApiException.invalid(key, "Unknown crawl field.");
        if (!(body.opt("url") instanceof String)) throw ApiException.invalid("url", "An HTTP/HTTPS URL is required.");
        // Required even for administrators, internal callers and JSON null; never default to user.
        final String collection = ScoutroActions.stringField(body, "collection", ScoutroActions.COLLECTION,
                "letters, digits, '-' and '_' (1–64); collection is required");
        final String url = ScoutroActions.validateHttpUrl((String) body.opt("url"), "url");
        final String scope = ScoutroActions.enumField(body, "scope", "domain", "domain", "subpath", "wide");
        final int depth = ScoutroActions.intField(body, "depth", defaultDepth, 0, ScoutroActions.MAX_DEPTH);
        // boxed on both branches: an omitted maxPages without a default stays null (no unboxing)
        final Integer pages = body.has("maxPages")
                ? Integer.valueOf(ScoutroActions.intField(body, "maxPages", defaultPages == null ? 0 : defaultPages, 1, ScoutroActions.MAX_PAGES))
                : defaultPages;
        if (body.has("maxPages") && body.isNull("maxPages")) throw ApiException.invalid("maxPages", "Use a positive integer or omit maxPages.");
        return new CrawlRequest(url, collection, scope, depth, pages);
    }
    JSONObject json() {
        final JSONObject out = Json.obj("url", this.url, "collection", this.collection, "scope", this.scope, "depth", this.depth);
        if (this.maxPages != null) Json.put(out, "maxPages", this.maxPages);
        return out;
    }
    boolean matches(final CrawlRecord record) {
        if (!this.collection.equals(record.collection) || !this.host.equalsIgnoreCase(record.host)) return false;
        // Legacy ownership records have no full request: keep their conservative replay, never dispatch again.
        return record.url.isEmpty() || this.url.equals(record.url) && this.scope.equals(record.scope)
                && this.depth == record.depth && (this.maxPages == null ? -1 : this.maxPages) == record.maxPages;
    }
    static String key(final String value) throws ApiException {
        if (value == null) return null;
        final String key = value.trim();
        if (!key.matches("[A-Za-z0-9_.:-]{1,100}")) throw ApiException.invalid("Idempotency-Key", "Use 1–100 letters, digits, '_', '.', ':' or '-'.");
        return key;
    }
}
