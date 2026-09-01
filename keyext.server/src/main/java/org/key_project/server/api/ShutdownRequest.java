/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code server.shutdown}.
 *
 * @param force stop even though tasks are still running, default {@code false}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ShutdownRequest(@Nullable Boolean force) {

    /**
     * Whether the caller insisted.
     *
     * @return {@code true} only when force was explicitly asked for
     */
    public boolean forced() {
        return Boolean.TRUE.equals(force);
    }
}
