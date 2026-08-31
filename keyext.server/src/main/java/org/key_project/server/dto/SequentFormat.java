/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * How a sequent is rendered.
 *
 * <p>
 * Only {@link #TEXT} is implemented. The other two are declared because the protocol commits to
 * them, and asking for one returns an error rather than quietly handing back text under a
 * different label: a client that asked for structured output and received a string would build on
 * a promise the server did not keep.
 */
public enum SequentFormat {
    /** KeY's own pretty-printed rendering, as a list of formula strings. */
    TEXT,
    /** Pretty printing with unicode logical symbols. Declared, not yet implemented. */
    UNICODE,
    /** Formulas as trees rather than strings. Declared, not yet implemented. */
    STRUCTURED
}
