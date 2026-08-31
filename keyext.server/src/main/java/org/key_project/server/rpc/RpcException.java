/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

import org.key_project.server.dto.RpcErrorData;

import org.jspecify.annotations.Nullable;

/** A failure that is meant to reach the client as a JSON-RPC error object. */
public class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final RpcErrorCode errorCode;
    private final transient @Nullable RpcErrorData data;

    /**
     * Creates an error without structured detail.
     *
     * @param errorCode the code the client will branch on
     * @param message human-readable explanation
     */
    public RpcException(RpcErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    /**
     * Creates an error with structured detail.
     *
     * @param errorCode the code the client will branch on
     * @param message human-readable explanation
     * @param data structured detail, may be {@code null}
     * @param cause the underlying failure, may be {@code null}
     */
    public RpcException(RpcErrorCode errorCode, String message, @Nullable RpcErrorData data,
            @Nullable Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.data = data;
    }

    /**
     * The code to report.
     *
     * @return the error code
     */
    public RpcErrorCode errorCode() {
        return errorCode;
    }

    /**
     * The structured detail to report.
     *
     * @return the detail, or {@code null} when there is none
     */
    public @Nullable RpcErrorData data() {
        return data;
    }
}
