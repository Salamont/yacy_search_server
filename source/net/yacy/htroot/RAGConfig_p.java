// RAGConfig_p.java
// Configure RAG prompt strings

package net.yacy.htroot;

import net.yacy.ai.rag.RagSettings;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.search.Switchboard;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class RAGConfig_p {

    public static serverObjects respond(@SuppressWarnings("unused") final RequestHeader header, final serverObjects post, final serverSwitch env) {
        final Switchboard sb = (Switchboard) env;
        final serverObjects prop = new serverObjects();

        if (post != null) {
            final String systemPrompt = post.get("ai.system-prompt", "").trim();
            final String userPrefix = post.get("ai.llm-user-prefix", "").trim();
            final String queryPrefix = post.get("ai.llm-query-generator-prefix", "").trim();
            int searchDocumentMaxLength = post.getInt(net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_CONFIG, net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_DEFAULT);
            if (searchDocumentMaxLength <= 0) searchDocumentMaxLength = net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_DEFAULT;

            sb.setConfig("ai.system-prompt", systemPrompt);
            sb.setConfig("ai.llm-user-prefix", userPrefix);
            sb.setConfig("ai.llm-query-generator-prefix", queryPrefix);
            sb.setConfig(net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_CONFIG, Integer.toString(searchDocumentMaxLength));
            final int maxSources = post.getInt(RagSettings.MAX_SOURCES, RagSettings.MAX_SOURCES_DEFAULT);
            sb.setConfig(RagSettings.MAX_SOURCES, Integer.toString(Math.max(1, Math.min(20, maxSources))));
            final int documentMaxLength = post.getInt(RagSettings.DOCUMENT_MAXLENGTH, RagSettings.DOCUMENT_MAXLENGTH_DEFAULT);
            sb.setConfig(RagSettings.DOCUMENT_MAXLENGTH, Integer.toString(Math.max(400, Math.min(20000, documentMaxLength))));
            double temperature = RagSettings.TEMPERATURE_DEFAULT;
            try {
                temperature = Double.parseDouble(post.get(RagSettings.TEMPERATURE, Double.toString(RagSettings.TEMPERATURE_DEFAULT)).trim());
            } catch (final NumberFormatException e) {
                temperature = RagSettings.TEMPERATURE_DEFAULT;
            }
            sb.setConfig(RagSettings.TEMPERATURE, Double.toString(Math.max(0.0, Math.min(2.0, temperature))));
        }

        // mark page as visited for gamification/quest tracking
        sb.setConfig("ui.RAGConfig_p.visited", "true");

        prop.put("ai.system-prompt", sb.getConfig("ai.system-prompt", net.yacy.http.servlets.RAGProxyServlet.LLM_SYSTEM_PROMPT_DEFAULT));
        prop.put("ai.llm-user-prefix", net.yacy.http.servlets.RAGProxyServlet.userPrefix(sb.getConfig("ai.llm-user-prefix", "")));
        prop.put("ai.llm-query-generator-prefix", sb.getConfig("ai.llm-query-generator-prefix", "Make a list of search words with low document frequency for the following prompt; use a JSON Array: "));
        prop.put("ai.rag.search-document-maxlength", sb.getConfig(net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_CONFIG, Integer.toString(net.yacy.ai.RAGAugmentor.SEARCH_DOCUMENT_MAX_LENGTH_DEFAULT)));
        final RagSettings rag = RagSettings.current();
        prop.put(RagSettings.MAX_SOURCES, rag.maxSources);
        prop.put(RagSettings.DOCUMENT_MAXLENGTH, rag.documentMaxLength);
        prop.put(RagSettings.TEMPERATURE, Double.toString(rag.temperature));

        return prop;
    }
}
