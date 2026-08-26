/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

/**
 * Exception thrown when a request violates the security policy.
 */
public class McpSecurityException extends RuntimeException {
    /**
     * Legacy-range, implementation-defined error code for security policy violations.
     * On the wire it is translated per {@code McpProtocol.wireErrorCode} when serving
     * modern (2026-07-28) requests.
     */
    public static final int CODE = -32001;

    public McpSecurityException(String message) {
        super(message);
    }
}
