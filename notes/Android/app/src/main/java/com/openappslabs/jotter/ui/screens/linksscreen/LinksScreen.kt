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

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openappslabs.jotter.data.model.Link
import com.openappslabs.jotter.ui.components.BackButton
import com.openappslabs.jotter.ui.components.EmptyState
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.utils.LinkUrl
import kotlinx.coroutines.launch

/**
 * The link inbox on the phone: share or paste a link, find it later.
 *
 * Capture is the important half. Typing (or pasting) a URL and pressing Done
 * stores it; the paste button does the same in one tap when the clipboard
 * already holds the link, which is the common case after reading something.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LinksScreen(
    onBackClick: () -> Unit,
    viewModel: LinksScreenViewModel = hiltViewModel()
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf("") }

    val openLink: (Link) -> Unit = { link ->
        haptics.tick()
        viewModel.markOpened(link)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url)))
        } catch (error: ActivityNotFoundException) {
            Log.w("JotterLinks", "no app to open ${link.url}: ${error.message}")
            Toast.makeText(context, strings.invalidLink, Toast.LENGTH_SHORT).show()
        }
    }

    val saveLink: (String) -> Unit = { raw ->
        val text = raw.trim()
        if (text.isEmpty()) {
            focusManager.clearFocus()
        } else {
            scope.launch {
                val saved = viewModel.save(text)
                if (saved) {
                    haptics.success()
                    draft = ""
                    focusManager.clearFocus()
                    Toast.makeText(context, strings.linkSaved, Toast.LENGTH_SHORT).show()
                } else {
                    haptics.tick()
                    Toast.makeText(context, strings.invalidLink, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val pasteAndSave: () -> Unit = {
        val text = clipboard.getText()?.text?.trim().orEmpty()
        if (text.isEmpty()) {
            Toast.makeText(context, strings.clipboardEmpty, Toast.LENGTH_SHORT).show()
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

            SearchField(value = uiState.query, onValueChange = viewModel::onQueryChange)

            Spacer(modifier = Modifier.height(8.dp))

            when {
                uiState.links.isEmpty() && uiState.total == 0 -> EmptyState(
                    icon = Icons.Outlined.Link,
                    title = strings.noLinksYet,
                    description = strings.noLinksHint,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )

                uiState.links.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.LinkOff,
                    title = strings.noLinksMatch(uiState.query),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
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
                                Toast.makeText(context, strings.linkCopied, Toast.LENGTH_SHORT).show()
                            },
                            onDelete = {
                                haptics.click()
                                viewModel.delete(link)
                                Toast.makeText(context, strings.linkRemoved, Toast.LENGTH_SHORT).show()
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
            .height(60.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            modifier = Modifier.weight(1f)
        )

        ActionButton(
            icon = Icons.Rounded.ContentPaste,
            description = strings.pasteLink,
            onClick = onPaste,
            container = MaterialTheme.colorScheme.surfaceContainerHighest,
            content = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.size(8.dp))

        ActionButton(
            icon = Icons.Filled.Add,
            description = strings.saveLink,
            onClick = { if (canSave) onSave() },
            container = MaterialTheme.colorScheme.primary.copy(alpha = if (canSave) 1f else 0.4f),
            content = MaterialTheme.colorScheme.onPrimary
        )
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
        trailingIcon = if (value.isNotEmpty()) {
            {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = strings.clearSearch,
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onValueChange("") }
                )
            }
        } else {
            null
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
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
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
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

            ActionButton(
                icon = Icons.Rounded.ContentCopy,
                description = strings.copyLink,
                onClick = onCopy,
                container = Color.Transparent,
                content = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ActionButton(
                icon = Icons.Rounded.Delete,
                description = strings.deleteLink,
                onClick = onDelete,
                container = Color.Transparent,
                content = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    container: Color,
    content: Color
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = container,
        contentColor = content,
        modifier = Modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(19.dp))
        }
    }
}
