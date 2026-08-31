/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.exec.SerialExecutor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes JSON-RPC 2.0 requests to method handlers.
 *
 * <p>
 * Handlers declared {@link Concurrency#SERIAL} are run on the single KeY worker thread and the
 * caller waits; {@link Concurrency#INLINE} handlers run on the calling thread. Batch requests are
 * not supported and are rejected explicitly rather than half-handled.
 */
public final class JsonRpcDispatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(JsonRpcDispatcher.class);
    private static final String VERSION = "2.0";

    private final ObjectMapper mapper;
    private final SerialExecutor executor;
    private final Map<String, RpcMethod> methods = new HashMap<>();

    /**
     * Creates a dispatcher.
     *
     * @param mapper the JSON mapper used for parameters and results
     * @param executor the worker thread that owns KeY state
     */
    public JsonRpcDispatcher(ObjectMapper mapper, SerialExecutor executor) {
        this.mapper = mapper;
        this.executor = executor;
    }

    /**
     * Makes a method callable.
     *
     * @param method the method to register
     */
    public void register(RpcMethod method) {
        RpcMethod previous = methods.put(method.name(), method);
        if (previous != null) {
            throw new IllegalStateException("Duplicate method: " + method.name());
        }
    }

    /**
     * The names of every callable method.
     *
     * @return the registered method names
     */
    public java.util.Set<String> methodNames() {
        return java.util.Set.copyOf(methods.keySet());
    }

    /**
     * Handles one request document.
     *
     * @param body the raw request text
     * @return the response text, or {@code null} when the request was a notification and therefore
     *         has no response
     */
    public @Nullable String handle(String body) {
        JsonNode request;
        try {
            request = mapper.readTree(body);
        } catch (Exception e) {
            return write(errorResponse(null, RpcErrorCode.PARSE_ERROR, "Malformed JSON", null));
        }
        if (request == null || request.isMissingNode()) {
            return write(errorResponse(null, RpcErrorCode.PARSE_ERROR, "Empty request", null));
        }
        if (request.isArray()) {
            return write(errorResponse(null, RpcErrorCode.INVALID_REQUEST,
                "Batch requests are not supported; send one request per call", null));
        }
        return handleSingle(request);
    }

    private @Nullable String handleSingle(JsonNode request) {
        JsonNode id = request.get("id");
        boolean isNotification = id == null || id.isNull();

        JsonNode version = request.get("jsonrpc");
        if (version == null || !VERSION.equals(version.asText())) {
            return isNotification ? null
                    : write(errorResponse(id, RpcErrorCode.INVALID_REQUEST,
                        "Expected \"jsonrpc\": \"2.0\"", null));
        }
        JsonNode name = request.get("method");
        if (name == null || !name.isTextual()) {
            return isNotification ? null
                    : write(errorResponse(id, RpcErrorCode.INVALID_REQUEST,
                        "Request is missing a method name", null));
        }
        RpcMethod method = methods.get(name.asText());
        if (method == null) {
            return isNotification ? null
                    : write(errorResponse(id, RpcErrorCode.METHOD_NOT_FOUND,
                        "No such method: " + name.asText(), null));
        }

        try {
            Object result = invoke(method, new Params(mapper, request.get("params")));
            return isNotification ? null : write(successResponse(id, result));
        } catch (RpcException e) {
            LOGGER.debug("Method {} failed with {}", method.name(), e.errorCode(), e);
            return isNotification ? null
                    : write(errorResponse(id, e.errorCode(), e.getMessage(), e.data()));
        } catch (RuntimeException e) {
            LOGGER.error("Method {} failed unexpectedly", method.name(), e);
            return isNotification ? null
                    : write(errorResponse(id, RpcErrorCode.INTERNAL_ERROR,
                        "Internal error while handling " + method.name(),
                        RpcErrorData.of(String.valueOf(e.getMessage()))));
        }
    }

    private Object invoke(RpcMethod method, Params params) {
        if (method.concurrency() == Concurrency.INLINE) {
            return method.handler().handle(params);
        }
        try {
            return executor.submit(() -> method.handler().handle(params)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException(RpcErrorCode.INTERNAL_ERROR,
                "Interrupted while waiting for " + method.name(), null, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RpcException rpcException) {
                throw rpcException;
            }
            throw new RpcException(RpcErrorCode.INTERNAL_ERROR,
                "Internal error while handling " + method.name(), null, cause);
        }
    }

    private ObjectNode successResponse(JsonNode id, Object result) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", VERSION);
        response.set("id", id);
        response.set("result", mapper.valueToTree(result));
        return response;
    }

    private ObjectNode errorResponse(@Nullable JsonNode id, RpcErrorCode code, String message,
            @Nullable RpcErrorData data) {
        ObjectNode response = mapper.createObjectNode();
        response.put("jsonrpc", VERSION);
        if (id == null) {
            response.putNull("id");
        } else {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", code.code());
        error.put("message", message);
        if (data != null) {
            error.set("data", mapper.valueToTree(data));
        }
        return response;
    }

    private String write(ObjectNode response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            // Cannot happen for a tree we just built, but the protocol must not stall on it.
            LOGGER.error("Failed to serialise a response", e);
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,"
                + "\"message\":\"Failed to serialise the response\"}}";
        }
    }
}
