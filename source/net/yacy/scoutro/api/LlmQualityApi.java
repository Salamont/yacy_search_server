/* Copyright 2026 Scoutro contributors. GPL-2.0-or-later. */
package net.yacy.scoutro.api;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import net.yacy.ai.LLM;
import net.yacy.scoutro.knowledge.KgRuntime;
import net.yacy.scoutro.knowledge.quality.ModelQualityCheck;
import net.yacy.search.Switchboard;

/**
 * The model quality check of the LLM selection (docs/SCOUTRO_LLM_QUALITY_CHECK.md), administrator only, under
 * {@code /scoutro/api/v1/kg/llm-quality}:
 * <ul>
 * <li>{@code GET} the test set, every production model row with the state of its result, and the run;</li>
 * <li>{@code GET .../plan?candidate=…} scope and estimated cost of a check, before anything is sent;</li>
 * <li>{@code GET .../result?candidate=…} the stored result with its deviations and whether it is stale;</li>
 * <li>{@code GET .../testset} the reference pages and their expectations;</li>
 * <li>{@code POST {"action":"start", …}} starts a check, {@code POST {"action":"cancel"}} cancels it.</li>
 * </ul>
 * A check never changes the selection of a model; the row's key is used on the server only.
 */
final class LlmQualityApi {

    static final String DIR = "DATA/SCOUTRO/llm-quality";
    private static final Set<String> PLAN_PARAMETERS = Set.of("candidate", "inputPrice", "outputPrice", "currency", "priceSource",
            "maxCalls", "maxCost");
    private static ModelQualityCheck check;

    private LlmQualityApi() {
    }

    /** The production environment: the rows of the LLM selection, the running graph and the data directory. */
    static final class SwitchboardEnvironment implements ModelQualityCheck.Environment {
        private final Switchboard sb;

        SwitchboardEnvironment(final Switchboard sb) {
            this.sb = sb;
        }

        @Override
        public List<ModelQualityCheck.Candidate> candidates() {
            return LlmQualityApi.candidates(this.sb.getConfig("ai.production_models", "[]"), this.sb.getConfig("ai.model_capabilities", "{}"),
                    hoststub -> LLM.configuredServiceNumCtx(this.sb, hoststub));
        }

        @Override
        public ModelQualityCheck.Inputs inputs() {
            final KgRuntime r = KgRuntime.current();
            return r == null ? null : r.qualityInputs();
        }

        @Override
        public File dir() {
            return new File(this.sb.getDataPath(), DIR);
        }
    }

    /** The candidates of the stored production rows, with their capabilities and the service's configured window. */
    static List<ModelQualityCheck.Candidate> candidates(final String rows, final String capabilities,
            final java.util.function.ToIntFunction<String> numCtx) {
        final List<ModelQualityCheck.Candidate> out = new ArrayList<>();
        JSONObject caps;
        try {
            caps = new JSONObject(new JSONTokener(capabilities == null ? "{}" : capabilities));
        } catch (final JSONException e) {
            caps = new JSONObject();
        }
        final JSONArray a;
        try {
            a = new JSONArray(new JSONTokener(rows == null || rows.isEmpty() ? "[]" : rows));
        } catch (final JSONException e) {
            return out;
        }
        for (int i = 0; i < a.length(); i++) {
            final JSONObject row = a.optJSONObject(i);
            if (row == null || row.optString("model", "").trim().isEmpty() || row.optString("hoststub", "").trim().isEmpty()) {
                continue;
            }
            final String service = row.optString("service", "OLLAMA");
            final String hoststub = row.optString("hoststub", "");
            final String model = row.optString("model", "");
            int maxTokens;
            try {
                maxTokens = Integer.parseInt(row.optString("max_tokens", String.valueOf(LLM.DEFAULT_MAX_TOKENS)).trim());
            } catch (final NumberFormatException e) {
                maxTokens = LLM.DEFAULT_MAX_TOKENS;
            }
            LLM.LLMType type = null;
            try {
                type = LLM.LLMType.valueOf(service);
            } catch (final IllegalArgumentException e) {
                // an unknown service: listed, never run
            }
            final JSONObject entry = caps.optJSONObject(LLM.capabilityKey(type, hoststub, model));
            out.add(new ModelQualityCheck.Candidate(service, hoststub, model, maxTokens, numCtx.applyAsInt(hoststub),
                    type == null ? "unknown" : LLM.formatCapability(entry, type), type == null ? "unknown" : LLM.thinkingCapability(entry, type),
                    row.optBoolean("knowledge", false), row.optString("api_key", "")));
        }
        return out;
    }

