/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Why an automatic proof search stopped.
 *
 * <p>
 * Note that none of these values says anything about success. A run that stopped because KeY had
 * nothing left to try is {@link #COMPLETED} whether or not the proof closed; only
 * {@link ProofStatistics#closed()} answers that.
 */
public enum AutoModeOutcome {
    /** KeY's proof search ran to its own end, whatever it left behind. */
    COMPLETED,
    /** The requested time limit elapsed and the search was interrupted. */
    TIMEOUT
}
