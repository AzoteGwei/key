/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.exec;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Serialises every operation that mutates KeY state.
 *
 * <p>
 * KeY's {@code Proof}, {@code InitConfig} and {@code Services} are not thread safe, so one instance
 * runs all state-changing work on a single thread. Proving several things at once means running
 * several instances, not sharing one.
 *
 * <p>
 * Read-only request handling must <em>not</em> go through here. If it did, an agent could not even
 * ask what a long-running task is doing while that task holds the worker thread.
 */
public final class SerialExecutor implements AutoCloseable {

    private static final ThreadFactory THREAD_FACTORY = runnable -> {
        Thread thread = new Thread(runnable, "key-worker");
        thread.setDaemon(true);
        return thread;
    };

    private final ExecutorService executor = Executors.newSingleThreadExecutor(THREAD_FACTORY);

    /**
     * Queues work on the single KeY thread.
     *
     * @param work the operation to run
     * @param <T> result type of the operation
     * @return a handle on the queued work
     */
    public <T> Future<T> submit(Callable<T> work) {
        return executor.submit(work);
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
