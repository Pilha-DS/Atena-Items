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

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openappslabs.jotter.data.model.Link
import com.openappslabs.jotter.data.repository.LinksRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class LinksUiState(
    /** What the list shows: the inbox filtered by [query]. */
    val links: List<Link> = emptyList(),
    /** How many links exist in total, so "no results" differs from "no links". */
    val total: Int = 0,
    val query: String = ""
)

/**
 * The link inbox screen.
 *
 * It is deliberately thin: the repository owns normalization and dedupe, and
 * this only filters what is already in the database. Searching runs in memory
 * because the inbox is small and a `LIKE` would re-read the table on every
 * keystroke.
 */
@HiltViewModel
class LinksScreenViewModel @Inject constructor(
    private val repository: LinksRepository
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<LinksUiState> =
        combine(repository.observeLinks(), query) { links, currentQuery ->
            val needle = currentQuery.trim()
            val filtered = if (needle.isEmpty()) {
                links
            } else {
                links.filter { link ->
                    link.title.contains(needle, ignoreCase = true) ||
                        link.url.contains(needle, ignoreCase = true)
                }
            }
            LinksUiState(links = filtered, total = links.size, query = currentQuery)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LinksUiState())

    fun onQueryChange(value: String) {
        query.value = value
    }

    /** Saves and tells the screen whether it may clear the input. */
    suspend fun save(rawUrl: String): Boolean = repository.saveLink(rawUrl) != null

    fun markOpened(link: Link) {
        viewModelScope.launch { repository.markOpened(link.id) }
    }

    fun delete(link: Link) {
        viewModelScope.launch { repository.delete(link) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
