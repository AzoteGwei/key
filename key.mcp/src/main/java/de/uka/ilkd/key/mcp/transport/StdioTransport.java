/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.transport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP stdio transport: reads line-delimited JSON-RPC messages from stdin and
 * writes responses to stdout.
 *
 * <p>
 * Each message is dispatched to a single-threaded executor so that the read loop
 * never blocks on long-running tool handlers. The one exception is
 * {@code notifications/cancelled}, which is handled immediately in the read loop
 * so that it can interrupt an in-flight blocking request.
 * </p>
 */
public class StdioTransport implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(StdioTransport.class);

    private static final String CANCELLED_NOTIFICATION = "\"notifications/cancelled\"";

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Consumer<String> messageHandler;
    private final ExecutorService executor;
    private volatile boolean closed;

    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler) {
        this(in, out, messageHandler, Executors.newSingleThreadExecutor(
            r -> new Thread(r, "key-mcp-handler")));
    }

    /**
     * Test-only constructor that allows injecting the executor.
     */
    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler,
            ExecutorService executor) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        this.messageHandler = messageHandler;
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
            // Cancel notifications must preempt any queued work so they can interrupt
            // a currently blocking tool handler.
            final String currentLine = line;
            if (currentLine.contains(CANCELLED_NOTIFICATION)) {
                handle(currentLine);
            } else {
                executor.submit(() -> handle(currentLine));
            }
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
