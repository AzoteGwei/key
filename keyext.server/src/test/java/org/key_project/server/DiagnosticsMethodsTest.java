/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

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
    void namesRulesAProofScriptCouldUseWhenTheProverHasGivenUp() throws Exception {
        String proofId = symbolicallyExecute("broken-max", "Max");
        JsonNode finished = awaitTask(client.result("proof.runAuto", proof(proofId))
                .get("taskId").asText());
        assertThat(finished.get("result").get("outcome").asText()).isEqualTo("EXHAUSTED");

        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();
        JsonNode answer = client.result("diagnostics.listApplicableRules", goal(proofId, goalId));

        // The complement of the stuck points, which are empty here. The prover ran out of ideas;
        // these are the ideas a person would still have.
        assertThat(client.result("diagnostics.listStuckPoints", proof(proofId)).get(0)
                .get("stuckPoints")).isEmpty();
        assertThat(answer.get("goalId").asInt()).isEqualTo(goalId);
        assertThat(answer.get("rules")).isNotEmpty();
        assertThat(answer.get("truncated").asBoolean()).isFalse();

        List<String> ruleIds = new ArrayList<>();
        for (JsonNode rule : answer.get("rules")) {
            assertThat(rule.get("kind").asText()).isIn("NO_FIND", "FIND", "REWRITE");
            ruleIds.add(rule.get("ruleId").asText());
        }
        // Names, not descriptions: these are what a proof script's `rule` command takes.
        assertThat(ruleIds).contains("cut");
    }

    @Test
    void theScriptLineItGivesActuallyApplies() throws Exception {
        String envId = loadFixture("broken-max");

        List<String> scripts = new ArrayList<>();
        String first = stuckProofIn(envId, "Max");
        int goalId = client.result("goal.list", proof(first)).get(0).get("goalId").asInt();
        for (JsonNode rule : client.result("diagnostics.listApplicableRules", goal(first, goalId))
                .get("rules")) {
            if (rule.has("script")) {
                scripts.add(rule.get("script").asText());
            }
        }
        assertThat(scripts).describedAs("some rule can be applied as it stands").isNotEmpty();
        // Roughly half of them match in more than one place, so if the occurrence number were
        // wrong this would be a coin flip rather than a test.
        assertThat(scripts).anyMatch(line -> line.contains("occ="));

        // Every one of them, not a sample. A script line that does not apply is worse than no
        // script line: it reads like an instruction and fails like a bug, and the caller has no
        // way to tell which of the two it is looking at. Each is tried on a proof of its own,
        // because applying one changes the goal the next was written for.
        for (String script : scripts) {
            String proofId = stuckProofIn(envId, "Max");
            int goal = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();
            JsonNode applied = awaitTask(client.result("goal.applyScript",
                "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goal
                    + "},\"script\":" + quote(script) + "}").get("taskId").asText());
            assertThat(applied.get("status").asText())
                    .describedAs("%s -> %s", script, applied).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void aRuleNeedingInputIsOfferedWithoutAScriptLine() throws Exception {
        String proofId = stuckProofIn(loadFixture("broken-max"), "Max");
        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();

        JsonNode rules = client.result("diagnostics.listApplicableRules", goal(proofId, goalId))
                .get("rules");

        JsonNode blocked = null;
        for (JsonNode rule : rules) {
            if (rule.get("needsAssumption").asBoolean()
                    || rule.get("needsInstantiation").asBoolean()) {
                blocked = rule;
                break;
            }
        }
        assertThat(blocked).describedAs("some rule needs input").isNotNull();
        // Offered, because it is genuinely applicable and a caller may know what to supply. But
        // without a line, because any line this could invent would not work.
        assertThat(blocked.has("script")).isFalse();

        JsonNode refused = awaitTask(client.result("goal.applyScript",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
                + "},\"script\":\"rule \\\"" + blocked.get("ruleId").asText()
                + "\\\";\"}").get("taskId").asText());
        assertThat(refused.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void aRuleThatMatchesInSeveralPlacesSaysHowManyAndHowToPick() throws Exception {
        String proofId = stuckProofIn(loadFixture("broken-max"), "Max");
        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();

        JsonNode hideLeft = null;
        for (JsonNode rule : client.result("diagnostics.listApplicableRules",
            goal(proofId, goalId)).get("rules")) {
            if ("hide_left".equals(rule.get("ruleId").asText())) {
                hideLeft = rule;
            }
        }
        assertThat(hideLeft).isNotNull();

        // hide_left matches every antecedent formula. Saying how many and handing over a line
        // that picks one is the difference between a rule being mentionable and being usable.
        assertThat(hideLeft.get("occurrences").asInt()).isGreaterThan(1);
        assertThat(hideLeft.get("script").asText()).contains("occ=");

        JsonNode ambiguous = awaitTask(client.result("goal.applyScript",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
                + "},\"script\":\"rule \\\"hide_left\\\";\"}").get("taskId").asText());
        assertThat(ambiguous.get("status").asText())
                .describedAs("naming it without an occurrence is refused").isEqualTo("FAILED");
    }

    @Test
    void enumeratingRulesLeavesTheProofAlone() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");
        JsonNode before = client.result("proof.getStatistics", proof(proofId));
        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();

        client.result("diagnostics.listApplicableRules", goal(proofId, goalId));

        // Enumeration builds rule applications, and now also puts the rule index back into
        // interactive mode, before throwing all of it away. If any of that left a mark, asking
        // what could be done would change what has been done.
        JsonNode after = client.result("proof.getStatistics", proof(proofId));
        assertThat(after.get("nodes").asInt()).isEqualTo(before.get("nodes").asInt());
        assertThat(after.get("totalRuleApps").asInt())
                .isEqualTo(before.get("totalRuleApps").asInt());
        assertThat(after.get("openGoals").asInt()).isEqualTo(before.get("openGoals").asInt());
        assertThat(after.get("closed").asBoolean()).isFalse();

        // And the proof can still be worked on afterwards, which the index mode change could
        // plausibly have broken.
        JsonNode ran = awaitTask(client.result("proof.runAuto", proof(proofId))
                .get("taskId").asText());
        assertThat(ran.get("status").asText()).isEqualTo("SUCCEEDED");
    }

    @Test
    void saysSoWhenItStoppedNamingRulesEarly() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");
        int goalId = client.result("goal.list", proof(proofId)).get(0).get("goalId").asInt();

        JsonNode capped = client.result("diagnostics.listApplicableRules",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
                + "},\"maxRules\":3}");

        assertThat(capped.get("rules")).hasSize(3);
        assertThat(capped.get("truncated").asBoolean()).isTrue();
        assertThat(client.errorCode("diagnostics.listApplicableRules",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
                + "},\"maxRules\":0}")).isEqualTo(-32602);
    }

    @Test
    void rejectsGoalsAndProofsItDoesNotKnow() throws Exception {
        String proofId = symbolicallyExecute("no-invariant", "Summer");

        assertThat(client.errorCode("diagnostics.explainGoal",
            "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":99999}}")).isEqualTo(-32003);
        assertThat(client.errorCode("diagnostics.listStuckPoints",
            "{\"proof\":{\"proofId\":\"prf-deadbeef\"}}")).isEqualTo(-32002);
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char each : text.toCharArray()) {
            if (each == '"' || each == '\\') {
                out.append('\\');
            }
            out.append(each);
        }
        return out.append('"').toString();
    }

    private String loadFixture(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        JsonNode loaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}").get("taskId")
                .asText());
        return loaded.get("result").get("envId").asText();
    }

    /** Starts a fresh proof in an already loaded environment and runs it to a standstill. */
    private String stuckProofIn(String envId, String className) throws Exception {
        String contractId = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"},\"targetClass\":\"" + className + "\"}")
                .get(0).get("contractId").asText();
        String proofId = client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();
        awaitTask(client.result("proof.runAuto", proof(proofId)).get("taskId").asText());
        return proofId;
    }

    private static String goal(String proofId, int goalId) {
        return "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId + "}}";
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
