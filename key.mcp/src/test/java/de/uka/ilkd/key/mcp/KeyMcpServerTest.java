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
        TestTransport transport = new TestTransport();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        McpServerConfig config = new McpServerConfig(
            root,
            List.of(root),
            60000L, 10000L, "4g", List.of());
        return new KeyMcpServer(transport, config);
    }

    private void initialize(KeyMcpServer server) {
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    }

    private static void assertNoError(Map<String, Object> response) {
        if (response.containsKey("error")) {
            throw new AssertionError("Unexpected error response: " + response.get("error"));
        }
    }

    private String loadExampleProject(KeyMcpServer server) {
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"key.core.example/example\"}}}");
        return "key_project_load";
    }

    private Map<String, Object> contractsResult(KeyMcpServer server) {
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"key_contracts_list\",\"arguments\":{}}}");
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> contractsResponse = Json.parseObject(transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        assertNoError(contractsResponse);
        return (Map<String, Object>) contractsResponse.get("result");
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
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");
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
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
        TestTransport transport = (TestTransport) server.transport;

        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> tools = (List<?>) result.get("tools");
        assertThat(tools).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void projectLoadAndContractsList() {
        KeyMcpServer server = createServer();
        initialize(server);
        String load = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"key.core.example/example\"}}}";
        server.handleMessage(load);
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"key_contracts_list\",\"arguments\":{}}}");
        TestTransport transport = (TestTransport) server.transport;

        assertThat(transport.getSentMessages()).hasSize(3);
        Map<String, Object> loadResponse = Json.parseObject(transport.getSentMessages().get(1));
        assertNoError(loadResponse);
        Map<String, Object> loadResult = (Map<String, Object>) loadResponse.get("result");
        assertThat(loadResult.get("success")).isEqualTo(true);
        assertThat((Integer) loadResult.get("contractCount")).isGreaterThan(0);

        Map<String, Object> contractsResponse = Json.parseObject(transport.getSentMessages().get(2));
        assertNoError(contractsResponse);
        Map<String, Object> contractsResult = (Map<String, Object>) contractsResponse.get("result");
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

        String auto = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_auto\",\"arguments\":{\"contractId\":\"" + contractId + "\",\"timeoutMs\":30000,\"maxSteps\":10000,\"async\":true}}}";
        server.handleMessage(auto);
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> autoResponse = Json.parseObject(transport.getSentMessages().get(3));
        assertNoError(autoResponse);
        Map<String, Object> autoResult = (Map<String, Object>) autoResponse.get("result");
        String operationId = (String) autoResult.get("operationId");

        String wait = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"key_operation_wait\",\"arguments\":{\"operationId\":\"" + operationId + "\",\"timeoutMs\":35000}}}";
        server.handleMessage(wait);
        Map<String, Object> waitResponse = Json.parseObject(transport.getSentMessages().get(4));
        assertNoError(waitResponse);
        Map<String, Object> waitResult = (Map<String, Object>) waitResponse.get("result");
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

        String create = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_create\",\"arguments\":{\"contractId\":\"" + contractId + "\"}}}";
        server.handleMessage(create);
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> createResponse = Json.parseObject(transport.getSentMessages().get(3));
        assertNoError(createResponse);
        Map<String, Object> createResult = (Map<String, Object>) createResponse.get("result");
        String proofId = (String) createResult.get("proofId");
        assertThat((Integer) createResult.get("openGoals")).isGreaterThan(0);

        String goals = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_goals_list\",\"arguments\":{\"proofId\":\"" + proofId + "\"}}}";
        server.handleMessage(goals);
        Map<String, Object> goalsResponse = Json.parseObject(transport.getSentMessages().get(4));
        assertNoError(goalsResponse);
        Map<String, Object> goalsResult = (Map<String, Object>) goalsResponse.get("result");
        List<?> openGoals = (List<?>) goalsResult.get("goals");
        assertThat(openGoals).isNotEmpty();
        int goalId = ((Number) ((Map<String, Object>) openGoals.get(0)).get("goalId")).intValue();

        String goalGet = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_goal_get\",\"arguments\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId + "}}}";
        server.handleMessage(goalGet);
        Map<String, Object> goalResponse = Json.parseObject(transport.getSentMessages().get(5));
        assertNoError(goalResponse);

        String scriptRun = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_script_run\",\"arguments\":{\"proofId\":\"" + proofId + "\",\"script\":\"auto;\"}}}";
        server.handleMessage(scriptRun);
        Map<String, Object> scriptResponse = Json.parseObject(transport.getSentMessages().get(6));
        assertNoError(scriptResponse);
        Map<String, Object> scriptResult = (Map<String, Object>) scriptResponse.get("result");
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

        String create = "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_create\",\"arguments\":{\"contractId\":\"" + contractId + "\"}}}";
        server.handleMessage(create);
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> createResponse = Json.parseObject(transport.getSentMessages().get(3));
        assertNoError(createResponse);
        Map<String, Object> createResult = (Map<String, Object>) createResponse.get("result");
        String proofId = (String) createResult.get("proofId");

        String exportProof = "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_export\",\"arguments\":{\"proofId\":\"" + proofId + "\",\"format\":\"proof\"}}}";
        server.handleMessage(exportProof);
        Map<String, Object> exportProofResponse = Json.parseObject(transport.getSentMessages().get(4));
        assertNoError(exportProofResponse);
        Map<String, Object> exportProofResult = (Map<String, Object>) exportProofResponse.get("result");
        assertThat((String) exportProofResult.get("content")).contains("\\proof");

        String exportJson = "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_export\",\"arguments\":{\"proofId\":\"" + proofId + "\",\"format\":\"json\"}}}";
        server.handleMessage(exportJson);
        Map<String, Object> exportJsonResponse = Json.parseObject(transport.getSentMessages().get(5));
        assertNoError(exportJsonResponse);
        Map<String, Object> exportJsonResult = (Map<String, Object>) exportJsonResponse.get("result");
        assertThat(exportJsonResult.get("tree")).isNotNull();

        String smt = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_smt\",\"arguments\":{\"proofId\":\"" + proofId + "\"}}}";
        server.handleMessage(smt);
        Map<String, Object> smtResponse = Json.parseObject(transport.getSentMessages().get(6));
        assertNoError(smtResponse);
        Map<String, Object> smtResult = (Map<String, Object>) smtResponse.get("result");
        assertThat((String) smtResult.get("smt")).contains("assert");
    }
}
