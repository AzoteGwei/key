/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Why an automatic proof search stopped.
 *
 * <p>
 * None of these says anything about success. A run that stopped because KeY had nothing left to
 * try is {@link #EXHAUSTED} whether or not the proof closed; only {@link ProofStatistics#closed()}
 * answers that.
 *
 * <p>
 * What they do say is whether more of the same would help, which is the question a client asks
 * next. {@link #MAX_RULES} and {@link #BUDGET_ELAPSED} mean the search was still working when it
 * was stopped. {@link #EXHAUSTED} means it was not: the prover did everything it knows, and the
 * goals that remain need a script, a solver or a specification rather than more time.
 */
public enum AutoModeOutcome {
    /**
     * KeY had no rule left to offer for any open goal.
     *
     * <p>
     * The end of the road for the automatic strategy. Also how a finished proof ends, since
     * closing the last goal leaves nothing to apply.
     */
    EXHAUSTED,
    /** KeY's own limit on rule applications was reached while it was still finding work. */
    MAX_RULES,
    /** The {@code timeoutMs} this client asked for elapsed and the search was interrupted. */
    BUDGET_ELAPSED,
    /** KeY's own strategy time limit elapsed. */
    STRATEGY_TIMEOUT,
    /** The search was told to stop at the first goal it could not close, and did. */
    NON_CLOSEABLE_GOAL,
    /** A stop condition other than the usual limits ended the search. */
    STOP_CONDITION,
    /** The search stopped because KeY has flagged the proof as erroneous. */
    PROOF_ERRONEOUS,
    /** The search was interrupted; a cancellation reaches KeY this way. */
    INTERRUPTED
}
