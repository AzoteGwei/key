/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import de.uka.ilkd.key.util.KeYConstants;

import org.key_project.server.ApiVersion;
import org.key_project.server.ServerOptions;
import org.key_project.server.dto.HealthStatus;
import org.key_project.server.dto.ServerVersion;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcMethod;

/** The {@code server.*} methods: who you are talking to and whether it is alive. */
public final class ServerMethods {

    private final String instanceId;
    private final ServerOptions options;

    /**
     * Creates the handlers.
     *
     * @param instanceId identifier of the running instance
     * @param options the configuration the instance was started with
     */
    public ServerMethods(String instanceId, ServerOptions options) {
        this.instanceId = instanceId;
        this.options = options;
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
    }

    private ServerVersion version() {
        return new ServerVersion(ApiVersion.CURRENT, KeYConstants.VERSION, instanceId,
            options.threads());
    }

    private HealthStatus health() {
        return new HealthStatus(true);
    }
}
