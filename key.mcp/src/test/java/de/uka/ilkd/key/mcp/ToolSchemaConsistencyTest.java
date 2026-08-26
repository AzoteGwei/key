/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.json.JsonSchemaValidator;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that every MCP tool declares an {@code outputSchema} that matches the
 * structure of its actual {@code structuredContent} output, and that annotations are
 * present where required by the agent-hardening specification.
 */
class ToolSchemaConsistencyTest {

    private static final String PROJECT_LOCATION = "key.core.example/example";

    private KeyMcpServer createServer() {
        TestTransport transport = new TestTransport();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        McpServerConfig config = new McpServerConfig(
            root,
            List.of(root),
            60000L, 10000L, List.of());
        return new KeyMcpServer(transport, config);
    }

    private Map<String, Object> lastResponse(KeyMcpServer server) {
        TestTransport transport = (TestTransport) server.transport;
        return Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
    }

    private void initialize(KeyMcpServer server) {
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    }

    private void assertNoError(Map<String, Object> response) {
        if (response.containsKey("error")) {
            throw new AssertionError("Unexpected error response: " + response.get("error"));
        }
    }

    private Map<String, Object> buildToolSchemas(KeyMcpServer server) {
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        Map<String, Object> response = lastResponse(server);
        assertNoError(response);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> tools = (List<?>) result.get("tools");
        Map<String, Object> schemas = new HashMap<>();
        for (Object t : tools) {
            Map<String, Object> tool = (Map<String, Object>) t;
            String name = (String) tool.get("name");
            Object outputSchema = tool.get("outputSchema");
            assertThat(outputSchema).as("outputSchema for %s", name).isNotNull();
            assertThat(outputSchema).as("outputSchema for %s", name).isInstanceOf(Map.class);
            schemas.put(name, outputSchema);
        }
        return schemas;
    }

    private int nextId = 10;

