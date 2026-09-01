/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.util.concurrent.atomic.AtomicReference;

import de.uka.ilkd.key.proof.Proof;

import org.key_project.server.dto.AutoModeOutcome;

import org.jspecify.annotations.Nullable;

/**
 * One proof the server holds, together with where it came from and how it was last worked on.
 */
public final class RegisteredProof {

    private final String proofId;
    private final String envId;
    private final @Nullable String contractId;
    private final Proof proof;

    /**
     * How the last automatic search on this proof ended.
     *
     * <p>
     * Kept here rather than on the proof because it is this server's bookkeeping, not KeY's. It is
     * what lets the diagnostics tell "the prover ran out of ideas" from "the prover never
     * finished looking", which are the same empty stuck-point list and opposite problems.
     */
    private final AtomicReference<@Nullable AutoModeOutcome> lastSearchOutcome =
        new AtomicReference<>();

    /**
     * Creates a registration.
     *
     * @param proofId opaque identifier issued to clients
     * @param envId the environment this proof belongs to
     * @param contractId the contract it was started from, {@code null} when it came from a file
     * @param proof the KeY proof object
     */
    public RegisteredProof(String proofId, String envId, @Nullable String contractId,
            Proof proof) {
        this.proofId = proofId;
        this.envId = envId;
        this.contractId = contractId;
        this.proof = proof;
    }

    /**
     * The identifier clients use.
     *
     * @return the proof identifier
     */
    public String proofId() {
        return proofId;
    }

    /**
     * The environment this proof belongs to.
     *
     * @return the environment identifier
     */
    public String envId() {
        return envId;
    }

    /**
     * The contract this proof was started from.
     *
     * @return the contract name, or {@code null} when the proof came from a file
     */
    public @Nullable String contractId() {
        return contractId;
    }

    /**
     * The KeY proof.
     *
     * @return the proof object
     */
    public Proof proof() {
        return proof;
    }

    /**
     * Records how the last automatic search ended.
     *
     * @param outcome the ending to remember
     */
    public void searchEnded(AutoModeOutcome outcome) {
        lastSearchOutcome.set(outcome);
    }

    /**
     * How the last automatic search ended.
     *
     * @return the ending, or {@code null} when no search has been run on this proof
     */
    public @Nullable AutoModeOutcome lastSearchOutcome() {
        return lastSearchOutcome.get();
    }

    /** Releases the KeY resources held by this proof. */
    public void dispose() {
        if (!proof.isDisposed()) {
            proof.dispose();
        }
    }
}
