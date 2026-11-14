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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openappslabs.jotter.data.model.Link
import com.openappslabs.jotter.di.AppGraph
import com.openappslabs.jotter.platform.Diagnostics
import com.openappslabs.jotter.platform.ExternalLinks
import com.openappslabs.jotter.platform.Toaster
import com.openappslabs.jotter.ui.i18n.LocalStrings
import com.openappslabs.jotter.ui.theme.rememberJotterHaptics
import com.openappslabs.jotter.utils.LinkUrl
import kotlinx.coroutines.launch

/** Height of the capture field: compact, the panel is only part of a window. */
private val CAPTURE_HEIGHT = 36.dp

/** A compact row of the panel, small enough to show a few under the clipboard. */
private val ROW_ACTION_SIZE = 26.dp

/**
 * The link inbox as a strip, meant to live under the clipboard editor in the
 * left companion window.
 *
 * It is the same inbox as [LinksScreen], only without the chrome that a
 * full-screen destination can afford: no search box, no titles in the rows.
 * Saving is still one line and one Enter — capture is the reason this panel
 * exists, so it never costs more than a paste.
 *
 * Like the other window-level companions it talks to [AppGraph] directly
 * instead of using a ViewModel: these windows live outside the navigation graph
 * and are disposed when hidden.
 */
@Composable
fun LinksPanel(
    modifier: Modifier = Modifier,
    /** Given by the shell so the strip can open the full screen. */
    onOpenFullScreen: (() -> Unit)? = null
) {
    val haptics = rememberJotterHaptics()
    val strings = LocalStrings.current
    val repository = remember { AppGraph.linksRepository }
    val links by remember { repository.observeLinks() }.collectAsState(initial = emptyList())
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf("") }

    val openLink: (Link) -> Unit = { link ->
        haptics.tick()
        scope.launch { repository.markOpened(link.id) }
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
            focusManager.clearFocus()
        } else {
            scope.launch {
                val saved = repository.saveLink(text) != null
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

    Column(modifier = modifier.fillMaxSize()) {
        PanelHeader(
            count = links.size,
            onOpenFullScreen = onOpenFullScreen
        )

        val primaryColor = MaterialTheme.colorScheme.primary
        val cursorBrush = remember(primaryColor) { SolidColor(primaryColor) }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp)
                .height(CAPTURE_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(10.dp))
                .padding(start = 10.dp, end = 2.dp)
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) {
                    Text(
                        text = strings.linkPlaceholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    cursorBrush = cursorBrush,
                    textStyle = MemoryPanelTextStyle,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { saveLink(draft) }),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SmallPanelAction(
                icon = Icons.Rounded.ContentPaste,
                description = strings.pasteLink,
                onClick = {
                    val text = clipboard.getText()?.text?.trim().orEmpty()
                    if (text.isEmpty()) Toaster.show(strings.clipboardEmpty) else saveLink(text)
                }
            )
        }

        if (links.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = strings.noLinksYet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)
            ) {
                items(links, key = { it.id }) { link ->
                    PanelLinkRow(
                        link = link,
                        onOpen = { openLink(link) },
                        onCopy = {
                            haptics.tick()
                            clipboard.setText(AnnotatedString(link.url))
                            Toaster.show(strings.linkCopied)
                        },
                        onDelete = {
                            haptics.click()
                            scope.launch { repository.delete(link) }
                            Toaster.show(strings.linkRemoved)
                        }
                    )
                }
            }
        }
    }
}

private val MemoryPanelTextStyle = TextStyle(fontSize = 13.sp)

/** Label, how many links there are, and the way out to the full screen. */
@Composable
private fun PanelHeader(
    count: Int,
    onOpenFullScreen: (() -> Unit)?
) {
    val strings = LocalStrings.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = 14.dp, end = 4.dp)
    ) {
        Text(
            text = strings.linkInbox,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (count > 0) {
            Text(
                text = " · $count",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Box(modifier = Modifier.weight(1f))
        if (onOpenFullScreen != null) {
            SmallPanelAction(
                icon = Icons.Rounded.OpenInFull,
                description = strings.linkInbox,
                onClick = onOpenFullScreen
            )
        }
    }
}

/** One link, one line for the title and one for where it goes. */
@Composable
private fun PanelLinkRow(
    link: Link,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    val strings = LocalStrings.current
    val title = link.title.ifBlank { LinkUrl.hostOf(link.url).ifBlank { link.url } }

    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = link.url,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }

            SmallPanelAction(
                icon = Icons.Rounded.ContentCopy,
                description = strings.copyLink,
                onClick = onCopy
            )
            SmallPanelAction(
                icon = Icons.Rounded.Delete,
                description = strings.deleteLink,
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Borderless 26dp button: the panel is too small for filled icons. */
@Composable
private fun SmallPanelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(ROW_ACTION_SIZE)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(15.dp)
        )
    }
}
