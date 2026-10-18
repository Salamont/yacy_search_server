/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.collections;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * The files of the collection decisions, built so that a lost or damaged file never silently brings back a corrected
 * collection membership:
 * <ul>
 * <li>{@code decisions.json}: the current snapshot (generation and all decisions), with a SHA-256 of its body;</li>
 * <li>{@code decisions.json.1} to {@code .5}: the previous validated snapshots;</li>
 * <li>{@code decisions-journal.jsonl}: every change since the first, one hash-chained line each, written (and forced
 * to disk) before the snapshot;</li>
 * <li>a marker in another directory ({@code DATA/SETTINGS}) with the newest committed generation.</li>
 * </ul>
 * Loading takes the newest valid snapshot and replays the valid journal lines after it. If what can be read is older
 * than the marker, decisions are known to be missing: the store is {@link Health#UNAVAILABLE} and the caller must block
 * writes that depend on it until an operator accepts a state ({@link #recover}). A torn last journal line (a crash
 * while appending) is ignored: that change was never acknowledged.
 */
public final class DecisionStore {

    public static final String SCHEMA = "scoutro.collection-decisions.v1";
    static final String SNAPSHOT = "decisions.json";
    static final String JOURNAL = "decisions-journal.jsonl";
    static final int BACKUPS = 5;

    public enum Health {
        /** Every file read as expected. */
        OK,
        /** A file was damaged or missing, but no committed decision is lost (rebuilt from the others). */
        RECOVERED,
        /** Committed decisions cannot be read: block writes that depend on them. */
        UNAVAILABLE
    }

    /** What a load found. */
    public static final class State {
        public final Health health;
        public final long generation;
        public final long markerGeneration;
        public final List<CollectionDecision> decisions;
        public final List<String> problems;
        /** Which file the decisions came from: snapshot, backup N, journal, or none. */
        public final String source;

        State(final Health health, final long generation, final long marker, final List<CollectionDecision> decisions,
                final List<String> problems, final String source) {
            this.health = health;
            this.generation = generation;
            this.markerGeneration = marker;
            this.decisions = Collections.unmodifiableList(new ArrayList<>(decisions));
            this.problems = Collections.unmodifiableList(new ArrayList<>(problems));
            this.source = source;
        }

        public JSONObject toJson() {
            try {
                return json();
            } catch (final JSONException e) {
                throw new IllegalStateException(e);
            }
        }

        private JSONObject json() throws JSONException {
            final JSONObject o = new JSONObject();
            o.put("health", this.health.name().toLowerCase(java.util.Locale.ROOT));
            o.put("generation", this.generation);
            o.put("markerGeneration", this.markerGeneration);
            o.put("source", this.source);
            o.put("problems", new JSONArray(this.problems));
            o.put("decisions", this.decisions.size());
            return o;
        }
    }

    private final Path dir;
    private final Path marker;
    private String journalTail = "";
    private long generation;
    /** The journal had a torn or invalid line: the next write starts a new journal (the old one is kept aside). */
    private boolean journalDamaged;

    /**
     * @param dir    directory of snapshot, backups and journal ({@code DATA/SCOUTRO/collections})
     * @param marker the marker file, deliberately elsewhere ({@code DATA/SETTINGS/scoutro-collection-decisions.marker})
     */
    public DecisionStore(final Path dir, final Path marker) {
        this.dir = dir;
        this.marker = marker;
    }

    public Path directory() {
        return this.dir;
    }

    /** Reads all files; never throws for damaged content (it is reported in the state). */
    public synchronized State load() {
        final List<String> problems = new ArrayList<>();
        long markerGen = 0L;
        boolean markerKnown = true;
        try {
            markerGen = readMarker();
        } catch (final Exception e) {
            markerKnown = false;
            problems.add("marker unreadable: " + e.getMessage());
        }
        // newest valid snapshot
        long baseGen = 0L;
        Map<String, CollectionDecision> base = new LinkedHashMap<>();
        String source = "none";
        boolean anySnapshot = false;
        for (int i = 0; i <= BACKUPS; i++) {
            final Path p = snapshotPath(i);
            if (!Files.exists(p)) continue;
            anySnapshot = true;
            try {
                final JSONObject body = readSnapshot(p);
                baseGen = body.getLong("generation");
                base = decisions(body.getJSONArray("decisions"));
                source = i == 0 ? "snapshot" : "backup " + i;
                if (i > 0) problems.add("snapshot unreadable or invalid; using backup " + i);
                break;
            } catch (final Exception e) {
                problems.add((i == 0 ? "snapshot" : "backup " + i) + " invalid: " + e.getMessage());
            }
        }
        // replay the journal after it
        long gen = baseGen;
        String tail = "";
        boolean replayed = false;
        boolean damaged = false;
        final Path journal = this.dir.resolve(JOURNAL);
        boolean journalSeen = false;
        if (Files.exists(journal)) {
            journalSeen = true;
            try {
                final List<String> lines = Files.readAllLines(journal, StandardCharsets.UTF_8);
                String prev = "";
                for (int n = 0; n < lines.size(); n++) {
                    final String line = lines.get(n);
                    if (line.isEmpty()) continue;
                    final JSONObject entry;
                    try {
                        entry = new JSONObject(line);
                        final String body = entry.getString("body");
                        if (!entry.getString("prev").equals(prev) || !entry.getString("sha256").equals(sha256(prev + "\n" + body))) {
                            throw new IllegalArgumentException("checksum");
                        }
                        final JSONObject b = new JSONObject(body);
                        final long g = b.getLong("generation");
                        if (g > gen) {
                            // every entry holds the full set; a gap is only allowed where a new journal was started
                            if (g != gen + 1 && !(n == 0 && "rebase".equals(b.optString("op")))) {
                                throw new IllegalArgumentException("generation gap " + gen + " -> " + g);
                            }
                            apply(base, b);
                            gen = g;
                            replayed = true;
                        }
                        prev = entry.getString("sha256");
                    } catch (final Exception e) {
                        final boolean last = n == lines.size() - 1;
                        problems.add("journal line " + (n + 1) + (last ? " torn (ignored)" : " invalid") + ": " + e.getMessage());
                        damaged = true;
                        break;
                    }
                }
                tail = prev;
            } catch (final IOException e) {
                problems.add("journal unreadable: " + e.getMessage());
                damaged = true;
            }
        } else if (anySnapshot || markerGen > 0) {
            problems.add("journal missing");
        }
        if (replayed) source = "none".equals(source) ? "journal" : source + " + journal";
        if (!anySnapshot && (journalSeen || markerGen > 0)) problems.add("snapshot missing; rebuilt from the journal");
        Health health = problems.isEmpty() ? Health.OK : Health.RECOVERED;
        if (!markerKnown && (anySnapshot || journalSeen)) {
            // without the marker nothing proves that the newest decisions were read
            health = Health.RECOVERED;
        }
        if (gen < markerGen) {
            problems.add("committed generation " + markerGen + " but only " + gen + " readable: decisions are missing");
            health = Health.UNAVAILABLE;
        }
        if (!markerKnown && !anySnapshot && !journalSeen) {
            problems.add("marker damaged and no decisions readable");
            health = Health.UNAVAILABLE;
        }
        this.generation = gen;
        this.journalTail = tail;
        this.journalDamaged = damaged;
        return new State(health, gen, markerGen, new ArrayList<>(base.values()), problems, source);
    }

    /**
     * Commits the next generation: journal line first (forced), then the snapshot (old one rotated into the backups),
     * then the marker. {@code expected} must be the generation of the last load or commit.
     */
    public synchronized State commit(final long expected, final List<CollectionDecision> decisions, final String op, final String detail,
            final long at) throws IOException {
        if (expected != this.generation) throw new IOException("generation changed: expected " + expected + ", have " + this.generation);
        final long next = this.generation + 1;
        write(next, decisions, op, detail, at, next);
        return new State(Health.OK, next, next, decisions, List.of(), "snapshot");
    }

    /**
     * Accepts {@code decisions} as the new truth after an {@link Health#UNAVAILABLE} load: writes a generation above
     * both the readable one and the marker, and records the loss in the journal. Only an explicit operator action.
     */
    public synchronized State recover(final List<CollectionDecision> decisions, final long markerGeneration, final String detail,
            final long at) throws IOException {
        final long next = Math.max(this.generation, markerGeneration) + 1;
        // the journal restarts (the old one is kept aside): the jump in generations is only valid at its start
        this.journalDamaged = true;
        write(next, decisions, "recover", detail, at, next);
        return new State(Health.OK, next, next, decisions, List.of(), "snapshot");
    }

    private void write(final long gen, final List<CollectionDecision> decisions, final String op, final String detail, final long at,
            final long markerGen) throws IOException {
        try {
            writeJson(gen, decisions, op, detail, at, markerGen);
        } catch (final JSONException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private void writeJson(final long gen, final List<CollectionDecision> decisions, final String op, final String detail, final long at,
            final long markerGen) throws IOException, JSONException {
        Files.createDirectories(this.dir);
        final JSONObject body = new JSONObject();
        body.put("generation", gen);
        body.put("op", op);
        body.put("at", at);
        if (detail != null) body.put("detail", detail);
        final JSONArray all = new JSONArray();
        for (final CollectionDecision d : decisions) all.put(d.toJson());
        // every entry carries the full set: replay never depends on the order of partial changes
        body.put("decisions", all);
        final Path journal = this.dir.resolve(JOURNAL);
        if (this.journalDamaged && Files.exists(journal)) {
            // never append behind a torn or invalid line: keep the old journal aside, start a new one with the full set
            Files.move(journal, this.dir.resolve(JOURNAL + ".damaged-" + gen), StandardCopyOption.REPLACE_EXISTING);
            this.journalTail = "";
            body.put("op", "rebase");
            body.put("rebasedOp", op);
        }
        this.journalDamaged = false;
        final String bodyString = body.toString();
        final String sha = sha256(this.journalTail + "\n" + bodyString);
        final JSONObject line = new JSONObject();
        line.put("prev", this.journalTail);
        line.put("body", bodyString);
        line.put("sha256", sha);
        try (FileChannel ch = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.APPEND)) {
            ch.write(ByteBuffer.wrap((line.toString() + "\n").getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
        this.journalTail = sha;
        this.generation = gen;
        // snapshot: rotate the old one into the backups, then move the new one in place
        final JSONObject snapshotBody = new JSONObject();
        snapshotBody.put("generation", gen);
        snapshotBody.put("decisions", all);
        final String sb = snapshotBody.toString();
        final JSONObject snapshot = new JSONObject();
        snapshot.put("schema", SCHEMA);
        snapshot.put("body", sb);
        snapshot.put("sha256", sha256(sb));
        final Path tmp = this.dir.resolve(SNAPSHOT + ".tmp");
        writeForced(tmp, snapshot.toString());
        final Path current = snapshotPath(0);
        if (Files.exists(current)) {
            Files.deleteIfExists(snapshotPath(BACKUPS));
            for (int i = BACKUPS - 1; i >= 1; i--) {
                if (Files.exists(snapshotPath(i))) Files.move(snapshotPath(i), snapshotPath(i + 1), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(current, snapshotPath(1), StandardCopyOption.REPLACE_EXISTING);
        }
        move(tmp, current);
        // marker last: until here a crash leaves a readable generation >= the marker
        Files.createDirectories(this.marker.getParent());
        final Path mtmp = this.marker.resolveSibling(this.marker.getFileName() + ".tmp");
        writeForced(mtmp, markerGen + " " + sha256(Long.toString(markerGen)) + "\n");
        move(mtmp, this.marker);
    }

    private long readMarker() throws IOException {
        final String s;
        try {
            s = new String(Files.readAllBytes(this.marker), StandardCharsets.UTF_8).trim();
        } catch (final NoSuchFileException e) {
            return 0L;
        }
        final String[] parts = s.split(" ");
        if (parts.length != 2 || !parts[1].equals(sha256(parts[0]))) throw new IOException("checksum");
        return Long.parseLong(parts[0]);
    }

    private static JSONObject readSnapshot(final Path p) throws IOException, JSONException {
        final JSONObject o = new JSONObject(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
        if (!SCHEMA.equals(o.getString("schema"))) throw new IOException("schema " + o.optString("schema"));
        final String body = o.getString("body");
        if (!sha256(body).equals(o.getString("sha256"))) throw new IOException("checksum");
        final JSONObject b = new JSONObject(body);
        decisions(b.getJSONArray("decisions")); // validates
        return b;
    }

    private static Map<String, CollectionDecision> decisions(final JSONArray a) throws JSONException {
        final Map<String, CollectionDecision> out = new LinkedHashMap<>();
        for (int i = 0; i < a.length(); i++) {
            final CollectionDecision d = CollectionDecision.fromJson(a.getJSONObject(i));
            out.put(d.id, d);
        }
        return out;
    }

    private static void apply(final Map<String, CollectionDecision> state, final JSONObject body) throws JSONException {
        final Map<String, CollectionDecision> next = decisions(body.getJSONArray("decisions"));
        state.clear();
        state.putAll(next);
    }

    private Path snapshotPath(final int backup) {
        return this.dir.resolve(backup == 0 ? SNAPSHOT : SNAPSHOT + "." + backup);
    }

    private static void writeForced(final Path p, final String content) throws IOException {
        try (FileChannel ch = FileChannel.open(p, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ch.write(ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8)));
            ch.force(true);
        }
    }

    private static void move(final Path from, final Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static String sha256(final String s) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(64);
            for (final byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
