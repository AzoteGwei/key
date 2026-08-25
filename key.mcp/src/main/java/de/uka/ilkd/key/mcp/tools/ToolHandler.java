/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Base class for domain-specific MCP tool handlers.
 */
public abstract class ToolHandler {
    protected final ToolContext ctx;

    protected ToolHandler(ToolContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Registers all tools of this handler into the given registry map.
     */
    public abstract void registerTools(Map<String, ToolDefinition> tools);

    protected void register(Map<String, ToolDefinition> tools, String name, String description,
            Map<String, Object> properties, List<String> required,
            ToolDefinition.ToolExecutor executor) {
        Map<String, Object> schema = Json.object();
        schema.put("name", name);
        schema.put("description", description);
        schema.put("inputSchema",
            Map.of("type", "object", "properties", properties, "required", required));
        tools.put(name, new ToolDefinition(schema, executor));
    }

    protected Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> properties = Json.object();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }
}
