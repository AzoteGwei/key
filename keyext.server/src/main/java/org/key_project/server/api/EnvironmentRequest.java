/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.EnvironmentRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of the methods that address an existing environment.
 *
 * @param env the environment to act on
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record EnvironmentRequest(EnvironmentRef env) {
}
