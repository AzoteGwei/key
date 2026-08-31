/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import de.uka.ilkd.key.prover.impl.ParallelProver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pins the prover mode for this JVM.
 *
 * <p>
 * The selection seam is {@link ParallelProver#isEnabled()}, which reads
 * {@link ParallelProver#PARALLEL_PROPERTY} first and only falls back to the persisted
 * {@code GeneralSettings} when that property is unset. We therefore always set the property
 * explicitly, even for the single-threaded default: a server whose proving mode silently depends on
 * whatever the user last clicked in the GUI is not reproducible.
 *
 * <p>
 * Mutating {@code GeneralSettings} instead would fire a PropertyChange that
 * {@code ProofIndependentSettings} turns into a settings save, permanently rewriting the user's
 * preferences from a server run. That is why this goes through system properties.
 */
public final class ProverMode {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProverMode.class);

    private ProverMode() {
    }

    /**
     * Applies the requested worker count as a process-scoped override.
     *
     * <p>
     * A count of {@code 1} pins the single-threaded prover. Higher counts are clamped to the number
     * of available processors, mirroring what the KeY CLI does for {@code --threads}.
     *
     * @param threads requested number of prover workers, at least 1
     * @return the worker count that actually took effect
     */
    public static int apply(int threads) {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        if (threads == 1) {
            System.setProperty(ParallelProver.PARALLEL_PROPERTY, "false");
            System.clearProperty(ParallelProver.THREADS_PROPERTY);
            LOGGER.info("Prover pinned to the single-threaded engine.");
            return 1;
        }
        int workers = Math.min(threads, Runtime.getRuntime().availableProcessors());
        System.setProperty(ParallelProver.PARALLEL_PROPERTY, "true");
        System.setProperty(ParallelProver.THREADS_PROPERTY, Integer.toString(workers));
        if (workers != threads) {
            LOGGER.warn("Requested {} prover workers, clamped to {} available processors.", threads,
                workers);
        } else {
            LOGGER.info("Prover using {} workers.", workers);
        }
        return workers;
    }
}
