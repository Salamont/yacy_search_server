/**
 *  RAGProxyServlet
 *  Copyright 2024 by Michael Peter Christen
 *  First released 17.05.2024 at https://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 *
 *  This library is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program in the file lgpl21.txt
 *  If not, see <http://www.gnu.org/licenses/>.
 */

package net.yacy.http.servlets;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.solr.servlet.cache.Method;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.LLM;
import net.yacy.ai.PromptGuard;
import net.yacy.ai.RAGAugmentor;
import net.yacy.ai.ToolCallProtocol;
import net.yacy.ai.rag.RagCitations;
import net.yacy.ai.rag.RagContext;
import net.yacy.ai.rag.RagConversation;
import net.yacy.ai.rag.RagQuery;
import net.yacy.ai.rag.RagRetriever;
import net.yacy.ai.rag.RagSettings;
import net.yacy.cora.util.ConcurrentLog;
import net.yacy.cora.util.LogRedaction;
import net.yacy.http.ClientAddress;
import net.yacy.search.Switchboard;

/**
 * This class implements a Retrieval Augmented Generation ("RAG") proxy which
 * uses a YaCy search index to enrich a chat with search results.
 *
 * The endpoint has two operation modes (see LLMAdminProxyServlet for the concept):
 * without a "hoststub" request parameter it is the public, AIShield-regulated RAG chat
 * proxy implemented in this class, where the target LLM comes from the server-side
 * configuration. With a "hoststub" parameter the request is handed over to the
 * admin-only passthrough proxy which mirrors the given endpoint 1:1.
 *
 * AI Shield (see LLMAccess): a direct local connection passes; a remote client passes as
 * guest only with ai.shield.allow-nonlocalhost=true, otherwise only as authenticated YaCy
 * administrator from the same site. The client address comes from ClientAddress (forwarded
 * headers only from server.reverseProxy.trusted, a proxied request is never local).
 *
 * Prompt (see PromptGuard): the server puts its base system prompt first; client system
 * messages are only lower-priority preferences. Search results and attached texts are
 * untrusted data in a block with a random delimiter.
 *
 * You can test this using a curl command:
 curl -X POST "http://localhost:8090/v1/chat/completions"\
     -s -H "Content-Type: application/json"\
     -d '{
    "model": "llama3.2:1b", "temperature": 0.1, "max_tokens": 1024,
    "messages": [
      {"role": "system", "content": "You are a helpful assistant."},
      {"role": "user", "content": "Hello, how are you?"}
    ],
    "stream": false
 }'
 */
public class RAGProxyServlet extends HttpServlet {

    private static final long serialVersionUID = 3411544789759643137L;
    private static final int DIRECT_SEARCH_WORD_LIMIT = 40;

    public static final String LLM_SYSTEM_PROMPT_DEFAULT = "You are a smart and helpful chatbot. If possible, use friendly emojies.";
    static final String LLM_USER_PREFIX_DEFAULT = "\n\nSearch results (numbered sources, cite them as [n]):\n\n";
    private static final String LLM_QUERY_GENERATOR_PREFIX_DEFAULT = "Make a list of search words with low document frequency for the following prompt; use a JSON Array: ";

    // Volatile, in-memory access log for rate limiting. This is intentionally not persisted
    // to respect user privacy; entries older than 24h are purged on each access.
    public static final Deque<AbstractMap.SimpleEntry<Long, String>> ACCESS_LOG = new ConcurrentLinkedDeque<>();
    public static final long ONE_MINUTE_MS = 60_000L;
    public static final long ONE_HOUR_MS = 60 * ONE_MINUTE_MS;
    public static final long ONE_DAY_MS = 24 * ONE_HOUR_MS;

