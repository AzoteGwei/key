/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * How a taclet finds what it applies to. KeY's own categorisation, not an assessment of the rule.
 */
public enum RuleKind {
    /** Applies to the goal as a whole rather than to any particular formula. */
    NO_FIND,
    /** Matches a formula. */
    FIND,
    /** Rewrites a term inside a formula. */
    REWRITE
}
