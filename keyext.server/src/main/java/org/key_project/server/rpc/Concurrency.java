/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

/**
 * How a method may be executed.
 *
 * <p>
 * This distinction is a hard requirement, not an optimisation. If read-only handling went through
 * the single KeY worker thread, an agent could not ask what a running proof search is doing until
 * that search finished, which defeats the point of the server.
 */
public enum Concurrency {

    /**
     * Safe to run on the request thread.
     *
     * <p>
     * Either the handler only reads, or it merely queues work and returns a task handle.
     */
    INLINE,

    /**
     * Mutates KeY state, so it must run on the single worker thread and the caller waits.
     *
     * <p>
     * Only for operations that are short enough to answer synchronously. Anything that may run for
     * more than about a second returns a task handle instead and is therefore {@link #INLINE}.
     */
    SERIAL
}
