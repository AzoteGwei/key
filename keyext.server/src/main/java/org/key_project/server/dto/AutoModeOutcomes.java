/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import org.key_project.prover.engine.StopReason;

/** Translates KeY's own ending of a proof search into the one the protocol reports. */
public final class AutoModeOutcomes {

    private AutoModeOutcomes() {
    }

    /**
     * Reports how a search ended.
     *
     * <p>
     * KeY's {@code TIMEOUT} is its strategy's own limit, which this server never sets. A budget
     * this client asked for is enforced by interrupting the search, so it arrives as
     * {@code INTERRUPTED} and only the caller knows which of the two it was — hence the flag
     * rather than a guess from the enum alone.
     *
     * @param reason how KeY says the search ended
     * @param budgetElapsed whether this server's own {@code timeoutMs} ran out
     * @return the ending to report
     */
    public static AutoModeOutcome of(StopReason reason, boolean budgetElapsed) {
        if (budgetElapsed) {
            return AutoModeOutcome.BUDGET_ELAPSED;
        }
        return switch (reason) {
            case EXHAUSTED -> AutoModeOutcome.EXHAUSTED;
            case MAX_RULES -> AutoModeOutcome.MAX_RULES;
            case TIMEOUT -> AutoModeOutcome.STRATEGY_TIMEOUT;
            case NON_CLOSEABLE_GOAL -> AutoModeOutcome.NON_CLOSEABLE_GOAL;
            case STOP_CONDITION -> AutoModeOutcome.STOP_CONDITION;
            case PROOF_ERRONEOUS -> AutoModeOutcome.PROOF_ERRONEOUS;
            case INTERRUPTED, ERROR -> AutoModeOutcome.INTERRUPTED;
        };
    }
}
