/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import de.uka.ilkd.key.macros.ProofMacro;
import de.uka.ilkd.key.nparser.ParsingFacade;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Node;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.scripts.ScriptException;

import org.key_project.server.ProofFacts;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.GoalRef;
import org.key_project.server.dto.GoalSummary;
import org.key_project.server.dto.MacroInfo;
import org.key_project.server.dto.ProofRef;
import org.key_project.server.dto.ProofRunResult;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.exec.InterruptibleRun;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.pp.SequentRenderer;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.registry.RegisteredProof;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.KeyErrors;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;


/**
 * The {@code goal.*} methods: seeing where a proof is stuck and doing something about it.
 *
 * <p>
 * These are what makes the server worth running rather than shelling out to {@code key --auto}: an
 * agent can read the open goals, look at the sequent that defeated the prover, and try a script
 * against it without paying for a load again.
 */
public final class GoalMethods {

    /**
     * The macros KeY offers, keyed by their script command name.
     *
     * <p>
     * Keyed by script name, not display name, so the identifier a client gets from
     * {@code goal.listAvailableMacros} is the same word it can write inside a proof script. KeY's
     * own {@code macro} script command builds its map the same way.
     */
    private static final Map<String, ProofMacro> MACROS = loadMacros();

    private final ServerUserInterfaceControl control;
    private final ProofRegistry proofs;
    private final TaskRunner tasks;

    /**
     * Creates the handlers.
     *
     * @param control the control macros and scripts report through
     * @param proofs where started proofs are kept
     * @param tasks used to run long operations off the request thread
     */
    public GoalMethods(ServerUserInterfaceControl control, ProofRegistry proofs,
            TaskRunner tasks) {
        this.control = control;
        this.proofs = proofs;
        this.tasks = tasks;
    }

    private static Map<String, ProofMacro> loadMacros() {
        Map<String, ProofMacro> macros = new HashMap<>();
        for (ProofMacro macro : ServiceLoader.load(ProofMacro.class)) {
            String scriptName = macro.getScriptCommandName();
            if (scriptName != null) {
                macros.put(scriptName, macro);
            }
        }
        return Map.copyOf(macros);
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(new RpcMethod("goal.list", Concurrency.INLINE,
            params -> list(params.as(GoalListRequest.class))));
        dispatcher.register(new RpcMethod("goal.getSequent", Concurrency.INLINE,
            params -> sequent(params.as(SequentRequest.class))));
        dispatcher.register(new RpcMethod("goal.listAvailableMacros", Concurrency.INLINE,
            params -> macros(params.as(ProofRequest.class))));
        dispatcher.register(new RpcMethod("goal.applyMacro", Concurrency.INLINE,
            params -> applyMacro(params.as(ApplyMacroRequest.class))));
        dispatcher.register(new RpcMethod("goal.applyScript", Concurrency.INLINE,
            params -> applyScript(params.as(ApplyScriptRequest.class))));
    }

    private Object list(GoalListRequest request) {
        Proof proof = proofs.require(request.proof().proofId()).proof();
        List<GoalSummary> summaries = new ArrayList<>();
        for (Goal goal : proof.openGoals()) {
            summaries.add(summarise(request.proof().proofId(), goal));
        }
        if (request.wantsClosed()) {
            for (Goal goal : proof.closedGoals()) {
                summaries.add(summarise(request.proof().proofId(), goal));
            }
        }
        summaries.sort((left, right) -> Integer.compare(left.goalId(), right.goalId()));
        return summaries;
    }

    private static GoalSummary summarise(String proofId, Goal goal) {
        int serial = goal.node().serialNr();
        return new GoalSummary(new GoalRef(proofId, serial), serial, serial,
            !goal.node().isClosed(), goal.isLinked());
    }

    private Object sequent(SequentRequest request) {
        Goal goal = requireGoal(request.goal());
        return SequentRenderer.render(goal.sequent(), goal.proof().getServices(),
            request.formatOrDefault());
    }

