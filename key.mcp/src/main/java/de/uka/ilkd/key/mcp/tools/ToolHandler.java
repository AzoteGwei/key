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
            Map<String, Object> inputProperties, List<String> requiredInput,
            Map<String, Object> outputSchema, Map<String, Object> annotations,
            ToolDefinition.ToolExecutor executor) {
        Map<String, Object> schema = Json.object();
        schema.put("name", name);
        schema.put("description", description);
        schema.put("inputSchema",
            Map.of("type", "object", "properties", inputProperties, "required", requiredInput));
        schema.put("outputSchema", outputSchema);
        schema.put("annotations", annotations != null ? annotations : Json.object());
        tools.put(name, new ToolDefinition(schema, executor));
    }

    protected Map<String, Object> props(Object... keysAndValues) {
        Map<String, Object> properties = Json.object();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }

    // ----- JSON Schema helpers ------------------------------------------------

    protected static Map<String, Object> objectSchema(List<String> required,
            Map<String, Object> properties) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    protected static Map<String, Object> arraySchema(Map<String, Object> items) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "array");
        if (items != null) {
            schema.put("items", items);
        }
        return schema;
    }

    protected static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    protected static Map<String, Object> integerSchema() {
        return Map.of("type", "integer");
    }

    protected static Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    protected static Map<String, Object> enumSchema(List<String> values) {
        Map<String, Object> schema = Json.object();
        schema.put("type", "string");
        schema.put("enum", values);
        return schema;
    }

    @SafeVarargs
    protected static Map<String, Object> anyOfSchema(Map<String, Object>... alternatives) {
        // MCP requires outputSchema to be a JSON Schema object with type "object"
        // at the top level; the alternatives describe the possible shapes of that object.
        Map<String, Object> schema = Json.object();
        schema.put("type", "object");
        schema.put("anyOf", List.of(alternatives));
        return schema;
    }

    // ----- Annotation helpers -------------------------------------------------

    protected static Map<String, Object> annotations(boolean readOnly, boolean destructive,
            boolean idempotent) {
        Map<String, Object> annotations = Json.object();
        if (readOnly) {
            annotations.put("readOnlyHint", true);
        }
        if (destructive) {
            annotations.put("destructiveHint", true);
        }
        if (idempotent) {
            annotations.put("idempotentHint", true);
        }
        return annotations;
    }
}
