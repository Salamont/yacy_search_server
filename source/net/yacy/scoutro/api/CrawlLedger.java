/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import net.yacy.scoutro.agents.CrawlRecord;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import org.json.JSONObject;

/** Append-only start metadata, not crawl workers/state. Reads never create DATA.
 * Complete fsynced records precede dispatch. A torn/corrupt journal fails closed.
 * One YaCy process owns DATA; instances share LOCK, and invalidate on file changes.
 */
final class CrawlLedger {
    private static final Object LOCK = new Object();
    private static final int MAX_RECORD_BYTES = 16384;
    static final class Entry {
        final CrawlRecord record;
        final Object error;
        Entry(CrawlRecord record, Object error) { this.record = record; this.error = error; }
    }
    private final Supplier<Path> file;
    private final Map<String, Entry> markers = new HashMap<>(), references = new HashMap<>(), ids = new HashMap<>();
    private Path loadedPath;
    private long loadedSize = -1, loadedModified = -1;
    CrawlLedger(final Supplier<Path> file) { this.file = file; }
    private static ApiException unavailable() {
        return new ApiException(503, "crawl_store_unavailable", "Crawl metadata is unavailable; existing records are retained. No new crawl was dispatched by this request.");
    }
    private Path path() throws ApiException {
        try { return this.file.get(); } catch (RuntimeException notReady) { throw unavailable(); }
    }
    private Entry decode(final String line) throws IOException, ApiException {
        if (line.getBytes(StandardCharsets.UTF_8).length > MAX_RECORD_BYTES) throw new IOException("record size");
        final JsonObject envelope = new JsonObject(line);
        if (envelope.getInt("schema_version") != 1) throw new IOException("record schema");
        final JsonObject row = envelope.getJSONObject("record");
        for (String field : List.of("crawlId", "agentId", "collection", "host", "clientRef", "state", "startMarker", "url", "scope")) row.getString(field);
        row.getLong("createdAt"); row.getInt("depth"); row.getInt("maxPages");
        final CrawlRecord record = CrawlRecord.fromJson(row);
        if (!record.startMarker.matches("[0-9a-f]{32}") || !(record.isStarting() || record.isStarted()) || record.createdAt <= 0
                || (record.isStarted() && !record.crawlId.matches("[A-Za-z0-9_-]{1,64}"))) throw new IOException("record");
        final JsonObject request = new JsonObject().put("url", record.url).put("collection", record.collection)
                .put("scope", record.scope).put("depth", record.depth);
        if (record.maxPages != -1) request.put("maxPages", record.maxPages);
        if (!CrawlRequest.parse(request).matches(record)) throw new IOException("parameters");
        return new Entry(record, row.opt("lastError"));
    }
    private void validate(final Entry entry) throws IOException {
        final CrawlRecord record = entry.record;
        final Entry previous = this.markers.get(record.startMarker), referenced = this.references.get(record.clientRef);
        if (previous != null && (!previous.record.url.equals(record.url) || !previous.record.collection.equals(record.collection)
                || !previous.record.host.equals(record.host) || !previous.record.clientRef.equals(record.clientRef)
                || !previous.record.scope.equals(record.scope) || previous.record.depth != record.depth
                || previous.record.maxPages != record.maxPages || previous.record.createdAt != record.createdAt
                || previous.record.isStarted() && (!record.isStarted() || !previous.record.crawlId.equals(record.crawlId))))
            throw new IOException("changed intent");
        if (!record.clientRef.isEmpty() && referenced != null && !referenced.record.startMarker.equals(record.startMarker))
            throw new IOException("duplicate reference");
    }
    private void apply(final Entry entry) throws IOException {
        validate(entry);
        final CrawlRecord record = entry.record;
        this.markers.put(record.startMarker, entry);
        if (!record.clientRef.isEmpty()) this.references.put(record.clientRef, entry);
        if (record.isStarted()) {
            final Entry existingId = this.ids.get(record.crawlId);
            if (existingId == null || existingId.record.createdAt <= record.createdAt) this.ids.put(record.crawlId, entry);
        }
    }
    private void load() throws ApiException {
        final Path path = path();
        if (path == null) return; // fake upstream: memory only
        try {
            final boolean exists = Files.exists(path);
            final long size = exists ? Files.size(path) : 0, modified = exists ? Files.getLastModifiedTime(path).toMillis() : 0;
            if (path.equals(this.loadedPath) && size == this.loadedSize && modified == this.loadedModified) return;
            this.loadedSize = -1; this.markers.clear(); this.references.clear(); this.ids.clear();
            if (exists && size > 0) {
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                    final ByteBuffer end = ByteBuffer.allocate(1); channel.read(end, size - 1);
                    if (end.array()[0] != '\n') throw new IOException("incomplete journal");
                }
                try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) apply(decode(line));
                }
            }
            this.loadedPath = path; this.loadedSize = size; this.loadedModified = modified;
        } catch (IOException | RuntimeException | ApiException error) { this.loadedSize = -1; throw unavailable(); }
    }
    CrawlRecord find(final String reference) throws ApiException {
        if (reference == null) return null;
        synchronized (LOCK) { load(); final Entry entry = this.references.get(reference); return entry == null ? null : entry.record; }
    }
    Entry byMarker(final String marker) throws ApiException {
        synchronized (LOCK) { load(); return this.markers.get(marker); }
    }
    Entry byId(final String id) throws ApiException {
        synchronized (LOCK) { load(); return this.ids.get(id); }
    }
    void save(final CrawlRecord record, final String error) throws ApiException {
        synchronized (LOCK) {
            load();
            final JsonObject row = new JsonObject(record.toJson()).put("lastError", error);
            final String line = new JsonObject().put("schema_version", 1).put("record", row).toString();
            final Entry entry;
            try { entry = decode(line); } catch (IOException | RuntimeException failure) { throw unavailable(); }
            final Path path = path();
            if (path == null) { try { apply(entry); } catch (IOException failure) { throw unavailable(); } return; }
            try {
                validate(entry); // refuse conflicting intent before appending anything
                Files.createDirectories(path.getParent());
                final byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                try (FileChannel directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) { directory.force(true); }
                apply(entry);
                this.loadedPath = path; this.loadedSize = Files.size(path); this.loadedModified = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException | RuntimeException failure) { this.loadedSize = -1; throw unavailable(); }
        }
    }
    static JSONObject describe(final JSONObject live, final CrawlRecord record) {
        final JsonObject out = live == null ? new JsonObject(Json.obj("id", record.crawlId, "state", "removed", "pagesLoaded", null,
                "collections", new JsonArray().put(record.collection))) : new JsonObject(live);
        Json.put(out, "url", record.url.isEmpty() ? null : record.url);
        Json.put(out, "startUrl", record.url.isEmpty() ? null : record.url);
        Json.put(out, "host", record.host); Json.put(out, "collection", record.collection);
        Json.put(out, "scope", record.scope.isEmpty() ? null : record.scope);
        Json.put(out, "startedAt", AgentApi.iso(record.createdAt)); Json.put(out, "endedAt", null);
        Json.put(out, "depth", record.depth < 0 ? out.opt("depth") : record.depth);
        Json.put(out, "maxPages", record.maxPages < 0 ? out.opt("maxPages") : record.maxPages);
        if (!out.has("lastError")) Json.put(out, "lastError", null);
        Json.put(out, "progress", Json.obj("pagesLoaded", out.opt("pagesLoaded"), "total", null, "percent", null));
        return out;
    }
}
