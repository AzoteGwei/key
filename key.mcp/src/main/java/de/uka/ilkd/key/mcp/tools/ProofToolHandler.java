/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.operation.Operation;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.rule.BuiltInRule;
import de.uka.ilkd.key.rule.Taclet;
import de.uka.ilkd.key.scripts.ScriptException;
import de.uka.ilkd.key.settings.ProofSettings;
import de.uka.ilkd.key.strategy.StrategyProperties;

/**
 * Tools for creating proofs, running auto mode and interacting with open goals.
 */
public class ProofToolHandler extends ToolHandler {

    public ProofToolHandler(ToolContext ctx) {
        super(ctx);
    }

    @Override
    public void registerTools(Map<String, ToolDefinition> tools) {
        register(tools, "key_proof_create",
            "Create a proof for a contract without starting auto mode.",
            props("contractId", Map.of("type", "string")), List.of("contractId"),
            this::handleProofCreate);

        register(tools, "key_proof_auto", "Create a proof for a contract and run KeY auto mode.",
            props(
                "contractId", Map.of("type", "string"),
                "timeoutMs", Map.of("type", "integer", "minimum", 1000),
                "maxSteps", Map.of("type", "integer", "minimum", 1),
                "strategyOptions", Map.of("type", "object"),
                "async", Map.of("type", "boolean", "default", true)),
            List.of("contractId", "timeoutMs", "maxSteps"), this::handleProofAuto);

        register(tools, "key_proof_status", "Get the status of a proof.",
            props("proofId", Map.of("type", "string")), List.of("proofId"),
            this::handleProofStatus);

        register(tools, "key_proof_goals_list", "List all open goals of a proof.",
            props("proofId", Map.of("type", "string")), List.of("proofId"),
            this::handleProofGoalsList);

        register(tools, "key_proof_goal_get", "Get the sequent of a specific open goal.",
            props(
                "proofId", Map.of("type", "string"),
                "goalId", Map.of("type", "integer")),
            List.of("proofId", "goalId"), this::handleProofGoalGet);

        register(tools, "key_proof_rule_apply",
            "Apply a rule by name to the given goal. Use key_proof_rules_list to discover valid "
                + "rule names. Additional KeY proof-script options (on, formula, occ, matches, "
                + "assumes, inst_*) can be passed via 'parameters'.",
            props(
                "proofId", Map.of("type", "string"),
                "goalId", Map.of("type", "integer"),
                "ruleName", Map.of("type", "string"),
                "parameters", Map.of("type", "object", "description",
                    "Additional rule options, e.g. {\"on\": \"x + y\", \"occ\": 1}")),
            List.of("proofId", "goalId", "ruleName"), this::handleProofRuleApply);

        register(tools, "key_proof_script_run",
            "Run a KeY proof script on the current proof. Useful commands: auto, select "
                + "number=N, rule <name> [on=... formula=... occ=... inst_*=...], cut, "
                + "instantiate, macro <name>, tryclose, smt. Commands end with ';'.",
            props(
                "proofId", Map.of("type", "string"),
                "script", Map.of("type", "string")),
            List.of("proofId", "script"), this::handleProofScriptRun);

        register(tools, "key_proof_rules_list",
            "List rule names usable with key_proof_rule_apply: all built-in rules plus active "
                + "taclets. Pass 'filter' (case-insensitive substring) to search taclets; "
                + "without a filter only the taclet count is returned.",
            props(
                "proofId", Map.of("type", "string"),
                "filter", Map.of("type", "string")),
            List.of("proofId"), this::handleProofRulesList);

        register(tools, "key_proof_undo", "Undo the last rule application on the given goal.",
            props(
                "proofId", Map.of("type", "string"),
                "goalId", Map.of("type", "integer")),
            List.of("proofId", "goalId"), this::handleProofUndo);
    }

