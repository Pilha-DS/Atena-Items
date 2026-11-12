/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Jotter is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Jotter.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.jotter.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openappslabs.jotter.data.crypto.NoteCrypto
import com.openappslabs.jotter.ui.i18n.LocalStrings

enum class NotePasswordMode {
    /** Ask for a new password (twice) when turning encryption on. */
    SET,

    /** Ask for the note's own password to read it. */
    UNLOCK,

    /** Ask for the note's own password to turn encryption off. */
    REMOVE
}

/**
 * Password prompt for per-note encryption. The password never leaves this
 * dialog except as the value passed to [onSubmit]; `onSubmit` returns whether
 * it was accepted (wrong password / too short) and the dialog stays open.
 */
@Composable
fun NotePasswordDialog(
    mode: NotePasswordMode,
    onSubmit: (String) -> Boolean,
    onDismiss: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val strings = LocalStrings.current

    fun submit() {
        if (mode == NotePasswordMode.SET) {
            when {
                password.length < NoteCrypto.MIN_PASSWORD_LENGTH ->
                    error = strings.minPasswordCharacters(NoteCrypto.MIN_PASSWORD_LENGTH)
                password != confirmation -> error = strings.passwordsDoNotMatch
                else -> if (onSubmit(password)) return else error = strings.couldNotEncryptNote
            }
        } else {
            if (onSubmit(password)) return else error = strings.wrongPassword
        }
    }

    val title = when (mode) {
        NotePasswordMode.SET -> strings.encryptNoteTitle
        NotePasswordMode.UNLOCK -> strings.unlockNoteTitle
        NotePasswordMode.REMOVE -> strings.removeEncryptionTitle
    }
    val subtitle = when (mode) {
        NotePasswordMode.SET -> strings.encryptNoteDescription
        NotePasswordMode.UNLOCK -> strings.enterNotePassword
        NotePasswordMode.REMOVE -> strings.enterNotePasswordToRemove
    }
    val confirmLabel = when (mode) {
        NotePasswordMode.SET -> strings.encryptAction
        NotePasswordMode.UNLOCK -> strings.unlock
        NotePasswordMode.REMOVE -> strings.remove
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = { Text(strings.password) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (mode == NotePasswordMode.SET) ImeAction.Next else ImeAction.Done
                    ),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                )
                if (mode == NotePasswordMode.SET) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirmation,
                        onValueChange = {
                            confirmation = it
                            error = null
                        },
                        label = { Text(strings.confirmPassword) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done
                        ),
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.cancel) }
        }
    )
}
