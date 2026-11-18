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

package com.openappslabs.docs.ui.format

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
private val timeOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val sizeUnits = listOf("KB", "MB", "GB", "TB")

/**
 * Size with decimal units, like Dolphin shows them (1000, not 1024), so the
 * number in the list matches the one in the file manager next to it.
 */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1000) return "$bytes B"

    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1000 && unit < sizeUnits.lastIndex) {
        value /= 1000
        unit += 1
    }
    val pattern = if (value >= 100) "%.0f" else "%.1f"
    val text = String.format(Locale.ROOT, pattern, value).replace('.', ',')
    return "$text ${sizeUnits[unit]}"
}

fun formatDateTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    dateTime.format(Instant.ofEpochMilli(millis).atZone(zone))

fun formatTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    timeOnly.format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * Second line of a list entry: kind, size, date and — only when the file is in
 * a subfolder — where it lives. A root-level document is already identified by
 * its name, so the folder part would just repeat "AtenaData/Docs" on every row.
 */
fun docDetails(
    kindLabel: String,
    size: Long,
    modifiedTime: Long,
    folder: String,
    zone: ZoneId = ZoneId.systemDefault()
): String = buildList {
    add(kindLabel)
    add(formatSize(size))
    add(formatDateTime(modifiedTime, zone))
    if (folder.isNotBlank()) add(folder)
}.joinToString(" · ")
