/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.PathValidator;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.proof.Proof;

/**
 * Tools for exporting proofs, translating goals to SMT-LIB and extracting counterexamples.
 */
public class ProofExportToolHandler extends ToolHandler {

    public ProofExportToolHandler(ToolContext ctx) {
        super(ctx);
    }

    @Override
    public void registerTools(Map<String, ToolDefinition> tools) {
        register(tools, "key_proof_export",
            "Export a proof as a KeY .proof file or as a JSON tree.",
            props(
                "proofId", Map.of("type", "string"),
                "format",
                Map.of("type", "string", "enum", List.of("proof", "json"), "default", "proof"),
                "path", Map.of("type", "string")),
            List.of("proofId"), this::handleProofExport);

        register(tools, "key_proof_smt",
            "Translate the first open goal of a proof to SMT-LIB format.",
            props("proofId", Map.of("type", "string")), List.of("proofId"), this::handleProofSmt);

        register(tools, "key_proof_counterexample",
            "Get a counterexample or error trace for a proof, if available.",
            props(
                "proofId", Map.of("type", "string"),
                "solver", Map.of("type", "string", "description",
                    "Name of the SMT solver to use (default: first enabled solver)")),
            List.of("proofId"), this::handleProofCounterexample);
    }

    private Map<String, Object> handleProofExport(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        String format = (String) params.getOrDefault("format", "proof");
        String path = (String) params.get("path");
        Proof proof = ctx.requireProof(proofId);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("format", format);

        switch (format) {
            case "proof": {
                String content = ctx.exportProofText(proof);
                if (path != null) {
                    Path target = PathValidator.resolveAndValidate(path, ctx.config().workspace(),
                        ctx.config().allowedPaths());
                    try {
                        Files.writeString(target, content);
                    } catch (java.io.IOException e) {
                        throw new McpToolException(-32603,
                            "Failed to write proof file: " + e.getMessage(), e.getMessage());
                    }
                    result.put("path", target.toString());
                } else {
                    result.put("content", content);
                }
                break;
            }
            case "json": {
                result.put("tree", ctx.proofTreeJson(proof.root()));
                break;
            }
            default:
                throw new McpToolException(-32602, "Unknown export format: " + format, null);
        }
        return result;
    }

    private Map<String, Object> handleProofSmt(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        Proof proof = ctx.requireProof(proofId);
        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("smt", ctx.smtText(proof));
        return result;
    }

    private Map<String, Object> handleProofCounterexample(Map<String, Object> params) {
        String proofId = ToolContext.requireString(params, "proofId");
        Proof proof = ctx.requireProof(proofId);
        Map<String, Object> result =
            ctx.counterexampleFor(proof, (String) params.get("solver"));
        result.put("proofId", proofId);
        return result;
    }
}
