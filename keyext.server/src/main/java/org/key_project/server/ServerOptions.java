/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;

/**
 * The validated startup configuration of one server instance.
 *
 * <p>
 * Everything in here is fixed for the lifetime of the instance. In particular the prover mode
 * ({@link #threads()}) is deliberately <em>not</em> a per-request parameter: it is backed by JVM
 * global system properties (see {@link ProverMode}), and dressing a process-wide switch up as a
 * per-call option would be dishonest. Different degrees of parallelism mean different instances.
 *
 * @param port TCP port to bind on {@code 127.0.0.1}, or {@code 0} to let the OS choose
 * @param workspace directory the instance is anchored to; the instance file is written below it
 * @param idleTimeoutSeconds shut down after this many seconds without a request, or {@code 0} to
 *        stay alive indefinitely
 * @param threads number of prover worker threads; {@code 1} means the single-threaded prover
 */
public record ServerOptions(int port, Path workspace, int idleTimeoutSeconds, int threads) {

    /** Idle timeout used when the caller does not pass {@code --idle-timeout}. */
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 1800;

    public ServerOptions {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be in 0..65535, got " + port);
        }
        if (idleTimeoutSeconds < 0) {
            throw new IllegalArgumentException(
                "idle timeout must not be negative, got " + idleTimeoutSeconds);
        }
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be at least 1, got " + threads);
        }
        workspace = workspace.toAbsolutePath().normalize();
    }

    /** Whether this instance shuts itself down after a period without requests. */
    public boolean hasIdleTimeout() {
        return idleTimeoutSeconds > 0;
    }
}
