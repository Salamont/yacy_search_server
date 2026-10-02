/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.api.ApiException;

/** One serializing writer. Never replaces invalid input or falls back to a non-atomic rename. */
public final class JobStore {
    private final Path path;
    private JsonObject root;
    private boolean failed;
    @FunctionalInterface public interface Change { void apply(JsonObject document) throws ApiException; }

    public JobStore(final Path path) throws IOException {
        this.path = path;
        try {
            if (Files.exists(path)) {
                if (Files.size(path) > 8_000_000) throw new IOException("Oversized jobstore");
                this.root = new JsonObject(Files.readString(path));
                if (this.root.getInt("schema_version") != 1 || !(this.root.get("revision") instanceof Number)
                        || !(this.root.get("enabled") instanceof Boolean) || !(this.root.get("paused") instanceof Boolean)) {
                    throw new IOException("Invalid jobstore header");
                }
                nonnegative(this.root, "revision"); nonnegative(this.root, "schema_version");
                if (this.root.getLong("revision") < 0 || this.root.getJSONArray("jobs").length() > 100
                        || this.root.getJSONArray("history").length() > 20) throw new IOException("Invalid store limits");
                final java.util.Set<String> ids = new java.util.HashSet<>();
                for (final Object row : this.root.getJSONArray("jobs")) {
                    final JsonObject job = ((JsonObject) row).getJSONObject("definition");
                    JobSchema.validate(job, JobSchema.Limits.hard());
                    if (!ids.add(job.getString("id"))) throw new IOException("Duplicate job id");
                    final JsonObject runtime = ((JsonObject) row).getJSONObject("runtime");
                    for (final String key : java.util.List.of("next_due", "last_served")) nonnegative(runtime, key);
                    runtime.getJSONObject("cursors"); runtime.getJSONObject("source_last_replenish");
                    for (final String key : java.util.List.of("requested")) if (runtime.has(key)) runtime.getBoolean(key);
                    for (final String key : runtime.getJSONObject("cursors").keySet()) nonnegative(runtime.getJSONObject("cursors"), key);
                    for (final String key : runtime.getJSONObject("source_last_replenish").keySet()) nonnegative(runtime.getJSONObject("source_last_replenish"), key);
                }
                if (!this.root.has("active_run")) throw new IOException("Missing active run");
                if (!this.root.isNull("active_run")) {
                    final JsonObject run = this.root.getJSONObject("active_run");
                    java.util.UUID.fromString(run.getString("id"));
                    if (!ids.contains(run.getString("job_id"))) throw new IOException("Run job missing");
                    JobSchema.validate(run.getJSONObject("job"), JobSchema.Limits.hard());
                    if (!run.getString("job_id").equals(run.getJSONObject("job").getString("id"))
                            || !run.getString("config_revision").matches("[a-f0-9]{64}")
                            || !Path.of(run.getString("state_root")).isAbsolute()
                            || !run.getString("collection").matches("[A-Za-z0-9_-]{1,64}")) throw new IOException("Invalid run identity");
                    run.getJSONObject("regions"); nonnegative(run, "started_at");
                    if (!java.util.Set.of("reserved", "running", "waiting_for_crawler", "needs_reconcile", "needs_review").contains(run.getString("phase"))) throw new IOException("Invalid run phase");
                    if (run.getJSONArray("attempts").length() > 500) throw new IOException("Too many attempts");
                    final java.util.Set<String> attempts = new java.util.HashSet<>();
                    for (final Object value : run.getJSONArray("attempts")) {
                        final JsonObject attempt = (JsonObject) value;
                        if (!attempt.getString("id").matches("[a-f0-9]{32}") || !attempts.add(attempt.getString("id"))
                                || !attempt.getString("id").equals(attempt.getString("marker"))
                                || !attempt.getString("profile").equals(run.getJSONObject("job").getString("profile"))
                                || !attempt.getString("domain").matches("[a-z0-9.-]{3,253}")
                                || !java.util.Set.of("prepared", "accepted", "not_submitted", "submitted_unknown").contains(attempt.getString("state"))) throw new IOException("Invalid attempt");
                        attempt.getBoolean("state_applied"); nonnegative(attempt, "submitted_at"); nonnegative(attempt, "recrawl_days");
                        if ("accepted".equals(attempt.getString("state")) && attempt.getString("crawl_id").isBlank()) throw new IOException("Missing crawl id");
                    }
                }
            } else {
                this.root = new JsonObject().put("schema_version", 1).put("revision", 0)
                        .put("enabled", false).put("paused", false).put("jobs", new JsonArray())
                        .put("active_run", JsonObject.NULL).put("history", new JsonArray());
            }
        } catch (final Exception e) { throw new IOException("Invalid discovery jobstore; original left intact", e); }
    }

    private static void nonnegative(final JsonObject object, final String key) throws IOException {
        final Object value = object.get(key);
        if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())
                || ((Number) value).doubleValue() < 0 || ((Number) value).doubleValue() != Math.rint(((Number) value).doubleValue())) {
            throw new IOException("Invalid numeric runtime field");
        }
    }

    public synchronized JsonObject read() { return new JsonObject(this.root.toString()); }
    public synchronized JsonObject change(final Long expected, final Change operation) throws IOException, ApiException {
        if (this.failed) throw new IOException("Jobstore requires reload after failed persistence");
        if (expected != null && expected != this.root.getLong("revision")) {
            throw new ApiException(409, "revision_conflict", "Reload the current jobstore revision before editing.");
        }
        final JsonObject next = read();
        operation.apply(next);
        next.put("revision", this.root.getLong("revision") + 1);
        try { atomicWrite(this.path, next.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        catch (final IOException e) { this.failed = true; throw e; }
        this.root = next;
        return read();
    }

    public static JsonObject job(final JsonObject root, final String id) throws ApiException {
        for (final Object value : root.getJSONArray("jobs")) {
            final JsonObject entry = (JsonObject) value;
            if (entry.getJSONObject("definition").getString("id").equals(id)) return entry;
        }
        throw new ApiException(404, "job_not_found", "Discovery job does not exist.");
    }

    public static void atomicWrite(final Path target, final byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temporary = Files.createTempFile(target.getParent(), ".discovery-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
            catch (final UnsupportedOperationException e) { /* portable file systems */ }
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                final ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) file.write(buffer);
                file.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel directory = FileChannel.open(target.getParent(), StandardOpenOption.READ)) { directory.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
