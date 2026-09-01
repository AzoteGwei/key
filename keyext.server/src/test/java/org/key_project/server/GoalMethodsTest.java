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
 * Looking at a proof and pushing it along, over HTTP.
 *
 * <p>
 * This is the part an agent actually works in: the automatic search stops somewhere, and the
 * question becomes what it stopped on and what to try next. So the assertions are about whether
 * the answers are usable — a sequent an agent can read, a goal identifier that stops working once
 * the goal is gone rather than silently naming something else, an error that says where in a
 * script the trouble was.
 */
class GoalMethodsTest {

    private static final Duration BUDGET = Duration.ofMinutes(5);

    private KeyServerInstance instance;
    private RpcTestClient client;
    private String proofId;

    @BeforeEach
    void startServerAndProof() throws Exception {
        instance = TestServer.start();
        client = new RpcTestClient(instance.port());
        proofId = startProofFor("broken-max");
    }

    @AfterEach
    void stopServer() {
        if (instance != null) {
            instance.close();
        }
    }

    @Test
    void listsTheOpenGoalsOfAFreshProof() throws Exception {
        JsonNode goals = client.result("goal.list", proof(proofId));

        assertThat(goals).hasSize(1);
        JsonNode only = goals.get(0);
        assertThat(only.get("isOpen").asBoolean()).isTrue();
        assertThat(only.get("isLinked").asBoolean()).isFalse();
        assertThat(only.get("goal").get("proofId").asText()).isEqualTo(proofId);
        assertThat(only.get("goal").get("goalId").asInt()).isEqualTo(only.get("goalId").asInt());
        assertThat(only.get("nodeId").asInt()).isEqualTo(only.get("goalId").asInt());
    }

    @Test
    void rendersASequentAnAgentCanRead() throws Exception {
        int goalId = firstGoalId();

        JsonNode sequent = client.result("goal.getSequent", goal(goalId, null));

        assertThat(sequent.get("format").asText()).isEqualTo("TEXT");
        assertThat(sequent.get("succedent")).isNotEmpty();
        // The whole point of the method: what is in there is the proof obligation, not a summary
        // of it.
        assertThat(sequent.get("succedent").get(0).asText()).contains("Max.max");
    }

    @Test
    void everyDeclaredSequentFormatIsAnsweredInThatFormat() throws Exception {
        int goalId = firstGoalId();

        for (String format : new String[] { "TEXT", "UNICODE", "STRUCTURED" }) {
            JsonNode sequent = client.result("goal.getSequent", goal(goalId, format));
            // The label a client gets back has to be the one it asked for. Answering in a
            // different format under the requested name would leave it building on a promise
            // that was never kept.
            assertThat(sequent.get("format").asText()).isEqualTo(format);
            assertThat(sequent.get("succedent")).describedAs("%s", format).isNotEmpty();
        }
        assertThat(client.errorCode("goal.getSequent", goal(goalId, "NO_SUCH_FORMAT")))
                .isEqualTo(-32602);
    }

    @Test
    void unicodeUsesLogicalSymbolsWhereTheTextFormatSpellsThemOut() throws Exception {
        int goalId = firstGoalId();

        String plain = client.result("goal.getSequent", goal(goalId, "TEXT")).toString();
        String unicode = client.result("goal.getSequent", goal(goalId, "UNICODE")).toString();

        assertThat(unicode).isNotEqualTo(plain);
    }

    @Test
    void structuredSeparatesTheStateTheProgramAndWhatMustHold() throws Exception {
        int goalId = firstGoalId();

        JsonNode sequent = client.result("goal.getSequent", goal(goalId, "STRUCTURED"));
        JsonNode formulas = sequent.get("formulas");

        assertThat(formulas).isNotEmpty();
        assertThat(formulas).hasSize(
            sequent.get("antecedent").size() + sequent.get("succedent").size());

        JsonNode obligation = null;
        for (JsonNode formula : formulas) {
            assertThat(formula.get("side").asText()).isIn("ANTECEDENT", "SUCCEDENT");
            assertThat(formula.get("claim").asText()).isNotBlank();
            if (formula.has("program")) {
                obligation = formula;
            }
        }

        // The point of the format. A proof obligation in flight is one enormous formula whose
        // interesting part is the last thirty characters of several hundred; separated, each
        // piece answers a different question.
        assertThat(obligation).describedAs("a goal mid-execution carries a program").isNotNull();
        assertThat(obligation.get("side").asText()).isEqualTo("SUCCEDENT");
        assertThat(obligation.get("program").asText()).contains("Max.max");
        assertThat(obligation.get("state").asText()).contains(":=");
        // What remains once the state and the program are set aside is what has to be shown, and
        // it is far shorter than the whole.
        assertThat(obligation.get("claim").asText()).contains("…");
        assertThat(obligation.get("claim").asText().length())
                .isLessThan(obligation.get("text").asText().length());
    }

    @Test
    void theFormulaTextsAgreeWithTheStructuredBreakdown() throws Exception {
        int goalId = firstGoalId();

        JsonNode sequent = client.result("goal.getSequent", goal(goalId, "STRUCTURED"));

        // A client reading `succedent[0]` and a client reading `formulas[i].text` for the
        // succedent's first formula must be reading the same thing.
        for (JsonNode formula : sequent.get("formulas")) {
            String side = formula.get("side").asText().equals("ANTECEDENT") ? "antecedent"
                    : "succedent";
            assertThat(sequent.get(side).get(formula.get("index").asInt()).asText())
                    .isEqualTo(formula.get("text").asText());
        }
    }

