/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.nio.file.Path;

import de.uka.ilkd.key.control.KeYEnvironment;
import de.uka.ilkd.key.proof.Proof;

import org.key_project.server.ServerUserInterfaceControl;

import org.jspecify.annotations.Nullable;

/**
 * One loaded project, together with what the server needs to describe it.
 *
 * @param envId opaque identifier issued to clients
 * @param source location the project was loaded from
 * @param environment the KeY environment holding the {@code InitConfig} and {@code Services}
 * @param loadedProof the proof a loaded {@code .proof} file produced, {@code null} for projects
 */
public record LoadedEnvironment(String envId, Path source,
        KeYEnvironment<ServerUserInterfaceControl> environment, @Nullable Proof loadedProof) {

    /** Releases the KeY resources held by this environment. */
    public void dispose() {
        environment.dispose();
    }
}
