/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The state of one long-running operation.
 *
 * <p>
 * Read {@link TaskStatus#SUCCEEDED} carefully: it reports that the task finished without throwing,
 * not that a proof closed.
 *
 * @param taskId opaque task identifier
 * @param kind what the task does
 * @param status where the task is in its lifecycle
 * @param subject what the task operates on, an {@link EnvironmentRef} or a proof reference
 * @param result the product of a succeeded task, for a {@link TaskKind#LOAD} an
 *        {@link EnvironmentRef}
 * @param progress free-form progress detail while running
 * @param error why a failed task failed
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskHandle(String taskId, TaskKind kind, TaskStatus status, Object subject,
        Object result, Object progress, RpcErrorData error) {
}
