/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a real instance over HTTP.
 *
 * <p>
 * This is the acceptance test for the transport milestone: a client that knows nothing but the
 * protocol can start a conversation, load a project and observe the resulting task.
 */
class ServerEndToEndTest {

    private static final Duration LOAD_BUDGET = Duration.ofMinutes(5);

    private KeyServerInstance instance;
    private RpcTestClient client;

    @BeforeEach
    void startServer() throws Exception {
        instance = TestServer.start();
        client = new RpcTestClient(instance.port());
    }

    @AfterEach
    void stopServer() {
        if (instance != null) {
            instance.close();
        }
    }

    private static Path fixture() {
        return Path.of("src/test/resources/fixtures/adder").toAbsolutePath();
    }

    @Test
    void reportsItsOwnIdentity() throws Exception {
        JsonNode version = client.result("server.version", null);

        assertThat(version.get("apiVersion").asText()).isEqualTo(ApiVersion.CURRENT);
        assertThat(version.get("keyVersion").asText()).isNotBlank();
        assertThat(version.get("instanceId").asText()).isEqualTo(instance.instanceId());
        assertThat(version.get("threads").asInt()).isEqualTo(1);
        assertThat(version.has("parallelAvailable")).isFalse();
    }

    @Test
    void answersHealthChecks() throws Exception {
        assertThat(client.result("server.health", null).get("ok").asBoolean()).isTrue();
    }

    @Test
    void loadsAProjectAndReportsItThroughATask() throws Exception {
        JsonNode launched =
            client.result("environment.load", "{\"path\":\"" + json(fixture()) + "\"}");

        assertThat(launched.get("kind").asText()).isEqualTo("LOAD");
        assertThat(launched.get("status").asText()).isIn("PENDING", "RUNNING");

        JsonNode finished = awaitTask(launched.get("taskId").asText());

        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("envId").asText()).startsWith("env-");
    }

    @Test
    void listsAndClosesLoadedEnvironments() throws Exception {
        String envId = loadFixture();

        JsonNode listed = client.result("environment.list", null);
        assertThat(listed).hasSize(1);
        assertThat(listed.get(0).get("envId").asText()).isEqualTo(envId);
        assertThat(listed.get(0).get("path").asText()).isEqualTo(fixture().toString());

        assertThat(client
                .result("environment.close", "{\"env\":{\"envId\":\"" + envId + "\"}}")
                .get("ok").asBoolean()).isTrue();
        assertThat(client.result("environment.list", null)).isEmpty();
    }

    @Test
    void readMethodsStayAnswerableWhileTheWorkerIsBusy() throws Exception {
        JsonNode launched =
            client.result("environment.load", "{\"path\":\"" + json(fixture()) + "\"}");
        String taskId = launched.get("taskId").asText();

        // The worker thread is loading a project right now. Read-only methods must not queue
        // behind it, otherwise an agent cannot see what the server is doing.
        assertThat(client.result("server.health", null).get("ok").asBoolean()).isTrue();
        assertThat(client.result("task.get", "{\"taskId\":\"" + taskId + "\"}").get("taskId")
                .asText()).isEqualTo(taskId);
        assertThat(client.result("task.list", null)).hasSize(1);

        awaitTask(taskId);
    }

    @Test
    void rejectsPathsThatDoNotExistBeforeQueueingAnyWork() throws Exception {
        JsonNode response = client.call("environment.load", "{\"path\":\"does/not/exist\"}");

        // The path is checked up front, so the client learns immediately instead of having to
        // poll a task only to be told the file was never there.
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32004);
        assertThat(response.get("error").get("data").get("detail").asText())
                .contains("workspace");
        assertThat(client.result("task.list", null)).isEmpty();
    }

    @Test
    void refusesToShutDownWhileWorkIsInProgress() throws Exception {
        // Occupy the worker with a load, then ask the server to stop. A client that started work
        // and then asked to stop has probably lost track of one of the two, and throwing the work
        // away on that basis would be the wrong guess to make on its behalf.
        client.result("environment.load", "{\"path\":\"" + json(fixture()) + "\"}");

        assertThat(client.errorCode("server.shutdown", null)).isEqualTo(-32007);
        assertThat(client.errorCode("server.shutdown", "{\"force\":false}")).isEqualTo(-32007);
        assertThat(client.result("server.health", null).get("ok").asBoolean()).isTrue();
    }

    @Test
    void shutsDownWhenAskedAndNothingIsRunning() throws Exception {
        assertThat(client.result("server.shutdown", null).get("ok").asBoolean()).isTrue();

        // The acknowledgement is sent before the instance goes down, so the caller gets an answer
        // rather than a broken socket.
        instance.awaitShutdown();
    }

    @Test
    void describesItselfWithTheDocumentItShips() throws Exception {
        JsonNode document = client.result("server.describe", null);

        assertThat(document.get("openrpc").asText()).isNotBlank();
        assertThat(document.get("methods")).isNotEmpty();
    }

    @Test
    void rejectsUnknownMethods() throws Exception {
        assertThat(client.errorCode("no.such.method", null)).isEqualTo(-32601);
    }

    @Test
    void rejectsBadParameters() throws Exception {
        assertThat(client.errorCode("environment.load", "{\"wrong\":1}")).isEqualTo(-32602);
        assertThat(client.errorCode("environment.load", "[\"positional\"]")).isEqualTo(-32602);
        assertThat(client.errorCode("environment.load", null)).isEqualTo(-32602);
    }

    @Test
    void rejectsUnknownTaskIds() throws Exception {
        assertThat(client.errorCode("task.get", "{\"taskId\":\"task-deadbeef\"}"))
                .isEqualTo(-32010);
    }

    @Test
    void rejectsMalformedAndUnsupportedDocuments() throws Exception {
        assertThat(client.send("not json").get("error").get("code").asInt()).isEqualTo(-32700);
        assertThat(client.send("{\"id\":1,\"method\":\"server.health\"}").get("error").get("code")
                .asInt()).isEqualTo(-32600);
        assertThat(client.send("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server.health\"}]")
                .get("error").get("code").asInt()).isEqualTo(-32600);
    }

    @Test
    void answersNotificationsWithNoContent() throws Exception {
        assertThat(client.send("{\"jsonrpc\":\"2.0\",\"method\":\"server.health\"}").isNull())
                .isTrue();
    }

    private String loadFixture() throws Exception {
        JsonNode launched =
            client.result("environment.load", "{\"path\":\"" + json(fixture()) + "\"}");
        JsonNode finished = awaitTask(launched.get("taskId").asText());
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        return finished.get("result").get("envId").asText();
    }

    private JsonNode awaitTask(String taskId) throws Exception {
        Instant deadline = Instant.now().plus(LOAD_BUDGET);
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = client.result("task.get", "{\"taskId\":\"" + taskId + "\"}");
            String status = task.get("status").asText();
            if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
                return task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Task " + taskId + " did not finish within " + LOAD_BUDGET);
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
