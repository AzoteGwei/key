/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.GoalRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code diagnostics.explainGoal}.
 *
 * @param goal the goal to examine
 * @param maxDepth how deep into each formula to look, default
 *        {@code StuckPointProbe.DEFAULT_MAX_DEPTH}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ExplainGoalRequest(GoalRef goal, @Nullable Integer maxDepth) {
}
