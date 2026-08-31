/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.key_project.server.instance.IdleTimeout;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Going away when nobody is using it, and only then.
 *
 * <p>
 * Both halves matter. A server that outlives its agent is how a machine ends up out of memory for
 * reasons nobody can trace; a server that gives up during a half-hour proof search destroys the
 * exact thing it exists to make possible.
 */
class IdleTimeoutTest {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Test
    void shutsDownWhenNothingHasHappenedForLongEnough() throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);

        try (IdleTimeout timeout = new IdleTimeout(1, () -> false, shutdown::countDown)) {
            assertThat(shutdown.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void staysUpWhileWorkIsInProgress() throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);
        AtomicBoolean working = new AtomicBoolean(true);

        try (IdleTimeout timeout = new IdleTimeout(1, working::get, shutdown::countDown)) {
            // A proof search needs no requests while it runs. Counting that as idleness would
            // kill exactly the long jobs this server is for.
            assertThat(shutdown.await(4, TimeUnit.SECONDS)).isFalse();

            working.set(false);
            assertThat(shutdown.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void aRequestPutsTheClockBack() throws Exception {
        CountDownLatch shutdown = new CountDownLatch(1);

        try (IdleTimeout timeout = new IdleTimeout(2, () -> false, shutdown::countDown)) {
            for (int i = 0; i < 5; i++) {
                Thread.sleep(500);
                timeout.touch();
            }
            assertThat(shutdown.getCount()).isOne();

            assertThat(shutdown.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }
}
