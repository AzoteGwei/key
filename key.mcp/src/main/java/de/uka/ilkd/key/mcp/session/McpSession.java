/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.java.ast.abstraction.KeYJavaType;
import de.uka.ilkd.key.logic.op.IObserverFunction;
import de.uka.ilkd.key.mcp.operation.OperationTracker;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.speclang.Contract;
import de.uka.ilkd.key.util.KeYTypeUtil;

import org.key_project.util.collection.ImmutableSet;

/**
 * Holds the runtime state for a single MCP client session.
 */
public class McpSession {
    private final String id;
    private KeYEnvironment<?> environment;
    private final Map<String, Contract> contracts = new LinkedHashMap<>();
    private final Map<String, Proof> proofs = new LinkedHashMap<>();
    private final OperationTracker operationTracker = new OperationTracker();
    private int proofCounter;

    public McpSession(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public KeYEnvironment<?> getEnvironment() {
        return environment;
    }

    public void setEnvironment(KeYEnvironment<?> environment) {
        this.environment = environment;
        this.contracts.clear();
        this.proofs.clear();
        this.proofCounter = 0;
    }

    /**
     * Loads contracts from the current environment and stores them under contract IDs.
     */
    public void loadContracts() {
        contracts.clear();
        if (environment == null) {
            return;
        }
        List<Contract> loadedContracts = new ArrayList<>();
        var kjts = environment.getJavaInfo().getAllKeYJavaTypes();
        for (KeYJavaType type : kjts) {
            if (!KeYTypeUtil.isLibraryClass(type)) {
                ImmutableSet<IObserverFunction> targets =
                    environment.getSpecificationRepository().getContractTargets(type);
                for (IObserverFunction target : targets) {
                    ImmutableSet<Contract> cs =
                        environment.getSpecificationRepository().getContracts(type, target);
                    for (Contract c : cs) {
                        loadedContracts.add(c);
                    }
                }
            }
        }
        int index = 0;
        for (Contract c : loadedContracts) {
            String contractId = "contract_" + index + "_" + sanitize(c.getDisplayName());
            contracts.put(contractId, c);
            index++;
        }
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^a-zA-Z0-9_]+", "_");
    }

    public Map<String, Contract> getContracts() {
        return contracts;
    }

    public Contract getContract(String id) {
        return contracts.get(id);
    }

    public void registerProof(String proofId, Proof proof) {
        proofs.put(proofId, proof);
    }

    public Proof getProof(String proofId) {
        return proofs.get(proofId);
    }

    public Proof removeProof(String proofId) {
        return proofs.remove(proofId);
    }

    public Map<String, Proof> getProofs() {
        return proofs;
    }

    public String nextProofId(String contractName) {
        proofCounter++;
        return "proof_" + proofCounter + "_" + sanitize(contractName);
    }

    public OperationTracker getOperationTracker() {
        return operationTracker;
    }

    /**
     * Disposes the session and all owned proofs and the environment.
     */
    public void dispose() {
        for (Proof proof : proofs.values()) {
            if (!proof.isDisposed()) {
                proof.dispose();
            }
        }
        proofs.clear();
        contracts.clear();
        if (environment != null) {
            environment.dispose();
            environment = null;
        }
    }
}
