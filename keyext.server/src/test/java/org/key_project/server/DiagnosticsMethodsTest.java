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
 * Telling an under-specified proof from a false one, over HTTP.
 *
 * <p>
 * This is the distinction the whole diagnostics surface exists for. Two proofs that both leave one
 * goal open are the same event as far as {@code proof.getStatistics} is concerned, and completely
 * different problems: one needs a loop invariant written, the other needs the specification or the
 * code fixed. An agent that cannot tell them apart will guess, and guess wrong half the time.
 */
class DiagnosticsMethodsTest {

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
    void pointsAtTheLoopThatHasNoInvariant() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        JsonNode perGoal = client.result("diagnostics.listStuckPoints", proof(proofId));

        assertThat(perGoal).hasSize(1);
        JsonNode goal = perGoal.get(0);
        assertThat(goal.get("truncated").asBoolean()).isFalse();
        assertThat(goal.get("stuckPoints")).isNotEmpty();

        assertThat(goal.get("stuckPoints")).anySatisfy(point -> {
            assertThat(point.get("reason").asText()).isEqualTo("NEEDS_SPEC");
            assertThat(point.get("ruleId").asText()).contains("InvariantRule");
            // The file and line of the loop itself. Anything less and an agent has to search for
            // the loop before it can write anything.
            assertThat(point.get("source").get("file").asText()).endsWith("Summer.java");
            assertThat(point.get("source").get("line").asInt()).isPositive();
            assertThat(point.get("positionHint").asText()).contains("Summer.java:");
        });

        // The per-goal method and the whole-proof method have to agree.
        int goalId = goal.get("goalId").asInt();
        JsonNode explained = client.result("diagnostics.explainGoal",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId + "}}");
        assertThat(explained.get("goalId").asInt()).isEqualTo(goalId);
        assertThat(explained.get("stuckPoints")).hasSize(goal.get("stuckPoints").size());
    }

    @Test
    void reportsNothingStuckWhenTheGoalIsSimplyFalse() throws Exception {
        String proofId = symbolicallyExecute("broken-max", "Max");
        awaitTask(client.result("proof.runAuto", proof(proofId)).get("taskId").asText());

        JsonNode perGoal = client.result("diagnostics.listStuckPoints", proof(proofId));

        assertThat(perGoal).hasSize(1);
        // No rule is waiting on anything here. That is a finding: the goal is not under-specified,
        // it is unprovable, and an agent should be looking at the specification rather than adding
        // to it.
        assertThat(perGoal.get(0).get("stuckPoints")).isEmpty();
        assertThat(perGoal.get(0).get("truncated").asBoolean()).isFalse();
        assertThat(client.result("proof.getStatistics", proof(proofId)).get("closed").asBoolean())
                .isFalse();
    }

    @Test
    void tellsAProverOutOfIdeasFromOneOutOfBudget() throws Exception {
        String proofId = symbolicallyExecute("broken-max", "Max");

        // Run to the strategy's own end. The fixture is unprovable, so goals remain, and no
        // built-in rule is waiting on anything.
        JsonNode finished = awaitTask(client.result("proof.runAuto", proof(proofId))
                .get("taskId").asText());
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("EXHAUSTED");

        JsonNode exhausted = client.result("diagnostics.listStuckPoints", proof(proofId));
        assertThat(exhausted.get(0).get("stuckPoints")).isEmpty();
        // Without this, an empty list means two opposite things and the caller cannot tell which:
        // a prover that gave everything it had, or one that never finished looking. The first
        // calls for a script or a solver; the second just calls for more budget.
        assertThat(exhausted.get(0).get("lastSearchOutcome").asText()).isEqualTo("EXHAUSTED");

        // Now the other half of the distinction, on a proof that was stopped rather than spent.
        String cutShort = symbolicallyExecute("no-invariant", "Summer");
        awaitTask(client.result("proof.runAuto",
            "{\"proof\":{\"proofId\":\"" + cutShort + "\"},\"timeoutMs\":1}").get("taskId")
                .asText());

        JsonNode interrupted = client.result("diagnostics.listStuckPoints", proof(cutShort));
        assertThat(interrupted.get(0).get("lastSearchOutcome").asText())
                .isEqualTo("BUDGET_ELAPSED");
    }

    @Test
    void saysNothingAboutASearchThatNeverRan() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        JsonNode perGoal = client.result("diagnostics.listStuckPoints", proof(proofId));

        // A script ran, but no automatic search did. Reporting an ending for a search that never
        // happened would be worse than reporting none.
        assertThat(perGoal.get(0).has("lastSearchOutcome")).isFalse();
        assertThat(perGoal.get(0).get("stuckPoints")).isNotEmpty();
    }

    @Test
    void saysSoWhenItStoppedLookingEarly() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        JsonNode shallow = client.result("diagnostics.listStuckPoints",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"maxDepth\":0}");

        assertThat(shallow.get(0).get("truncated").asBoolean()).isTrue();
        assertThat(client.errorCode("diagnostics.listStuckPoints",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"maxDepth\":-1}")).isEqualTo(-32602);
    }

    @Test
    void refusesToProbeAProofSomethingElseIsAboutToChange() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        // Occupy the worker with a long load, then queue a search behind it. The search is not
        // running yet, but it is coming, and an answer computed now could be stale before it
        // reached the client.
        client.result("environment.load", loadParams("adder"));
        client.result("proof.runAuto", proof(proofId));

        JsonNode response = client.call("diagnostics.listStuckPoints", proof(proofId));

        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32007);
        assertThat(response.get("error").get("message").asText()).contains("task-");
    }

    @Test
    void rejectsGoalsAndProofsItDoesNotKnow() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        assertThat(client.errorCode("diagnostics.explainGoal",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":99999}}")).isEqualTo(-32003);
        assertThat(client.errorCode("diagnostics.listStuckPoints",
            "{\"proof\":{\"proofId\":\"prf-deadbeef\"}}")).isEqualTo(-32002);
    }

    private String proof(String proofId) {
        return "{\"proof\":{\"proofId\":\"" + proofId + "\"}}";
    }

    private static String loadParams(String fixture) {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        return "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}";
    }

    /**
     * Loads a fixture, starts its proof and symbolically executes it.
     *
     * @param fixture the directory under {@code src/test/resources/fixtures}
     * @param className the class whose contract to prove
     * @return the identifier of the proof, left at the goal symbolic execution stopped on
     */
    private String symbolicallyExecute(String fixture, String className) throws Exception {
        JsonNode loaded = awaitTask(
            client.result("environment.load", loadParams(fixture)).get("taskId").asText());
        String envId = loaded.get("result").get("envId").asText();

        JsonNode obligations = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"targetClass\":\"" + className + "\"}");
        String contractId = obligations.get(0).get("contractId").asText();
        String proofId = client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();

        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();
        awaitTask(client.result("goal.applyScript",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
                + "},\"script\":\"macro \\\"symbex\\\";\"}").get("taskId").asText());
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
