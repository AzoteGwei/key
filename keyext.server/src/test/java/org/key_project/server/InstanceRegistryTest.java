/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.key_project.server.instance.InstanceRecord;
import org.key_project.server.instance.InstanceRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Finding a server, and recognising one that is no longer there.
 *
 * <p>
 * A record on disk is a claim, not a fact. Processes are killed, machines lose power, and a
 * registry that reported every file it found as a running server would send clients at ports that
 * answer nothing. So the interesting cases here are the leftovers.
 */
class InstanceRegistryTest {

    @TempDir
    private Path workspace;

    @TempDir
    private Path state;

    @Test
    void aRunningInstanceIsPublishedWhereBothKindsOfClientLook() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace, state);

        registry.register(record("inst-alive", ProcessHandle.current().pid()));

        // Under the workspace, for a client that knows the project but not the port; and under
        // the user's state directory, for a client asking what it has left running.
        assertThat(registry.listWorkspace()).hasSize(1);
        assertThat(registry.list()).hasSize(1);

        InstanceRegistry.Instance found = registry.list().get(0);
        assertThat(found.record().instanceId()).isEqualTo("inst-alive");
        assertThat(found.record().port()).isEqualTo(8899);
        assertThat(found.record().workspacePath()).isEqualTo(workspace.toString());
        assertThat(found.alive()).isTrue();
        assertThat(found.stale()).isFalse();
    }

    @Test
    void aCleanShutdownLeavesNothingBehind() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace, state);
        registry.register(record("inst-tidy", ProcessHandle.current().pid()));

        registry.unregister("inst-tidy");

        assertThat(registry.list()).isEmpty();
        assertThat(registry.listWorkspace()).isEmpty();
    }

    @Test
    void aRecordFromAProcessThatIsGoneIsReportedAsStale() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace, state);
        // A process id that cannot be running: pid 0 is never a live user process, and the JVM's
        // own handle lookup returns nothing for it.
        registry.register(record("inst-crashed", 0));

        List<InstanceRegistry.Instance> found = registry.list();

        assertThat(found).hasSize(1);
        assertThat(found.get(0).stale()).isTrue();
        // The file is named so the caller can clean it up, rather than being told only that
        // something somewhere is stale.
        assertThat(found.get(0).file()).exists();
        assertThat(found.get(0).file().getFileName().toString()).isEqualTo("inst-crashed.json");
    }

    @Test
    void oneUnreadableFileDoesNotHideTheHealthyOnes() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace, state);
        registry.register(record("inst-good", ProcessHandle.current().pid()));
        Files.writeString(state.resolve("inst-garbage.json"), "{ this is not json");

        // A half-written or hand-edited file must cost its own entry, not the whole listing.
        assertThat(registry.list()).hasSize(1);
        assertThat(registry.list().get(0).record().instanceId()).isEqualTo("inst-good");
    }

    @Test
    void theRegistryGoesWhereTheEnvironmentSaysOnEveryPlatform(@TempDir Path chosen) {
        String previous = System.getenv("XDG_STATE_HOME");
        assumeTrue(previous != null, "the build points this at the build directory");

        // Honoured everywhere, not only where the convention comes from, so a caller that needs
        // to say where can always say it — which is what lets these tests run without writing
        // into the developer's own home directory.
        assertThat(InstanceRegistry.userStateDirectory()).startsWith(Path.of(previous));
        assertThat(InstanceRegistry.userStateDirectory().getFileName())
                .isEqualTo(Path.of("instances"));
    }

    @Test
    void anEmptyOrAbsentRegistryIsAnEmptyList() {
        InstanceRegistry registry =
            new InstanceRegistry(workspace.resolve("nope"), state.resolve("nope"));

        assertThat(registry.list()).isEmpty();
        assertThat(registry.listWorkspace()).isEmpty();
    }

    private InstanceRecord record(String instanceId, long pid) {
        return new InstanceRecord(instanceId, pid, "127.0.0.1", 8899, workspace.toString(),
            ApiVersion.CURRENT, "3.1.0-dev", 1, Instant.now().toString());
    }
}
