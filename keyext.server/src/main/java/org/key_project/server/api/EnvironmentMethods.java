/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.uka.ilkd.key.logic.op.IObserverFunction;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.init.ContractPO;
import de.uka.ilkd.key.proof.mgt.SpecificationRepository;
import de.uka.ilkd.key.speclang.Contract;
import de.uka.ilkd.key.speclang.DependencyContract;
import de.uka.ilkd.key.speclang.FunctionalAuxiliaryContract;
import de.uka.ilkd.key.speclang.FunctionalBlockContract;
import de.uka.ilkd.key.speclang.FunctionalLoopContract;
import de.uka.ilkd.key.speclang.FunctionalOperationContract;
import de.uka.ilkd.key.speclang.infflow.InformationFlowContract;
import de.uka.ilkd.key.util.KeYTypeUtil;

import org.key_project.server.ServerOptions;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.Ok;
import org.key_project.server.dto.ProofObligation;
import org.key_project.server.dto.ProofObligationKind;
import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.exec.ProjectLoader;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.registry.LoadedEnvironment;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

import org.jspecify.annotations.Nullable;

/** The {@code environment.*} methods: loading and releasing projects. */
public final class EnvironmentMethods {

    private final ServerOptions options;
    private final ServerUserInterfaceControl control;
    private final EnvironmentRegistry environments;
    private final TaskRunner tasks;
    private final ProofRegistry proofs;
    private final ProjectLoader loader;

    /**
     * Creates the handlers.
     *
     * @param options the instance configuration, used to resolve relative paths
     * @param control the control that loading runs through
     * @param environments where loaded environments are kept
     * @param tasks used to run loading off the request thread
     * @param proofs where the proofs of an environment are kept
     * @param loader brings a location in and registers what it produced
     */
    public EnvironmentMethods(ServerOptions options, ServerUserInterfaceControl control,
            EnvironmentRegistry environments, TaskRunner tasks, ProofRegistry proofs,
            ProjectLoader loader) {
        this.options = options;
        this.control = control;
        this.environments = environments;
        this.tasks = tasks;
        this.proofs = proofs;
        this.loader = loader;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        // Loading is INLINE because the handler only queues work: loading a real Java project takes
        // far longer than a request should, so it answers with a task handle straight away.
        dispatcher.register(new RpcMethod("environment.load", Concurrency.INLINE,
            params -> load(params.as(LoadRequest.class))));
        dispatcher.register(new RpcMethod("environment.list", Concurrency.INLINE,
            params -> environments.list(proofs::countFor)));
        dispatcher.register(new RpcMethod("environment.listProofObligations", Concurrency.INLINE,
            params -> listProofObligations(params.as(ListProofObligationsRequest.class))));
        dispatcher.register(new RpcMethod("environment.close", Concurrency.SERIAL,
            params -> close(params.as(EnvironmentRequest.class))));
    }

    private Object load(LoadRequest request) {
        Path file = resolve(request.path());
        if (!Files.exists(file)) {
            throw new RpcException(RpcErrorCode.LOAD_FAILED, "No such file or directory: " + file,
                RpcErrorData.of("Paths are resolved against the workspace " + options.workspace()),
                null);
        }
        List<Path> classPath = resolveAll(request.classpath());
        Path bootClassPath = request.bootClassPath() == null ? null
                : resolve(request.bootClassPath());
        List<Path> includes = resolveAll(request.includes());

        return tasks.launch(TaskKind.LOAD, null,
            task -> loader.load(file, classPath, bootClassPath, includes));
    }

    private Object close(EnvironmentRequest request) {
        String envId = request.env().envId();
        // Proofs first: they hold KeY state that belongs to this environment's InitConfig, so
        // they must go before the environment underneath them does.
        environments.require(envId);
        proofs.closeAllIn(envId);
        environments.close(envId);
        return new Ok(true);
    }

