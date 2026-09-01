/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.prover;

import java.nio.file.Path;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.init.JavaProfile;
import de.uka.ilkd.key.util.HelperClassForTests;
import de.uka.ilkd.key.util.ProofStarter;

import org.key_project.prover.engine.ProofSearchInformation;
import org.key_project.prover.engine.StopReason;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the distinction the search's own message cannot make.
 *
 * <p>
 * {@code AppliedRuleStopCondition} reports both of its endings with one sentence, "Maximal number
 * of rule applications reached or timed out.", which leaves a caller unable to tell a search that
 * ran out of budget from one that ran out of ideas. The two call for opposite responses — raise
 * the limit, or stop raising it and help the prover — so {@link ProofSearchInformation#stopReason}
 * has to keep them apart.
 */
public class StopReasonTest {

    private static Proof load() throws Exception {
        Path key = HelperClassForTests.TESTCASE_DIRECTORY.resolve("naming")
                .resolve("skolemSiblings.key");
        KeYEnvironment<?> env =
            KeYEnvironment.load(JavaProfile.getDefaultInstance(), key, null, null, null, true);
        Proof proof = env.getLoadedProof();
        assertNotNull(proof);
        return proof;
    }

    @Test
    public void aSearchStoppedByItsRuleLimitSaysSo() throws Exception {
        Proof proof = load();
        ProofStarter starter = new ProofStarter(false);
        starter.init(proof);
        // One rule. Whatever this problem needs, it is not going to be finished, so the search
        // must report the limit rather than an ending it did not reach.
        starter.setMaxRuleApplications(1);

        ProofSearchInformation<Proof, Goal> result = starter.start();

        assertEquals(StopReason.MAX_RULES, result.stopReason());
        assertTrue(result.reason().contains("Maximal number of rule applications reached"),
            "the prose is unchanged, only the code beside it is new");
    }

    @Test
    public void aSearchWithNothingLeftToTrySaysSomethingElse() throws Exception {
        Proof proof = load();
        ProofStarter starter = new ProofStarter(false);
        starter.init(proof);
        starter.setMaxRuleApplications(100_000);

        ProofSearchInformation<Proof, Goal> result = starter.start();

        // The prover did everything it knows. This is the ending that means more budget will not
        // help, and it has to be distinguishable from the one above even though a caller reading
        // only the message could not tell.
        assertEquals(StopReason.EXHAUSTED, result.stopReason());
    }

    @Test
    public void theEndingSaysNothingAboutWhetherTheProofClosed() throws Exception {
        Proof proof = load();
        ProofStarter starter = new ProofStarter(false);
        starter.init(proof);
        starter.setMaxRuleApplications(100_000);

        ProofSearchInformation<Proof, Goal> result = starter.start();

        // A closed proof and an abandoned one both end with nothing left to apply. Only the proof
        // answers the other question, and nothing here may be read as answering it.
        assertEquals(StopReason.EXHAUSTED, result.stopReason());
        assertEquals(proof.openGoals().isEmpty(), proof.closed());
    }
}
