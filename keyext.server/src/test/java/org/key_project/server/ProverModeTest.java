/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import de.uka.ilkd.key.prover.impl.ParallelProver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that the prover mode is pinned through the system properties that
 * {@link ParallelProver#isEnabled()} consults, rather than through the persisted user settings.
 */
class ProverModeTest {

    @AfterEach
    void restoreDefaults() {
        // The surrounding build pins key.prover.parallel=false for the whole test suite.
        System.setProperty(ParallelProver.PARALLEL_PROPERTY, "false");
        System.clearProperty(ParallelProver.THREADS_PROPERTY);
    }

    @Test
    void singleWorkerPinsTheSequentialProver() {
        assertThat(ProverMode.apply(1)).isEqualTo(1);

        assertThat(System.getProperty(ParallelProver.PARALLEL_PROPERTY)).isEqualTo("false");
        assertThat(System.getProperty(ParallelProver.THREADS_PROPERTY)).isNull();
        assertThat(ParallelProver.isEnabled()).isFalse();
    }

    @Test
    void severalWorkersEnableTheParallelProver() {
        int requested = Math.min(2, Runtime.getRuntime().availableProcessors());
        if (requested < 2) {
            return; // a single-core machine cannot exercise this
        }

        assertThat(ProverMode.apply(requested)).isEqualTo(requested);

        assertThat(System.getProperty(ParallelProver.PARALLEL_PROPERTY)).isEqualTo("true");
        assertThat(ParallelProver.isEnabled()).isTrue();
        assertThat(ParallelProver.effectiveWorkerCount()).isEqualTo(requested);
    }

    @Test
    void workerCountIsClampedToTheAvailableProcessors() {
        int available = Runtime.getRuntime().availableProcessors();

        assertThat(ProverMode.apply(available + 8)).isEqualTo(available);
        assertThat(ParallelProver.effectiveWorkerCount()).isEqualTo(available);
    }

    @Test
    void rejectsNonPositiveWorkerCounts() {
        assertThatThrownBy(() -> ProverMode.apply(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads");
    }
}
