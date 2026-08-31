/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.io.AbstractProblemLoader;

import org.key_project.server.ServerOptions;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.KeyErrors;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

import org.jspecify.annotations.Nullable;

/** The {@code environment.*} methods: loading and releasing projects. */
public final class EnvironmentMethods {

    private final ServerOptions options;
    private final ServerUserInterfaceControl control;
    private final EnvironmentRegistry environments;
    private final TaskRunner tasks;
    private final EnvironmentRegistry.ProofCounter proofCounter;

    /**
     * Creates the handlers.
     *
     * @param options the instance configuration, used to resolve relative paths
     * @param control the control that loading runs through
     * @param environments where loaded environments are kept
     * @param tasks used to run loading off the request thread
     * @param proofCounter tells how many proofs belong to an environment
     */
    public EnvironmentMethods(ServerOptions options, ServerUserInterfaceControl control,
            EnvironmentRegistry environments, TaskRunner tasks,
            EnvironmentRegistry.ProofCounter proofCounter) {
        this.options = options;
        this.control = control;
        this.environments = environments;
        this.tasks = tasks;
        this.proofCounter = proofCounter;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        // Loading is INLINE because the handler only queues work: loading a real Java project takes
        // far longer than a request should, so it answers with a task handle straight away.
        dispatcher.register(new RpcMethod("environment.load", Concurrency.INLINE,
            params -> load(params.as(LoadRequest.class))));
        dispatcher.register(new RpcMethod("environment.list", Concurrency.INLINE,
            params -> environments.list(proofCounter)));
        dispatcher.register(new RpcMethod("environment.close", Concurrency.SERIAL,
            params -> close(params.as(EnvironmentRequest.class))));
    }

    private Object load(LoadRequest request) {
        Path file = resolve(request.path());
        if (!Files.exists(file)) {
            throw new RpcException(RpcErrorCode.LOAD_FAILED, "No such file or directory: " + file,
                RpcErrorData.of("Paths are resolved against the workspace " + options.workspace()),
                null);
        }
        List<Path> classPath = resolveAll(request.classpath());
        Path bootClassPath = request.bootClassPath() == null ? null
                : resolve(request.bootClassPath());
        List<Path> includes = resolveAll(request.includes());

        return tasks.launch(TaskKind.LOAD, null, () -> {
            AbstractProblemLoader loader;
            try {
                loader = control.load(null, file, classPath, bootClassPath, includes, null, false,
                    null);
            } catch (Exception e) {
                throw new RpcException(RpcErrorCode.LOAD_FAILED, "Failed to load " + file,
                    KeyErrors.describe(e), e);
            }
            control.drainWarnings();
            KeYEnvironment<ServerUserInterfaceControl> environment =
                new KeYEnvironment<>(control, loader.getInitConfig(), loader.getProof(),
                    loader.getProofScript(), loader.getResult());
            return environments.register(file, environment, loader.getProof());
        });
    }

    private Object close(EnvironmentRequest request) {
        environments.close(request.env().envId());
        return new Ok(true);
    }

    /**
     * Resolves a client-supplied path.
     *
     * <p>
     * Relative paths are taken against the instance workspace. Absolute paths are honoured as
     * given: the workspace anchors an instance, it does not confine it, and a client that can reach
     * the loopback port can already read these files anyway.
     *
     * @param raw the path as the client wrote it
     * @return the resolved absolute path
     */
    private Path resolve(String raw) {
        try {
            Path path = Path.of(raw);
            return (path.isAbsolute() ? path : options.workspace().resolve(path)).normalize();
        } catch (InvalidPathException e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS, "Not a usable path: " + raw, null,
                e);
        }
    }

    private List<Path> resolveAll(@Nullable List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>(raw.size());
        for (String entry : raw) {
            paths.add(resolve(entry));
        }
        return paths;
    }

    /**
     * The plain acknowledgement several methods return.
     *
     * @param ok always {@code true}; a failure arrives as an error object instead
     */
    public record Ok(boolean ok) {
    }
}
