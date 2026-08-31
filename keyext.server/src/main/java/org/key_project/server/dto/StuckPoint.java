/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A rule that KeY would like to apply to a goal but cannot.
 *
 * <p>
 * These are what an open goal count leaves out. "One goal remains open" says a proof did not
 * finish; "a loop invariant rule applies at {@code Summer.java:26} and there is no invariant to
 * instantiate it with" says what to do about it.
 *
 * @param ruleId stable identifier of the rule, its implementation class name
 * @param ruleName what KeY calls the rule where a person would read it
 * @param positionHint where in the goal the rule wanted to apply
 * @param reason what stands in the way
 * @param source the place in the Java source this points at, when there is one
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StuckPoint(String ruleId, String ruleName, String positionHint, StuckReason reason,
        SourcePosition source) {
}
