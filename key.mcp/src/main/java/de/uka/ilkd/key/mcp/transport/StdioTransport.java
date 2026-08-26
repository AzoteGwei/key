/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.transport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP stdio transport: reads line-delimited JSON-RPC messages from stdin and
 * writes responses to stdout.
 *
 * <p>
 * Each message is dispatched to a single-threaded executor so that the read loop
 * never blocks on long-running tool handlers. The exceptions are:
 * </p>
 * <ul>
 * <li>{@code notifications/cancelled}, which is handled immediately so it can
 * interrupt an in-flight blocking request.</li>
 * <li>Responses to server-to-client requests (e.g. elicitation), which are
 * handled immediately so the waiting tool handler can resume.</li>
 * </ul>
 */
public class StdioTransport implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(StdioTransport.class);

    private static final String CANCELLED_NOTIFICATION = "\"notifications/cancelled\"";

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Consumer<String> messageHandler;
    private final Supplier<Set<Object>> pendingResponseIds;
    private final ExecutorService executor;
    private volatile boolean closed;

    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler) {
        this(in, out, messageHandler, Set::of, Executors.newSingleThreadExecutor(
            r -> new Thread(r, "key-mcp-handler")));
    }

    /**
     * Production constructor that allows the transport to route responses to pending
     * server-to-client requests directly without queuing them behind tool handlers.
     */
    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler,
            Supplier<Set<Object>> pendingResponseIds) {
        this(in, out, messageHandler, pendingResponseIds, Executors.newSingleThreadExecutor(
            r -> new Thread(r, "key-mcp-handler")));
    }

    /**
     * Test-only constructor that allows injecting the executor and the set of ids
     * for which the transport should bypass the executor.
     */
    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler,
            Supplier<Set<Object>> pendingResponseIds, ExecutorService executor) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        this.messageHandler = messageHandler;
        this.pendingResponseIds = pendingResponseIds;
        this.executor = executor;
    }

    /**
     * Starts the read loop. Blocks until the transport is closed or stdin EOF.
     */
    public void run() throws IOException {
        String line;
        while (!closed && (line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            final String currentLine = line;
            if (isPendingResponse(currentLine) || currentLine.contains(CANCELLED_NOTIFICATION)) {
                handle(currentLine);
            } else {
                executor.submit(() -> handle(currentLine));
            }
        }
    }

    private boolean isPendingResponse(String line) {
        try {
            Set<Object> pending = pendingResponseIds.get();
            if (pending.isEmpty()) {
                return false;
            }
            Object id = de.uka.ilkd.key.mcp.protocol.JsonRpcCodec.parseResponseId(line);
            return id != null && pending.contains(id);
        } catch (Exception e) {
            return false;
        }
    }

    private void handle(String line) {
        try {
            messageHandler.accept(line);
        } catch (Exception e) {
            LOGGER.error("Unhandled error processing message", e);
        }
    }

    /**
     * Sends a single JSON-RPC message followed by a newline.
     *
     * @param message the serialized JSON
     */
    public void send(String message) {
        if (closed) {
            return;
        }
        writer.println(message);
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        writer.close();
    }
}
