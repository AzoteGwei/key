/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import java.util.List;

/**
 * The formulas of one goal.
 *
 * @param antecedent what is assumed
 * @param succedent what is to be shown
 * @param format how the formulas were rendered
 */
public record SequentView(List<String> antecedent, List<String> succedent,
        SequentFormat format) {
}
