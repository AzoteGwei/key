/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One formula of a sequent, taken apart into the pieces it is actually made of.
 *
 * <p>
 * A proof obligation in mid-flight is one enormous formula: a symbolic state, then the program
 * still to run, then what must hold once it has. Printed as one string that is several hundred
 * characters with the interesting part at the end, and a reader has to parse KeY's syntax before
 * it can find out what the goal even is. Separated, each piece answers a different question and
 * can be read on its own.
 *
 * @param side which side of the turnstile this formula sits on
 * @param index its position on that side, matching the {@code antecedent} or {@code succedent}
 *        array of the same response
 * @param text the whole formula
 * @param state the symbolic state, KeY's update, absent when the formula carries none
 * @param program the Java still to be executed, absent when the formula carries none
 * @param claim the formula with the state and the program elided, which is what remains to be
 *        shown about them. For a formula with neither it is the whole formula
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StructuredFormula(SequentSide side, int index, String text, String state,
        String program, String claim) {
}
