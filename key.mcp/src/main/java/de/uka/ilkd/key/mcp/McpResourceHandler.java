/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.protocol.ResourceContents;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.mcp.tools.ToolContext;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;

/**
 * Handles MCP resources.
 *
 * <p>
 * Resource readers only produce a {@link Payload} (MIME type + text); the final
 * {@code contents} item is assembled in {@link #readResource(String)} via
 * {@link ResourceContents}, which guarantees the schema-mandated {@code uri} and
 * {@code text} fields are always present.
 * </p>
 */
public class McpResourceHandler {
    private final McpSession session;
    private final ToolContext ctx;

    public McpResourceHandler(ToolContext ctx) {
        this.ctx = ctx;
        this.session = ctx.session();
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
            resources.add(
                resource("proof://" + proofId + "/export", "Proof export", "text/plain"));
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
        Payload payload = readPayload(uri);
        return ResourceContents.text(uri, payload.mimeType(), payload.text());
    }

    private Payload readPayload(String uri) {
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

    /**
     * MIME type and textual payload of a resource, without the envelope fields
     * ({@code uri}, {@code text}) that the MCP schema requires on content items.
     */
    private record Payload(String mimeType, String text) {
    }

    private Payload readSessionInfo() {
        Map<String, Object> result = Json.object();
        result.put("sessionId", session.getId());
        result.put("environmentLoaded", session.getEnvironment() != null);
        result.put("contractCount", session.getContracts().size());
        result.put("proofCount", session.getProofs().size());
        return new Payload("application/json", Json.stringify(result));
    }

    private Payload readContracts() {
        return new Payload("application/json",
            Json.stringify(Map.of("contracts", ctx.contractsListJson())));
    }

    private Payload readProofResource(String uri) {
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

        switch (suffix) {
            case "status":
                return new Payload("application/json", statusText(proof));
            case "goals":
                return new Payload("application/json", goalsJson(proof));
            case "goal":
                if (parts.length < 3) {
                    throw new McpToolException(-32602, "Missing goal id in URI: " + uri, null);
                }
                int goalId;
                try {
                    goalId = Integer.parseInt(parts[2]);
                } catch (NumberFormatException e) {
                    throw new McpToolException(-32602, "Invalid goal id in URI: " + uri, null);
                }
                return new Payload("application/json", goalJson(proof, goalId));
            case "tree":
                return new Payload("application/json",
                    Json.stringify(ctx.proofTreeJson(proof.root())));
            case "export":
                return new Payload("text/plain", ctx.exportProofText(proof));
            case "smt":
                if (proof.openGoals().isEmpty()) {
                    return new Payload("text/plain",
                        "// Proof is closed; no SMT problem available.");
                } else {
                    return new Payload("text/plain", ctx.smtText(proof));
                }
            case "counterexample":
                return new Payload("application/json",
                    Json.stringify(ctx.counterexampleFor(proof, null)));
            default:
                throw new McpToolException(-32002, "Unknown proof resource suffix: " + suffix,
                    null);
        }
    }

    private Payload readOperationEvents(String uri) {
        String opId = uri.substring("operation://".length());
        if (opId.endsWith("/events")) {
            opId = opId.substring(0, opId.length() - "/events".length());
        }
        var op = session.getOperationTracker().get(opId);
        if (op == null) {
            throw new McpToolException(-32003, "Operation not found: " + opId, null);
        }
        return new Payload("application/json", Json.stringify(op.getEvents()));
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
}
