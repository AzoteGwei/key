/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.util.concurrent.TimeUnit;

import de.uka.ilkd.key.control.ProofControl;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.speclang.Contract;

import org.key_project.server.ProofFacts;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.AutoModeOutcome;
import org.key_project.server.dto.AutoModeResult;
import org.key_project.server.dto.Ok;
import org.key_project.server.dto.ProofRef;
import org.key_project.server.dto.TaskKind;
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

import org.jspecify.annotations.Nullable;

/** The {@code proof.*} methods: starting proofs, running the search and reading the result. */
public final class ProofMethods {

    /**
     * How often the worker thread looks at KeY's auto mode while waiting for it.
     *
     * <p>
     * Short enough that a cancellation or a timeout takes effect promptly, long enough that
     * waiting costs nothing measurable next to proof search.
     */
    private static final long POLL_MILLIS = 20;

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
            ProofControl proofControl = control.getProofControl();
            task.onCancel(proofControl::stopAutoMode);
            proofControl.startAutoMode(proof);
            AutoModeOutcome outcome = awaitAutoMode(proofControl, timeoutMs);
            // Read afterwards, from the proof itself: whether anything was achieved is KeY's
            // answer, not a conclusion drawn from the search having returned.
            return new AutoModeResult(ref, outcome, ProofFacts.describe(proof));
        });
    }

    /**
     * Waits for KeY's automatic search to end, interrupting it when its budget runs out.
     *
     * <p>
     * The budget is enforced by stopping the search, which is the same mechanism a client
     * cancellation uses: KeY checks for it between rule applications. It is deliberately not
     * implemented by writing to the strategy settings, because every {@code ProofSettings} object
     * persists property changes to the user's own configuration file.
     *
     * @param proofControl KeY's proof control, already running a search
     * @param timeoutMs the budget in milliseconds, or {@code null} for none
     * @return whether the search finished on its own or was cut short
     * @throws InterruptedException when the worker thread itself is interrupted
     */
    private AutoModeOutcome awaitAutoMode(ProofControl proofControl, @Nullable Long timeoutMs)
            throws InterruptedException {
        long deadline =
            System.nanoTime() + (timeoutMs == null ? 0 : TimeUnit.MILLISECONDS.toNanos(timeoutMs));
        AutoModeOutcome outcome = AutoModeOutcome.COMPLETED;
        while (proofControl.isInAutoMode()) {
            long remaining = deadline - System.nanoTime();
            if (timeoutMs != null && outcome == AutoModeOutcome.COMPLETED && remaining <= 0) {
                proofControl.stopAutoMode();
                outcome = AutoModeOutcome.TIMEOUT;
            }
            // Never sleep past the deadline: a budget of a few milliseconds has to mean a few
            // milliseconds, not however long the next poll happens to be.
            long sleep = POLL_MILLIS;
            if (timeoutMs != null && remaining > 0) {
                sleep =
                    Math.max(1, Math.min(POLL_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining)));
            }
            Thread.sleep(sleep);
        }
        return outcome;
    }

    private Object statistics(ProofRequest request) {
        return ProofFacts.describe(proofs.require(request.proof().proofId()).proof());
    }

    private Object close(ProofRequest request) {
        proofs.close(request.proof().proofId());
        return new Ok(true);
    }
}
