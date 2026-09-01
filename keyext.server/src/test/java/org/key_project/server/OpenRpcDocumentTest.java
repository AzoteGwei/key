/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.key_project.server.dto.AutoModeOutcome;
import org.key_project.server.dto.ProofObligationKind;
import org.key_project.server.dto.SequentFormat;
import org.key_project.server.dto.StuckReason;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.dto.TaskStatus;
import org.key_project.server.rpc.RpcErrorCode;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeping the description and the server the same thing.
 *
 * <p>
 * A protocol document is worth having only while it is true, and hand-written ones stop being
 * true quietly: a method is added, the file is not, and a client built from the document finds
 * out at runtime. So the document is compared against the dispatcher of a real instance rather
 * than reviewed, and adding a method without describing it fails the build.
 */
class OpenRpcDocumentTest {

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
    void everyMethodTheServerHasIsDescribedAndNothingElseIs() throws Exception {
        Set<String> described = documentedMethods(client.result("server.describe", null));

        // Both directions. A missing entry leaves a client unable to discover a method that
        // exists; a spare one promises something that will answer -32601.
        assertThat(described).containsExactlyInAnyOrderElementsOf(instance.methodNames());
    }

    @Test
    void theDocumentIsVersionedWithTheProtocolItDescribes() throws Exception {
        JsonNode document = client.result("server.describe", null);

        assertThat(document.get("openrpc").asText()).isNotBlank();
        // One version, reported in two places, and clients compare them.
        assertThat(document.get("info").get("version").asText()).isEqualTo(ApiVersion.CURRENT);
        assertThat(client.result("server.version", null).get("apiVersion").asText())
                .isEqualTo(document.get("info").get("version").asText());
    }

    @Test
    void everyErrorCodeItDocumentsIsOneTheServerCanActuallyReturn() throws Exception {
        JsonNode document = client.result("server.describe", null);
        Set<Integer> known = new TreeSet<>();
        for (RpcErrorCode code : RpcErrorCode.values()) {
            known.add(code.code());
        }

        List<Integer> documented = new ArrayList<>();
        for (JsonNode method : document.get("methods")) {
            for (JsonNode error : method.path("errors")) {
                documented.add(error.get("code").asInt());
            }
        }

        assertThat(documented).isNotEmpty();
        // Documenting a code the server cannot produce teaches clients to handle a case that
        // will never arrive, which is how dead error handling gets written.
        assertThat(known).containsAll(documented);
    }

    @Test
    void everyDescribedMethodSaysWhatItIsFor() throws Exception {
        JsonNode document = client.result("server.describe", null);

        for (JsonNode method : document.get("methods")) {
            String name = method.get("name").asText();
            assertThat(method.path("summary").asText())
                    .describedAs("%s needs a summary", name).isNotBlank();
            assertThat(method.path("result").path("name").asText())
                    .describedAs("%s needs a named result", name).isNotBlank();
            assertThat(method.path("paramStructure").asText())
                    .describedAs("%s must take named parameters", name).isEqualTo("by-name");
        }
    }

    @Test
    void everyMethodIsFiledUnderATagThatIsActuallyDefined() throws Exception {
        JsonNode document = client.result("server.describe", null);
        JsonNode defined = document.get("components").get("tags");

        for (JsonNode method : document.get("methods")) {
            String name = method.get("name").asText();
            JsonNode tags = method.path("tags");
            assertThat(tags).describedAs("%s is filed under no tag", name).isNotEmpty();
            for (JsonNode tag : tags) {
                // Written as references so the prose describing a namespace lives in one place.
                // A reference to a tag that was never defined is a dangling pointer in a document
                // whose whole job is to be resolvable.
                String ref = tag.path("$ref").asText();
                assertThat(ref).describedAs("%s should reference a tag", name)
                        .startsWith("#/components/tags/");
                String target = ref.substring("#/components/tags/".length());
                assertThat(defined.has(target))
                        .describedAs("%s references tag %s, which is not defined", name, target)
                        .isTrue();
            }
        }
    }

