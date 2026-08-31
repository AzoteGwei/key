/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * Identity and capabilities of the instance a client just connected to.
 *
 * @param apiVersion semantic version of the RPC surface, matching the OpenRPC document
 * @param keyVersion version of the embedded KeY
 * @param instanceId identifier of this running instance
 * @param threads prover worker count this instance was started with
 */
public record ServerVersion(String apiVersion, String keyVersion, String instanceId, int threads) {
}
