/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.diagnostics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.control.ProofControl;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.RuleAppIndex;
import de.uka.ilkd.key.rule.PosTacletApp;
import de.uka.ilkd.key.rule.RewriteTaclet;
import de.uka.ilkd.key.rule.TacletApp;

import org.key_project.logic.PosInTerm;
import org.key_project.logic.op.sv.SchemaVariable;
import org.key_project.prover.proof.rulefilter.TacletFilter;
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
 * Asks a goal which rules a person could still apply to it, and how to write each one down.
 *
 * <p>
 * {@link StuckPointProbe} answers what wants to apply and cannot. This answers the other half:
 * what could apply and was not chosen. Those are different questions, and after a search that
 * ended {@code EXHAUSTED} with nothing stuck, the second is the only one left — the automatic
 * strategy declined these rules, and a person picking one is how such a proof normally continues.
 *
 * <p>
 * The enumeration deliberately mirrors the {@code rule} proof-script command's own
 * ({@code RuleCommand.findAllTacletApps}): each formula of the antecedent and then the succedent,
 * scanned at and below the top level, keeping the applications that carry a position. Mirroring
 * it is the point. It means the count of matches this reports is the count that command will
 * find, so the occurrence number reported is the one it will accept — and a rule that matches
 * more than once, which is a third to a half of them, becomes writable instead of merely
 * mentionable.
 */
public final class ApplicableRuleProbe {

    /**
     * How many distinct rules are reported before the answer is cut short.
     *
     * <p>
     * Measured rather than guessed: the stuck goals of this project's fixtures offer 49 to 66
     * distinct rules over 133 to 269 matches, enumerated in two to five milliseconds. This leaves
     * room for a busier goal while keeping the answer to something that can be read.
     */
    public static final int DEFAULT_MAX_RULES = 200;

    private ApplicableRuleProbe() {
    }

    /**
     * Lists what can be applied to a goal.
     *
     * <p>
     * Read-only as far as the proof is concerned. Applications are built to be inspected and
     * discarded; nothing is applied.
     *
     * @param control the proof control, used for the rules that need no position
     * @param goal the goal to examine
     * @param maxRules how many distinct rules to report before stopping
     * @return the rules, and whether there were more
     */
    public static GoalRules probe(ProofControl control, Goal goal, int maxRules) {
        List<ApplicableRule> found = new ArrayList<>();
        boolean truncated = collectNoFind(control, goal, found, maxRules);
        if (!truncated) {
            truncated = collectPositioned(goal, found, maxRules);
        }
        return new GoalRules(goal.node().serialNr(), List.copyOf(found), truncated);
    }

    /**
     * Collects the rules that apply to the goal rather than to any position in it.
     *
     * <p>
     * These never need an occurrence: the script command reaches them by a different path that
     * does not count matches at all.
     */
    private static boolean collectNoFind(ProofControl control, Goal goal,
            List<ApplicableRule> found, int maxRules) {
        for (TacletApp app : control.getNoFindTaclet(goal)) {
            if (found.size() >= maxRules) {
                return true;
            }
            // Never with a script line. The rule command builds a positionless application for
            // these and then filters its candidates to positioned ones before counting them, so
            // the count is always zero and it always refuses. Offering a line that cannot work
            // would be worse than offering none.
            found.add(new ApplicableRule(app.taclet().name().toString(), RuleKind.NO_FIND, null,
                null, false, false, 1, null));
        }
        return false;
    }

    /**
     * Collects the rules that match somewhere in the sequent, counting the matches.
     */
    private static boolean collectPositioned(Goal goal, List<ApplicableRule> found,
            int maxRules) {
        RuleAppIndex index = goal.ruleAppIndex();
        // Mirrors the script command, which does the same before enumerating: while a search is
        // running the index answers from the strategy-filtered subset, which is a different
        // question. The server refuses to probe a busy proof anyway; this makes it certain.
        index.autoModeStopped();

        Map<String, List<TacletApp>> byName = new LinkedHashMap<>();
        scanSide(index, goal, goal.sequent().antecedent(), true, byName);
        scanSide(index, goal, goal.sequent().succedent(), false, byName);

        for (Map.Entry<String, List<TacletApp>> entry : byName.entrySet()) {
            if (found.size() >= maxRules) {
                return true;
            }
            List<TacletApp> matches = entry.getValue();
            TacletApp first = matches.get(0);
            PosInOccurrence where = first.posInOccurrence();
            found.add(describe(goal, first, entry.getKey(),
                first.taclet() instanceof RewriteTaclet ? RuleKind.REWRITE : RuleKind.FIND,
                where == null ? null
                        : where.isInAntec() ? SequentSide.ANTECEDENT : SequentSide.SUCCEDENT,
                indexOf(goal, where), matches.size(), matches.size() > 1));
        }
        return false;
    }

