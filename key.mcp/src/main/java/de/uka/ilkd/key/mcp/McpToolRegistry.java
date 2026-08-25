/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import de.uka.ilkd.key.control.AbstractUserInterfaceControl;
import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.operation.Operation;
import de.uka.ilkd.key.mcp.operation.Operation.State;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.nparser.KeyAst;
import de.uka.ilkd.key.nparser.ParsingFacade;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.init.ProofInputException;
import de.uka.ilkd.key.proof.io.OutputStreamProofSaver;
import de.uka.ilkd.key.proof.io.ProblemLoaderException;
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.scripts.ScriptException;
import de.uka.ilkd.key.settings.DefaultSMTSettings;
import de.uka.ilkd.key.settings.ProofIndependentSMTSettings;
import de.uka.ilkd.key.settings.ProofSettings;
import de.uka.ilkd.key.smt.SMTSettings;
import de.uka.ilkd.key.smt.SmtLib2Translator;
import de.uka.ilkd.key.speclang.Contract;
import de.uka.ilkd.key.strategy.StrategyProperties;

/**
 * Registry for MCP tools backed by a KeY session.
 */
public class McpToolRegistry {
    private final McpServerConfig config;
    private final McpSession session;

    public McpToolRegistry(McpServerConfig config, McpSession session) {
        this.config = config;
        this.session = session;
    }

