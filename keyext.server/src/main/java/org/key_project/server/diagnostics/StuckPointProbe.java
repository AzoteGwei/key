/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.diagnostics;

import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.java.Services;
import de.uka.ilkd.key.java.ast.PositionInfo;
import de.uka.ilkd.key.logic.JTerm;
import de.uka.ilkd.key.pp.LogicPrinter;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.rule.AbstractContractRuleApp;
import de.uka.ilkd.key.rule.AbstractLoopContractBuiltInRuleApp;
import de.uka.ilkd.key.rule.BuiltInRule;
import de.uka.ilkd.key.rule.IBuiltInRuleApp;
import de.uka.ilkd.key.rule.LoopInvariantBuiltInRuleApp;

import org.key_project.logic.PosInTerm;
import org.key_project.logic.Term;
import org.key_project.prover.sequent.PosInOccurrence;
import org.key_project.prover.sequent.SequentFormula;
import org.key_project.server.dto.GoalDiagnostics;
import org.key_project.server.dto.SourcePosition;
import org.key_project.server.dto.StuckPoint;
import org.key_project.server.dto.StuckReason;
import org.key_project.util.collection.ImmutableList;

import org.jspecify.annotations.Nullable;

/**
 * Asks a goal which rules would like to apply to it and which of those cannot be completed.
 *
 * <p>
 * This is on-demand: nothing is recorded while the prover runs, the goal is simply examined when
 * someone asks. That was a deliberate choice over instrumenting the search, because the automatic
 * path never consults a completion handler — {@code AbstractProofControl.completeBuiltInRuleApp}
 * has exactly one caller and it is a Swing menu — so there is no hook to record from without
 * changing {@code key.core}.
 *
 * <p>
 * The enumeration below mirrors {@link de.uka.ilkd.key.proof.BuiltInRuleAppIndex}
 * {@code .scanApplicableRules} phase for phase, so what it reports is what the automatic search
 * would consider, not a separate opinion about the goal. It is reproduced here rather than called
 * because KeY's version descends the whole term tree with no way to bound it — the recursion
 * carries its own {@code // TODO: optimise?} — and a diagnostic an agent runs between attempts has
 * to have a ceiling. {@code StuckPointProbeFidelityTest} pins the two together by requiring
 * identical output when the ceiling is out of reach.
 */
public final class StuckPointProbe {

    /**
     * How deep into a formula the probe descends by default.
     *
     * <p>
     * Measured rather than guessed: the stuck goals of this project's fixtures have formulas 5 to
     * 11 levels deep and scan in 0.1 to 1.3 milliseconds, so this leaves roughly threefold
     * headroom over anything observed while still bounding a pathological sequent. It is a
     * ceiling, not a budget — reaching it is reported as {@code truncated} rather than passed off
     * as a complete answer.
     */
    public static final int DEFAULT_MAX_DEPTH = 32;

    /** How much of a term is quoted in a position hint. */
    private static final int HINT_LENGTH = 160;

    private StuckPointProbe() {
    }

    /**
     * Examines one goal.
     *
     * @param goal the goal to examine
     * @param maxDepth how deep into each formula to look
     * @return the rules that want to apply and cannot
     */
    public static GoalDiagnostics probe(Goal goal, int maxDepth) {
        Scan scan = scan(goal, maxDepth);
        List<StuckPoint> stuck = new ArrayList<>();
        for (Candidate candidate : scan.candidates()) {
            StuckPoint point = examine(goal, candidate);
            if (point != null) {
                stuck.add(point);
            }
        }
        return new GoalDiagnostics(goal.node().serialNr(), List.copyOf(stuck), scan.truncated());
    }

