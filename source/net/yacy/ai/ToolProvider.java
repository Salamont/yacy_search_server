/**
 *  ToolProvider
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import net.yacy.ai.tools.CalculatorTool;
import net.yacy.ai.tools.ChitChatTool;
import net.yacy.ai.tools.DateMathTool;
import net.yacy.ai.tools.DateTimeTool;
import net.yacy.ai.tools.HttpJsonTool;
import net.yacy.ai.tools.Mermaid2ASCIITool;
import net.yacy.ai.tools.NumberParserTool;
import net.yacy.ai.tools.PromptToMermaidTool;
import net.yacy.ai.tools.SearchTool;
import net.yacy.ai.tools.SelfReflectTool;
import net.yacy.ai.tools.TableOpsTool;
import net.yacy.ai.tools.UnitConverterTool;
import net.yacy.ai.tools.UpdatePlanTool;
import net.yacy.ai.tools.WebFetchTool;
import net.yacy.ai.tools.WikipediaLinkCreatorTool;
import net.yacy.search.Switchboard;

/**
 * Central registry and dispatch utility for all built-in YaCy LLM tools.
 * <p>
 * Responsibilities:
 * <ul>
 *   <li>Expose tool JSON schemas into outbound chat-completion requests.</li>
 *   <li>Resolve tool names to handlers.</li>
 *   <li>Execute tool calls by name with raw JSON argument text.</li>
 * </ul>
 * <p>
 * A tool is offered to the model and executable only when it is released server-side:
 * {@code ai.tools.<name>.enabled=true} and {@code ai.tools.<name>.maxCallsPerTurn > 0}.
 * Model capability {@code tooling=supported} alone releases nothing; it only allows the
 * released tools to be sent to that model. Client-supplied tool definitions are removed.
 */
public final class ToolProvider {
    private static final String CONFIG_PREFIX = "ai.tools.";
    private static final String DESCRIPTION_SUFFIX = ".description";
    private static final String MAX_CALLS_SUFFIX = ".maxCallsPerTurn";
    private static final String ENABLED_SUFFIX = ".enabled";

    /** Configuration source: the Switchboard, or a fixed map in tests. */
    interface Settings {
        String get(String key, String defaultValue);
    }

    private static volatile Settings settings = null;

    /** Tests only: read the tool configuration from the given source (null: Switchboard). */
    static void useSettings(final Settings source) {
        settings = source;
    }

    private static String config(final String key, final String defaultValue) {
        final Settings source = settings;
        if (source != null) return source.get(key, defaultValue);
        final Switchboard sb = Switchboard.getSwitchboard();
        return sb == null ? defaultValue : sb.getConfig(key, defaultValue);
    }


    /**
     * Tool handlers for different skill groups
     */
    private static final List<ToolHandler> TOOLS_BASIC = Arrays.asList(
            new DateTimeTool(),
            new DateMathTool(),
            new CalculatorTool(),
            new NumberParserTool(),
            new UnitConverterTool(),
            new HttpJsonTool(),
            new TableOpsTool(),
            new UpdatePlanTool(),
            new SelfReflectTool(),
            new ChitChatTool()
    );
    private static final List<ToolHandler> TOOLS_VISUALIZATION = Arrays.asList(
            new PromptToMermaidTool(),
            new Mermaid2ASCIITool()
    );
    private static final List<ToolHandler> TOOLS_RETRIEVAL = Arrays.asList(
            new SearchTool(),
            new WikipediaLinkCreatorTool(),
            new WebFetchTool()
    );
    
    /**
     * Ordered list of built-in tool handlers. Order is preserved when exposing
     * definitions to providers.
     */
    private static final List<ToolHandler> TOOLS = new ArrayList<>();
    static {
        TOOLS.addAll(TOOLS_BASIC);
        TOOLS.addAll(TOOLS_VISUALIZATION);
        TOOLS.addAll(TOOLS_RETRIEVAL);
    }

    /**
     * Lookup table for fast runtime dispatch by function/tool name.
     */
    private static final Map<String, ToolHandler> TOOL_BY_NAME = buildToolIndex(TOOLS);

    public static final class ToolConfig {
        public final String name;
        public final String description;
        public final int maxCallsPerTurn;
        public final int defaultMaxCallsPerTurn;
        /** explicitly released by the operator (ai.tools.NAME.enabled) */
        public final boolean released;
        /** released and maxCallsPerTurn > 0: offered to tool-capable models */
        public final boolean enabled;

