/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.Ok;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.RpcMethod;

/**
 * The {@code task.*} methods.
 *
 * <p>
 * These are deliberately {@link Concurrency#INLINE}. Asking what a running task is doing must not
 * queue behind the task itself, and cancelling one certainly must not: a cancel that waited for
 * the worker thread would only ever arrive after the work it was meant to stop had finished.
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
        dispatcher.register(new RpcMethod("task.cancel", Concurrency.INLINE,
            params -> cancel(params.as(TaskRequest.class))));
    }

    /**
     * Asks a task to stop.
     *
     * <p>
     * The acknowledgement reports whether the task was still running and the request was passed
     * on, not that it has already stopped: KeY notices the request between rule applications.
     * Clients poll {@code task.get} to see it reach {@code CANCELLED}.
     *
     * @param request names the task to stop
     * @return whether a cancellation was actually requested
     */
    private Object cancel(TaskRequest request) {
        return new Ok(tasks.require(request.taskId()).requestCancel());
    }
}
