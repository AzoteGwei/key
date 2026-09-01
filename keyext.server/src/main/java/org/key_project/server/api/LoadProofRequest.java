/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of {@code proof.loadFile}.
 *
 * @param path the {@code .proof} or {@code .zproof} file to replay; relative paths are taken
 *        against the workspace
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LoadProofRequest(String path) {
}
