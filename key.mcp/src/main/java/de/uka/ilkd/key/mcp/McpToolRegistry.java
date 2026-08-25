/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.mcp.tools.OperationToolHandler;
import de.uka.ilkd.key.mcp.tools.ProjectToolHandler;
import de.uka.ilkd.key.mcp.tools.ProofExportToolHandler;
import de.uka.ilkd.key.mcp.tools.ProofToolHandler;
import de.uka.ilkd.key.mcp.tools.SessionToolHandler;
import de.uka.ilkd.key.mcp.tools.ToolContext;
import de.uka.ilkd.key.mcp.tools.ToolDefinition;

/**
 * Registry for MCP tools backed by a KeY session. The actual tools live in the
 * {@link de.uka.ilkd.key.mcp.tools} package and are grouped by domain.
 */
public class McpToolRegistry {
    private final Map<String, ToolDefinition> tools = new LinkedHashMap<>();

    public McpToolRegistry(McpServerConfig config, McpSession session) {
        ToolContext ctx = new ToolContext(config, session);
        new SessionToolHandler(ctx).registerTools(tools);
        new ProjectToolHandler(ctx).registerTools(tools);
        new ProofToolHandler(ctx).registerTools(tools);
        new OperationToolHandler(ctx).registerTools(tools);
        new ProofExportToolHandler(ctx).registerTools(tools);
    }

    public List<Map<String, Object>> listTools() {
        return tools.values().stream().map(ToolDefinition::schema).toList();
    }

    public Map<String, Object> execute(String name, Map<String, Object> params) {
        ToolDefinition tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Tool not implemented: " + name);
        }
        return tool.executor().execute(params);
    }
}
