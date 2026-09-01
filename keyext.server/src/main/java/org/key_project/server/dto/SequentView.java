/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The formulas of one goal.
 *
 * <p>
 * {@code antecedent} and {@code succedent} are present whatever the format was, so a client that
 * wants the text of a formula never has to know which format it asked for.
 *
 * @param antecedent what is assumed
 * @param succedent what is to be shown
 * @param format how the formulas were rendered
 * @param formulas the same formulas taken apart, present only for
 *        {@link SequentFormat#STRUCTURED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SequentView(List<String> antecedent, List<String> succedent, SequentFormat format,
        List<StructuredFormula> formulas) {
}
