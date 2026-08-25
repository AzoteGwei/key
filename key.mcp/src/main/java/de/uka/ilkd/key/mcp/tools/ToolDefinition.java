/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.util.Map;

/**
 * A single MCP tool: its JSON schema and the executor producing the tool result.
 */
public record ToolDefinition(Map<String, Object> schema, ToolExecutor executor) {

    @FunctionalInterface
    public interface ToolExecutor {
        Map<String, Object> execute(Map<String, Object> params);
    }
}