        private ToolConfig(final String name, final String description, final int maxCallsPerTurn, final int defaultMaxCallsPerTurn,
                final boolean released) {
            this.name = name;
            this.description = description;
            this.maxCallsPerTurn = Math.max(0, maxCallsPerTurn);
            this.defaultMaxCallsPerTurn = Math.max(0, defaultMaxCallsPerTurn);
            this.released = released;
            this.enabled = released && this.maxCallsPerTurn > 0;
        }
    }

    /**
     * Utility class; not instantiable.
     */
    private ToolProvider() {}

    /**
     * Replace the tool part of a chat request body by the released tools.
     * <p>
     * Client-supplied {@code tools}, {@code tool_choice}, {@code functions} and
     * {@code function_call} are always removed. When the model may use tools, the
     * definitions of the released tools are added with {@code tool_choice=auto};
     * without released tools the body carries no tools at all.
     *
     * @param body request body to mutate
     * @param toolingEnabled the model supports tool calls
     */
    public static void applyReleasedTools(final JSONObject body, final boolean toolingEnabled) {
        if (body == null) return;
        body.remove("tools");
        body.remove("tool_choice");
        body.remove("functions");
        body.remove("function_call");
        if (!toolingEnabled) return;
        try {
            final JSONArray tools = new JSONArray();
            for (ToolHandler tool : TOOLS) {
                JSONObject definition = configuredDefinition(tool);
                if (definition == null) continue;
                addToolDefinitionIfMissing(tools, definition);
            }
            if (tools.length() == 0) return;
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        } catch (JSONException e) {
            body.remove("tools");
            body.remove("tool_choice");
        }
    }

    /**
     * Dispatches one tool invocation by name.
     *
     * @param name tool/function name
     * @param arguments raw JSON arguments string
     * @return tool execution result as JSON string (or error JSON)
     */
    public static String executeTool(String name, String arguments) {
        if (name == null) return errorJson("Invalid tool call");
        ToolHandler tool = TOOL_BY_NAME.get(name);
        if (tool == null) return errorJson("Unknown tool: " + name);
        if (allowedCallsPerTurn(name) <= 0) return errorJson("Tool not released: " + name);
        // Tool implementations are responsible for argument parsing/validation.
        return tool.execute(arguments);
    }

    /**
     * Returns the configured maximum number of calls for a tool within one tool
     * turn lifecycle.
     *
     * @param name tool/function name
     * @return maximum call count, defaults to 1 for unknown/invalid values
     */
    public static int maxCallsPerTurn(String name) {
        if (name == null) return 1;
        ToolHandler tool = TOOL_BY_NAME.get(name);
        if (tool == null) return 1;
        int max = configuredMaxCalls(name, tool.maxCallsPerTurn());
        return Math.max(0, max);
    }

    /** True when the operator released the tool (ai.tools.NAME.enabled=true). */
    public static boolean isReleased(final String name) {
        if (name == null || !TOOL_BY_NAME.containsKey(name)) return false;
        return "true".equalsIgnoreCase(config(CONFIG_PREFIX + name + ENABLED_SUFFIX, "false").trim());
    }

    /**
     * Calls of a tool allowed within one tool turn: 0 for unknown or not released tools,
     * otherwise maxCallsPerTurn.
     */
    public static int allowedCallsPerTurn(final String name) {
        if (!isReleased(name)) return 0;
        return maxCallsPerTurn(name);
    }

    public static List<ToolConfig> listTools() {
        return listToolConfigs(TOOLS);
    }

    public static List<ToolConfig> listBasicTools() {
        return listToolConfigs(TOOLS_BASIC);
    }

    public static List<ToolConfig> listVisualizationTools() {
        return listToolConfigs(TOOLS_VISUALIZATION);
    }

    public static List<ToolConfig> listRetrievalTools() {
        return listToolConfigs(TOOLS_RETRIEVAL);
    }

    private static List<ToolConfig> listToolConfigs(final List<ToolHandler> source) {
        final List<ToolConfig> configuredTools = new ArrayList<>();
        for (ToolHandler tool : source) {
            if (tool == null) continue;
            try {
                final JSONObject definition = tool.definition();
                final String name = extractToolName(definition);
                if (name == null || name.isEmpty()) continue;
                final JSONObject fn = definition.optJSONObject("function");
                final String defaultDescription = fn == null ? "" : fn.optString("description", "");
                final int defaultMaxCalls = Math.max(0, tool.maxCallsPerTurn());
                configuredTools.add(new ToolConfig(
                        name,
                        configuredDescription(name, defaultDescription),
                        configuredMaxCalls(name, defaultMaxCalls),
                        defaultMaxCalls,
                        isReleased(name)));
            } catch (JSONException e) {
                // skip invalid definitions
            }
        }
        return Collections.unmodifiableList(configuredTools);
    }

