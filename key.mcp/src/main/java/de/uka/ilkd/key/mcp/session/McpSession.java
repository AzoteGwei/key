/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.session;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.java.ast.abstraction.KeYJavaType;
import de.uka.ilkd.key.logic.op.IObserverFunction;
import de.uka.ilkd.key.mcp.operation.Operation;
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
    private Map<String, Object> clientCapabilities = Map.of();
    private final Set<Path> sessionAllowedPaths = Collections.synchronizedSet(new HashSet<>());

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
        // Sort by target name first: display names are not unique (e.g. several
        // "JML normal behavior operation contract"s), so they cannot stabilize the order.
        loadedContracts.sort(java.util.Comparator
                .comparing((Contract c) -> c.getTarget().name().toString())
                .thenComparing(Contract::getDisplayName)
                .thenComparing(c -> c.getClass().getName()));
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
     * Stores the client capabilities declared during the legacy {@code initialize} handshake.
     */
    public void setClientCapabilities(Map<String, Object> capabilities) {
        this.clientCapabilities = capabilities != null ? capabilities : Map.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> experimentalCapabilities() {
        Object experimental = clientCapabilities.get("experimental");
        return experimental instanceof Map ? (Map<String, Object>) experimental : Map.of();
    }

    /**
     * Returns whether the client declared support for the elicitation capability.
     */
    public boolean hasElicitationCapability() {
        return Boolean.TRUE.equals(clientCapabilities.get("elicitation"))
                || Boolean.TRUE.equals(experimentalCapabilities().get("elicitation"));
    }

    /**
     * Adds a path to the session-level authorization cache.
     */
    public void allowPath(Path path) {
        sessionAllowedPaths.add(path);
    }

    /**
     * Checks whether a path has been authorized at session level (in addition to the
     * configured whitelist).
     */
    public boolean isPathAllowed(Path path) {
        return sessionAllowedPaths.contains(path);
    }

    /**
     * Disposes the session and all owned proofs and the environment. Running operations
     * (e.g. auto mode workers) are interrupted first so they do not keep mutating disposed
     * proofs.
     */
    public void dispose() {
        for (Operation operation : operationTracker.getAll()) {
            Thread worker = operation.getWorkerThread();
            if (worker != null && worker.isAlive()) {
                worker.interrupt();
            }
        }
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
