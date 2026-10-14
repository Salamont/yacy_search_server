/*
 *  ModelQualityCheck
 *  Copyright 2026 by Scoutro contributors
 *  Scoutro is an independent community project based on YaCy.
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  General Public License for more details.
 */

package net.yacy.scoutro.knowledge.quality;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import net.yacy.ai.LLM;
import net.yacy.cora.util.LogRedaction;
import net.yacy.scoutro.knowledge.KgConfig;
import net.yacy.scoutro.knowledge.KgJson;
import net.yacy.scoutro.knowledge.extract.Extraction;
import net.yacy.scoutro.knowledge.extract.JsonLdExtractor;
import net.yacy.scoutro.knowledge.extract.KnowledgePrompt;
import net.yacy.scoutro.knowledge.extract.LlmClient;
import net.yacy.scoutro.knowledge.extract.LlmExtractor;
import net.yacy.scoutro.knowledge.extract.MetadataExtractor;
import net.yacy.scoutro.knowledge.extract.RequestBudget;
import net.yacy.scoutro.knowledge.extract.RuleExtractor;
import net.yacy.scoutro.knowledge.extract.Vocabulary;
import net.yacy.scoutro.knowledge.extract.YacyLlmClient;
import net.yacy.scoutro.knowledge.quality.QualityCases.Case;
import net.yacy.scoutro.knowledge.sync.LlmReading;
import net.yacy.scoutro.knowledge.sync.SolrDoc;

/**
 * The quality check of a candidate model for the knowledge usage (docs/SCOUTRO_LLM_QUALITY_CHECK.md): it reads the
 * reference pages of {@link QualityCases} with the candidate exactly as the LLM tier reads a page ({@link LlmReading}:
 * the real client, the active prompt, the chunks, the request budget, the validator and the extraction the publication
 * takes) and compares the result with the expectations ({@link QualityEvaluator}).
 * <p>
 * It writes nothing but its own result: no graph, no extraction cache, no index, no setting; a check never selects or
 * deselects a model. The administrator starts it explicitly, sees its scope and its maximum cost first, and every
 * request (retries included) is admitted against a cap of calls, tokens and, where a price is given, cost; a run can be
 * cancelled. The result keeps its provenance; a change of the model's row, the prompt, the schema, the validator, the
 * rules or the test set makes it stale, and nothing is run again on its own.
 */
public final class ModelQualityCheck {

    public static final String THREAD = "ScoutroKG.quality";
    private static final net.yacy.cora.util.ConcurrentLog LOG = new net.yacy.cora.util.ConcurrentLog("SCOUTRO-QUALITY");
    /** The most requests one run may make, retries included, whatever the administrator sets. */
    public static final int MAX_CALLS = 100;
    /** The worst case of a prompt: at least this many characters per token (every tokenizer in use stays above). */
    static final double WORST_CHARS_PER_TOKEN = 2.0;
    /** Tokens of the chat template around the messages, counted in the worst case. */
    static final int FRAME_TOKENS = 64;
    /** Characters of a provider's error message kept. */
    static final int MAX_ERROR_CHARS = 300;
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    /** The state of a model's result. */
    public static final String UNCHECKED = "unchecked", PASSED = "passed", DEVIATIONS = "deviations", FAILED = "technical_failure";
    /** The state of the run. */
    public static final String IDLE = "idle", RUNNING = "running", CANCELLING = "cancelling", FINISHED = "finished",
            CANCELLED = "cancelled", STOPPED = "stopped";
    /** Why a run stopped before its end. */
    public static final String CALL_CAP = "call_cap", TOKEN_CAP = "token_cap", COST_CAP = "cost_cap", CANCEL = "cancelled";

    /** A refusal of the API: the HTTP status, a code and a message. */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        public final int status;
        public final String code;

