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
 * Tests for protocol-level request cancellation via {@code notifications/cancelled}.
 */
class ProtocolCancelTest {

    private KeyMcpServer createServer() {
        TestTransport transport = new TestTransport();
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent();
        McpServerConfig config = new McpServerConfig(
            root,
            List.of(root),
            60000L, 10000L, List.of());
        return new KeyMcpServer(transport, config);
    }

    private void initialize(KeyMcpServer server) {
        server.handleMessage(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}");
    }

    private void loadProject(KeyMcpServer server) {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"key_project_load\",\"arguments\":{\"location\":\"key.core.example/example\"}}}";
        server.handleMessage(message);
    }

    private String findContractId(KeyMcpServer server, String targetSubstring) {
        String message = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"key_contracts_list\",\"arguments\":{}}}";
        server.handleMessage(message);
        TestTransport transport = (TestTransport) server.transport;
        Map<String, Object> response = Json.parseObject(
            transport.getSentMessages().get(transport.getSentMessages().size() - 1));
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        List<?> contracts =
            (List<?>) ((Map<String, Object>) result.get("structuredContent")).get("contracts");
        for (Object c : contracts) {
            Map<String, Object> contract = (Map<String, Object>) c;
            if (contract.get("targetName").toString().contains(targetSubstring)) {
                return (String) contract.get("contractId");
            }
        }
        return null;
    }

    private List<String> messagesWithId(KeyMcpServer server, Object id) {
        TestTransport transport = (TestTransport) server.transport;
        return transport.getSentMessages().stream()
                .map(Json::parseObject)
                .filter(r -> id.equals(r.get("id")))
                .map(Json::stringify)
                .toList();
    }

    private void waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    @Test
    void cancelInterruptsBlockingOperationWaitAndSuppressesResponse() throws Exception {
        KeyMcpServer server = createServer();
        initialize(server);
        // Create a running operation that never finishes on its own.
        String operationId = server.getSession().getOperationTracker()
                .start("proof_1", "test").getId();

        int waitRequestId = 42;
        server.clearCancelled(waitRequestId);
        CountDownLatch waitDone = new CountDownLatch(1);
        String waitMessage = "{\"jsonrpc\":\"2.0\",\"id\":" + waitRequestId
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"key_operation_wait\","
            + "\"arguments\":{\"operationId\":\"" + operationId + "\",\"timeoutMs\":60000}}}";
        Thread waitThread = new Thread(() -> {
            try {
                server.handleMessage(waitMessage);
            } finally {
                waitDone.countDown();
            }
        }, "test-wait-thread");
        waitThread.start();

        // Wait until the server has registered the wait request as in-flight, then cancel it.
        waitUntil(() -> server.isInFlight(waitRequestId), 2000);
        String cancelMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
            + "\"params\":{\"requestId\":" + waitRequestId + "}}";
        server.handleMessage(cancelMessage);

        // The wait thread should finish quickly after cancellation.
        assertThat(waitDone.await(5, TimeUnit.SECONDS)).isTrue();

        // No response must be sent for the cancelled wait request.
        assertThat(messagesWithId(server, waitRequestId)).isEmpty();
    }

    @Test
    void cancelInterruptsSyncProofAutoAndSuppressesResponse() throws Exception {
        KeyMcpServer server = createServer();
        initialize(server);
        loadProject(server);
        String contractId = findContractId(server, "sub");
        assertThat(contractId).isNotNull();

        int autoRequestId = 43;
        server.clearCancelled(autoRequestId);
        CountDownLatch autoDone = new CountDownLatch(1);
        String autoMessage = "{\"jsonrpc\":\"2.0\",\"id\":" + autoRequestId
            + ",\"method\":\"tools/call\",\"params\":{\"name\":\"key_proof_auto\","
            + "\"arguments\":{\"contractId\":\"" + contractId
            + "\",\"timeoutMs\":120000,\"maxSteps\":100000,\"async\":false}}}";
        Thread autoThread = new Thread(() -> {
            try {
                server.handleMessage(autoMessage);
            } finally {
                autoDone.countDown();
            }
        }, "test-auto-thread");
        autoThread.start();

        // Give the handler a moment to enter worker.join, then cancel it.
        waitUntil(() -> server.isInFlight(autoRequestId), 2000);
        Thread.sleep(100);
        String cancelMessage = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\","
            + "\"params\":{\"requestId\":" + autoRequestId + "}}";
        server.handleMessage(cancelMessage);

        assertThat(autoDone.await(10, TimeUnit.SECONDS)).isTrue();

        // No response must be sent for the cancelled auto request.
        assertThat(messagesWithId(server, autoRequestId)).isEmpty();
    }
}
