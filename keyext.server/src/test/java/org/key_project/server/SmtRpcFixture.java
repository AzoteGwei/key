/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a proof to completion through the RPC surface using the {@code smt} script command.
 *
 * <p>
 * Shared by the degraded and the working case so the two differ in nothing but whether the solver
 * can be executed.
 */
final class SmtRpcFixture {

    /**
     * Symbolic execution followed by a solver call.
     *
     * <p>
     * The correct fixture needs both: {@code symbex} reduces the method to a first-order goal, and
     * only then is there something a solver can be handed.
     */
    static final String SCRIPT = "macro \\\"symbex\\\"; smt;";

    private static final Duration BUDGET = Duration.ofMinutes(5);

    private final RpcTestClient client;

    SmtRpcFixture(RpcTestClient client) {
        this.client = client;
    }

    /**
     * Runs the script against the provable fixture and returns the resulting statistics.
     *
     * @return the {@code statistics} member of the finished task
     */
    JsonNode proveWithSmt() throws Exception {
        String proofId = startProof();
        JsonNode finished = awaitTask(client.result("goal.applyScript",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + firstGoalId(proofId)
                + "},\"script\":\"" + SCRIPT + "\"}").get("taskId").asText());

        assertThat(finished.get("kind").asText()).isEqualTo("SCRIPT");
        return finished.get("result").get("statistics");
    }

    private String startProof() throws Exception {
        Path path = Path.of("src/test/resources/fixtures/max").toAbsolutePath();
        JsonNode loaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}").get("taskId")
                .asText());
        assertThat(loaded.get("status").asText()).isEqualTo("SUCCEEDED");
        String envId = loaded.get("result").get("envId").asText();
        String contractId = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"}}").get(0).get("contractId").asText();
        return client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();
    }

    private int firstGoalId(String proofId) throws Exception {
        return client.result("goal.list", "{\"proof\":{\"proofId\":\"" + proofId + "\"}}").get(0)
                .get("goalId").asInt();
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