    /**
     * Decides whether one candidate is a stuck point.
     *
     * @param goal the goal it was found on
     * @param candidate the rule application to test
     * @return the finding, or {@code null} when the rule could be applied after all
     */
    private static @Nullable StuckPoint examine(Goal goal, Candidate candidate) {
        IBuiltInRuleApp app = candidate.app();
        if (app.complete()) {
            // Ready to fire. Nothing is stuck here; the search simply has not got to it.
            return null;
        }
        IBuiltInRuleApp forced;
        try {
            forced = app.forceInstantiate(goal);
        } catch (Exception e) {
            // Refusing to instantiate is exactly the condition being looked for, however it is
            // signalled. What it is not is a reason to fail the whole request.
            return describe(app, candidate.pos(), StuckReason.NOT_INSTANTIABLE,
                goal.proof().getServices());
        }
        if (forced != null && forced.complete()) {
            return null;
        }
        return describe(app, candidate.pos(), reasonFor(forced == null ? app : forced),
            goal.proof().getServices());
    }

    /**
     * Names why a rule could not be completed, where KeY's own rule application can say.
     *
     * <p>
     * A loop invariant application knows whether it found an invariant, and a contract application
     * knows whether it found a contract. Where one of those says no, the goal is waiting on a
     * specification somebody has to write. Anything else is reported as unexplained rather than
     * attributed to a missing specification that may not be the problem.
     *
     * @param app the application that would not complete
     * @return what to report as the reason
     */
    private static StuckReason reasonFor(IBuiltInRuleApp app) {
        if (app instanceof LoopInvariantBuiltInRuleApp<?> loop && !loop.invariantAvailable()) {
            return StuckReason.NEEDS_SPEC;
        }
        if (app instanceof AbstractContractRuleApp<?> contract
                && contract.getInstantiation() == null) {
            return StuckReason.NEEDS_SPEC;
        }
        if (app instanceof AbstractLoopContractBuiltInRuleApp<?>) {
            return StuckReason.NEEDS_SPEC;
        }
        return StuckReason.NOT_INSTANTIABLE;
    }

    private static StuckPoint describe(IBuiltInRuleApp app, @Nullable PosInOccurrence pos,
            StuckReason reason, Services services) {
        BuiltInRule rule = app.rule();
        return new StuckPoint(rule.getClass().getSimpleName(), rule.displayName(),
            hint(app, pos, services), reason, source(app));
    }

    /**
     * Says where the rule wanted to apply, in terms an agent can act on.
     *
     * @param app the rule application
     * @param pos where it was found, {@code null} when it applies to the whole sequent
     * @return a short description of the position
     */
    private static String hint(IBuiltInRuleApp app, @Nullable PosInOccurrence pos,
            Services services) {
        if (app instanceof LoopInvariantBuiltInRuleApp<?> loop
                && loop.getLoopStatement() != null) {
            // Far more use than the position in the sequent: this is the loop in the source file
            // that needs the invariant.
            return "loop at " + describePosition(loop.getLoopStatement().getPositionInfo());
        }
        if (pos == null) {
            return "the whole sequent";
        }
        String side = pos.isInAntec() ? "antecedent" : "succedent";
        String path = pos.posInTerm() == null || pos.posInTerm().depth() == 0 ? "top level"
                : "subterm " + pos.posInTerm();
        return side + ", " + path + ": " + abbreviate(print(pos.subTerm(), services));
    }

    private static @Nullable SourcePosition source(IBuiltInRuleApp app) {
        if (!(app instanceof LoopInvariantBuiltInRuleApp<?> loop)
                || loop.getLoopStatement() == null) {
            return null;
        }
        PositionInfo info = loop.getLoopStatement().getPositionInfo();
        if (info == null || info.getStartPosition().isNegative()) {
            return null;
        }
        return new SourcePosition(info.getURI().map(Object::toString).orElse(null),
            info.getStartPosition().line(), info.getStartPosition().column(),
            "loop with no invariant");
    }

    private static String describePosition(@Nullable PositionInfo info) {
        if (info == null || info.getStartPosition().isNegative()) {
            return "an unknown source position";
        }
        return info.getFileName() + ":" + info.getStartPosition().line();
    }

