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
        Map<String, Object> p = Json.object();
        p.put("name", "verify_contract");
        p.put("description", "Guide the agent through verifying a KeY contract.");
        prompts.add(p);
        return prompts;
    }

    public Map<String, Object> getPrompt(String name, Map<String, Object> arguments) {
        if (!"verify_contract".equals(name)) {
            throw new McpToolException(-32601, "Prompt not found: " + name, null);
        }

        String contractId = arguments != null ? (String) arguments.get("contractId") : null;

        List<Map<String, Object>> messages = new ArrayList<>();
        Map<String, Object> message = Json.object();
        message.put("role", "user");
        StringBuilder text = new StringBuilder();
        text.append("You are verifying a Java/JML contract using KeY. Follow these steps:\n\n");
        text.append("1. Call key_project_load to load the project directory or .key file.\n");
        text.append("2. Call key_contracts_list to list available contracts.\n");
        if (contractId != null) {
            text.append("3. Use contractId ").append(contractId)
                    .append(" and call key_proof_auto.\n");
        } else {
            text.append("3. Pick a contractId and call key_proof_auto.\n");
        }
        text.append("4. Wait for completion with key_operation_wait.\n");
        text.append(
            "5. If the proof is not closed, inspect goals with key_proof_goals_list and try key_proof_rule_apply or key_proof_script_run.\n");
        text.append("6. Export the result with key_proof_export.\n");
        message.put("content", Map.of("type", "text", "text", text.toString()));
        messages.add(message);

        Map<String, Object> result = Json.object();
        result.put("description", "Verify a KeY contract");
        result.put("messages", messages);
        return result;
    }
}
