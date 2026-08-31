/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.GoalRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of {@code goal.applyScript}.
 *
 * @param goal the goal the script starts on
 * @param script the proof script source, in KeY's own script syntax
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ApplyScriptRequest(GoalRef goal, String script) {
}
