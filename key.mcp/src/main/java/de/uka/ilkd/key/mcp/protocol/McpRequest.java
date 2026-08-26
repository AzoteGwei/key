/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.Map;

/**
 * A parsed MCP JSON-RPC request.
 */
public record McpRequest(Object id, String method, Map<String, Object> params) {

    /**
     * Returns the {@code _meta} object of the request params, or an empty map.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> meta() {
        Object meta = params.get("_meta");
        return meta instanceof Map ? (Map<String, Object>) meta : Map.of();
    }

    /**
     * Returns the protocol version declared by a modern request
     * ({@code _meta["io.modelcontextprotocol/protocolVersion"]}), or {@code null} if the
     * request does not carry one (legacy request).
     */
    public String protocolVersion() {
        Object version = meta().get(McpProtocol.META_PROTOCOL_VERSION);
        return version instanceof String ? (String) version : null;
    }

    /**
     * Whether the request declares client capabilities via
     * {@code _meta["io.modelcontextprotocol/clientCapabilities"]} (required on modern
     * requests).
     */
    public boolean hasClientCapabilities() {
        return meta().containsKey(McpProtocol.META_CLIENT_CAPABILITIES);
    }
}
