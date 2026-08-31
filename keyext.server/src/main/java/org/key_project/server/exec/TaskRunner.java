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
        return start(tasks.create(kind, subject), work);
    }

    /**
     * Queues work that must be the only one running on its subject.
     *
     * @param kind what the work does
     * @param subject what it operates on
     * @param work the work itself
     * @return the handle the client polls
     * @throws RpcException with {@link org.key_project.server.rpc.RpcErrorCode#TASK_CONFLICT} when
     *         another task is already active on that subject
     */
    public TaskHandle launchExclusive(TaskKind kind, Object subject, Work work) {
        return start(tasks.createExclusive(kind, subject), work);
    }

    private TaskHandle start(TaskState task, Work work) {
        executor.submit(() -> {
            if (task.isCancelRequested()) {
                // Cancelled while still queued; the work must not start at all.
                task.cancelled();
                return null;
            }
            task.running();
            control.setCurrentTask(task);
            try {
                Object product = work.run(task);
                if (task.isCancelRequested()) {
                    // The work returned, but only because it was asked to stop. Reporting it as
                    // succeeded would present a half-finished run as a completed one.
                    task.cancelled();
                } else {
                    task.succeeded(product);
                }
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
         * @param task the task being run, so long work can register how to interrupt it
         * @return the product to publish on the task, may be {@code null}
         * @throws Exception when the work fails
         */
        @Nullable
        Object run(TaskState task) throws Exception;
    }
}
