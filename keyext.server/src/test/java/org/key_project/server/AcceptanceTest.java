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
 * The tests this project exists to pass.
 *
 * <p>
 * Every other test here asks whether a feature works. These ask the one question that matters:
 * can this server ever report a proof as verified when it is not? They drive the full public
 * pipeline over HTTP — load, list, start, run, read back — against a matched pair of fixtures
 * that differ in one line, and they are as much about the failing half as the passing one. A
 * suite that only ever exercises a provable input cannot tell a working prover from a stub that
 * returns {@code closed: true}.
 *
 * <p>
 * That is why the negative cases come first here, and why they assert on the outcome of a real
 * run rather than on an error the server raised itself.
 */
class AcceptanceTest {

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

    /**
     * A method whose implementation contradicts its specification must not come out verified.
     *
     * <p>
     * The search is given no budget at all to fail against: it runs to its own end and is still
     * expected to leave the proof open. So this cannot pass by accident of a timeout.
     */
    @Test
    void aMethodThatViolatesItsSpecificationIsNotProved() throws Exception {
        String proofId = startProofFor("broken-max");

        JsonNode finished = awaitTask(client.result("proof.runAuto", proof(proofId))
                .get("taskId").asText());

        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("EXHAUSTED");

        // The task did its work and the work found nothing. Those are two separate facts and the
        // protocol has to keep saying both.
        JsonNode statistics = finished.get("result").get("statistics");
        assertThat(statistics.get("closed").asBoolean()).isFalse();
        assertThat(statistics.get("openGoals").asInt()).isPositive();
        assertThat(statistics.get("totalRuleApps").asInt())
                .describedAs("the prover must actually have tried").isPositive();

        JsonNode readBack = client.result("proof.getStatistics", proof(proofId));
        assertThat(readBack.get("closed").asBoolean()).isFalse();
        assertThat(readBack.get("openGoals").asInt()).isPositive();
    }

    /**
     * A search cut short by its time budget must report that, not silence.
     *
     * <p>
     * The fixture used here is the provable one, so a run allowed to finish would report
     * {@code closed: true}. Under a budget it must not.
     */
    @Test
    void aSearchStoppedByItsTimeBudgetDoesNotReportSuccess() throws Exception {
        String proofId = startProofFor("max");

        JsonNode finished = awaitTask(client.result("proof.runAuto",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"timeoutMs\":1}").get("taskId")
                .asText());

        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("BUDGET_ELAPSED");
        assertThat(finished.get("result").get("statistics").get("closed").asBoolean()).isFalse();
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("closed").asBoolean())
                .isFalse();
    }

    /**
     * The provable half of the pair, proved.
     *
     * <p>
     * Its job is to keep the negative tests meaningful: without it they would also pass if the
     * server had simply stopped being able to prove anything at all.
     */
    @Test
    void aMethodThatMeetsItsSpecificationIsProved() throws Exception {
        String proofId = startProofFor("max");

        JsonNode finished = awaitTask(client.result("proof.runAuto", proof(proofId))
                .get("taskId").asText());

        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("EXHAUSTED");
        assertThat(finished.get("result").get("statistics").get("closed").asBoolean()).isTrue();
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("openGoals").asInt())
                .isZero();
    }

    /**
     * Loads a fixture, finds its single obligation and starts a proof for it.
     *
     * @param fixture the directory under {@code src/test/resources/fixtures}
     * @return the identifier of the started proof
     */
    private String startProofFor(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        JsonNode loaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}").get("taskId")
                .asText());
        assertThat(loaded.get("status").asText()).isEqualTo("SUCCEEDED");
        String envId = loaded.get("result").get("envId").asText();

        JsonNode obligations = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"}}");
        assertThat(obligations).describedAs("fixture %s declares exactly one obligation", fixture)
                .hasSize(1);
        String contractId = obligations.get(0).get("contractId").asText();

        return client.result("proof.start",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"contractId\":\"" + contractId + "\"}")
                .get("proofId").asText();
    }

    private static String proof(String proofId) {
        return "{\"proof\":{\"proofId\":\"" + proofId + "\"}}";
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
