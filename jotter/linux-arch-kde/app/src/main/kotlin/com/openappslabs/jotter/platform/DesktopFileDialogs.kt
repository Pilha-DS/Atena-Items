/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.platform

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Native-ish AWT file dialogs. Compose Desktop has no SAF, so export/import
 * use the platform file chooser directly.
 */
object DesktopFileDialogs {

    fun saveJson(owner: Frame?, suggestedName: String, title: String = "Export notes"): File? {
        val dialog = FileDialog(owner, title, FileDialog.SAVE).apply {
            file = suggestedName
            setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
            isVisible = true
        }
        val directory = dialog.directory ?: return null
        val name = dialog.file ?: return null
        val fileName = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"
        return File(directory, fileName)
    }

    fun openJson(owner: Frame?, title: String = "Import notes"): File? {
        val dialog = FileDialog(owner, title, FileDialog.LOAD).apply {
            setFilenameFilter { _, name -> name.endsWith(".json", ignoreCase = true) }
            isVisible = true
        }
        val directory = dialog.directory ?: return null
        val name = dialog.file ?: return null
        return File(directory, name)
    }
}
