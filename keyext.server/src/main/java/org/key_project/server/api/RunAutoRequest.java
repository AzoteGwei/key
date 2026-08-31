/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.ProofRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code proof.runAuto}.
 *
 * @param proof the proof to work on
 * @param timeoutMs wall-clock budget for the search; when it elapses the search is interrupted
 *        and whatever it reached is reported. {@code null} means no budget
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record RunAutoRequest(ProofRef proof, @Nullable Long timeoutMs) {
}
