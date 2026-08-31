/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.exec;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.key_project.server.registry.TaskState;

import org.jspecify.annotations.Nullable;

/**
 * Runs proof work on the thread that owns KeY state, and stops it on request or on a deadline.
 *
 * <p>
 * Everything that changes a proof — the automatic search, a macro, a script — runs here rather
 * than on a thread KeY starts for us. That matters for more than tidiness: a search started
 * through {@code ProofControl} runs on a thread this server does not hold a reference to, so an
 * exception thrown inside it reaches nothing but the default handler and the task it belonged to
 * would be reported as having finished normally. Running the work in place means a failure comes
 * back as a failure.
 *
 * <p>
 * Both stopping mechanisms are the one KeY already implements: the prover checks
 * {@code Thread.interrupted()} between rule applications, so a client cancelling and a budget
 * running out take exactly the same path, and neither can leave a proof half-way through a rule.
 */
public final class InterruptibleRun {

    private static final ThreadFactory ALARM_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "key-deadline");
        thread.setDaemon(true);
        return thread;
    };

    private static final ScheduledExecutorService ALARMS =
        Executors.newSingleThreadScheduledExecutor(ALARM_THREADS);

    private InterruptibleRun() {
    }

    /**
     * What a run produced and whether its budget ran out first.
     *
     * @param value the value the work returned
     * @param timedOut whether the run was stopped because its budget elapsed
     * @param <T> what the work produces
     */
    public record Result<T>(T value, boolean timedOut) {
    }

    /**
     * Runs work on the calling thread under a cancellation hook and an optional budget.
     *
     * @param task the task the work belongs to, which the client may cancel
     * @param budgetMs milliseconds the work may take, or {@code null} for no limit
     * @param work the work to run
     * @param <T> what the work produces
     * @return the value and whether the budget elapsed
     * @throws Exception whatever the work throws
     */
    public static <T> Result<T> run(TaskState task, @Nullable Long budgetMs, Callable<T> work)
            throws Exception {
        Thread worker = Thread.currentThread();
        AtomicBoolean elapsed = new AtomicBoolean();
        task.onCancel(worker::interrupt);

        ScheduledFuture<?> alarm = budgetMs == null ? null : ALARMS.schedule(() -> {
            elapsed.set(true);
            worker.interrupt();
        }, budgetMs, TimeUnit.MILLISECONDS);

        try {
            T value = work.call();
            // Ask the alarm to stand down before reading the flag. A successful cancellation
            // proves it never ran, which rules out reporting a timeout for a run that finished
            // in the instant before the deadline.
            boolean stoodDown = alarm == null || alarm.cancel(false);
            return new Result<>(value, !stoodDown && elapsed.get());
        } finally {
            if (alarm != null) {
                alarm.cancel(false);
            }
            task.clearCancelHook();
            // An interrupt that arrived just as the work finished must not follow this thread
            // into the next task.
            Thread.interrupted();
        }
    }
}
