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

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.repository.DescriptionRepository
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * One sentence about a folder or a tag, written by the user.
 *
 * The app cannot guess that `Original` holds the signed contract while
 * `Certificados` holds the course hours; the person knows. This field is where
 * that knowledge goes, and it is stored in SQLite, never inside the library
 * folder.
 *
 * Saving with the field empty erases the description, which is the whole
 * "remove" flow: one button, one state, no confirmation for undoing a sentence.
 * The text is trimmed and capped by [DescriptionRepository.clean] — this dialog
 * only shows the limit.
 */
@Composable
fun DescribeDialog(
    title: String,
    subject: String,
    hint: String,
    placeholder: String,
    current: String,
    strings: DocsStrings,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    // Seeded once: the dialog is recreated for each target, and re-reading
    // `current` on every recomposition would fight the typing.
    var text by remember(subject) { mutableStateOf(current) }
    val tooLong = text.length > DescriptionRepository.MAX_LENGTH
    val changed = text.trim() != current.trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    subject,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(placeholder, style = MaterialTheme.typography.bodySmall) },
                    isError = tooLong,
                    minLines = 3,
                    maxLines = 5,
                    supportingText = {
                        Text(
                            "${text.length}/${DescriptionRepository.MAX_LENGTH} · ${strings.describeClearHint}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = !tooLong && changed
            ) {
                Text(strings.describeSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}
