/* Scoutro contributors, GPL-2.0-or-later. */
package net.yacy.scoutro.discovery;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.yacy.scoutro.discovery.JsonObject;

/** Bounded parent-owned RPC; stdout is protocol, stderr is never echoed to users/logs. */
public final class DiscoveryProcess {
    @FunctionalInterface public interface Handler { JsonObject request(String action, JsonObject parameters) throws Exception; }
    private static final java.util.Set<Process> ACTIVE = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private DiscoveryProcess() { }
    public static void stopAll() { ACTIVE.forEach(DiscoveryProcess::stop); }
    public static JsonObject run(final Path script, final Path state, final Path snapshot, final JsonObject init,
            final Handler handler, final int timeoutSeconds) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(List.of("python3", script.toString(), "--workdir", state.toString(),
                "--config-dir", snapshot.toString(), "automation"));
        final Map<String, String> environment = builder.environment();
        final Map<String, String> inherited = new java.util.HashMap<>(environment);
        environment.clear();
        for (final String key : List.of("PATH", "LANG", "LC_ALL", "TZ", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY",
                "http_proxy", "https_proxy", "no_proxy", "SSL_CERT_FILE", "SCOUTRO_DISCOVERY_TLDS")) {
            if (inherited.containsKey(key)) environment.put(key, inherited.get(key));
        }
        environment.put("PYTHONDONTWRITEBYTECODE", "1"); environment.put("PYTHONUNBUFFERED", "1");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        final Process process = builder.start();
        ACTIVE.add(process);
        final var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread thread = new Thread(r, "ScoutroDiscovery.process-timeout"); thread.setDaemon(true); return thread;
        });
        watchdog.schedule(() -> stop(process), timeoutSeconds, TimeUnit.SECONDS);
        try (BufferedWriter input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            input.write(init.toString()); input.newLine(); input.flush();
            String line;
            int requests = 0;
            while ((line = boundedLine(output)) != null) {
                final JsonObject message = new JsonObject(line);
                if ("done".equals(message.optString("type"))) return message;
                if (!"request".equals(message.optString("type")) || ++requests > 10000) throw new IOException("Invalid discovery protocol");
                final JsonObject reply = new JsonObject().put("id", message.getInt("id"));
                try { reply.put("result", handler.request(message.getString("action"), message.getJSONObject("params"))); }
                catch (final net.yacy.scoutro.api.ApiException e) {
                    final JsonObject error = new JsonObject(e.toJson()).getJSONObject("error").put("status", e.status());
                    if ("submitted_unknown".equals(e.code())) error.put("uncertain", true)
                            .put("attempt_id", error.getJSONObject("details").getString("attempt_id"));
                    reply.put("error", error);
                }
                input.write(reply.toString()); input.newLine(); input.flush();
            }
            throw new IOException("Discovery process exited without completion");
        } finally { stop(process); ACTIVE.remove(process); watchdog.shutdownNow(); }
    }
    private static String boundedLine(final BufferedReader input) throws IOException {
        final StringBuilder line = new StringBuilder();
        int ch;
        while ((ch = input.read()) >= 0) {
            if (ch == '\n') return line.toString();
            if (line.length() >= 1_000_000) throw new IOException("Oversized discovery message");
            line.append((char) ch);
        }
        return line.isEmpty() ? null : line.toString();
    }
    private static void stop(final Process process) {
        process.descendants().forEach(child -> child.destroyForcibly());
        if (process.isAlive()) process.destroyForcibly();
    }
}
