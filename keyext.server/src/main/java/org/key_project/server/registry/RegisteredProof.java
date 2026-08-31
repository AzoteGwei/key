/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import de.uka.ilkd.key.proof.Proof;

import org.jspecify.annotations.Nullable;

/**
 * One proof the server holds, together with where it came from.
 *
 * @param proofId opaque identifier issued to clients
 * @param envId the environment this proof belongs to
 * @param contractId the contract it was started from, {@code null} when it came from a file
 * @param proof the KeY proof object
 */
public record RegisteredProof(String proofId, String envId, @Nullable String contractId,
        Proof proof) {

    /** Releases the KeY resources held by this proof. */
    public void dispose() {
        if (!proof.isDisposed()) {
            proof.dispose();
        }
    }
}
