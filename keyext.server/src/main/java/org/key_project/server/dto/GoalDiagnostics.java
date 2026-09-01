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
 * <p>
 * Which is why {@code lastSearchOutcome} is here. An empty list on a proof whose last search came
 * back {@code EXHAUSTED} means the prover tried and ran out of ideas: what is needed is a script,
 * a solver or an interactive step. The same empty list on a proof whose search hit a limit means
 * something much less interesting — it never finished looking. Without this the two are
 * indistinguishable, and they call for opposite responses.
 *
 * @param goalId the goal these findings are about
 * @param stuckPoints rules that want to apply and cannot, possibly empty
 * @param truncated whether the probe stopped descending before it ran out of term to look at
 * @param lastSearchOutcome how the last automatic search on this proof ended, or {@code null}
 *        when none has been run
 */
@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record GoalDiagnostics(int goalId, List<StuckPoint> stuckPoints, boolean truncated,
        AutoModeOutcome lastSearchOutcome) {
}
