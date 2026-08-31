/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import de.uka.ilkd.key.control.AbstractUserInterfaceControl;
import de.uka.ilkd.key.control.DefaultProofControl;
import de.uka.ilkd.key.control.TermLabelVisibilityManager;
import de.uka.ilkd.key.proof.ProofAggregate;
import de.uka.ilkd.key.proof.init.InitConfig;
import de.uka.ilkd.key.proof.init.ProofOblInput;
import de.uka.ilkd.key.proof.mgt.ProofEnvironment;
import de.uka.ilkd.key.speclang.PositionedString;

import org.key_project.prover.engine.TaskFinishedInfo;
import org.key_project.prover.engine.TaskStartedInfo;
import org.key_project.server.registry.TaskState;
import org.key_project.util.collection.ImmutableSet;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The headless {@code UserInterfaceControl} the server proves through.
 *
 * <p>
 * This extends {@link AbstractUserInterfaceControl} rather than the mediator-based controls, which
 * live in {@code key.ui}: they require a {@code KeYMediator} that is bound to Swing and to a single
 * "current proof", neither of which fits a server holding many sessions. The registration behaviour
 * below mirrors {@code DefaultUserInterfaceControl}, which is the existing headless implementation.
 *
 * <p>
 * Where the console UI would print a progress bar, this forwards progress to the task the worker
 * thread is currently running. Because all state-changing work is serialised on one thread, at most
 * one task is ever current.
 */
public final class ServerUserInterfaceControl extends AbstractUserInterfaceControl {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerUserInterfaceControl.class);

    private final TermLabelVisibilityManager termLabelVisibilityManager =
        new TermLabelVisibilityManager();

    private final DefaultProofControl proofControl = new DefaultProofControl(this, this);

    /** The task whose work the single worker thread is currently executing. */
    private final AtomicReference<@Nullable TaskState> currentTask = new AtomicReference<>();

    /** Warnings KeY reported during the load that is currently in progress. */
    private final List<PositionedString> pendingWarnings = new ArrayList<>();

    /**
     * Directs progress reporting at the given task for the duration of one operation.
     *
     * @param task the task to report against, or {@code null} to stop reporting
     */
    public void setCurrentTask(@Nullable TaskState task) {
        currentTask.set(task);
    }

    /**
     * Takes the warnings collected since the last call and clears them.
     *
     * @return the warnings KeY reported, possibly empty
     */
    public synchronized List<PositionedString> drainWarnings() {
        List<PositionedString> drained = List.copyOf(pendingWarnings);
        pendingWarnings.clear();
        return drained;
    }

    @Override
    public DefaultProofControl getProofControl() {
        return proofControl;
    }

    @Override
    public TermLabelVisibilityManager getTermLabelVisibilityManager() {
        return termLabelVisibilityManager;
    }

    @Override
    public ProofEnvironment createProofEnvironmentAndRegisterProof(ProofOblInput proofOblInput,
            ProofAggregate proofList, InitConfig initConfig) {
        // Mirrors DefaultUserInterfaceControl: the specification repository has to know about the
        // proof, and the aggregate has to be tied to an environment object.
        initConfig.getServices().getSpecificationRepository().registerProof(proofOblInput,
            proofList.getFirstProof());
        ProofEnvironment environment = new ProofEnvironment(initConfig);
        environment.registerProof(proofOblInput, proofList);
        return environment;
    }

    @Override
    public boolean selectProofObligation(InitConfig initConfig) {
        // A .key file that does not name a proof obligation cannot be resolved without asking, and
        // there is nobody to ask. Reporting "not selected" lets the loader fail cleanly instead of
        // guessing one.
        return false;
    }

    @Override
    public void taskStarted(TaskStartedInfo info) {
        TaskState task = currentTask.get();
        if (task != null) {
            task.progress(new Progress(info.message(), 0, info.size()));
        }
        super.taskStarted(info);
    }

    @Override
    public void taskProgress(int position) {
        TaskState task = currentTask.get();
        if (task != null) {
            task.progress(new Progress(null, position, 0));
        }
        super.taskProgress(position);
    }

    @Override
    public void taskFinished(TaskFinishedInfo info) {
        // Deliberately no status conclusion here: whether the work succeeded is decided by the
        // caller that owns the task, and whether a proof closed is decided only by Proof.closed().
        super.taskFinished(info);
    }

    @Override
    public synchronized void reportWarnings(ImmutableSet<PositionedString> warnings) {
        for (PositionedString warning : warnings) {
            pendingWarnings.add(warning);
        }
    }

    @Override
    public void reportException(Object sender, ProofOblInput input, Exception e) {
        LOGGER.warn("KeY reported an exception while processing {}", input, e);
    }

    @Override
    public void reportStatus(Object sender, String status, int progress) {
        reportStatus(sender, status);
    }

    @Override
    public void reportStatus(Object sender, String status) {
        TaskState task = currentTask.get();
        if (task != null) {
            task.progress(new Progress(status, 0, 0));
        }
    }

    @Override
    public void progressStarted(Object sender) {
        // no progress bar to open
    }

    @Override
    public void progressStopped(Object sender) {
        // no progress bar to close
    }

    @Override
    public void resetStatus(Object sender) {
        TaskState task = currentTask.get();
        if (task != null) {
            task.progress(null);
        }
    }

    @Override
    public void setProgress(int progress) {
        taskProgress(progress);
    }

    @Override
    public void setMaximum(int maximum) {
        // the size is already carried by TaskStartedInfo
    }

    @Override
    public void registerProofAggregate(ProofAggregate pa) {
        // proofs are registered by the caller that asked for them
    }

    /**
     * Progress detail published on a running task.
     *
     * @param message what KeY says it is doing, may be {@code null}
     * @param position steps completed so far
     * @param size total steps expected, {@code 0} when unknown
     */
    public record Progress(@Nullable String message, int position, int size) {
    }
}
