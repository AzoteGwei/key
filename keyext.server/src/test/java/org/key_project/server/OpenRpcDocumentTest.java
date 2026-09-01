/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

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
    void theDocumentWarnsThatAFinishedTaskIsNotAClosedProof() throws Exception {
        JsonNode document = client.result("server.describe", null);
        String text = document.toString();

        // The single most misreadable thing in this protocol. A client generated from a document
        // that left it out would be built on the wrong assumption from the first line.
        assertThat(document.get("info").get("description").asText())
                .contains("Proof.closed()");
        assertThat(text).contains("It does NOT mean a proof closed");
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
