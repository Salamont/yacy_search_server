/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.util.Set;
import java.util.UUID;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.api.ApiException;

public final class JobSchema {
    private JobSchema() { }
    public record Limits(int maxDomains, int maxPages, int maxDepth) {
        public static Limits hard() { return new Limits(500, 10000, 10); }
        public JsonObject json() { return new JsonObject().put("max_domains", maxDomains).put("max_pages", maxPages).put("depth", maxDepth); }
    }
    public static JsonObject defaults(final JsonObject input) {
        final JsonObject job = new JsonObject().put("id", UUID.randomUUID().toString()).put("name", "")
                .put("enabled", false).put("paused", false).put("profile", "").put("sources", new JsonObject())
                .put("candidate_scope", "source_regions")
                .put("discovery", new JsonObject().put("replenish", true).put("replenish_interval_hours", 24))
                .put("batch", new JsonObject().put("max_domains", 50).put("max_pages", 15).put("depth", 2).put("seed_delay_seconds", 10))
                .put("processing", new JsonObject().put("fresh", true).put("retry", false)
                        .put("recrawl", new JsonObject().put("enabled", false).put("days", 30)))
                .put("schedule", new JsonObject().put("every_minutes", 60));
        return merge(job, input);
    }
    public static JsonObject merge(final JsonObject base, final JsonObject patch) {
        final JsonObject out = new JsonObject(base.toString());
        for (final String key : patch.keySet()) {
            final Object value = patch.get(key);
            if (value instanceof JsonObject && out.opt(key) instanceof JsonObject && !"sources".equals(key)) {
                out.put(key, merge(out.getJSONObject(key), (JsonObject) value));
            } else out.put(key, value);
        }
        return out;
    }
    public static void keys(final JsonObject object, final String... allowed) throws ApiException {
        final Set<String> names = Set.of(allowed);
        for (final String key : object.keySet()) if (!names.contains(key)) throw ApiException.invalid(key, "Unknown field.");
    }
    private static void bool(final JsonObject object, final String key) throws ApiException {
        if (!(object.opt(key) instanceof Boolean)) throw ApiException.invalid(key, "Expected boolean.");
    }
    private static void number(final JsonObject object, final String key, final double minimum, final double maximum,
            final boolean integer) throws ApiException {
        final Object value = object.opt(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) throw ApiException.invalid(key, "Expected finite number.");
        final double n = ((Number) value).doubleValue();
        if (n < minimum || n > maximum || (integer && n != Math.rint(n))) throw ApiException.invalid(key, "Value outside allowed limits.");
    }
    public static void validate(final JsonObject job, final Limits limits) throws ApiException {
        try {
            keys(job, "id", "name", "enabled", "paused", "profile", "sources", "candidate_scope", "discovery", "batch", "processing", "schedule");
            if (!job.getString("id").equals(UUID.fromString(job.getString("id")).toString())) throw ApiException.invalid("id", "Expected UUID.");
            final String name = job.getString("name");
            if (name.isBlank() || name.length() > 120 || name.chars().anyMatch(Character::isISOControl)) throw ApiException.invalid("name", "Expected a short name.");
            if (!job.getString("profile").matches("[a-z][a-z0-9_-]{0,31}")) throw ApiException.invalid("profile", "Invalid profile id.");
            bool(job, "enabled"); bool(job, "paused");
            final String scope = job.getString("candidate_scope");
            if (!Set.of("source_regions", "profile_backlog").contains(scope)) throw ApiException.invalid("candidate_scope", "Unknown scope.");
            final JsonObject sources = job.getJSONObject("sources");
            for (final String id : sources.keySet()) {
                if (!RuntimeCatalog.SOURCES.contains(id)) throw ApiException.invalid("sources", "Unregistered discovery source.");
                final JsonObject spec = sources.getJSONObject(id);
                keys(spec, "mode", "regions");
                final String mode = spec.optString("mode", "selected");
                if (!Set.of("selected", "all").contains(mode)) throw ApiException.invalid("mode", "Expected selected or all.");
                final JsonArray regions = spec.optJSONArray("regions");
                if (regions == null || regions.length() > 256 || (regions.isEmpty() && !"all".equals(mode))) throw ApiException.invalid("regions", "Choose source regions.");
                for (final Object region : regions) if (!(region instanceof String) || ((String) region).isBlank()) throw ApiException.invalid("regions", "Expected region ids.");
                if ("all".equals(mode) && !regions.isEmpty()) throw ApiException.invalid("regions", "All mode has no explicit region list.");
                spec.put("mode", mode);
            }
            final JsonObject discovery = job.getJSONObject("discovery");
            keys(discovery, "replenish", "replenish_interval_hours"); bool(discovery, "replenish");
            number(discovery, "replenish_interval_hours", 1, 8760, true);
            if (sources.isEmpty() && (!"profile_backlog".equals(scope) || discovery.getBoolean("replenish"))) throw ApiException.invalid("sources", "A source is required except for drain-only profile backlog.");
            final JsonObject batch = job.getJSONObject("batch"); keys(batch, "max_domains", "max_pages", "depth", "seed_delay_seconds");
            number(batch, "max_domains", 1, limits.maxDomains, true); number(batch, "max_pages", 1, limits.maxPages, true);
            number(batch, "depth", 0, limits.maxDepth, true); number(batch, "seed_delay_seconds", 0, 300, false);
            final JsonObject processing = job.getJSONObject("processing"); keys(processing, "fresh", "retry", "recrawl");
            bool(processing, "fresh"); bool(processing, "retry");
            final JsonObject recrawl = processing.getJSONObject("recrawl"); keys(recrawl, "enabled", "days"); bool(recrawl, "enabled");
            number(recrawl, "days", 1, 3650, true);
            if (!processing.getBoolean("fresh") && !processing.getBoolean("retry") && !recrawl.getBoolean("enabled")) throw ApiException.invalid("processing", "Enable a processing mode.");
            final JsonObject schedule = job.getJSONObject("schedule"); keys(schedule, "every_minutes"); number(schedule, "every_minutes", 10, 525600, true);
        } catch (final IllegalArgumentException | ClassCastException e) { throw ApiException.invalid("job", "Malformed job definition."); }
    }
}
