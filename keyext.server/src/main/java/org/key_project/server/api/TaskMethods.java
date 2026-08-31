/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcMethod;

/**
 * The {@code task.*} read methods.
 *
 * <p>
 * These are deliberately {@link Concurrency#INLINE}: asking what a running task is doing must not
 * queue behind the task itself.
 */
public final class TaskMethods {

    private final TaskRegistry tasks;

    /**
     * Creates the handlers.
     *
     * @param tasks the registry to read from
     */
    public TaskMethods(TaskRegistry tasks) {
        this.tasks = tasks;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(new RpcMethod("task.get", Concurrency.INLINE,
            params -> tasks.require(params.as(TaskRequest.class).taskId()).toHandle()));
        dispatcher.register(
            new RpcMethod("task.list", Concurrency.INLINE, params -> tasks.list()));
    }
}
