/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * What sort of contract a proof obligation comes from.
 *
 * <p>
 * Derived from the implementation type of KeY's {@code Contract}, so it says what KeY thinks the
 * obligation is, not what the server guesses from a name.
 */
public enum ProofObligationKind {
    /** A JML method contract. */
    FUNCTIONAL_OPERATION,
    /** An {@code accessible} clause obligation. */
    DEPENDENCY,
    /** A block contract taken as a proof obligation of its own. */
    BLOCK,
    /** A loop contract taken as a proof obligation of its own. */
    LOOP,
    /**
     * An information flow contract.
     *
     * <p>
     * KeY only produces these when a provider of {@code InformationFlowContractSupplier} is on the
     * class path; that provider lives in {@code key.core.infflow}, which this server does not
     * depend on. The value is declared because the contract interface is part of {@code key.core}
     * and a differently packaged deployment can produce it.
     */
    INFORMATION_FLOW,
    /** Some other auxiliary contract. */
    FUNCTIONAL_AUXILIARY,
    /**
     * A contract type this version does not classify.
     *
     * <p>
     * Reported rather than guessed at: a wrong kind would be worse than an honest "unknown".
     */
    OTHER
}
