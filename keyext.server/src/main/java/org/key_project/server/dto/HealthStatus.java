/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Liveness answer.
 *
 * @param ok always {@code true}; reaching this handler at all is the signal
 */
public record HealthStatus(boolean ok) {
}
