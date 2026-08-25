/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

/**
 * Exception carrying an MCP error code for tool execution failures.
 */
public class McpToolException extends RuntimeException {
    private final int code;
    private final Object data;

    public McpToolException(int code, String message, Object data) {
        super(message);
        this.code = code;
        this.data = data;
    }

    public int code() {
        return code;
    }

    public Object data() {
        return data;
    }
}