    private Map<String, Object> handleProofCreate(Map<String, Object> params) {
        String contractId = ToolContext.requireString(params, "contractId");
        Proof proof = ctx.createProof(contractId);
        String proofId = ctx.session().nextProofId(contractId);
        ctx.session().registerProof(proofId, proof);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("status", "created");
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofAuto(Map<String, Object> params) {
        String contractId = ToolContext.requireString(params, "contractId");
        long timeoutMs =
            ToolContext.longValue(params.get("timeoutMs"), ctx.config().defaultTimeoutMs());
        long maxSteps =
            ToolContext.longValue(params.get("maxSteps"), ctx.config().defaultMaxSteps());
        Object strategyOptions = params.get("strategyOptions");
        boolean async = ToolContext.boolValue(params.get("async"), true);

        Proof proof = ctx.createProof(contractId);
        String proofId = ctx.session().nextProofId(contractId);
        ctx.session().registerProof(proofId, proof);

        configureStrategy(proof, maxSteps, strategyOptions);

        Operation operation = ctx.session().getOperationTracker().start(proofId, "proof_auto");
        Thread worker = new Thread(() -> runAutoMode(operation, proof, timeoutMs),
            "key-proof-auto-" + operation.getId());
        worker.setDaemon(true);
        operation.setWorkerThread(worker);
        worker.start();

        if (async) {
            Map<String, Object> result = Json.object();
            result.put("proofId", proofId);
            result.put("operationId", operation.getId());
            result.put("status", "running");
            return result;
        } else {
            try {
                worker.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new McpToolException(-32603, "Interrupted while waiting for proof",
                    e.getMessage());
            }
            return ctx.statusOf(operation);
        }
    }

    private Map<String, Object> handleProofStatus(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        Proof proof = ctx.requireProof(proofId);
        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("closed", proof.openGoals().isEmpty());
        result.put("openGoals", proof.openGoals().size());
        result.put("usedSteps", proof.countNodes());
        return result;
    }

    private Map<String, Object> handleProofGoalsList(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        Proof proof = ctx.requireProof(proofId);
        List<Map<String, Object>> goals = new ArrayList<>();
        int index = 0;
        for (Goal goal : proof.openGoals()) {
            Map<String, Object> item = Json.object();
            item.put("goalId", index);
            item.put("serialNr", goal.node().serialNr());
            item.put("sequent", goal.sequent().toString());
            goals.add(item);
            index++;
        }
        return Map.of("proofId", proofId, "goals", goals);
    }

    private Map<String, Object> handleProofGoalGet(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        int goalId = ToolContext.intValue(params.get("goalId"));
        Proof proof = ctx.requireProof(proofId);
        Goal goal = ctx.openGoalByIndex(proof, goalId);
        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("serialNr", goal.node().serialNr());
        result.put("sequent", goal.sequent().toString());
        return result;
    }

    private Map<String, Object> handleProofRuleApply(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        int goalId = ToolContext.intValue(params.get("goalId"));
        String ruleName = ToolContext.requireString(params, "ruleName");
        Proof proof = ctx.requireProof(proofId);

        StringBuilder script = new StringBuilder();
        script.append("select number=").append(goalId).append(";\n");
        // Rule names may contain spaces (e.g. "One Step Simplification"), so always quote.
        script.append("rule ").append(ToolContext.scriptValue(ruleName));
        Object parameters = params.get("parameters");
        if (parameters instanceof Map<?, ?> options) {
            for (Map.Entry<?, ?> entry : options.entrySet()) {
                script.append(' ').append(entry.getKey().toString()).append('=')
                        .append(ToolContext.scriptValue(entry.getValue()));
            }
        }
        script.append(';');
        try {
            ctx.executeScript(proof, script.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException(-32603, "Interrupted", e.getMessage());
        } catch (ScriptException e) {
            throw new McpToolException(-32603, "Rule application failed: " + e.getMessage(),
                e.getMessage());
        }

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("ruleName", ruleName);
        result.put("script", script.toString());
        result.put("applied", true);
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofScriptRun(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        String script = ToolContext.requireString(params, "script");
        Proof proof = ctx.requireProof(proofId);

        try {
            ctx.executeScript(proof, script);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException(-32603, "Interrupted", e.getMessage());
        } catch (ScriptException e) {
            throw new McpToolException(-32603, "Script failed: " + e.getMessage(), e.getMessage());
        }

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("scriptExecuted", true);
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofRulesList(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        String filter = (String) params.get("filter");
        String filterLower = filter == null ? null : filter.toLowerCase(java.util.Locale.ROOT);
        Proof proof = ctx.requireProof(proofId);

        List<Map<String, Object>> builtIns = new ArrayList<>();
        for (BuiltInRule rule : proof.getInitConfig().getProfile().getStandardRules()
                .standardBuiltInRules()) {
            String name = rule.name().toString();
            if (filterLower != null && !name.toLowerCase(java.util.Locale.ROOT)
                    .contains(filterLower)) {
                continue;
            }
            Map<String, Object> item = Json.object();
            item.put("name", name);
            item.put("displayName", rule.displayName());
            builtIns.add(item);
        }
        builtIns.sort(java.util.Comparator.comparing(m -> (String) m.get("name")));

        Collection<Taclet> activeTaclets = proof.getInitConfig().activatedTaclets();

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("builtInRules", builtIns);
        result.put("tacletCount", activeTaclets.size());

        if (filterLower == null) {
            result.put("tacletHint",
                "Pass 'filter' (case-insensitive substring) to list matching taclet names.");
        } else {
            List<String> matches = new ArrayList<>();
            for (Taclet taclet : activeTaclets) {
                String name = taclet.name().toString();
                if (name.toLowerCase(java.util.Locale.ROOT).contains(filterLower)) {
                    matches.add(name);
                }
            }
            java.util.Collections.sort(matches);
            boolean truncated = matches.size() > 200;
            if (truncated) {
                matches = matches.subList(0, 200);
            }
            result.put("taclets", matches);
            if (truncated) {
                result.put("tacletsTruncated", true);
            }
        }
        return result;
    }

    private Map<String, Object> handleProofUndo(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        int goalId = ToolContext.intValue(params.get("goalId"));
        Proof proof = ctx.requireProof(proofId);
        Goal goal = ctx.openGoalByIndex(proof, goalId);
        proof.pruneProof(goal);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("undone", true);
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private void configureStrategy(Proof proof, long maxSteps, Object strategyOptions) {
        StrategyProperties sp =
            proof.getSettings().getStrategySettings().getActiveStrategyProperties();
        sp.setProperty(StrategyProperties.METHOD_OPTIONS_KEY, StrategyProperties.METHOD_CONTRACT);
        sp.setProperty(StrategyProperties.DEP_OPTIONS_KEY, StrategyProperties.DEP_ON);
        sp.setProperty(StrategyProperties.QUERY_OPTIONS_KEY, StrategyProperties.QUERY_ON);
        sp.setProperty(StrategyProperties.NON_LIN_ARITH_OPTIONS_KEY,
            StrategyProperties.NON_LIN_ARITH_DEF_OPS);
        sp.setProperty(StrategyProperties.STOPMODE_OPTIONS_KEY,
            StrategyProperties.STOPMODE_NONCLOSE);

        if (strategyOptions instanceof Map<?, ?> options) {
            for (Map.Entry<?, ?> entry : options.entrySet()) {
                sp.setProperty(entry.getKey().toString(), entry.getValue().toString());
            }
        }

        proof.getSettings().getStrategySettings().setActiveStrategyProperties(sp);
        proof.getSettings().getStrategySettings().setMaxSteps((int) maxSteps);
        ProofSettings.DEFAULT_SETTINGS.getStrategySettings().setMaxSteps((int) maxSteps);
        ProofSettings.DEFAULT_SETTINGS.getStrategySettings().setActiveStrategyProperties(sp);
        proof.setActiveStrategy(
            proof.getServices().getProfile().getDefaultStrategyFactory().create(proof, sp));
    }

    private void runAutoMode(Operation operation, Proof proof, long timeoutMs) {
        Timer timer = new Timer(true);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                Thread worker = operation.getWorkerThread();
                if (worker != null && worker.isAlive()) {
                    worker.interrupt();
                }
                operation.addTimeoutEvent();
            }
        }, timeoutMs);

        ProgressWatcher watcher = new ProgressWatcher(operation, proof);
        watcher.start();

        long start = System.currentTimeMillis();
        try {
            ctx.session().getEnvironment().getUi().getProofControl().startAndWaitForAutoMode(proof);
            if (operation.getState() == Operation.State.RUNNING) {
                long duration = System.currentTimeMillis() - start;
                operation.addCompletedEvent(proof.openGoals().isEmpty(), proof.openGoals().size(),
                    proof.countNodes(), duration);
            }
        } catch (Exception e) {
            if (operation.getState() == Operation.State.RUNNING) {
                if (Thread.currentThread().isInterrupted()) {
                    operation.addTimeoutEvent();
                } else {
                    operation.addErrorEvent(e.getMessage());
                }
            }
        } finally {
            timer.cancel();
            watcher.stopWatching();
        }
    }

    private static class ProgressWatcher {
        private final Operation operation;
        private final Proof proof;
        private volatile boolean stopped;
        private final Thread thread;

        ProgressWatcher(Operation operation, Proof proof) {
            this.operation = operation;
            this.proof = proof;
            this.thread = new Thread(this::watch, "key-progress-watcher");
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stopWatching() {
            stopped = true;
            thread.interrupt();
        }

        private void watch() {
            while (!stopped && operation.getState() == Operation.State.RUNNING) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                if (!stopped && operation.getState() == Operation.State.RUNNING) {
                    operation.addProgressEvent(proof.countNodes(), proof.openGoals().size());
                }
            }
        }
    }
}
