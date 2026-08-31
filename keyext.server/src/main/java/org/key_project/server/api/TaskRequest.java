/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of {@code task.get}.
 *
 * @param taskId the task to look up
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TaskRequest(String taskId) {
}
