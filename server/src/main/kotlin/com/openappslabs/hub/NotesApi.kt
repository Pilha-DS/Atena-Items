/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Hub
 *
 * Atena Hub is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Hub is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Hub.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.hub

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Read API over the notes the hub already stores for the sync.
 *
 * It exists so another desktop app can read notes without having its own copy
 * of the database: the hub is the meeting point. Writes
 * still happen only through `/api/v1/sync`, by the app that owns the note, so
 * a reader can never corrupt a client's local state.
 */
fun Route.jotterNotesApi(database: Database) {

    route("/tools/jotter/notes") {
        get {
            if (!call.requireToken(database)) return@get

            val query = call.request.queryParameters["q"]?.trim().orEmpty()
            val category = call.request.queryParameters["category"]?.trim().orEmpty()
            val updatedAfter = call.request.queryParameters["updatedAfter"]?.toLongOrNull() ?: 0L
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
                .coerceIn(1, MAX_LIMIT)
            val includeTrashed = call.request.queryParameters["includeTrashed"] == "1"

            val result = database.read { connection ->
                val sql = buildString {
                    append("SELECT $NOTE_COLUMNS FROM notes WHERE updated_time >= ? ")
                    if (!includeTrashed) append("AND is_trashed = 0 ")
                    if (category.isNotEmpty()) append("AND category = ? ")
                    append("ORDER BY updated_time DESC LIMIT ?")
                }
                val args = buildList<Any?> {
                    add(updatedAfter)
                    if (category.isNotEmpty()) add(category)
                    // When there is a search term, read a wider window and rank
                    // locally before truncating: the newest note is not always
                    // the one that mentions the subject.
                    add(limit * if (query.isEmpty()) 1 else 10)
                }
                val notes = connection.queryList(sql, *args.toTypedArray()) { row ->
                    row.readToolNote()
                }

                val filtered = if (query.isEmpty()) {
                    notes
                } else {
                    notes
                        .map { it to score(it, query) }
                        .filter { it.second > 0 }
                        .sortedByDescending { it.second }
                        .map { it.first }
                }

                val total = connection.query(
                    "SELECT COUNT(*) FROM notes WHERE is_trashed = 0"
                ) { it.getInt(1) } ?: 0

                ToolNotesResponse(
                    total = total,
                    cursor = connection.query(
                        "SELECT value FROM meta WHERE key = ?", Database.KEY_SEQ
                    ) { it.getString(1)?.toLongOrNull() ?: 0L } ?: 0L,
                    notes = filtered.take(limit)
                )
            }

            call.respond(result)
        }

        get("/{uuid}") {
            if (!call.requireToken(database)) return@get
            val uuid = call.parameters["uuid"]?.trim().orEmpty()
            if (uuid.isEmpty()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("missing note uuid"))
                return@get
            }
            val note = database.read { connection ->
                connection.query(
                    "SELECT $NOTE_COLUMNS FROM notes WHERE uuid = ?", uuid
                ) { it.readToolNote() }
            }
            if (note == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("note not found"))
            } else {
                call.respond(note)
            }
        }
    }
}

/**
 * A tiny ranking good enough to pick which notes to hand to a model: title
 * matches weigh more than body matches, and every distinct term counts once.
 *
 * Both sides are folded to ASCII first, so `pao` finds "pão" and `cafe`
 * finds "café" — the model should not depend on the user typing accents.
 */
internal fun score(note: ToolNoteDto, query: String): Int {
    val terms = query.lowercase()
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map(::fold)
        .filter { it.length > 2 }
        .distinct()
    if (terms.isEmpty()) return 0
    val title = fold(note.title.lowercase())
    val content = fold(note.content.lowercase())
    val category = fold(note.category.lowercase())
    var total = 0
    for (term in terms) {
        if (title.contains(term)) total += 6
        if (content.contains(term)) total += 2
        if (category.contains(term)) total += 1
    }
    return total
}

/** Lowercase, accent-free form used for matching. */
internal fun fold(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase()

private fun java.sql.ResultSet.readToolNote(): ToolNoteDto {
    val encrypted = getInt("is_encrypted") != 0
    return ToolNoteDto(
        uuid = getString("uuid"),
        title = getString("title") ?: "",
        // An encrypted note is metadata only: the server never has the password.
        content = if (encrypted) "" else getString("content") ?: "",
        category = getString("category") ?: "",
        createdTime = getLong("created_time"),
        updatedTime = getLong("updated_time"),
        format = getString("format") ?: "text",
        isPinned = getInt("is_pinned") != 0,
        isTrashed = getInt("is_trashed") != 0,
        isEncrypted = encrypted
    )
}

/** Bearer or legacy header; answers 401 itself when the token is wrong. */
internal suspend fun ApplicationCall.requireToken(database: Database): Boolean {
    val header = request.headers["Authorization"]
    val provided = when {
        header != null && header.startsWith("Bearer ", ignoreCase = true) ->
            header.substring("Bearer ".length).trim()
        else -> request.headers["X-Jotter-Token"]?.trim()
            ?: request.headers["X-Atena-Token"]?.trim()
    }
    val label = database.tokenLabel(provided)
    if (label != null) {
        // Kept on the call so the route can say who synced, without reading the
        // header (and the database) a second time.
        attributes.put(CallerLabel, label)
        return true
    }
    respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid or missing token"))
    return false
}

/** Label of the token that passed [requireToken]; empty when there is none. */
internal fun ApplicationCall.caller(): String = attributes.getOrNull(CallerLabel).orEmpty()

private val CallerLabel = io.ktor.util.AttributeKey<String>("atena.caller")

private const val DEFAULT_LIMIT = 20
private const val MAX_LIMIT = 500
private const val NOTE_COLUMNS =
    "uuid, title, content, category, created_time, updated_time, is_pinned, " +
        "is_trashed, is_encrypted, format"
