/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * What is known about a proof right now.
 *
 * <p>
 * {@code closed} is the only field that answers "is this verified", and it is read straight from
 * KeY's {@code Proof.closed()} every time this record is built. It is never cached, defaulted or
 * inferred from whether some task finished.
 *
 * @param closed whether KeY considers the proof closed
 * @param openGoals number of goals still open
 * @param nodes total nodes in the proof tree
 * @param branches total branches
 * @param totalRuleApps rule applications of any kind
 * @param interactiveSteps rule applications made interactively
 * @param symbExApps symbolic execution steps
 * @param smtSolverApps goals closed by an SMT solver
 * @param loopInvApps loop invariant applications
 * @param operationContractApps method contract applications
 * @param dependencyContractApps dependency contract applications
 * @param blockLoopContractApps block and loop contract applications
 * @param autoModeTimeMs milliseconds spent in automatic proof search
 */
public record ProofStatistics(boolean closed, int openGoals, int nodes, int branches,
        int totalRuleApps, int interactiveSteps, int symbExApps, int smtSolverApps,
        int loopInvApps, int operationContractApps, int dependencyContractApps,
        int blockLoopContractApps, long autoModeTimeMs) {
}
