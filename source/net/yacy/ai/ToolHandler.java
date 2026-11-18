/**
 *  ToolHandler
 *  Copyright 2026 by Michael Peter Christen
 *  First released 06.02.2026 at https://yacy.net
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

package net.yacy.ai;

import org.json.JSONException;
import org.json.JSONObject;

public interface ToolHandler {

    JSONObject definition() throws JSONException;

    String execute(String arguments);

    int maxCallsPerTurn();

    /**
     * True for a tool that only exists within the server-side scope of a chat request
     * ({@link net.yacy.ai.rag.ToolContext}): it is offered and run only with one.
     */
    default boolean scoped() {
        return false;
    }

    /** For a scoped tool: whether it needs the knowledge graph (a scope that admits the graph). */
    default boolean requiresGraph() {
        return true;
    }

    /**
     * Runs the tool within the scope of a chat request. The scope comes from the chat session, never from
     * {@code arguments}. A tool that knows no scope runs as without one.
     */
    default String execute(final String arguments, final net.yacy.ai.rag.ToolContext context) {
        return execute(arguments);
    }
    
    public static String errorJson(String message) {
        try {
            JSONObject err = new JSONObject(true);
            err.put("error", message == null ? "error" : message);
            return err.toString();
        } catch (JSONException e) {
            return "{\"error\":\"" + (message == null ? "error" : message.replace("\"", "'")) + "\"}";
        }
    }
}
