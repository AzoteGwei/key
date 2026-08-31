/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Lifecycle state of a task.
 *
 * <p>
 * {@link #SUCCEEDED} means <em>this task ran to completion without throwing</em>. It does
 * <em>not</em> mean the proof is closed. A macro that finishes normally and leaves three open goals
 * is a SUCCEEDED task with three open goals. Whether a proof is closed is answered only by
 * {@code proof.getStatistics().closed}, which comes straight from KeY's own {@code Proof.closed()}.
 */
public enum TaskStatus {
    /** Queued, not started yet. */
    PENDING,
    /** Currently executing. */
    RUNNING,
    /** Ran to completion without throwing. Says nothing about whether a proof closed. */
    SUCCEEDED,
    /** Aborted by an error. */
    FAILED,
    /** Cancelled on request. */
    CANCELLED
}
