/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Starts instances for tests, anchored somewhere they cannot leave anything behind. */
final class TestServer {

    private TestServer() {
    }

    /**
     * Starts a running instance with the idle timeout off and a workspace of its own.
     *
     * <p>
     * A real workspace matters even for tests that never resolve a relative path: an instance
     * publishes a record under its workspace, and a test that used the module directory would
     * write into the source tree.
     *
     * @return the started instance, which the caller closes
     * @throws IOException when the port cannot be bound or the workspace cannot be created
     */
    static KeyServerInstance start() throws IOException {
        Path workspace = Files.createTempDirectory("keyext-server-test");
        workspace.toFile().deleteOnExit();
        KeyServerInstance instance = new KeyServerInstance(new ServerOptions(0, workspace, 0, 1));
        instance.start();
        return instance;
    }
}