        Refused(final int status, final String code, final String message) {
            super(message);
            this.status = status;
            this.code = code;
        }
    }

    // ------------------------------------------------------------ candidate

    /** A candidate: a row of the production models (service, endpoint, model and its settings); its key never leaves the server. */
    public static final class Candidate {
        public final String service;
        public final String hoststub;
        public final String model;
        public final int maxTokens;
        /** The service's configured window, or 0 for none (then the window is detected or assumed as for the graph). */
        public final int numCtx;
        public final String formatCapability;
        public final String thinkingCapability;
        /** True if the row is the selected model of the knowledge usage. */
        public final boolean active;
        private final String apiKey;

        public Candidate(final String service, final String hoststub, final String model, final int maxTokens, final int numCtx,
                final String formatCapability, final String thinkingCapability, final boolean active, final String apiKey) {
            this.service = service == null ? "" : service.trim();
            this.hoststub = LLM.normalizeHoststub(hoststub);
            this.model = model == null ? "" : model.trim();
            this.maxTokens = maxTokens <= 0 ? LLM.DEFAULT_MAX_TOKENS : maxTokens;
            this.numCtx = Math.max(0, numCtx);
            this.formatCapability = formatCapability == null ? "unknown" : formatCapability;
            this.thinkingCapability = thinkingCapability == null ? "unknown" : thinkingCapability;
            this.active = active;
            this.apiKey = apiKey == null ? "" : apiKey;
        }

        /** The key of the row as the LLM selection names it: {@code service|hoststub|model}. */
        public String key() {
            return this.service + "|" + this.hoststub + "|" + this.model;
        }

        /** The model as the LLM tier would call it (the row's endpoint, key, output cap and window). */
        public LLM.LLMModel llmModel() {
            final LLM.LLMType type;
            try {
                type = LLM.LLMType.valueOf(this.service);
            } catch (final IllegalArgumentException e) {
                return null;
            }
            final LLM.LLMModel m = new LLM.LLMModel(new LLM(this.hoststub, this.apiKey, this.maxTokens, this.numCtx, type), this.model,
                    false, "supported".equals(this.thinkingCapability));
            m.formatCapability = this.formatCapability;
            m.thinkingCapability = this.thinkingCapability;
            return m;
        }

        /** The endpoint without user information, query and fragment. */
        public String endpoint() {
            return endpoint(this.hoststub);
        }

        static String endpoint(final String hoststub) {
            try {
                final URI u = new URI(hoststub);
                if (u.getHost() == null) {
                    return "(invalid)";
                }
                return u.getScheme() + "://" + u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "") + (u.getRawPath() == null ? "" : u.getRawPath());
            } catch (final Exception e) {
                return "(invalid)";
            }
        }

        /** What the API shows of the candidate: no key, no user information of the endpoint. */
        public JSONObject json() {
            return KgJson.obj("key", key(), "service", this.service, "endpoint", endpoint(), "model", this.model, "maxTokens", this.maxTokens,
                    "numCtx", this.numCtx == 0 ? null : this.numCtx, "numCtxConfigured", this.numCtx > 0, "formatCapability",
                    this.formatCapability, "thinkingCapability", this.thinkingCapability, "activeForKnowledge", this.active,
                    "apiKeySet", !this.apiKey.isEmpty());
        }

        String secret() {
            return this.apiKey;
        }
    }

    // ------------------------------------------------------------ price

    /** A price per million tokens, its currency and where it comes from (the provider's model list, or entered). */
    public static final class Price {
        public final BigDecimal inputPerMillion;
        public final BigDecimal outputPerMillion;
        public final String currency;
        public final String source;

        public Price(final BigDecimal inputPerMillion, final BigDecimal outputPerMillion, final String currency, final String source) {
            this.inputPerMillion = inputPerMillion;
            this.outputPerMillion = outputPerMillion;
            this.currency = currency;
            this.source = source;
        }

        /** The price of a request (tokens of the prompt and of the answer). */
        BigDecimal of(final long promptTokens, final long answerTokens) {
            return this.inputPerMillion.multiply(BigDecimal.valueOf(promptTokens)).add(this.outputPerMillion.multiply(BigDecimal.valueOf(answerTokens)))
                    .divide(MILLION, 8, RoundingMode.UP);
        }

        JSONObject json() {
            return KgJson.obj("inputPerMillion", this.inputPerMillion.toPlainString(), "outputPerMillion", this.outputPerMillion.toPlainString(),
                    "currency", this.currency, "source", this.source);
        }

        private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
        private static final List<String> SOURCES = List.of("provider_list", "entered");

        /** A price from the API's fields, or null if none is given; refused if a field is invalid. */
        public static Price parse(final String input, final String output, final String currency, final String source) throws Refused {
            if (blank(input) && blank(output)) {
                return null;
            }
            final BigDecimal in = decimal("inputPrice", input), out = decimal("outputPrice", output);
            final String cur = blank(currency) ? "USD" : currency.trim();
            if (!CURRENCY.matcher(cur).matches()) {
                throw new Refused(400, "invalid_currency", "The currency is a three-letter code, e.g. USD or EUR.");
            }
            final String src = blank(source) ? "entered" : source.trim();
            if (!SOURCES.contains(src)) {
                throw new Refused(400, "invalid_price_source", "The price source is provider_list or entered.");
            }
            return new Price(in, out, cur, src);
        }

        private static BigDecimal decimal(final String field, final String value) throws Refused {
            try {
                final BigDecimal d = new BigDecimal(value.trim());
                if (d.signum() < 0 || d.compareTo(BigDecimal.valueOf(1_000_000L)) > 0) {
                    throw new NumberFormatException();
                }
                return d;
            } catch (final RuntimeException e) {
                throw new Refused(400, "invalid_" + field, field + " is a price per million tokens from 0 to 1000000.");
            }
        }
    }

    static boolean blank(final String s) {
        return s == null || s.trim().isEmpty();
    }

    // ------------------------------------------------------------ environment

    /** What the check reads: the graph's settings and the active prompt of the knowledge usage. */
    public static final class Inputs {
        final Function<String, String> settings;
        final KnowledgePrompt prompt;

        public Inputs(final Function<String, String> settings, final KnowledgePrompt prompt) {
            this.settings = settings;
            this.prompt = prompt;
        }
    }

    /** Where the check gets its candidates, inputs and clients from, and where it keeps its results. */
    public interface Environment {
        List<Candidate> candidates();

        /** The graph's settings and active prompt; null if the graph does not run. */
        Inputs inputs();

        /** A client of the candidate that reports each answer's usage to {@code usage}. */
        default LlmClient client(final Candidate c, final Consumer<JSONObject> usage) {
            final LLM.LLMModel m = c.llmModel();
            return new YacyLlmClient(() -> m, usage);
        }

        File dir();

        default long now() {
            return System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ the settings of a reading

    /**
     * The settings the reference pages are read with: the graph's settings for the LLM tier (input size, statement limit,
     * timeout, structured output, answer reserve), with the pages in their own collection that the LLM tier follows and
     * whose job postings it reads. The graph's collections are not used.
     */
    static KgConfig settings(final Function<String, String> graph) {
        final String jobs = KgConfig.JOBS_EXTRACT_PREFIX + QualityCases.COLLECTION;
        return KgConfig.read(key -> {
            if (KgConfig.COLLECTIONS.equals(key) || KgConfig.LLM_COLLECTIONS.equals(key)) {
                return QualityCases.COLLECTION;
            }
            if (jobs.equals(key)) {
                return "true";
            }
            if (key.endsWith("." + QualityCases.COLLECTION)) {
                return null;
            }
            return graph == null ? null : graph.apply(key);
        }, List.of(jobs));
    }

    /** The reference page as the reading sees a crawled page. */
    static SolrDoc doc(final Case c) {
        final Map<String, Object> f = new HashMap<>();
        f.put(SolrDoc.ID, "quality" + Integer.toHexString(c.id.hashCode()));
        f.put(SolrDoc.SKU, c.url);
        f.put(SolrDoc.TITLE, List.of(c.title));
        f.put(SolrDoc.LANGUAGE, c.language);
        f.put(SolrDoc.COLLECTIONS, List.of(QualityCases.COLLECTION));
        return SolrDoc.of(f);
    }

    // ------------------------------------------------------------ versions and staleness

    static String sha(final String s) {
        try {
            final byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static final String SCHEMA_HASH = sha(LlmExtractor.SCHEMA.toString());
    static final String VALIDATOR = LlmExtractor.NAME + "/" + LlmExtractor.VERSION + "/" + sha(String.join(",", LlmExtractor.INVALID_REASONS));
    static final String RULES = "jsonld/" + JsonLdExtractor.VERSION + " metadata/" + MetadataExtractor.VERSION + " rules/" + RuleExtractor.VERSION
            + " vocabulary/" + Vocabulary.VERSION;

    /**
     * What a result depends on: the candidate's row, the prompt, the schema, the validator, the rules and the settings of
     * the reading, the test set and the evaluation. Another value of any of them makes a result stale.
     */
    static JSONObject fingerprint(final Candidate c, final KnowledgePrompt prompt, final KgConfig cfg) {
        final JSONObject o = new JSONObject(true);
        KgJson.put(o, "service", c.service);
        KgJson.put(o, "endpoint", sha(c.hoststub));
        KgJson.put(o, "model", c.model);
        KgJson.put(o, "maxTokens", c.maxTokens);
        KgJson.put(o, "numCtx", c.numCtx);
        KgJson.put(o, "formatCapability", c.formatCapability);
        KgJson.put(o, "thinkingCapability", c.thinkingCapability);
        KgJson.put(o, "prompt", prompt.hash);
        KgJson.put(o, "schema", SCHEMA_HASH);
        KgJson.put(o, "validator", VALIDATOR);
        KgJson.put(o, "rules", RULES);
        KgJson.put(o, "structuredOutput", cfg.llmStructuredOutput);
        KgJson.put(o, "answerReserveTokens", cfg.llmAnswerReserveTokens);
        KgJson.put(o, "maxInputChars", cfg.extractMaxInputChars);
        KgJson.put(o, "maxStatementsPerDoc", cfg.extractMaxStatementsPerDoc);
        KgJson.put(o, "timeoutMillis", cfg.llmTimeoutMillis);
        KgJson.put(o, "testset", QualityCases.ID + "/" + QualityCases.VERSION);
        KgJson.put(o, "evaluator", QualityEvaluator.VERSION);
        return o;
    }

    /** The fields of a stored fingerprint that differ from the current one (empty: the result is current). */
    static JSONArray changed(final JSONObject stored, final JSONObject now) {
        final JSONArray out = new JSONArray();
        if (stored == null) {
            return out.put("fingerprint");
        }
        for (final String k : now.keySet()) {
            if (!String.valueOf(now.opt(k)).equals(String.valueOf(stored.opt(k)))) {
                out.put(k);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ the plan of a run

    /** One request of the plan: its case, its estimate and its worst case. */
    static final class Request {
        final Case c;
        final RequestBudget.Part part;
        final int estimatedPromptTokens;
        final int worstPromptTokens;

        Request(final Case c, final RequestBudget.Part part, final int estimated, final int worst) {
            this.c = c;
            this.part = part;
            this.estimatedPromptTokens = estimated;
            this.worstPromptTokens = worst;
        }
    }

    /** The plan: the pages, their requests, what is not asked and why, and the bounds. */
    static final class Plan {
        final Candidate candidate;
        final Inputs inputs;
        final KgConfig cfg;
        final LlmClient.Window window;
        final Map<String, LlmReading.Page> pages = new LinkedHashMap<>();
        final Map<String, List<Request>> requests = new LinkedHashMap<>();
        final Map<String, String> notAsked = new LinkedHashMap<>();
        int calls;
        long estimatedPromptTokens;
        long worstPromptTokens;

        Plan(final Candidate candidate, final Inputs inputs, final KgConfig cfg, final LlmClient.Window window) {
            this.candidate = candidate;
            this.inputs = inputs;
            this.cfg = cfg;
            this.window = window;
        }

        /** Every request may be sent twice: once more without the schema after an endpoint rejected it (HTTP 400). */
        int worstCalls() {
            return 2 * this.calls;
        }

        long worstTokens() {
            return 2L * (this.worstPromptTokens + (long) this.calls * this.candidate.maxTokens);
        }

        BigDecimal worstCost(final Price p) {
            return p == null ? null : p.of(2L * this.worstPromptTokens, 2L * this.calls * this.candidate.maxTokens);
        }

        JSONObject json(final Price price, final int callCap, final long tokenCap, final BigDecimal costCap) {
            final JSONArray cases = new JSONArray();
            for (final Case c : QualityCases.CASES) {
                final List<Request> r = this.requests.get(c.id);
                cases.put(KgJson.obj("case", c.id, "requests", r == null ? 0 : r.size(), "notAsked", this.notAsked.get(c.id)));
            }
            final BigDecimal worst = worstCost(price);
            return KgJson.obj("candidate", this.candidate.json(),
                    "scope", KgJson.obj("testset", QualityCases.ID, "version", QualityCases.VERSION, "cases", QualityCases.CASES.size(),
                            "usage", "knowledge", "requests", this.calls, "perCase", cases),
                    "window", this.window == null ? KgJson.obj("tokens", RequestBudget.DEFAULT_WINDOW, "source", LlmClient.Window.ASSUMED)
                            : KgJson.obj("tokens", this.window.tokens, "source", this.window.source),
                    "calls", KgJson.obj("planned", this.calls, "worstCase", worstCalls(), "cap", callCap, "max", MAX_CALLS),
                    "tokens", KgJson.obj("promptEstimated", this.estimatedPromptTokens, "promptWorstCase", 2L * this.worstPromptTokens,
                            "answerWorstCase", 2L * this.calls * this.candidate.maxTokens, "worstCase", worstTokens(), "cap", tokenCap),
                    "cost", price == null
                            ? KgJson.obj("known", false, "worstCase", null, "cap", null,
                                    "note", "no price: only the caps of calls and tokens apply; the cost is unknown")
                            : KgJson.obj("known", true, "price", price.json(), "currency", price.currency,
                                    "worstCase", worst.setScale(4, RoundingMode.UP).toPlainString(),
                                    "cap", costCap == null ? null : costCap.toPlainString()));
        }
    }

    private Plan plan(final Candidate c, final Inputs in, final LlmClient client) throws Refused {
        final KgConfig cfg = settings(in.settings);
        if (!cfg.valid()) {
            throw new Refused(409, "settings_invalid", "The knowledge graph's settings are invalid: " + cfg.problems());
        }
        final LlmClient.Window w = client.window();
        final RequestBudget budget = LlmReading.budget(cfg, in.prompt, w);
        final Plan p = new Plan(c, in, cfg, w);
        final int schemaChars = LlmExtractor.SCHEMA.toString().length();
        for (final Case qc : QualityCases.CASES) {
            final LlmReading.Page page;
            try {
                page = LlmReading.page(cfg, doc(qc), qc.text);
            } catch (final IOException e) {
                p.notAsked.put(qc.id, "page_unreadable");
                continue;
            }
            p.pages.put(qc.id, page);
            if (!page.candidate) {
                p.notAsked.put(qc.id, "not_candidate"); // the queue would skip the page: a fault of the test set
                continue;
            }
            final RequestBudget.Plan rp = page.reading.plan(budget, page.chunks);
            if (rp.refused) {
                p.notAsked.put(qc.id, "context_budget"); // the request does not fit the window: never sent
                continue;
            }
            final List<Request> list = new ArrayList<>();
            for (final RequestBudget.Part part : rp.parts) {
                final int chars = in.prompt.text.length() + page.reading.userPrompt(part).length() + schemaChars;
                final int worst = (int) Math.ceil(chars / WORST_CHARS_PER_TOKEN) + FRAME_TOKENS;
                list.add(new Request(qc, part, part.promptTokens, worst));
                p.calls++;
                p.estimatedPromptTokens += part.promptTokens;
                p.worstPromptTokens += worst;
            }
            p.requests.put(qc.id, list);
        }
        return p;
    }

    // ------------------------------------------------------------ the run

    /** One transport: what was reserved for it and what the endpoint reported. */
    static final class Transport {
        final long reservedTokens;
        final BigDecimal reservedCost;
        final int worstPrompt;
        JSONObject usage;

        Transport(final long reservedTokens, final BigDecimal reservedCost, final int worstPrompt) {
            this.reservedTokens = reservedTokens;
            this.reservedCost = reservedCost;
            this.worstPrompt = worstPrompt;
        }
    }

    /** A run: its plan, caps, counters and per-case results. */
    final class Run {
        final Plan plan;
        final Price price;
        final int callCap;
        final long tokenCap;
        final BigDecimal costCap;
        final long startedAt;
        final JSONObject fingerprint;
        volatile String state = RUNNING;
        volatile boolean cancel;
        volatile String stopReason;
        volatile String caseId;
        volatile int caseIndex;
        volatile long finishedAt;
        // counters of the run (written by its thread only; read by the status)
        volatile int calls;
        volatile long committedTokens;
        volatile BigDecimal committedCost = BigDecimal.ZERO;
        volatile long promptTokens, answerTokens, estimatedTokens;
        final Map<String, Integer> servedModels = new LinkedHashMap<>();
        final Map<String, Integer> servedProviders = new LinkedHashMap<>();
        volatile int unreported;
        final List<JSONObject> technical = new ArrayList<>();
        final List<JSONObject> functional = new ArrayList<>();
        /** The transport the usage report of the client belongs to. */
        Transport current;

        Run(final Plan plan, final Price price, final int callCap, final long tokenCap, final BigDecimal costCap, final long now) {
            this.plan = plan;
            this.price = price;
            this.callCap = callCap;
            this.tokenCap = tokenCap;
            this.costCap = costCap;
            this.startedAt = now;
            this.fingerprint = fingerprint(plan.candidate, plan.inputs.prompt, plan.cfg);
        }

        JSONObject json() {
            return KgJson.obj("state", this.state, "candidate", this.plan.candidate.json(), "startedAt", this.startedAt,
                    "finishedAt", this.finishedAt == 0L ? null : this.finishedAt, "case", this.caseId, "caseIndex", this.caseIndex,
                    "cases", QualityCases.CASES.size(), "stopReason", this.stopReason,
                    "calls", KgJson.obj("made", this.calls, "cap", this.callCap),
                    "tokens", KgJson.obj("prompt", this.promptTokens, "answer", this.answerTokens, "estimated", this.estimatedTokens,
                            "committed", this.committedTokens, "cap", this.tokenCap),
                    "cost", this.price == null ? KgJson.obj("known", false)
                            : KgJson.obj("known", true, "currency", this.price.currency, "spent",
                                    this.committedCost.setScale(4, RoundingMode.UP).toPlainString(), "cap", this.costCap.toPlainString()));
        }
    }

    private final Environment env;
    private final QualityResults results;
    private volatile Run run;
    private Thread thread;

    public ModelQualityCheck(final Environment env) {
        this.env = env;
        this.results = new QualityResults(env.dir());
    }

    private Candidate candidate(final String key) throws Refused {
        if (blank(key)) {
            throw new Refused(400, "candidate_missing", "Name the candidate: service|hoststub|model of a production model row.");
        }
        for (final Candidate c : this.env.candidates()) {
            if (c.key().equals(key.trim())) {
                if (c.llmModel() == null) {
                    throw new Refused(409, "candidate_invalid", "The row's service is unknown.");
                }
                return c;
            }
        }
        throw new Refused(404, "candidate_unknown", "No production model row has this service, endpoint and model; deploy the model first.");
    }

    private Inputs inputs() throws Refused {
        final Inputs in = this.env.inputs();
        if (in == null || in.prompt == null) {
            throw new Refused(409, "kg_not_running", "The knowledge graph does not run: its active prompt and settings are not available.");
        }
        return in;
    }

    /** Scope and maximum cost of a check of this candidate, before anything is sent (no model call). */
    public JSONObject plan(final String key, final Price price, final Integer maxCalls, final BigDecimal maxCost) throws Refused {
        final Candidate c = candidate(key);
        final Inputs in = inputs();
        final Plan p = plan(c, in, this.env.client(c, null));
        final int callCap = maxCalls == null ? Math.min(MAX_CALLS, p.worstCalls()) : maxCalls;
        final BigDecimal worst = p.worstCost(price);
        final JSONObject o = p.json(price, callCap, p.worstTokens(), maxCost != null ? maxCost : worst);
        KgJson.put(o, "startable", p.calls > 0 && p.calls <= MAX_CALLS);
        KgJson.put(o, "limits", limits());
        return o;
    }

    /** What the result of a check means and does not mean (shown with every plan and result). */
    static JSONObject limits() {
        return KgJson.obj("usage", "knowledge",
                "validFor", "the knowledge graph's extraction (usage knowledge) with this row, prompt, schema, validator and test set",
                "notValidFor", "log-report, chat and every other usage; other pages than the reference pages",
                "passMeans", "every reference page was read and no deviation was found within the stated scope; it is no guarantee for other pages",
                "rated", "only what the LLM tier adds; statements of tiers 1 and 2 are listed as given by the rules",
                "noScore", true);
    }

    /**
     * Starts a check of the candidate (one at a time). Refused without an explicit acceptance of an unknown cost, with a
     * cap of calls below the planned requests or above {@link #MAX_CALLS}, or a cost cap that is not positive.
     */
    public synchronized JSONObject start(final String key, final Price price, final boolean acceptUnknownCost, final Integer maxCalls,
            final BigDecimal maxCost) throws Refused {
        final Run last = this.run;
        if (last != null && (RUNNING.equals(last.state) || CANCELLING.equals(last.state))) {
            throw new Refused(409, "running", "A check runs already; wait for it or cancel it.");
        }
        final Candidate c = candidate(key);
        final Inputs in = inputs();
        if (price == null && !acceptUnknownCost) {
            throw new Refused(400, "cost_unknown", "No price is given: enter one, or accept explicitly that the cost is unknown.");
        }
        final Run[] holder = new Run[1];
        final LlmClient client = this.env.client(c, u -> {
            final Run r = holder[0];
            if (r != null && r.current != null) {
                r.current.usage = u;
            }
        });
        final Plan p = plan(c, in, client);
        if (p.calls == 0) {
            throw new Refused(409, "nothing_to_ask", "No reference page can be asked with this model's window and settings.");
        }
        final int callCap = maxCalls == null ? Math.min(MAX_CALLS, p.worstCalls()) : maxCalls;
        if (callCap < p.calls || callCap > MAX_CALLS) {
            throw new Refused(400, "invalid_maxCalls", "maxCalls is at least the " + p.calls + " planned requests and at most " + MAX_CALLS + ".");
        }
        final BigDecimal costCap = price == null ? null : maxCost != null ? maxCost : p.worstCost(price);
        if (price != null && costCap.signum() <= 0 && p.worstCost(price).signum() > 0) {
            throw new Refused(400, "invalid_maxCost", "maxCost is above 0.");
        }
        final Run r = new Run(p, price, callCap, p.worstTokens(), costCap, this.env.now());
        holder[0] = r;
        this.run = r;
        this.thread = new Thread(() -> execute(r, client), THREAD);
        this.thread.setDaemon(true);
        this.thread.start();
        return status();
    }

    /** Cancels the running check: no further request is sent; one already sent is answered or times out. */
    public synchronized JSONObject cancel() throws Refused {
        final Run r = this.run;
        if (r == null || !(RUNNING.equals(r.state) || CANCELLING.equals(r.state))) {
            throw new Refused(409, "not_running", "No check runs.");
        }
        r.cancel = true;
        r.state = CANCELLING;
        return status();
    }

    /** Waits until no check runs (tests and shutdown). */
    public void await(final long millis) throws InterruptedException {
        final Thread t = this.thread;
        if (t != null) {
            t.join(millis);
        }
    }

    // ------------------------------------------------------------ execution

    private LlmClient.RequestGate gate(final Run r, final Request q, final int[] caseCalls) {
        return () -> {
            if (r.cancel) {
                throw new LlmClient.Deferred(CANCEL);
            }
            if (r.calls + 1 > r.callCap) {
                throw new LlmClient.Deferred(CALL_CAP);
            }
            final long tokens = q.worstPromptTokens + (long) r.plan.candidate.maxTokens;
            if (r.committedTokens + tokens > r.tokenCap) {
                throw new LlmClient.Deferred(TOKEN_CAP);
            }
            final BigDecimal cost = r.price == null ? BigDecimal.ZERO : r.price.of(q.worstPromptTokens, r.plan.candidate.maxTokens);
            if (r.price != null && r.committedCost.add(cost).compareTo(r.costCap) > 0) {
                throw new LlmClient.Deferred(COST_CAP);
            }
            // reserved at its worst case until the answer says what it cost
            r.calls++;
            caseCalls[0]++;
            r.committedTokens += tokens;
            r.committedCost = r.committedCost.add(cost);
            r.current = new Transport(tokens, cost, q.worstPromptTokens);
            transports(r).add(r.current);
            return () -> {
            };
        };
    }

    private final Map<Run, List<Transport>> transports = new HashMap<>();

    private synchronized List<Transport> transports(final Run r) {
        return this.transports.computeIfAbsent(r, k -> new ArrayList<>());
    }

    /**
     * Replaces the reservation of the request's transports by what they cost: the tokens the endpoint reported, else the
     * prompt's worst case and, for an answer, the output cap (counted as estimated).
     */
    private void settle(final Run r, final List<Transport> sent, final boolean answered, final JSONObject tech) {
        long prompt = 0, answer = 0, estimated = 0;
        int truncated = 0;
        for (int i = 0; i < sent.size(); i++) {
            final Transport t = sent.get(i);
            final boolean last = i == sent.size() - 1;
            long p, a;
            if (t.usage != null && t.usage.has("promptTokens")) {
                p = t.usage.optLong("promptTokens");
                a = t.usage.optLong("answerTokens", last && answered ? r.plan.candidate.maxTokens : 0);
                if (!t.usage.has("answerTokens") && last && answered) {
                    estimated += a;
                }
                prompt += p;
                answer += a;
            } else {
                // not reported: the worst case of the prompt and, for the answer, the output cap
                p = t.worstPrompt;
                a = last && answered ? r.plan.candidate.maxTokens : 0;
                estimated += p + a;
                r.unreported++;
            }
            if (t.usage != null && "length".equals(t.usage.optString("stop"))) {
                truncated++;
            }
            if (t.usage != null) {
                count(r.servedModels, t.usage.optString("servedModel", null));
                count(r.servedProviders, t.usage.optString("servedProvider", null));
            }
            r.committedTokens += p + a - t.reservedTokens;
            if (r.price != null) {
                r.committedCost = r.committedCost.add(r.price.of(p, a)).subtract(t.reservedCost);
            }
        }
        r.promptTokens += prompt;
        r.answerTokens += answer;
        r.estimatedTokens += estimated;
        final JSONObject tokens = tech.optJSONObject("tokens");
        KgJson.put(tokens, "prompt", tokens.optLong("prompt") + prompt);
        KgJson.put(tokens, "answer", tokens.optLong("answer") + answer);
        KgJson.put(tokens, "estimated", tokens.optLong("estimated") + estimated);
        KgJson.put(tech, "truncated", tech.optInt("truncated") + truncated);
    }

    private static void count(final Map<String, Integer> m, final String key) {
        if (key != null && !key.isEmpty() && (m.containsKey(key) || m.size() < 10)) {
            m.merge(key.length() > 120 ? key.substring(0, 120) : key, 1, Integer::sum);
        }
    }

    /** A provider's error without keys, bounded. */
    static String clean(final String message, final String secret) {
        String m = message == null ? "" : message;
        if (secret != null && secret.length() >= 4) {
            m = m.replace(secret, "[key]");
        }
        m = m.replaceAll("(?i)bearer\\s+[A-Za-z0-9._~+/=-]+", "Bearer [key]").replaceAll("\\b(?:sk|pk|rk)-[A-Za-z0-9_-]{8,}", "[key]");
        m = LogRedaction.redact(m).replaceAll("\\s+", " ").trim();
        return m.length() > MAX_ERROR_CHARS ? m.substring(0, MAX_ERROR_CHARS) + "…" : m;
    }

    private void execute(final Run r, final LlmClient client) {
        final Plan p = r.plan;
        final KnowledgePrompt prompt = p.inputs.prompt;
        String stop = null;
        try {
            for (int i = 0; i < QualityCases.CASES.size(); i++) {
                final Case c = QualityCases.CASES.get(i);
                r.caseIndex = i + 1;
                r.caseId = c.id;
                final long t0 = this.env.now();
                final JSONObject tech = KgJson.obj("case", c.id, "requests", 0, "calls", 0, "retries", 0,
                        "tokens", KgJson.obj("prompt", 0L, "answer", 0L, "estimated", 0L), "truncated", 0);
                r.technical.add(tech);
                if (stop != null) {
                    KgJson.put(tech, "notAsked", stop);
                    continue;
                }
                final String not = p.notAsked.get(c.id);
                if (not != null) {
                    KgJson.put(tech, "notAsked", not);
                    continue;
                }
                final LlmReading.Page page = p.pages.get(c.id);
                final List<Request> requests = p.requests.get(c.id);
                KgJson.put(tech, "requests", requests.size());
                final List<JSONObject> accepted = new ArrayList<>();
                final List<List<LlmExtractor.Known>> acceptedKnown = new ArrayList<>();
                final List<QualityEvaluator.Answer> answers = new ArrayList<>();
                final JSONArray refused = new JSONArray();
                final Map<String, Integer> drops = new LinkedHashMap<>();
                final int[] caseCalls = {0};
                boolean failed = false;
                for (final Request q : requests) {
                    final int before = transports(r).size();
                    String answer = null;
                    IOException error = null;
                    try {
                        answer = page.reading.ask(client, p.cfg, prompt, q.part, gate(r, q, caseCalls));
                    } catch (final IOException e) {
                        error = e;
                    }
                    final List<Transport> all = transports(r);
                    settle(r, new ArrayList<>(all.subList(before, all.size())), answer != null, tech);
                    r.current = null;
                    if (error instanceof LlmClient.Deferred) {
                        stop = ((LlmClient.Deferred) error).reason;
                        KgJson.put(tech, "notAsked", stop);
                        failed = true;
                        break;
                    }
                    if (error != null) {
                        KgJson.put(tech, "error", KgJson.obj("code", "api_error", "message", clean(error.getMessage(), p.candidate.secret())));
                        failed = true;
                        break;
                    }
                    final LlmExtractor.Result res = page.reading.check(answer, q.part);
                    answers.add(new QualityEvaluator.Answer(answer, res, q.part.known));
                    if (res.refused != null) {
                        refused.put(res.refused);
                        continue;
                    }
                    for (final Map.Entry<String, Integer> e : res.droppedInvalidByReason.entrySet()) {
                        drops.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                    if (res.droppedUngrounded > 0) {
                        drops.merge("ungrounded", res.droppedUngrounded, Integer::sum);
                    }
                    accepted.add(res.accepted);
                    acceptedKnown.add(q.part.known);
                }
                KgJson.put(tech, "calls", caseCalls[0]);
                KgJson.put(tech, "retries", Math.max(0, caseCalls[0] - requests.size()));
                KgJson.put(tech, "durationMillis", this.env.now() - t0);
                KgJson.put(tech, "refusedAnswers", refused);
                KgJson.put(tech, "validatorDrops", new JSONObject(drops));
                if (failed) {
                    continue;
                }
                final Extraction ex = page.extraction(p.cfg, accepted, acceptedKnown);
                r.functional.add(QualityEvaluator.evaluate(c, page.base, ex, answers));
            }
        } catch (final RuntimeException e) {
            // a fault of the check itself: logged by its class and place only (no page, answer or key)
            LOG.warn("quality check stopped: " + e.getClass().getName() + " at " + (e.getStackTrace().length == 0 ? "?" : e.getStackTrace()[0]));
            stop = "internal_error";
        } finally {
            r.stopReason = stop;
            r.finishedAt = this.env.now();
            r.caseId = null;
            synchronized (this) {
                this.transports.remove(r);
            }
            try {
                this.results.save(r.plan.candidate.key(), result(r));
            } catch (final IOException e) {
                r.stopReason = r.stopReason == null ? "result_not_stored" : r.stopReason;
            }
            r.state = CANCEL.equals(stop) ? CANCELLED : stop != null ? STOPPED : FINISHED;
        }
    }

    /** The status of a finished run's result: technically failed, with deviations, or passed within its scope. */
    static String status(final List<JSONObject> technical, final List<JSONObject> functional, final String stop) {
        if (stop != null || functional.size() < QualityCases.CASES.size()) {
            return FAILED;
        }
        for (final JSONObject t : technical) {
            if (t.has("notAsked") || t.has("error")) {
                return FAILED;
            }
        }
        for (final JSONObject t : technical) {
            final JSONArray refused = t.optJSONArray("refusedAnswers");
            if (refused != null && refused.length() > 0) {
                return DEVIATIONS;
            }
        }
        for (final JSONObject f : functional) {
            if (QualityEvaluator.deviates(f)) {
                return DEVIATIONS;
            }
        }
        return PASSED;
    }

    private JSONObject result(final Run r) {
        final Plan p = r.plan;
        final String status = status(r.technical, r.functional, r.stopReason);
        int expected = 0, byRules = 0, forbidden = 0, correct = 0, missing = 0, wrong = 0, wrongType = 0, wrongRelation = 0, evidence = 0;
        for (final JSONObject f : r.functional) {
            final JSONObject n = f.optJSONObject("counts");
            expected += n.optInt("expected");
            byRules += n.optInt("givenByRules");
            correct += n.optInt("correct");
            missing += n.optInt("missing");
            wrong += n.optInt("forbidden");
            wrongType += n.optInt("wrongType");
            wrongRelation += n.optInt("wrongRelation");
            evidence += n.optInt("evidence");
        }
        for (final Case c : QualityCases.CASES) {
            forbidden += c.forbidden.size();
        }
        final JSONArray technical = new JSONArray();
        int apiErrors = 0, refusedAnswers = 0, truncated = 0, drops = 0, retries = 0;
        for (final JSONObject t : r.technical) {
            technical.put(t);
            apiErrors += t.has("error") ? 1 : 0;
            refusedAnswers += t.optJSONArray("refusedAnswers") == null ? 0 : t.optJSONArray("refusedAnswers").length();
            truncated += t.optInt("truncated");
            retries += t.optInt("retries");
            final JSONObject d = t.optJSONObject("validatorDrops");
            if (d != null) {
                for (final String k : d.keySet()) {
                    drops += d.optInt(k);
                }
            }
        }
        final KnowledgePrompt prompt = p.inputs.prompt;
        return KgJson.obj("status", status, "startedAt", r.startedAt, "finishedAt", r.finishedAt, "stopReason", r.stopReason,
                "scope", KgJson.obj("testset", QualityCases.ID, "version", QualityCases.VERSION, "cases", QualityCases.CASES.size(),
                        "casesEvaluated", r.functional.size(), "expectedOfModel", expected, "givenByRules", byRules,
                        "forbiddenChecked", forbidden, "usage", "knowledge"),
                "limits", limits(),
                "functional", KgJson.obj("correct", correct, "missing", missing, "forbidden", wrong, "wrongType", wrongType,
                        "wrongRelation", wrongRelation, "evidence", evidence, "cases", new JSONArray(r.functional)),
                "technical", KgJson.obj("apiErrors", apiErrors, "refusedAnswers", refusedAnswers, "validatorDrops", drops,
                        "truncated", truncated, "calls", r.calls, "retries", retries, "callCap", r.callCap,
                        "durationMillis", r.finishedAt - r.startedAt,
                        "tokens", KgJson.obj("prompt", r.promptTokens, "answer", r.answerTokens, "estimated", r.estimatedTokens,
                                "unreportedCalls", r.unreported, "cap", r.tokenCap),
                        "cost", r.price == null ? KgJson.obj("known", false, "note", "no price was given; the cost is unknown")
                                : KgJson.obj("known", true, "currency", r.price.currency, "price", r.price.json(),
                                        "amount", r.committedCost.setScale(4, RoundingMode.UP).toPlainString(),
                                        "estimated", r.estimatedTokens > 0, "cap", r.costCap.toPlainString()),
                        "cases", technical),
                "provenance", KgJson.obj("candidate", p.candidate.json(),
                        "served", KgJson.obj("models", new JSONObject(r.servedModels), "providers", new JSONObject(r.servedProviders),
                                "unreportedCalls", r.unreported),
                        "parameters", KgJson.obj("temperature", 0.1, "maxTokens", p.candidate.maxTokens,
                                "window", p.window == null ? null : KgJson.obj("tokens", p.window.tokens, "source", p.window.source),
                                "structuredOutput", p.cfg.llmStructuredOutput, "timeoutMillis", p.cfg.llmTimeoutMillis,
                                "answerReserveTokens", p.cfg.llmAnswerReserveTokens, "maxInputChars", p.cfg.extractMaxInputChars),
                        "prompt", KgJson.obj("hash", prompt.hash, "source", prompt.source, "revision", prompt.revision),
                        "schema", SCHEMA_HASH, "validator", VALIDATOR, "rules", RULES,
                        "testset", QualityCases.ID + "/" + QualityCases.VERSION, "evaluator", QualityEvaluator.VERSION),
                "fingerprint", r.fingerprint);
    }

    // ------------------------------------------------------------ status and results

    /** A stored result with whether it is stale (and why) against the current row, prompt and versions; null if none. */
    private JSONObject withStaleness(final Candidate c, final JSONObject stored, final Inputs in) {
        if (stored == null) {
            return null;
        }
        if (in == null || in.prompt == null) {
            KgJson.put(stored, "stale", null);
            KgJson.put(stored, "staleReasons", new JSONArray().put("kg_not_running"));
            return stored;
        }
        final JSONArray changed = changed(stored.optJSONObject("fingerprint"), fingerprint(c, in.prompt, settings(in.settings)));
        KgJson.put(stored, "stale", changed.length() > 0);
        KgJson.put(stored, "staleReasons", changed);
        return stored;
    }

    /** The test set, every candidate with the state of its result, and the run. */
    public JSONObject status() {
        final Inputs in = this.env.inputs();
        final JSONArray candidates = new JSONArray();
        for (final Candidate c : this.env.candidates()) {
            final JSONObject stored = withStaleness(c, this.results.load(c.key()), in);
            final JSONObject o = c.json();
            if (stored == null) {
                KgJson.put(o, "status", UNCHECKED);
            } else {
                KgJson.put(o, "status", stored.optString("status"));
                KgJson.put(o, "checkedAt", stored.opt("finishedAt"));
                KgJson.put(o, "stale", stored.opt("stale"));
                KgJson.put(o, "staleReasons", stored.opt("staleReasons"));
                KgJson.put(o, "scope", stored.opt("scope"));
                KgJson.put(o, "functional", summary(stored.optJSONObject("functional")));
            }
            candidates.put(o);
        }
        final Run r = this.run;
        return KgJson.obj("testset", KgJson.obj("id", QualityCases.ID, "version", QualityCases.VERSION, "evaluator", QualityEvaluator.VERSION,
                "cases", QualityCases.CASES.size()), "graphRunning", in != null && in.prompt != null, "maxCalls", MAX_CALLS,
                "candidates", candidates, "run", r == null ? KgJson.obj("state", IDLE) : r.json(), "limits", limits());
    }

    private static JSONObject summary(final JSONObject functional) {
        if (functional == null) {
            return null;
        }
        final JSONObject o = new JSONObject(true);
        for (final String k : List.of("correct", "missing", "forbidden", "wrongType", "wrongRelation", "evidence")) {
            KgJson.put(o, k, functional.optInt(k));
        }
        return o;
    }

    /** The stored result of a candidate with its staleness. */
    public JSONObject result(final String key) throws Refused {
        if (blank(key)) {
            throw new Refused(400, "candidate_missing", "Name the candidate: service|hoststub|model of a production model row.");
        }
        Candidate found = null;
        for (final Candidate c : this.env.candidates()) {
            if (c.key().equals(key.trim())) {
                found = c;
            }
        }
        final JSONObject stored = this.results.load(key.trim());
        if (stored == null) {
            return KgJson.obj("status", UNCHECKED, "candidate", found == null ? null : found.json());
        }
        if (found == null) {
            KgJson.put(stored, "stale", true);
            KgJson.put(stored, "staleReasons", new JSONArray().put("candidate_removed"));
            return stored;
        }
        return withStaleness(found, stored, this.env.inputs());
    }

    /** The test set with its pages and expectations. */
    public static JSONObject testset() {
        return QualityCases.json();
    }
}
