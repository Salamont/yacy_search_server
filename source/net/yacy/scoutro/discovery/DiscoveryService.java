/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.scoutro.discovery.JsonArray;
import net.yacy.scoutro.discovery.JsonObject;
import net.yacy.scoutro.api.ApiException;
import net.yacy.scoutro.api.DiscoveryGateway;
import net.yacy.search.Switchboard;

/** One bounded coordinator; WorkTables only wakes it. No candidate-state writer or crawler here. */
public final class DiscoveryService implements AutoCloseable {
    public interface Backend {
        JsonObject capacity();
        JsonArray crawls() throws ApiException;
        JsonObject search(JsonObject parameters) throws ApiException;
        JsonObject start(JsonObject parameters, String marker) throws ApiException;
    }
    @FunctionalInterface public interface Runner {
        JsonObject run(Path script, Path state, Path config, JsonObject init, DiscoveryProcess.Handler handler, int timeout) throws Exception;
    }
    private static volatile DiscoveryService instance;
    public final JobStore store;
    private final Path configRoot, stateRoot, snapshots, script;
    private final DiscoveryHeartbeat heartbeat;
    private final Backend backend;
    private final LongSupplier clock;
    private final JobSchema.Limits limits;
    private final Runner runner;
    private final int timeout;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        final Thread t = new Thread(r, "ScoutroDiscovery.coordinator"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile JsonObject stateSnapshot = new JsonObject().put("available", false);
    private volatile long snapshotAt;
    private volatile String waiting = "disabled";

    public DiscoveryService(final Path data, final Path app, final Path config, final Path state,
            final DiscoveryHeartbeat heartbeat, final Backend backend, final LongSupplier clock,
            final JobSchema.Limits limits, final Runner runner, final int timeout) throws IOException {
        this.store = new JobStore(data.resolve("SETTINGS/scoutro-discovery-jobs.json"));
        this.configRoot = config; this.stateRoot = state; this.snapshots = data.resolve("SCOUTRO/automation/config-snapshots");
        this.script = app.resolve("tools/scoutro/scoutro-discovery"); this.heartbeat = heartbeat;
        this.backend = backend; this.clock = clock; this.limits = limits; this.runner = runner; this.timeout = timeout;
    }
    public static Path resolveRoot(final String environment, final String setting, final Path fallback) {
        return Path.of(environment != null && !environment.isBlank() ? environment
                : setting != null && !setting.isBlank() ? setting : fallback.toString()).toAbsolutePath().normalize();
    }
    public static DiscoveryService get() throws ApiException {
        if (instance == null) synchronized (DiscoveryService.class) {
            if (instance == null) {
                final Switchboard sb = Switchboard.getSwitchboard();
                if (sb == null) throw new ApiException(503, "unavailable", "Scoutro is not initialized.");
                final Path data = sb.getDataPath().toPath().resolve("DATA");
                try {
                    instance = new DiscoveryService(data, sb.getAppPath().toPath(),
                            resolveRoot(System.getenv("SCOUTRO_CONFIG_ROOT"), sb.getConfig("scoutro.discovery.configRoot", ""), data.resolve("SCOUTRO/config")),
                            resolveRoot(System.getenv("SCOUTRO_DISCOVERY_DIR"), sb.getConfig("scoutro.discovery.stateRoot", ""), data.resolve("SCOUTRO/discovery")),
                            new DiscoveryHeartbeat(sb.tables), new DiscoveryGateway(), System::currentTimeMillis,
                            new JobSchema.Limits(Math.max(1, Math.min(500, sb.getConfigInt("scoutro.discovery.maxDomains", 100))),
                                    Math.max(1, Math.min(10000, sb.getConfigInt("scoutro.discovery.maxPages", 100))),
                                    Math.max(0, Math.min(10, sb.getConfigInt("scoutro.discovery.maxDepth", 5)))),
                            DiscoveryProcess::run, Math.max(60, Math.min(7200, sb.getConfigInt("scoutro.discovery.workerTimeoutSeconds", 3600))));
                } catch (final IOException e) { throw unavailable(); }
            }
        }
        return instance;
    }
    public static void closeCurrent() { final DiscoveryService service = instance; if (service != null) service.close(); instance = null; }
    private static ApiException unavailable() { return new ApiException(503, "jobstore_unavailable", "Discovery persistence is unavailable; existing files are retained."); }
    private RuntimeCatalog catalog() throws ApiException { return RuntimeCatalog.load(this.configRoot); }
    public JsonObject catalogJson() throws ApiException { return catalog().json().put("limits", this.limits.json()); }
    public JsonObject status() throws ApiException {
        final JsonObject root = this.store.read();
        final JsonObject result = new JsonObject().put("revision", root.getLong("revision"))
                .put("enabled", root.getBoolean("enabled")).put("paused", root.getBoolean("paused"))
                .put("worker_busy", this.busy.get()).put("waiting_reason", this.waiting)
                .put("state", new JsonObject(this.stateSnapshot.toString())).put("state_observed_at", this.snapshotAt)
                .put("active_run", publicRun(root.optJSONObject("active_run")))
                .put("capacity", this.backend.capacity()).put("job_count", root.getJSONArray("jobs").length())
                .put("last_run", root.getJSONArray("history").isEmpty() ? JsonObject.NULL
                        : root.getJSONArray("history").get(root.getJSONArray("history").length() - 1));
        if (root.optJSONObject("active_run") == null && (!root.getBoolean("enabled") || root.getBoolean("paused"))) {
            result.put("waiting_reason", root.getBoolean("paused") ? "paused" : "disabled");
        }
        try { result.put("heartbeat", this.heartbeat.status()); } catch (final IOException e) { throw unavailable(); }
        if (root.getBoolean("enabled") && !result.getJSONObject("heartbeat").getBoolean("enabled")) result.put("waiting_reason", "heartbeat_disabled_or_duplicate");
        try { result.put("config_revision", catalog().revision()); }
        catch (final ApiException e) { result.put("config_error", e.code()); }
        if (this.clock.getAsLong() - this.snapshotAt > 30000) refreshState();
        return result;
    }
    private static Object publicRun(final JsonObject run) {
        if (run == null) return JsonObject.NULL;
        final JsonObject out = new JsonObject();
        for (final String field : java.util.List.of("id", "job_id", "phase", "started_at", "finished_at", "error", "report")) {
            if (run.has(field)) out.put(field, run.get(field));
        }
        out.put("attempt_count", run.getJSONArray("attempts").length());
        return out;
    }
    public JsonObject jobs(final String id) throws ApiException {
        final JsonObject root = this.store.read();
        final JsonArray jobs = new JsonArray();
        RuntimeCatalog config = null;
        try { config = catalog(); } catch (final ApiException e) { /* each row displays blocked config */ }
        for (final Object item : root.getJSONArray("jobs")) {
            final JsonObject row = (JsonObject) item;
            final JsonObject definition = row.getJSONObject("definition");
            if (id != null && !definition.getString("id").equals(id)) continue;
            String state = !definition.getBoolean("enabled") ? "disabled" : definition.getBoolean("paused") ? "paused"
                    : row.getJSONObject("runtime").optLong("next_due", 0) <= this.clock.getAsLong() ? "due" : "idle";
            String reason = row.getJSONObject("runtime").optString("error", "");
            if (!reason.isEmpty()) state = "error";
            if (config == null) { state = "error"; reason = "runtime_config_invalid"; }
            else try { config.validate(definition); JobSchema.validate(definition, this.limits); }
            catch (final ApiException e) { state = "error"; reason = e.code(); }
            final JsonObject active = root.optJSONObject("active_run");
            if (active != null && active.getString("job_id").equals(definition.getString("id"))) state = active.getString("phase");
            final JsonObject fresh = this.stateSnapshot.optJSONObject("jobs");
            jobs.put(new JsonObject().put("definition", definition).put("runtime", row.getJSONObject("runtime"))
                    .put("status", state).put("waiting_reason", reason).put("fresh_backlog",
                            fresh != null && fresh.has(definition.getString("id")) ? fresh.getJSONObject(definition.getString("id")).getInt("fresh") : JsonObject.NULL));
        }
        if (id != null && jobs.isEmpty()) throw new ApiException(404, "job_not_found", "Discovery job does not exist.");
        return new JsonObject().put("revision", root.getLong("revision")).put("jobs", jobs);
    }
    public JsonObject export() {
        final JsonArray jobs = new JsonArray();
        for (final Object value : this.store.read().getJSONArray("jobs")) jobs.put(((JsonObject) value).getJSONObject("definition"));
        return new JsonObject().put("schema_version", 1).put("jobs", jobs);
    }
    public JsonObject create(final JsonObject input, final Long revision) throws ApiException {
        if (input.has("id")) throw ApiException.invalid("id", "Job id is assigned by the server.");
        final JsonObject definition = JobSchema.defaults(input);
        JobSchema.validate(definition, this.limits); catalog().validate(definition);
        change(revision, root -> {
            if (root.getJSONArray("jobs").length() >= 100) throw ApiException.invalid("jobs", "Maximum job count reached.");
            root.getJSONArray("jobs").put(new JsonObject().put("definition", definition).put("runtime", new JsonObject()
                    .put("next_due", this.clock.getAsLong()).put("last_served", 0).put("cursors", new JsonObject())
                    .put("source_last_replenish", new JsonObject())));
        });
        return jobs(definition.getString("id"));
    }
    public JsonObject edit(final String id, final JsonObject patch, final Long revision) throws ApiException {
        if (revision == null) throw new ApiException(428, "revision_required", "Send If-Match with the current revision.");
        if (patch.has("id")) throw ApiException.invalid("id", "Job identity is immutable.");
        final RuntimeCatalog config = catalog();
        change(revision, root -> {
            final JsonObject row = JobStore.job(root, id);
            final JsonObject definition = JobSchema.merge(row.getJSONObject("definition"), patch);
            JobSchema.validate(definition, this.limits); config.validate(definition);
            row.put("definition", definition);
        });
        return jobs(id);
    }
    public JsonObject delete(final String id, final Long revision) throws ApiException {
        if (revision == null) throw new ApiException(428, "revision_required", "Send If-Match with the current revision.");
        change(revision, root -> {
            JobStore.job(root, id);
            if (root.optJSONObject("active_run") != null && root.getJSONObject("active_run").getString("job_id").equals(id)) {
                throw new ApiException(409, "job_busy", "Wait for the current batch before deleting its job.");
            }
            final JsonArray kept = new JsonArray();
            for (final Object value : root.getJSONArray("jobs")) if (!((JsonObject) value).getJSONObject("definition").getString("id").equals(id)) kept.put(value);
            root.put("jobs", kept);
        });
        return jobs(null);
    }
    private JsonObject change(final Long revision, final JobStore.Change change) throws ApiException {
        try { return this.store.change(revision, change); } catch (final IOException e) { throw unavailable(); }
    }
    public JsonObject global(final String action, final Long revision) throws ApiException {
        if (revision == null) throw new ApiException(428, "revision_required", "Send If-Match with the current revision.");
        try {
            // Serialize heartbeat + global store mutation with all other admin edits.
            synchronized (this.store) {
                if (this.store.read().getLong("revision") != revision) throw new ApiException(409, "revision_conflict", "Reload the current revision.");
                if ("enable".equals(action)) { catalog(); this.heartbeat.enable(); }
                else if ("disable".equals(action)) this.heartbeat.disable();
                else if (!java.util.Set.of("pause", "resume").contains(action)) throw ApiException.invalid("action", "Unknown global action.");
                change(revision, root -> {
                    if ("enable".equals(action)) root.put("enabled", true);
                    if ("disable".equals(action)) root.put("enabled", false);
                    if ("pause".equals(action)) root.put("paused", true);
                    if ("resume".equals(action)) root.put("paused", false);
                });
            }
        } catch (final IOException e) { throw unavailable(); }
        if ("enable".equals(action) || "resume".equals(action)) this.waiting = "idle";
        return status();
    }
    public JsonObject requestRun(final String id, final String requestId) throws ApiException {
        if (!requestId.matches("[A-Za-z0-9_-]{1,80}")) throw ApiException.invalid("request_id", "Use a unique request id.");
        final RuntimeCatalog config = catalog();
        change(null, root -> {
            if (!root.getBoolean("enabled") || root.getBoolean("paused")) throw new ApiException(409, "paused", "Enable and resume global automation first.");
            final JsonObject row = JobStore.job(root, id);
            config.validate(row.getJSONObject("definition"));
            if (row.getJSONObject("definition").getBoolean("paused")) throw new ApiException(409, "paused", "Resume the job first.");
            final JsonObject runtime = row.getJSONObject("runtime");
            if (requestId.equals(runtime.optString("last_request_id"))) return;
            if (root.optJSONObject("active_run") != null && root.getJSONObject("active_run").getString("job_id").equals(id)) throw new ApiException(409, "job_busy", "Job already has an active batch.");
            runtime.put("requested", true).put("last_request_id", requestId);
        });
        return tick();
    }
    public JsonObject tick() {
        if (this.closed.get()) return new JsonObject().put("accepted", false).put("reason", "shutdown");
        final JsonObject root = this.store.read();
        // Reconciliation may proceed while disabled; it never dispatches new seeds.
        if (root.optJSONObject("active_run") == null && (!root.getBoolean("enabled") || root.getBoolean("paused"))) {
            this.waiting = root.getBoolean("paused") ? "paused" : "disabled";
            return new JsonObject().put("accepted", false).put("reason", this.waiting);
        }
        if (!this.busy.compareAndSet(false, true)) return new JsonObject().put("accepted", false).put("reason", "busy");
        this.executor.execute(() -> {
            try { advance(); }
            catch (final Exception e) {
                if (!(e instanceof ApiException)) ConcurrentLog.severe("ScoutroDiscovery",
                        "Unexpected coordinator failure (exception messages redacted)", diagnostic(e, 0));
                this.waiting = e instanceof ApiException ? ((ApiException) e).code() : "coordinator_error";
                try {
                    change(null, next -> {
                        final JsonObject run = next.optJSONObject("active_run");
                        if (run != null) run.put("error", this.waiting).put("phase", "needs_reconcile");
                    });
                } catch (final ApiException ignored) { /* persistence failure already blocks external effects */ }
            } finally { this.busy.set(false); }
        });
        return new JsonObject().put("accepted", true);
    }
    /** Preserve exception types, causes and call sites without external URLs, credentials or payloads. */
    private static Throwable diagnostic(final Throwable failure, final int depth) {
        final Throwable safe = new Throwable(failure.getClass().getName());
        safe.setStackTrace(failure.getStackTrace());
        if (failure.getCause() != null && depth < 8) safe.initCause(diagnostic(failure.getCause(), depth + 1));
        return safe;
    }
    /** Public for deterministic tests; production only executes it on the single coordinator. */
    public void advance() throws Exception {
        if (this.store.read().optJSONObject("active_run") != null) {
            reconcile();
            return; // at most one batch per wakeup, including completion/recovery.
        }
        final JsonObject root = this.store.read();
        if (!root.getBoolean("enabled") || root.getBoolean("paused")) { this.waiting = "paused_or_disabled"; return; }
        if (!this.heartbeat.status().getBoolean("enabled")) { this.waiting = "heartbeat_disabled_or_duplicate"; return; }
        final JsonObject capacity = this.backend.capacity();
        if (!capacity.getBoolean("allowed")) { this.waiting = capacity.optString("reason", "capacity"); return; }
        final RuntimeCatalog config = catalog();
        final long now = this.clock.getAsLong();
        final JsonObject row = choose(root, now);
        if (row == null) { this.waiting = "no_due_job"; return; }
        final JsonObject job = row.getJSONObject("definition");
        try { config.validate(job); JobSchema.validate(job, this.limits); }
        catch (final ApiException e) {
            change(null, next -> JobStore.job(next, job.getString("id")).getJSONObject("runtime")
                    .put("error", e.code()).put("next_due", now + job.getJSONObject("schedule").getLong("every_minutes") * 60000)
                    .put("last_served", now).put("requested", false));
            this.waiting = e.code(); return;
        }
        final Path frozen = config.freeze(this.snapshots);
        final JsonObject run = new JsonObject().put("id", UUID.randomUUID().toString()).put("job_id", job.getString("id"))
                .put("job", job).put("manual", row.getJSONObject("runtime").optBoolean("requested")).put("phase", "reserved").put("started_at", now).put("config_revision", config.revision())
                .put("state_root", this.stateRoot.toString()).put("collection", config.collection(job.getString("profile")))
                .put("regions", config.json().getJSONObject("regions")).put("attempts", new JsonArray());
        change(root.getLong("revision"), next -> {
            if (next.optJSONObject("active_run") != null) throw new ApiException(409, "busy", "Batch already reserved.");
            next.put("active_run", run);
            final JsonObject runtime = JobStore.job(next, job.getString("id")).getJSONObject("runtime");
            runtime.put("next_due", now + job.getJSONObject("schedule").getLong("every_minutes") * 60000)
                    .put("last_served", now).put("requested", false).remove("error");
        });
        final JsonObject init = initialization(run, "run");
        final JsonArray replenish = new JsonArray();
        final JsonObject runtime = JobStore.job(root, job.getString("id")).getJSONObject("runtime");
        final JsonObject last = runtime.optJSONObject("source_last_replenish");
        for (final String source : job.getJSONObject("sources").keySet()) {
            if (last == null || now - last.optLong(source, 0) >= job.getJSONObject("discovery").getLong("replenish_interval_hours") * 3600000) replenish.put(source);
        }
        init.put("replenish_due", !replenish.isEmpty()).put("replenish_sources", replenish)
                .put("cursors", runtime.optJSONObject("cursors") == null ? new JsonObject() : runtime.getJSONObject("cursors"));
        change(null, next -> next.getJSONObject("active_run").put("phase", "running"));
        final JsonObject answer = this.runner.run(this.script, this.stateRoot, frozen, init, (action, params) -> request(run.getString("id"), action, params), this.timeout);
        finishProcess(answer);
    }
    public static JsonObject choose(final JsonObject root, final long now) {
        final java.util.List<JsonObject> due = new java.util.ArrayList<>();
        for (final Object item : root.getJSONArray("jobs")) {
            final JsonObject row = (JsonObject) item;
            final JsonObject job = row.getJSONObject("definition"), runtime = row.getJSONObject("runtime");
            if (!job.getBoolean("paused") && (runtime.optBoolean("requested") || job.getBoolean("enabled") && runtime.optLong("next_due", 0) <= now)) due.add(row);
        }
        due.sort(Comparator.<JsonObject>comparingLong(r -> r.getJSONObject("runtime").optLong("next_due", 0))
                .thenComparingLong(r -> r.getJSONObject("runtime").optLong("last_served", 0))
                .thenComparing(r -> r.getJSONObject("definition").getString("id")));
        return due.isEmpty() ? null : due.get(0);
    }
    private JsonObject initialization(final JsonObject run, final String operation) {
        final JsonArray definitions = new JsonArray();
        for (final Object value : this.store.read().getJSONArray("jobs")) definitions.put(((JsonObject) value).getJSONObject("definition"));
        return new JsonObject().put("operation", operation).put("job", run.getJSONObject("job"))
                .put("collection", run.getString("collection")).put("regions", run.getJSONObject("regions")).put("jobs", definitions);
    }
    private JsonObject request(final String runId, final String action, final JsonObject params) throws Exception {
        final JsonObject root = this.store.read(), run = root.getJSONObject("active_run"), job = run.getJSONObject("job");
        if (!runId.equals(run.getString("id"))) throw new ApiException(409, "stale_run", "Run reservation changed.");
        switch (action) {
            case "admission":
                final JsonObject current = JobStore.job(root, run.getString("job_id")).getJSONObject("definition");
                if (this.closed.get() || !root.getBoolean("enabled") || root.getBoolean("paused") || current.getBoolean("paused")
                        || (!run.optBoolean("manual") && !current.getBoolean("enabled"))) return new JsonObject().put("allowed", false);
                if (!this.heartbeat.status().getBoolean("enabled")) return new JsonObject().put("allowed", false);
                return this.backend.capacity();
            case "search":
                JobSchema.keys(params, "query", "source", "limit");
                if (!job.getJSONObject("sources").has("freeworld")) throw ApiException.invalid("source", "Source not in reserved job.");
                return this.backend.search(params);
            case "source_complete":
                final String source = params.getString("source");
                if (!job.getJSONObject("sources").has(source)) throw ApiException.invalid("source", "Unknown job source.");
                change(null, next -> {
                    final JsonObject runtime = JobStore.job(next, run.getString("job_id")).getJSONObject("runtime");
                    runtime.getJSONObject("cursors").put(source, params.getInt("cursor"));
                    if (params.optBoolean("cycle_complete", true)) runtime.getJSONObject("source_last_replenish").put(source, this.clock.getAsLong());
                });
                return new JsonObject();
            case "ack":
                change(null, next -> {
                    final JsonObject intent = attempt(next.getJSONObject("active_run"), params.getString("attempt_id"));
                    if (!"accepted".equals(intent.getString("state"))) throw new ApiException(409, "attempt_unconfirmed", "Only accepted starts can be acknowledged.");
                    intent.put("state_applied", true);
                });
                return new JsonObject();
            case "crawl":
                JobSchema.keys(params, "domain", "url");
                final String domain = params.getString("domain"), url = params.getString("url");
                final URI uri = new URI(url);
                if (!domain.matches("[a-z0-9.-]{3,253}") || uri.getHost() == null
                        || !(uri.getHost().equals(domain) || uri.getHost().endsWith("." + domain))) throw ApiException.invalid("url", "Candidate domain mismatch.");
                if (!request(runId, "admission", new JsonObject()).getBoolean("allowed")) throw new ApiException(409, "capacity", "Admission is paused or capacity is unavailable.");
                final String marker = UUID.randomUUID().toString().replace("-", "");
                final JsonObject intent = new JsonObject().put("id", marker).put("marker", marker).put("domain", domain)
                        .put("profile", job.getString("profile")).put("state", "prepared").put("submitted_at", this.clock.getAsLong())
                        .put("recrawl_days", job.getJSONObject("processing").getJSONObject("recrawl").getInt("days"))
                        .put("state_applied", false);
                change(null, next -> next.getJSONObject("active_run").getJSONArray("attempts").put(intent));
                final JsonObject parameters = new JsonObject().put("url", url).put("collection", run.getString("collection"))
                        .put("depth", job.getJSONObject("batch").getInt("depth")).put("maxPages", job.getJSONObject("batch").getInt("max_pages"))
                        .put("scope", "domain");
                try {
                    final JsonObject result = this.backend.start(parameters, marker);
                    change(null, next -> attempt(next.getJSONObject("active_run"), marker).put("state", "accepted").put("crawl_id", result.getString("id")));
                    return result.put("attempt_id", marker);
                } catch (final ApiException e) {
                    final boolean definite = java.util.Set.of("host_busy", "collection_conflict", "crawl_rejected", "invalid_request").contains(e.code());
                    change(null, next -> attempt(next.getJSONObject("active_run"), marker).put("state", definite ? "not_submitted" : "submitted_unknown"));
                    if (definite) throw e;
                    throw new ApiException(503, "submitted_unknown", "Crawl start outcome requires reconciliation.",
                            new JsonObject().put("attempt_id", marker));
                }
            default: throw ApiException.invalid("action", "Unregistered process action.");
        }
    }
    private static JsonObject attempt(final JsonObject run, final String id) throws ApiException {
        for (final Object value : run.getJSONArray("attempts")) if (((JsonObject) value).getString("id").equals(id)) return (JsonObject) value;
        throw new ApiException(409, "attempt_unknown", "Attempt is not part of this run.");
    }
    private void finishProcess(final JsonObject answer) throws ApiException {
        if (answer.has("snapshot")) { this.stateSnapshot = answer.getJSONObject("snapshot"); this.snapshotAt = this.clock.getAsLong(); }
        change(null, root -> {
            final JsonObject run = root.getJSONObject("active_run");
            if (answer.has("error")) {
                run.put("error", answer.getString("error"));
                JobStore.job(root, run.getString("job_id")).getJSONObject("runtime").put("error", answer.getString("error"));
            }
            final String error = answer.optString("error");
            if (java.util.Set.of("busy", "paused").contains(error) && run.getJSONArray("attempts").isEmpty()) {
                final JsonObject runtime = JobStore.job(root, run.getString("job_id")).getJSONObject("runtime");
                runtime.put("next_due", this.clock.getAsLong()).remove("error");
            }
            run.put("report", answer.optJSONObject("report") == null ? new JsonObject() : answer.getJSONObject("report"));
            run.put("phase", "submitted_unknown".equals(error) ? "needs_reconcile" : "waiting_for_crawler");
            if (run.getJSONArray("attempts").isEmpty()) complete(root);
        });
        final JsonObject active = this.store.read().optJSONObject("active_run");
        this.waiting = answer.has("error") ? answer.getString("error")
                : active != null ? "waiting_for_crawler" : answer.optJSONObject("report") == null ? "idle"
                : answer.getJSONObject("report").optString("waiting_reason", "idle");
    }
    private void reconcile() throws Exception {
        final JsonObject run = this.store.read().getJSONObject("active_run");
        if (!run.getString("state_root").equals(this.stateRoot.toString())) { this.waiting = "state_root_changed"; return; }
        final JsonArray crawls = this.backend.crawls();
        final Map<String, JsonObject> ids = new java.util.HashMap<>(), markers = new java.util.HashMap<>();
        for (final Object value : crawls) {
            final JsonObject crawl = (JsonObject) value;
            ids.put(crawl.getString("id"), crawl);
            if (!crawl.isNull("startMarker") && !crawl.optString("startMarker").isEmpty()) markers.put(crawl.getString("startMarker"), crawl);
        }
        final AtomicBoolean unresolved = new AtomicBoolean();
        change(null, root -> {
            final JsonObject active = root.getJSONObject("active_run");
            for (final Object value : active.getJSONArray("attempts")) {
                final JsonObject intent = (JsonObject) value;
                if (java.util.Set.of("prepared", "submitted_unknown").contains(intent.getString("state"))) {
                    final JsonObject crawl = markers.get(intent.getString("marker"));
                    if (crawl == null) unresolved.set(true);
                    else intent.put("state", "accepted").put("crawl_id", crawl.getString("id"));
                }
            }
            if (unresolved.get()) active.put("phase", "needs_review").put("error", "submitted_unknown");
            else {
                active.put("phase", "waiting_for_crawler");
                if ("submitted_unknown".equals(active.optString("error"))) active.remove("error");
                final JsonObject runtime = JobStore.job(root, active.getString("job_id")).getJSONObject("runtime");
                if ("submitted_unknown".equals(runtime.optString("error"))) runtime.remove("error");
            }
        });
        if (unresolved.get()) { this.waiting = "submitted_unknown"; return; }
        final JsonObject updated = this.store.read().getJSONObject("active_run");
        final JsonArray confirmations = new JsonArray();
        for (final Object value : updated.getJSONArray("attempts")) {
            final JsonObject intent = (JsonObject) value;
            if ("accepted".equals(intent.getString("state")) && !intent.getBoolean("state_applied")) confirmations.put(intent);
        }
        if (!confirmations.isEmpty()) {
            final JsonObject init = initialization(updated, "confirm").put("confirmations", confirmations);
            final JsonObject answer = this.runner.run(this.script, this.stateRoot, this.snapshots.resolve(updated.getString("config_revision")), init,
                    (action, params) -> request(updated.getString("id"), action, params), this.timeout);
            if (answer.has("error")) { this.waiting = answer.getString("error"); return; }
        }
        boolean running = false;
        for (final Object value : updated.getJSONArray("attempts")) {
            final JsonObject intent = (JsonObject) value;
            if (!"accepted".equals(intent.getString("state"))) continue;
            final JsonObject crawl = ids.get(intent.getString("crawl_id"));
            if (crawl == null) {
                change(null, root -> root.getJSONObject("active_run").put("phase", "needs_review").put("error", "crawl_status_unknown"));
                this.waiting = "crawl_status_unknown"; return;
            }
            if (!"terminated".equals(crawl.optString("state"))) running = true;
        }
        if (running) { this.waiting = "waiting_for_crawler"; return; }
        change(null, DiscoveryService::complete);
        this.waiting = "idle";
    }
    private static void complete(final JsonObject root) throws ApiException {
        final JsonObject run = root.getJSONObject("active_run");
        run.put("finished_at", System.currentTimeMillis());
        JobStore.job(root, run.getString("job_id")).getJSONObject("runtime").put("last_run", publicRun(run));
        final JsonArray history = root.getJSONArray("history"); history.put(publicRun(run));
        final JsonArray bounded = new JsonArray();
        for (int i = Math.max(0, history.length() - 20); i < history.length(); i++) bounded.put(history.get(i));
        root.put("history", bounded).put("active_run", JsonObject.NULL);
    }
    private void refreshState() {
        if (!this.busy.compareAndSet(false, true)) return;
        this.executor.execute(() -> {
            try {
                final JsonObject root = this.store.read();
                final JsonArray jobs = new JsonArray();
                for (final Object row : root.getJSONArray("jobs")) jobs.put(((JsonObject) row).getJSONObject("definition"));
                JsonObject regions = new JsonObject();
                try { regions = catalog().json().getJSONObject("regions"); } catch (final ApiException ignored) { }
                final JsonObject answer = this.runner.run(this.script, this.stateRoot, this.configRoot,
                        new JsonObject().put("operation", "status").put("jobs", jobs).put("regions", regions),
                        (action, params) -> { throw new ApiException(403, "read_only", "Status cannot perform actions."); }, 60);
                this.stateSnapshot = answer.has("snapshot") ? answer.getJSONObject("snapshot")
                        : new JsonObject().put("available", false).put("error", answer.optString("error", "state_unavailable"));
            } catch (final Exception e) { this.stateSnapshot = new JsonObject().put("available", false).put("error", "state_unavailable"); }
            finally { this.snapshotAt = this.clock.getAsLong(); this.busy.set(false); }
        });
    }
    public void recordHeartbeat(final String key) throws ApiException {
        try { this.heartbeat.record(key); } catch (final IOException e) { throw unavailable(); }
    }
    @Override public void close() { this.closed.set(true); this.executor.shutdownNow(); DiscoveryProcess.stopAll(); }
}
