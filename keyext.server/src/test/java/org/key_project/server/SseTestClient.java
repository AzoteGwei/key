/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads the server's event stream on a thread of its own, so tests can wait on what arrives. */
final class SseTestClient implements AutoCloseable {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BlockingQueue<JsonNode> received = new LinkedBlockingQueue<>();
    private final List<String> comments = new ArrayList<>();
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Thread reader;

    private volatile boolean open = true;

    SseTestClient(int port) throws Exception {
        HttpResponse<InputStream> response = http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/rpc"))
                    .header("Accept", "text/event-stream").GET().build(),
            HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new AssertionError("Event stream refused with " + response.statusCode());
        }
        reader = new Thread(() -> read(response.body()), "sse-test-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void read(InputStream body) {
        try (BufferedReader lines =
            new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (open && (line = lines.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    received.add(mapper.readTree(line.substring("data: ".length())));
                } else if (line.startsWith(": ")) {
                    synchronized (comments) {
                        comments.add(line.substring(2));
                    }
                }
            }
        } catch (Exception e) {
            // The stream ends when the server shuts down; that is how these tests finish.
        }
    }

    /**
     * Waits for the next notification with the given method name.
     *
     * @param method the notification to wait for
     * @param budget how long to wait
     * @return the {@code params} member of that notification
     */
    JsonNode await(String method, Duration budget) throws InterruptedException {
        long deadline = System.nanoTime() + budget.toNanos();
        while (System.nanoTime() < deadline) {
            JsonNode next = received.poll(100, TimeUnit.MILLISECONDS);
            if (next == null) {
                continue;
            }
            // Every frame has to be a well-formed JSON-RPC notification: version, method, and no
            // id, because nothing is expected to answer it.
            if (!"2.0".equals(next.path("jsonrpc").asText()) || next.has("id")) {
                throw new AssertionError("Not a JSON-RPC notification: " + next);
            }
            if (method.equals(next.path("method").asText())) {
                return next.get("params");
            }
        }
        throw new AssertionError("No " + method + " notification within " + budget);
    }

    /**
     * How many notifications with the given method have arrived and not yet been consumed.
     *
     * @param method the notification to count
     * @return the number seen
     */
    int countPending(String method) {
        return (int) received.stream()
                .filter(node -> method.equals(node.path("method").asText())).count();
    }

    /**
     * The comment lines the server sent, which carry no data but keep the connection alive.
     *
     * @return a snapshot of the comments seen so far
     */
    List<String> comments() {
        synchronized (comments) {
            return List.copyOf(comments);
        }
    }

    @Override
    public void close() {
        open = false;
        reader.interrupt();
    }
}