    public List<Map<String, Object>> listTools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(sessionInfo());
        tools.add(sessionReset());
        tools.add(sessionDispose());
        tools.add(projectLoad());
        tools.add(contractsList());
        tools.add(proofCreate());
        tools.add(proofAuto());
        tools.add(proofStatus());
        tools.add(operationWait());
        tools.add(operationCancel());
        tools.add(proofGoalsList());
        tools.add(proofGoalGet());
        tools.add(proofRuleApply());
        tools.add(proofScriptRun());
        tools.add(proofUndo());
        tools.add(proofExport());
        tools.add(proofSmt());
        tools.add(proofCounterexample());
        return tools;
    }

    public Map<String, Object> execute(String name, Map<String, Object> params) {
        return switch (name) {
        case "key_session_info" -> handleSessionInfo(params);
        case "key_session_reset" -> handleSessionReset(params);
        case "key_session_dispose" -> handleSessionDispose(params);
        case "key_project_load" -> handleProjectLoad(params);
        case "key_contracts_list" -> handleContractsList(params);
        case "key_proof_create" -> handleProofCreate(params);
        case "key_proof_auto" -> handleProofAuto(params);
        case "key_proof_status" -> handleProofStatus(params);
        case "key_operation_wait" -> handleOperationWait(params);
        case "key_operation_cancel" -> handleOperationCancel(params);
        case "key_proof_goals_list" -> handleProofGoalsList(params);
        case "key_proof_goal_get" -> handleProofGoalGet(params);
        case "key_proof_rule_apply" -> handleProofRuleApply(params);
        case "key_proof_script_run" -> handleProofScriptRun(params);
        case "key_proof_undo" -> handleProofUndo(params);
        case "key_proof_export" -> handleProofExport(params);
        case "key_proof_smt" -> handleProofSmt(params);
        case "key_proof_counterexample" -> handleProofCounterexample(params);
        default -> throw new IllegalArgumentException("Tool not implemented: " + name);
        };
    }

    private Map<String, Object> sessionInfo() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_info");
        schema.put("description", "Get information about the current MCP session.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> sessionReset() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_reset");
        schema.put("description", "Reset the session, disposing all proofs and the current KeY environment.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> sessionDispose() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_session_dispose");
        schema.put("description", "Dispose the session and release all resources.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> projectLoad() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_project_load");
        schema.put("description", "Load a KeY project from a directory or .key file.");

        Map<String, Object> properties = Json.object();
        properties.put("location", Map.of("type", "string", "description", "Path to the project directory or .key file"));
        properties.put("classPaths", Map.of("type", "array", "items", Map.of("type", "string")));
        properties.put("bootClassPath", Map.of("type", "string"));
        properties.put("includes", Map.of("type", "array", "items", Map.of("type", "string")));

        schema.put("inputSchema", Map.of("type", "object", "properties", properties, "required", List.of("location")));
        return schema;
    }

    private Map<String, Object> contractsList() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_contracts_list");
        schema.put("description", "List all verification contracts in the loaded project.");
        schema.put("inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of()));
        return schema;
    }

    private Map<String, Object> handleSessionInfo(Map<String, Object> params) {
        Map<String, Object> result = Json.object();
        result.put("sessionId", session.getId());
        result.put("environmentLoaded", session.getEnvironment() != null);
        result.put("contractCount", session.getContracts().size());
        result.put("proofCount", session.getProofs().size());
        return result;
    }

    private Map<String, Object> handleSessionReset(Map<String, Object> params) {
        session.dispose();
        return Map.of("success", true);
    }

    private Map<String, Object> handleSessionDispose(Map<String, Object> params) {
        session.dispose();
        return Map.of("success", true, "disposed", true);
    }

    private Map<String, Object> handleProjectLoad(Map<String, Object> params) {
        String location = (String) params.get("location");
        Path projectPath = PathValidator.resolveAndValidate(location, config.workspace(), config.allowedPaths());

        List<Path> classPaths = toPathList(params.get("classPaths"));
        Path bootClassPath = toPath(params.get("bootClassPath"));
        List<Path> includes = toPathList(params.get("includes"));

        try {
            KeYEnvironment<?> env = KeYEnvironment.load(projectPath, classPaths, bootClassPath, includes);
            session.dispose();
            session.setEnvironment(env);
            session.loadContracts();

            Map<String, Object> result = Json.object();
            result.put("success", true);
            result.put("loadedTypes", env.getJavaInfo().getAllKeYJavaTypes().size());
            result.put("contractCount", session.getContracts().size());
            return result;
        } catch (ProblemLoaderException e) {
            throw new McpToolException(-32603, "Failed to load project: " + e.getMessage(), e.getMessage());
        }
    }

    private Map<String, Object> handleContractsList(Map<String, Object> params) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Contract> entry : session.getContracts().entrySet()) {
            Map<String, Object> item = Json.object();
            item.put("contractId", entry.getKey());
            item.put("targetName", entry.getValue().getTarget().name().toString());
            item.put("displayName", entry.getValue().getDisplayName());
            item.put("type", entry.getValue().getClass().getSimpleName());
            list.add(item);
        }
        return Map.of("contracts", list);
    }

    private Map<String, Object> proofCreate() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_create");
        schema.put("description", "Create a proof for a contract without starting auto mode.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("contractId", Map.of("type", "string")),
            "required", List.of("contractId")));
        return schema;
    }

    private Map<String, Object> proofAuto() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_auto");
        schema.put("description", "Create a proof for a contract and run KeY auto mode.");
        Map<String, Object> properties = Json.object();
        properties.put("contractId", Map.of("type", "string"));
        properties.put("timeoutMs", Map.of("type", "integer", "minimum", 1000));
        properties.put("maxSteps", Map.of("type", "integer", "minimum", 1));
        properties.put("strategyOptions", Map.of("type", "object"));
        properties.put("async", Map.of("type", "boolean", "default", true));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("contractId", "timeoutMs", "maxSteps")));
        return schema;
    }

    private Map<String, Object> proofStatus() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_status");
        schema.put("description", "Get the status of a proof.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("proofId", Map.of("type", "string")),
            "required", List.of("proofId")));
        return schema;
    }

    private Map<String, Object> operationWait() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_operation_wait");
        schema.put("description", "Poll events for a long-running operation.");
        Map<String, Object> properties = Json.object();
        properties.put("operationId", Map.of("type", "string"));
        properties.put("timeoutMs", Map.of("type", "integer", "default", 30000));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("operationId")));
        return schema;
    }

    private Map<String, Object> operationCancel() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_operation_cancel");
        schema.put("description", "Cancel a long-running operation.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("operationId", Map.of("type", "string")),
            "required", List.of("operationId")));
        return schema;
    }

    private Map<String, Object> proofGoalsList() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_goals_list");
        schema.put("description", "List all open goals of a proof.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("proofId", Map.of("type", "string")),
            "required", List.of("proofId")));
        return schema;
    }

    private Map<String, Object> proofGoalGet() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_goal_get");
        schema.put("description", "Get the sequent of a specific open goal.");
        Map<String, Object> properties = Json.object();
        properties.put("proofId", Map.of("type", "string"));
        properties.put("goalId", Map.of("type", "integer"));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("proofId", "goalId")));
        return schema;
    }

    private Map<String, Object> proofRuleApply() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_rule_apply");
        schema.put("description", "Apply a rule by name to the given goal.");
        Map<String, Object> properties = Json.object();
        properties.put("proofId", Map.of("type", "string"));
        properties.put("goalId", Map.of("type", "integer"));
        properties.put("ruleName", Map.of("type", "string"));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("proofId", "goalId", "ruleName")));
        return schema;
    }

    private Map<String, Object> proofScriptRun() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_script_run");
        schema.put("description", "Run a KeY proof script on the current proof.");
        Map<String, Object> properties = Json.object();
        properties.put("proofId", Map.of("type", "string"));
        properties.put("script", Map.of("type", "string"));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("proofId", "script")));
        return schema;
    }

    private Map<String, Object> proofUndo() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_undo");
        schema.put("description", "Undo the last rule application on the given goal.");
        Map<String, Object> properties = Json.object();
        properties.put("proofId", Map.of("type", "string"));
        properties.put("goalId", Map.of("type", "integer"));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("proofId", "goalId")));
        return schema;
    }

    private Map<String, Object> proofExport() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_export");
        schema.put("description", "Export a proof as a KeY .proof file or as a JSON tree.");
        Map<String, Object> properties = Json.object();
        properties.put("proofId", Map.of("type", "string"));
        properties.put("format", Map.of("type", "string", "enum", List.of("proof", "json"), "default", "proof"));
        properties.put("path", Map.of("type", "string"));
        schema.put("inputSchema", Map.of("type", "object", "properties", properties,
            "required", List.of("proofId")));
        return schema;
    }

    private Map<String, Object> proofSmt() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_smt");
        schema.put("description", "Translate the first open goal of a proof to SMT-LIB format.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("proofId", Map.of("type", "string")),
            "required", List.of("proofId")));
        return schema;
    }

    private Map<String, Object> proofCounterexample() {
        Map<String, Object> schema = Json.object();
        schema.put("name", "key_proof_counterexample");
        schema.put("description", "Get a counterexample or error trace for a proof, if available.");
        schema.put("inputSchema", Map.of("type", "object",
            "properties", Map.of("proofId", Map.of("type", "string")),
            "required", List.of("proofId")));
        return schema;
    }

    private Map<String, Object> handleProofCreate(Map<String, Object> params) {
        String contractId = (String) params.get("contractId");
        Proof proof = createProof(contractId);
        String proofId = session.nextProofId(contractId);
        session.registerProof(proofId, proof);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("status", "created");
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofAuto(Map<String, Object> params) {
        String contractId = (String) params.get("contractId");
        long timeoutMs = longValue(params.get("timeoutMs"), config.defaultTimeoutMs());
        long maxSteps = longValue(params.get("maxSteps"), config.defaultMaxSteps());
        Object strategyOptions = params.get("strategyOptions");
        boolean async = boolValue(params.get("async"), true);

        Proof proof = createProof(contractId);
        String proofId = session.nextProofId(contractId);
        session.registerProof(proofId, proof);

        configureStrategy(proof, maxSteps, strategyOptions);

        Operation operation = session.getOperationTracker().start(proofId, "proof_auto");
        Thread worker = new Thread(() -> runAutoMode(operation, proof, timeoutMs), "key-proof-auto-" + operation.getId());
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
                throw new McpToolException(-32603, "Interrupted while waiting for proof", e.getMessage());
            }
            return statusOf(operation);
        }
    }

    private Map<String, Object> handleProofStatus(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        Proof proof = session.getProof(proofId);
        if (proof == null) {
            throw new McpToolException(-32002, "Proof not found: " + proofId, null);
        }
        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("closed", proof.openGoals().isEmpty());
        result.put("openGoals", proof.openGoals().size());
        result.put("usedSteps", proof.countNodes());
        return result;
    }

    private Map<String, Object> handleOperationWait(Map<String, Object> params) {
        String operationId = (String) params.get("operationId");
        long waitTimeoutMs = longValue(params.get("timeoutMs"), 30000L);
        Operation operation = session.getOperationTracker().get(operationId);
        if (operation == null) {
            throw new McpToolException(-32003, "Operation not found: " + operationId, null);
        }
        try {
            operation.await(waitTimeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return statusOf(operation);
    }

    private Map<String, Object> handleOperationCancel(Map<String, Object> params) {
        String operationId = (String) params.get("operationId");
        Operation operation = session.getOperationTracker().get(operationId);
        if (operation == null) {
            throw new McpToolException(-32003, "Operation not found: " + operationId, null);
        }
        Thread worker = operation.getWorkerThread();
        if (worker != null && worker.isAlive()) {
            worker.interrupt();
        }
        operation.addCancelledEvent();
        return Map.of("cancelled", true);
    }

    private Map<String, Object> handleProofGoalsList(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        Proof proof = requireProof(proofId);
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
        String proofId = (String) params.get("proofId");
        int goalId = intValue(params.get("goalId"));
        Proof proof = requireProof(proofId);
        Goal goal = openGoalByIndex(proof, goalId);
        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("serialNr", goal.node().serialNr());
        result.put("sequent", goal.sequent().toString());
        return result;
    }

    private Map<String, Object> handleProofRuleApply(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        int goalId = intValue(params.get("goalId"));
        String ruleName = (String) params.get("ruleName");
        Proof proof = requireProof(proofId);

        String script = "select number=" + goalId + ";\nrule " + ruleName + ";";
        try {
            executeScript(proof, script);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpToolException(-32603, "Interrupted", e.getMessage());
        } catch (ScriptException e) {
            throw new McpToolException(-32603, "Rule application failed: " + e.getMessage(), e.getMessage());
        }

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("ruleName", ruleName);
        result.put("applied", true);
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofScriptRun(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        String script = (String) params.get("script");
        Proof proof = requireProof(proofId);

        try {
            executeScript(proof, script);
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

    private Map<String, Object> handleProofUndo(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        int goalId = intValue(params.get("goalId"));
        Proof proof = requireProof(proofId);
        Goal goal = openGoalByIndex(proof, goalId);
        proof.pruneProof(goal);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("goalId", goalId);
        result.put("undone", true);
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> handleProofExport(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        String format = (String) params.getOrDefault("format", "proof");
        String path = (String) params.get("path");
        Proof proof = requireProof(proofId);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("format", format);

        switch (format) {
        case "proof": {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try {
                new OutputStreamProofSaver(proof).save(config.workspace(), baos);
                String content = baos.toString(java.nio.charset.StandardCharsets.UTF_8);
                if (path != null) {
                    Path target = PathValidator.resolveAndValidate(path, config.workspace(), config.allowedPaths());
                    java.nio.file.Files.writeString(target, content);
                    result.put("path", target.toString());
                } else {
                    result.put("content", content);
                }
            } catch (IOException e) {
                throw new McpToolException(-32603, "Failed to export proof: " + e.getMessage(), e.getMessage());
            }
            break;
        }
        case "json": {
            result.put("tree", proofTreeJson(proof.root()));
            break;
        }
        default:
            throw new McpToolException(-32602, "Unknown export format: " + format, null);
        }
        return result;
    }

    private Map<String, Object> handleProofSmt(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        Proof proof = requireProof(proofId);
        if (proof.openGoals().isEmpty()) {
            throw new McpToolException(-32603, "Proof has no open goals", null);
        }
        Goal goal = proof.openGoals().head();

        SMTSettings settings = new DefaultSMTSettings(proof.getSettings().getSMTSettings(),
            ProofIndependentSMTSettings.getDefaultSettingsData(),
            proof.getSettings().getNewSMTSettings(), proof);

        try {
            SmtLib2Translator translator = new SmtLib2Translator(new String[0], new String[0], null);
            String text = translator.translateProblem(goal.sequent(), proof.getServices(), settings).toString();
            Map<String, Object> result = Json.object();
            result.put("proofId", proofId);
            result.put("smt", text);
            return result;
        } catch (Exception e) {
            throw new McpToolException(-32603, "SMT translation failed: " + e.getMessage(), e.getMessage());
        }
    }

    private Map<String, Object> handleProofCounterexample(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        Proof proof = requireProof(proofId);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("supported", false);
        result.put("message",
            "Counterexample extraction requires an explicitly enabled SMT solver and is not yet implemented in this version.");
        result.put("openGoals", proof.openGoals().size());
        return result;
    }

    private Map<String, Object> proofTreeJson(de.uka.ilkd.key.proof.Node node) {
        Map<String, Object> item = Json.object();
        item.put("serialNr", node.serialNr());
        item.put("sequent", node.sequent().toString());
        if (node.getAppliedRuleApp() != null) {
            item.put("rule", node.getAppliedRuleApp().rule().name().toString());
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (de.uka.ilkd.key.proof.Node child : node.children()) {
            children.add(proofTreeJson(child));
        }
        item.put("children", children);
        return item;
    }

    private Proof requireProof(String proofId) {
        Proof proof = session.getProof(proofId);
        if (proof == null) {
            throw new McpToolException(-32002, "Proof not found: " + proofId, null);
        }
        return proof;
    }

    private Goal openGoalByIndex(Proof proof, int index) {
        int i = 0;
        for (Goal goal : proof.openGoals()) {
            if (i == index) {
                return goal;
            }
            i++;
        }
        throw new McpToolException(-32002, "Goal not found: " + index, null);
    }

    private void executeScript(Proof proof, String scriptText) throws ScriptException, InterruptedException {
        ensureEnvironment();
        AbstractUserInterfaceControl ui = (AbstractUserInterfaceControl) session.getEnvironment().getUi();
        KeyAst.ProofScript script = ParsingFacade.parseScript(scriptText);
        ProofScriptEngine engine = new ProofScriptEngine(proof);
        engine.setInitiallySelectedGoal(proof.openGoals().head());
        engine.execute(ui, script);
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    private Proof createProof(String contractId) {
        ensureEnvironment();
        Contract contract = session.getContract(contractId);
        if (contract == null) {
            throw new McpToolException(-32602, "Unknown contract: " + contractId, null);
        }
        try {
            return session.getEnvironment().createProof(contract.createProofObl(
                session.getEnvironment().getInitConfig(), contract));
        } catch (ProofInputException e) {
            throw new McpToolException(-32603, "Failed to create proof: " + e.getMessage(), e.getMessage());
        }
    }

    private void configureStrategy(Proof proof, long maxSteps, Object strategyOptions) {
        StrategyProperties sp = proof.getSettings().getStrategySettings().getActiveStrategyProperties();
        sp.setProperty(StrategyProperties.METHOD_OPTIONS_KEY, StrategyProperties.METHOD_CONTRACT);
        sp.setProperty(StrategyProperties.DEP_OPTIONS_KEY, StrategyProperties.DEP_ON);
        sp.setProperty(StrategyProperties.QUERY_OPTIONS_KEY, StrategyProperties.QUERY_ON);
        sp.setProperty(StrategyProperties.NON_LIN_ARITH_OPTIONS_KEY, StrategyProperties.NON_LIN_ARITH_DEF_OPS);
        sp.setProperty(StrategyProperties.STOPMODE_OPTIONS_KEY, StrategyProperties.STOPMODE_NONCLOSE);

        if (strategyOptions instanceof Map<?, ?> options) {
            for (Map.Entry<?, ?> entry : options.entrySet()) {
                sp.setProperty(entry.getKey().toString(), entry.getValue().toString());
            }
        }

        proof.getSettings().getStrategySettings().setActiveStrategyProperties(sp);
        proof.getSettings().getStrategySettings().setMaxSteps((int) maxSteps);
        ProofSettings.DEFAULT_SETTINGS.getStrategySettings().setMaxSteps((int) maxSteps);
        ProofSettings.DEFAULT_SETTINGS.getStrategySettings().setActiveStrategyProperties(sp);
        proof.setActiveStrategy(proof.getServices().getProfile().getDefaultStrategyFactory().create(proof, sp));
    }

    private void runAutoMode(Operation operation, Proof proof, long timeoutMs) {
        Timer timer = new Timer();
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
            session.getEnvironment().getUi().getProofControl().startAndWaitForAutoMode(proof);
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

    private Map<String, Object> statusOf(Operation operation) {
        Map<String, Object> result = Json.object();
        result.put("operationId", operation.getId());
        result.put("state", operation.getState().name().toLowerCase());
        result.put("proofId", operation.getProofId());
        result.put("events", operation.getEvents());
        if (operation.getErrorMessage() != null) {
            result.put("errorMessage", operation.getErrorMessage());
        }
        return result;
    }

    private void ensureEnvironment() {
        if (session.getEnvironment() == null) {
            throw new McpToolException(-32603, "No project loaded. Call key_project_load first.", null);
        }
    }

    private static long longValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    private static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.toString());
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

    private static List<Path> toPathList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            List<Path> paths = new ArrayList<>();
            for (Object o : list) {
                paths.add(Path.of(o.toString()));
            }
            return paths;
        }
        return null;
    }

    private static Path toPath(Object value) {
        if (value == null) {
            return null;
        }
        return Path.of(value.toString());
    }
}
