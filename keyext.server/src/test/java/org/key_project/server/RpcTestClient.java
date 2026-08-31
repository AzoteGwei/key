/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A small JSON-RPC client used by the tests to talk to a running instance. */
final class RpcTestClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final URI endpoint;

    RpcTestClient(int port) {
        this.endpoint = URI.create("http://127.0.0.1:" + port + "/rpc");
    }

    /**
     * Sends a request and returns the parsed response document.
     *
     * @param method the method name
     * @param paramsJson the {@code params} member as JSON text, or {@code null} for none
     * @return the full response document
     */
    JsonNode call(String method, String paramsJson) throws IOException, InterruptedException {
        String params = paramsJson == null ? "" : ",\"params\":" + paramsJson;
        return send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\"" + params + "}");
    }

    /**
     * Sends a raw request document.
     *
     * @param body the exact text to post
     * @return the parsed response, or a null node when the server answered with no content
     */
    JsonNode send(String body) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(
            HttpRequest.newBuilder(endpoint).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.body().isEmpty()) {
            return mapper.nullNode();
        }
        return mapper.readTree(response.body());
    }

    /**
     * Sends a request and asserts nothing, returning only the {@code result} member.
     *
     * @param method the method name
     * @param paramsJson the {@code params} member as JSON text, or {@code null} for none
     * @return the {@code result} member
     */
    JsonNode result(String method, String paramsJson) throws IOException, InterruptedException {
        JsonNode response = call(method, paramsJson);
        if (response.has("error")) {
            throw new AssertionError(method + " failed: " + response.get("error"));
        }
        return response.get("result");
    }

    /**
     * Returns the status code of the {@code error} member.
     *
     * @param method the method name
     * @param paramsJson the {@code params} member as JSON text, or {@code null} for none
     * @return the numeric error code
     */
    int errorCode(String method, String paramsJson) throws IOException, InterruptedException {
        JsonNode response = call(method, paramsJson);
        if (!response.has("error")) {
            throw new AssertionError(method + " unexpectedly succeeded: " + response);
        }
        return response.get("error").get("code").asInt();
    }
}
