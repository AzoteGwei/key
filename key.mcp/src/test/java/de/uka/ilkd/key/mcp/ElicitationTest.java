/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.uka.ilkd.key.mcp.json.Json;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for legacy-era elicitation path confirmation.
 */
class ElicitationTest {

    private KeyMcpServer createServer(List<Path> allowedPaths, long elicitationTimeoutMs) {
        TestTransport transport = new TestTransport();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        McpServerConfig config = new McpServerConfig(
            root,
            allowedPaths,
            60000L, 10000L, List.of(), elicitationTimeoutMs);
        return new KeyMcpServer(transport, config);
    }

    private Map<String, Object> lastResponse(KeyMcpServer server) {
        TestTransport transport = (TestTransport) server.transport;
        return Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
    }

    private void initialize(KeyMcpServer server, boolean elicitation) {
        String caps = elicitation
                ? "{\"experimental\":{\"elicitation\":true}}"
                : "{}";
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"capabilities\":"
                + caps + "}}");
    }

    private void initialize(KeyMcpServer server) {
        initialize(server, false);
    }

    private String findElicitationRequest(KeyMcpServer server) {
        TestTransport transport = (TestTransport) server.transport;
        for (String message : transport.getSentMessages()) {
            if (message.contains("\"method\":\"elicitation/create\"")) {
                return message;
            }
        }
        return null;
    }

    private String extractRequestId(String message) {
        Map<String, Object> parsed = Json.parseObject(message);
        return (String) parsed.get("id");
    }

    @Test
    void legacyHardRejectWithoutElicitationCapability() {
        KeyMcpServer server = createServer(List.of(), 60000L);
        initialize(server);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"/etc\"}}}");
        Map<String, Object> response = lastResponse(server);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32001);
    }

    @Test
    void modernHardRejectWithoutElicitation() {
        KeyMcpServer server = createServer(List.of(), 60000L);
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"/etc\"},"
                + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\",\"io.modelcontextprotocol/clientCapabilities\":{}}}}");
        Map<String, Object> response = lastResponse(server);
        Map<String, Object> error = (Map<String, Object>) response.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32602);
    }

    @Test
    void elicitationAllowSessionCachesPath() throws Exception {
        KeyMcpServer server = createServer(List.of(), 60000L);
        initialize(server, true);

        CountDownLatch done = new CountDownLatch(1);
        // The example project is not in the (empty) whitelist, so elicitation is triggered.
        String location = "key.core.example/example";
        String loadMessage =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\""
                + location + "\"}}}";
        new Thread(() -> {
            try {
                server.handleMessage(loadMessage);
            } finally {
                done.countDown();
            }
        }).start();

        // Wait for the elicitation request to be sent.
        String elicitationRequest = null;
        long deadline = System.currentTimeMillis() + 30000;
        while (elicitationRequest == null && System.currentTimeMillis() < deadline) {
            elicitationRequest = findElicitationRequest(server);
            Thread.sleep(10);
        }
        assertThat(elicitationRequest).isNotNull();
        String requestId = extractRequestId(elicitationRequest);

        // Respond with accept + allow_session.
        String responseMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
            + "\",\"result\":{\"action\":\"accept\",\"result\":{\"decision\":\"allow_session\"}}}";
        server.handleMessage(responseMessage);

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> loadResponse = lastResponse(server);
        assertThat(loadResponse.get("error")).isNull();
        Map<String, Object> result = (Map<String, Object>) loadResponse.get("result");
        Map<String, Object> content = (Map<String, Object>) result.get("structuredContent");
        assertThat(content.get("success")).isEqualTo(true);

        // A second request for the same path must succeed without another elicitation.
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\""
                + location + "\"}}}");
        Map<String, Object> secondResponse = lastResponse(server);
        assertThat(secondResponse.get("error")).isNull();
        // No new elicitation request was sent.
        int elicitationCount = 0;
        TestTransport transport = (TestTransport) server.transport;
        for (String m : transport.getSentMessages()) {
            if (m.contains("\"method\":\"elicitation/create\"")) {
                elicitationCount++;
            }
        }
        assertThat(elicitationCount).isEqualTo(1);
    }

    @Test
    void elicitationDeclineReturnsHardReject() throws Exception {
        KeyMcpServer server = createServer(List.of(), 60000L);
        initialize(server, true);

        CountDownLatch done = new CountDownLatch(1);
        String loadMessage =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"/etc\"}}}";
        new Thread(() -> {
            try {
                server.handleMessage(loadMessage);
            } finally {
                done.countDown();
            }
        }).start();

        String elicitationRequest = null;
        long deadline = System.currentTimeMillis() + 30000;
        while (elicitationRequest == null && System.currentTimeMillis() < deadline) {
            elicitationRequest = findElicitationRequest(server);
            Thread.sleep(10);
        }
        assertThat(elicitationRequest).isNotNull();
        String requestId = extractRequestId(elicitationRequest);

        String responseMessage = "{\"jsonrpc\":\"2.0\",\"id\":\"" + requestId
            + "\",\"result\":{\"action\":\"decline\"}}";
        server.handleMessage(responseMessage);

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> loadResponse = lastResponse(server);
        Map<String, Object> error = (Map<String, Object>) loadResponse.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32001);
    }

    @Test
    void elicitationTimeoutReturnsHardReject() throws Exception {
        KeyMcpServer server = createServer(List.of(), 500L);
        initialize(server, true);

        CountDownLatch done = new CountDownLatch(1);
        String loadMessage =
            "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"/etc\"}}}";
        new Thread(() -> {
            try {
                server.handleMessage(loadMessage);
            } finally {
                done.countDown();
            }
        }).start();

        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        Map<String, Object> loadResponse = lastResponse(server);
        Map<String, Object> error = (Map<String, Object>) loadResponse.get("error");
        assertThat(error).isNotNull();
        assertThat(error.get("code")).isEqualTo(-32001);
    }
}
