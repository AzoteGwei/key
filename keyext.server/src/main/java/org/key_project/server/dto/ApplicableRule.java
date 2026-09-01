/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A rule that can be applied to a goal here and now.
 *
 * <p>
 * One entry per place the rule applies, so a rule that appears twice really does match twice.
 * That matters for using it: a proof script's {@code rule "name";} refuses an ambiguous match and
 * needs {@code occ=} or {@code formula=} to pick one.
 *
 * @param ruleId the taclet's name, which is what a proof script's {@code rule} command takes
 * @param kind how the rule finds what it applies to
 * @param side which side of the turnstile it applies on, absent for {@link RuleKind#NO_FIND}
 * @param index the formula on that side, absent for {@link RuleKind#NO_FIND}
 * @param needsInstantiation whether the rule has schema variables still to be filled in. When
 *        {@code true} a bare {@code rule "name";} is not enough and the script has to supply them
 *        with {@code inst_…}
 * @param needsAssumption whether the rule has an {@code \assumes} clause with no instantiation
 *        chosen. When {@code true} a script has to say which formula satisfies it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApplicableRule(String ruleId, RuleKind kind, SequentSide side, Integer index,
        boolean needsInstantiation, boolean needsAssumption) {
}
