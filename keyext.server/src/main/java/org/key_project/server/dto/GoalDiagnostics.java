/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import java.util.List;

/**
 * What is standing in the way of one goal.
 *
 * <p>
 * An empty {@code stuckPoints} list is a finding, not a failure to find: it means no built-in rule
 * even applies here, so the goal is not waiting on a missing specification. Usually that means it
 * is simply not provable — which, for an agent, points at the specification or the code being
 * wrong rather than incomplete.
 *
 * @param goalId the goal these findings are about
 * @param stuckPoints rules that want to apply and cannot, possibly empty
 * @param truncated whether the probe stopped descending before it ran out of term to look at
 */
public record GoalDiagnostics(int goalId, List<StuckPoint> stuckPoints, boolean truncated) {
}
