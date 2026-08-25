/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.session.McpSession;

/**
 * Handles MCP resources.
 */
public class McpResourceHandler {
    private final McpSession session;

    public McpResourceHandler(McpSession session) {
        this.session = session;
    }

    public List<Map<String, Object>> listResources() {
        List<Map<String, Object>> resources = new ArrayList<>();
        resources.add(resource("session:///info", "Session information", "application/json"));
        resources.add(resource("project:///contracts", "Contracts list", "application/json"));
        return resources;
    }

    private Map<String, Object> resource(String uri, String name, String mimeType) {
        Map<String, Object> r = Json.object();
        r.put("uri", uri);
        r.put("name", name);
        r.put("mimeType", mimeType);
        return r;
    }

    public Map<String, Object> readResource(String uri) {
        return switch (uri) {
        case "session:///info" -> readSessionInfo();
        case "project:///contracts" -> readContracts();
        default -> throw new McpToolException(-32002, "Resource not found: " + uri, null);
        };
    }

    private Map<String, Object> readSessionInfo() {
        Map<String, Object> result = Json.object();
        result.put("sessionId", session.getId());
        result.put("environmentLoaded", session.getEnvironment() != null);
        result.put("contractCount", session.getContracts().size());
        result.put("proofCount", session.getProofs().size());
        return result;
    }

    private Map<String, Object> readContracts() {
        return new McpToolRegistry(null, session).execute("key_contracts_list", Map.of());
    }
}
