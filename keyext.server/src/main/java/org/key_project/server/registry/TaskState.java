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
    private boolean cancelRequested;
    private @Nullable Runnable canceller;

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

    /**
     * What this task operates on.
     *
     * @return the subject reference, or {@code null} when the task has none
     */
    public @Nullable Object subject() {
        return subject;
    }

    /** Marks the task as executing. */
    public synchronized void running() {
        status = TaskStatus.RUNNING;
    }

    /**
     * Registers how to interrupt this task's work while it runs.
     *
     * <p>
     * Called by the work itself once it knows what it is driving, for instance KeY's auto mode.
     * If cancellation was already requested before that point, the hook fires immediately so a
     * cancel that raced ahead of the worker thread is not lost.
     *
     * @param interrupt what to run when the client asks to cancel
     */
    public void onCancel(Runnable interrupt) {
        boolean cancelAlreadyAsked;
        synchronized (this) {
            canceller = interrupt;
            cancelAlreadyAsked = cancelRequested;
        }
        if (cancelAlreadyAsked) {
            interrupt.run();
        }
    }

    /** Forgets the interrupt hook, once the work it belonged to has finished. */
    public synchronized void clearCancelHook() {
        canceller = null;
    }

    /**
     * Asks the task to stop.
     *
     * <p>
     * This only requests: KeY stops the automatic search between rule applications, so the work
     * ends when it next gets the chance. The task reaches {@link TaskStatus#CANCELLED} only once
     * it actually has.
     *
     * @return {@code true} if the task was still active and the request was recorded
     */
    public boolean requestCancel() {
        Runnable interrupt;
        synchronized (this) {
            if (!isActive()) {
                return false;
            }
            cancelRequested = true;
            interrupt = canceller;
        }
        // Outside the lock: the hook reaches into KeY's proof control, which does its own
        // locking, and holding two locks in an order we do not control invites a deadlock.
        if (interrupt != null) {
            interrupt.run();
        }
        return true;
    }

    /**
     * Whether the client asked for this task to stop.
     *
     * @return {@code true} once cancellation has been requested
     */
    public synchronized boolean isCancelRequested() {
        return cancelRequested;
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