    /**
     * Builds an immutable map from tool name to handler.
     * Invalid or unnamed definitions are skipped.
     *
     * @param tools source handlers
     * @return unmodifiable name-indexed map
     */
    private static Map<String, ToolHandler> buildToolIndex(List<ToolHandler> tools) {
        Map<String, ToolHandler> byName = new LinkedHashMap<>();
        for (ToolHandler tool : tools) {
            if (tool == null) continue;
            try {
                String name = extractToolName(tool.definition());
                if (name == null || name.isEmpty()) continue;
                byName.put(name, tool);
            } catch (JSONException e) {
                // skip invalid tool definition
            }
        }
        return Collections.unmodifiableMap(byName);
    }

    /**
     * Appends a tool definition if no existing tool with the same function name
     * is already present.
     *
     * @param tools destination tool array in request body
     * @param toolDefinition candidate definition
     */
    private static void addToolDefinitionIfMissing(JSONArray tools, JSONObject toolDefinition) {
        if (tools == null || toolDefinition == null) return;
        try {
            String name = extractToolName(toolDefinition);
            if (name == null || name.isEmpty()) return;
            // Dedupe by logical function name (not by full JSON string equality).
            for (int i = 0; i < tools.length(); i++) {
                JSONObject existing = tools.optJSONObject(i);
                if (existing == null) continue;
                String existingName = extractToolName(existing);
                if (name.equals(existingName)) return;
            }
            tools.put(toolDefinition);
        } catch (JSONException e) {
            // ignore malformed tool schema
        }
    }

    /**
     * Extracts the function name from a tool definition object.
     *
     * @param toolDefinition MCP/OpenAI-style tool definition
     * @return trimmed function name or {@code null}
     * @throws JSONException if JSON access fails unexpectedly
     */
    private static String extractToolName(JSONObject toolDefinition) throws JSONException {
        if (toolDefinition == null) return null;
        JSONObject fn = toolDefinition.optJSONObject("function");
        if (fn == null) return null;
        String name = fn.optString("name", "");
        return name == null ? null : name.trim();
    }

    private static JSONObject configuredDefinition(final ToolHandler tool) {
        if (tool == null) return null;
        try {
            final JSONObject definition = tool.definition();
            final String name = extractToolName(definition);
            if (name == null || name.isEmpty()) return null;
            if (allowedCallsPerTurn(name) <= 0) return null;
            final JSONObject fn = definition.optJSONObject("function");
            if (fn != null) {
                final String defaultDescription = fn.optString("description", "");
                fn.put("description", configuredDescription(name, defaultDescription));
            }
            return definition;
        } catch (JSONException e) {
            return null;
        }
    }

    private static String configuredDescription(final String toolName, final String defaultDescription) {
        if (toolName == null || toolName.isEmpty()) return defaultDescription == null ? "" : defaultDescription;
        return config(CONFIG_PREFIX + toolName + DESCRIPTION_SUFFIX, defaultDescription == null ? "" : defaultDescription);
    }

    private static int configuredMaxCalls(final String toolName, final int defaultMaxCalls) {
        if (toolName == null || toolName.isEmpty()) return Math.max(0, defaultMaxCalls);
        int configured = defaultMaxCalls;
        try {
            configured = Integer.parseInt(config(CONFIG_PREFIX + toolName + MAX_CALLS_SUFFIX, Integer.toString(defaultMaxCalls)).trim());
        } catch (final NumberFormatException e) {
            configured = defaultMaxCalls;
        }
        return Math.max(0, configured);
    }

    /**
     * Builds a minimal error JSON string while avoiding propagation of secondary
     * JSON construction failures.
     *
     * @param message human-readable error message
     * @return serialized JSON error object
     */
    private static String errorJson(String message) {
        try {
            JSONObject err = new JSONObject(true);
            err.put("error", message == null ? "error" : message);
            return err.toString();
        } catch (JSONException e) {
            return "{\"error\":\"" + (message == null ? "error" : message.replace("\"", "'")) + "\"}";
        }
    }
}
