/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import org.key_project.server.api.DiagnosticsMethods;
import org.key_project.server.api.EnvironmentMethods;
import org.key_project.server.api.GoalMethods;
import org.key_project.server.api.ProofMethods;
import org.key_project.server.api.ServerMethods;
import org.key_project.server.api.TaskMethods;
import org.key_project.server.dto.TaskHandle;
import org.key_project.server.exec.SerialExecutor;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.registry.Ids;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.registry.TaskRegistry;
import org.key_project.server.registry.TaskState;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.transport.HttpTransport;
import org.key_project.server.transport.SseHub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One running server: the registries, the worker thread and the endpoint.
 *
 * <p>
 * All proof state lives here for as long as the process runs, which is the point of the whole
 * exercise: an agent loads a project once and then iterates against warm state.
 */
public final class KeyServerInstance implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeyServerInstance.class);

    private final String instanceId = Ids.create("inst");
    private final ServerOptions options;

    private final SerialExecutor executor = new SerialExecutor();
    private final ServerUserInterfaceControl control = new ServerUserInterfaceControl();
    private final EnvironmentRegistry environments = new EnvironmentRegistry();
    private final ProofRegistry proofs = new ProofRegistry();
    private final TaskRegistry tasks = new TaskRegistry();

    private final SseHub events;
    private final HttpTransport transport;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Builds an instance and binds its endpoint.
     *
     * @param options the validated startup configuration
     * @throws IOException when the port cannot be bound
     */
    public KeyServerInstance(ServerOptions options) throws IOException {
        this.options = options;

        ObjectMapper mapper = new ObjectMapper();
        this.events = new SseHub(mapper);
        tasks.publishTo(new TaskState.Events() {
            @Override
            public void progressed(TaskHandle handle) {
                events.progressed(handle);
            }

            @Override
            public void finished(TaskHandle handle) {
                events.finished(handle);
            }
        });
        JsonRpcDispatcher dispatcher = new JsonRpcDispatcher(mapper, executor);
        TaskRunner runner = new TaskRunner(executor, tasks, control);

        new ServerMethods(instanceId, options).registerOn(dispatcher);
        new TaskMethods(tasks).registerOn(dispatcher);
        new EnvironmentMethods(options, control, environments, runner, proofs)
                .registerOn(dispatcher);
        new ProofMethods(control, environments, proofs, runner).registerOn(dispatcher);
        new GoalMethods(control, proofs, runner).registerOn(dispatcher);
        new DiagnosticsMethods(proofs, tasks).registerOn(dispatcher);

        this.transport = new HttpTransport(dispatcher, events, options.port(), this::onRequest);
        LOGGER.info("Instance {} exposes {} methods.", instanceId,
            dispatcher.methodNames().size());
    }

    /**
     * The identifier this instance reports as its own.
     *
     * @return the instance identifier
     */
    public String instanceId() {
        return instanceId;
    }

    /**
     * The port the endpoint is bound to.
     *
     * @return the local port
     */
    public int port() {
        return transport.port();
    }

    /** Starts serving requests. */
    public void start() {
        transport.start();
    }

    /**
     * Blocks until the instance is closed.
     *
     * @throws InterruptedException when the waiting thread is interrupted
     */
    public void awaitShutdown() throws InterruptedException {
        stopped.await();
    }

    private void onRequest() {
        // The idle timeout hangs off this in a later milestone.
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOGGER.info("Shutting down instance {}.", instanceId);
        transport.close();
        events.close();
        proofs.closeAll();
        environments.closeAll();
        executor.close();
        stopped.countDown();
    }
}
