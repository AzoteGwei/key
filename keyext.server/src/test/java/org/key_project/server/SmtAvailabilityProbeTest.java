/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.util.List;

import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.smt.SMTSolverResult.ThreeValuedTruth;
import de.uka.ilkd.key.smt.solvertypes.SolverType;

import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * The control for the degraded case seen through the protocol.
     *
     * <p>
     * With the solver left alone, the script that
     * {@code SmtDegradationTest#aClientRunningTheSmtScriptCommandIsToldItDidNotWork} runs has to
     * close the proof, and {@code smtSolverApps} has to show that a solver is what closed it.
     * Without this, that test would keep passing if the fixture stopped reaching a solver at all.
     */
    @Test
    void theSmtScriptCommandClosesTheProofWhenTheSolverWorks() throws Exception {
        assumeTrue(SmtFixture.solver().isInstalled(true),
            "no " + SmtFixture.SOLVER_NAME + " on this machine");

        try (KeyServerInstance instance =
            new KeyServerInstance(new ServerOptions(0, Path.of(""), 0, 1))) {
            instance.start();
            JsonNode statistics =
                new SmtRpcFixture(new RpcTestClient(instance.port())).proveWithSmt();

            assertThat(statistics.get("closed").asBoolean()).isTrue();
            assertThat(statistics.get("openGoals").asInt()).isZero();
            assertThat(statistics.get("smtSolverApps").asInt()).isPositive();
        }
    }
}
