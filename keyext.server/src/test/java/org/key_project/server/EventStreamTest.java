/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Being told rather than asking.
 *
 * <p>
 * Polling works and stays supported, but it makes a client choose between asking too often and
 * learning too late, and a proof search offers no way to pick. These tests never call
 * {@code task.get}: everything they assert arrives on the stream on its own.
 */
class EventStreamTest {

    private static final Duration BUDGET = Duration.ofMinutes(5);

    private KeyServerInstance instance;
    private RpcTestClient client;

    @BeforeEach
    void startServer() throws Exception {
        instance = new KeyServerInstance(new ServerOptions(0, Path.of(""), 0, 1));
        instance.start();
        client = new RpcTestClient(instance.port());
    }

    @AfterEach
    void stopServer() {
        if (instance != null) {
            instance.close();
        }
    }

    @Test
    void aClientLearnsATaskFinishedWithoutEverAskingAboutIt() throws Exception {
        try (SseTestClient stream = new SseTestClient(instance.port())) {
            String taskId = client.result("environment.load", loadParams("adder")).get("taskId")
                    .asText();

            JsonNode progress = stream.await("task.progress", BUDGET);
            assertThat(progress.get("taskId").asText()).isEqualTo(taskId);

            JsonNode finished = stream.await("task.finished", BUDGET);
            assertThat(finished.get("taskId").asText()).isEqualTo(taskId);
            assertThat(finished.get("kind").asText()).isEqualTo("LOAD");
            assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
            // The finished event carries the whole handle, so there is nothing left to go and
            // fetch afterwards.
            assertThat(finished.get("result").get("envId").asText()).startsWith("env-");
        }
    }

    @Test
    void aSearchAnnouncesItsOutcomeAndWhatTheProofLooksLike() throws Exception {
        String proofId = startProof();

        try (SseTestClient stream = new SseTestClient(instance.port())) {
            String taskId = client.result("proof.runAuto",
                "{\"proof\":{\"proofId\":\"" + proofId + "\"}}").get("taskId").asText();

            JsonNode finished = stream.await("task.finished", BUDGET);

            assertThat(finished.get("taskId").asText()).isEqualTo(taskId);
            assertThat(finished.get("kind").asText()).isEqualTo("AUTO");
            assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
            // The fixture is the broken one, so the event has to carry the honest answer too.
            assertThat(finished.get("result").get("statistics").get("closed").asBoolean())
                    .isFalse();
        }
    }

    @Test
    void aFailureArrivesOnTheStreamAsPlainlyAsASuccess() throws Exception {
        try (SseTestClient stream = new SseTestClient(instance.port())) {
            // The file exists, so the request is accepted and the failure happens inside the
            // task. That is precisely the case a client cannot see for itself, and the one where
            // a stream that only reported good news would be worse than no stream.
            client.result("environment.load", loadParams("unparseable/broken.key"));

            JsonNode finished = stream.await("task.finished", BUDGET);

            assertThat(finished.get("status").asText()).isEqualTo("FAILED");
            assertThat(finished.has("result")).isFalse();
            // And it arrives with what KeY knew about it, not just a status.
            JsonNode positions = finished.get("error").get("positions");
            assertThat(positions).isNotEmpty();
            assertThat(positions.get(0).get("file").asText()).endsWith("broken.key");
            assertThat(positions.get(0).get("line").asInt()).isPositive();
            assertThat(finished.get("error").get("detail").asText()).isNotBlank();
        }
    }

    @Test
    void severalClientsAreAllTold() throws Exception {
        try (SseTestClient first = new SseTestClient(instance.port());
                SseTestClient second = new SseTestClient(instance.port())) {
            String taskId = client.result("environment.load", loadParams("adder")).get("taskId")
                    .asText();

            assertThat(first.await("task.finished", BUDGET).get("taskId").asText())
                    .isEqualTo(taskId);
            assertThat(second.await("task.finished", BUDGET).get("taskId").asText())
                    .isEqualTo(taskId);
        }
    }

    @Test
    void theStreamSaysHelloSoAClientKnowsItIsListening() throws Exception {
        try (SseTestClient stream = new SseTestClient(instance.port())) {
            // Without a first byte a client cannot tell an accepted stream from one still being
            // negotiated, and would not know when it is safe to trigger the work it wants to
            // watch.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (stream.comments().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertThat(stream.comments()).contains("open");
        }
    }

    @Test
    void progressIsCoalescedInsteadOfFloodingTheStream() throws Exception {
        String proofId = startProof();

        try (SseTestClient stream = new SseTestClient(instance.port())) {
            client.result("proof.runAuto", "{\"proof\":{\"proofId\":\"" + proofId + "\"}}");
            JsonNode finished = stream.await("task.finished", BUDGET);
            int ruleApps = finished.get("result").get("statistics").get("totalRuleApps").asInt();

            // KeY reports progress on every rule application. Relaying each one would put
            // thousands of frames a second on a socket nobody can read that fast, so only the
            // latest survives each flush.
            assertThat(ruleApps).isGreaterThan(20);
            assertThat(stream.countPending("task.progress")).isLessThan(ruleApps);
        }
    }

    private static String loadParams(String fixture) {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        return "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}";
    }

    private String startProof() throws Exception {
        String loadTask =
            client.result("environment.load", loadParams("broken-max")).get("taskId").asText();
        JsonNode loaded;
        do {
            Thread.sleep(100);
            loaded = client.result("task.get", "{\"taskId\":\"" + loadTask + "\"}");
        } while ("PENDING".equals(loaded.get("status").asText())
                || "RUNNING".equals(loaded.get("status").asText()));
        String envId = loaded.get("result").get("envId").asText();
        String contractId = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"}}").get(0).get("contractId").asText();
        return client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();
    }
}
