/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

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
import de.uka.ilkd.key.scripts.ProofScriptEngine;
import de.uka.ilkd.key.scripts.ScriptException;
import de.uka.ilkd.key.settings.DefaultSMTSettings;
import de.uka.ilkd.key.settings.ProofIndependentSMTSettings;
import de.uka.ilkd.key.smt.SMTSettings;
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

    // ---------------------------------------------------------------- static value helpers

    public static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
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
