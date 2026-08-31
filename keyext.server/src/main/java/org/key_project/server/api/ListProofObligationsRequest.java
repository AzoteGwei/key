/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.EnvironmentRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code environment.listProofObligations}.
 *
 * @param env the environment to enumerate
 * @param targetClass restrict the result to one fully qualified class name, may be {@code null}
 * @param includeLibraryClasses whether to also report contracts of the JDK stubs KeY loads
 *        alongside the project. Defaults to {@code false}: those run to several hundred entries
 *        and are almost never what was asked for, and KeY's own headless obligation selector
 *        skips them too. Nothing is hidden silently — set this to {@code true} to see them
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ListProofObligationsRequest(EnvironmentRef env, @Nullable String targetClass,
        @Nullable Boolean includeLibraryClasses) {

    /**
     * Whether library contracts were asked for.
     *
     * @return {@code true} only when the client explicitly asked for them
     */
    public boolean wantsLibraryClasses() {
        return Boolean.TRUE.equals(includeLibraryClasses);
    }
}