    private static String abbreviate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= HINT_LENGTH ? flat : flat.substring(0, HINT_LENGTH) + "…";
    }

    /**
     * One rule that KeY reports as applicable, and where.
     *
     * @param app the rule application KeY built
     * @param pos where it applies, {@code null} for the whole sequent
     */
    public record Candidate(IBuiltInRuleApp app, @Nullable PosInOccurrence pos) {
    }

    /**
     * Everything one scan turned up.
     *
     * @param candidates the applicable rules found
     * @param truncated whether the depth ceiling stopped the scan short of the whole term
     */
    public record Scan(List<Candidate> candidates, boolean truncated) {
    }

    /**
     * Enumerates the built-in rules applicable to a goal, to a bounded depth.
     *
     * <p>
     * Phase for phase this is {@code BuiltInRuleAppIndex.scanSimplificationRule}: the rules that
     * apply to no position at all, then each succedent formula, then each antecedent formula, and
     * within a formula the rules that do not work on subterms at top level followed by those that
     * do, descending. The order is kept identical so the fidelity test can compare the two
     * outputs element by element.
     *
     * @param goal the goal to scan
     * @param maxDepth how far below a formula's root to descend
     * @return what was found and whether the ceiling was reached
     */
    public static Scan scan(Goal goal, int maxDepth) {
        List<Candidate> candidates = new ArrayList<>();
        boolean[] truncated = { false };
        ImmutableList<BuiltInRule> rules =
            goal.ruleAppIndex().builtInRuleAppIndex().builtInRuleIndex().rules();
        if (rules.isEmpty()) {
            return new Scan(List.of(), false);
        }

        for (BuiltInRule rule : rules) {
            if (rule.isApplicable(goal, null)) {
                candidates.add(new Candidate(rule.createApp(null, goal.proof().getServices()),
                    null));
            }
        }
        scanSide(rules, goal, false, maxDepth, candidates, truncated);
        scanSide(rules, goal, true, maxDepth, candidates, truncated);
        return new Scan(List.copyOf(candidates), truncated[0]);
    }

    private static void scanSide(ImmutableList<BuiltInRule> rules, Goal goal, boolean antecedent,
            int maxDepth, List<Candidate> candidates, boolean[] truncated) {
        var side = antecedent ? goal.sequent().antecedent() : goal.sequent().succedent();
        for (SequentFormula formula : side) {
            PosInOccurrence pos =
                new PosInOccurrence(formula, PosInTerm.getTopLevel(), antecedent);
            List<BuiltInRule> subTermRules = new ArrayList<>();
            for (BuiltInRule rule : rules) {
                if (rule.isApplicableOnSubTerms()) {
                    subTermRules.add(rule);
                } else if (rule.isApplicable(goal, pos)) {
                    candidates.add(
                        new Candidate(rule.createApp(pos, goal.proof().getServices()), pos));
                }
            }
            scanSubTerms(subTermRules, goal, pos, 0, maxDepth, candidates, truncated);
        }
    }

    private static void scanSubTerms(List<BuiltInRule> rules, Goal goal, PosInOccurrence pos,
            int depth, int maxDepth, List<Candidate> candidates, boolean[] truncated) {
        for (BuiltInRule rule : rules) {
            if (rule.isApplicable(goal, pos)) {
                candidates.add(new Candidate(rule.createApp(pos, goal.proof().getServices()), pos));
            }
        }
        int arity = pos.subTerm().arity();
        if (arity == 0) {
            return;
        }
        if (depth >= maxDepth) {
            // There is more term below here that was not looked at, and the caller has to be told
            // rather than handed a shorter list that looks complete.
            truncated[0] = true;
            return;
        }
        for (int i = 0; i < arity; i++) {
            scanSubTerms(rules, goal, pos.down(i), depth + 1, maxDepth, candidates, truncated);
        }
    }

    /**
     * Renders a term the way the sequent view does, so a hint quotes what a client already reads
     * elsewhere.
     *
     * @param term the term to print
     * @param services the services of the proof it belongs to
     * @return the printed term
     */
    private static String print(Term term, Services services) {
        return term instanceof JTerm jTerm ? LogicPrinter.quickPrintTerm(jTerm, services).trim()
                : String.valueOf(term);
    }
}
