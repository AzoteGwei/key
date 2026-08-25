/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.io.OutputStreamProofSaver;
import de.uka.ilkd.key.settings.DefaultSMTSettings;
import de.uka.ilkd.key.settings.ProofIndependentSMTSettings;
import de.uka.ilkd.key.smt.SMTSettings;
import de.uka.ilkd.key.smt.SmtLib2Translator;

/**
 * Handles MCP resources.
 */
public class McpResourceHandler {
    private final McpSession session;

    public McpResourceHandler(McpSession session) {
        this.session = session;
    }

    public List<Map<String, Object>> listResources() {
        List<Map<String, Object>> resources = new ArrayList<>();
        resources.add(resource("session:///info", "Session information", "application/json"));
        resources.add(resource("project:///contracts", "Contracts list", "application/json"));
        for (String proofId : session.getProofs().keySet()) {
            resources.add(
                resource("proof://" + proofId + "/status", "Proof status", "application/json"));
            resources.add(
                resource("proof://" + proofId + "/goals", "Proof goals", "application/json"));
            resources.add(
                resource("proof://" + proofId + "/tree", "Proof tree", "application/json"));
            resources.add(resource("proof://" + proofId + "/export", "Proof export",
                "application/octet-stream"));
            resources.add(resource("proof://" + proofId + "/smt", "SMT translation", "text/plain"));
            resources.add(
                resource("proof://" + proofId + "/counterexample", "Counterexample", "text/plain"));
        }
        return resources;
    }

    private Map<String, Object> resource(String uri, String name, String mimeType) {
        Map<String, Object> r = Json.object();
        r.put("uri", uri);
        r.put("name", name);
        r.put("mimeType", mimeType);
        return r;
    }

    public Map<String, Object> readResource(String uri) {
        if (uri.startsWith("session://")) {
            return readSessionInfo();
        } else if (uri.startsWith("project://")) {
            return readContracts();
        } else if (uri.startsWith("proof://")) {
            return readProofResource(uri);
        } else if (uri.startsWith("operation://")) {
            return readOperationEvents(uri);
        }
        throw new McpToolException(-32002, "Resource not found: " + uri, null);
    }

    private Map<String, Object> readSessionInfo() {
        Map<String, Object> result = Json.object();
        result.put("sessionId", session.getId());
        result.put("environmentLoaded", session.getEnvironment() != null);
        result.put("contractCount", session.getContracts().size());
        result.put("proofCount", session.getProofs().size());
        return result;
    }

    private Map<String, Object> readContracts() {
        return new McpToolRegistry(null, session).execute("key_contracts_list", Map.of());
    }

    private Map<String, Object> readProofResource(String uri) {
        String[] parts = uri.substring("proof://".length()).split("/");
        if (parts.length < 2) {
            throw new McpToolException(-32602, "Invalid proof URI: " + uri, null);
        }
        String proofId = parts[0];
        String suffix = parts[1];
        Proof proof = session.getProof(proofId);
        if (proof == null) {
            throw new McpToolException(-32002, "Proof not found: " + proofId, null);
        }

        Map<String, Object> result = Json.object();
        result.put("uri", uri);
        switch (suffix) {
            case "status":
                result.put("text", statusText(proof));
                break;
            case "goals":
                result.put("text", goalsJson(proof));
                break;
            case "goal":
                if (parts.length < 3) {
                    throw new McpToolException(-32602, "Missing goal id in URI: " + uri, null);
                }
                int goalId = Integer.parseInt(parts[2]);
                result.put("text", goalJson(proof, goalId));
                break;
            case "tree":
                result.put("text", Json.stringify(proofTreeJson(proof.root())));
                break;
            case "export":
                result.put("text", exportProof(proof));
                break;
            case "smt":
                result.put("text", smtText(proof));
                break;
            case "counterexample":
                result.put("text",
                    "Counterexample extraction requires an explicitly enabled SMT solver and is not yet implemented in this version.");
                break;
            default:
                throw new McpToolException(-32002, "Unknown proof resource suffix: " + suffix,
                    null);
        }
        return result;
    }

    private Map<String, Object> readOperationEvents(String uri) {
        String opId = uri.substring("operation://".length());
        if (opId.endsWith("/events")) {
            opId = opId.substring(0, opId.length() - "/events".length());
        }
        var op = session.getOperationTracker().get(opId);
        if (op == null) {
            throw new McpToolException(-32003, "Operation not found: " + opId, null);
        }
        Map<String, Object> result = Json.object();
        result.put("uri", uri);
        result.put("text", Json.stringify(op.getEvents()));
        return result;
    }

    private String statusText(Proof proof) {
        return Json.stringify(Map.of(
            "closed", proof.openGoals().isEmpty(),
            "openGoals", proof.openGoals().size(),
            "usedSteps", proof.countNodes()));
    }

    private String goalsJson(Proof proof) {
        List<Map<String, Object>> goals = new ArrayList<>();
        int index = 0;
        for (Goal goal : proof.openGoals()) {
            goals.add(Map.of("goalId", index, "serialNr", goal.node().serialNr(),
                "sequent", goal.sequent().toString()));
            index++;
        }
        return Json.stringify(Map.of("goals", goals));
    }

    private String goalJson(Proof proof, int goalId) {
        int index = 0;
        for (Goal goal : proof.openGoals()) {
            if (index == goalId) {
                return Json.stringify(Map.of("goalId", goalId, "serialNr", goal.node().serialNr(),
                    "sequent", goal.sequent().toString()));
            }
            index++;
        }
        throw new McpToolException(-32002, "Goal not found: " + goalId, null);
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

    private String exportProof(Proof proof) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            new OutputStreamProofSaver(proof).save(null, baos);
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new McpToolException(-32603, "Failed to export proof: " + e.getMessage(),
                e.getMessage());
        }
    }

    private String smtText(Proof proof) {
        if (proof.openGoals().isEmpty()) {
            return "// Proof is closed; no SMT problem available.";
        }
        Goal goal = proof.openGoals().head();
        SMTSettings settings = new DefaultSMTSettings(proof.getSettings().getSMTSettings(),
            ProofIndependentSMTSettings.getDefaultSettingsData(),
            proof.getSettings().getNewSMTSettings(), proof);
        try {
            SmtLib2Translator translator =
                new SmtLib2Translator(new String[0], new String[0], null);
            return translator.translateProblem(goal.sequent(), proof.getServices(), settings)
                    .toString();
        } catch (Exception e) {
            throw new McpToolException(-32603, "SMT translation failed: " + e.getMessage(),
                e.getMessage());
        }
    }
}
