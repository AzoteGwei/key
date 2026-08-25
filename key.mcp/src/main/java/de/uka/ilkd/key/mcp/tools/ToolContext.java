/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.control.AbstractUserInterfaceControl;
import de.uka.ilkd.key.mcp.McpServerConfig;
import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.operation.Operation;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.nparser.KeyAst;
import de.uka.ilkd.key.nparser.ParsingFacade;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Node;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.init.ProofInputException;
import de.uka.ilkd.key.proof.io.OutputStreamProofSaver;
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.scripts.ScriptException;
import de.uka.ilkd.key.settings.DefaultSMTSettings;
import de.uka.ilkd.key.settings.ProofIndependentSMTSettings;
import de.uka.ilkd.key.smt.SMTProblem;
import de.uka.ilkd.key.smt.SMTSettings;
import de.uka.ilkd.key.smt.SMTSolver;
import de.uka.ilkd.key.smt.SMTSolverResult;
import de.uka.ilkd.key.smt.SmtLib2Translator;
import de.uka.ilkd.key.smt.SolverLauncher;
import de.uka.ilkd.key.smt.solvertypes.SolverType;
import de.uka.ilkd.key.smt.solvertypes.SolverTypes;
import de.uka.ilkd.key.speclang.Contract;

/**
 * Shared state and helper methods used by all MCP tool handlers.
 */
public class ToolContext {
    private final McpServerConfig config;
    private final McpSession session;

    public ToolContext(McpServerConfig config, McpSession session) {
        this.config = config;
        this.session = session;
    }

    public McpServerConfig config() {
        return config;
    }

    public McpSession session() {
        return session;
    }

    public void ensureEnvironment() {
        if (session.getEnvironment() == null) {
            throw new McpToolException(-32603, "No project loaded. Call key_project_load first.",
                null);
        }
    }

    public Proof requireProof(String proofId) {
        Proof proof = session.getProof(proofId);
        if (proof == null) {
            throw new McpToolException(-32002, "Proof not found: " + proofId, null);
        }
        return proof;
    }

    public Goal openGoalByIndex(Proof proof, int index) {
        int i = 0;
        for (Goal goal : proof.openGoals()) {
            if (i == index) {
                return goal;
            }
            i++;
        }
        throw new McpToolException(-32002, "Goal not found: " + index, null);
    }

    public Proof createProof(String contractId) {
        ensureEnvironment();
        Contract contract = session.getContract(contractId);
        if (contract == null) {
            throw new McpToolException(-32602, "Unknown contract: " + contractId, null);
        }
        try {
            return session.getEnvironment().createProof(
                contract.createProofObl(session.getEnvironment().getInitConfig(), contract));
        } catch (ProofInputException e) {
            throw new McpToolException(-32603, "Failed to create proof: " + e.getMessage(),
                e.getMessage());
        }
    }

    public void executeScript(Proof proof, String scriptText)
            throws ScriptException, InterruptedException {
        ensureEnvironment();
        if (proof.openGoals().isEmpty()) {
            throw new McpToolException(-32603, "Proof has no open goals", null);
        }
        AbstractUserInterfaceControl ui =
            (AbstractUserInterfaceControl) session.getEnvironment().getUi();
        KeyAst.ProofScript script = ParsingFacade.parseScript(scriptText);
        ProofScriptEngine engine = new ProofScriptEngine(proof);
        engine.setInitiallySelectedGoal(proof.openGoals().head());
        engine.execute(ui, script);
    }

    public SMTSettings createSmtSettings(Proof proof) {
        return new DefaultSMTSettings(proof.getSettings().getSMTSettings(),
            ProofIndependentSMTSettings.getDefaultSettingsData(),
            proof.getSettings().getNewSMTSettings(), proof);
    }