    static synchronized ModelQualityCheck check() {
        if (check == null) {
            final Switchboard sb = Switchboard.getSwitchboard();
            if (sb == null) {
                return null;
            }
            check = new ModelQualityCheck(new SwitchboardEnvironment(sb));
        }
        return check;
    }

    private static ApiException api(final ModelQualityCheck.Refused e) {
        return new ApiException(e.status, e.code, e.getMessage());
    }

    private static Integer integer(final String field, final Object value) throws ApiException {
        if (value == null || value == JSONObject.NULL || value.toString().trim().isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(value.toString().trim());
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(field, field + " is a whole number.");
        }
    }

    private static BigDecimal decimal(final String field, final Object value) throws ApiException {
        if (value == null || value == JSONObject.NULL || value.toString().trim().isEmpty()) {
            return null;
        }
        try {
            final BigDecimal d = new BigDecimal(value.toString().trim());
            if (d.signum() < 0) {
                throw new NumberFormatException();
            }
            return d;
        } catch (final NumberFormatException e) {
            throw ApiException.invalid(field, field + " is a number of at least 0.");
        }
    }

    private static String text(final Object value) {
        return value == null || value == JSONObject.NULL ? null : value.toString();
    }

    static JSONObject route(final ModelQualityCheck q, final String method, final String[] parts, final Map<String, String> query,
            final KnowledgeApi.Body body) throws ApiException, IOException {
        if (q == null) {
            throw new ApiException(503, "unavailable", "The quality check is not available.");
        }
        try {
            if (parts.length == 4) {
                if ("GET".equals(method)) {
                    if (!query.isEmpty()) {
                        throw ApiException.invalid(query.keySet().iterator().next(), "This route takes no parameters.");
                    }
                    return q.status();
                }
                if (!"POST".equals(method)) {
                    throw new ApiException(405, "method_not_allowed", "Method " + method + " is not allowed here. Allowed: GET, POST.");
                }
                final JSONObject b = body.get();
                final String action = b.optString("action", "");
                if ("cancel".equals(action)) {
                    return q.cancel();
                }
                if (!"start".equals(action)) {
                    throw ApiException.invalid("action", "action is start or cancel.");
                }
                final JSONObject p = b.optJSONObject("price");
                final ModelQualityCheck.Price price = p == null ? null
                        : ModelQualityCheck.Price.parse(text(p.opt("inputPerMillion")), text(p.opt("outputPerMillion")),
                                text(p.opt("currency")), text(p.opt("source")));
                return q.start(b.optString("candidate", null), price, b.optBoolean("acceptUnknownCost", false),
                        integer("maxCalls", b.opt("maxCalls")), decimal("maxCost", b.opt("maxCost")));
            }
            if (parts.length == 5 && "GET".equals(method)) {
                switch (parts[4]) {
                    case "plan":
                        for (final String k : query.keySet()) {
                            if (!PLAN_PARAMETERS.contains(k)) {
                                throw ApiException.invalid(k, "Unknown parameter.");
                            }
                        }
                        return q.plan(query.get("candidate"), ModelQualityCheck.Price.parse(query.get("inputPrice"), query.get("outputPrice"),
                                query.get("currency"), query.get("priceSource")), integer("maxCalls", query.get("maxCalls")),
                                decimal("maxCost", query.get("maxCost")));
                    case "result":
                        return q.result(query.get("candidate"));
                    case "testset":
                        return ModelQualityCheck.testset();
                    default:
                        break;
                }
            }
        } catch (final ModelQualityCheck.Refused e) {
            throw api(e);
        }
        throw new ApiException(404, "not_found", "Unknown API path. See /scoutro/api/openapi.json.");
    }
}
