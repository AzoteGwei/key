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

    /**
     * The API version this build speaks.
     *
     * <p>
     * Frozen at 1.0.0. Within 1.x nothing already published may be taken away or given a new
     * meaning: no method removed, no field removed, no field redefined, and enumerations gain
     * values but never lose them. The protocol changed twelve times while it was being found;
     * anything built on it now needs it to stop moving.
     */
    public static final String CURRENT = "1.0.0";

    private ApiVersion() {
    }
}