    private static void scanSide(RuleAppIndex index, Goal goal, Semisequent side, boolean antec,
            Map<String, List<TacletApp>> byName) {
        for (SequentFormula formula : side) {
            PosInOccurrence position = new PosInOccurrence(formula, PosInTerm.getTopLevel(), antec);
            ImmutableList<TacletApp> apps = index.getTacletAppAtAndBelow(TacletFilter.TRUE,
                position, goal.proof().getServices());
            for (TacletApp app : apps) {
                // Only positioned applications: the script command discards the rest before it
                // counts, so counting them here would give an occurrence number it will not take.
                if (app instanceof PosTacletApp) {
                    byName.computeIfAbsent(app.taclet().name().toString(),
                        name -> new ArrayList<>()).add(app);
                }
            }
        }
    }

    /**
     * Builds the report for one rule, including the script line that applies it.
     *
     * @param app the first application of this rule
     * @param name the rule's name
     * @param kind how it finds what it applies to
     * @param side where its first match is, {@code null} when it needs no position
     * @param index the formula its first match is under, {@code null} when it needs no position
     * @param occurrences how many places it matches
     * @param needsOccurrence whether a script has to say which of them
     * @return the report
     */
    private static ApplicableRule describe(Goal goal, TacletApp app, String name, RuleKind kind,
            @Nullable SequentSide side, @Nullable Integer index, int occurrences,
            boolean needsOccurrence) {
        // Applies to the positioned rules only; see collectNoFind for why the others get none.
        boolean needsAssumption = !uniqueAssumption(goal, app);
        boolean needsInstantiation = !needsAssumption && !instantiable(goal, app);
        String script = null;
        if (!needsInstantiation && !needsAssumption) {
            // The occurrence number the script command takes is a zero-based index into the
            // matches it finds, whatever its own documentation says about counting from one.
            script = needsOccurrence ? "rule \"" + name + "\" occ=0;" : "rule \"" + name + "\";";
        }
        return new ApplicableRule(name, kind, side, index, needsInstantiation, needsAssumption,
            occurrences, script);
    }

    /**
     * Whether the rule's assumption can be satisfied in exactly one way.
     *
     * <p>
     * The script command resolves the assumption itself and refuses when there is nothing to
     * resolve it with, and equally when there is more than one candidate and no way to tell which
     * was meant. Both are cases where naming the rule is not enough.
     *
     * <p>
     * The candidates are counted the way the command counts them, which is to say only the
     * positioned ones: its filter drops the rest before it looks at how many are left. That has
     * a consequence worth knowing — a rule that finds no position of its own and carries an
     * assumption can never satisfy it through that command, so it never gets a line here either.
     *
     * @param goal the goal the rule would apply to
     * @param app the offered application
     * @return {@code true} when the assumption is either absent or uniquely satisfiable
     */
    private static boolean uniqueAssumption(Goal goal, TacletApp app) {
        if (app.taclet().assumesSequent().isEmpty()) {
            return true;
        }
        try {
            int positioned = 0;
            for (TacletApp candidate : app.findIfFormulaInstantiations(goal.sequent(),
                goal.proof().getServices())) {
                if (candidate instanceof PosTacletApp) {
                    positioned++;
                }
            }
            return positioned == 1;
        } catch (RuntimeException e) {
            // Cannot be told; treating that as needing input keeps the offer honest.
            return false;
        }
    }

    /**
     * Whether the rule can be filled in without being told anything.
     *
     * <p>
     * Mirrors what the script command does before it gives up: instantiate as much as can be
     * worked out, then look at whether anything that is genuinely required is still missing.
     * Judging on the uninstantiated variables alone, before that step, would withhold a working
     * line from most of the rules that have one.
     *
     * @param goal the goal the rule would apply to
     * @param app the offered application
     * @return {@code true} when nothing further has to be supplied
     */
    private static boolean instantiable(Goal goal, TacletApp app) {
        try {
            TacletApp filled = app.tryToInstantiateAsMuchAsPossible(
                goal.proof().getServices().getOverlay(goal.getLocalNamespaces()));
            TacletApp candidate = filled == null ? app : filled;
            for (SchemaVariable variable : candidate.uninstantiatedVars()) {
                if (candidate.isInstantiationRequired(variable)) {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Finds which formula of its side a position sits under.
     *
     * @param goal the goal being examined
     * @param position the position, may be {@code null}
     * @return the formula's index, or {@code null} when it cannot be placed
     */
    private static @Nullable Integer indexOf(Goal goal, @Nullable PosInOccurrence position) {
        if (position == null) {
            return null;
        }
        Semisequent side = position.isInAntec() ? goal.sequent().antecedent()
                : goal.sequent().succedent();
        int index = 0;
        for (SequentFormula formula : side) {
            if (formula.equals(position.sequentFormula())) {
                return index;
            }
            index++;
        }
        return null;
    }
}
