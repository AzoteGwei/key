/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.registry;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

/**
 * Generates the opaque identifiers used in the protocol.
 *
 * <p>
 * Identifiers are deliberately not paths, indices or anything else a client could reconstruct or
 * reason about: clients must treat them as opaque handles, and making them unguessable is the
 * cheapest way to keep that contract honest.
 */
public final class Ids {

    private static final RandomGenerator RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final int LENGTH = 8;

    private Ids() {
    }

    /**
     * Creates a fresh identifier with the given prefix.
     *
     * @param prefix short namespace marker, for instance {@code env}
     * @return an identifier such as {@code env-3f9a2c7b}
     */
    public static String create(String prefix) {
        StringBuilder builder = new StringBuilder(prefix).append('-');
        for (int i = 0; i < LENGTH; i++) {
            builder.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return builder.toString();
    }
}
