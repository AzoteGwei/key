/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Configuration for the KeY MCP server, usually provided via environment variables
 * or command-line arguments.
 */
public record McpServerConfig(
        Path workspace,
        List<Path> allowedPaths,
        long defaultTimeoutMs,
        long defaultMaxSteps,
        String defaultMaxHeap,
        List<String> allowedSmtSolvers) {

    /**
     * Reads configuration from environment variables.
     */
    public static McpServerConfig fromEnvironment() {
        Path workspace = Paths
                .get(System.getenv().getOrDefault("KEY_MCP_WORKSPACE",
                    System.getProperty("user.dir")))
                .toAbsolutePath();
        List<Path> allowedPaths = parsePathList(
            System.getenv().getOrDefault("KEY_MCP_ALLOWED_PATHS", workspace.toString()));
        long defaultTimeoutMs =
            Long.parseLong(System.getenv().getOrDefault("KEY_MCP_DEFAULT_TIMEOUT_MS", "60000"));
        long defaultMaxSteps =
            Long.parseLong(System.getenv().getOrDefault("KEY_MCP_DEFAULT_MAX_STEPS", "10000"));
        String defaultMaxHeap = System.getenv().getOrDefault("KEY_MCP_DEFAULT_MAX_HEAP", "4g");
        List<String> allowedSmtSolvers =
            parseStringList(System.getenv().getOrDefault("KEY_MCP_SMT_SOLVERS", ""));
        return new McpServerConfig(workspace, allowedPaths, defaultTimeoutMs, defaultMaxSteps,
            defaultMaxHeap,
            allowedSmtSolvers);
    }

    private static List<Path> parsePathList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Paths::get)
                .map(Path::toAbsolutePath)
                .toList();
    }

    private static List<String> parseStringList(String value) {
        if (value == null || value.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
