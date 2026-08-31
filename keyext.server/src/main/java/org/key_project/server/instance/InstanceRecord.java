/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.instance;

/**
 * What a running instance publishes about itself so a client can find it.
 *
 * <p>
 * The point of writing this to disk is that a client should not have to be told where the server
 * is. An agent workflow that has to pass a port around has one more thing to get wrong, and the
 * port is usually chosen by the OS anyway.
 *
 * @param instanceId the identifier the instance reports as its own
 * @param pid the process it runs in, which is how a leftover file is recognised as leftover
 * @param host the address it listens on
 * @param port the port it listens on
 * @param workspacePath the directory the instance is anchored to
 * @param apiVersion the protocol version it speaks
 * @param keyVersion the KeY it embeds
 * @param threads how many prover workers it was started with
 * @param startedAt when it started, as an ISO-8601 instant
 */
public record InstanceRecord(String instanceId, long pid, String host, int port,
        String workspacePath, String apiVersion, String keyVersion, int threads,
        String startedAt) {
}
