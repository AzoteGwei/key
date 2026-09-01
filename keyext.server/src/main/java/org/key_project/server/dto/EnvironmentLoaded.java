/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What a finished {@link TaskKind#LOAD} task produced.
 *
 * <p>
 * A {@code .proof} or {@code .zproof} file brings a proof with it, and without a reference to it
 * that proof would be loaded and then unreachable — the environment would report holding one and
 * offer no way to ask it anything. The field is absent for a project or a {@code .key} file that
 * declares no proof.
 *
 * @param envId the environment, to be passed back as {@code {"env": {"envId": …}}}
 * @param proof the proof the file carried, absent when it carried none
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EnvironmentLoaded(String envId, ProofRef proof) {
}
