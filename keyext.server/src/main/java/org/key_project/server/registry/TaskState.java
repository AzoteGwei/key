/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.dto.TaskHandle;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.dto.TaskStatus;

import org.jspecify.annotations.Nullable;

/**
 * Mutable state of one long-running operation.
 *
 * <p>
 * Written by the worker thread and read by request threads, hence the synchronisation. Note that
 * {@link TaskStatus#SUCCEEDED} only records that the work finished without throwing; it never
 * implies that a proof closed.
 */
public final class TaskState {

    private final String taskId;
    private final TaskKind kind;
    private final @Nullable Object subject;

    private TaskStatus status = TaskStatus.PENDING;
    private @Nullable Object result;
    private @Nullable Object progress;
    private @Nullable RpcErrorData error;

    TaskState(String taskId, TaskKind kind, @Nullable Object subject) {
        this.taskId = taskId;
        this.kind = kind;
        this.subject = subject;
    }

    /**
     * The identifier clients use to poll this task.
     *
     * @return the task identifier
     */
    public String taskId() {
        return taskId;
    }

    /** Marks the task as executing. */
    public synchronized void running() {
        status = TaskStatus.RUNNING;
    }

    /**
     * Records that the work finished without throwing.
     *
     * <p>
     * This is not a statement about any proof being closed.
     *
     * @param taskResult the product of the work, may be {@code null}
     */
    public synchronized void succeeded(@Nullable Object taskResult) {
        this.result = taskResult;
        this.progress = null;
        status = TaskStatus.SUCCEEDED;
    }

    /**
     * Records that the work aborted.
     *
     * @param failure structured description of the failure
     */
    public synchronized void failed(RpcErrorData failure) {
        this.error = failure;
        this.progress = null;
        status = TaskStatus.FAILED;
    }

    /** Records that the work was cancelled on request. */
    public synchronized void cancelled() {
        this.progress = null;
        status = TaskStatus.CANCELLED;
    }

    /**
     * Publishes current progress detail.
     *
     * @param detail free-form progress information
     */
    public synchronized void progress(@Nullable Object detail) {
        this.progress = detail;
    }

    /**
     * Whether the task is still queued or executing.
     *
     * @return {@code true} while the task has not reached a terminal state
     */
    public synchronized boolean isActive() {
        return status == TaskStatus.PENDING || status == TaskStatus.RUNNING;
    }

    /**
     * Renders the immutable view sent to clients.
     *
     * @return the wire representation of this task
     */
    public synchronized TaskHandle toHandle() {
        return new TaskHandle(taskId, kind, status, subject, result, progress, error);
    }
}