    private Object macros(ProofRequest request) {
        // Resolved even though the list does not depend on it: a client that asks about a proof
        // that is gone should hear about that, not receive a list it cannot use.
        proofs.require(request.proof().proofId());

        List<MacroInfo> available = new ArrayList<>(MACROS.size());
        MACROS.forEach((scriptName, macro) -> available.add(new MacroInfo(scriptName,
            macro.getName(), macro.getCategory(), macro.getDescription())));
        available.sort((left, right) -> left.macroId().compareTo(right.macroId()));
        return available;
    }

    private Object applyMacro(ApplyMacroRequest request) {
        RegisteredProof registered = proofs.require(request.proof().proofId());
        ProofMacro macro = MACROS.get(request.macroId());
        if (macro == null) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS, "No such macro: "
                + request.macroId() + ". Macro identifiers come from goal.listAvailableMacros.");
        }
        GoalRef goalRef = request.goal();
        if (goalRef != null && !goalRef.proofId().equals(request.proof().proofId())) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "The goal belongs to a different proof than the one given");
        }
        Node node = goalRef == null ? registered.proof().root() : requireGoal(goalRef).node();
        if (!macro.canApplyTo(node, null)) {
            // KeY's macros do nothing at all when they do not apply. Left to itself that would
            // surface as a task that succeeded and changed nothing, which reads like a result.
            throw new RpcException(RpcErrorCode.INVALID_PARAMS, "Macro " + request.macroId()
                + " does not apply to this node");
        }

        ProofRef ref = new ProofRef(registered.proofId());
        return tasks.launchExclusive(TaskKind.MACRO, ref, task -> {
            InterruptibleRun.run(task, null,
                () -> macro.applyTo(control, node, null, control));
            return new ProofRunResult(ref, ProofFacts.describe(registered.proof()));
        });
    }

    private Object applyScript(ApplyScriptRequest request) {
        Goal goal = requireGoal(request.goal());
        RegisteredProof registered = proofs.require(request.goal().proofId());
        var parsed = parse(request.script());

        ProofRef ref = new ProofRef(registered.proofId());
        return tasks.launchExclusive(TaskKind.SCRIPT, ref, task -> {
            ProofScriptEngine engine = new ProofScriptEngine(registered.proof());
            engine.setInitiallySelectedGoal(goal);
            InterruptibleRun.run(task, null, () -> {
                try {
                    engine.execute(control, parsed);
                } catch (ScriptException e) {
                    throw new RpcException(RpcErrorCode.SCRIPT_ERROR,
                        "Proof script failed: " + e.getMessage(),
                        KeyErrors.at(e.getLocation(), String.valueOf(e.getMessage())), e);
                }
                return null;
            });
            return new ProofRunResult(ref, ProofFacts.describe(registered.proof()));
        });
    }

    /**
     * Parses a script before any task is created.
     *
     * <p>
     * A script that does not parse is the client's mistake and it can be reported straight away,
     * rather than making the client poll a task to be told about a typo.
     *
     * @param script the script source
     * @return the parsed script
     */
    private static de.uka.ilkd.key.nparser.KeyAst.ProofScript parse(String script) {
        try {
            return ParsingFacade.parseScript(script);
        } catch (Exception e) {
            throw new RpcException(RpcErrorCode.SCRIPT_ERROR,
                "Proof script does not parse: " + e.getMessage(), KeyErrors.describe(e), e);
        }
    }

    /**
     * Resolves a goal reference.
     *
     * @param ref the reference to resolve
     * @return the goal it names
     * @throws RpcException with {@link RpcErrorCode#GOAL_NOT_FOUND} when the proof has no such
     *         open goal
     */
    private Goal requireGoal(GoalRef ref) {
        Proof proof = proofs.require(ref.proofId()).proof();
        for (Goal goal : proof.openGoals()) {
            if (goal.node().serialNr() == ref.goalId()) {
                return goal;
            }
        }
        throw new RpcException(RpcErrorCode.GOAL_NOT_FOUND, "Proof " + ref.proofId()
            + " has no open goal " + ref.goalId() + "; goals close and are replaced as a proof "
            + "grows, so list them again");
    }
}
