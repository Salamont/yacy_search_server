/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. Read-only: no crawl, no Discovery run, no LLM. */
package net.yacy.scoutro.api;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.json.JSONTokener;

import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.discovery.DiscoveryService;
import net.yacy.scoutro.report.CrawlSnapshot;
import net.yacy.scoutro.report.DomainTable;
import net.yacy.search.Switchboard;

/**
 * Adds what Scoutro itself recorded about a host and collection to a {@link DomainCandidate}:
 * the latest crawl from the crawl report table, the Discovery job that started it (profile and
 * its only configured source) and the Discovery state of the registrable domain (profile,
 * region and classification of that collection). A value is set only when it maps to exactly
 * this host and collection; otherwise it stays null.
 */
interface DomainEnrichment {

    /** One request: job definitions and the Discovery state are read once. */
    interface Session { void enrich(DomainCandidate.Builder candidate); }

    Session open();

    DomainEnrichment NONE = () -> candidate -> { };

    /** Latest crawl of a host in a collection, as recorded by the crawl report capture. */
    final class Crawl {
        final String outcome, job, scheme;
        final Long endedAt;
        Crawl(final String outcome, final Long endedAt, final String job, final String scheme) {
            this.outcome = outcome; this.endedAt = endedAt; this.job = job; this.scheme = scheme;
        }
    }

    /** A Discovery job definition: profile and its only configured source, or null with several sources. */
    final class Job {
        final String profile, source;
        Job(final String profile, final String source) { this.profile = profile; this.source = source; }
    }

    /** What the Discovery state knows about one registrable domain. */
    final class DomainState {
        /** profile -> {collection, region} */
        final Map<String, String[]> profiles = new HashMap<>();
        /** profile -> classification record (with its collection) */
        final Map<String, Object[]> classifications = new HashMap<>();
    }

    @FunctionalInterface interface CrawlLookup { Crawl read(String host, String collection) throws IOException; }

    /** The composition used by the API; each source can be replaced in tests. */
    static DomainEnrichment of(final CrawlLookup crawls, final java.util.function.Supplier<Map<String, Job>> jobs,
            final java.util.function.Supplier<Map<String, DomainState>> state) {
        return () -> {
            final Map<String, Job> jobMap = jobs.get();
            final Map<String, DomainState> stateMap = state.get();
            return candidate -> enrich(candidate, crawls, jobMap, stateMap);
        };
    }

    static void enrich(final DomainCandidate.Builder b, final CrawlLookup crawls, final Map<String, Job> jobs,
            final Map<String, DomainState> state) {
        final String collection = b.collection();
        Crawl crawl = null;
        if (collection != null) {
            try {
                crawl = crawls.read(b.host(), collection);
            } catch (final IOException | RuntimeException e) {
                crawl = null; // the table is optional; nothing is reported then
            }
        }
        if (crawl != null) {
            b.crawlStatus(crawl.outcome).lastCrawled(crawl.endedAt);
            if (b.scheme() == null && ("http".equals(crawl.scheme) || "https".equals(crawl.scheme))) b.scheme(crawl.scheme);
        }
        final Job job = crawl == null || crawl.job == null ? null : jobs.get(crawl.job);
        final DomainState domain = state.get(DomainCandidate.registrableDomain(b.host()));
        String profile = job == null ? null : job.profile;
        if (profile == null && domain != null && collection != null) {
            final List<String> matching = new ArrayList<>();
            for (final Map.Entry<String, String[]> p : domain.profiles.entrySet())
                if (collection.equals(p.getValue()[0])) matching.add(p.getKey());
            if (matching.size() == 1) profile = matching.get(0);
        }
        if (profile != null || (crawl != null && crawl.job != null)) {
            final String[] entry = domain == null || profile == null ? null : domain.profiles.get(profile);
            final String region = entry == null || entry[1] == null || entry[1].isEmpty() ? null : entry[1];
            b.discovery(new DomainCandidate.Discovery(profile, job == null ? null : job.source, crawl == null ? null : crawl.job, region));
        }
        if (domain != null && collection != null) {
            Object[] record = profile == null ? null : domain.classifications.get(profile);
            if (record != null && !collection.equals(record[3])) record = null;
            if (record == null) {
                final List<Object[]> matching = new ArrayList<>();
                for (final Object[] r : domain.classifications.values()) if (collection.equals(r[3])) matching.add(r);
                if (matching.size() == 1) record = matching.get(0);
            }
            if (record != null)
                b.classification(new DomainCandidate.Classification((String) record[0], (Double) record[1], (String) record[2], (Long) record[4]));
        }
    }

    // ------------------------------------------------------------------ Scoutro sources

    /** The running Scoutro: crawl report table, Discovery job store and state.json. */
    static DomainEnrichment current() {
        final Switchboard sb = Switchboard.getSwitchboard();
        if (sb == null) return NONE;
        final DomainTable table = sb.tables == null ? null : new DomainTable(sb.tables);
        final Path data = sb.getDataPath().toPath().resolve("DATA");
        final Path state = DiscoveryService.resolveRoot(System.getenv("SCOUTRO_DISCOVERY_DIR"),
                sb.getConfig("scoutro.discovery.stateRoot", ""), data.resolve("SCOUTRO/discovery")).resolve("state.json");
        return of((host, collection) -> table == null ? null : crawl(table, host, collection), DomainEnrichment::jobs,
                () -> StateFile.read(state));
    }

