/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.key_project.server.dto.TaskHandle;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;

import org.jspecify.annotations.Nullable;

/** Keeps track of the long-running operations of one instance. */
public final class TaskRegistry {

    private final Map<String, TaskState> tasks = new ConcurrentHashMap<>();

    /**
     * Registers a new task in state {@code PENDING}.
     *
     * @param kind what the task will do
     * @param subject what it operates on, may be {@code null} before the subject exists
     * @return the new task state
     */
    public synchronized TaskState create(TaskKind kind, @Nullable Object subject) {
        String id = Ids.create("task");
        TaskState state = new TaskState(id, kind, subject);
        tasks.put(id, state);
        return state;
    }

    /**
     * Registers a new task, provided nothing else is already working on the same subject.
     *
     * <p>
     * Two proof searches on one proof would corrupt it, so the check and the registration happen
     * together rather than leaving a window in which two requests both pass the check.
     *
     * @param kind what the task will do
     * @param subject what it operates on
     * @return the new task state
     * @throws RpcException with {@link RpcErrorCode#TASK_CONFLICT} when a task is already active
     *         on that subject
     */
    public synchronized TaskState createExclusive(TaskKind kind, Object subject) {
        for (TaskState existing : tasks.values()) {
            if (subject.equals(existing.subject()) && existing.isActive()) {
                throw new RpcException(RpcErrorCode.TASK_CONFLICT,
                    "Another task is already running on this subject: " + existing.taskId());
            }
        }
        return create(kind, subject);
    }

    /**
     * Looks up a task.
     *
     * @param taskId the identifier to resolve
     * @return the task state
     * @throws RpcException with {@link RpcErrorCode#TASK_NOT_FOUND} when the identifier is unknown
     */
    public TaskState require(String taskId) {
        TaskState state = tasks.get(taskId);
        if (state == null) {
            throw new RpcException(RpcErrorCode.TASK_NOT_FOUND, "No such task: " + taskId);
        }
        return state;
    }

    /**
     * Snapshots every known task.
     *
     * @return the wire representation of all tasks
     */
    public List<TaskHandle> list() {
        List<TaskHandle> handles = new ArrayList<>(tasks.size());
        for (TaskState state : tasks.values()) {
            handles.add(state.toHandle());
        }
        return handles;
    }
}