    public Map<String, Object> statusOf(Operation operation) {
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

    public Map<String, Object> proofTreeJson(Node node) {
        Map<String, Object> item = Json.object();
        item.put("serialNr", node.serialNr());
        item.put("sequent", node.sequent().toString());
        if (node.getAppliedRuleApp() != null) {
            item.put("rule", node.getAppliedRuleApp().rule().name().toString());
        }
        List<Map<String, Object>> children = new ArrayList<>();
        for (Node child : node.children()) {
            children.add(proofTreeJson(child));
        }
        item.put("children", children);
        return item;
    }

    // ---------------------------------------------------------------- shared business logic

    /**
     * Lists all contracts of the loaded project as JSON-friendly maps.
     */
    public List<Map<String, Object>> contractsListJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map.Entry<String, Contract> entry : session.getContracts().entrySet()) {
            Map<String, Object> item = Json.object();
            item.put("contractId", entry.getKey());
            item.put("targetName", entry.getValue().getTarget().name().toString());
            item.put("displayName", entry.getValue().getDisplayName());
            item.put("type", entry.getValue().getClass().getSimpleName());
            list.add(item);
        }
        return list;
    }

    /**
     * Serializes a proof in KeY's {@code .proof} format.
     */
    public String exportProofText(Proof proof) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            new OutputStreamProofSaver(proof).save(config.workspace(), baos);
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new McpToolException(-32603, "Failed to export proof: " + e.getMessage(),
                e.getMessage());
        }
    }

    /**
     * Translates the first open goal of a proof to SMT-LIB format.
     */
    public String smtText(Proof proof) {
        if (proof.openGoals().isEmpty()) {
            throw new McpToolException(-32603, "Proof has no open goals", null);
        }
        Goal goal = proof.openGoals().head();
        try {
            SmtLib2Translator translator =
                new SmtLib2Translator(new String[0], new String[0], null);
            return translator
                    .translateProblem(goal.sequent(), proof.getServices(), createSmtSettings(proof))
                    .toString();
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException(-32603, "SMT translation failed: " + e.getMessage(),
                e.getMessage());
        }
    }

    /**
     * Runs the configured SMT solver on the first open goal of a proof and extracts a
     * counterexample model if the goal is falsifiable.
     */
    public Map<String, Object> counterexampleFor(Proof proof, String requestedSolver) {
        Map<String, Object> result = Json.object();
        result.put("openGoals", proof.openGoals().size());

        if (proof.openGoals().isEmpty()) {
            result.put("supported", false);
            result.put("message", "Proof is closed; there is no goal to falsify.");
            return result;
        }

        SolverType solverType = findCounterExampleSolver(requestedSolver);
        if (solverType == null) {
            result.put("supported", false);
            result.put("message",
                "No SMT solver enabled. Set KEY_MCP_SMT_SOLVERS to a solver name (e.g. 'Z3_CE') and ensure the solver binary is installed.");
            return result;
        }

        Goal goal = proof.openGoals().head();
        SMTProblem problem = new SMTProblem(goal);
        SolverLauncher launcher = new SolverLauncher(createSmtSettings(proof));
        try {
            launcher.launch(problem, proof.getServices(), solverType);
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            throw new McpToolException(-32603,
                "Failed to run SMT solver '" + solverType.getName() + "': "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(),
                sw.toString());
        }

        SMTSolverResult solverResult = problem.getFinalResult();
        if (solverResult == null) {
            result.put("supported", false);
            result.put("message", "Solver produced no result.");
            return result;
        }

        result.put("supported", true);
        result.put("result", solverResult.isValid().name());
        result.put("resultText", solverResult.toString());

        if (solverResult.isValid() == SMTSolverResult.ThreeValuedTruth.FALSIFIABLE) {
            for (SMTSolver solver : problem.getSolvers()) {
                if (solver.getType() == solverType && solver.getSocket() != null
                        && solver.getSocket().getQuery() != null) {
                    var model = solver.getSocket().getQuery().getModel();
                    if (model != null) {
                        result.put("counterexample", model.toString());
                        break;
                    }
                }
            }
            if (!result.containsKey("counterexample")) {
                result.put("counterexample",
                    "Sequent is falsifiable, but no model could be extracted from the solver output.");
            }
        }
        return result;
    }

    private SolverType findCounterExampleSolver(String requestedName) {
        for (SolverType type : SolverTypes.getSolverTypes()) {
            String name = type.getName();
            boolean matchesRequest = requestedName == null
                    ? (name.equalsIgnoreCase("Z3_CE") || name.equalsIgnoreCase("Z3"))
                    : name.equalsIgnoreCase(requestedName);
            if (matchesRequest && isSolverEnabled(name)) {
                return type;
            }
        }
        return null;
    }

    private boolean isSolverEnabled(String name) {
        for (String enabled : config.allowedSmtSolvers()) {
            if (enabled.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- static value helpers

    /**
     * Returns the required string parameter or throws an "invalid params" error.
     */
    public static String requireString(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (!(value instanceof String s) || s.isBlank()) {
            throw new McpToolException(-32602, "Missing or invalid required parameter: " + key,
                null);
        }
        return s;
    }

    public static int intValue(Object value) {
        if (value == null) {
            throw new McpToolException(-32602, "Missing required integer parameter", null);
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            throw new McpToolException(-32602, "Invalid integer parameter: " + value, null);
        }
    }

    public static long longValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    public static boolean boolValue(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.toString());
    }

    public static List<Path> toPathList(Object value) {
        if (value instanceof List<?> list) {
            List<Path> paths = new ArrayList<>();
            for (Object o : list) {
                paths.add(Path.of(o.toString()));
            }
            return paths;
        }
        return null;
    }

    public static Path toPath(Object value) {
        if (value == null) {
            return null;
        }
        return Path.of(value.toString());
    }

    public static String scriptValue(Object value) {
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        String text = value.toString();
        StringBuilder sb = new StringBuilder(text.length() + 2);
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\');
            }
            sb.append(c);
        }
        sb.append('"');
        return sb.toString();
    }
}
