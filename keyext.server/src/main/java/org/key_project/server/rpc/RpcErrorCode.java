/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

/**
 * The error codes this server returns.
 *
 * <p>
 * Clients branch on the numeric code; message text is for humans and may change. Application codes
 * start below the JSON-RPC reserved range, as the specification requires.
 */
public enum RpcErrorCode {

    /** Invalid JSON was received. */
    PARSE_ERROR(-32700),
    /** The payload was not a valid JSON-RPC request object. */
    INVALID_REQUEST(-32600),
    /** No such method. */
    METHOD_NOT_FOUND(-32601),
    /** The parameters did not fit the method. */
    INVALID_PARAMS(-32602),
    /** The server hit an unexpected condition. */
    INTERNAL_ERROR(-32603),

    /** The environment identifier is unknown or the environment was closed. */
    ENV_NOT_FOUND(-32001),
    /** The proof identifier is unknown or the proof was closed. */
    PROOF_NOT_FOUND(-32002),
    /** The goal identifier is unknown or the goal is no longer open. */
    GOAL_NOT_FOUND(-32003),
    /** A project or file could not be loaded; the data carries source positions. */
    LOAD_FAILED(-32004),
    /** A proof script failed to parse or to run; the data carries a source position. */
    SCRIPT_ERROR(-32005),
    /** A diagnostic probe could not complete within its limits. */
    DIAGNOSTIC_UNAVAILABLE(-32006),
    /** The proof already has a running task. */
    TASK_CONFLICT(-32007),
    /** An SMT solver is missing or not executable. */
    SOLVER_UNAVAILABLE(-32008),
    /** A sequent format was requested that this version does not implement. */
    UNSUPPORTED_FORMAT(-32009),
    /** The task identifier is unknown. */
    TASK_NOT_FOUND(-32010);

    private final int code;

    RpcErrorCode(int code) {
        this.code = code;
    }

    /**
     * The numeric code sent on the wire.
     *
     * @return the JSON-RPC error code
     */
    public int code() {
        return code;
    }
}