    private Object listProofObligations(ListProofObligationsRequest request) {
        LoadedEnvironment environment = environments.require(request.env().envId());
        SpecificationRepository repository =
            environment.environment().getSpecificationRepository();
        Set<String> alreadyProven = provenContractNames(repository);

        List<ProofObligation> obligations = new ArrayList<>();
        for (Contract contract : repository.getAllContracts()) {
            String targetClass = contract.getKJT().getFullName();
            if (request.targetClass() != null && !request.targetClass().equals(targetClass)) {
                continue;
            }
            if (!request.wantsLibraryClasses() && KeYTypeUtil.isLibraryClass(contract.getKJT())) {
                continue;
            }
            obligations.add(new ProofObligation(contract.getName(), kindOf(contract), targetClass,
                memberOf(contract), alreadyProven.contains(contract.getName())));
        }
        obligations.sort((left, right) -> left.contractId().compareTo(right.contractId()));
        return obligations;
    }

    /**
     * Names the contracts a proof already exists for in this environment.
     *
     * <p>
     * Read from the repository rather than from
     * {@code SpecificationRepository.getProofs(Contract)},
     * which asserts that the contract passed to it is atomic and would therefore fail on combined
     * contracts when assertions are enabled.
     *
     * @param repository the environment's specification repository
     * @return the unique internal names of contracts that have a proof
     */
    private static Set<String> provenContractNames(SpecificationRepository repository) {
        Set<String> names = new HashSet<>();
        for (Proof proof : repository.getAllProofs()) {
            ContractPO po = repository.getContractPOForProof(proof);
            if (po != null) {
                names.add(po.getContract().getName());
            }
        }
        return names;
    }

    /**
     * Names the method or observer a contract constrains.
     *
     * <p>
     * KeY qualifies the observer's own name with a class, which the {@code targetClass} field
     * already carries, so that prefix is dropped. The parameter types stay: without them
     * overloaded methods would be indistinguishable in a listing.
     *
     * @param contract the contract to describe
     * @return a signature such as {@code add(int, int)}
     */
    private static String memberOf(Contract contract) {
        IObserverFunction target = contract.getTarget();
        String qualified = target.name().toString();
        int separator = qualified.lastIndexOf("::");
        String simple = separator < 0 ? qualified : qualified.substring(separator + 2);

        StringBuilder signature = new StringBuilder(simple).append('(');
        for (int i = 0; i < target.getNumParams(); i++) {
            if (i > 0) {
                signature.append(", ");
            }
            signature.append(target.getParamType(i).getFullName());
        }
        return signature.append(')').toString();
    }

    /**
     * Classifies a contract by the type KeY built for it.
     *
     * <p>
     * The order matters: block and loop contracts are auxiliary contracts too, so the specific
     * cases have to be tested before the general one. Anything unrecognised is reported as
     * {@link ProofObligationKind#OTHER} rather than squeezed into a neighbouring kind.
     *
     * @param contract the contract to classify
     * @return the kind reported to clients
     */
    private static ProofObligationKind kindOf(Contract contract) {
        if (contract instanceof InformationFlowContract) {
            return ProofObligationKind.INFORMATION_FLOW;
        }
        if (contract instanceof FunctionalOperationContract) {
            return ProofObligationKind.FUNCTIONAL_OPERATION;
        }
        if (contract instanceof DependencyContract) {
            return ProofObligationKind.DEPENDENCY;
        }
        if (contract instanceof FunctionalBlockContract) {
            return ProofObligationKind.BLOCK;
        }
        if (contract instanceof FunctionalLoopContract) {
            return ProofObligationKind.LOOP;
        }
        if (contract instanceof FunctionalAuxiliaryContract) {
            return ProofObligationKind.FUNCTIONAL_AUXILIARY;
        }
        return ProofObligationKind.OTHER;
    }

    /**
     * Resolves a client-supplied path against the workspace.
     *
     * @param raw the path as the client wrote it
     * @return the resolved absolute path
     */
    private Path resolve(String raw) {
        try {
            return options.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS, e.getMessage(), null, e);
        }
    }

    private List<Path> resolveAll(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>(raw.size());
        for (String entry : raw) {
            paths.add(resolve(entry));
        }
        return paths;
    }
}
