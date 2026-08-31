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
 * Drives one proof from a loaded project to a closed proof, over HTTP.
 *
 * <p>
 * Every claim about verification made here is read back from {@code proof.getStatistics}, which
 * gets it from KeY's own {@code Proof.closed()}. A task reaching {@code SUCCEEDED} is treated as
 * what it is — the work finished without throwing — and never as evidence that anything was
 * proved.
 */
class ProofRunTest {

    private static final Duration BUDGET = Duration.ofMinutes(5);

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
    void listsTheProofObligationsTheFixtureDeclares() throws Exception {
        String envId = loadFixture();

        JsonNode obligations =
            client.result("environment.listProofObligations", env(envId));

        assertThat(obligations).isNotEmpty();
        JsonNode add = obligationFor(obligations, "add");
        assertThat(add.get("kind").asText()).isEqualTo("FUNCTIONAL_OPERATION");
        assertThat(add.get("targetClass").asText()).isEqualTo("Adder");
        assertThat(add.get("targetMember").asText()).isEqualTo("add(int, int)");
        assertThat(add.get("hasExistingProof").asBoolean()).isFalse();
        assertThat(add.get("contractId").asText()).isNotBlank();
    }

    @Test
    void filtersProofObligationsByClass() throws Exception {
        String envId = loadFixture();

        assertThat(client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"targetClass\":\"Adder\"}")).isNotEmpty();
        assertThat(client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"targetClass\":\"NoSuchClass\"}")).isEmpty();
    }

    @Test
    void leavesTheJdkStubsOutOfTheListingUnlessAskedFor() throws Exception {
        String envId = loadFixture();

        JsonNode projectOnly = client.result("environment.listProofObligations", env(envId));
        JsonNode everything = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"includeLibraryClasses\":true}");

        // The default listing is about the project. KeY also loads specifications for its JDK
        // stubs, and burying one contract under hundreds of them would make the method useless.
        assertThat(projectOnly).allSatisfy(
            obligation -> assertThat(obligation.get("targetClass").asText()).isEqualTo("Adder"));
        assertThat(everything.size()).isGreaterThan(projectOnly.size());
        assertThat(everything).anySatisfy(obligation -> assertThat(
            obligation.get("targetClass").asText()).startsWith("java."));
    }

    @Test
    void provesTheFixtureAndReportsTheProofAsClosed() throws Exception {
        String envId = loadFixture();
        String contractId = contractIdFor(envId, "add");

        String proofId = client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();
        assertThat(proofId).startsWith("prf-");

        // Before any search has run, the proof exists but is emphatically not closed.
        JsonNode before = client.result("proof.getStatistics", proof(proofId));
        assertThat(before.get("closed").asBoolean()).isFalse();
        assertThat(before.get("openGoals").asInt()).isPositive();

        JsonNode launched = client.result("proof.runAuto", proof(proofId));
        assertThat(launched.get("kind").asText()).isEqualTo("AUTO");
        assertThat(launched.get("subject").get("proofId").asText()).isEqualTo(proofId);

        JsonNode finished = awaitTask(launched.get("taskId").asText());
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("COMPLETED");
        assertThat(finished.get("result").get("statistics").get("closed").asBoolean()).isTrue();

        // The authority is the proof itself, asked again afterwards.
        JsonNode after = client.result("proof.getStatistics", proof(proofId));
        assertThat(after.get("closed").asBoolean()).isTrue();
        assertThat(after.get("openGoals").asInt()).isZero();
        assertThat(after.get("totalRuleApps").asInt()).isPositive();
        assertThat(after.get("nodes").asInt()).isPositive();
    }

    @Test
    void countsStartedProofsAgainstTheirEnvironment() throws Exception {
        String envId = loadFixture();
        assertThat(client.result("environment.list", null).get(0).get("proofCount").asInt())
                .isZero();

        String contractId = contractIdFor(envId, "add");
        client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}");

        assertThat(client.result("environment.list", null).get(0).get("proofCount").asInt())
                .isEqualTo(1);
        assertThat(obligationFor(client.result("environment.listProofObligations", env(envId)),
            "add").get("hasExistingProof").asBoolean()).isTrue();
    }

    @Test
    void refusesASecondSearchOnTheSameProof() throws Exception {
        String envId = loadFixture();
        String contractId = contractIdFor(envId, "add");
        String proofId = client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();

        JsonNode launched = client.result("proof.runAuto", proof(proofId));
        int second = client.errorCode("proof.runAuto", proof(proofId));

        // Two searches on one proof would corrupt it; the second must be refused, not queued.
        assertThat(second).isEqualTo(-32007);
        awaitTask(launched.get("taskId").asText());
    }

    @Test
    void cancellingAFinishedTaskReportsThatNothingWasStopped() throws Exception {
        String envId = loadFixture();
        String contractId = contractIdFor(envId, "add");
        String proofId = client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();
        String taskId = client.result("proof.runAuto", proof(proofId)).get("taskId").asText();
        awaitTask(taskId);

        assertThat(client.result("task.cancel", "{\"taskId\":\"" + taskId + "\"}").get("ok")
                .asBoolean()).isFalse();
        assertThat(client.result("task.get", "{\"taskId\":\"" + taskId + "\"}").get("status")
                .asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void rejectsUnknownProofsAndContracts() throws Exception {
        String envId = loadFixture();

        assertThat(client.errorCode("proof.getStatistics", proof("prf-deadbeef")))
                .isEqualTo(-32002);
        assertThat(client.errorCode("proof.runAuto", proof("prf-deadbeef"))).isEqualTo(-32002);
        assertThat(client.errorCode("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"no.such.Contract\"}"))
                .isEqualTo(-32602);
    }

    @Test
    void rejectsATimeoutThatCouldNeverElapse() throws Exception {
        String envId = loadFixture();
        String contractId = contractIdFor(envId, "add");
        String proofId = client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();

        assertThat(client.errorCode("proof.runAuto",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"timeoutMs\":0}")).isEqualTo(-32602);
    }

    @Test
    void closingAnEnvironmentTakesItsProofsWithIt() throws Exception {
        String envId = loadFixture();
        String contractId = contractIdFor(envId, "add");
        String proofId = client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();

        client.result("environment.close", env(envId));

        assertThat(client.errorCode("proof.getStatistics", proof(proofId))).isEqualTo(-32002);
    }

    private String contractIdFor(String envId, String member) throws Exception {
        return obligationFor(client.result("environment.listProofObligations", env(envId)), member)
                .get("contractId").asText();
    }

    private static JsonNode obligationFor(JsonNode obligations, String member) {
        for (JsonNode obligation : obligations) {
            if (obligation.get("targetMember").asText().startsWith(member + "(")) {
                return obligation;
            }
        }
        throw new AssertionError("No proof obligation for " + member + " in " + obligations);
    }

    private static String env(String envId) {
        return "{\"env\":{\"envId\":\"" + envId + "\"}}";
    }

    private static String proof(String proofId) {
        return "{\"proof\":{\"proofId\":\"" + proofId + "\"}}";
    }

    private String loadFixture() throws Exception {
        Path fixture = Path.of("src/test/resources/fixtures/adder").toAbsolutePath();
        JsonNode launched = client.result("environment.load",
            "{\"path\":\"" + fixture.toString().replace("\\", "\\\\") + "\"}");
        JsonNode finished = awaitTask(launched.get("taskId").asText());
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        return finished.get("result").get("envId").asText();
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
