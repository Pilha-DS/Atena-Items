/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openappslabs.jotter.platform.AppLock
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.platform.PinStore

@Composable
fun PinDialog(request: AppLock.Request) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val strings = LocalStrings.current

    fun submit() {
        when (request.mode) {
            AppLock.Mode.UNLOCK -> {
                if (PinStore.verify(pin)) {
                    request.onSuccess()
                    AppLock.onUnlocked()
                } else {
                    error = strings.wrongPin
                }
            }
            AppLock.Mode.SETUP -> {
                if (PinStore.setPin(pin)) {
                    request.onSuccess()
                    AppLock.onUnlocked()
                } else {
                    error = strings.useAtLeast4Digits
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { AppLock.dismiss() },
        title = { Text(request.title) },
        text = {
            Column {
                Text(request.subtitle, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        pin = it.filter(Char::isDigit).take(12)
                        error = null
                    },
                    label = { Text(strings.pin) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.padding(top = 4.dp)
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::submit) {
                Text(if (request.mode == AppLock.Mode.SETUP) strings.save else strings.unlock)
            }
        },
        dismissButton = {
            TextButton(onClick = { AppLock.dismiss() }) { Text(strings.cancel) }
        }
    )
}
