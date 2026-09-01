/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.api;

import org.key_project.server.dto.ProofRef;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Parameters of {@code proof.prune}.
 *
 * @param proof the proof to cut back
 * @param nodeId the node to cut back to, as reported by {@code goal.list}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PruneRequest(ProofRef proof, int nodeId) {
}
