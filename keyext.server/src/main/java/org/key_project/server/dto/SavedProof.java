/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Where a proof was written.
 *
 * <p>
 * That this record exists at all means the file is on disk: the save method reports a failure as
 * an error, never as a path.
 *
 * @param path the absolute path of the file that was written
 * @param bytes its size, so a caller can tell a written proof from an empty file without
 *        going to look
 */
public record SavedProof(String path, long bytes) {
}
