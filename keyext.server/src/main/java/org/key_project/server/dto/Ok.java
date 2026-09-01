/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * The plain acknowledgement the methods that only have an effect return.
 *
 * <p>
 * It reports that the request was carried out, nothing more. In particular it is never a statement
 * about a proof.
 *
 * <p>
 * Nearly every method that returns this returns {@code true} and reports failure as an error
 * object instead. {@code task.cancel} is the exception: it answers {@code false} when there was
 * nothing to interrupt, which is an outcome rather than a failure.
 *
 * @param ok whether the request was carried out
 */
public record Ok(boolean ok) {
}
