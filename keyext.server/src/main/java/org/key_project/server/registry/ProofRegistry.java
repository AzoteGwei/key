/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.uka.ilkd.key.proof.Proof;

import org.key_project.server.dto.ProofRef;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;

import org.jspecify.annotations.Nullable;

/** Keeps the proofs of one instance, each tied to the environment it belongs to. */
public final class ProofRegistry {

    private final Map<String, RegisteredProof> proofs = new ConcurrentHashMap<>();

    /**
     * Takes ownership of a proof.
     *
     * @param envId the environment the proof belongs to
     * @param contractId the contract it proves, {@code null} when it came from a file
     * @param proof the KeY proof object
     * @return the reference handed to the client
     */
    public ProofRef register(String envId, @Nullable String contractId, Proof proof) {
        String proofId = Ids.create("prf");
        proofs.put(proofId, new RegisteredProof(proofId, envId, contractId, proof));
        return new ProofRef(proofId);
    }

    /**
     * Looks up a proof.
     *
     * @param proofId the identifier to resolve
     * @return the registered proof
     * @throws RpcException with {@link RpcErrorCode#PROOF_NOT_FOUND} when the identifier is
     *         unknown, or when KeY has already disposed of the proof
     */
    public RegisteredProof require(String proofId) {
        RegisteredProof registered = proofs.get(proofId);
        if (registered == null) {
            throw new RpcException(RpcErrorCode.PROOF_NOT_FOUND, "No such proof: " + proofId);
        }
        if (registered.proof().isDisposed()) {
            throw new RpcException(RpcErrorCode.PROOF_NOT_FOUND,
                "Proof " + proofId + " has been disposed of");
        }
        return registered;
    }

    /**
     * Counts the proofs of one environment.
     *
     * @param envId the environment to count for
     * @return how many proofs belong to it
     */
    public int countFor(String envId) {
        return (int) proofs.values().stream().filter(p -> p.envId().equals(envId)).count();
    }

    /**
     * Names the contracts of one environment that already have a proof here.
     *
     * @param envId the environment to look at
     * @return the contract identifiers proofs were started from
     */
    public List<String> provenContractsOf(String envId) {
        return proofs.values().stream().filter(p -> p.envId().equals(envId))
                .map(RegisteredProof::contractId).filter(id -> id != null).toList();
    }

    /**
     * Drops a single proof and releases it.
     *
     * @param proofId the proof to close
     * @throws RpcException with {@link RpcErrorCode#PROOF_NOT_FOUND} when the identifier is
     *         unknown
     */
    public void close(String proofId) {
        RegisteredProof registered = proofs.remove(proofId);
        if (registered == null) {
            throw new RpcException(RpcErrorCode.PROOF_NOT_FOUND, "No such proof: " + proofId);
        }
        registered.dispose();
    }

    /**
     * Drops every proof of one environment; used when that environment is closed.
     *
     * @param envId the environment being closed
     */
    public void closeAllIn(String envId) {
        for (RegisteredProof registered : List.copyOf(proofs.values())) {
            if (registered.envId().equals(envId) && proofs.remove(registered.proofId()) != null) {
                registered.dispose();
            }
        }
    }

    /** Drops every proof; used when the instance shuts down. */
    public void closeAll() {
        for (RegisteredProof registered : List.copyOf(proofs.values())) {
            if (proofs.remove(registered.proofId()) != null) {
                registered.dispose();
            }
        }
    }
}
