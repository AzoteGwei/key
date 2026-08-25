/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Validates paths against a whitelist.
 */
public final class PathValidator {
    private PathValidator() {
    }

    /**
     * Resolves a path string against the workspace and checks it against the whitelist.
     *
     * @param raw the raw path string
     * @param workspace the workspace root
     * @param allowedPaths the whitelist
     * @return the resolved, normalized absolute path
     * @throws McpSecurityException if the path is outside the whitelist
     */
    public static Path resolveAndValidate(String raw, Path workspace, List<Path> allowedPaths) {
        Path path = Paths.get(raw);
        if (!path.isAbsolute()) {
            path = workspace.resolve(path);
        }
        path = path.toAbsolutePath().normalize();
        for (Path allowed : allowedPaths) {
            if (path.startsWith(allowed.normalize())) {
                return path;
            }
        }
        throw new McpSecurityException("Path not allowed: " + raw);
    }
}
