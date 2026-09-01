/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import de.uka.ilkd.key.util.KeYConstants;

import org.key_project.server.ApiVersion;
import org.key_project.server.ServerOptions;
import org.key_project.server.dto.HealthStatus;
import org.key_project.server.dto.Ok;
import org.key_project.server.dto.ServerVersion;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code server.*} methods: who you are talking to, whether it is alive, and what it can do.
 */
public final class ServerMethods {

    /** Where the OpenRPC document lives in the jar. */
    private static final String DESCRIPTION_RESOURCE = "/openrpc.json";

    private final String instanceId;
    private final ServerOptions options;
    private final TaskRegistry tasks;
    private final ObjectMapper mapper;
    private final Runnable shutdown;

    /**
     * Creates the handlers.
     *
     * @param instanceId identifier of the running instance
     * @param options the configuration the instance was started with
     * @param tasks used to tell whether anything is still running
     * @param mapper used to read the shipped OpenRPC document
     * @param shutdown what to run when a client asks the instance to stop
     */
    public ServerMethods(String instanceId, ServerOptions options, TaskRegistry tasks,
            ObjectMapper mapper, Runnable shutdown) {
        this.instanceId = instanceId;
        this.options = options;
        this.tasks = tasks;
        this.mapper = mapper;
        this.shutdown = shutdown;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(
            new RpcMethod("server.version", Concurrency.INLINE, params -> version()));
        dispatcher.register(
            new RpcMethod("server.health", Concurrency.INLINE, params -> health()));
        dispatcher.register(
            new RpcMethod("server.describe", Concurrency.INLINE, params -> describe()));
        dispatcher.register(new RpcMethod("server.shutdown", Concurrency.INLINE,
            params -> shutdown(params.asOptional(ShutdownRequest.class))));
    }

    /**
     * Returns the OpenRPC document describing this server.
     *
     * <p>
     * Served from the same file that ships in the jar, so a client is reading the description of
     * the build it is talking to rather than of whatever was current when the documentation was
     * last published.
     *
     * @return the OpenRPC document
     */
    private JsonNode describe() {
        try (InputStream resource = ServerMethods.class.getResourceAsStream(DESCRIPTION_RESOURCE)) {
            if (resource == null) {
                throw new RpcException(RpcErrorCode.INTERNAL_ERROR,
                    "This build ships no OpenRPC document at " + DESCRIPTION_RESOURCE);
            }
            return mapper.readTree(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new RpcException(RpcErrorCode.INTERNAL_ERROR,
                "Could not read the OpenRPC document", null, e);
        }
    }

    /**
     * Stops the instance.
     *
     * <p>
     * Refused while work is in progress unless forced. A client that started a search and then
     * asked the server to stop has almost certainly lost track of one of the two, and throwing
     * away a proof in progress on that basis would be the wrong guess.
     *
     * @param request whether to stop regardless
     * @return an acknowledgement, sent before the instance actually goes down
     */
    private Object shutdown(ShutdownRequest request) {
        if (!request.forced() && tasks.hasActiveTask()) {
            throw new RpcException(RpcErrorCode.TASK_CONFLICT,
                "Work is still in progress; pass force to stop anyway");
        }
        // Answered first, closed afterwards: shutting down inside the handler would take the
        // connection down with it and the caller would see a broken socket instead of an answer.
        Thread closer = new Thread(shutdown, "key-shutdown-request");
        closer.setDaemon(true);
        closer.start();
        return new Ok(true);
    }

    private ServerVersion version() {
        return new ServerVersion(ApiVersion.CURRENT, KeYConstants.VERSION, instanceId,
            options.threads());
    }

    private HealthStatus health() {
        return new HealthStatus(true);
    }
}
