/**
 *  SearchTool
 *  Copyright 2026 by Michael Peter Christen
 *  First released 28.03.2026 at https://yacy.net
 *
 *  This library is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public
 *  License as published by the Free Software Foundation; either
 *  version 2.1 of the License, or (at your option) any later version.
 */

package net.yacy.ai.tools;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.RAGAugmentor;
import net.yacy.ai.ToolHandler;

public class SearchTool implements ToolHandler {

    private static final String NAME = "search";
    private static final int RESULT_COUNT = 10;

    @Override
    public JSONObject definition() throws JSONException {
        final JSONObject tool = new JSONObject(true);
        tool.put("type", "function");
        final JSONObject fn = new JSONObject(true);
        fn.put("name", NAME);
        fn.put("description", "Search for documents in the YaCy index. This is like web search. In the chat it searches the local index"
                + " within the chat's collections again: use the concrete words of the question (names, products, job titles, places).");

        final JSONObject params = new JSONObject(true);
        params.put("type", "object");
        final JSONObject props = new JSONObject(true);

        final JSONObject query = new JSONObject(true);
        query.put("type", "string");
        query.put("description", "Search query to run against YaCy.");
        props.put("query", query);

        params.put("properties", props);
        params.put("required", new JSONArray().put("query"));
        fn.put("parameters", params);
        tool.put("function", fn);
        return tool;
    }

    @Override
    public int maxCallsPerTurn() {
        return 3;
    }

    /**
     * Within a chat request: the local search of the RAG retrieval, limited to the chat's collection. The scope is the
     * context's; a {@code collection:} word of the query or any other argument cannot change it, and the peer-to-peer
     * network is never asked. Each page is a numbered source of the answer.
     */
    @Override
    public String execute(final String arguments, final net.yacy.ai.rag.ToolContext context) {
        if (context == null) return execute(arguments);
        return context.run(NAME, () -> {
            final JSONObject args;
            try {
                args = (arguments == null || arguments.isEmpty()) ? new JSONObject(true) : new JSONObject(arguments);
            } catch (final JSONException e) {
                throw new IllegalArgumentException("Invalid arguments JSON");
            }
            final String query = args.optString("query", "").trim();
            if (query.isEmpty()) throw new IllegalArgumentException("Missing query");
            return context.search(NAME, query.length() > 400 ? query.substring(0, 400) : query);
        });
    }

    @Override
    public String execute(final String arguments) {
        final JSONObject args;
        try {
            args = (arguments == null || arguments.isEmpty()) ? new JSONObject(true) : new JSONObject(arguments);
        } catch (final JSONException e) {
            return ToolHandler.errorJson("Invalid arguments JSON");
        }

        final String query = args.optString("query", "").trim();
        if (query.isEmpty()) return ToolHandler.errorJson("Missing query");

        try {
            final JSONObject result = RAGAugmentor.searchResultsDocument(query, RESULT_COUNT, RAGAugmentor.defaultSearchIsGlobal());
            result.put("tool", NAME);
            result.put("query", query);
            result.put("global", RAGAugmentor.defaultSearchIsGlobal());
            return result.toString();
        } catch (final JSONException e) {
            return ToolHandler.errorJson("Failed to build search result document");
        }
    }
}
