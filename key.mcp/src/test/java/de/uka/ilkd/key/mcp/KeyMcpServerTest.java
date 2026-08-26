/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMcpServerTest {

    private KeyMcpServer createServer() {
        return createServer(List.of());
    }

    private KeyMcpServer createServer(List<String> smtSolvers) {
        TestTransport transport = new TestTransport();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        McpServerConfig config = new McpServerConfig(
            root,
            List.of(root),
            60000L, 10000L, smtSolvers);
        return new KeyMcpServer(transport, config);
    }

    private void initialize(KeyMcpServer server) {
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    }

    private static void assertNoError(Map<String, Object> response) {
        if (response.containsKey("error")) {
            throw new AssertionError("Unexpected error response: " + response.get("error"));
        }
    }

    private Map<String, Object> callTool(KeyMcpServer server, int id, String name,
            Map<String, Object> args) {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":" + id
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"" + name
            + "\",\"arguments\":" + Json.stringify(args) + "}}";
        server.handleMessage(message);
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> response = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        assertNoError(response);
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result.get("content")).isNotNull();
        return (Map<String, Object>) result.get("structuredContent");
    }

    private String loadExampleProject(KeyMcpServer server) {
        Map<String, Object> result =
            callTool(server, 2, "key_project_load", Map.of("location", "key.core.example/example"));
        assertThat(result.get("success")).isEqualTo(true);
        return "key.core.example/example";
    }

    private Map<String, Object> contractsResult(KeyMcpServer server) {
        return callTool(server, 3, "key_contracts_list", Map.of());
    }

    private String findContractId(List<?> contracts, String targetSubstring) {
        for (Object c : contracts) {
            Map<String, Object> contract = (Map<String, Object>) c;
            if (contract.get("targetName").toString().contains(targetSubstring)) {
                return (String) contract.get("contractId");
            }
        }
        return null;
    }

    @Test
    void initializeSucceeds() {
        KeyMcpServer server = createServer();
        initialize(server);
        TestTransport transport = (TestTransport) server.transport;

        assertThat(transport.getSentMessages()).hasSize(1);
        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(0));
        assertThat(response.get("id")).isEqualTo(1);

        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result).isNotNull();
        assertThat(result.get("protocolVersion")).isEqualTo("2025-11-25");

        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertThat(serverInfo.get("name")).isEqualTo("key-mcp");
    }

    @Test
    void initializeTwiceFails() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;

        assertThat(transport.getSentMessages()).hasSize(2);
        Map<String, Object> second = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> error = (Map<String, Object>) second.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    void pingReturnsEmptyResult() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;

        assertThat(transport.getSentMessages()).hasSize(2);
        Map<String, Object> pingResponse = Json.parseObject(transport.getSentMessages().get(1));
        assertThat(pingResponse.get("id")).isEqualTo(2);
        assertThat(pingResponse.get("result")).isEqualTo(Map.of());
    }

    @Test
    void toolsListReturnsTools() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;

        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSizeGreaterThanOrEqualTo(15);
    }

    @Test
    void projectLoadAndContractsList() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        assertThat(contracts).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void proofAutoForAddContract() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "add");
        assertThat(contractId).isNotNull();

        Map<String, Object> autoResult = callTool(server, 4, "key_proof_auto", Map.of(
            "contractId", contractId,
            "timeoutMs", 30000,
            "maxSteps", 10000,
            "async", true));
        String operationId = (String) autoResult.get("operationId");
        assertThat(operationId).isNotNull();

        Map<String, Object> waitResult = callTool(server, 5, "key_operation_wait", Map.of(
            "operationId", operationId,
            "timeoutMs", 35000));
        assertThat(waitResult.get("state")).isEqualTo("completed");

        List<?> events = (List<?>) waitResult.get("events");
        Map<String, Object> completed = null;
        for (Object e : events) {
            Map<String, Object> event = (Map<String, Object>) e;
            if ("completed".equals(event.get("type"))) {
                completed = event;
            }
        }
        assertThat(completed).isNotNull();
        assertThat(completed.get("closed")).isEqualTo(true);
    }

    @Test
    void interactiveProofTools() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");
        assertThat((Integer) createResult.get("openGoals")).isGreaterThan(0);

        Map<String, Object> goalsResult =
            callTool(server, 5, "key_proof_goals_list", Map.of("proofId", proofId));
        List<?> openGoals = (List<?>) goalsResult.get("goals");
        assertThat(openGoals).isNotEmpty();
        int goalId = ((Number) ((Map<String, Object>) openGoals.get(0)).get("goalId")).intValue();

        Map<String, Object> goalResult =
            callTool(server, 6, "key_proof_goal_get", Map.of("proofId", proofId, "goalId", goalId));
        assertThat((String) goalResult.get("sequent")).isNotBlank();

        Map<String, Object> scriptResult = callTool(server, 7, "key_proof_script_run",
            Map.of("proofId", proofId, "script", "auto;"));
        assertThat(scriptResult.get("scriptExecuted")).isEqualTo(true);
    }

    @Test
    void proofExportAndSmt() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");

        Map<String, Object> exportProofResult =
            callTool(server, 5, "key_proof_export", Map.of("proofId", proofId, "format", "proof"));
        assertThat((String) exportProofResult.get("content")).contains("\\proof");

        Map<String, Object> exportJsonResult =
            callTool(server, 6, "key_proof_export", Map.of("proofId", proofId, "format", "json"));
        assertThat(exportJsonResult.get("tree")).isNotNull();

        Map<String, Object> smtResult =
            callTool(server, 7, "key_proof_smt", Map.of("proofId", proofId));
        assertThat((String) smtResult.get("smt")).contains("assert");
    }

    @Test
    void counterexampleWithoutSolverReturnsGuidance() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");

        Map<String, Object> ceResult =
            callTool(server, 5, "key_proof_counterexample", Map.of("proofId", proofId));
        assertThat(ceResult.get("supported")).isEqualTo(false);
        assertThat((String) ceResult.get("message")).contains("KEY_MCP_SMT_SOLVERS");
    }

    @Test
    void counterexampleWithZ3CeSolver() {
        org.junit.jupiter.api.Assumptions.assumeTrue(isZ3Available(), "z3 binary not available");
        KeyMcpServer server = createServer(List.of("Z3_CE"));
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> autoResult = callTool(server, 4, "key_proof_auto", Map.of(
            "contractId", contractId,
            "timeoutMs", 30000,
            "maxSteps", 10000,
            "async", false));
        String proofId = (String) autoResult.get("proofId");
        assertThat(autoResult.get("state")).isEqualTo("completed");

        Map<String, Object> ceResult = callTool(server, 5, "key_proof_counterexample", Map.of(
            "proofId", proofId, "solver", "Z3_CE"));
        assertThat(ceResult.get("supported")).isEqualTo(true);
        assertThat(ceResult.get("result")).isEqualTo("FALSIFIABLE");
        assertThat((String) ceResult.get("counterexample")).isNotBlank();
    }

    private static boolean isZ3Available() {
        try {
            Process process = new ProcessBuilder("z3", "--version").start();
            process.waitFor();
            return process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void promptsListAndGet() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"prompts/list\",\"params\":{}}");
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"prompts/get\",\"params\":{\"name\":\"extract_counterexample\",\"arguments\":{\"proofId\":\"proof_1\"}}}");
        TestTransport transport = (TestTransport) server.transport;

        Map<String, Object> listResponse = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> listResult = (Map<String, Object>) listResponse.get("result");
        List<?> prompts = (List<?>) listResult.get("prompts");
        assertThat(prompts).hasSize(4);

        Map<String, Object> getResponse = Json.parseObject(transport.getSentMessages().get(2));
        Map<String, Object> getResult = (Map<String, Object>) getResponse.get("result");
        List<?> messages = (List<?>) getResult.get("messages");
        assertThat(messages).isNotEmpty();
        Map<String, Object> content =
            (Map<String, Object>) ((Map<String, Object>) messages.get(0)).get("content");
        assertThat((String) content.get("text")).contains("key_proof_counterexample");
    }

    @Test
    void unterminatedJsonYieldsParseError() {
        KeyMcpServer server = createServer();
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"");
        TestTransport transport = (TestTransport) server.transport;
        assertThat(transport.getSentMessages()).hasSize(1);
        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(0));
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32700);
    }

    @Test
    void notificationsAreNotAnswered() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{}}");
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"method\":\"unknown/method\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;
        // Only the initialize response was sent; notifications get no response.
        assertThat(transport.getSentMessages()).hasSize(1);
    }

    @Test
    void rootPathIsRejectedByWhitelist() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"/\"}}}");
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32001);
    }

    @Test
    void missingRequiredParamYieldsInvalidParams() {
        KeyMcpServer server = createServer();
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_goal_get\",\"arguments\":{\"proofId\":\"proof_1_x\"}}}");
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32602);
    }

    @Test
    void contractIdsAreDeterministicAcrossSessions() {
        KeyMcpServer first = createServer();
        initialize(first);
        loadExampleProject(first);
        List<?> firstContracts = (List<?>) contractsResult(first).get("contracts");

        KeyMcpServer second = createServer();
        initialize(second);
        loadExampleProject(second);
        List<?> secondContracts = (List<?>) contractsResult(second).get("contracts");

        assertThat(firstContracts).isEqualTo(secondContracts);
    }

    @Test
    void rulesListDiscoversRuleNames() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");

        Map<String, Object> allResult =
            callTool(server, 5, "key_proof_rules_list", Map.of("proofId", proofId));
        List<?> builtIns = (List<?>) allResult.get("builtInRules");
        assertThat(builtIns).isNotEmpty();
        assertThat(((Number) allResult.get("tacletCount")).intValue()).isGreaterThan(100);
        assertThat((String) allResult.get("tacletHint")).contains("filter");

        Map<String, Object> filtered = callTool(server, 6, "key_proof_rules_list",
            Map.of("proofId", proofId, "filter", "andLeft"));
        List<?> taclets = (List<?>) filtered.get("taclets");
        assertThat(taclets.contains("andLeft")).isTrue();
    }

    @Test
    void ruleApplyQuotingAndErrorPaths() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");

        // A built-in rule name with spaces must be quoted in the generated script:
        // the failure must be semantic ("No matching applications"), not a parse error.
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_rule_apply\",\"arguments\":{\"proofId\":\""
                + proofId
                + "\",\"goalId\":0,\"ruleName\":\"One Step Simplification\"}}}");
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> response = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        if (error != null) {
            assertThat(error.get("code")).isEqualTo(-32603);
            assertThat((String) error.get("message"))
                    .doesNotContain("Unexpected positional argument");
        } else {
            // OSS was applicable in this environment: the rule was applied successfully.
            Map<String, Object> result = (Map<String, Object>) response.get("result");
            Map<String, Object> structured = (Map<String, Object>) result.get("structuredContent");
            assertThat(structured.get("applied")).isEqualTo(true);
            assertThat((String) structured.get("script"))
                    .contains("rule \"One Step Simplification\"");
        }

        // Unknown rule names fail gracefully as well.
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_rule_apply\",\"arguments\":{\"proofId\":\""
                + proofId + "\",\"goalId\":0,\"ruleName\":\"no_such_rule_xyz\"}}}");
        Map<String, Object> response2 = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        Map<String, Object> error2 = (Map<String, Object>) response2.get("error");
        assertThat(error2).isNotNull();
        assertThat(error2.get("code")).isEqualTo(-32603);
        assertThat((String) error2.get("message")).contains("no_such_rule_xyz");
    }

    @Test
    void resourcesReadAfterProofCreate() {
        KeyMcpServer server = createServer();
        initialize(server);
        loadExampleProject(server);
        Map<String, Object> contractsResult = contractsResult(server);
        List<?> contracts = (List<?>) contractsResult.get("contracts");
        String contractId = findContractId(contracts, "sub");
        assertThat(contractId).isNotNull();

        Map<String, Object> createResult =
            callTool(server, 4, "key_proof_create", Map.of("contractId", contractId));
        String proofId = (String) createResult.get("proofId");

        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> listResponse = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        Map<String, Object> listResult = (Map<String, Object>) listResponse.get("result");
        List<?> resources = (List<?>) listResult.get("resources");
        assertThat(resources).isNotEmpty();

        String uri = "proof://" + proofId + "/status";
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/read\",\"params\":{\"uri\":\""
                + uri + "\"}}");
        Map<String, Object> readResponse = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        assertNoError(readResponse);
        Map<String, Object> readResult = (Map<String, Object>) readResponse.get("result");
        List<?> contents = (List<?>) readResult.get("contents");
        assertThat(contents).isNotEmpty();
        Map<String, Object> content = (Map<String, Object>) contents.get(0);
        assertThat((String) content.get("text")).contains("closed");

        // The export resource must produce a .proof file (regression: null basePath NPE).
        String exportUri = "proof://" + proofId + "/export";
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/read\",\"params\":{\"uri\":\""
                + exportUri + "\"}}");
        Map<String, Object> exportResponse = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        assertNoError(exportResponse);
        Map<String, Object> exportResult = (Map<String, Object>) exportResponse.get("result");
        List<?> exportContents = (List<?>) exportResult.get("contents");
        Map<String, Object> exportContent = (Map<String, Object>) exportContents.get(0);
        assertThat((String) exportContent.get("text")).contains("\\proof");
    }
}
