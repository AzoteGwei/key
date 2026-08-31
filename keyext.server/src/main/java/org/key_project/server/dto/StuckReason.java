/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/** Why a rule that wants to apply to a goal cannot be applied to it. */
public enum StuckReason {
    /**
     * The rule needs a JML specification that has not been written.
     *
     * <p>
     * This is the actionable case, and the one worth telling an agent about: a loop with no
     * {@code loop_invariant}, a call to a method with no contract. What to write next follows
     * directly from it.
     */
    NEEDS_SPEC,
    /**
     * The rule applies here but could not be completed, for a reason this server cannot name.
     *
     * <p>
     * Reported as-is rather than guessed at. Naming a cause that might be wrong would send an
     * agent off writing specifications that change nothing.
     */
    NOT_INSTANTIABLE
}
