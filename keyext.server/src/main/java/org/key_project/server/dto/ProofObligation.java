/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * One thing that can be proved in an environment.
 *
 * @param contractId KeY's unique internal contract name, used to start a proof
 * @param kind what sort of contract this is
 * @param targetClass fully qualified name of the class the contract belongs to
 * @param targetMember the method or observer the contract constrains
 * @param hasExistingProof whether a proof for exactly this contract is already registered
 */
public record ProofObligation(String contractId, ProofObligationKind kind, String targetClass,
        String targetMember, boolean hasExistingProof) {
}