    @Test
    void everyMethodShowsACallAndOnlyNamesParametersItHas() throws Exception {
        JsonNode document = client.result("server.describe", null);

        for (JsonNode method : document.get("methods")) {
            String name = method.get("name").asText();
            JsonNode examples = method.path("examples");
            assertThat(examples).describedAs("%s has no example", name).isNotEmpty();

            Set<String> declared = new TreeSet<>();
            for (JsonNode param : method.path("params")) {
                declared.add(param.get("name").asText());
            }
            for (JsonNode pairing : examples) {
                for (JsonNode example : pairing.path("params")) {
                    // The example is what a reader copies, so a name that drifted out of the
                    // signature is worse than no example: it is a call that cannot work, shown
                    // as one that does.
                    assertThat(declared).describedAs("%s shows a parameter it does not take", name)
                            .contains(example.get("name").asText());
                }
            }
        }
    }

    @Test
    void everythingAClientHasToSupplyOrReadIsExplained() throws Exception {
        JsonNode document = client.result("server.describe", null);

        for (JsonNode method : document.get("methods")) {
            String name = method.get("name").asText();
            for (JsonNode param : method.path("params")) {
                assertThat(param.path("description").asText())
                        .describedAs("%s(%s) is undescribed", name, param.get("name").asText())
                        .isNotBlank();
            }
            assertThat(method.path("result").path("description").asText())
                    .describedAs("the result of %s is undescribed", name).isNotBlank();
        }

        JsonNode schemas = document.get("components").get("schemas");
        schemas.fieldNames().forEachRemaining(schema -> {
            assertThat(schemas.get(schema).path("description").asText())
                    .describedAs("schema %s is undescribed", schema).isNotBlank();
            JsonNode properties = schemas.get(schema).path("properties");
            properties.fieldNames().forEachRemaining(property ->
            // A shape a caller reads off the wire is where a guess turns into a bug quietly:
            // openGoals and nodes are both plausible names for either number.
            assertThat(properties.get(property).path("description").asText())
                    .describedAs("%s.%s is undescribed", schema, property).isNotBlank());
        });
    }

    @Test
    void theDocumentWarnsThatAFinishedTaskIsNotAClosedProof() throws Exception {
        JsonNode document = client.result("server.describe", null);
        String text = document.toString();

        // The single most misreadable thing in this protocol. A client generated from a document
        // that left it out would be built on the wrong assumption from the first line.
        assertThat(document.get("info").get("description").asText())
                .contains("Proof.closed()");
        assertThat(text).contains("It does NOT mean a proof closed");
    }

    @Test
    void everyEnumItDocumentsHasTheValuesTheServerActuallySends() throws Exception {
        JsonNode schemas = client.result("server.describe", null).get("components").get("schemas");

        // The method-name check above says nothing about shapes, and an enum is exactly where a
        // document rots unnoticed: a value is added to the Java side, clients keep the old list,
        // and the mismatch only shows up as an unparseable response in somebody else's program.
        assertEnum(schemas, "TaskKind", TaskKind.class);
        assertEnum(schemas, "TaskStatus", TaskStatus.class);
        assertEnum(schemas, "ProofObligationKind", ProofObligationKind.class);
        assertEnum(schemas, "SequentFormat", SequentFormat.class);
        assertEnum(schemas, "StuckReason", StuckReason.class);
        assertEnum(schemas, "AutoModeOutcome", AutoModeOutcome.class);
    }

    private static void assertEnum(JsonNode schemas, String name, Class<? extends Enum<?>> type) {
        JsonNode declared = schemas.path(name).path("enum");
        assertThat(declared).describedAs("%s is not described", name).isNotEmpty();
        List<String> documented = new ArrayList<>();
        declared.forEach(value -> documented.add(value.asText()));

        List<String> actual = new ArrayList<>();
        for (Enum<?> constant : type.getEnumConstants()) {
            actual.add(constant.name());
        }
        assertThat(documented).describedAs("%s", name).containsExactlyInAnyOrderElementsOf(actual);
    }

    private static Set<String> documentedMethods(JsonNode document) {
        // Read only the top level of each entry. Params and results carry names of their own, and
        // a recursive search would sweep them in and make this assertion meaningless.
        Set<String> names = new TreeSet<>();
        for (JsonNode method : document.get("methods")) {
            names.add(method.get("name").asText());
        }
        return names;
    }
}
