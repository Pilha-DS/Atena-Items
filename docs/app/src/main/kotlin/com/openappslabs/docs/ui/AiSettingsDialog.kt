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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openappslabs.docs.data.ai.AiProvider
import com.openappslabs.docs.data.ai.AiSettings
import com.openappslabs.docs.data.ai.KeyChange
import com.openappslabs.docs.ui.i18n.DocsStrings

/**
 * Chooses **where** the AI runs: the local Ollama or a remote service, and the
 * key the remote one needs.
 *
 * The key field is masked with a show toggle, because pasting a long token is
 * exactly when you need to check the last characters — and it is **write-only**:
 * the stored key never comes back into it. When one is already configured the
 * dialog says so and offers "Remover"; the field only ever carries a *new*
 * value, which is cleared from memory as soon as the dialog saves or closes.
 *
 * The draft lives here and is only committed by "Salvar"; "Testar" runs against
 * a copy and changes nothing on disk.
 *
 * Local (Ollama) offers size shortcuts — 7B is the default suggestion for this
 * app's tag prompts; 3B stays as a light option and 8B as an alternate.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettingsDialog(
    settings: AiSettings,
    isTesting: Boolean,
    testOk: Boolean?,
    configPath: String,
    strings: DocsStrings,
    onTest: (AiSettings) -> Unit,
    onSave: (AiSettings, KeyChange) -> Unit,
    onRemoveKey: (AiSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var url by remember(settings) { mutableStateOf(settings.url) }
    var key by remember(settings) { mutableStateOf("") }
    var revealKey by remember(settings) { mutableStateOf(false) }

    // A blank/unchanged field follows the provider; a value the user typed wins.
    fun rebuild(provider: AiProvider, nextModel: String, nextUrl: String): AiSettings =
        draft.copy(provider = provider, model = nextModel, url = nextUrl)

    /** What the typed key means for this save: write it, or leave the file alone. */
    fun candidate(next: AiSettings): AiSettings =
        if (key.isBlank()) next.withKeyFrom(settings) else next.copy(apiKey = key.trim())

    fun keyChange(): KeyChange = if (key.isBlank()) KeyChange.Keep else KeyChange.Set(key)

    val storedKey = settings.apiKey.isNotBlank()
    val needsKey = draft.provider == AiProvider.OPENAI && !storedKey && key.isBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.aiTitle) },
        text = {
            Column(
                Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    strings.aiIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(12.dp))

                ProviderOption(
                    label = strings.aiLocal,
                    detail = strings.aiLocalDetail,
                    selected = draft.provider == AiProvider.OLLAMA,
                    onSelect = {
                        val next = draft.withProvider(AiProvider.OLLAMA)
                        draft = next
                        model = next.model
                        url = next.url
                    }
                )
                ProviderOption(
                    label = strings.aiRemote,
                    detail = strings.aiRemoteDetail,
                    selected = draft.provider == AiProvider.OPENAI,
                    onSelect = {
                        val next = draft.withProvider(AiProvider.OPENAI)
                        draft = next
                        model = next.model
                        url = next.url
                    }
                )

                Spacer(Modifier.size(12.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = {
                        model = it
                        draft = rebuild(draft.provider, it, url)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(strings.aiModel) },
                    placeholder = {
                        Text(
                            AiSettings.defaultModel(draft.provider)
                                .ifEmpty { strings.aiModelAny }
                        )
                    },
                    supportingText = {
                        Text(
                            if (draft.provider == AiProvider.OPENAI) {
                                strings.aiRemoteModelHint
                            } else {
                                strings.aiModelHint
                            }
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                val modelSuggestions = when (draft.provider) {
                    AiProvider.OLLAMA -> AiSettings.OLLAMA_MODEL_SUGGESTIONS
                    AiProvider.OPENAI -> AiSettings.DEEPSEEK_MODEL_SUGGESTIONS
                }
                Spacer(Modifier.size(6.dp))
                Text(
                    strings.aiModelSuggestions,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    modelSuggestions.forEach { suggestion ->
                        FilterChip(
                            selected = model == suggestion,
                            onClick = {
                                model = suggestion
                                draft = rebuild(draft.provider, suggestion, url)
                            },
                            label = { Text(suggestion) }
                        )
                    }
                }
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        draft = rebuild(draft.provider, model, it)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(strings.aiUrl) },
                    placeholder = { Text(AiSettings.defaultUrl(draft.provider)) },
                    isError = url.isNotBlank() && !url.startsWith("http"),
                    textStyle = MaterialTheme.typography.bodySmall
                )

                Spacer(Modifier.size(14.dp))
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(strings.aiKey) },
                    placeholder = {
                        Text(if (storedKey) strings.aiKeyKeep else strings.aiKeyPaste)
                    },
                    // Masked by default: the tokens are long and the person next
                    // to you does not need to read it.
                    visualTransformation = if (revealKey) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { revealKey = !revealKey }) {
                            Icon(
                                if (revealKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (revealKey) strings.aiKeyHide else strings.aiKeyShow
                            )
                        }
                    },
                    supportingText = {
                        Text(
                            if (storedKey) strings.aiKeyStored else strings.aiKeyAbsent,
                            color = if (needsKey) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall
                )
                // Never the key itself — only where it lives and how to get rid of it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        strings.aiKeyWhere(configPath),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    if (storedKey) {
                        TextButton(onClick = { onRemoveKey(candidate(rebuild(draft.provider, model, url))) }) {
                            Text(strings.aiKeyRemove)
                        }
                    }
                }

                Spacer(Modifier.size(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(
                        enabled = !isTesting && url.startsWith("http"),
                        onClick = {
                            onTest(candidate(rebuild(draft.provider, model, url)))
                        }
                    ) {
                        Text(strings.aiTest)
                    }
                    if (isTesting) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else if (testOk != null) {
                        Text(
                            if (testOk) strings.aiTestOk else strings.aiTestFailed,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (testOk) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.error
                            }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isTesting && url.startsWith("http"),
                onClick = {
                    onSave(candidate(rebuild(draft.provider, model, url)), keyChange())
                }
            ) {
                Text(strings.aiSave)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(strings.close) }
        }
    )
}

@Composable
private fun ProviderOption(
    label: String,
    detail: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
