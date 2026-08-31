/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * What an environment is and how much is going on in it.
 *
 * @param envId opaque environment identifier
 * @param path location this environment was loaded from
 * @param proofCount number of proofs currently registered against it
 */
public record EnvironmentSummary(String envId, String path, int proofCount) {
}
