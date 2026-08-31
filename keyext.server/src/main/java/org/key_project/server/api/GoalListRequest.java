/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.ProofRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code goal.list}.
 *
 * @param proof the proof to enumerate
 * @param includeClosed whether to also report goals that are no longer open, default {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record GoalListRequest(ProofRef proof, @Nullable Boolean includeClosed) {

    /**
     * Whether closed goals were asked for.
     *
     * @return {@code true} only when the client explicitly asked for them
     */
    public boolean wantsClosed() {
        return Boolean.TRUE.equals(includeClosed);
    }
}
