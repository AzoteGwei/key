/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.Statistics;

import org.key_project.server.dto.ProofStatistics;

/**
 * The one place in this server that is allowed to state whether a proof is closed.
 *
 * <p>
 * Everything the protocol says about verification funnels through {@link #describe(Proof)}, and
 * the only way that method can report {@code closed} is by asking KeY's own
 * {@link Proof#closed()}. There is deliberately no other constructor, no setter, no cache and no
 * argument by which a caller could supply the answer instead: a task finishing without throwing,
 * a macro running to its end or an error branch being taken must never be able to turn into a
 * claim of success.
 */
public final class ProofFacts {

    private ProofFacts() {
    }

    /**
     * Reads the current state of a proof.
     *
     * @param proof the proof to inspect
     * @return its statistics, with {@code closed} taken from {@link Proof#closed()}
     */
    public static ProofStatistics describe(Proof proof) {
        Statistics statistics = proof.getStatistics();
        return new ProofStatistics(proof.closed(), proof.openGoals().size(), statistics.nodes,
            statistics.branches, statistics.totalRuleApps, statistics.interactiveSteps,
            statistics.symbExApps, statistics.smtSolverApps, statistics.loopInvApps,
            statistics.operationContractApps, statistics.dependencyContractApps,
            statistics.blockLoopContractApps, statistics.autoModeTimeInMillis);
    }
}
