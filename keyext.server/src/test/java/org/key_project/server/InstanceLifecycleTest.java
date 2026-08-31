/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.key_project.server.instance.InstanceRegistry;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real instance announcing itself, and taking itself away again.
 *
 * <p>
 * The record has to describe the instance well enough that a client which knows only the
 * workspace can connect, and it has to be gone once the instance is. Both are checked against a
 * running server rather than the registry in isolation, because what goes wrong here is the
 * wiring: a port recorded before it was resolved, a record written but never withdrawn.
 */
class InstanceLifecycleTest {

    @TempDir
    private Path workspace;

    @Test
    void publishesEnoughForAClientToConnectAndWithdrawsItOnShutdown() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace);
        String instanceId;

        try (KeyServerInstance instance =
            new KeyServerInstance(new ServerOptions(0, workspace, 0, 1))) {
            instance.start();
            instanceId = instance.instanceId();

            List<InstanceRegistry.Instance> published = registry.listWorkspace();
            assertThat(published).hasSize(1);
            InstanceRegistry.Instance record = published.get(0);

            assertThat(record.record().instanceId()).isEqualTo(instanceId);
            assertThat(record.alive()).isTrue();
            assertThat(record.record().pid()).isEqualTo(ProcessHandle.current().pid());
            // The port has to be the one actually bound. With --port 0 the OS picks it, so a
            // record written from the requested port would send every client to port zero.
            assertThat(record.record().port()).isEqualTo(instance.port());
            assertThat(record.record().port()).isNotZero();

            // Everything a client needs, checked by using it.
            JsonNode version = new RpcTestClient(record.record().port())
                    .result("server.version", null);
            assertThat(version.get("instanceId").asText()).isEqualTo(instanceId);
            assertThat(version.get("apiVersion").asText())
                    .isEqualTo(record.record().apiVersion());
            assertThat(version.get("keyVersion").asText()).isEqualTo(record.record().keyVersion());
            assertThat(version.get("threads").asInt()).isEqualTo(record.record().threads());
        }

        assertThat(registry.listWorkspace()).isEmpty();
        assertThat(registry.list())
                .noneMatch(each -> each.record().instanceId().equals(instanceId));
    }

    @Test
    void anIdleInstanceShutsItselfDownAndTidiesUp() throws Exception {
        InstanceRegistry registry = new InstanceRegistry(workspace);

        try (KeyServerInstance instance =
            new KeyServerInstance(new ServerOptions(0, workspace, 1, 1))) {
            instance.start();
            assertThat(registry.listWorkspace()).hasSize(1);

            // Nobody calls anything. Forgetting to stop a server is the ordinary case in an agent
            // workflow, so the server has to handle being forgotten.
            instance.awaitShutdown();
        }

        assertThat(registry.listWorkspace()).isEmpty();
    }

    @Test
    void anInstanceBeingUsedIsNotShutDownUnderneathItsClient() throws Exception {
        try (KeyServerInstance instance =
            new KeyServerInstance(new ServerOptions(0, workspace, 1, 1))) {
            instance.start();
            RpcTestClient client = new RpcTestClient(instance.port());

            // Loading a project takes far longer than this timeout and needs no requests while it
            // runs. A server that counted that as idleness would kill the work it was asked to
            // do.
            Path fixture = Path.of("src/test/resources/fixtures/adder").toAbsolutePath();
            String taskId = client.result("environment.load",
                "{\"path\":\"" + fixture.toString().replace("\\", "\\\\") + "\"}").get("taskId")
                    .asText();

            JsonNode task = null;
            long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
            while (System.nanoTime() < deadline) {
                task = client.result("task.get", "{\"taskId\":\"" + taskId + "\"}");
                if (!"PENDING".equals(task.get("status").asText())
                        && !"RUNNING".equals(task.get("status").asText())) {
                    break;
                }
                Thread.sleep(200);
            }
            assertThat(task).isNotNull();
            assertThat(task.get("status").asText()).isEqualTo("SUCCEEDED");
        }
    }
}
