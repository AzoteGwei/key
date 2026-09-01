/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import java.util.List;

/**
 * What can still be applied to a goal by hand.
 *
 * <p>
 * The other half of {@link GoalDiagnostics}. Stuck points say what wants to apply and cannot;
 * this says what could apply and was not chosen. When a search comes back {@code EXHAUSTED} with
 * no stuck points, this is what is left to look at: the automatic strategy declined these, a
 * person would pick one, and every identifier here can be written straight into a proof script.
 *
 * <p>
 * No judgement is offered about which are worth trying. Some — {@code cut}, {@code hide_left},
 * {@code case_distinction_l} — apply almost anywhere and being listed says nothing about them
 * beyond that.
 *
 * <p>
 * Nor is anything promised about applying one straight away. {@code needsInstantiation} and
 * {@code needsAssumption} name the obstacles that are known, and a rule listed several times
 * matches in several places, which a script naming it without {@code occ=} or {@code formula=}
 * will be refused for. Those are the obstacles this can see: only the top level of each formula
 * is surveyed, so a rule listed once may still match inside a term and be ambiguous for that
 * reason. Treat the list as candidates worth trying rather than as a promise.
 *
 * @param goalId the goal these apply to
 * @param rules what can be applied
 * @param truncated whether more were found than were reported
 */
public record GoalRules(int goalId, List<ApplicableRule> rules, boolean truncated) {
}
