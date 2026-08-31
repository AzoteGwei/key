/* This file is part of KeY - https://key-project.org
 * KeY is licensed under the GNU General Public License Version 2
 * SPDX-License-Identifier: GPL-2.0-only */
package org.key_project.server.dto;

/** What kind of work a task performs. */
public enum TaskKind {
    /** Loading a project or {@code .key} file into a fresh environment. */
    LOAD,
    /** Replaying a saved {@code .proof} file. */
    REPLAY,
    /** Running the automatic proof search. */
    AUTO,
    /** Running a proof macro. */
    MACRO,
    /** Running a proof script. */
    SCRIPT
}
