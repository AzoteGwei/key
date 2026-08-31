/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.rpc;

import java.util.ArrayList;
import java.util.List;

import de.uka.ilkd.key.speclang.PositionedString;
import de.uka.ilkd.key.util.ExceptionTools;

import org.key_project.server.dto.RpcErrorData;
import org.key_project.server.dto.SourcePosition;
import org.key_project.util.parsing.Location;
import org.key_project.util.parsing.Position;

/**
 * Turns KeY failures into the structured detail clients receive.
 *
 * <p>
 * KeY already knows where a load or parse failure happened; the console UI uses
 * {@link ExceptionTools#getMessages(Throwable)} to render its caret-annotated message. We reuse the
 * same extraction, but hand the positions over as data instead of as formatted text, so an agent
 * does not have to scrape a human-readable string.
 */
public final class KeyErrors {

    private KeyErrors() {
    }

    /**
     * Extracts every message and source position KeY attached to a failure.
     *
     * @param failure the exception thrown by KeY
     * @return structured detail carrying the positions, never {@code null}
     */
    public static RpcErrorData describe(Throwable failure) {
        List<SourcePosition> positions = new ArrayList<>();
        for (PositionedString message : ExceptionTools.getMessages(failure)) {
            positions.add(toSourcePosition(message));
        }
        return new RpcErrorData(List.copyOf(positions), rootMessage(failure));
    }

    private static SourcePosition toSourcePosition(PositionedString message) {
        Location location = message.getLocation();
        Position position = location.getPosition();
        String file = location.getFileUri() == null ? null : location.getFileUri().toString();
        int line = position.isNegative() ? 0 : position.line();
        int column = position.isNegative() ? 0 : position.column();
        return new SourcePosition(file, line, column, message.getText());
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? current.getClass().getName() : message;
    }
}