    static Crawl crawl(final DomainTable table, final String host, final String collection) throws IOException {
        final DomainTable.Lookup lookup;
        try {
            lookup = table.read(host, collection);
        } catch (final IllegalArgumentException invalid) {
            return null; // IP literals and single labels have no report rows
        }
        if (lookup.status != DomainTable.ReadStatus.FOUND || lookup.entry.current == null) return null;
        final CrawlSnapshot c = lookup.entry.current;
        return new Crawl(c.labels.get("outcome"), c.endedAt, c.job, c.labels.get("scheme"));
    }

    static Map<String, Job> jobs() {
        final Map<String, Job> out = new HashMap<>();
        try {
            for (final Object row : DiscoveryService.get().store.read().getJSONArray("jobs")) {
                final net.yacy.scoutro.discovery.JsonObject definition = ((net.yacy.scoutro.discovery.JsonObject) row).optJSONObject("definition");
                if (definition == null) continue;
                final net.yacy.scoutro.discovery.JsonObject sources = definition.optJSONObject("sources");
                final Set<String> names = sources == null ? Collections.emptySet() : sources.keySet();
                final String profile = definition.optString("profile", "");
                out.put(definition.optString("id", ""), new Job(profile.isEmpty() ? null : profile,
                        names.size() == 1 ? names.iterator().next() : null));
            }
        } catch (final ApiException | RuntimeException unavailable) {
            // without the job store, crawls keep their job id but no profile/source
        }
        return out;
    }

    /** state.json of scoutro-discovery, parsed once per file version and reduced to the fields used here. */
    final class StateFile {
        private static volatile Object[] cache; // path, modified, size, map
        private StateFile() { }

        static Map<String, DomainState> read(final Path file) {
            try {
                if (!Files.isRegularFile(file)) return Collections.emptyMap();
                final long modified = Files.getLastModifiedTime(file).toMillis(), size = Files.size(file);
                final Object[] c = cache;
                if (c != null && c[0].equals(file) && (long) c[1] == modified && (long) c[2] == size) {
                    @SuppressWarnings("unchecked") final Map<String, DomainState> map = (Map<String, DomainState>) c[3];
                    return map;
                }
                final Map<String, DomainState> map;
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    map = parse(new JSONObject(new JSONTokener(reader)));
                }
                cache = new Object[] {file, modified, size, map};
                return map;
            } catch (final IOException | org.json.JSONException | RuntimeException e) {
                ConcurrentLog.warn("SCOUTRO-API", "Discovery state unreadable for domain export: " + e.getClass().getSimpleName());
                return Collections.emptyMap();
            }
        }

        static Map<String, DomainState> parse(final JSONObject root) {
            final Map<String, DomainState> out = new HashMap<>();
            final JSONObject domains = root.optJSONObject("domains");
            if (domains == null) return out;
            for (final String name : domains.keySet()) {
                final JSONObject e = domains.optJSONObject(name);
                if (e == null) continue;
                final DomainState s = new DomainState();
                final JSONObject profiles = e.optJSONObject("profiles");
                if (profiles != null) {
                    for (final String p : profiles.keySet()) {
                        final JSONObject pe = profiles.optJSONObject(p);
                        if (pe != null) s.profiles.put(p, new String[] {text(pe, "collection"), text(pe, "region")});
                    }
                } else if (!text(e, "profile", "").isEmpty()) {
                    // state_version 1: one flat crawl entry
                    s.profiles.put(text(e, "profile"), new String[] {text(e, "collection"), text(e, "region")});
                }
                final JSONObject classifications = e.optJSONObject("classifications");
                if (classifications != null) {
                    for (final String p : classifications.keySet()) {
                        final JSONObject r = classifications.optJSONObject(p);
                        if (r == null) continue;
                        final String verdict = text(r, "verdict");
                        if (!"PASS".equals(verdict) && !"FAIL".equals(verdict) && !"UNSURE".equals(verdict)) continue;
                        final Object confidence = r.opt("confidence");
                        String collection = text(r, "collection");
                        if (collection == null && s.profiles.containsKey(p)) collection = s.profiles.get(p)[0];
                        s.classifications.put(p, new Object[] {verdict,
                                confidence instanceof Number ? ((Number) confidence).doubleValue() : null, p, collection,
                                time(text(r, "classified_at"))});
                    }
                }
                if (!s.profiles.isEmpty() || !s.classifications.isEmpty()) out.put(name.toLowerCase(java.util.Locale.ROOT), s);
            }
            return out;
        }

        private static String text(final JSONObject o, final String key) {
            final Object v = o.opt(key);
            return v instanceof String && !((String) v).isEmpty() ? (String) v : null;
        }

        private static String text(final JSONObject o, final String key, final String fallback) {
            final String v = text(o, key);
            return v == null ? fallback : v;
        }

        private static Long time(final String rfc3339) {
            if (rfc3339 == null) return null;
            try {
                return OffsetDateTime.parse(rfc3339).toInstant().toEpochMilli();
            } catch (final DateTimeParseException e) {
                return null;
            }
        }
    }
}
