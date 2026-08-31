/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

/**
 * One callable method.
 *
 * @param name the {@code namespace.verb} name clients send
 * @param concurrency where the handler is allowed to run
 * @param handler the implementation
 */
public record RpcMethod(String name, Concurrency concurrency, Handler handler) {

    /** The implementation of a method. */
    @FunctionalInterface
    public interface Handler {
        /**
         * Executes the method.
         *
         * @param params the named parameters of the request
         * @return the value to send as {@code result}
         */
        Object handle(Params params);
    }
}
