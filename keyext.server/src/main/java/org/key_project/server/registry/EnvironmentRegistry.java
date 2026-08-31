/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.Proof;

import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.EnvironmentRef;
import org.key_project.server.dto.EnvironmentSummary;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;

import org.jspecify.annotations.Nullable;

/** Keeps the loaded projects of one instance. */
public final class EnvironmentRegistry {

    private final Map<String, LoadedEnvironment> environments = new ConcurrentHashMap<>();

    /**
     * Takes ownership of a freshly loaded environment.
     *
     * @param source the location it was loaded from
     * @param environment the loaded KeY environment
     * @param loadedProof a proof that came with the file, or {@code null}
     * @return the reference handed to the client
     */
    public EnvironmentRef register(Path source,
            KeYEnvironment<ServerUserInterfaceControl> environment, @Nullable Proof loadedProof) {
        String envId = Ids.create("env");
        environments.put(envId, new LoadedEnvironment(envId, source, environment, loadedProof));
        return new EnvironmentRef(envId);
    }

    /**
     * Looks up an environment.
     *
     * @param envId the identifier to resolve
     * @return the loaded environment
     * @throws RpcException with {@link RpcErrorCode#ENV_NOT_FOUND} when the identifier is unknown
     */
    public LoadedEnvironment require(String envId) {
        LoadedEnvironment environment = environments.get(envId);
        if (environment == null) {
            throw new RpcException(RpcErrorCode.ENV_NOT_FOUND, "No such environment: " + envId);
        }
        return environment;
    }

    /**
     * Closes an environment and releases its KeY resources.
     *
     * @param envId the identifier to close
     * @throws RpcException with {@link RpcErrorCode#ENV_NOT_FOUND} when the identifier is unknown
     */
    public void close(String envId) {
        LoadedEnvironment environment = environments.remove(envId);
        if (environment == null) {
            throw new RpcException(RpcErrorCode.ENV_NOT_FOUND, "No such environment: " + envId);
        }
        environment.dispose();
    }

    /**
     * Describes every loaded environment.
     *
     * @param proofCounter tells how many proofs currently belong to an environment
     * @return one summary per environment
     */
    public List<EnvironmentSummary> list(ProofCounter proofCounter) {
        List<EnvironmentSummary> summaries = new ArrayList<>(environments.size());
        for (LoadedEnvironment environment : environments.values()) {
            summaries.add(new EnvironmentSummary(environment.envId(),
                environment.source().toString(), proofCounter.countFor(environment.envId())));
        }
        return summaries;
    }

    /** Closes every environment; used when the instance shuts down. */
    public void closeAll() {
        for (String envId : List.copyOf(environments.keySet())) {
            LoadedEnvironment environment = environments.remove(envId);
            if (environment != null) {
                environment.dispose();
            }
        }
    }

    /** Supplies the number of proofs registered against an environment. */
    @FunctionalInterface
    public interface ProofCounter {
        /**
         * Counts the proofs belonging to one environment.
         *
         * @param envId the environment to count for
         * @return the number of proofs
         */
        int countFor(String envId);
    }
}
