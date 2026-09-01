/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import de.uka.ilkd.key.proof.Goal;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.io.ProofBundleSaver;
import de.uka.ilkd.key.proof.io.ProofSaver;
import de.uka.ilkd.key.speclang.Contract;
import de.uka.ilkd.key.util.MiscTools;
import de.uka.ilkd.key.util.ProofStarter;

import org.key_project.prover.engine.ProofSearchInformation;
import org.key_project.server.ProofFacts;
import org.key_project.server.ServerOptions;
import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.AutoModeOutcome;
import org.key_project.server.dto.AutoModeOutcomes;
import org.key_project.server.dto.AutoModeResult;
import org.key_project.server.dto.EnvironmentLoaded;
import org.key_project.server.dto.Ok;
import org.key_project.server.dto.ProofRef;
import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.dto.SavedProof;
import org.key_project.server.dto.TaskKind;
import org.key_project.server.exec.InterruptibleRun;
import org.key_project.server.exec.ProjectLoader;
import org.key_project.server.exec.TaskRunner;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.registry.LoadedEnvironment;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.registry.RegisteredProof;
import org.key_project.server.rpc.Concurrency;
import org.key_project.server.rpc.JsonRpcDispatcher;
import org.key_project.server.rpc.KeyErrors;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;
import org.key_project.server.rpc.RpcMethod;

/** The {@code proof.*} methods: starting proofs, running the search and reading the result. */
public final class ProofMethods {

    private final ServerOptions options;
    private final ServerUserInterfaceControl control;
    private final EnvironmentRegistry environments;
    private final ProofRegistry proofs;
    private final TaskRunner tasks;
    private final ProjectLoader loader;

    /**
     * Creates the handlers.
     *
     * @param options the instance configuration, used to resolve relative paths
     * @param control the control that owns KeY's proof control
     * @param environments where loaded environments are kept
     * @param proofs where started proofs are kept
     * @param tasks used to run proof search off the request thread
     * @param loader brings a saved proof back in
     */
    public ProofMethods(ServerOptions options, ServerUserInterfaceControl control,
            EnvironmentRegistry environments, ProofRegistry proofs, TaskRunner tasks,
            ProjectLoader loader) {
        this.loader = loader;
        this.options = options;
        this.control = control;
        this.environments = environments;
        this.proofs = proofs;
        this.tasks = tasks;
    }

    /**
     * Registers these methods.
     *
     * @param dispatcher the dispatcher to register with
     */
    public void registerOn(JsonRpcDispatcher dispatcher) {
        dispatcher.register(new RpcMethod("proof.start", Concurrency.SERIAL,
            params -> start(params.as(StartProofRequest.class))));
        // Like environment.load, this only queues: the handler returns a task handle at once and
        // the search itself runs on the worker thread.
        dispatcher.register(new RpcMethod("proof.runAuto", Concurrency.INLINE,
            params -> runAuto(params.as(RunAutoRequest.class))));
        dispatcher.register(new RpcMethod("proof.getStatistics", Concurrency.INLINE,
            params -> statistics(params.as(ProofRequest.class))));
        dispatcher.register(new RpcMethod("proof.close", Concurrency.SERIAL,
            params -> close(params.as(ProofRequest.class))));
        // Serialising a proof walks the whole tree, so it must not run while the tree is being
        // rewritten underneath it.
        dispatcher.register(new RpcMethod("proof.save", Concurrency.SERIAL,
            params -> save(params.as(SaveProofRequest.class))));
        // Replaying a saved proof takes as long as the proof did, so this only queues.
        dispatcher.register(new RpcMethod("proof.loadFile", Concurrency.INLINE,
            params -> loadFile(params.as(LoadProofRequest.class))));
    }

