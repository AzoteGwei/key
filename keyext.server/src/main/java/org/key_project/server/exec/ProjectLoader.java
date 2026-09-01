/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.exec;

import java.nio.file.Path;
import java.util.List;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.Proof;
import de.uka.ilkd.key.proof.io.AbstractProblemLoader;

import org.key_project.server.ServerUserInterfaceControl;
import org.key_project.server.dto.EnvironmentLoaded;
import org.key_project.server.dto.EnvironmentRef;
import org.key_project.server.registry.EnvironmentRegistry;
import org.key_project.server.registry.ProofRegistry;
import org.key_project.server.rpc.KeyErrors;
import org.key_project.server.rpc.RpcErrorCode;
import org.key_project.server.rpc.RpcException;

import org.jspecify.annotations.Nullable;

/**
 * Brings a project, a {@code .key} file or a saved proof into the server.
 *
 * <p>
 * Shared by {@code environment.load} and {@code proof.loadFile} so that both get the same
 * treatment of a partial replay, which is the case that matters. A {@code .proof} file whose
 * replay only half succeeded still produces a proof object, and that object looks like any other:
 * it has a tree, it has open goals, it answers questions. Reporting it as loaded would hand a
 * client something that is not the proof the file describes, and the difference is invisible from
 * the outside.
 */
public final class ProjectLoader {

    private final ServerUserInterfaceControl control;
    private final EnvironmentRegistry environments;
    private final ProofRegistry proofs;

    /**
     * Creates a loader.
     *
     * @param control the control loading runs through
     * @param environments where loaded environments are kept
     * @param proofs where the proofs they bring are kept
     */
    public ProjectLoader(ServerUserInterfaceControl control, EnvironmentRegistry environments,
            ProofRegistry proofs) {
        this.control = control;
        this.environments = environments;
        this.proofs = proofs;
    }

    /**
     * Loads one location and registers what it produced.
     *
     * <p>
     * Runs on the worker thread, as part of a task.
     *
     * @param file what to load
     * @param classPath extra class path entries
     * @param bootClassPath the boot class path, may be {@code null}
     * @param includes additional include files
     * @return the environment, and the proof if the file carried one
     * @throws RpcException when KeY could not load it, or replayed it only in part
     */
    public EnvironmentLoaded load(Path file, List<Path> classPath, @Nullable Path bootClassPath,
            List<Path> includes) {
        AbstractProblemLoader loader;
        try {
            loader = control.load(null, file, classPath, bootClassPath, includes, null, false,
                null);
        } catch (Exception e) {
            throw new RpcException(RpcErrorCode.LOAD_FAILED, "Failed to load " + file,
                KeyErrors.describe(e), e);
        }
        control.drainWarnings();
        requireCompleteReplay(file, loader);

        KeYEnvironment<ServerUserInterfaceControl> environment =
            new KeYEnvironment<>(control, loader.getInitConfig(), loader.getProof(),
                loader.getProofScript(), loader.getResult());
        EnvironmentRef ref = environments.register(file, environment, loader.getProof());

        Proof loadedProof = loader.getProof();
        if (loadedProof == null) {
            return new EnvironmentLoaded(ref.envId(), null);
        }
        return new EnvironmentLoaded(ref.envId(),
            proofs.register(ref.envId(), null, loadedProof));
    }

    /**
     * Refuses a proof that was only partly replayed.
     *
     * <p>
     * KeY reports these as a list of exceptions on the side and hands back the proof regardless.
     * Taking it would mean serving a proof that silently differs from the file it came from — and
     * since the missing steps are exactly the ones that failed, what is missing is what was hard.
     *
     * @param file what was being loaded, for the message
     * @param loader the loader that has just finished
     * @throws RpcException with {@link RpcErrorCode#LOAD_FAILED} when the replay was incomplete
     */
    private static void requireCompleteReplay(Path file, AbstractProblemLoader loader) {
        AbstractProblemLoader.ReplayResult replay = loader.getResult();
        if (replay == null || !replay.hasErrors()) {
            return;
        }
        Throwable first = replay.getErrorList().get(0);
        throw new RpcException(RpcErrorCode.LOAD_FAILED,
            "KeY could not replay all of " + file + ": " + replay.getStatus()
                + " (" + replay.getErrorList().size() + " error(s))",
            KeyErrors.describe(first), first);
    }
}
