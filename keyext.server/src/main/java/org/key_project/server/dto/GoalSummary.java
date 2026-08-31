/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * What a goal is, without its sequent.
 *
 * <p>
 * {@code goalId} and {@code nodeId} are the same number: KeY has no identifier for a goal beyond
 * the serial number of the node it sits on. Both are reported because they are used in different
 * places — a goal is addressed by {@code goalId}, a node is pruned by {@code nodeId} — and a
 * client should not have to know they coincide, in case they one day stop coinciding.
 *
 * @param goal the reference to pass to the methods that act on this goal
 * @param goalId serial number of the goal's node
 * @param nodeId serial number of the same node
 * @param isOpen whether the goal is still open
 * @param isLinked whether the goal was closed by linking it to another node rather than by proof
 */
public record GoalSummary(GoalRef goal, int goalId, int nodeId, boolean isOpen,
        boolean isLinked) {
}
