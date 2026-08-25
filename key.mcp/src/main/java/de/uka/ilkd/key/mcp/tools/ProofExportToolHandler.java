/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.McpToolException;
import de.uka.ilkd.key.mcp.PathValidator;
import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.io.OutputStreamProofSaver;
import de.uka.ilkd.key.smt.SMTProblem;
import de.uka.ilkd.key.smt.SMTSolver;
import de.uka.ilkd.key.smt.SMTSolverResult;
import de.uka.ilkd.key.smt.SmtLib2Translator;
import de.uka.ilkd.key.smt.SolverLauncher;
import de.uka.ilkd.key.smt.solvertypes.SolverType;
import de.uka.ilkd.key.smt.solvertypes.SolverTypes;

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
        String proofId = (String) params.get("proofId");
        String format = (String) params.getOrDefault("format", "proof");
        String path = (String) params.get("path");
        Proof proof = ctx.requireProof(proofId);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("format", format);

        switch (format) {
            case "proof": {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    new OutputStreamProofSaver(proof).save(ctx.config().workspace(), baos);
                    String content = baos.toString(StandardCharsets.UTF_8);
                    if (path != null) {
                        Path target =
                            PathValidator.resolveAndValidate(path, ctx.config().workspace(),
                                ctx.config().allowedPaths());
                        Files.writeString(target, content);
                        result.put("path", target.toString());
                    } else {
                        result.put("content", content);
                    }
                } catch (IOException e) {
                    throw new McpToolException(-32603, "Failed to export proof: " + e.getMessage(),
                        e.getMessage());
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
        String proofId = (String) params.get("proofId");
        Proof proof = ctx.requireProof(proofId);
        if (proof.openGoals().isEmpty()) {
            throw new McpToolException(-32603, "Proof has no open goals", null);
        }
        Goal goal = proof.openGoals().head();

        try {
            SmtLib2Translator translator =
                new SmtLib2Translator(new String[0], new String[0], null);
            String text = translator
                    .translateProblem(goal.sequent(), proof.getServices(),
                        ctx.createSmtSettings(proof))
                    .toString();
            Map<String, Object> result = Json.object();
            result.put("proofId", proofId);
            result.put("smt", text);
            return result;
        } catch (Exception e) {
            throw new McpToolException(-32603, "SMT translation failed: " + e.getMessage(),
                e.getMessage());
        }
    }

    private Map<String, Object> handleProofCounterexample(Map<String, Object> params) {
        String proofId = (String) params.get("proofId");
        Proof proof = ctx.requireProof(proofId);

        Map<String, Object> result = Json.object();
        result.put("proofId", proofId);
        result.put("openGoals", proof.openGoals().size());

        if (proof.openGoals().isEmpty()) {
            result.put("supported", false);
            result.put("message", "Proof is closed; there is no goal to falsify.");
            return result;
        }

        SolverType solverType = findCounterExampleSolver((String) params.get("solver"));
        if (solverType == null) {
            result.put("supported", false);
            result.put("message",
                "No SMT solver enabled. Set KEY_MCP_SMT_SOLVERS to a solver name (e.g. 'Z3_CE') and ensure the solver binary is installed.");
            return result;
        }

        Goal goal = proof.openGoals().head();
        SMTProblem problem = new SMTProblem(goal);
        SolverLauncher launcher = new SolverLauncher(ctx.createSmtSettings(proof));
        try {
            launcher.launch(problem, proof.getServices(), solverType);
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
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
        for (String enabled : ctx.config().allowedSmtSolvers()) {
            if (enabled.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
