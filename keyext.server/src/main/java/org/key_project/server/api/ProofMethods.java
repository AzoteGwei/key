/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.speclang.Contract;
import de.uka.ilkd.key.util.ProofStarter;

import org.key_project.server.ProofFacts;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.AutoModeOutcome;
import org.key_project.server.dto.AutoModeResult;
import org.key_project.server.dto.Ok;
import org.key_project.server.dto.ProofRef;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.exec.InterruptibleRun;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.registry.LoadedEnvironment;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.registry.RegisteredProof;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

/** The {@code proof.*} methods: starting proofs, running the search and reading the result. */
public final class ProofMethods {

    private final ServerUserInterfaceControl control;
    private final EnvironmentRegistry environments;
    private final ProofRegistry proofs;
    private final TaskRunner tasks;

    /**
     * Creates the handlers.
     *
     * @param control the control that owns KeY's proof control
     * @param environments where loaded environments are kept
     * @param proofs where started proofs are kept
     * @param tasks used to run proof search off the request thread
     */
    public ProofMethods(ServerUserInterfaceControl control, EnvironmentRegistry environments,
            ProofRegistry proofs, TaskRunner tasks) {
        this.control = control;
        this.environments = environments;
        this.proofs = proofs;
        this.tasks = tasks;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(new RpcMethod("proof.start", Concurrency.SERIAL,
            params -> start(params.as(StartProofRequest.class))));
        // Like environment.load, this only queues: the handler returns a task handle at once and
        // the search itself runs on the worker thread.
        dispatcher.register(new RpcMethod("proof.runAuto", Concurrency.INLINE,
            params -> runAuto(params.as(RunAutoRequest.class))));
        dispatcher.register(new RpcMethod("proof.getStatistics", Concurrency.INLINE,
            params -> statistics(params.as(ProofRequest.class))));
        dispatcher.register(new RpcMethod("proof.close", Concurrency.SERIAL,
            params -> close(params.as(ProofRequest.class))));
    }

    private ProofRef start(StartProofRequest request) {
        LoadedEnvironment environment = environments.require(request.env().envId());
        Contract contract = environment.environment().getSpecificationRepository()
                .getContractByName(request.contractId());
        if (contract == null) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "No such contract in this environment: " + request.contractId()
                    + ". Contract identifiers come from environment.listProofObligations.");
        }
        Proof proof;
        try {
            proof = environment.environment()
                    .createProof(
                        contract.createProofObl(environment.environment().getInitConfig()));
        } catch (Exception e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "KeY could not build a proof obligation for " + request.contractId(), null, e);
        }
        return proofs.register(environment.envId(), contract.getName(), proof);
    }

    private Object runAuto(RunAutoRequest request) {
        RegisteredProof registered = proofs.require(request.proof().proofId());
        Long timeoutMs = request.timeoutMs();
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "timeoutMs must be positive, got " + timeoutMs);
        }
        Proof proof = registered.proof();
        if (proof.isErroneous()) {
            // KeY refuses to search on a proof it marked erroneous, and does so silently. Saying
            // so here keeps a run that never happened from looking like one that found nothing.
            throw new RpcException(RpcErrorCode.PROOF_NOT_FOUND,
                "KeY marked proof " + registered.proofId()
                    + " erroneous; it will not run the automatic search on it");
        }
        ProofRef ref = new ProofRef(registered.proofId());
        return tasks.launchExclusive(TaskKind.AUTO, ref, task -> {
            // ProofStarter is what KeY's own proof control runs behind its auto-mode thread; the
            // difference is that this runs it here, where a failure is still ours to report.
            ProofStarter starter = new ProofStarter(control, false);
            starter.init(proof);
            InterruptibleRun.Result<?> run =
                InterruptibleRun.run(task, timeoutMs, starter::start);

            AutoModeOutcome outcome =
                run.timedOut() ? AutoModeOutcome.TIMEOUT : AutoModeOutcome.COMPLETED;
            // Read afterwards, from the proof itself: whether anything was achieved is KeY's
            // answer, not a conclusion drawn from the search having returned.
            return new AutoModeResult(ref, outcome, ProofFacts.describe(proof));
        });
    }

    private Object statistics(ProofRequest request) {
        return ProofFacts.describe(proofs.require(request.proof().proofId()).proof());
    }

    private Object close(ProofRequest request) {
        proofs.close(request.proof().proofId());
        return new Ok(true);
    }
}
