/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.protocol;

import java.util.Map;

/**
 * A parsed MCP JSON-RPC response.
 */
public record McpResponse(Object id, Object result, McpError error) {
}