    private Map<String, Object> callTool(KeyMcpServer server, String name,
            Map<String, Object> args) {
        int id = nextId++;
        String message = "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
            + "\",\"arguments\":" + Json.stringify(args) + "}}";
        server.handleMessage(message);
        Map<String, Object> response = lastResponse(server);
        assertNoError(response);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result.get("content")).isNotNull();
        return (Map<String, Object>) result.get("structuredContent");
    }

    private Map<String, Object> loadProject(KeyMcpServer server) {
        Map<String, Object> result = callTool(server, "key_project_load",
            Map.of("location", PROJECT_LOCATION));
        assertThat(result.get("success")).isEqualTo(true);
        return result;
    }

    private String findContractId(KeyMcpServer server, String targetSubstring) {
        Map<String, Object> result = callTool(server, "key_contracts_list", Map.of());
        List<?> contracts = (List<?>) result.get("contracts");
        for (Object c : contracts) {
            Map<String, Object> contract = (Map<String, Object>) c;
            if (contract.get("targetName").toString().contains(targetSubstring)) {
                return (String) contract.get("contractId");
            }
        }
        return null;
    }

    @Test
    void everyToolDeclaresOutputSchemaAndAnnotations() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        Map<String, Object> response = lastResponse(server);
        assertNoError(response);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSizeGreaterThanOrEqualTo(19);

        List<String> expectedReadOnly = List.of(
            "key_session_info", "key_contracts_list", "key_proof_status",
            "key_proof_goals_list", "key_proof_goal_get", "key_proof_rules_list",
            "key_proof_smt", "key_proof_counterexample", "key_operation_wait");
        List<String> expectedDestructive = List.of(
            "key_session_reset", "key_session_dispose", "key_proof_undo",
            "key_proof_export", "key_operation_cancel");

        for (Object t : tools) {
            Map<String, Object> tool = (Map<String, Object>) t;
            String name = (String) tool.get("name");
            assertThat(tool.get("outputSchema")).as("outputSchema for %s", name)
                    .isNotNull();
            assertThat(tool.get("annotations")).as("annotations for %s", name)
                    .isNotNull();
            Map<String, Object> annotations = (Map<String, Object>) tool.get("annotations");
            if (expectedReadOnly.contains(name)) {
                assertThat(annotations.get("readOnlyHint")).as("readOnlyHint for %s", name)
                        .isEqualTo(true);
            }
            if (expectedDestructive.contains(name)) {
                assertThat(annotations.get("destructiveHint")).as("destructiveHint for %s", name)
                        .isEqualTo(true);
            }
        }
    }

    @Test
    void sessionToolsOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        Map<String, Object> schemas = buildToolSchemas(server);

        Map<String, Object> info = callTool(server, "key_session_info", Map.of());
        JsonSchemaValidator.validate(info, (Map<String, Object>) schemas.get("key_session_info"));

        Map<String, Object> reset = callTool(server, "key_session_reset", Map.of());
        JsonSchemaValidator.validate(reset, (Map<String, Object>) schemas.get("key_session_reset"));
    }

    @Test
    void projectToolsOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        Map<String, Object> schemas = buildToolSchemas(server);

        Map<String, Object> load = loadProject(server);
        JsonSchemaValidator.validate(load, (Map<String, Object>) schemas.get("key_project_load"));

        Map<String, Object> contracts = callTool(server, "key_contracts_list", Map.of());
        JsonSchemaValidator.validate(contracts,
            (Map<String, Object>) schemas.get("key_contracts_list"));
    }

    @Test
    void proofToolsOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadProject(server);
        Map<String, Object> schemas = buildToolSchemas(server);
        String contractId = findContractId(server, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> create = callTool(server, "key_proof_create",
            Map.of("contractId", contractId));
        String proofId = (String) create.get("proofId");
        JsonSchemaValidator.validate(create, (Map<String, Object>) schemas.get("key_proof_create"));

        Map<String, Object> status = callTool(server, "key_proof_status",
            Map.of("proofId", proofId));
        JsonSchemaValidator.validate(status, (Map<String, Object>) schemas.get("key_proof_status"));

        Map<String, Object> goals = callTool(server, "key_proof_goals_list",
            Map.of("proofId", proofId));
        JsonSchemaValidator.validate(goals,
            (Map<String, Object>) schemas.get("key_proof_goals_list"));

        int goalId = ((Number) ((Map<String, Object>) ((List<?>) goals.get("goals")).get(0))
                .get("goalId")).intValue();
        Map<String, Object> goal = callTool(server, "key_proof_goal_get",
            Map.of("proofId", proofId, "goalId", goalId));
        JsonSchemaValidator.validate(goal, (Map<String, Object>) schemas.get("key_proof_goal_get"));

        Map<String, Object> rules = callTool(server, "key_proof_rules_list",
            Map.of("proofId", proofId));
        JsonSchemaValidator.validate(rules,
            (Map<String, Object>) schemas.get("key_proof_rules_list"));

        List<?> builtIns = (List<?>) rules.get("builtInRules");
        assertThat(builtIns).isNotEmpty();
        // The initial goal of a method-contract proof is an implication; impRight is
        // reliably applicable to split it.
        String ruleName = "impRight";

        Map<String, Object> ruleApply = callTool(server, "key_proof_rule_apply",
            Map.of("proofId", proofId, "goalId", goalId, "ruleName", ruleName));
        JsonSchemaValidator.validate(ruleApply,
            (Map<String, Object>) schemas.get("key_proof_rule_apply"));

        Map<String, Object> undo = callTool(server, "key_proof_undo",
            Map.of("proofId", proofId, "goalId", goalId));
        JsonSchemaValidator.validate(undo, (Map<String, Object>) schemas.get("key_proof_undo"));

        Map<String, Object> script = callTool(server, "key_proof_script_run",
            Map.of("proofId", proofId, "script", "auto;"));
        JsonSchemaValidator.validate(script,
            (Map<String, Object>) schemas.get("key_proof_script_run"));
    }

    @Test
    void proofAutoAndOperationToolsOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadProject(server);
        Map<String, Object> schemas = buildToolSchemas(server);
        String contractId = findContractId(server, "add");
        assertThat(contractId).isNotNull();

        Map<String, Object> auto = callTool(server, "key_proof_auto", Map.of(
            "contractId", contractId,
            "timeoutMs", 30000,
            "maxSteps", 10000,
            "async", true));
        JsonSchemaValidator.validate(auto, (Map<String, Object>) schemas.get("key_proof_auto"));

        String operationId = (String) auto.get("operationId");
        Map<String, Object> wait = callTool(server, "key_operation_wait", Map.of(
            "operationId", operationId,
            "timeoutMs", 35000));
        JsonSchemaValidator.validate(wait,
            (Map<String, Object>) schemas.get("key_operation_wait"));

        // Re-create an operation so we can exercise key_operation_cancel.
        Map<String, Object> auto2 = callTool(server, "key_proof_auto", Map.of(
            "contractId", contractId,
            "timeoutMs", 30000,
            "maxSteps", 10000,
            "async", true));
        Map<String, Object> cancel = callTool(server, "key_operation_cancel", Map.of(
            "operationId", auto2.get("operationId")));
        JsonSchemaValidator.validate(cancel,
            (Map<String, Object>) schemas.get("key_operation_cancel"));
    }

    @Test
    void exportToolsOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadProject(server);
        Map<String, Object> schemas = buildToolSchemas(server);
        String contractId = findContractId(server, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> create = callTool(server, "key_proof_create",
            Map.of("contractId", contractId));
        String proofId = (String) create.get("proofId");

        Map<String, Object> exportProof = callTool(server, "key_proof_export",
            Map.of("proofId", proofId, "format", "proof"));
        JsonSchemaValidator.validate(exportProof,
            (Map<String, Object>) schemas.get("key_proof_export"));

        Map<String, Object> exportJson = callTool(server, "key_proof_export",
            Map.of("proofId", proofId, "format", "json"));
        JsonSchemaValidator.validate(exportJson,
            (Map<String, Object>) schemas.get("key_proof_export"));

        Map<String, Object> smt = callTool(server, "key_proof_smt",
            Map.of("proofId", proofId));
        JsonSchemaValidator.validate(smt, (Map<String, Object>) schemas.get("key_proof_smt"));

        Map<String, Object> counterexample = callTool(server, "key_proof_counterexample",
            Map.of("proofId", proofId));
        JsonSchemaValidator.validate(counterexample,
            (Map<String, Object>) schemas.get("key_proof_counterexample"));
    }

    @Test
    void syncProofAutoOutputMatchesSchema() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadProject(server);
        Map<String, Object> schemas = buildToolSchemas(server);
        String contractId = findContractId(server, "add");
        assertThat(contractId).isNotNull();

        Map<String, Object> auto = callTool(server, "key_proof_auto", Map.of(
            "contractId", contractId,
            "timeoutMs", 30000,
            "maxSteps", 10000,
            "async", false));
        JsonSchemaValidator.validate(auto, (Map<String, Object>) schemas.get("key_proof_auto"));
    }
}
