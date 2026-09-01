/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;

import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.diagnostics.ApplicableRuleProbe;
import org.key_project.server.diagnostics.StuckPointProbe;
import org.key_project.server.dto.GoalDiagnostics;
import org.key_project.server.dto.GoalRef;
import org.key_project.server.dto.ProofRef;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.registry.RegisteredProof;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

import org.jspecify.annotations.Nullable;

/**
 * The {@code diagnostics.*} methods: why a goal is not closing.
 *
 * <p>
 * Read-only, and answered on the request thread. That is the point of them — a client asks these
 * exactly when a long search has left it with open goals, so queueing them behind the worker
 * would make them useless at the moment they are wanted.
 */
public final class DiagnosticsMethods {

    private final ServerUserInterfaceControl control;
    private final ProofRegistry proofs;
    private final TaskRegistry tasks;

    /**
     * Creates the handlers.
     *
     * @param control the control whose proof control enumerates applicable rules
     * @param proofs where started proofs are kept
     * @param tasks used to tell whether a proof is being worked on right now
     */
    public DiagnosticsMethods(ServerUserInterfaceControl control, ProofRegistry proofs,
            TaskRegistry tasks) {
        this.control = control;
        this.proofs = proofs;
        this.tasks = tasks;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(new RpcMethod("diagnostics.explainGoal", Concurrency.INLINE,
            params -> explainGoal(params.as(ExplainGoalRequest.class))));
        dispatcher.register(new RpcMethod("diagnostics.listStuckPoints", Concurrency.INLINE,
            params -> listStuckPoints(params.as(ListStuckPointsRequest.class))));
        dispatcher.register(new RpcMethod("diagnostics.listApplicableRules", Concurrency.INLINE,
            params -> listApplicableRules(params.as(ListApplicableRulesRequest.class))));
    }

    private Object explainGoal(ExplainGoalRequest request) {
        RegisteredProof registered = requireIdleProof(request.goal().proofId());
        return StuckPointProbe.probe(requireGoal(registered.proof(), request.goal()),
            depth(request.maxDepth()), registered.lastSearchOutcome());
    }

    private Object listStuckPoints(ListStuckPointsRequest request) {
        RegisteredProof registered = requireIdleProof(request.proof().proofId());
        int maxDepth = depth(request.maxDepth());

        List<GoalDiagnostics> perGoal = new ArrayList<>();
        for (Goal goal : registered.proof().openGoals()) {
            perGoal.add(StuckPointProbe.probe(goal, maxDepth, registered.lastSearchOutcome()));
        }
        perGoal.sort((left, right) -> Integer.compare(left.goalId(), right.goalId()));
        return perGoal;
    }

    /**
     * Lists what a person could still apply to a goal.
     *
     * <p>
     * The complement of the stuck points. Those say what wants to apply and cannot; this says
     * what could apply and the automatic strategy did not choose, which after a search that ran
     * out of ideas is the only thing left to look at.
     *
     * @param request which goal, and how much to report
     * @return the rules, and whether there were more
     */
    private Object listApplicableRules(ListApplicableRulesRequest request) {
        RegisteredProof registered = requireIdleProof(request.goal().proofId());
        Goal goal = requireGoal(registered.proof(), request.goal());
        return ApplicableRuleProbe.probe(control.getProofControl(), goal,
            limit(request.maxRules()));
    }

    private static int limit(@Nullable Integer requested) {
        if (requested == null) {
            return ApplicableRuleProbe.DEFAULT_MAX_RULES;
        }
        if (requested < 1) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "maxRules must be at least 1, got " + requested);
        }
        return requested;
    }

    /**
     * Resolves a proof, provided nothing is currently changing it.
     *
     * <p>
     * Unlike {@code goal.getSequent}, which only reads terms that never change once built, the
     * probes build rule applications against live goal state. Doing that while a search is
     * rewriting the same proof is a race, and an answer computed from a proof mid-rewrite would
     * be worse than no answer. Clients cancel or wait, and are told which.
     *
     * <p>
     * Rule enumeration needs this for a second reason: KeY consults its interactive rule index
     * only while no automatic search is running. Asked during one it would quietly answer from
     * the strategy-filtered subset instead, which is a different question than the one asked.
     *
     * @param proofId the proof to resolve
     * @return the proof
     * @throws RpcException with {@link RpcErrorCode#TASK_CONFLICT} when it is busy
     */
    private RegisteredProof requireIdleProof(String proofId) {
        String busy = tasks.activeTaskFor(new ProofRef(proofId));
        if (busy != null) {
            throw new RpcException(RpcErrorCode.TASK_CONFLICT, "Proof " + proofId
                + " is being worked on by task " + busy
                + "; the probe reads live goal state, so it waits for that to finish");
        }
        return proofs.require(proofId);
    }

    private static Goal requireGoal(Proof proof, GoalRef ref) {
        for (Goal goal : proof.openGoals()) {
            if (goal.node().serialNr() == ref.goalId()) {
                return goal;
            }
        }
        throw new RpcException(RpcErrorCode.GOAL_NOT_FOUND,
            "Proof " + ref.proofId() + " has no open goal " + ref.goalId());
    }

    private static int depth(@Nullable Integer requested) {
        if (requested == null) {
            return StuckPointProbe.DEFAULT_MAX_DEPTH;
        }
        if (requested < 0) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "maxDepth must not be negative, got " + requested);
        }
        return requested;
    }
}
