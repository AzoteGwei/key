/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Opaque handle on one goal of one proof.
 *
 * <p>
 * A goal is only identified within its proof — KeY numbers nodes per proof — so the reference
 * carries both parts. Clients pass it back as they received it and must not parse or build one.
 *
 * @param proofId the proof the goal belongs to
 * @param goalId the goal within that proof
 */
public record GoalRef(String proofId, int goalId) {
}
