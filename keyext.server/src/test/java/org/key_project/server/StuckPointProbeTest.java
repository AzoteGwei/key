/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.nparser.ParsingFacade;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.Statistics;
import de.uka.ilkd.key.proof.io.AbstractProblemLoader;
import de.uka.ilkd.key.rule.IBuiltInRuleApp;
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.speclang.Contract;

import org.key_project.prover.rules.RuleApp;
import org.key_project.prover.sequent.PosInOccurrence;
import org.key_project.prover.strategy.NewRuleListener;
import org.key_project.server.diagnostics.StuckPointProbe;
import org.key_project.server.dto.GoalDiagnostics;
import org.key_project.util.collection.ImmutableList;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two things the probe has to be, tested directly against KeY.
 *
 * <p>
 * It has to report what the automatic search would consider, and it has to leave the proof exactly
 * as it found it. Everything the diagnostics say rests on those, and neither can be checked from
 * the protocol surface.
 */
class StuckPointProbeTest {

    /**
     * Deeper than any formula could plausibly be, so the ceiling is out of reach.
     */
    private static final int UNBOUNDED = 10_000;

    @Test
    void reportsExactlyWhatKeYsOwnScanReports() throws Exception {
        Goal goal = stuckGoalOf("no-invariant", "Summer");

        List<String> mine = new ArrayList<>();
        for (StuckPointProbe.Candidate candidate : StuckPointProbe.scan(goal, UNBOUNDED)
                .candidates()) {
            mine.add(identify(candidate.app(), candidate.pos()));
        }

        List<String> keys = new ArrayList<>();
        goal.ruleAppIndex().builtInRuleAppIndex().scanApplicableRules(goal, new NewRuleListener() {
            @Override
            public void ruleAdded(RuleApp rule, PosInOccurrence pos) {
                if (rule instanceof IBuiltInRuleApp app) {
                    keys.add(identify(app, pos));
                }
            }

            @Override
            public void rulesAdded(ImmutableList<? extends RuleApp> rules, PosInOccurrence pos) {
                for (RuleApp rule : rules) {
                    ruleAdded(rule, pos);
                }
            }
        });

        // The bounded traversal exists only because KeY's cannot be bounded. If it ever stopped
        // agreeing with KeY's, the diagnostics would be reporting a second opinion about the goal
        // rather than the search's own.
        assertThat(mine).isNotEmpty();
        assertThat(mine).containsExactlyElementsOf(keys);
    }

    @Test
    void leavesTheProofExactlyAsItFoundIt() throws Exception {
        Goal goal = stuckGoalOf("no-invariant", "Summer");
        Proof proof = goal.proof();

        Statistics before = proof.getStatistics();
        int nodesBefore = proof.countNodes();
        int openBefore = proof.openGoals().size();
        int serialBefore = highestSerial(proof);

        GoalDiagnostics diagnostics = StuckPointProbe.probe(goal, UNBOUNDED);

        // forceInstantiate builds a rule application and the result is thrown away. If that ever
        // left a mark on the proof, every diagnostic call would quietly alter the thing it was
        // asked about.
        assertThat(diagnostics.stuckPoints()).isNotEmpty();
        assertThat(proof.countNodes()).isEqualTo(nodesBefore);
        assertThat(proof.openGoals()).hasSize(openBefore);
        assertThat(highestSerial(proof)).isEqualTo(serialBefore);
        assertThat(proof.closed()).isFalse();

        Statistics after = proof.getStatistics();
        assertThat(after.nodes).isEqualTo(before.nodes);
        assertThat(after.totalRuleApps).isEqualTo(before.totalRuleApps);
        assertThat(after.interactiveSteps).isEqualTo(before.interactiveSteps);
    }

    @Test
    void aShallowCeilingIsReportedRatherThanPassedOffAsComplete() throws Exception {
        Goal goal = stuckGoalOf("no-invariant", "Summer");

        GoalDiagnostics deep = StuckPointProbe.probe(goal, UNBOUNDED);
        GoalDiagnostics shallow = StuckPointProbe.probe(goal, 0);

        assertThat(deep.truncated()).isFalse();
        assertThat(shallow.truncated()).isTrue();
        // The default has to be generous enough that a real goal is answered in full; a diagnostic
        // that routinely comes back truncated would train an agent to ignore the flag.
        assertThat(StuckPointProbe.probe(goal, StuckPointProbe.DEFAULT_MAX_DEPTH).truncated())
                .isFalse();
    }

    private static String identify(IBuiltInRuleApp app, PosInOccurrence pos) {
        return app.rule().name() + "@" + (pos == null ? "<sequent>"
                : (pos.isInAntec() ? "A" : "S") + pos.posInTerm());
    }

    private static int highestSerial(Proof proof) {
        int highest = 0;
        for (Goal goal : proof.openGoals()) {
            highest = Math.max(highest, goal.node().serialNr());
        }
        return highest;
    }

    /**
     * Loads a fixture, symbolically executes it and hands back the goal it got stuck on.
     *
     * @param fixture the directory under {@code src/test/resources/fixtures}
     * @param className the class whose contract to prove
     * @return the single open goal left behind
     */
    private static Goal stuckGoalOf(String fixture, String className) throws Exception {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        ServerUserInterfaceControl control = new ServerUserInterfaceControl();
        AbstractProblemLoader loader =
            control.load(null, path, List.of(), null, List.of(), null, false, null);
        KeYEnvironment<ServerUserInterfaceControl> environment =
            new KeYEnvironment<>(control, loader.getInitConfig(), loader.getProof(),
                loader.getProofScript(), loader.getResult());
        Contract contract = environment.getSpecificationRepository().getAllContracts().stream()
                .filter(each -> each.getKJT().getFullName().equals(className)).findFirst()
                .orElseThrow();
        Proof proof = environment.createProof(contract.createProofObl(environment.getInitConfig()));

        ProofScriptEngine engine = new ProofScriptEngine(proof);
        engine.setInitiallySelectedGoal(proof.openGoals().head());
        engine.execute(control, ParsingFacade.parseScript("macro \"symbex\";"));

        assertThat(proof.closed()).isFalse();
        assertThat(proof.openGoals()).hasSize(1);
        return proof.openGoals().head();
    }
}
