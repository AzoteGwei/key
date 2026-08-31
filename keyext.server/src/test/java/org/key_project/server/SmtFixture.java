/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.io.AbstractProblemLoader;
import de.uka.ilkd.key.settings.DefaultSMTSettings;
import de.uka.ilkd.key.settings.ProofIndependentSettings;
import de.uka.ilkd.key.smt.SMTProblem;
import de.uka.ilkd.key.smt.SMTSolverResult;
import de.uka.ilkd.key.smt.SolverLauncher;
import de.uka.ilkd.key.smt.solvertypes.SolverType;
import de.uka.ilkd.key.smt.solvertypes.SolverTypes;

/**
 * Shared setup for the two SMT tests, so both put the same problem through the same launcher and
 * differ only in whether the solver can be executed.
 */
final class SmtFixture {

    /**
     * The solver these tests drive.
     *
     * <p>
     * One named solver rather than everything installed: {@code Z3_CE} searches for counter
     * examples, so its answers are the mirror image of {@code Z3}'s, and asking both about one
     * goal makes {@code SMTProblem.getFinalResult()} throw on the contradiction. KeY's own
     * {@code smt} script command defaults to this same solver for the same reason.
     */
    static final String SOLVER_NAME = "Z3";

    private static final Path PROBLEM =
        Path.of("src/test/resources/fixtures/smt/valid.key").toAbsolutePath();

    private SmtFixture() {
    }

    /**
     * The solver type these tests use.
     *
     * @return KeY's singleton for {@value #SOLVER_NAME}
     */
    static SolverType solver() {
        return SolverTypes.getSolverTypes().stream()
                .filter(type -> SOLVER_NAME.equals(type.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("KeY no longer defines a solver named "
                    + SOLVER_NAME + "; these tests need updating"));
    }

    /**
     * Loads the first-order problem both tests pose.
     *
     * @return an open proof of a valid, translatable sequent
     */
    static Proof loadValidProblem() throws Exception {
        AbstractProblemLoader loader = new ServerUserInterfaceControl().load(null, PROBLEM,
            List.of(), null, List.of(), null, false, null);
        return loader.getProof();
    }

    /**
     * Hands the proof's first open goal to the given solvers and waits for them.
     *
     * @param proof the proof whose goal to pose
     * @param solvers the solver types to launch
     * @return what the solvers made of it
     */
    static SMTSolverResult ask(Proof proof, Collection<SolverType> solvers) {
        Goal goal = proof.openGoals().head();
        SMTProblem problem = new SMTProblem(goal);
        DefaultSMTSettings settings = new DefaultSMTSettings(proof.getSettings().getSMTSettings(),
            ProofIndependentSettings.DEFAULT_INSTANCE.getSMTSettings(),
            proof.getSettings().getNewSMTSettings(), proof);

        new SolverLauncher(settings).launch(solvers, List.of(problem), proof.getServices());
        return problem.getFinalResult();
    }
}
