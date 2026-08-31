/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.instance;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shuts an instance down when nobody is using it any more.
 *
 * <p>
 * On by default, because leaving servers running is not a hypothetical: an agent workflow starts
 * one, the run ends badly or the agent forgets, and a JVM holding a loaded project sits there for
 * the rest of the day. Several of those and a machine is out of memory for reasons nobody can
 * trace back to a proof attempt.
 *
 * <p>
 * Idle means two things at once. No request has arrived for the configured time, <em>and</em> no
 * task is running. Without the second condition a proof search that takes half an hour and needs
 * no attention while it runs would be killed halfway through, which is precisely the work this
 * server exists to make possible.
 */
public final class IdleTimeout implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(IdleTimeout.class);

    /** How often to look, at most. A minute is soon enough for something measured in half-hours. */
    private static final long MAX_CHECK_SECONDS = 60;

    private final long timeoutNanos;
    private final BooleanSupplier busy;
    private final Runnable shutdown;
    private final AtomicLong lastRequest = new AtomicLong(System.nanoTime());
    private final ScheduledExecutorService checks =
        Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "key-idle");
            thread.setDaemon(true);
            return thread;
        });

    /**
     * Starts watching.
     *
     * @param timeoutSeconds how long without a request counts as idle
     * @param busy tells whether work is in progress, which suspends the timeout
     * @param shutdown what to run once the instance is judged idle
     */
    public IdleTimeout(int timeoutSeconds, BooleanSupplier busy, Runnable shutdown) {
        this.timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        this.busy = busy;
        this.shutdown = shutdown;
        long period = Math.max(1, Math.min(MAX_CHECK_SECONDS, timeoutSeconds));
        checks.scheduleWithFixedDelay(this::check, period, period, TimeUnit.SECONDS);
        LOGGER.debug("Idle timeout armed at {}s, checked every {}s", timeoutSeconds, period);
    }

    /** Records that somebody is still there. */
    public void touch() {
        lastRequest.set(System.nanoTime());
    }

    private void check() {
        if (busy.getAsBoolean()) {
            // Work in progress counts as use, however quiet the socket is.
            touch();
            return;
        }
        long idleFor = System.nanoTime() - lastRequest.get();
        if (idleFor >= timeoutNanos) {
            LOGGER.info("Shutting down after {}s without a request.",
                TimeUnit.NANOSECONDS.toSeconds(idleFor));
            shutdown.run();
        }
    }

    @Override
    public void close() {
        checks.shutdownNow();
    }
}
