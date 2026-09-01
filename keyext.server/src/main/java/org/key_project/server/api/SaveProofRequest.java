/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.ProofRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code proof.save}.
 *
 * @param proof the proof to write out
 * @param path where to write it; relative paths are taken against the workspace. When omitted the
 *        name KeY gave the proof is used, in the workspace
 * @param asBundle write a {@code .zproof} bundle carrying the sources alongside the proof, rather
 *        than a bare {@code .proof} file. Default {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SaveProofRequest(ProofRef proof, @Nullable String path,
        @Nullable Boolean asBundle) {

    /**
     * Whether a bundle was asked for.
     *
     * @return {@code true} only when the client explicitly asked for one
     */
    public boolean bundle() {
        return Boolean.TRUE.equals(asBundle);
    }
}
