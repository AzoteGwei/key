/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.protocol.JsonRpcCodec;
import de.uka.ilkd.key.mcp.protocol.McpRequest;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.mcp.transport.StdioTransport;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.ConsoleAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KeY MCP server implementation.
 *
 * <p>
 * Implements the server side of the Model Context Protocol over stdio. The server
 * is single-session and exclusive: after a successful {@code initialize} it owns one
 * KeY environment and rejects a second {@code initialize}.
 * </p>
 */
public class KeyMcpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyMcpServer.class);

    private static final String PROTOCOL_VERSION = "2025-11-25";

    StdioTransport transport;
    private volatile boolean initialized;

    private final McpServerConfig config;
    private McpSession session;
    private McpToolRegistry toolRegistry;
    private McpResourceHandler resourceHandler;
    private McpPromptHandler promptHandler;

    public KeyMcpServer(StdioTransport transport) {
        this(transport, McpServerConfig.fromEnvironment());
    }

    public KeyMcpServer(StdioTransport transport, McpServerConfig config) {
        this.transport = transport;
        this.config = config;
    }

    /**
     * Creates a server attached to standard input and output.
     */
    public static KeyMcpServer stdio() {
        return stdio(McpServerConfig.fromEnvironment());
    }

    public static KeyMcpServer stdio(McpServerConfig config) {
        KeyMcpServer server = new KeyMcpServer(null, config);
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
            String errorJson =
                JsonRpcCodec.encodeError(null, -32603, "Internal error: " + e.getMessage(), null);
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
            case "tools/call" -> handleToolsCall(request);
            case "resources/list" -> handleResourcesList(request);
            case "resources/read" -> handleResourcesRead(request);
            case "prompts/list" -> handlePromptsList(request);
            case "prompts/get" -> handlePromptsGet(request);
            default -> JsonRpcCodec.encodeError(request.id(), -32601,
                "Method not found: " + request.method(), null);
        };
    }

    private String handleInitialize(McpRequest request) {
        if (initialized) {
            return JsonRpcCodec.encodeError(request.id(), -32600, "Session already initialized",
                null);
        }
        initialized = true;

        String sessionId = UUID.randomUUID().toString();
        session = new McpSession(sessionId);
        toolRegistry = new McpToolRegistry(config, session);
        resourceHandler = new McpResourceHandler(session);
        promptHandler = new McpPromptHandler();

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
        result.put("tools", toolRegistry.listTools());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handleToolsCall(McpRequest request) {
        Map<String, Object> params = request.params();
        String name = (String) params.get("name");
        Object arguments = params.get("arguments");
        Map<String, Object> argumentsMap =
            (arguments instanceof Map) ? (Map<String, Object>) arguments : Map.of();
        try {
            Map<String, Object> toolOutput = toolRegistry.execute(name, argumentsMap);
            Map<String, Object> result = Json.object();
            result.put("content",
                List.of(Map.of("type", "text", "text", Json.stringify(toolOutput))));
            result.put("structuredContent", toolOutput);
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpSecurityException e) {
            return JsonRpcCodec.encodeError(request.id(), -32001, e.getMessage(), null);
        } catch (McpToolException e) {
            return JsonRpcCodec.encodeError(request.id(), e.code(), e.getMessage(), e.data());
        } catch (Exception e) {
            LOGGER.error("Tool execution failed: {}", name, e);
            return JsonRpcCodec.encodeError(request.id(), -32603,
                "Tool execution failed: " + e.getMessage(), null);
        }
    }

    private String handleResourcesList(McpRequest request) {
        Map<String, Object> result = Json.object();
        result.put("resources", resourceHandler.listResources());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handleResourcesRead(McpRequest request) {
        String uri = (String) request.params().get("uri");
        try {
            Map<String, Object> contents = resourceHandler.readResource(uri);
            Map<String, Object> result = Json.object();
            result.put("contents", List.of(contents));
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpToolException e) {
            return JsonRpcCodec.encodeError(request.id(), e.code(), e.getMessage(), e.data());
        }
    }

    private String handlePromptsList(McpRequest request) {
        Map<String, Object> result = Json.object();
        result.put("prompts", promptHandler.listPrompts());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handlePromptsGet(McpRequest request) {
        String name = (String) request.params().get("name");
        Object arguments = request.params().get("arguments");
        Map<String, Object> argumentsMap =
            (arguments instanceof Map) ? (Map<String, Object>) arguments : Map.of();
        try {
            Map<String, Object> result = promptHandler.getPrompt(name, argumentsMap);
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpToolException e) {
            return JsonRpcCodec.encodeError(request.id(), e.code(), e.getMessage(), e.data());
        }
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
        configureLogging();
        LOGGER.info("Starting KeY MCP server");
        KeyMcpServer server = stdio();
        server.run();
    }

    /**
     * Redirects all logging to stderr and applies {@code KEY_MCP_LOG_LEVEL}.
     *
     * <p>
     * The MCP stdio transport requires stdout to carry only JSON-RPC messages, so logback's
     * default console appender (which targets stdout) must be retargeted to stderr.
     * </p>
     */
    static void configureLogging() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            ch.qos.logback.classic.Logger root =
                context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.detachAndStopAllAppenders();

            PatternLayoutEncoder encoder = new PatternLayoutEncoder();
            encoder.setContext(context);
            encoder.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} -- %msg%n");
            encoder.start();

            ConsoleAppender<ILoggingEvent> stderr = new ConsoleAppender<>();
            stderr.setContext(context);
            stderr.setTarget("System.err");
            stderr.setEncoder(encoder);
            stderr.start();
            root.addAppender(stderr);

            String level = System.getenv("KEY_MCP_LOG_LEVEL");
            if (level != null && !level.isBlank()) {
                root.setLevel(Level.toLevel(level, Level.INFO));
            }
        }
    }
}
