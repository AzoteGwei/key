/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.EnvironmentRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of {@code proof.start}.
 *
 * @param env the environment holding the contract
 * @param contractId the contract to prove, as reported by
 *        {@code environment.listProofObligations}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record StartProofRequest(EnvironmentRef env, String contractId) {
}
