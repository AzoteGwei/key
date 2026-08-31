/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * The product of a finished {@link TaskKind#MACRO} or {@link TaskKind#SCRIPT} task.
 *
 * <p>
 * A macro or script that ran to its end is reported as a succeeded task whatever it left behind.
 * Read {@link ProofStatistics#closed()} for the only statement about verification; a macro
 * finishing and a proof closing are different events.
 *
 * @param proof the proof that was worked on
 * @param statistics the state of the proof afterwards
 */
public record ProofRunResult(ProofRef proof, ProofStatistics statistics) {
}
