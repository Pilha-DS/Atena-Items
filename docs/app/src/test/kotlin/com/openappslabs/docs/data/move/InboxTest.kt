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
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The inbox: where it is, what counts as being in it, and that it is created. */
class InboxTest {

    @Test
    fun `the inbox is the init folder at the top of the library`() {
        val root = createTempDirectory("docs-inbox")

        assertEquals(root.resolve("init"), Inbox.path(root))
        assertEquals("init", Inbox.FOLDER)
    }

    @Test
    fun `a document in init or below it is in the inbox`() {
        // A file at the top of the inbox has folder "init"; one in a subfolder
        // has "init/2026" — both are the waiting room.
        assertTrue(Inbox.holds("init"))
        assertTrue(Inbox.holds("init/2026"))
        assertTrue(Inbox.holds("init/2026/boletos"))
    }

    @Test
    fun `a folder that merely starts with the same letters is not the inbox`() {
        assertFalse(Inbox.holds(""))
        assertFalse(Inbox.holds("inicial"))
        assertFalse(Inbox.holds("academico/init"))
        assertFalse(Inbox.holds("meus-init"))
    }

    @Test
    fun `the name is compared without case because the disk may not care`() {
        assertTrue(Inbox.holds("Init"))
        assertTrue(Inbox.holds("INIT/2026"))
    }

    @Test
    fun `ensure creates the folder once and keeps what is inside`() {
        val root = createTempDirectory("docs-inbox")
        val existing = root.resolve("init").resolve("ja-estava.pdf")
        Files.createDirectories(existing.parent)
        Files.writeString(existing, "conteudo")

        val first = Inbox.ensure(root)
        val second = Inbox.ensure(root)

        assertTrue(Files.isDirectory(first))
        assertEquals(first, second)
        assertTrue(Files.exists(existing))
    }

    @Test
    fun `ensure does not create the whole tree when the root cannot be written`() {
        // A file where the inbox folder should go: the failure is swallowed and
        // the path is still returned, so nothing in the window breaks.
        val root = createTempDirectory("docs-inbox")
        Files.writeString(root.resolve("init"), "sou um arquivo")

        val path = Inbox.ensure(root)

        assertEquals(root.resolve("init"), path)
        assertFalse(Files.isDirectory(path))
    }
}