    @Test
    void listsTheMacrosItCanActuallyRun() throws Exception {
        JsonNode macros = client.result("goal.listAvailableMacros", proof(proofId));

        assertThat(macros).isNotEmpty();
        assertThat(macros).anySatisfy(
            macro -> assertThat(macro.get("macroId").asText()).isEqualTo("symbex"));
        assertThat(macros).allSatisfy(macro -> {
            assertThat(macro.get("macroId").asText()).isNotBlank();
            assertThat(macro.get("name").asText()).isNotBlank();
        });
    }

    @Test
    void aScriptMovesTheProofAlong() throws Exception {
        int before = firstGoalId();

        JsonNode finished = awaitTask(client.result("goal.applyScript",
            script(before, "macro \\\"symbex\\\";")).get("taskId").asText());

        assertThat(finished.get("kind").asText()).isEqualTo("SCRIPT");
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        // Symbolic execution ran: the proof has more nodes and the goal that was open is not the
        // one that is open now.
        assertThat(finished.get("result").get("statistics").get("symbExApps").asInt())
                .isPositive();
        assertThat(firstGoalId()).isNotEqualTo(before);

        // And the fixture is still the broken one, so nothing was proved.
        assertThat(finished.get("result").get("statistics").get("closed").asBoolean()).isFalse();
    }

    @Test
    void aMacroMovesTheProofAlong() throws Exception {
        int goalId = firstGoalId();

        JsonNode finished = awaitTask(client.result("goal.applyMacro",
            "{\"proof\":{\"proofId\":\"" + proofId + "\"},\"goal\":{\"proofId\":\"" + proofId
                + "\",\"goalId\":" + goalId + "},\"macroId\":\"symbex\"}").get("taskId").asText());

        assertThat(finished.get("kind").asText()).isEqualTo("MACRO");
        assertThat(finished.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(finished.get("result").get("statistics").get("symbExApps").asInt())
                .isPositive();
        assertThat(finished.get("result").get("statistics").get("closed").asBoolean()).isFalse();
    }

    @Test
    void aGoalIdentifierStopsWorkingOnceItsGoalIsGone() throws Exception {
        int before = firstGoalId();
        awaitTask(client.result("goal.applyScript", script(before, "macro \\\"symbex\\\";"))
                .get("taskId").asText());

        // Node serial numbers are not reused, so a stale identifier can only ever be wrong. It
        // has to fail rather than quietly resolve to whatever is open now.
        assertThat(client.errorCode("goal.getSequent", goal(before, null))).isEqualTo(-32003);
    }

    @Test
    void reportsWhereAScriptWentWrong() throws Exception {
        int goalId = firstGoalId();

        JsonNode response = client.call("goal.applyScript", script(goalId, "macro 'symbex';"));

        assertThat(response.get("error").get("code").asInt()).isEqualTo(-32005);
        // An agent rewriting the script needs the position, not prose it has to parse.
        assertThat(response.get("error").get("data").get("positions")).isNotEmpty();
        assertThat(response.get("error").get("data").get("positions").get(0).get("line").asInt())
                .isPositive();
        // Nothing was queued: the script never made it past parsing.
        assertThat(client.result("task.list", null)).hasSize(1);
    }

    @Test
    void rejectsMacrosAndGoalsItDoesNotKnow() throws Exception {
        assertThat(client.errorCode("goal.applyMacro", "{\"proof\":{\"proofId\":\"" + proofId
            + "\"},\"macroId\":\"no-such-macro\"}")).isEqualTo(-32602);
        assertThat(client.errorCode("goal.getSequent", goal(9999, null))).isEqualTo(-32003);
        assertThat(client.errorCode("goal.list", "{\"proof\":{\"proofId\":\"prf-deadbeef\"}}"))
                .isEqualTo(-32002);
    }

    private int firstGoalId() throws Exception {
        JsonNode goals = client.result("goal.list", proof(proofId));
        assertThat(goals).isNotEmpty();
        return goals.get(0).get("goalId").asInt();
    }

    private String proof(String id) {
        return "{\"proof\":{\"proofId\":\"" + id + "\"}}";
    }

    private String goal(int goalId, String format) {
        String suffix = format == null ? "" : ",\"format\":\"" + format + "\"";
        return "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId + "}" + suffix
            + "}";
    }

    private String script(int goalId, String source) {
        return "{\"goal\":{\"proofId\":\"" + proofId + "\",\"goalId\":" + goalId
            + "},\"script\":\"" + source + "\"}";
    }

    private String startProofFor(String fixture) throws Exception {
        Path path = Path.of("src/test/resources/fixtures", fixture).toAbsolutePath();
        JsonNode loaded = awaitTask(client.result("environment.load",
            "{\"path\":\"" + path.toString().replace("\\", "\\\\") + "\"}").get("taskId")
                .asText());
        String envId = loaded.get("result").get("envId").asText();
        String contractId = client.result("environment.listProofObligations",
            "{\"env\":{\"envId\":\"" + envId + "\"}}").get(0).get("contractId").asText();
        return client.result("proof.start", "{\"env\":{\"envId\":\"" + envId
            + "\"},\"contractId\":\"" + contractId + "\"}").get("proofId").asText();
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
