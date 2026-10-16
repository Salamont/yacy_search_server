/* Copyright 2026 Scoutro contributors. LGPL-2.1-or-later. */
package net.yacy.ai;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.rag.RagQuery;
import net.yacy.cora.util.LogRedaction;
import net.yacy.search.Switchboard;

/** One bounded search-query shortening call; no crawl, indexing, graph write or automatic retry. */
public final class TldrService {
    public static final String ENABLED = "ai.tldr.enabled";
    public static final String DIAGNOSTICS_UNTIL = "ai.tldr.diagnostics_until";
    public static final String DEFAULT_INSTRUCTION = "Make a list of search words with low document frequency for the following prompt; use a JSON Array: ";
    public static final int MAX_INPUT_CHARS = 16000;
    public static final long DIAGNOSTICS_MS = 15 * 60_000L;
    static final int MAX_HISTORY = 32;
    static final long HISTORY_MS = 30 * 60_000L;
    private static final Deque<JSONObject> HISTORY = new ArrayDeque<>();

    private TldrService() { }

    public static boolean automaticEnabled(final Switchboard sb) { return sb != null && sb.getConfigBool(ENABLED, true); }

    public static boolean shouldRun(final boolean enabled, final boolean modelAvailable, final int words, final int sources) {
        return enabled && modelAvailable && words > 40 && sources == 0;
    }

    public static final class Attempt {
        public final JSONObject details = new JSONObject(true);
        public String words = "";
        public String error = "";
        private final long started = System.currentTimeMillis();
        public boolean valid() { return this.error.isEmpty() && !this.words.isEmpty(); }
    }

    public static Attempt execute(final LLM.LLMModel model, final String input, final String instruction, final boolean manual) {
        final Attempt attempt = new Attempt();
        final String question = input == null ? "" : input;
        final String system = instruction == null || instruction.trim().isEmpty() ? DEFAULT_INSTRUCTION : instruction.trim();
        try {
            attempt.details.put("timestamp", Instant.ofEpochMilli(attempt.started).toString());
            attempt.details.put("manual", manual);
            attempt.details.put("reason", manual ? "explicit-test-click" : "long-question-without-sources");
            attempt.details.put("model", model == null ? "" : model.model);
            attempt.details.put("inputWords", countWords(question));
            attempt.details.put("inputTerms", question.length() <= MAX_INPUT_CHARS ? RagQuery.prepare(question).terms.size() : 0);
            attempt.details.put("input", question.length() <= MAX_INPUT_CHARS ? question : "[input exceeds limit]");
            attempt.details.put("instruction", system);
            if (model == null) throw new IOException("Kein TLDR-Modell zugewiesen.");
            if (system.length() > 8192 || (question.length() + system.length()) / 3 + 392 > model.llm.num_ctx)
                throw new IOException("Die Eingabe überschreitet das konfigurierte Kontextbudget des TLDR-Modells.");
            if (question.trim().isEmpty() || question.length() > MAX_INPUT_CHARS) throw new IOException("Die Frage muss 1 bis 16000 Zeichen enthalten.");
            final LLM.Context context = new LLM.Context(system);
            context.addPrompt(question);
            final JSONObject format = new JSONObject().put("type", "json_schema")
                    .put("json_schema", new JSONObject().put("strict", true).put("schema", LLM.listSchema));
            final String raw = model.llm.chatWithResponseFormat(model.model, context, format,
                    Math.min(200, model.llm.max_tokens), 30_000, 16_384,
                    usage -> { try { attempt.details.put("usage", usage); } catch (final JSONException ignored) { } }, true);
            attempt.details.put("raw", raw);
            final JSONArray array = new JSONArray(raw.trim());
            if (array.length() < 1 || array.length() > 32) throw new IOException("Ungültige Antwort: erwartet wird eine kurze JSON-Liste mit Suchbegriffen.");
            final Set<String> words = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                final Object value = array.get(i);
                if (!(value instanceof String) || ((String) value).length() > 160) throw new IOException("Ungültiger Suchbegriff in der Modellantwort.");
                // Treat model output as words, not as search operators or a change of collection scope.
                for (final String word : ((String) value).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                    if (!word.isEmpty()) words.add(word);
                }
            }
            attempt.words = String.join(" ", words);
            final RagQuery query = RagQuery.prepare(attempt.words);
            if (query.isEmpty()) throw new IOException("Die Modellantwort enthält keine nutzbaren Suchbegriffe.");
            attempt.details.put("terms", new JSONArray(words));
            attempt.details.put("effectiveQuery", query.searchString(true));
            attempt.details.put("outputTerms", query.terms.size());
        } catch (final IOException | JSONException | RuntimeException e) {
            attempt.words = "";
            // Never echo an upstream body or a question through an error or unprotected log.
            attempt.error = e instanceof IOException && e.getMessage() != null && !e.getMessage().contains("HTTP")
                    ? LogRedaction.redactMessage(e) : "TLDR-Aufruf fehlgeschlagen oder ungültige JSON-Antwort. Modell und Verbindung prüfen.";
        }
        try {
            attempt.details.put("valid", attempt.valid());
            attempt.details.put("error", attempt.error);
            attempt.details.put("durationMs", System.currentTimeMillis() - attempt.started);
        } catch (final JSONException ignored) { }
        return attempt;
    }

    /** Store only administrator-owned calls, bounded in memory. Other users see their own metadata in their chat only. */
    public static void finish(final Switchboard sb, final Attempt attempt, final int before, final int after, final boolean administratorOwned) {
        remember(attempt, before, after, administratorOwned, sb.getConfigLong(DIAGNOSTICS_UNTIL, 0));
    }

    static void remember(final Attempt attempt, final int before, final int after, final boolean administratorOwned, final long until) {
        try {
            attempt.details.put("sourcesBefore", before);
            attempt.details.put("sourcesAfter", after);
            if (!administratorOwned) return;
            final long now = System.currentTimeMillis();
            final JSONObject entry = new JSONObject(attempt.details.toString());
            entry.put("storedAt", now);
            entry.put("detailsUntil", until > now && until <= now + DIAGNOSTICS_MS ? until : 0);
            // Defence in depth: known credential patterns are redacted even in explicitly enabled diagnostic content.
            for (final String key : new String[] {"input", "raw", "instruction"}) {
                if (entry.has(key)) entry.put(key, LogRedaction.redact(entry.optString(key)));
            }
            synchronized (HISTORY) {
                purge(now);
                if (entry.optLong("detailsUntil") <= now) stripDetails(entry);
                HISTORY.addFirst(entry);
                while (HISTORY.size() > MAX_HISTORY) HISTORY.removeLast();
            }
        } catch (final JSONException ignored) { }
    }

    private static void stripDetails(final JSONObject entry) {
        entry.remove("input"); entry.remove("raw"); entry.remove("instruction");
    }

    private static void purge(final long now) {
        HISTORY.removeIf(entry -> now - entry.optLong("storedAt") >= HISTORY_MS);
        for (final JSONObject entry : HISTORY) if (entry.optLong("detailsUntil") <= now) stripDetails(entry);
    }

    public static JSONArray history() { return history(System.currentTimeMillis()); }

    static JSONArray history(final long now) {
        synchronized (HISTORY) {
            purge(now);
            // Copy: a page render must never mutate retained records.
            try { return new JSONArray(new JSONArray(HISTORY).toString()); }
            catch (final JSONException e) { return new JSONArray(); }
        }
    }

    public static void clearHistory() { synchronized (HISTORY) { HISTORY.clear(); } }

    static int countWords(final String text) { return text.trim().isEmpty() ? 0 : text.trim().split("\\s+").length; }
}
