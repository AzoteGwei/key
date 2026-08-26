/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.json;

import java.util.List;
import java.util.Map;

/**
 * A minimal JSON Schema 2020-12 validator for MCP tool output schemas.
 *
 * <p>
 * Intentionally small and dependency-free to avoid adding a third-party JSON Schema
 * library to the key.mcp module. Supports the subset used by the KeY MCP tool schemas:
 * {@code type}, {@code properties}, {@code required}, {@code items}, {@code enum} and
 * {@code anyOf}.
 * </p>
 */
public final class JsonSchemaValidator {
    private JsonSchemaValidator() {
    }

    /**
     * Validates a value against a JSON Schema.
     *
     * @param value the value to validate
     * @param schema the schema (JSON object)
     * @throws ValidationException if validation fails
     */
    public static void validate(Object value, Map<String, Object> schema) {
        validate(value, schema, "");
    }

    private static void validate(Object value, Map<String, Object> schema, String path) {
        if (schema == null) {
            return;
        }
        Object anyOf = schema.get("anyOf");
        if (anyOf instanceof List<?> alternatives) {
            validateAnyOf(value, alternatives, path);
            return;
        }
        String type = (String) schema.get("type");
        if (type != null) {
            validateType(value, type, path);
        }
        Object enumValues = schema.get("enum");
        if (enumValues instanceof List<?> values) {
            if (!values.contains(value)) {
                throw new ValidationException(path,
                    "Value " + value + " not in enum " + values);
            }
        }
        if ("object".equals(type) && value instanceof Map<?, ?> map) {
            validateObject(map, schema, path);
        } else if ("array".equals(type) && value instanceof List<?> list) {
            validateArray(list, schema, path);
        }
    }

    private static void validateAnyOf(Object value, List<?> alternatives, String path) {
        ValidationException last = null;
        for (Object alt : alternatives) {
            if (!(alt instanceof Map<?, ?>)) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> altSchema = (Map<String, Object>) alt;
                validate(value, altSchema, path);
                return;
            } catch (ValidationException e) {
                last = e;
            }
        }
        if (last != null) {
            throw new ValidationException(path,
                "Value does not match anyOf schema: " + last.getMessage());
        }
        throw new ValidationException(path, "Value does not match anyOf schema");
    }

    private static void validateType(Object value, String type, String path) {
        boolean ok = switch (type) {
            case "object" -> value instanceof Map;
            case "array" -> value instanceof List;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Number n && n.doubleValue() == n.longValue();
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
        if (!ok) {
            throw new ValidationException(path,
                "Expected type " + type + " but got " + describe(value));
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateObject(Map<?, ?> map, Map<String, Object> schema, String path) {
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Object required = schema.get("required");
        if (required instanceof List<?> requiredKeys) {
            for (Object keyObj : requiredKeys) {
                String key = (String) keyObj;
                if (!map.containsKey(key)) {
                    throw new ValidationException(path,
                        "Missing required property: " + key);
                }
            }
        }
        if (properties != null) {
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                String key = entry.getKey();
                Object propertySchema = entry.getValue();
                if (map.containsKey(key) && propertySchema instanceof Map<?, ?>) {
                    String childPath = path.isEmpty() ? key : path + "." + key;
                    validate(map.get(key), (Map<String, Object>) propertySchema, childPath);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void validateArray(List<?> list, Map<String, Object> schema, String path) {
        Object itemsSchema = schema.get("items");
        if (itemsSchema instanceof Map<?, ?>) {
            for (int i = 0; i < list.size(); i++) {
                String childPath = path + "[" + i + "]";
                validate(list.get(i), (Map<String, Object>) itemsSchema, childPath);
            }
        }
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Number) {
            return "number";
        }
        return value.getClass().getSimpleName();
    }

    /**
     * Exception thrown when a value does not satisfy a JSON Schema.
     */
    public static class ValidationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ValidationException(String path, String message) {
            super((path.isEmpty() ? "" : path + ": ") + message);
        }
    }
}
