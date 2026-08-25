/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp.transport;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MCP stdio transport: reads line-delimited JSON-RPC messages from stdin and
 * writes responses to stdout.
 */
public class StdioTransport implements Closeable {
    private static final Logger LOGGER = LoggerFactory.getLogger(StdioTransport.class);

    private final BufferedReader reader;
    private final PrintWriter writer;
    private final Consumer<String> messageHandler;
    private volatile boolean closed;

    public StdioTransport(InputStream in, OutputStream out, Consumer<String> messageHandler) {
        this.reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), true);
        this.messageHandler = messageHandler;
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
            try {
                messageHandler.accept(line);
            } catch (Exception e) {
                LOGGER.error("Unhandled error processing message", e);
            }
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
        writer.close();
    }
}
