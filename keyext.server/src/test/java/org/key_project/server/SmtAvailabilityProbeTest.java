/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.util.List;

import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.smt.SMTSolverResult.ThreeValuedTruth;
import de.uka.ilkd.key.smt.solvertypes.SolverType;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The control for {@link SmtDegradationTest}.
 *
 * <p>
 * A test asserting "the solver did not answer VALID" passes just as happily when the solver was
 * never really consulted — because the fixture stopped being translatable, say, or because the
 * launcher changed shape underneath it. The degradation test would then stay green while having
 * quietly stopped testing anything, which is exactly the sort of rot it exists to catch elsewhere.
 *
 * <p>
 * So this poses the same problem to the same launcher with the solver left alone, and requires the
 * answer to be VALID. Where no solver is installed there is nothing to control against and the
 * test is skipped; CI installs one, so it runs there.
 *
 * <p>
 * It lives in its own class on purpose: the forked test task restarts the JVM between classes, so
 * this never shares a process with the one that sabotages the solver singletons.
 */
class SmtAvailabilityProbeTest {

    @Test
    void aWorkingSolverProvesTheProblemTheDegradedOneCannot() throws Exception {
        SolverType z3 = SmtFixture.solver();
        assumeTrue(z3.isInstalled(true), "no " + SmtFixture.SOLVER_NAME + " on this machine");

        Proof proof = SmtFixture.loadValidProblem();
        assertThat(proof).isNotNull();

        assertThat(SmtFixture.ask(proof, List.of(z3)).isValid())
                .isEqualTo(ThreeValuedTruth.VALID);
    }
}