    private ProofRef start(StartProofRequest request) {
        LoadedEnvironment environment = environments.require(request.env().envId());
        Contract contract = environment.environment().getSpecificationRepository()
                .getContractByName(request.contractId());
        if (contract == null) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "No such contract in this environment: " + request.contractId()
                    + ". Contract identifiers come from environment.listProofObligations.");
        }
        Proof proof;
        try {
            proof = environment.environment()
                    .createProof(
                        contract.createProofObl(environment.environment().getInitConfig()));
        } catch (Exception e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "KeY could not build a proof obligation for " + request.contractId(), null, e);
        }
        return proofs.register(environment.envId(), contract.getName(), proof);
    }

    private Object runAuto(RunAutoRequest request) {
        RegisteredProof registered = proofs.require(request.proof().proofId());
        Long timeoutMs = request.timeoutMs();
        if (timeoutMs != null && timeoutMs <= 0) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS,
                "timeoutMs must be positive, got " + timeoutMs);
        }
        Proof proof = registered.proof();
        if (proof.isErroneous()) {
            // KeY refuses to search on a proof it marked erroneous, and does so silently. Saying
            // so here keeps a run that never happened from looking like one that found nothing.
            throw new RpcException(RpcErrorCode.PROOF_NOT_FOUND,
                "KeY marked proof " + registered.proofId()
                    + " erroneous; it will not run the automatic search on it");
        }
        ProofRef ref = new ProofRef(registered.proofId());
        return tasks.launchExclusive(TaskKind.AUTO, ref, task -> {
            // ProofStarter is what KeY's own proof control runs behind its auto-mode thread; the
            // difference is that this runs it here, where a failure is still ours to report.
            ProofStarter starter = new ProofStarter(control, false);
            starter.init(proof);
            InterruptibleRun.Result<ProofSearchInformation<Proof, Goal>> run =
                InterruptibleRun.run(task, timeoutMs, starter::start);

            // KeY's own account of why it stopped, not an inference from how long it took. The
            // difference between running out of ideas and running out of budget is the whole
            // value of this field, and only the prover knows which.
            AutoModeOutcome outcome =
                AutoModeOutcomes.of(run.value().stopReason(), run.timedOut());
            registered.searchEnded(outcome);
            // Read afterwards, from the proof itself: whether anything was achieved is KeY's
            // answer, not a conclusion drawn from the search having returned.
            return new AutoModeResult(ref, outcome, ProofFacts.describe(proof));
        });
    }

    private Object statistics(ProofRequest request) {
        return ProofFacts.describe(proofs.require(request.proof().proofId()).proof());
    }

    private Object close(ProofRequest request) {
        proofs.close(request.proof().proofId());
        return new Ok(true);
    }

    /**
     * Replays a saved proof.
     *
     * <p>
     * The other half of {@code proof.save}: a result nobody can load back is not much of a
     * result. The file is loaded into an environment of its own, so a proof written from one
     * project can be checked without that project already being open.
     *
     * @param request which file
     * @return the task to poll
     */
    private Object loadFile(LoadProofRequest request) {
        Path file = resolve(request.path());
        if (!Files.exists(file)) {
            throw new RpcException(RpcErrorCode.LOAD_FAILED, "No such file: " + file,
                RpcErrorData.of("Paths are resolved against the workspace " + options.workspace()),
                null);
        }
        return tasks.launch(TaskKind.REPLAY, null, task -> {
            EnvironmentLoaded loaded = loader.load(file, List.of(), null, List.of());
            if (loaded.proof() == null) {
                // The file loaded, and there is no proof in it. Answering with an environment
                // would be answering a question that was not asked.
                throw new RpcException(RpcErrorCode.LOAD_FAILED,
                    file + " loaded but carries no proof; use environment.load for a project or "
                        + "a problem file");
            }
            return loaded;
        });
    }

    /**
     * Writes a proof to disk.
     *
     * <p>
     * Until this exists a proof lives only as long as the server does, which makes every result
     * an agent produces unreviewable: there is nothing to read, re-check or commit. What is
     * written is KeY's own format, so the file can be loaded back by KeY itself rather than only
     * by this server.
     *
     * @param request which proof, and where
     * @return where it was written
     */
    private Object save(SaveProofRequest request) {
        RegisteredProof registered = proofs.require(request.proof().proofId());
        Path file = destination(registered, request);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
        } catch (IOException e) {
            throw new RpcException(RpcErrorCode.SAVE_FAILED,
                "Could not create the directory for " + file, KeyErrors.describe(e), e);
        }

        // KeY's saver reports a failure by RETURNING it, and returns null when it worked. Nothing
        // is thrown, so a caller that does not look at this value would report a proof as saved
        // that is not on disk anywhere. This is the whole reason SAVE_FAILED exists.
        String failure = request.bundle() ? new ProofBundleSaver(registered.proof(), file).save()
                : new ProofSaver(registered.proof(), file).save();
        if (failure != null) {
            throw new RpcException(RpcErrorCode.SAVE_FAILED,
                "KeY could not write " + file + ": " + failure);
        }

        long bytes;
        try {
            bytes = Files.size(file);
        } catch (IOException e) {
            // The saver said it worked and the file is not there to measure. Believing the saver
            // over the filesystem would be the wrong way round.
            throw new RpcException(RpcErrorCode.SAVE_FAILED,
                "KeY reported success but " + file + " cannot be read back",
                KeyErrors.describe(e), e);
        }
        // Mirrors what KeY's own UI does after a save, so a later save with no path goes back to
        // the same place.
        registered.proof().setProofFile(file);
        return new SavedProof(file.toString(), bytes);
    }

    /**
     * Works out where a proof should be written.
     *
     * @param registered the proof being saved
     * @param request the client's wishes
     * @return an absolute path
     */
    private Path destination(RegisteredProof registered, SaveProofRequest request) {
        String extension = request.bundle() ? ".zproof" : ".proof";
        if (request.path() != null) {
            return resolve(request.path());
        }
        Path previous = registered.proof().getProofFile();
        if (previous != null && previous.toString().endsWith(extension)) {
            return previous;
        }
        // KeY names its proofs after the obligation, which is descriptive but full of characters
        // a file system will not take.
        String name = MiscTools.toValidFileName(registered.proof().name().toString());
        return options.workspace().resolve(name + extension);
    }

    private Path resolve(String raw) {
        try {
            return options.resolve(raw);
        } catch (IllegalArgumentException e) {
            throw new RpcException(RpcErrorCode.INVALID_PARAMS, e.getMessage(), null, e);
        }
    }
}
