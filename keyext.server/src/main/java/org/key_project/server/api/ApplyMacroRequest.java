/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.GoalRef;
import org.key_project.server.dto.ProofRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code goal.applyMacro}.
 *
 * @param proof the proof to work on
 * @param goal the goal to start from; when {@code null} the macro is applied to the whole proof
 * @param macroId the macro to run, as reported by {@code goal.listAvailableMacros}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApplyMacroRequest(ProofRef proof, @Nullable GoalRef goal, String macroId) {
}
