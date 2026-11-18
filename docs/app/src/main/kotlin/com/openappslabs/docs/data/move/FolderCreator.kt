/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Docs
 *
 * Atena Docs is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Docs is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Docs.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.docs.data.move

import java.nio.file.Files
import java.nio.file.Path

/** Why a folder was not created; the dialog turns it into a sentence. */
enum class CreateIssue {
    /** `..`, `~`, a hidden segment, too deep, or the root itself. */
    INVALID,

    /** Something with that name is already there (a folder or a file). */
    EXISTS,

    /** The filesystem refused; the message says nothing about the cause. */
    FAILED
}

sealed interface CreateOutcome {
    data class Created(val path: String) : CreateOutcome
    data class Rejected(val issue: CreateIssue) : CreateOutcome
}

/**
 * Creates a folder — with its parents — inside the library.
 *
 * This is the user's deliberate act, so unlike [DocMover] there is no dialog
 * showing a suggestion first: the person typed the path and pressed create.
 * The rules are the ones [FolderPath] already enforces, plus two of its own:
 *
 *  - creating is idempotent in spirit but refused in practice: an existing
 *    folder is [CreateIssue.EXISTS], so a typo does not silently land in an
 *    older branch that happens to have the same name;
 *  - nothing is created without this call: the window only reaches it after the
 *    user pressed create. Inside `init` the same rule applies — the machine
 *    proposes, the person confirms.
 *
 * Parents come along on purpose (`faculdade/2026/semestre-1` makes all three),
 * which is the same promise the tag tree keeps.
 */
class FolderCreator(private val root: Path) {

    fun create(raw: String): CreateOutcome {
        val path = FolderPath.normalize(raw)
            ?: return CreateOutcome.Rejected(CreateIssue.INVALID)
        // The root is where the library already is: there is nothing to create.
        if (path.isEmpty()) return CreateOutcome.Rejected(CreateIssue.INVALID)

        val directory = FolderPath.resolve(root, path)
            ?: return CreateOutcome.Rejected(CreateIssue.INVALID)
        if (Files.exists(directory)) return CreateOutcome.Rejected(CreateIssue.EXISTS)

        val created = runCatching { Files.createDirectories(directory) }
        if (created.isFailure || !Files.isDirectory(directory)) {
            return CreateOutcome.Rejected(CreateIssue.FAILED)
        }
        return CreateOutcome.Created(path)
    }
}
