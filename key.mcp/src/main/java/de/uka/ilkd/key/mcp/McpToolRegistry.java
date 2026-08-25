/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.proof.io.ProblemLoaderException;
import de.uka.ilkd.key.speclang.Contract;

/**
 * Registry for MCP tools backed by a KeY session.
 */
public class McpToolRegistry {
    private final McpServerConfig config;
    private final McpSession session;

    public McpToolRegistry(McpServerConfig config, McpSession session) {
        this.config = config;
        this.session = session;
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(sessionInfo());
        tools.add(sessionReset());
        tools.add(sessionDispose());
        tools.add(projectLoad());
        tools.add(contractsList());
        return tools;
    }

    public Map<String, Object> execute(String name, Map<String, Object> params) {
        return switch (name) {
        case "key_session_info" -> handleSessionInfo(params);
        case "key_session_reset" -> handleSessionReset(params);
        case "key_session_dispose" -> handleSessionDispose(params);
        case "key_project_load" -> handleProjectLoad(params);
        case "key_contracts_list" -> handleContractsList(params);
        default -> throw new IllegalArgumentException("Tool not implemented: " + name);
        };
    }

    private Map<String, Object> sessionInfo() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_info");
        schema.put("description", "Get information about the current MCP session.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> sessionReset() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_reset");
        schema.put("description", "Reset the session, disposing all proofs and the current KeY environment.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> sessionDispose() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_dispose");
        schema.put("description", "Dispose the session and release all resources.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> projectLoad() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_project_load");
        schema.put("description", "Load a KeY project from a directory or .key file.");

        Map<String, Object> properties = Json.object();
        properties.put("location", Map.of("type", "string", "description", "Path to the project directory or .key file"));
        properties.put("classPaths", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("bootClassPath", Map.of("type", "string"));
        properties.put("includes", Map.of("type", "array", "items", Map.of("type", "string")));

        schema.put("inputSchema", Map.of("type", "object", "properties", properties, "required", List.of("location")));
        return schema;
    }

    private Map<String, Object> contractsList() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_contracts_list");
        schema.put("description", "List all verification contracts in the loaded project.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> handleSessionInfo(Map<String, Object> params) {
        Map<String, Object> result = Json.object();
        result.put("sessionId", session.getId());
        result.put("environmentLoaded", session.getEnvironment() != null);
        result.put("contractCount", session.getContracts().size());
        result.put("proofCount", session.getProofs().size());
        return result;
    }

    private Map<String, Object> handleSessionReset(Map<String, Object> params) {
        session.dispose();
        return Map.of("success", true);
    }

    private Map<String, Object> handleSessionDispose(Map<String, Object> params) {
        session.dispose();
        return Map.of("success", true, "disposed", true);
    }

    private Map<String, Object> handleProjectLoad(Map<String, Object> params) {
        String location = (String) params.get("location");
        Path projectPath = PathValidator.resolveAndValidate(location, config.workspace(), config.allowedPaths());

        List<Path> classPaths = toPathList(params.get("classPaths"));
        Path bootClassPath = toPath(params.get("bootClassPath"));
        List<Path> includes = toPathList(params.get("includes"));

        try {
            KeYEnvironment<?> env = KeYEnvironment.load(projectPath, classPaths, bootClassPath, includes);
            session.dispose();
            session.setEnvironment(env);
            session.loadContracts();

            Map<String, Object> result = Json.object();
            result.put("success", true);
            result.put("loadedTypes", env.getJavaInfo().getAllKeYJavaTypes().size());
            result.put("contractCount", session.getContracts().size());
            return result;
        } catch (ProblemLoaderException e) {
            throw new McpToolException(-32603, "Failed to load project: " + e.getMessage(), e.getMessage());
        }
    }

    private Map<String, Object> handleContractsList(Map<String, Object> params) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Contract> entry : session.getContracts().entrySet()) {
            Map<String, Object> item = Json.object();
            item.put("contractId", entry.getKey());
            item.put("targetName", entry.getValue().getTarget().name().toString());
            item.put("displayName", entry.getValue().getDisplayName());
            item.put("type", entry.getValue().getClass().getSimpleName());
            list.add(item);
        }
        return Map.of("contracts", list);
    }

    private static List<Path> toPathList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<Path> paths = new ArrayList<>();
            for (Object o : list) {
                paths.add(Path.of(o.toString()));
            }
            return paths;
        }
        return null;
    }

    private static Path toPath(Object value) {
        if (value == null) {
            return null;
        }
        return Path.of(value.toString());
    }
}
