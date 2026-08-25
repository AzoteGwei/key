/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.json.JsonParseException;

/**
 * Encoder/decoder for MCP JSON-RPC messages.
 */
public final class JsonRpcCodec {
    private JsonRpcCodec() {
    }

    /**
     * Parses a JSON-RPC request line.
     *
     * @param line the raw JSON text
     * @return the parsed request
     * @throws JsonParseException if the line is not valid JSON-RPC
     */
    public static McpRequest parseRequest(String line) {
        Map<String, Object> map = Json.parseObject(line);
        Object id = map.get("id");
        Object method = map.get("method");
        if (!(method instanceof String)) {
            throw new JsonParseException("Missing or invalid 'method' field");
        }
        Object params = map.get("params");
        Map<String, Object> paramsMap = (params instanceof Map) ? castMap(params) : Map.of();
        return new McpRequest(id, (String) method, paramsMap);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    /**
     * Encodes a successful response.
     */
    public static String encodeSuccess(Object id, Object result) {
        Map<String, Object> map = Json.object();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        map.put("result", result);
        return Json.stringify(map);
    }

    /**
     * Encodes an error response.
     */
    public static String encodeError(Object id, int code, String message, Object data) {
        Map<String, Object> map = Json.object();
        map.put("jsonrpc", "2.0");
        map.put("id", id);
        Map<String, Object> error = Json.object();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", data);
        }
        map.put("error", error);
        return Json.stringify(map);
    }

    /**
     * Encodes a notification (no id).
     */
    public static String encodeNotification(String method, Object params) {
        Map<String, Object> map = Json.object();
        map.put("jsonrpc", "2.0");
        map.put("method", method);
        map.put("params", params);
        return Json.stringify(map);
    }
}
