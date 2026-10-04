/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later; see gpl.txt. */
package net.yacy.scoutro.report;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.json.JSONObject;

import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;

/**
 * Daily rollups, the permanent report history: one NDJSON file per Discovery job and
 * year ({@code <root>/<job-id>/<YYYY>.ndjson}), at most one line per day, written once.
 * There is no separate event log; chart markers are a bounded array inside a rollup.
 * Invalid files fail closed and are never rewritten or truncated.
 */
public final class RollupStore {
    public static final int MAX_LINE_BYTES = 16384;
    public static final int MAX_MARKERS = 20;
    private static final Set<String> RESERVED = Set.of("v", "job", "day");
    private static final Object LOCK = new Object();

    public enum Status { APPENDED, ALREADY_PRESENT }

    private static final class Loaded {
        final long size, modified;
        final TreeMap<LocalDate, JsonObject> days;
        Loaded(final long size, final long modified, final TreeMap<LocalDate, JsonObject> days) {
            this.size = size; this.modified = modified; this.days = days;
        }
    }

    private final Path root;
    private final Map<Path, Loaded> loaded = new HashMap<>();

    public RollupStore(final Path root) {
        if (root == null) throw new IllegalArgumentException("root");
        this.root = root.toAbsolutePath().normalize();
    }

    /** The rollup root below YaCy's application data path. */
    public static Path root(final Path dataPath) {
        return dataPath.resolve("DATA").resolve("SCOUTRO").resolve("reports").resolve("rollups");
    }

    /** Canonical lower-case Discovery job UUID. */
    public static String job(final String id) {
        try {
            if (id != null && UUID.fromString(id).toString().equals(id)) return id;
        } catch (final IllegalArgumentException invalid) {
            // fall through
        }
        throw new IllegalArgumentException("Invalid job id.");
    }

    Path file(final String job, final int year) {
        if (year < 1970 || year > 9999) throw new IllegalArgumentException("Invalid year.");
        return this.root.resolve(job(job)).resolve(String.format("%04d.ndjson", year));
    }

    /** Appends the rollup of one day; a day that already exists is never written again. */
    public Status append(final String jobId, final LocalDate day, final JSONObject aggregates) throws IOException {
        final String job = job(jobId);
        if (day == null) throw new IllegalArgumentException("day");
        final Path path = file(job, day.getYear());
        final JsonObject line = new JsonObject().put("v", 1).put("job", job).put("day", day.toString());
        if (aggregates != null) {
            for (final String key : aggregates.keySet()) {
                if (RESERVED.contains(key)) throw new IllegalArgumentException("Reserved rollup field " + key);
                line.put(key, aggregates.opt(key));
            }
        }
        checkMarkers(line);
        final String text = line.toString();
        final byte[] bytes = (text + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length - 1 > MAX_LINE_BYTES) throw new IllegalArgumentException("Rollup line too large.");
        synchronized (LOCK) {
            final Loaded current = load(path, job, day.getYear());
            if (current.days.containsKey(day)) return Status.ALREADY_PRESENT;
            try {
                directories(path.getParent());
                try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                        StandardOpenOption.APPEND)) {
                    final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                force(path.getParent());
            } catch (final IOException failure) {
                this.loaded.remove(path);
                throw failure;
            }
            final TreeMap<LocalDate, JsonObject> days = new TreeMap<>(current.days);
            days.put(day, new JsonObject(text)); // never keep caller-owned nested values
            this.loaded.put(path, new Loaded(Files.size(path), Files.getLastModifiedTime(path).toMillis(), days));
            return Status.APPENDED;
        }
    }

    /** All rollups of one job and year, sorted by day. */
    public List<JsonObject> read(final String jobId, final int year) throws IOException {
        final String job = job(jobId);
        final Path path = file(job, year);
        synchronized (LOCK) {
            return copies(load(path, job, year).days.values());
        }
    }

    /** Rollups of one job between two days (inclusive), sorted by day. */
    public List<JsonObject> read(final String jobId, final LocalDate from, final LocalDate to) throws IOException {
        if (from == null || to == null || to.isBefore(from)) throw new IllegalArgumentException("Invalid day range.");
        final String job = job(jobId);
        final List<JsonObject> result = new ArrayList<>();
        synchronized (LOCK) {
            for (int year = from.getYear(); year <= to.getYear(); year++)
                result.addAll(copies(load(file(job, year), job, year).days.subMap(from, true, to, true).values()));
        }
        return result;
    }

    private static List<JsonObject> copies(final Iterable<JsonObject> lines) {
        final List<JsonObject> out = new ArrayList<>();
        for (final JsonObject line : lines) out.add(new JsonObject(line.toString()));
        return out;
    }

    private Loaded load(final Path path, final String job, final int year) throws IOException {
        if (!Files.exists(path)) {
            this.loaded.remove(path);
            return new Loaded(0, 0, new TreeMap<>());
        }
        final long size = Files.size(path), modified = Files.getLastModifiedTime(path).toMillis();
        final Loaded cached = this.loaded.get(path);
        if (cached != null && cached.size == size && cached.modified == modified) return cached;
        this.loaded.remove(path);
        if (size > 366L * (MAX_LINE_BYTES + 1)) throw invalid(path);
        final byte[] content = Files.readAllBytes(path);
        if (content.length != size || content.length > 0 && content[content.length - 1] != '\n') throw invalid(path);
        final TreeMap<LocalDate, JsonObject> days = new TreeMap<>();
        int start = 0;
        for (int i = 0; i < content.length; i++) {
            if (content[i] != '\n') continue;
            if (i - start > MAX_LINE_BYTES || i == start) throw invalid(path);
            final JsonObject line;
            final LocalDate day;
            try {
                line = new JsonObject(new String(content, start, i - start, StandardCharsets.UTF_8));
                final Object v = line.opt("v");
                if (!(v instanceof Integer || v instanceof Long) || ((Number) v).longValue() != 1
                        || !job.equals(line.opt("job")) || !(line.opt("day") instanceof String)) throw invalid(path);
                day = LocalDate.parse(line.getString("day"));
                if (!day.toString().equals(line.getString("day")) || day.getYear() != year) throw invalid(path);
                checkMarkers(line);
            } catch (final IllegalArgumentException | DateTimeParseException malformed) {
                throw invalid(path);
            }
            if (days.put(day, line) != null) throw invalid(path);
            start = i + 1;
        }
        final Loaded result = new Loaded(size, modified, days);
        this.loaded.put(path, result);
        return result;
    }

    private static void checkMarkers(final JsonObject line) {
        if (!line.has("markers")) return;
        final Object markers = line.opt("markers");
        if (!(markers instanceof JsonArray) || ((JsonArray) markers).length() > MAX_MARKERS)
            throw new IllegalArgumentException("Invalid rollup markers.");
        for (final Object marker : (JsonArray) markers)
            if (!(marker instanceof JsonObject)) throw new IllegalArgumentException("Invalid rollup marker.");
    }

    private static IOException invalid(final Path path) {
        return new IOException("Invalid rollup file " + path.getParent().getFileName() + "/" + path.getFileName()
                + "; it was left unchanged.");
    }

    /** Creates missing directories and syncs each parent that received a new entry. */
    private static void directories(final Path dir) throws IOException {
        if (Files.isDirectory(dir)) return;
        final Path parent = dir.getParent();
        if (parent != null) directories(parent);
        Files.createDirectory(dir);
        if (parent != null) force(parent);
    }

    private static void force(final Path dir) throws IOException {
        try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }
}
