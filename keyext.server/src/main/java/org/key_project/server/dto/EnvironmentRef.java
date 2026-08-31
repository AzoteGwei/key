/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Opaque handle on a loaded environment.
 *
 * <p>
 * Clients must not parse or construct the identifier.
 *
 * @param envId the identifier issued by the server
 */
public record EnvironmentRef(String envId) {
}
