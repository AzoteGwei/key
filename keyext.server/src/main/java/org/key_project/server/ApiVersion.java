/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

/**
 * The version of the RPC surface.
 *
 * <p>
 * This is the number clients check for compatibility, and it is independent of the KeY version the
 * server embeds. It must match the {@code info.version} of the OpenRPC document and follow semantic
 * versioning: any change to a method's parameters, result shape or error behaviour bumps it.
 */
public final class ApiVersion {

    /** The API version this build speaks. */
    public static final String CURRENT = "0.1.0";

    private ApiVersion() {
    }
}
