/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.smt.SMTSolverResult.ThreeValuedTruth;
import de.uka.ilkd.key.smt.solvertypes.SolverType;
import de.uka.ilkd.key.smt.solvertypes.SolverTypes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the external tool a proof would depend on is not there.
 *
 * <p>
 * A missing or unrunnable SMT solver is the most likely way for this server to end up in a
 * degraded environment without anyone noticing: nothing crashes, a goal simply never closes. The
 * failure that would matter is not the missing binary, it is a pipeline that reads "the solver
 * said nothing" as "the solver said yes".
 *
 * <p>
 * So this drives KeY's real solver machinery with a real path that cannot be executed, rather than
 * checking that the server refuses the call up front. A guard clause tested against itself proves
 * nothing; the interesting question is what the stack does when it genuinely tries and genuinely
 * fails. {@link SmtAvailabilityProbeTest} is the control that keeps this test honest.
 *
 * <p>
 * {@link SolverTypes} hands out process-wide singletons, so pointing one at a bad path stays
 * pointed for the life of the JVM. This class therefore runs in the forked {@code
 * smtDegradationTest} task and must never join the main test task.
 */
class SmtDegradationTest {

    @Test
    void aSolverThatCannotBeExecutedNeverYieldsAProof() throws Exception {
        Path missing = Path.of("/nonexistent").resolve("keyext-server-no-such-solver");
        assertThat(Files.exists(missing)).isFalse();

        Collection<SolverType> solvers = SolverTypes.getSolverTypes();
        assertThat(solvers).isNotEmpty();
        for (SolverType solver : solvers) {
            solver.setSolverCommand(missing.toString());
        }
        SolverType z3 = SmtFixture.solver();
        // KeY's own availability check has to agree that the environment is degraded, otherwise
        // the rest of this test would be proving nothing.
        assertThat(z3.isInstalled(true)).isFalse();

        Proof proof = SmtFixture.loadValidProblem();
        assertThat(proof).isNotNull();
        assertThat(proof.closed()).isFalse();

        ThreeValuedTruth answer = SmtFixture.ask(proof, List.of(z3)).isValid();

        // The problem is valid, so a working solver answers VALID here — see the control test. A
        // broken one must not, and must not be mistaken for one either.
        assertThat(answer).isNotEqualTo(ThreeValuedTruth.VALID);

        // And the only statement this server makes about verification stays negative.
        assertThat(proof.openGoals().head().node().isClosed()).isFalse();
        assertThat(proof.closed()).isFalse();
        assertThat(ProofFacts.describe(proof).closed()).isFalse();
        assertThat(ProofFacts.describe(proof).openGoals()).isPositive();
    }
}