    @Override
    public void service(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        final String runId = UUID.randomUUID().toString();
        final long requestStart = System.currentTimeMillis();
        response.setContentType("application/json;charset=utf-8");

        HttpServletResponse hresponse = (HttpServletResponse) response;
        HttpServletRequest hrequest = (HttpServletRequest) request;

        // admin passthrough mode: a hoststub parameter selects a configured LLM endpoint
        // which is mirrored 1:1 (admin-only, no RAG augmentation, no AIShield rules)
        if (LLMAdminProxyServlet.tryHandle(hrequest, hresponse)) return;

        // Add CORS headers
        hresponse.setHeader("Access-Control-Allow-Origin", "*");
        hresponse.setHeader("Access-Control-Allow-Methods", "POST, GET, OPTIONS, DELETE");
        hresponse.setHeader("Access-Control-Allow-Headers", "Content-Type, Authorization");

        final Switchboard sb = Switchboard.getSwitchboard();
        if ("OPTIONS".equals(hrequest.getMethod())) {
            // CORS preflight: no data, no AI Shield decision needed
            hresponse.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        final ClientAddress client = LLMAccess.client(hrequest);
        final String clientIP = client.effective();
        final LLMAccess.Shield shield = LLMAccess.shield(sb.getConfigBool("ai.shield.allow-nonlocalhost", false), client,
                LLMAccess.crossSite(hrequest, client), LLMAccess.bearerToken(hrequest),
                () -> LLMAccess.admin(hrequest, hresponse, true));
        ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-request phase=start method=" + hrequest.getMethod() + " localhost=" + client.isLocal() + " " + client + " shield=" + shield);
        if (!shield.allowed()) {
            ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=reject reason=" + shield.name().toLowerCase(java.util.Locale.ROOT) + " durationMs=" + elapsed(requestStart));
            if (shield == LLMAccess.Shield.ADMIN_REQUIRED) {
                LLMAccess.error(hresponse, HttpServletResponse.SC_UNAUTHORIZED, LLMAccess.ADMIN_REQUIRED,
                        "YaCy administrator login required: the AI Shield admits remote clients only as administrator (guest access is off).");
            } else if (shield == LLMAccess.Shield.BLOCKED_CROSS_SITE) {
                LLMAccess.error(hresponse, HttpServletResponse.SC_FORBIDDEN, LLMAccess.AI_SHIELD_BLOCKED,
                        "Blocked by the AI Shield: requests from another site are not accepted.");
            } else {
                LLMAccess.error(hresponse, HttpServletResponse.SC_FORBIDDEN, LLMAccess.AI_SHIELD_BLOCKED,
                        "Blocked by the AI Shield: remote clients are admitted only as YaCy administrator, not with an agent token.");
            }
            return;
        }
        if (isRateLimited(sb, clientIP, shield.privileged)) {
            ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=reject reason=rate-limited localhost=" + client.isLocal() + " shield=" + shield + " durationMs=" + elapsed(requestStart));
            LLMAccess.error(hresponse, 429, LLMAccess.AI_SHIELD_RATE_LIMITED, "Blocked by the AI Shield: rate limit reached, please wait and try again.");
            return;
        }
        recordAccess(clientIP);

        final Method reqMethod = Method.getMethod(hrequest.getMethod());
        if (reqMethod == Method.OTHER) {
            // required to handle CORS
            hresponse.setStatus(HttpServletResponse.SC_OK);
            ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-request phase=end result=options durationMs=" + elapsed(requestStart));
            return;
        }

        // We expect a POST request
        if (reqMethod != Method.POST) {
            ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=reject reason=method-not-allowed method=" + hrequest.getMethod() + " durationMs=" + elapsed(requestStart));
            LLMAccess.error(hresponse, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "method_not_allowed", "Use POST.");
            return;
        }

        // get the output stream early to be able to generate messages to the user
        // before the actual retrieval starts
        ServletOutputStream out = response.getOutputStream();

        // read the body of the request and parse it as JSON
        BufferedReader reader = request.getReader();
        StringBuilder bodyBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            bodyBuilder.append(line);
        }
        String body = bodyBuilder.toString();
        ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-request phase=body-read bodyChars=" + body.length());
        JSONObject bodyObject;
        try {
            // get system message and user prompt
            bodyObject = new JSONObject(body);
            if (net.yacy.scoutro.api.SystemQuestionChat.handle(hrequest, hresponse, bodyObject)) {
                ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=system-question phase=end status=" + hresponse.getStatus());
                return;
            }
            // get chat functions
            String model = bodyObject.optString("model", LLM.LLMUsage.chat.name());
            //Double temperature = bodyObject.optDouble("temperature", 0.0);
            //int max_tokens = bodyObject.optInt("max_tokens", 1024);
            //boolean stream = bodyObject.optBoolean("stream", false);

            // resolve true model name from configuration
            LLM.LLMUsage usage = LLM.LLMUsage.chat;
            try {usage = LLM.LLMUsage.valueOf(model);} catch (IllegalArgumentException e) {}
            if (usage == LLM.LLMUsage.knowledge) usage = LLM.LLMUsage.chat; // the extraction model is never a chat model
            LLM.LLMModel llm4Chat = LLM.llmFromUsage(usage, runId, "rag-chat");
            LLM.LLMModel llm4tldr = LLM.llmFromUsage(LLM.LLMUsage.tldr, runId, "rag-query-generator");
            if (llm4Chat == null) {
                ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=reject reason=no-chat-model usage=" + usage + " durationMs=" + elapsed(requestStart));
                LLMAccess.error(hresponse, HttpServletResponse.SC_SERVICE_UNAVAILABLE, LLMAccess.NO_CHAT_MODEL,
                        "No chat model is configured: assign a model to the chat role in LLM Selection.");
                return;
            }
            bodyObject.put("model", llm4Chat.model); // replace the model with the decoded model name

            // RAG scope: an explicit collection of the request (validated), never mixed with other collections
            final Object requestedCollection = bodyObject.opt("collection");
            String collection = null;
            if (requestedCollection != null && requestedCollection != JSONObject.NULL && !String.valueOf(requestedCollection).trim().isEmpty()) {
                collection = String.valueOf(requestedCollection).trim();
                if (!RagSettings.COLLECTION.matcher(collection).matches()) {
                    LLMAccess.error(hresponse, HttpServletResponse.SC_BAD_REQUEST, "invalid_request",
                            "collection must be a collection name (letters, digits, '-' and '_', at most 64 characters).");
                    return;
                }
            }
            bodyObject.remove("collection");

            // server base system prompt first; client system messages become lower-priority preferences
            final PromptGuard guard = new PromptGuard();
            JSONArray messages = guard.withSystemPrompt(bodyObject.optJSONArray("messages"),
                    sb.getConfig("ai.system-prompt", LLM_SYSTEM_PROMPT_DEFAULT));
            bodyObject.put("messages", messages);
            final String userPrefix = userPrefix(sb.getConfig("ai.llm-user-prefix", LLM_USER_PREFIX_DEFAULT));
            final RagSettings ragSettings = RagSettings.current();

            UserObject userObject = null;
            String user = "";
            String ragMode = "no";
            int lastUserIndex = -1;
            for (int i = messages.length() - 1; i >= 0; i--) {
                JSONObject message = messages.getJSONObject(i);
                if ("user".equals(message.optString("role", ""))) {
                    lastUserIndex = i;
                    break;
                }
            }
            if (lastUserIndex >= 0) {
                userObject = new UserObject(messages.getJSONObject(lastUserIndex));
                user = userObject.getContentText(); // this is the latest user prompt
                ragMode = userObject.getSearchMode();
            }
            final boolean fresh = userObject != null && !"no".equals(ragMode);

            // answer parameters from the model configuration (LLM Selection) and the RAG settings
            int maxTokens = llm4Chat.llm.max_tokens;
            final int requestedMaxTokens = bodyObject.optInt("max_tokens", 0);
            if (requestedMaxTokens > 0 && requestedMaxTokens < maxTokens) maxTokens = requestedMaxTokens;
            bodyObject.put("max_tokens", maxTokens);
            if (!(bodyObject.opt("temperature") instanceof Number)) bodyObject.put("temperature", ragSettings.temperature);
            if (llm4Chat.llm.type == LLM.LLMType.OLLAMA || llm4Chat.llm.type == LLM.LLMType.LMSTUDIO) {
                bodyObject.put("num_ctx", llm4Chat.llm.num_ctx); // best-effort hint, as LLM.chat sends it
            }

            // budget: num_ctx minus answer, system prompt and question; the rest for history and sources
            final int fixedChars = RagConversation.chars(messages.optJSONObject(0)) + (user == null ? 0 : user.length())
                    + userPrefix.length() + 160;
            final RagContext.Budget budget = RagContext.plan(llm4Chat.llm.num_ctx, maxTokens, fixedChars, ragSettings.searchDocumentMaxLength);

            // earlier search documents: dropped when this turn searches again, else only the latest is kept
            final RagConversation.Pruned pruned = RagConversation.pruneSearchAttachments(messages, fresh);
            List<RagContext.Source> sources = new ArrayList<>();
            if (!fresh && pruned.keptSearchText != null) {
                final String limited = RagContext.limitEntries(pruned.keptSearchText, budget.sourceChars(0));
                RagConversation.replaceSearchDocument(messages, limited);
                sources = RagContext.parse(limited);
            }

            // attached texts (files, the kept search document) are untrusted data
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i);
                if (message.optString("role", "").equals("user")) {
                    UserObject attachments = new UserObject(message);
                    attachments.attachAttachment(userPrefix, guard);
                }
            }
            final int trimmed = RagConversation.trimHistory(messages, fresh ? budget.historyAllowance : budget.freeChars);
            if (!fresh && !pruned.keptAfterTrim(trimmed)) sources = new ArrayList<>(); // its message did not fit
            final String question = user == null ? "" : user;
            int currentExtraChars = 0;
            for (int i = messages.length() - 1; i >= 0; i--) {
                if ("user".equals(messages.getJSONObject(i).optString("role", ""))) {
                    userObject = new UserObject(messages.getJSONObject(i));
                    user = userObject.getContentText(); // with the data of files attached to this message
                    currentExtraChars = Math.max(0, user.length() - question.length());
                    break;
                }
            }
            ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-request phase=messages messages=" + messages.length() + " ragMode=" + ragMode
                    + " userChars=" + (user == null ? 0 : user.length()) + " collection=" + (collection != null) + " numCtx=" + llm4Chat.llm.num_ctx
                    + " maxTokens=" + maxTokens + " freeChars=" + budget.freeChars + " prunedSearchDocs=" + pruned.removed + " staleAnswers=" + pruned.staleAnswers + " trimmedMessages=" + trimmed);

            // RAG: one retrieval for the LLM context and for the sources shown to the user
            JSONObject initialMetadata = null;
            if (fresh) {
                final long searchStart = System.currentTimeMillis();
                RagQuery query = RagQuery.prepare(question);
                final String scope = collection != null ? collection : query.questionCollection;
                final boolean global = "global".equals(ragMode) && scope == null;
                final RagRetriever retriever = new RagRetriever((q, c, n, g) -> RAGAugmentor.searchCandidates(q, c, n, g, runId));
                RagRetriever.Result result = retriever.retrieve(query, scope, global, ragSettings.maxSources);
                if (result.selected.isEmpty() && countWords(question) > DIRECT_SEARCH_WORD_LIMIT && llm4tldr != null) {
                    // long question without result: let the tldr model suggest search words once
                    final String words = RAGAugmentor.searchWordsForPrompt(llm4tldr.llm, llm4tldr.model, question,
                            sb.getConfig("ai.llm-query-generator-prefix", LLM_QUERY_GENERATOR_PREFIX_DEFAULT), runId);
                    if (words != null && !words.isEmpty()) {
                        query = RagQuery.prepare(words);
                        result = retriever.retrieve(query, scope, global, ragSettings.maxSources);
                    }
                }
                final RagContext.Built built = RagContext.build(result.selected, query,
                        Math.max(0, budget.sourceChars(RagConversation.historyChars(messages)) - currentExtraChars),
                        ragSettings.documentMaxLength, ragSettings.maxSources, scope);
                sources = new ArrayList<>(built.sources);
                user += userPrefix;
                user += guard.data(built.text.isEmpty() ? "(no search results)" : built.text);
                userObject.setContentText(user);
                ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-retrieval phase=end ragMode=" + ragMode + " terms=" + query.terms.size()
                        + " stage=" + result.stage + " candidates=" + result.candidates + " foreignDropped=" + result.foreignDropped
                        + " sources=" + built.sources.size() + " contextChars=" + built.text.length() + " searchMs=" + (System.currentTimeMillis() - searchStart));
                initialMetadata = new JSONObject(true);
                initialMetadata.put("scoutro-sources", built.sourcesJson());
                final JSONObject retrieval = new JSONObject(true);
                retrieval.put("query", query.searchString(false));
                retrieval.put("stage", result.stage);
                retrieval.put("collection", scope == null ? JSONObject.NULL : scope);
                retrieval.put("global", result.global);
                retrieval.put("candidates", result.candidates);
                retrieval.put("contextChars", built.text.length());
                retrieval.put("contextTokens", RagContext.tokens(built.text.length()));
                retrieval.put("numCtx", llm4Chat.llm.num_ctx);
                initialMetadata.put("scoutro-retrieval", retrieval);
                if (!built.text.isEmpty()) {
                    // the attachment the chat page keeps for follow-up questions: exactly what the model saw
                    initialMetadata.put("search-filename", "scoutro-sources-" + (scope == null ? "index" : scope) + ".md");
                    initialMetadata.put("search-text-base64", Base64.getEncoder().encodeToString(built.text.getBytes(StandardCharsets.UTF_8)));
                }
            } else if (!sources.isEmpty()) {
                initialMetadata = new JSONObject(true);
                initialMetadata.put("scoutro-sources", RagContext.sourcesJson(sources));
            }
            final List<RagContext.Source> answerSources = sources;
            final ToolCallProtocol.AnswerHook citations = new ToolCallProtocol.AnswerHook(answer -> {
                try {
                    return new JSONObject(true).put("scoutro-citations", RagCitations.check(answer, answerSources));
                } catch (final JSONException e) {
                    return null;
                }
            });

            // ToolCallProtocol owns request preparation, initial stream handling and follow-up tool rounds.
            final int status;
            try {
                status = ToolCallProtocol.proxyToolLifecycle(out, llm4Chat, bodyObject, messages, initialMetadata, runId, citations);
            } catch (final ToolCallProtocol.UpstreamUnavailable e) {
                ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=end result=llm-unreachable reason=" + LogRedaction.redactMessage(e) + " durationMs=" + elapsed(requestStart));
                LLMAccess.error(hresponse, HttpServletResponse.SC_BAD_GATEWAY, LLMAccess.LLM_UNREACHABLE,
                        "The LLM endpoint is not reachable from the Scoutro server: check the hoststub in LLM Selection.");
                return;
            }
            if (status != HttpServletResponse.SC_OK) {
                ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=end result=llm-error status=" + status + " durationMs=" + elapsed(requestStart));
                if (status == HttpServletResponse.SC_UNAUTHORIZED || status == HttpServletResponse.SC_FORBIDDEN) {
                    LLMAccess.error(hresponse, HttpServletResponse.SC_BAD_GATEWAY, LLMAccess.LLM_AUTH_FAILED,
                            "The LLM endpoint rejected the credentials (HTTP " + status + "): check the API key in LLM Selection.");
                } else {
                    LLMAccess.error(hresponse, HttpServletResponse.SC_BAD_GATEWAY, LLMAccess.LLM_ERROR,
                            "The LLM endpoint answered with HTTP " + status + ".");
                }
                return;
            }
            hresponse.setStatus(status);
            out.close(); // close this here to end transmission
            ConcurrentLog.info("RAGProxy", "runId=" + runId + " event=rag-request phase=end result=success status=" + status + " durationMs=" + elapsed(requestStart));
        } catch (JSONException e) {
            ConcurrentLog.warn("RAGProxy", "runId=" + runId + " event=rag-request phase=end result=failure errorClass=" + e.getClass().getName() + " reason=" + LogRedaction.redactMessage(e) + " durationMs=" + elapsed(requestStart));
            if (!hresponse.isCommitted()) {
                LLMAccess.error(hresponse, HttpServletResponse.SC_BAD_REQUEST, "invalid_request", "The request body is not a valid chat completion request.");
                return;
            }
            throw new IOException(e.getMessage());
        }
    }

    /** the stored default of older versions asked the model not to discuss the documents; citations need the opposite */
    static final String LLM_USER_PREFIX_LEGACY = "\n\nAdditional Information:\n\nbelow you find a collection of texts that might be useful to generate a response. Do not discuss these documents, just use them to answer the question above.\n\n";

    /**
     * The configured RAG prefix between blank lines; empty or the legacy default (which asked the model
     * not to discuss the documents, the opposite of citing them) select the current default.
     */
    public static String userPrefix(final String configured) {
        String prefix = configured == null ? "" : configured.replace("\\n", "\n").trim();
        if (prefix.isEmpty() || prefix.equals(LLM_USER_PREFIX_LEGACY.trim())) prefix = LLM_USER_PREFIX_DEFAULT.trim();
        return "\n\n" + prefix + "\n\n";
    }

    private static long elapsed(final long start) {
        return System.currentTimeMillis() - start;
    }

    private static int countWords(final String text) {
        if (text == null) return 0;
        final String trimmed = text.trim();
        if (trimmed.isEmpty()) return 0;
        return trimmed.split("\\s+").length;
    }

    public final static class DataURL {
    	private String mimetype;
    	private byte[] data;
    	private int signature; // identifier/helper
    	public DataURL(String data_url) {
    		if (data_url == null || !data_url.startsWith("data:")) {
                throw new IllegalArgumentException("data url not valid: it must start with 'data:'");
            }
    		int commaIndex = data_url.indexOf(',');
            if (commaIndex == -1) {
                throw new IllegalArgumentException("data url not valid: it must contain a comma");
            }
            String header = data_url.substring(5, commaIndex); // "image/jpeg;base64"
            String base64Data = data_url.substring(commaIndex + 1); // "/9j/4AAQSkZJRgABAQEASAB..."
            String[] headerParts = header.split(";");
            this.mimetype = headerParts[0]; // i.e. "image/jpeg"
            this.data = Base64.getDecoder().decode(base64Data);
            this.signature = base64Data.hashCode();
    	}
    	public String getMimetype() {
    		return this.mimetype;
    	}
    	public byte[] getData() {
    		return this.data;
    	}
    	public int getSiganture() {
    	    return this.signature;
    	}
    }

    public final static class UserObject {
        private JSONObject userObject;

        public UserObject(JSONObject userObject) {
            this.userObject = userObject;
        }
        
        public void attachAttachment(String prefix, PromptGuard guard) {
            List<DataURL> data_urls = this.getContentAttachments(); // this list is a copy of the content data_urls
            
            // if the data_urls contains a text object, we remove that and inject it into the text prompt
            // as untrusted data (attached files and the search results of earlier rounds)
            for (DataURL data_url: data_urls) {
                if (!data_url.getMimetype().startsWith("text/")) continue;
                String user = this.getContentText(); // this is the latest prompt
                String attachment = new String(data_url.getData(), StandardCharsets.UTF_8);
                user += prefix;
                user += guard.data(attachment);
                this.setContentText(user);
                this.removeContentAttachment(data_url);
            }
            this.normalize();
        }
        
        public String getSearchMode() {
            Object raw = this.userObject.opt("search");
            if (raw instanceof Boolean) {
                return ((Boolean) raw) ? "local" : "no";
            }
            final String search = this.userObject.optString("search", "").trim().toLowerCase();
            if (search.isEmpty() || "no".equals(search) || "false".equals(search)) {
                return "no";
            }
            if ("local".equals(search) || "global".equals(search)) {
                return search;
            }
            return "no";
        }
        
        public String getContentText() {
            Object content = this.userObject.opt("content");
            assert content != null;
            if (content instanceof JSONArray) {
                JSONArray array = (JSONArray) content;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject j = array.optJSONObject(i);
                    String ctype = j.optString("type");
                    if (ctype != null && ctype.equals("text")) {
                        String text = j.optString("text", "");
                        return text;
                    }
                }
                return "";
            }
            assert content instanceof String;
            return (String) content;
        }
        
        public List<DataURL> getContentAttachments() {
        	ArrayList<DataURL> list = new ArrayList<>();
            Object content = this.userObject.opt("content");
            assert content != null;
            if (content instanceof JSONArray) {
                JSONArray array = (JSONArray) content;
                for (int i = 0; i < array.length(); i++) {
                    JSONObject j = array.optJSONObject(i);
                    String ctype = j.optString("type");
                    if (ctype != null && ctype.equals("image_url")) {
                        JSONObject image_url = j.optJSONObject("image_url");
                        if (image_url != null) {
                        	String data_url = image_url.optString("url", "");
                        	if (data_url.length() > 0) {
                        		DataURL dataurl = new DataURL(data_url);
                        		list.add(dataurl);
                        	}
                        }
                    }
                }
            }
            return list;
        }
        
        public void removeContentAttachment(final DataURL delete_data_url) {
            Object content = this.userObject.opt("content");
            assert content != null;
            if (content instanceof JSONArray) {
                JSONArray array = (JSONArray) content;
                arrayloop: for (int i = 0; i < array.length(); i++) {
                    JSONObject j = array.optJSONObject(i);
                    String ctype = j.optString("type");
                    if (ctype != null && ctype.equals("image_url")) {
                        JSONObject image_url = j.optJSONObject("image_url");
                        if (image_url != null) {
                            String data_url = image_url.optString("url", "");
                            if (data_url.length() > 0) {
                                DataURL dataurl = new DataURL(data_url);
                                if (dataurl.getSiganture() == delete_data_url.getSiganture()) {
                                    array.remove(i);
                                    break arrayloop;
                                }
                            }
                        }
                    }
                }
                normalize();
            }
        }
        
        public void normalize() {
            // make a canonical form, which is that if the user object has no attachment,
            // then it should not have a "content" object.
            Object content = this.userObject.opt("content");
            assert content != null;
            if (content instanceof String) return;
            assert content instanceof JSONArray;
            JSONArray array = (JSONArray) content;
            assert array.length() > 0;
            if (array.length() != 1) return;
            JSONObject j = array.optJSONObject(0);
            String ctype = j.optString("type");
            assert ctype != null;
            assert ctype.equals("text");
            if (!ctype.equals("text")) return; // but thats wrong
            String text = j.optString("text", "");
            // simply replace the content array with the text, because nothing else is there.
            try {this.userObject.putOpt("content", text);} catch (JSONException e) {}
        }
        
        public void setContentText(String text) {
            Object content = this.userObject.opt("content");
            assert content != null;
            if (content instanceof String) {
                try {this.userObject.put("content", text);} catch (JSONException e) {}
                return;
            }
            assert content instanceof JSONArray;
            JSONArray array = (JSONArray) content;
            for (int i = 0; i < array.length(); i++) {
                JSONObject j = array.optJSONObject(i);
                String ctype = j.optString("type");
                if (ctype != null && ctype.equals("text")) {
                    try {j.putOpt("text", text);} catch (JSONException e) {}
                    return;
                }
            }
        }
    }

    public static void pruneOldEntries(long now) {
        while (true) {
            final AbstractMap.SimpleEntry<Long, String> head = ACCESS_LOG.peekFirst();
            if (head == null) break;
            if (now - head.getKey() > ONE_DAY_MS) {
                ACCESS_LOG.pollFirst();
            } else {
                break;
            }
        }
    }

    public static void recordAccess(String ip) {
        final long now = System.currentTimeMillis();
        pruneOldEntries(now);
        ACCESS_LOG.addLast(new AbstractMap.SimpleEntry<>(now, ip));
    }

    public static long countAccess(String ip, long windowMillis, long now) {
        return ACCESS_LOG.stream()
                .filter(e -> (ip == null || e.getValue().equals(ip)) && (now - e.getKey()) <= windowMillis)
                .count();
    }

    public static boolean isRateLimited(Switchboard sb, String ip, boolean localhostAccess) {
        final long now = System.currentTimeMillis();
        pruneOldEntries(now);
        boolean allow_nonlocalhost = sb.getConfigBool("ai.shield.allow-nonlocalhost", false);
        boolean limit_all = sb.getConfigBool("ai.shield.limit-all", false);
        
        // guest limits apply only to non-localhost
        if (!localhostAccess) {
            long perMinuteLimit = allow_nonlocalhost ? parseLimit(sb.getConfig("ai.shield.rate.per-minute", "0")) : 0;
            long perHourLimit = allow_nonlocalhost ? parseLimit(sb.getConfig("ai.shield.rate.per-hour", "0")) : 0;
            long perDayLimit = allow_nonlocalhost ? parseLimit(sb.getConfig("ai.shield.rate.per-day", "0")) : 0;
            if (perMinuteLimit > 0 && countAccess(ip, ONE_MINUTE_MS, now) >= perMinuteLimit) return true;
            if (perHourLimit > 0 && countAccess(ip, ONE_HOUR_MS, now) >= perHourLimit) return true;
            if (perDayLimit > 0 && countAccess(ip, ONE_DAY_MS, now) >= perDayLimit) return true;
        }

        if (localhostAccess && limit_all) {
            long allMinute = parseLimit(sb.getConfig("ai.shield.all.per-minute", "0"));
            long allHour = parseLimit(sb.getConfig("ai.shield.all.per-hour", "0"));
            long allDay = parseLimit(sb.getConfig("ai.shield.all.per-day", "0"));
            if (allMinute > 0 && countAccess(null, ONE_MINUTE_MS, now) >= allMinute) return true;
            if (allHour > 0 && countAccess(null, ONE_HOUR_MS, now) >= allHour) return true;
            if (allDay > 0 && countAccess(null, ONE_DAY_MS, now) >= allDay) return true;
        }
        return false;
    }

    private static long parseLimit(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

}
