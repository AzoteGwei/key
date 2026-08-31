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

import org.jspecify.annotations.Nullable;

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

    /**
     * Describes a single source location KeY attached to a failure.
     *
     * <p>
     * Proof script failures carry a {@link Location} rather than a {@code PositionedString}: KeY
     * knows exactly which command in the script gave up, and that position is far more use to an
     * agent rewriting the script than the message text is.
     *
     * @param location where the failure happened, may be {@code null}
     * @param detail the explanation to accompany it
     * @return structured detail carrying the position, if there was one
     */
    public static RpcErrorData at(@Nullable Location location, String detail) {
        if (location == null) {
            return RpcErrorData.of(detail);
        }
        Position position = location.getPosition();
        String file = location.getFileUri() == null ? null : location.getFileUri().toString();
        int line = position.isNegative() ? 0 : position.line();
        int column = position.isNegative() ? 0 : position.column();
        return new RpcErrorData(List.of(new SourcePosition(file, line, column, detail)), detail);
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
