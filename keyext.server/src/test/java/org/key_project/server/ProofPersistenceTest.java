/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Making a proof outlive the server that found it.
 *
 * <p>
 * Until a proof can be written out, everything an agent establishes disappears when the process
 * does: there is no file to read, re-check or commit, and the only evidence a contract was proved
 * is that something once said so. What is written is KeY's own format, so the claim can be checked
 * by KeY rather than only by this server.
 */
class ProofPersistenceTest {

    private static final Duration BUDGET = Duration.ofMinutes(5);

    @TempDir
    private Path saveDirectory;

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

    @Test
    void aClosedProofSurvivesAsAFileAndComesBackClosed() throws Exception {
        String proofId = prove("max", "Max");
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("closed").asBoolean())
                .isTrue();

        Path file = saveDirectory.resolve("max.proof");
        JsonNode saved = client.result("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"" + json(file) + "\"}");

        assertThat(saved.get("path").asText()).isEqualTo(file.toString());
        assertThat(saved.get("bytes").asLong()).isPositive();
        assertThat(file).exists();
        assertThat(Files.readString(file)).contains("proof");

        // The point of writing it: the verdict has to survive the round trip. A file that loads
        // back as an open proof would be worse than no file, because it looks like evidence.
        JsonNode reloaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + json(file) + "\"}").get("taskId").asText());
        assertThat(reloaded.get("status").asText()).isEqualTo("SUCCEEDED");
        String reloadedProof = reloaded.get("result").get("proof").get("proofId").asText();
        assertThat(client.result("proof.getStatistics", proof(reloadedProof)).get("closed")
                .asBoolean()).isTrue();
    }

    @Test
    void anUnfinishedProofComesBackJustAsUnfinished() throws Exception {
        String proofId = prove("broken-max", "Max");
        int openGoals = client.result("proof.getStatistics", proof(proofId)).get("openGoals")
                .asInt();
        assertThat(openGoals).isPositive();

        Path file = saveDirectory.resolve("broken.proof");
        client.result("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"" + json(file) + "\"}");

        JsonNode reloaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + json(file) + "\"}").get("taskId").asText());
        String reloadedProof = reloaded.get("result").get("proof").get("proofId").asText();

        // Saving must not launder an unproved proof into a proved one, and reloading must not
        // quietly finish it either.
        JsonNode statistics = client.result("proof.getStatistics", proof(reloadedProof));
        assertThat(statistics.get("closed").asBoolean()).isFalse();
        assertThat(statistics.get("openGoals").asInt()).isEqualTo(openGoals);
    }

    @Test
    void aSaveThatDidNotHappenIsReportedAsAFailure() throws Exception {
        String proofId = prove("max", "Max");

        // KeY's saver reports a failure by returning it and throws nothing at all, so a server
        // that did not look at the return value would answer with a path to a file that does not
        // exist. This is the test that says it looked.
        JsonNode response = client.call("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"/proc/no/such/place.proof\"}");

        assertThat(response.has("result")).isFalse();
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32011);
    }

    @Test
    void aProofWithNoPathGivenIsNamedAfterItselfInTheWorkspace() throws Exception {
        String proofId = prove("max", "Max");

        JsonNode saved =
            client.result("proof.save", "{\"proof\":{\"proofId\":\"" + proofId + "\"}}");

        Path written = Path.of(saved.get("path").asText());
        assertThat(written).exists();
        assertThat(written.getFileName().toString()).endsWith(".proof");
        // In the workspace, not the working directory: an instance is anchored somewhere and its
        // output belongs there.
        assertThat(written.getParent()).isEqualTo(instance.workspace());
    }

    @Test
    void aBundleCarriesTheSourcesWithIt() throws Exception {
        String proofId = prove("max", "Max");
        Path file = saveDirectory.resolve("max.zproof");

        JsonNode saved = client.result("proof.save", "{\"proof\":{\"proofId\":\"" + proofId
            + "\"},\"path\":\"" + json(file) + "\",\"asBundle\":true}");

        assertThat(saved.get("bytes").asLong()).isPositive();
        // A bundle is a zip; a plain proof file is not. Checking the magic bytes rather than the
        // extension keeps this honest about what was actually produced.
        assertThat(Files.readAllBytes(file)).startsWith('P', 'K');
    }

    @Test
    void aSavedProofComesBackThroughLoadFileToo() throws Exception {
        String proofId = prove("max", "Max");
        Path file = saveDirectory.resolve("round-trip.proof");
        client.result("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"" + json(file) + "\"}");

        JsonNode finished = awaitTask(client.result("proof.loadFile",
            "{\"path\":\"" + json(file) + "\"}").get("taskId").asText());

        assertThat(finished.get("kind").asText()).isEqualTo("REPLAY");
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        String reloaded = finished.get("result").get("proof").get("proofId").asText();
        assertThat(client.result("proof.getStatistics", proof(reloaded)).get("closed").asBoolean())
                .isTrue();
    }

    @Test
    void aProofThatOnlyPartlyReplayedIsRefusedRatherThanServed() throws Exception {
        String proofId = prove("max", "Max");
        Path file = saveDirectory.resolve("damaged.proof");
        client.result("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"" + json(file) + "\"}");

        // Break one rule name in the middle of the recorded proof. KeY will replay what it can
        // and report the rest as errors on the side, handing back a proof object regardless.
        String damaged = Files.readString(file).replaceFirst(
            "\\(rule \"[A-Za-z_]+\"", "(rule \"noSuchRuleExistsAnywhere\"");
        assertThat(damaged).isNotEqualTo(Files.readString(file));
        Files.writeString(file, damaged);

        JsonNode finished = awaitTask(client.result("proof.loadFile",
            "{\"path\":\"" + json(file) + "\"}").get("taskId").asText());

        // A half-replayed proof looks like any other proof from the outside: it has a tree, it
        // has goals, it answers questions. Serving one would be serving something that is not
        // the proof the file describes, and the missing steps are exactly the ones that failed.
        assertThat(finished.get("status").asText()).isEqualTo("FAILED");
        assertThat(finished.has("result")).isFalse();
        assertThat(finished.get("error").get("detail").asText()).isNotBlank();
    }

    @Test
    void loadFileRefusesAFileWithNoProofInIt() throws Exception {
        Path project = Path.of("src/test/resources/fixtures/max").toAbsolutePath();

        // Whether a location holds a proof cannot be known without loading it, so this is
        // answered by the task rather than up front.
        JsonNode finished = awaitTask(client.result("proof.loadFile",
            "{\"path\":\"" + json(project) + "\"}").get("taskId").asText());

        assertThat(finished.get("status").asText()).isEqualTo("FAILED");
        assertThat(finished.get("error").get("detail").asText()).contains("carries no proof");

        // And a path that is not there at all is refused before any task exists.
        assertThat(client.errorCode("proof.loadFile", "{\"path\":\"nowhere.proof\"}"))
                .isEqualTo(-32004);
    }

    @Test
    void pruningTakesBackAWrongTurnAndSaysWhatItUndid() throws Exception {
        String proofId = prove("broken-max", "Max");
        JsonNode before = client.result("proof.getStatistics", proof(proofId));
        assertThat(before.get("closed").asBoolean()).isFalse();
        int nodes = before.get("nodes").asInt();
        assertThat(nodes).isGreaterThan(1);

        // Back to the root: everything comes off and what is left is the obligation again.
        JsonNode pruned = client.result("proof.prune",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"nodeId\":0}");

        assertThat(pruned.get("removedNodes").asInt()).isEqualTo(nodes - 1);
        assertThat(pruned.get("statistics").get("nodes").asInt()).isOne();
        assertThat(pruned.get("statistics").get("openGoals").asInt()).isOne();
        assertThat(pruned.get("statistics").get("closed").asBoolean()).isFalse();
        // The node it cut back to is an open goal again, and its identifier is handed over
        // because every next step needs one.
        assertThat(pruned.get("goal").get("goalId").asInt()).isZero();
        assertThat(client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt())
                .isZero();

        // And the proof can be worked on again from there, which is the point of undoing it.
        awaitTask(client.result("proof.runAuto", proof(proofId)).get("taskId").asText());
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("nodes").asInt())
                .isGreaterThan(1);
    }

    @Test
    void aClosedProofIsNotPrunedAway() throws Exception {
        String proofId = prove("max", "Max");
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("closed").asBoolean())
                .isTrue();

        JsonNode response = client.call("proof.prune",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"nodeId\":0}");

        // KeY declines to prune a closed proof unless told otherwise, which is a good default and
        // a surprising one: the request looks like it worked from the outside. Refusing it out
        // loud beats leaving a caller to wonder why nothing changed.
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("closed").asBoolean())
                .isTrue();
    }

    @Test
    void aPruneThatWouldRemoveNothingIsRefusedRatherThanReported() throws Exception {
        String proofId = prove("broken-max", "Max");
        int openGoal = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();

        // KeY answers "nothing to do" with a null rather than an empty list, so a caller that did
        // not look would be told it had taken back a step it still has.
        JsonNode response = client.call("proof.prune",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"nodeId\":" + openGoal + "}");

        assertThat(response.has("result")).isFalse();
        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32602);
        assertThat(response.get("error").get("message").asText()).contains("Nothing to prune");
    }

    @Test
    void rejectsNodesThatAreNotInThisProof() throws Exception {
        String proofId = prove("max", "Max");

        // KeY checks this with an assertion, which is off in any normal run and lets a foreign
        // node corrupt the proof silently. So it is checked here instead.
        assertThat(client.errorCode("proof.prune",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"nodeId\":999999}"))
                .isEqualTo(-32003);
        assertThat(client.errorCode("proof.prune",
            "{\"proof\":{\"proofId\":\"prf-deadbeef\"},\"nodeId\":0}")).isEqualTo(-32002);
    }

    @Test
    void rejectsProofsAndPathsItCannotUse() throws Exception {
        String proofId = prove("max", "Max");

        assertThat(client.errorCode("proof.save", "{\"proof\":{\"proofId\":\"prf-deadbeef\"}}"))
                .isEqualTo(-32002);
        assertThat(client.errorCode("proof.save",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"path\":\"\\u0000\"}"))
                .isEqualTo(-32602);
    }

    private String proof(String proofId) {
        return "{\"proof\":{\"proofId\":\"" + proofId + "\"}}";
    }

    private static String json(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    private String prove(String fixture, String className) throws Exception {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        JsonNode loaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + json(path) + "\"}").get("taskId").asText());
        String envId = loaded.get("result").get("envId").asText();
        String contractId = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"targetClass\":\"" + className + "\"}")
                .get(0).get("contractId").asText();
        String proofId = client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();
        awaitTask(client.result("proof.runAuto", proof(proofId)).get("taskId").asText());
        return proofId;
    }

    private JsonNode awaitTask(String taskId) throws Exception {
        Instant deadline = Instant.now().plus(BUDGET);
        while (Instant.now().isBefore(deadline)) {
            JsonNode task = client.result("task.get", "{\"taskId\":\"" + taskId + "\"}");
            String status = task.get("status").asText();
            if (!"PENDING".equals(status) && !"RUNNING".equals(status)) {
                return task;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Task " + taskId + " did not finish within " + BUDGET);
    }
}
