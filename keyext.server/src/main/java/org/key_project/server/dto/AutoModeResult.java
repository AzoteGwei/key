/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * The product of a finished {@link TaskKind#AUTO} task.
 *
 * <p>
 * The statistics are taken after the search stopped. A task in state
 * {@link TaskStatus#SUCCEEDED} carrying {@code closed: false} is the normal, expected outcome of
 * an unsuccessful proof attempt: the task did its work, the proof is still open.
 *
 * <p>
 * Read {@code outcome} alongside it. {@code EXHAUSTED} with open goals means the prover is out of
 * ideas and more time will not help; anything to do with a limit means it was still working when
 * it was stopped.
 *
 * @param proof the proof that was worked on
 * @param outcome why the search stopped
 * @param statistics the state of the proof afterwards
 */
public record AutoModeResult(ProofRef proof, AutoModeOutcome outcome,
        ProofStatistics statistics) {
}
