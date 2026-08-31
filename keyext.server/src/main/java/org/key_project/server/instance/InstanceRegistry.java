/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.instance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where running instances announce themselves, and how leftovers are recognised.
 *
 * <p>
 * A record is written in two places: under the workspace, so that a client working in a project
 * can find the server for that project without knowing anything else, and under the user's state
 * directory, so that one client can see every instance this user is running. Both are written
 * because neither covers the other case: a global registry cannot be reached from inside a
 * container that only shares the project directory, and a per-workspace file cannot answer "what
 * have I left running".
 *
 * <p>
 * Files are removed on a clean shutdown. They will nonetheless be left behind — a kill, a crash, a
 * machine losing power — so a reader must never assume a record means a live server. That is what
 * {@link Instance#alive()} is for, and why the record carries a pid.
 */
public final class InstanceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(InstanceRegistry.class);
    private static final String WORKSPACE_DIRECTORY = ".keyext-server";
    private static final String STATE_DIRECTORY = "keyext-server";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path workspaceDirectory;
    private final Path stateDirectory;

    /**
     * Creates a registry writing to the standard locations.
     *
     * @param workspace the directory the instance is anchored to
     */
    public InstanceRegistry(Path workspace) {
        this(workspace.resolve(WORKSPACE_DIRECTORY), userStateDirectory());
    }

    /**
     * Creates a registry writing to given locations, for tests.
     *
     * @param workspaceDirectory where to write the per-workspace record
     * @param stateDirectory where to write the per-user record
     */
    public InstanceRegistry(Path workspaceDirectory, Path stateDirectory) {
        this.workspaceDirectory = workspaceDirectory;
        this.stateDirectory = stateDirectory;
    }

    /**
     * The directory instance records are kept in for this user.
     *
     * <p>
     * {@code XDG_STATE_HOME} when it is set, otherwise the {@code ~/.local/state} the XDG
     * specification names as its default. State, not config or cache: these files describe what is
     * running now, they are neither settings a user edits nor data that can be regenerated.
     *
     * @return the per-user registry directory
     */
    public static Path userStateDirectory() {
        String configured = System.getenv("XDG_STATE_HOME");
        Path base = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".local", "state")
                : Path.of(configured);
        return base.resolve(STATE_DIRECTORY).resolve("instances");
    }

    /**
     * Publishes a record for a starting instance.
     *
     * <p>
     * Failing to write is logged and not raised: a server that works is more use than one that
     * refused to start because a directory was read-only, and a client that cannot find it can
     * still be given the port.
     *
     * @param record what to publish
     */
    public void register(InstanceRecord record) {
        write(workspaceDirectory.resolve(fileName(record.instanceId())), record);
        write(stateDirectory.resolve(fileName(record.instanceId())), record);
    }

    /**
     * Removes the records of an instance that is shutting down.
     *
     * @param instanceId the instance to withdraw
     */
    public void unregister(String instanceId) {
        delete(workspaceDirectory.resolve(fileName(instanceId)));
        delete(stateDirectory.resolve(fileName(instanceId)));
    }

    /**
     * Reads every record in the per-user registry.
     *
     * @return what is registered, newest first, each marked with whether its process still exists
     */
    public List<Instance> list() {
        return listIn(stateDirectory);
    }

    /**
     * Reads every record in the registry of one workspace.
     *
     * @return what that workspace has registered, newest first
     */
    public List<Instance> listWorkspace() {
        return listIn(workspaceDirectory);
    }

    private static List<Instance> listIn(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Instance> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(each -> each.getFileName().toString().endsWith(".json"))
                    .toList()) {
                try {
                    InstanceRecord record = MAPPER.readValue(file.toFile(), InstanceRecord.class);
                    found.add(new Instance(record, file, isAlive(record.pid())));
                } catch (IOException e) {
                    // A half-written or hand-edited file. Skipping it beats failing the whole
                    // listing, which would hide every healthy instance behind one bad file.
                    LOGGER.warn("Ignoring unreadable instance record {}", file, e);
                }
            }
        } catch (IOException e) {
            LOGGER.warn("Could not read the instance registry at {}", directory, e);
            return List.of();
        }
        found.sort(Comparator.comparing((Instance each) -> each.record().startedAt()).reversed());
        return List.copyOf(found);
    }

    /**
     * Whether a process with that id currently exists.
     *
     * <p>
     * This is a hint, not a guarantee. Process ids are reused, so a very old record can point at
     * an unrelated process that happens to have the same number. A client that needs certainty
     * asks the port for {@code server.version} and compares the instance id; this is the cheap
     * check that keeps a listing from being mostly rubbish.
     *
     * @param pid the process id from a record
     * @return {@code true} when a process with that id exists
     */
    private static boolean isAlive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static String fileName(String instanceId) {
        return instanceId + ".json";
    }

    private void write(Path file, InstanceRecord record) {
        try {
            Files.createDirectories(file.getParent());
            // Written to a neighbour and moved into place, so a reader never sees half a record.
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), record);
            Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not publish the instance record at {}", file, e);
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOGGER.warn("Could not remove the instance record at {}", file, e);
        }
    }

    /**
     * One record found in the registry, with what could be learned about it.
     *
     * @param record what the instance published
     * @param file where the record was found, so a caller can clean it up
     * @param alive whether a process with the recorded id still exists
     */
    public record Instance(InstanceRecord record, Path file, boolean alive) {

        /**
         * Whether this record is a leftover from an instance that is gone.
         *
         * @return {@code true} when no process holds the recorded id any more
         */
        public boolean stale() {
            return !alive;
        }
    }
}
