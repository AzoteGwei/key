/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import de.uka.ilkd.key.control.ProofControl;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.rule.TacletApp;

import org.key_project.logic.PosInTerm;
import org.key_project.prover.sequent.PosInOccurrence;
import org.key_project.prover.sequent.Semisequent;
import org.key_project.prover.sequent.SequentFormula;
import org.key_project.server.dto.ApplicableRule;
import org.key_project.server.dto.GoalRules;
import org.key_project.server.dto.RuleKind;
import org.key_project.server.dto.SequentSide;
import org.key_project.util.collection.ImmutableList;

import org.jspecify.annotations.Nullable;

/**
 * Asks a goal which rules a person could still apply to it.
 *
 * <p>
 * {@link StuckPointProbe} answers what wants to apply and cannot. This answers the other half:
 * what could apply and was not chosen. Those are different questions, and after a search that
 * ended {@code EXHAUSTED} with nothing stuck, the second is the only one left — the automatic
 * strategy declined these rules, and a person picking one is how such a proof normally continues.
 *
 * <p>
 * The identifiers reported are taclet names, which is what a proof script's {@code rule} command
 * takes. That does not make every one applicable by name alone, and what is known about why is
 * reported rather than left to be discovered from a failed script: a rule found at several
 * positions is listed once per position, and one still needing schema variables or an
 * {@code \assumes} instantiation is marked as needing them. Those are the obstacles that can be
 * seen from here — only the top level of each formula is surveyed, so a rule listed once may
 * still match inside a term. The list is candidates, not promises.
 *
 * <p>
 * Enumeration goes through {@link ProofControl}, the same entry point KeY's own context menu uses,
 * so the list is the one a person would be offered rather than a second opinion assembled here.
 * Only the top level of each formula is examined: naming a position inside a term needs a wire
 * representation the protocol does not have yet, and the rules that apply at the top are the
 * structural ones a person reaches for anyway.
 */
public final class ApplicableRuleProbe {

    /**
     * How many rules are reported before the answer is cut short.
     *
     * <p>
     * Measured rather than guessed: the stuck goals of this project's fixtures offer 57 to 65
     * rules and take about a millisecond to enumerate. This leaves room for a considerably busier
     * goal while keeping the answer to something that can be read.
     */
    public static final int DEFAULT_MAX_RULES = 200;

    private ApplicableRuleProbe() {
    }

    /**
     * Lists what can be applied to a goal.
     *
     * <p>
     * Read-only. Nothing here applies anything; the rule applications built during enumeration
     * are inspected for their names and discarded.
     *
     * @param control the proof control to enumerate through
     * @param goal the goal to examine
     * @param maxRules how many to report before stopping
     * @return the rules, and whether there were more
     */
    public static GoalRules probe(ProofControl control, Goal goal, int maxRules) {
        // Keyed by rule *and* position, not by rule. Collapsing the positions would hide the
        // multiplicity, and the multiplicity is what decides whether a script can name the rule
        // and stop there.
        Set<String> seen = new LinkedHashSet<>();
        List<ApplicableRule> found = new ArrayList<>();
        boolean truncated = false;

        truncated = add(control.getNoFindTaclet(goal), RuleKind.NO_FIND, null, null, seen, found,
            maxRules);
        if (!truncated) {
            truncated = scanSide(control, goal, goal.sequent().antecedent(), true,
                SequentSide.ANTECEDENT, seen, found, maxRules);
        }
        if (!truncated) {
            truncated = scanSide(control, goal, goal.sequent().succedent(), false,
                SequentSide.SUCCEDENT, seen, found, maxRules);
        }
        return new GoalRules(goal.node().serialNr(), List.copyOf(found), truncated);
    }

    private static boolean scanSide(ProofControl control, Goal goal, Semisequent side,
            boolean antecedent, SequentSide label, Set<String> seen, List<ApplicableRule> found,
            int maxRules) {
        int index = 0;
        for (SequentFormula formula : side) {
            PosInOccurrence position =
                new PosInOccurrence(formula, PosInTerm.getTopLevel(), antecedent);
            if (add(control.getFindTaclet(goal, position), RuleKind.FIND, label, index, seen,
                found, maxRules)) {
                return true;
            }
            if (add(control.getRewriteTaclet(goal, position), RuleKind.REWRITE, label, index, seen,
                found, maxRules)) {
                return true;
            }
            index++;
        }
        return false;
    }

    /**
     * Records the rules of one batch.
     *
     * @return {@code true} when the limit was reached and something was left out
     */
    /**
     * Whether the rule has an assumption it has not been told how to satisfy.
     *
     * <p>
     * KeY offers such a rule because the assumption <em>can</em> be satisfied by something on the
     * sequent, but the application itself does not carry the choice, so a script naming the rule
     * and nothing else is refused.
     *
     * @param app the offered application
     * @return {@code true} when a script would have to say which formula satisfies the assumption
     */
    private static boolean needsAssumption(TacletApp app) {
        return !app.taclet().assumesSequent().isEmpty()
                && app.assumesFormulaInstantiations() == null;
    }

    private static boolean add(ImmutableList<TacletApp> apps, RuleKind kind,
            @Nullable SequentSide side, @Nullable Integer index, Set<String> seen,
            List<ApplicableRule> found, int maxRules) {
        for (TacletApp app : apps) {
            String name = app.taclet().name().toString();
            if (!seen.add(name + "@" + side + ":" + index + ":" + kind)) {
                continue;
            }
            if (found.size() >= maxRules) {
                return true;
            }
            found.add(new ApplicableRule(name, kind, side, index,
                !app.uninstantiatedVars().isEmpty(), needsAssumption(app)));
        }
        return false;
    }
}
