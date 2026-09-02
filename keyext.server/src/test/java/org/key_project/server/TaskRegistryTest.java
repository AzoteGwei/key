/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import org.key_project.server.dto.ProofRef;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.registry.TaskState;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One task at a time on one subject.
 *
 * <p>
 * Two proof searches on one proof would corrupt it, so the exclusion has to hold from the moment a
 * task is registered, not from the moment its worker thread happens to pick it up. Asserting that
 * over the wire cannot be done without a race: the search on a small fixture can finish inside the
 * round trip that would carry the second request, and then the second one is refused or accepted
 * depending on which side of the machine wins. Here the lifecycle is driven by hand, so the queued
 * window is a state the test puts the task in rather than one it hopes to catch it in.
 */
class TaskRegistryTest {

    private final TaskRegistry tasks = new TaskRegistry();
    private final ProofRef proof = new ProofRef("prf-subject");

    @Test
    void aQueuedTaskAlreadyExcludesTheNext() {
        TaskState first = tasks.createExclusive(TaskKind.AUTO, proof);

        // Still PENDING: nothing has run yet. This is the window the RPC-level test could not
        // hold open, and the one where a second search does the damage.
        assertThat(first.isActive()).isTrue();
        assertConflict();
    }

    @Test
    void aRunningTaskExcludesTheNext() {
        TaskState first = tasks.createExclusive(TaskKind.AUTO, proof);
        first.running();

        assertConflict();
    }

    @Test
    void aFinishedTaskExcludesNothing() {
        tasks.createExclusive(TaskKind.AUTO, proof).succeeded(null);

        // A proof that has been searched once can be searched again; exclusion is about
        // overlapping work, not about the subject having a history.
        TaskState second = tasks.createExclusive(TaskKind.AUTO, proof);
        assertThat(second.isActive()).isTrue();
        assertThat(tasks.activeTaskFor(proof)).isEqualTo(second.taskId());
    }

    @Test
    void aCancelledTaskExcludesNothing() {
        tasks.createExclusive(TaskKind.AUTO, proof).cancelled();

        // Cancelling is how a client gets out of a search it no longer wants. If the cancelled
        // task kept holding the subject, the proof would be unusable for the rest of the session.
        assertThat(tasks.createExclusive(TaskKind.AUTO, proof).isActive()).isTrue();
    }

    @Test
    void tasksOnDifferentProofsDoNotSeeEachOther() {
        tasks.createExclusive(TaskKind.AUTO, proof);

        ProofRef other = new ProofRef("prf-elsewhere");
        assertThat(tasks.createExclusive(TaskKind.AUTO, other).isActive()).isTrue();
        assertThat(tasks.activeTaskFor(other)).isNotEqualTo(tasks.activeTaskFor(proof));
    }

    @Test
    void theRefusalNamesTheTaskThatHoldsTheSubject() {
        TaskState holder = tasks.createExclusive(TaskKind.AUTO, proof);

        // A client that is told only "busy" can do nothing with the answer; told which task, it
        // can wait on that one or cancel it.
        assertThatThrownBy(() -> tasks.createExclusive(TaskKind.AUTO, proof))
                .isInstanceOf(RpcException.class)
                .hasMessageContaining(holder.taskId());
        assertThat(tasks.activeTaskFor(proof)).isEqualTo(holder.taskId());
    }

    @Test
    void anUnclaimedProofHasNoActiveTask() {
        assertThat(tasks.activeTaskFor(proof)).isNull();
    }

    private void assertConflict() {
        assertThatThrownBy(() -> tasks.createExclusive(TaskKind.AUTO, proof))
                .isInstanceOf(RpcException.class)
                .extracting(thrown -> ((RpcException) thrown).errorCode())
                .isEqualTo(RpcErrorCode.TASK_CONFLICT);
    }
}
