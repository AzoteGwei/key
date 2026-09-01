/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Holding the protocol document to the OpenRPC specification itself.
 *
 * <p>
 * {@link OpenRpcDocumentTest} checks that the document says true things about this server. It
 * cannot check that the document is a well-formed OpenRPC document, and "it conforms to the
 * standard" is exactly the kind of claim that is easy to assert and easy to be wrong about: a
 * misplaced example, a tag that is a bare string, a content descriptor carrying a field the
 * specification does not allow. So the claim is delegated to the specification's own meta-schema,
 * which knows the rules in full, including the ones nobody here thought to check.
 *
 * <p>
 * Both schemas are checked in under {@code src/test/resources/openrpc} and the two remote
 * references between them are mapped onto those copies, so this test never reaches the network and
 * never changes meaning because something upstream was edited.
 */
class OpenRpcMetaSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The document's own dialect URI, which is Draft 07 published under a different name. */
    private static final String JSON_SCHEMA_TOOLS = "https://meta.json-schema.tools";

    @Test
    void theProtocolDocumentIsAValidOpenRpcDocument() throws Exception {
        JsonNode document = read("/openrpc.json");
        JsonNode metaSchema = read("/openrpc/meta-schema.json");
        String jsonSchemaTools = MAPPER.writeValueAsString(
            dialectStripped(read("/openrpc/json-schema-tools.json")));

        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7,
            builder -> builder.schemaLoaders(loaders -> loaders.schemas(Map.of(
                JSON_SCHEMA_TOOLS, jsonSchemaTools,
                JSON_SCHEMA_TOOLS + "/", jsonSchemaTools))));
        // format stays an annotation, which is what Draft 07 says it is unless a validator opts
        // in. It has to be: the Server Object's url "supports Server Variables and MAY be
        // relative", and neither a "{port}" template nor a relative reference is an RFC 3986 URI,
        // so asserting the meta-schema's "format": "uri" would reject documents the specification
        // explicitly provides for. Every other keyword is asserted as usual.
        SchemaValidatorsConfig config =
            SchemaValidatorsConfig.builder().formatAssertionsEnabled(false).build();
        JsonSchema schema = factory.getSchema(dialectStripped(metaSchema), config);

        Set<ValidationMessage> messages = schema.validate(document);

        // Every message, not the first: a document with six problems should take one run to fix,
        // and the messages are the only place the reason is stated in the specification's terms.
        assertThat(messages)
                .describedAs("openrpc.json does not conform to the OpenRPC meta-schema:%n%s",
                    report(messages))
                .isEmpty();
    }

    /**
     * Drops {@code $schema} so the factory uses the dialect it was given.
     *
     * <p>
     * Both vendored schemas declare {@code https://meta.json-schema.tools/}, a republication of
     * Draft 07 under its own URI, and the OpenRPC meta-schema references the other one, so both
     * have to be stripped. A validator that has never heard of that URI cannot be expected to
     * recognise it, so the dialect is stated once, in the factory, instead.
     */
    private static JsonNode dialectStripped(JsonNode schema) {
        ObjectNode copy = schema.deepCopy();
        copy.remove("$schema");
        return copy;
    }

    private static String report(Set<ValidationMessage> messages) {
        List<String> lines = new ArrayList<>();
        for (ValidationMessage message : messages) {
            lines.add("  " + message.getInstanceLocation() + ": " + message.getMessage());
        }
        return lines.stream().sorted().collect(Collectors.joining(System.lineSeparator()));
    }

    private static JsonNode read(String resource) throws Exception {
        try (InputStream in = open(resource)) {
            return MAPPER.readTree(in);
        }
    }

    private static InputStream open(String resource) {
        InputStream in = OpenRpcMetaSchemaTest.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("Missing test resource " + resource);
        }
        return in;
    }
}
