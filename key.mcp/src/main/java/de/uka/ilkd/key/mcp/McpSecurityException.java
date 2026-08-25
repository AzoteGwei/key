/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

/**
 * Exception thrown when a request violates the security policy.
 */
public class McpSecurityException extends RuntimeException {
    public McpSecurityException(String message) {
        super(message);
    }
}
