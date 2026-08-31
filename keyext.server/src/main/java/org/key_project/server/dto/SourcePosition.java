/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A position in a source file, as reported by KeY.
 *
 * @param file file the position refers to, {@code null} when KeY could not attribute one
 * @param line 1-based line number, or {@code 0} when unknown
 * @param column 1-based column number, or {@code 0} when unknown
 * @param message the message KeY attached to this position
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SourcePosition(String file, int line, int column, String message) {
}
