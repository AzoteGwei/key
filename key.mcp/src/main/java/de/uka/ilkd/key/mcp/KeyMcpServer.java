/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import de.uka.ilkd.key.mcp.json.Json;
import de.uka.ilkd.key.mcp.json.JsonParseException;
import de.uka.ilkd.key.mcp.protocol.JsonRpcCodec;
import de.uka.ilkd.key.mcp.protocol.McpProtocol;
import de.uka.ilkd.key.mcp.protocol.McpRequest;
import de.uka.ilkd.key.mcp.session.McpSession;
import de.uka.ilkd.key.mcp.tools.ToolContext;
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
 * Implements the server side of the Model Context Protocol over stdio. The server is
 * dual-era (see the MCP versioning spec): legacy clients open a session with the
 * {@code initialize} handshake (revision {@value McpProtocol#VERSION_2025_11_25}), while
 * modern clients (revision {@value McpProtocol#VERSION_2026_07_28}) send stateless
 * requests that carry their protocol version and capabilities in {@code _meta} and probe
 * the server via {@code server/discover}. The era is selected per request from how the
 * client opens the conversation.
 * </p>
 *
 * <p>
 * The server is single-session and exclusive: after the session is established (via
 * {@code initialize} or the first modern request) it owns one KeY environment and rejects
 * a second {@code initialize}.
 * </p>
 */
public class KeyMcpServer {
    private static final Logger LOGGER = LoggerFactory.getLogger(KeyMcpServer.class);

    private static final String SERVER_NAME = "key-mcp";
    private static final String SERVER_VERSION = "3.1.0-dev";

    private static final String INSTRUCTIONS =
        "MCP server for the KeY program verifier. Load a Java/JML project with "
            + "key_project_load, list contracts with key_contracts_list, start proofs with "
            + "key_proof_create/key_proof_auto, poll long-running work with "
            + "key_operation_wait, and inspect or export proofs via the key_proof_* tools "
            + "and the proof:// resources.";

    StdioTransport transport;
    private volatile boolean initialized;

    private final McpServerConfig config;
    private McpSession session;
    private McpToolRegistry toolRegistry;
    private McpResourceHandler resourceHandler;
    private McpPromptHandler promptHandler;

    private final Set<Object> cancelledRequests = Collections.synchronizedSet(new HashSet<>());
    private final Map<Object, Thread> inFlightRequests = new ConcurrentHashMap<>();

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
        McpRequest request = null;
        boolean cancelled = false;
        try {
            request = JsonRpcCodec.parseRequest(message);
            Object id = request.id();
            if (id != null) {
                inFlightRequests.put(id, Thread.currentThread());
            }
            String response = null;
            try {
                response = handleRequest(request);
            } finally {
                if (id != null && cancelledRequests.contains(id)) {
                    cancelled = true;
                    response = null;
                }
                if (id != null) {
                    inFlightRequests.remove(id);
                    cancelledRequests.remove(id);
                }
            }
            if (response != null) {
                transport.send(response);
            }
        } catch (JsonParseException e) {
            String errorJson =
                JsonRpcCodec.encodeError(null, -32700, "Parse error: " + e.getMessage(), null);
            transport.send(errorJson);
        } catch (Exception e) {
            if (cancelled) {
                // The request was cancelled; do not send any further message for it.
                return;
            }
            LOGGER.error("Failed to handle message: {}", message, e);
            String errorJson =
                JsonRpcCodec.encodeError(null, -32603, "Internal error: " + e.getMessage(), null);
            transport.send(errorJson);
        }
    }

    private boolean isCancelled(Object id) {
        return cancelledRequests.contains(id);
    }

    private void cancelRequest(Object id) {
        cancelledRequests.add(id);
        Thread thread = inFlightRequests.get(id);
        if (thread != null) {
            thread.interrupt();
        }
    }

    McpSession getSession() {
        return session;
    }

    boolean isInFlight(Object id) {
        return inFlightRequests.containsKey(id);
    }

    /**
     * Removes a request id from the cancelled set. Tests use this to avoid leaking
     * cancelled ids between scenarios.
     */
    void clearCancelled(Object id) {
        cancelledRequests.remove(id);
    }

    private String handleRequest(McpRequest request) {
        String response = switch (request.method()) {
            case "initialize" -> handleInitialize(request);
            case "server/discover" -> handleDiscover(request);
            case "notifications/initialized" -> null;
            case "notifications/cancelled" -> {
                Object cancelledId = request.params().get("requestId");
                if (cancelledId != null) {
                    cancelRequest(cancelledId);
                }
                yield null;
            }
            default -> handleEraRouted(request);
        };
        // JSON-RPC notifications (no id) must not be answered, even if method handling
        // produced a response value.
        return request.id() == null ? null : response;
    }

    /**
     * Routes a request to the legacy (handshake-based) or modern (per-request {@code
     * _meta}) code path, as described by the MCP versioning spec for dual-era servers.
     */
    private String handleEraRouted(McpRequest request) {
        String version = request.protocolVersion();
        if (version != null) {
            if (!McpProtocol.SUPPORTED_VERSIONS.contains(version)) {
                return JsonRpcCodec.encodeError(request.id(),
                    McpProtocol.UNSUPPORTED_PROTOCOL_VERSION,
                    "Unsupported protocol version: " + version,
                    Map.of("supported", McpProtocol.SUPPORTED_VERSIONS, "requested", version));
            }
            if (!request.hasClientCapabilities()) {
                return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_PARAMS,
                    "Missing required _meta field: " + McpProtocol.META_CLIENT_CAPABILITIES,
                    null);
            }
            ensureSession();
            return dispatch(request, true);
        }
        if (initialized) {
            return dispatch(request, false);
        }
        return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_PARAMS,
            "Request must either be 'initialize' or carry the _meta field '"
                + McpProtocol.META_PROTOCOL_VERSION + "'",
            null);
    }

    private String dispatch(McpRequest request, boolean modern) {
        return switch (request.method()) {
            case "ping" -> modern ? methodNotFound(request) : handlePing(request);
            case "tools/list" -> handleToolsList(request, modern);
            case "tools/call" -> handleToolsCall(request, modern);
            case "resources/list" -> handleResourcesList(request, modern);
            case "resources/read" -> handleResourcesRead(request, modern);
            case "prompts/list" -> handlePromptsList(request, modern);
            case "prompts/get" -> handlePromptsGet(request, modern);
            default -> methodNotFound(request);
        };
    }

    private String methodNotFound(McpRequest request) {
        return JsonRpcCodec.encodeError(request.id(), McpProtocol.METHOD_NOT_FOUND,
            "Method not found: " + request.method(), null);
    }

    /**
     * Lazily creates the single KeY session and its handlers. Legacy clients trigger this
     * via {@code initialize}; modern clients trigger it with their first request.
     */
    private void ensureSession() {
        if (session == null) {
            session = new McpSession(UUID.randomUUID().toString());
            ToolContext toolContext = new ToolContext(config, session);
            toolRegistry = new McpToolRegistry(toolContext);
            resourceHandler = new McpResourceHandler(toolContext);
            promptHandler = new McpPromptHandler();
        }
    }

    private static Map<String, Object> serverInfo() {
        return Map.of("name", SERVER_NAME, "version", SERVER_VERSION);
    }

    /**
     * Handles the legacy {@code initialize} handshake (revision 2025-11-25 and earlier).
     */
    private String handleInitialize(McpRequest request) {
        if (initialized) {
            return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_REQUEST,
                "Session already initialized", null);
        }
        initialized = true;
        ensureSession();

        // Legacy version negotiation: 2025-11-25 is the only legacy revision this server
        // supports. If the client requested a different version (including a modern one
        // sent through initialize by mistake), the legacy rules require answering with a
        // version the server does support; the client may then decide to continue or
        // disconnect. Modern clients should use server/discover instead.
        String negotiated = McpProtocol.VERSION_2025_11_25;

        Map<String, Object> capabilities = Json.object();
        capabilities.put("tools", Map.of("listChanged", false));
        capabilities.put("resources", Map.of("subscribe", false, "listChanged", false));
        capabilities.put("prompts", Map.of("listChanged", false));

        Map<String, Object> result = Json.object();
        result.put("protocolVersion", negotiated);
        result.put("serverInfo", serverInfo());
        result.put("capabilities", capabilities);
        result.put("instructions", INSTRUCTIONS);

        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    /**
     * Handles {@code server/discover}, mandatory for modern servers and used by dual-era
     * clients as the stdio backward-compatibility probe. The result always follows the
     * modern shape, regardless of how the request was sent.
     */
    private String handleDiscover(McpRequest request) {
        Map<String, Object> capabilities = Json.object();
        capabilities.put("tools", Map.of());
        capabilities.put("resources", Map.of());
        capabilities.put("prompts", Map.of());

        Map<String, Object> result = Json.object();
        result.put("supportedVersions", McpProtocol.SUPPORTED_VERSIONS);
        result.put("capabilities", capabilities);
        result.put("instructions", INSTRUCTIONS);
        McpProtocol.cacheable(result, McpProtocol.TTL_STATIC_MS, "public");
        McpProtocol.modernResult(result, serverInfo());
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handlePing(McpRequest request) {
        return JsonRpcCodec.encodeSuccess(request.id(), Map.of());
    }

    private String handleToolsList(McpRequest request, boolean modern) {
        Map<String, Object> result = Json.object();
        result.put("tools", toolRegistry.listTools());
        if (modern) {
            McpProtocol.cacheable(result, McpProtocol.TTL_STATIC_MS, "public");
            McpProtocol.modernResult(result, serverInfo());
        }
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handleToolsCall(McpRequest request, boolean modern) {
        Map<String, Object> params = request.params();
        Object nameObj = params.get("name");
        if (!(nameObj instanceof String name)) {
            return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_PARAMS,
                "Missing or invalid 'name' parameter", null);
        }
        Object arguments = params.get("arguments");
        Map<String, Object> argumentsMap =
            (arguments instanceof Map) ? castMap(arguments) : Map.of();
        try {
            Map<String, Object> toolOutput = toolRegistry.execute(name, argumentsMap);
            Map<String, Object> result = Json.object();
            result.put("content",
                List.of(Map.of("type", "text", "text", Json.stringify(toolOutput))));
            result.put("structuredContent", toolOutput);
            if (modern) {
                McpProtocol.modernResult(result, serverInfo());
            }
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpSecurityException e) {
            return encodeHandlerError(request, McpSecurityException.CODE, e.getMessage(), null,
                modern);
        } catch (McpToolException e) {
            return encodeHandlerError(request, e.code(), e.getMessage(), e.data(), modern);
        } catch (Exception e) {
            LOGGER.error("Tool execution failed: {}", name, e);
            return JsonRpcCodec.encodeError(request.id(), -32603,
                "Tool execution failed: " + e.getMessage(), null);
        }
    }

    private String handleResourcesList(McpRequest request, boolean modern) {
        Map<String, Object> result = Json.object();
        result.put("resources", resourceHandler.listResources());
        if (modern) {
            // The resource list changes as proofs are created and disposed.
            McpProtocol.cacheable(result, McpProtocol.TTL_DYNAMIC_MS, "public");
            McpProtocol.modernResult(result, serverInfo());
        }
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handleResourcesRead(McpRequest request, boolean modern) {
        Object uriObj = request.params().get("uri");
        if (!(uriObj instanceof String uri) || uri.isEmpty()) {
            return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_PARAMS,
                "Missing or invalid 'uri' parameter", null);
        }
        try {
            Map<String, Object> contents = resourceHandler.readResource(uri);
            Map<String, Object> result = Json.object();
            result.put("contents", List.of(contents));
            if (modern) {
                // Proof state changes constantly; reads must not be cached.
                McpProtocol.cacheable(result, McpProtocol.TTL_DYNAMIC_MS, "public");
                McpProtocol.modernResult(result, serverInfo());
            }
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpToolException e) {
            return encodeHandlerError(request, e.code(), e.getMessage(), e.data(), modern);
        }
    }

    private String handlePromptsList(McpRequest request, boolean modern) {
        Map<String, Object> result = Json.object();
        result.put("prompts", promptHandler.listPrompts());
        if (modern) {
            McpProtocol.cacheable(result, McpProtocol.TTL_STATIC_MS, "public");
            McpProtocol.modernResult(result, serverInfo());
        }
        return JsonRpcCodec.encodeSuccess(request.id(), result);
    }

    private String handlePromptsGet(McpRequest request, boolean modern) {
        Object nameObj = request.params().get("name");
        if (!(nameObj instanceof String name)) {
            return JsonRpcCodec.encodeError(request.id(), McpProtocol.INVALID_PARAMS,
                "Missing or invalid 'name' parameter", null);
        }
        Object arguments = request.params().get("arguments");
        Map<String, Object> argumentsMap =
            (arguments instanceof Map) ? castMap(arguments) : Map.of();
        try {
            Map<String, Object> result = promptHandler.getPrompt(name, argumentsMap);
            if (modern) {
                McpProtocol.modernResult(result, serverInfo());
            }
            return JsonRpcCodec.encodeSuccess(request.id(), result);
        } catch (McpToolException e) {
            return encodeHandlerError(request, e.code(), e.getMessage(), e.data(), modern);
        }
    }

    /**
     * Encodes a handler error, translating legacy-range codes to the codes mandated by
     * the modern revision where required.
     */
    private String encodeHandlerError(McpRequest request, int code, String message,
            Object data, boolean modern) {
        return JsonRpcCodec.encodeError(request.id(),
            McpProtocol.wireErrorCode(code, modern), message, data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
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
