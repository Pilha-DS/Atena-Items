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

package com.openappslabs.docs.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules behind the folder browser: the breadcrumb it draws and the
 * destination it produces. Both are pure, so the dialog's navigation is tested
 * without a window.
 */
class FolderBrowserTest {

    @Test
    fun `the trail has one crumb per level`() {
        assertEquals(
            listOf(
                FolderCrumb(label = "faculdade", path = "faculdade"),
                FolderCrumb(label = "2026", path = "faculdade/2026")
            ),
            folderTrail("faculdade/2026")
        )
    }

    @Test
    fun `the root has no crumb`() {
        assertTrue(folderTrail("").isEmpty())
        assertTrue(folderTrail("/").isEmpty())
    }

    @Test
    fun `the trail forgives spaces and repeated separators`() {
        assertEquals(
            listOf(FolderCrumb(label = "a", path = "a"), FolderCrumb(label = "b", path = "a/b")),
            folderTrail(" /a/ b / ")
        )
    }

    @Test
    fun `the trail ignores an unusable path`() {
        assertTrue(folderTrail("../fora").isEmpty())
        assertTrue(folderTrail(".oculta").isEmpty())
    }

    @Test
    fun `a typed name belongs to the folder on screen`() {
        assertEquals(
            "faculdade/2026/semestre-1",
            resolveFolderTarget("faculdade/2026", "semestre-1")
        )
    }

    @Test
    fun `a longer relative path is still relative`() {
        assertEquals(
            "faculdade/2026/semestre-1",
            resolveFolderTarget("faculdade", "2026/semestre-1")
        )
    }

    @Test
    fun `an empty field keeps the folder on screen`() {
        assertEquals("faculdade", resolveFolderTarget("faculdade", "   "))
        assertEquals("", resolveFolderTarget("", ""))
    }

    @Test
    fun `a name that escapes the library is refused`() {
        assertNull(resolveFolderTarget("faculdade", "../fora"))
        assertNull(resolveFolderTarget("faculdade", "~"))
        assertNull(resolveFolderTarget("faculdade", ".oculta"))
    }

    @Test
    fun `the root refuses a name with a control character`() {
        assertNull(resolveFolderTarget("", "nota\u0000final"))
    }
}
