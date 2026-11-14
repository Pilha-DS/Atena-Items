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

package com.openappslabs.jotter.ui.screens.linksscreen

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openappslabs.jotter.data.model.Link
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.platform.ExternalLinks
import com.openappslabs.jotter.platform.Toaster
import com.openappslabs.jotter.ui.components.BackButton
import com.openappslabs.jotter.ui.components.EmptyState
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.di.AppGraph
import com.openappslabs.jotter.utils.LinkUrl
import kotlinx.coroutines.launch

/**
 * The link inbox: one field to save, one list to find.
 *
 * Capture is the important half. Typing (or pasting) a URL and pressing Enter
 * stores it; the paste button does the same in one click when the clipboard
 * already holds the link. Opening a row bumps it to the top of the list.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinksScreen(
    onBackClick: () -> Unit,
    viewModel: LinksScreenViewModel = viewModel {
        LinksScreenViewModel(AppGraph.linksRepository)
    }
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val uiState by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf("") }

    val openLink: (Link) -> Unit = { link ->
        haptics.tick()
        viewModel.markOpened(link)
        // The browser takes the focus away; this keeps the widget from hiding.
        ExternalLinks.markOpened()
        runCatching { uriHandler.openUri(link.url) }
            .onFailure {
                Diagnostics.log("open link failed (${link.url}): ${it.message}")
                Toaster.show(strings.invalidLink)
            }
    }

    val saveLink: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isEmpty()) {
            // Nothing to save: just drop focus so the desktop widget can close.
            focusManager.clearFocus()
        } else {
            scope.launch {
                val saved = viewModel.save(text)
                if (saved) {
                    haptics.success()
                    draft = ""
                    focusManager.clearFocus()
                    Toaster.show(strings.linkSaved)
                } else {
                    haptics.tick()
                    Toaster.show(strings.invalidLink)
                }
            }
        }
    }

    val pasteAndSave: () -> Unit = {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isEmpty()) {
            Toaster.show(strings.clipboardEmpty)
        } else {
            saveLink(text)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = strings.linkInbox,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = { BackButton(onClick = onBackClick) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.surface
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            CaptureRow(
                value = draft,
                onValueChange = { draft = it },
                onSave = { saveLink(draft) },
                onPaste = pasteAndSave
            )

            Spacer(modifier = Modifier.height(8.dp))

            SearchField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.links.isEmpty() && uiState.total == 0 -> EmptyState(
                    icon = Icons.Outlined.Link,
                    title = strings.noLinksYet,
                    description = strings.noLinksHint,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                )

                uiState.links.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LinkOff,
                    title = strings.noLinksMatch(uiState.query),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(bottom = 48.dp)
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
                    items(uiState.links, key = { it.id }) { link ->
                        LinkRow(
                            link = link,
                            onOpen = { openLink(link) },
                            onCopy = {
                                haptics.tick()
                                clipboard.setText(AnnotatedString(link.url))
                                Toaster.show(strings.linkCopied)
                            },
                            onDelete = {
                                haptics.click()
                                viewModel.delete(link)
                                Toaster.show(strings.linkRemoved)
                            },
                            modifier = Modifier.animateItem()
                        )
                    }
                }
            }
        }
    }
}

/** One field, two actions: paste the clipboard or save what was typed. */
@Composable
private fun CaptureRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onPaste: () -> Unit
) {
    val strings = LocalStrings.current
    val canSave = value.isNotBlank()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(start = 4.dp, end = 8.dp)
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(strings.linkPlaceholder) },
            textStyle = MaterialTheme.typography.bodyLarge,
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.weight(1f)
        )

        Surface(
            onClick = onPaste,
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.ContentPaste,
                    contentDescription = strings.pasteLink,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.size(8.dp))

        Surface(
            onClick = { if (canSave) onSave() },
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = if (canSave) 1f else 0.4f),
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = strings.saveLink,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    val strings = LocalStrings.current
    TextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(strings.searchLinks) },
        leadingIcon = {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
    )
}

@Composable
private fun LinkRow(
    link: Link,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    val title = link.title.ifBlank { LinkUrl.hostOf(link.url).ifBlank { link.url } }

    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            SmallAction(
                icon = Icons.Rounded.OpenInNew,
                description = strings.openLink,
                onClick = onOpen
            )
            SmallAction(
                icon = Icons.Rounded.ContentCopy,
                description = strings.copyLink,
                onClick = onCopy
            )
            SmallAction(
                icon = Icons.Rounded.Delete,
                description = strings.deleteLink,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun SmallAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        contentColor = tint,
        modifier = Modifier.size(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(18.dp))
        }
    }
}
