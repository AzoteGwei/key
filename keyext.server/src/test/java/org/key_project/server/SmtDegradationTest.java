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

import com.fasterxml.jackson.databind.JsonNode;
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

    /**
     * Points every solver KeY knows about at a path that cannot be executed.
     *
     * @return the solver the tests then ask
     */
    private static SolverType breakEverySolver() {
        // Under the temporary directory, so it is absent on every platform rather than only on
        // the one whose root layout was assumed.
        Path missing = Path.of(System.getProperty("java.io.tmpdir"))
                .resolve("keyext-server-no-such-solver-" + System.nanoTime());
        assertThat(Files.exists(missing)).isFalse();

        Collection<SolverType> solvers = SolverTypes.getSolverTypes();
        assertThat(solvers).isNotEmpty();
        for (SolverType solver : solvers) {
            solver.setSolverCommand(missing.toString());
        }
        return SmtFixture.solver();
    }

    @Test
    void aSolverThatCannotBeExecutedNeverYieldsAProof() throws Exception {
        SolverType z3 = breakEverySolver();
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

    /**
     * The same degradation, seen from where a client stands.
     *
     * <p>
     * The test above establishes that KeY's launcher does not invent a result. This one asks the
     * question the protocol actually exposes: a client runs the script that proves this fixture
     * when a solver is present, and has to be told plainly that it did not work. Nothing in the
     * server's own code is mocked or short-circuited — the request goes over HTTP and the
     * {@code smt} command really tries to start a process.
     *
     * <p>
     * {@link SmtAvailabilityProbeTest} runs this same script with the solver left alone and
     * requires the proof to close, so the difference asserted here is genuinely the solver.
     */
    @Test
    void aClientRunningTheSmtScriptCommandIsToldItDidNotWork() throws Exception {
        breakEverySolver();

        try (KeyServerInstance instance = TestServer.start()) {
            JsonNode statistics =
                new SmtRpcFixture(new RpcTestClient(instance.port())).proveWithSmt();

            assertThat(statistics.get("closed").asBoolean()).isFalse();
            assertThat(statistics.get("openGoals").asInt()).isPositive();
            // No solver ran, so nothing may be counted as having been closed by one.
            assertThat(statistics.get("smtSolverApps").asInt()).isZero();
        }
    }
}
