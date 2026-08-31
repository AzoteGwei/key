/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.nio.file.Path;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Entry point of the headless KeY server.
 *
 * <p>
 * The server holds all proof state in a long-lived JVM and speaks JSON-RPC 2.0 over HTTP on
 * {@code 127.0.0.1}, so that an agent can load a project once and then iterate against warm state
 * instead of paying project loading on every attempt.
 *
 * <p>
 * <b>The server never decides that a proof succeeded.</b> The only legitimate source of that
 * judgement is KeY's own {@code Proof.closed()}. No error path, no timeout, no "the macro finished"
 * may be reported as a closed proof.
 */
@Command(name = "keyext-server", mixinStandardHelpOptions = true,
    description = "Headless JSON-RPC server exposing KeY to automated agents.")
public final class Main implements Callable<Integer> {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);

    @Option(names = "--port",
        description = "TCP port to bind on 127.0.0.1; 0 lets the OS pick a free port "
            + "(default: ${DEFAULT-VALUE})")
    private int port = 0;

    @Option(names = "--workspace",
        description = "Directory this instance is anchored to (default: the current directory)")
    private Path workspace = Path.of("");

    @Option(names = "--idle-timeout",
        description = "Shut down after this many seconds without a request; 0 disables the "
            + "timeout (default: ${DEFAULT-VALUE}). Leaving servers running is a real problem in "
            + "agent workflows, so this is on by default.")
    private int idleTimeoutSeconds = ServerOptions.DEFAULT_IDLE_TIMEOUT_SECONDS;

    @Option(names = "--threads",
        description = "Number of prover worker threads; 1 pins the single-threaded prover "
            + "(default: ${DEFAULT-VALUE}). This is fixed for the instance because it is backed "
            + "by a JVM-global switch; run several instances to prove in parallel.")
    private int threads = 1;

    /**
     * Builds the validated startup configuration from the parsed command line.
     *
     * @return the options this instance was asked to run with
     */
    ServerOptions toOptions() {
        return new ServerOptions(port, workspace, idleTimeoutSeconds, threads);
    }

    @Override
    public Integer call() {
        ServerOptions options = toOptions();
        int workers = ProverMode.apply(options.threads());
        LOGGER.info("Workspace {}, port {}, idle timeout {}s, {} prover worker(s).",
            options.workspace(), options.port(), options.idleTimeoutSeconds(), workers);
        // The transport, registries and RPC dispatcher are added in the next milestone.
        LOGGER.error("The RPC server is not implemented yet.");
        return 2;
    }

    /**
     * Starts the server.
     *
     * @param args command line arguments
     */
    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }
}
