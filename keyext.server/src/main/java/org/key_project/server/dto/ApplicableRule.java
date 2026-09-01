/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A rule that can be applied to a goal here and now, and how to write it down.
 *
 * <p>
 * {@code script} is the useful field. A rule name on its own is often not enough — a third to a
 * half of the rules offered on a real goal match in more than one place, and a script naming one
 * without saying which is refused — so the line that actually applies it is reported rather than
 * left to be reconstructed.
 *
 * @param ruleId the taclet's name
 * @param kind how the rule finds what it applies to
 * @param side which side its first match is on, absent for {@link RuleKind#NO_FIND}
 * @param index the formula its first match is under, absent for {@link RuleKind#NO_FIND}
 * @param needsInstantiation whether schema variables remain to be filled in, which a script has
 *        to supply with {@code inst_…}
 * @param needsAssumption whether the rule has an {@code \assumes} clause with no instantiation
 *        chosen, which a script has to make
 * @param occurrences how many places the rule matches. Beyond the first they are reached by
 *        raising the occurrence number in {@code script}, which counts from zero
 * @param script the line to hand to {@code goal.applyScript} to apply the first match. Absent
 *        when the rule needs input this cannot supply, and always absent for
 *        {@link RuleKind#NO_FIND}: the {@code rule} command filters its candidates to positioned
 *        applications before counting them, so it refuses those whatever is written
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicableRule(String ruleId, RuleKind kind, SequentSide side, Integer index,
        boolean needsInstantiation, boolean needsAssumption, int occurrences, String script) {
}
