/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.protocol.JsonRpcCodec;
import de.uka.ilkd.key.mcp.protocol.McpError;
import de.uka.ilkd.key.mcp.protocol.McpRequest;
import de.uka.ilkd.key.mcp.transport.StdioTransport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KeY MCP server implementation.
 *
 * <p>Implements the server side of the Model Context Protocol over stdio. The server
 * is single-session and exclusive: after a successful {@code initialize} it owns one
 * KeY environment and rejects a second {@code initialize}.</p>
 */
public class KeyMcpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyMcpServer.class);

    private static final String PROTOCOL_VERSION = "2025-11-25";

    private StdioTransport transport;
    private volatile boolean initialized;

    public KeyMcpServer(StdioTransport transport) {
        this.transport = transport;
    }

    /**
     * Creates a server attached to standard input and output.
     */
    public static KeyMcpServer stdio() {
        KeyMcpServer server = new KeyMcpServer(null);
        StdioTransport transport = new StdioTransport(System.in, System.out, server::handleMessage);
        server.transport = transport;
        return server;
    }

    void handleMessage(String message) {
        try {
            McpRequest request = JsonRpcCodec.parseRequest(message);
            String response = handleRequest(request);
            if (response != null) {
                transport.send(response);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to handle message: {}", message, e);
            String errorJson = JsonRpcCodec.encodeError(null, -32603, "Internal error: " + e.getMessage(), null);
            transport.send(errorJson);
        }
    }

    private String handleRequest(McpRequest request) {
        return switch (request.method()) {
        case "initialize" -> handleInitialize(request);
        case "notifications/initialized" -> {
            // No response for notifications.
            yield null;
        }
        case "ping" -> handlePing(request);
        case "tools/list" -> handleToolsList(request);
        case "resources/list" -> handleResourcesList(request);
        case "prompts/list" -> handlePromptsList(request);
        default -> JsonRpcCodec.encodeError(request.id(), -32601, "Method not found: " + request.method(), null);
        };
    }

    private String handleInitialize(McpRequest request) {
        if (initialized) {
            return JsonRpcCodec.encodeError(request.id(), -32600, "Session already initialized", null);
        }
        initialized = true;

        Map<String, Object> serverInfo = Json.object();
        serverInfo.put("name", "key-mcp");
        serverInfo.put("version", "3.1.0");

        Map<String, Object> capabilities = Json.object();
        capabilities.put("tools", Map.of("listChanged", true));
        capabilities.put("resources", Map.of("subscribe", false, "listChanged", true));
        capabilities.put("prompts", Map.of("listChanged", false));
        capabilities.put("logging", Map.of());

        Map<String, Object> result = Json.object();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("serverInfo", serverInfo);
        result.put("capabilities", capabilities);

        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handlePing(McpRequest request) {
        return JsonRpcCodec.encodeSuccess(request.id(), Map.of());
    }

    private String handleToolsList(McpRequest request) {
        Map<String, Object> result = Json.object();
        result.put("tools", List.of());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handleResourcesList(McpRequest request) {
        Map<String, Object> result = Json.object();
        result.put("resources", List.of());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handlePromptsList(McpRequest request) {
        Map<String, Object> result = Json.object();
        result.put("prompts", List.of());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    /**
     * Runs the server. Blocks until stdin closes or an unrecoverable IO error occurs.
     *
     * @throws IOException if reading from stdin fails
     */
    public void run() throws IOException {
        transport.run();
    }

    /**
     * Program entry point.
     */
    public static void main(String[] args) throws IOException {
        LOGGER.info("Starting KeY MCP server");
        KeyMcpServer server = stdio();
        server.run();
    }
}
