/**
 *  yacychat
 *  Copyright 2025 by Michael Peter Christen
 *  First released 23.11.2025 at https://yacy.net
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

package net.yacy.htroot;

import java.util.List;

import net.yacy.ai.rag.ChatCollections;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.http.servlets.LLMAccess;
import net.yacy.scoutro.api.CollectionCatalog;
import net.yacy.search.Switchboard;
import net.yacy.search.SwitchboardConstants;
import net.yacy.server.serverObjects;
import net.yacy.server.serverSwitch;

public class yacychat {

    public static serverObjects respond(final RequestHeader header, final serverObjects post, final serverSwitch env) {
        // return variable that accumulates replacements
        final serverObjects prop = new serverObjects();

        // system prompt comes from configuration; default is empty
        final Switchboard sb = (Switchboard) env;
        final String systemPrompt = sb.getConfig("ai.system-prompt", net.yacy.http.servlets.RAGProxyServlet.LLM_SYSTEM_PROMPT_DEFAULT);
        // escape for safe embedding in a JS single-quoted string literal
        final String systemPromptJs = systemPrompt.replace("\\", "\\\\").replace("'", "\\'").replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
        prop.put("system_prompt", systemPromptJs);
        // Administrators always get the administration navigation with the AI Lab; the front page link
        // (ai.shield.show-chat-link) only selects the public header for visitors. Access stays with the AI Shield.
        final boolean publicChat = sb.getConfigBool("ai.shield.show-chat-link", false) && !sb.verifyAuthentication(header);
        prop.put("topmenu", publicChat ? (sb.getConfigBool("publicTopmenu", true) ? 1 : 0) : 2);

        String promoteChatPageGreeting = env.getConfig("promoteChatPageGreeting", "");
        if (env.getConfigBool(SwitchboardConstants.GREETING_NETWORK_NAME, false)) promoteChatPageGreeting = env.getConfig("network.unit.description", "");
        
        prop.put("promoteChatPageGreeting", promoteChatPageGreeting);
        prop.put("topmenu_promoteChatPageGreeting", promoteChatPageGreeting);
        prop.put(SwitchboardConstants.GREETING_HOMEPAGE, sb.getConfig(SwitchboardConstants.GREETING_HOMEPAGE, ""));
        prop.put("topmenu_" + SwitchboardConstants.GREETING_HOMEPAGE, sb.getConfig(SwitchboardConstants.GREETING_HOMEPAGE, ""));
        prop.put(SwitchboardConstants.GREETING_LARGE_IMAGE, sb.getConfig(SwitchboardConstants.GREETING_LARGE_IMAGE, ""));
        prop.put("topmenu_" + SwitchboardConstants.GREETING_LARGE_IMAGE, sb.getConfig(SwitchboardConstants.GREETING_LARGE_IMAGE, ""));
        prop.put(SwitchboardConstants.GREETING_IMAGE_ALT, sb.getConfig(SwitchboardConstants.GREETING_IMAGE_ALT, ""));
        prop.put("topmenu_" + SwitchboardConstants.GREETING_IMAGE_ALT, sb.getConfig(SwitchboardConstants.GREETING_IMAGE_ALT, ""));

        // determine if P2P mode is active (global search available)
        final boolean indexReceiveGranted =
                sb.getConfigBool(net.yacy.search.SwitchboardConstants.INDEX_RECEIVE_ALLOW_SEARCH, true) ||
                (sb.isRobinsonMode() && sb.getConfig(net.yacy.search.SwitchboardConstants.CLUSTER_MODE, "").equals(net.yacy.search.SwitchboardConstants.CLUSTER_MODE_PUBLIC_CLUSTER));
        prop.put("p2p_mode", indexReceiveGranted ? 1 : 0);

        // the collections this client may choose as the search scope (package 6.1), the same rule as the chat endpoint:
        // local and administrator access all of the index, anyone else only the released ones; sorted alphabetically
        final boolean privileged = ChatCollections.privileged(LLMAccess.client(header), header);
        final List<String> collections = ChatCollections.allowed(privileged,
                () -> CollectionCatalog.current().selectable(), sb.getConfig(ChatCollections.GUEST_SETTING, ""));
        final java.util.Map<String, String> names = new java.util.HashMap<>();
        try {
            for (final CollectionCatalog.Entry e : CollectionCatalog.current().entries(false)) names.put(e.id, e.name);
        } catch (final net.yacy.scoutro.api.ApiException e) {
            // entries(false) does not fail for the index
        }
        for (int i = 0; i < collections.size(); i++) {
            final String id = collections.get(i), name = names.get(id);
            prop.putHTML("collections_" + i + "_id", id);
            prop.putHTML("collections_" + i + "_label", name == null || name.equals(id) ? id : name + " · " + id);
        }
        prop.put("collections", collections.size());

        // return rewrite properties
        return prop;
    }

}
