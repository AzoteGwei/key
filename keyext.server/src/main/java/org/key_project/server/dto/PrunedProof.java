/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * What pruning removed.
 *
 * <p>
 * The count is reported because KeY's own pruning answers "nothing to do" and "done" in ways that
 * look alike from outside, and a caller that undid nothing should not be told it undid something.
 * A prune that removes nothing is refused rather than reported here.
 *
 * @param goal the node that is now an open goal again, ready to be worked on
 * @param removedNodes how many nodes were discarded
 * @param statistics the state of the proof afterwards
 */
public record PrunedProof(GoalRef goal, int removedNodes, ProofStatistics statistics) {
}
