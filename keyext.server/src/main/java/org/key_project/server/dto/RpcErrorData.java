/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Machine-readable detail attached to an error.
 *
 * <p>
 * Clients are expected to branch on the numeric error code, never on message text. This carries the
 * extra structure that some codes promise, in particular the source positions KeY already knows
 * about for load and script failures.
 *
 * @param positions source locations relevant to the failure, may be empty
 * @param detail free-form additional explanation, may be {@code null}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RpcErrorData(List<SourcePosition> positions, String detail) {

    /**
     * Wraps a plain explanation with no source positions.
     *
     * @param detail the explanation
     * @return the error detail
     */
    public static RpcErrorData of(String detail) {
        return new RpcErrorData(List.of(), detail);
    }
}
