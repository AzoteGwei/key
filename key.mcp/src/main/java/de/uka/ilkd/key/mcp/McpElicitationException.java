/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

/**
 * Thrown when an elicitation confirmation is declined, cancelled, or times out.
 */
public class McpElicitationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public McpElicitationException(String message) {
        super(message);
    }
}
