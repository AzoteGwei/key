/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.jspecify.annotations.Nullable;

/**
 * Parameters of {@code environment.load}.
 *
 * @param path the {@code .key} file, {@code .proof} file, Java file or source directory to load
 * @param classpath extra class path entries, may be {@code null}
 * @param bootClassPath the boot class path to use, may be {@code null}
 * @param includes additional include files, may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record LoadRequest(String path, @Nullable List<String> classpath,
        @Nullable String bootClassPath, @Nullable List<String> includes) {
}
