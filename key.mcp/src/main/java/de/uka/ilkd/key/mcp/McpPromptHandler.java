/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

/**
 * Handles MCP prompts.
 */
public class McpPromptHandler {

    public List<Map<String, Object>> listPrompts() {
        List<Map<String, Object>> prompts = new ArrayList<>();
        prompts.add(prompt("verify_contract",
            "Guide the agent through verifying a single KeY contract."));
        prompts.add(prompt("verify_all_contracts",
            "Verify all contracts of a loaded project and summarize the results."));
        prompts.add(prompt("diagnose_open_goals",
            "Inspect open goals of a proof and suggest the next interactive steps."));
        prompts.add(prompt("extract_counterexample",
            "Try to obtain a counterexample for a falsifiable goal via the SMT solver."));
        return prompts;
    }

    private Map<String, Object> prompt(String name, String description) {
        Map<String, Object> p = Json.object();
        p.put("name", name);
        p.put("description", description);
        return p;
    }

    public Map<String, Object> getPrompt(String name, Map<String, Object> arguments) {
        return switch (name) {
            case "verify_contract" -> verifyContract(arguments);
            case "verify_all_contracts" -> verifyAllContracts(arguments);
            case "diagnose_open_goals" -> diagnoseOpenGoals(arguments);
            case "extract_counterexample" -> extractCounterexample(arguments);
            default -> throw new McpToolException(-32601, "Prompt not found: " + name, null);
        };
    }

    private Map<String, Object> verifyContract(Map<String, Object> arguments) {
        String contractId = arguments != null ? (String) arguments.get("contractId") : null;
        StringBuilder text = new StringBuilder();
        text.append("You are verifying a Java/JML contract using KeY. Follow these steps:\n\n");
        text.append("1. Call key_project_load to load the project directory or .key file.\n");
        text.append("2. Call key_contracts_list to list available contracts.\n");
        if (contractId != null) {
            text.append("3. Use contractId ").append(contractId)
                    .append(" and call key_proof_auto with timeoutMs and maxSteps.\n");
        } else {
            text.append(
                "3. Pick a contractId and call key_proof_auto with timeoutMs and maxSteps.\n");
        }
        text.append("4. Poll the operation with key_operation_wait until it completes.\n");
        text.append(
            "5. If the proof is not closed, inspect goals with key_proof_goals_list and try "
                + "key_proof_rule_apply or key_proof_script_run.\n");
        text.append("6. Export the result with key_proof_export.\n");
        return promptResult("Verify a KeY contract", text.toString());
    }

    private Map<String, Object> verifyAllContracts(Map<String, Object> arguments) {
        String location = arguments != null ? (String) arguments.get("location") : null;
        StringBuilder text = new StringBuilder();
        text.append("Verify all contracts of a Java/JML project with KeY:\n\n");
        text.append("1. Call key_project_load");
        if (location != null) {
            text.append(" with location=").append(location);
        } else {
            text.append(" with the project path");
        }
        text.append(".\n");
        text.append(
            "2. Call key_contracts_list and iterate over every contractId: call key_proof_auto "
                + "(async), wait via key_operation_wait, and record closed/open per contract.\n");
        text.append(
            "3. For open contracts, call key_proof_goals_list and summarize the open sequents.\n");
        text.append("4. Produce a final report listing verified and unproven contracts.\n");
        return promptResult("Verify all contracts", text.toString());
    }

    private Map<String, Object> diagnoseOpenGoals(Map<String, Object> arguments) {
        String proofId = arguments != null ? (String) arguments.get("proofId") : null;
        StringBuilder text = new StringBuilder();
        text.append("Diagnose open goals of a KeY proof:\n\n");
        if (proofId != null) {
            text.append("Use proofId ").append(proofId).append(".\n\n");
        }
        text.append("1. Call key_proof_goals_list to enumerate open goals.\n");
        text.append("2. For each goal, call key_proof_goal_get and inspect the sequent.\n");
        text.append(
            "3. If a goal contains a program modality or updates, first run a proof script via "
                + "key_proof_script_run (e.g. 'auto;' or 'unfold;').\n");
        text.append(
            "4. Try targeted rules via key_proof_rule_apply; additional options such as on, "
                + "formula, occ, matches, assumes, inst_* can be passed via 'parameters'.\n");
        text.append("5. Use key_proof_undo to revert unsuccessful rule applications.\n");
        return promptResult("Diagnose open goals", text.toString());
    }

    private Map<String, Object> extractCounterexample(Map<String, Object> arguments) {
        String proofId = arguments != null ? (String) arguments.get("proofId") : null;
        StringBuilder text = new StringBuilder();
        text.append("Extract a counterexample from a falsifiable KeY goal:\n\n");
        if (proofId != null) {
            text.append("Use proofId ").append(proofId).append(".\n\n");
        }
        text.append("1. Ensure the server was started with KEY_MCP_SMT_SOLVERS=Z3_CE.\n");
        text.append(
            "2. Run key_proof_auto first so the remaining open goals are simplified first-order "
                + "formulas.\n");
        text.append(
            "3. Call key_proof_counterexample with solver='Z3_CE'. If the result is "
                + "FALSIFIABLE, the 'counterexample' field contains the model.\n");
        text.append("4. Translate the model back to Java-level observations for the user.\n");
        return promptResult("Extract a counterexample", text.toString());
    }

    private Map<String, Object> promptResult(String description, String text) {
        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> message = Json.object();
        message.put("role", "user");
        message.put("content", Map.of("type", "text", "text", text));
        messages.add(message);

        Map<String, Object> result = Json.object();
        result.put("description", description);
        result.put("messages", messages);
        return result;
    }
}
