/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/**
 * One macro this server can run.
 *
 * @param macroId the name to pass to {@code goal.applyMacro}, which is the macro's script command
 *        name so the same identifier also works inside a proof script
 * @param name the macro's own display name
 * @param category how KeY groups it, may be {@code null}
 * @param description what KeY says it does, may be {@code null}
 */
public record MacroInfo(String macroId, String name, String category, String description) {
}
