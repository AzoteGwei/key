/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyMcpServerTest {

    @Test
    void initializeSucceeds() {
        TestTransport transport = new TestTransport();
        KeyMcpServer server = new KeyMcpServer(transport);

        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");

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
        TestTransport transport = new TestTransport();
        KeyMcpServer server = new KeyMcpServer(transport);

        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}");

        assertThat(transport.getSentMessages()).hasSize(2);
        Map<String, Object> second = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> error = (Map<String, Object>) second.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    void pingReturnsEmptyResult() {
        TestTransport transport = new TestTransport();
        KeyMcpServer server = new KeyMcpServer(transport);

        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\",\"params\":{}}");

        assertThat(transport.getSentMessages()).hasSize(2);
        Map<String, Object> pingResponse = Json.parseObject(transport.getSentMessages().get(1));
        assertThat(pingResponse.get("id")).isEqualTo(2);
        assertThat(pingResponse.get("result")).isEqualTo(Map.of());
    }

    @Test
    void toolsListReturnsEmptyArray() {
        TestTransport transport = new TestTransport();
        KeyMcpServer server = new KeyMcpServer(transport);

        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
        server.handleMessage("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");

        Map<String, Object> response = Json.parseObject(transport.getSentMessages().get(1));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        assertThat(result.get("tools")).isEqualTo(List.of());
    }
}
