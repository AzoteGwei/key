/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Tests for the parsing and validation of the instance startup options. */
class ServerOptionsTest {

    private static ServerOptions parse(String... args) {
        Main main = new Main();
        new CommandLine(main).parseArgs(args);
        return main.toOptions();
    }

    @Test
    void defaultsPinTheSingleThreadedProverAndEnableTheIdleTimeout() {
        ServerOptions options = parse();

        assertThat(options.port()).isZero();
        assertThat(options.threads()).isEqualTo(1);
        assertThat(options.idleTimeoutSeconds())
                .isEqualTo(ServerOptions.DEFAULT_IDLE_TIMEOUT_SECONDS);
        assertThat(options.hasIdleTimeout()).isTrue();
    }

    @Test
    void parsesAllOptions(@TempDir Path workspace) {
        ServerOptions options = parse("--port", "8899", "--workspace", workspace.toString(),
            "--idle-timeout", "60", "--threads", "4");

        assertThat(options.port()).isEqualTo(8899);
        assertThat(options.workspace()).isEqualTo(workspace.toAbsolutePath().normalize());
        assertThat(options.idleTimeoutSeconds()).isEqualTo(60);
        assertThat(options.threads()).isEqualTo(4);
    }

    @Test
    void workspaceIsNormalisedToAnAbsolutePath(@TempDir Path workspace) {
        Path detour = workspace.resolve("sub").resolve("..");

        ServerOptions options = parse("--workspace", detour.toString());

        assertThat(options.workspace()).isAbsolute()
                .isEqualTo(workspace.toAbsolutePath().normalize());
    }

    @Test
    void relativeWorkspaceIsResolvedAgainstTheWorkingDirectory() {
        ServerOptions options = parse();

        assertThat(options.workspace()).isAbsolute()
                .isEqualTo(Path.of("").toAbsolutePath().normalize());
    }

    @Test
    void zeroIdleTimeoutDisablesTheTimeout() {
        assertThat(parse("--idle-timeout", "0").hasIdleTimeout()).isFalse();
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> parse("--port", "70000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> parse("--threads", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threads");
        assertThatThrownBy(() -> parse("--idle-timeout", "-1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idle timeout");
    }
}
