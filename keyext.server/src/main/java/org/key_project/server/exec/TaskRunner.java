/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.exec;

import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.TaskHandle;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.registry.TaskState;
import org.key_project.server.rpc.KeyErrors;
import org.key_project.server.rpc.RpcException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Starts long-running work and reports it back as a task.
 *
 * <p>
 * The caller gets a handle immediately; the work runs on the single KeY worker thread. Note what
 * "succeeded" means here: the work returned without throwing. It is emphatically not a claim that
 * any proof closed, which only {@code Proof.closed()} can establish.
 */
public final class TaskRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskRunner.class);

    private final SerialExecutor executor;
    private final TaskRegistry tasks;
    private final ServerUserInterfaceControl control;

    /**
     * Creates a runner.
     *
     * @param executor the worker thread that owns KeY state
     * @param tasks where task state is recorded
     * @param control the control that reports KeY progress
     */
    public TaskRunner(SerialExecutor executor, TaskRegistry tasks,
            ServerUserInterfaceControl control) {
        this.executor = executor;
        this.tasks = tasks;
        this.control = control;
    }

    /**
     * Queues work and returns its handle without waiting.
     *
     * @param kind what the work does
     * @param subject what it operates on, may be {@code null}
     * @param work the work itself
     * @return the handle the client polls
     */
    public TaskHandle launch(TaskKind kind, @Nullable Object subject, Work work) {
        TaskState task = tasks.create(kind, subject);
        executor.submit(() -> {
            task.running();
            control.setCurrentTask(task);
            try {
                task.succeeded(work.run());
            } catch (RpcException e) {
                LOGGER.debug("Task {} failed with {}", task.taskId(), e.errorCode(), e);
                task.failed(e.data() != null ? e.data() : KeyErrors.describe(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                task.cancelled();
            } catch (Exception e) {
                LOGGER.warn("Task {} failed", task.taskId(), e);
                task.failed(KeyErrors.describe(e));
            } finally {
                control.setCurrentTask(null);
            }
            return null;
        });
        return task.toHandle();
    }

    /** Work that produces the result of a task. */
    @FunctionalInterface
    public interface Work {
        /**
         * Performs the work.
         *
         * @return the product to publish on the task, may be {@code null}
         * @throws Exception when the work fails
         */
        @Nullable
        Object run() throws Exception;
    }
}
