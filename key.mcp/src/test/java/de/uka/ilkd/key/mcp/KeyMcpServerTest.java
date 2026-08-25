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
}
