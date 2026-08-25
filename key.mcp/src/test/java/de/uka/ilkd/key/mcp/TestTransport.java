/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package de.uka.ilkd.key.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.mcp.transport.StdioTransport;

/**
 * Test-only transport that captures sent messages and ignores the read loop.
 */
class TestTransport extends StdioTransport {
    private final List<String> sentMessages = new ArrayList<>();

    TestTransport() {
        super(InputStream.nullInputStream(), OutputStream.nullOutputStream(), m -> {
        });
    }

    @Override
    public void send(String message) {
        sentMessages.add(message);
    }

    @Override
    public void run() throws IOException {
        // No-op for tests.
    }

    public List<String> getSentMessages() {
        return sentMessages;
    }
}
