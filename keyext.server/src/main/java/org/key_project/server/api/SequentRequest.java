/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.GoalRef;
import org.key_project.server.dto.SequentFormat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code goal.getSequent}.
 *
 * @param goal the goal to render
 * @param format how to render it, default {@link SequentFormat#TEXT}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record SequentRequest(GoalRef goal, @Nullable SequentFormat format) {

    /**
     * The requested format.
     *
     * @return the format asked for, or {@link SequentFormat#TEXT} when none was
     */
    public SequentFormat formatOrDefault() {
        return format == null ? SequentFormat.TEXT : format;
    }
}
