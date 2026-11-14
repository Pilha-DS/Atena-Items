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

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.nio.file.Files

/**
 * Routes of the universal store. Two shapes of data, one contract:
 *
 *  - **records** — small documents (JSON, text, a save file), merged by
 *    `updatedTime` with a cursor per `namespace/collection`;
 *  - **blobs** — files, streamed to disk, content-addressed and deduplicated.
 *
 * A new app needs neither a new route nor a new server release: it picks a
 * namespace (its own name), a collection and a key. The hub never interprets
 * the payload.
 */
fun Route.storeApi(
    config: Config,
    database: Database,
    storeService: StoreService,
    blobs: BlobStore
) {

    route("/store") {

        /** Discovery: which namespaces/collections exist, and their cursors. */
        get {
            if (!call.requireToken(database)) return@get
            val scope = call.callerScope()
            val all = storeService.streams()
            call.respond(
                StoreStreamsResponse(
                    streams = all.filter { scopeAllows(scope, it.namespace) }
                )
            )
        }

        /** One round of push + pull for a collection. */
        post("/{namespace}/{collection}/sync") {
            val namespace = call.parameters["namespace"].orEmpty()
            val collection = call.parameters["collection"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@post

            val bytesIn = call.request.contentLength() ?: 0L
            if (bytesIn > MAX_SYNC_BODY) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("sync body too large"))
                return@post
            }
            val request = call.receive<StoreSyncRequest>()
            if (request.records.any { it.payload.toByteArray().size.toLong() > config.maxRecordBytes }) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("record too large"))
                return@post
            }
            if (request.records.any { it.key.isBlank() || it.key.length > StoreService.MAX_KEY } ||
                request.deletions.any { it.key.isBlank() || it.key.length > StoreService.MAX_KEY }
            ) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid key"))
                return@post
            }
            call.respond(
                storeService.sync(
                    namespace = namespace,
                    collection = collection,
                    request = request,
                    remoteIp = call.request.origin.remoteHost,
                    caller = call.caller(),
                    bytesIn = bytesIn
                )
            )
        }

        /** One record, for a reader that does not want a whole sync round. */
        get("/{namespace}/{collection}/records/{key}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val collection = call.parameters["collection"].orEmpty()
            val key = call.parameters["key"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@get
            if (!validKey(key)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid key"))
                return@get
            }
            val record = storeService.read(namespace, collection, key)
            if (record == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("record not found"))
            } else {
                call.respond(record)
            }
        }

        /**
         * The shortest path to "put this there": the body is the payload, kept
         * byte for byte, and the server clock is the version.
         */
        put("/{namespace}/{collection}/records/{key}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val collection = call.parameters["collection"].orEmpty()
            val key = call.parameters["key"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@put
            if (!validKey(key)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid key"))
                return@put
            }
            val declared = call.request.contentLength() ?: 0L
            if (declared > config.maxRecordBytes) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("record too large"))
                return@put
            }
            val payload = call.receiveText()
            if (payload.toByteArray().size.toLong() > config.maxRecordBytes) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("record too large"))
                return@put
            }
            call.respond(storeService.write(namespace, collection, key, payload))
        }
    }

    route("/blobs") {

        /** Metadata of every file in one namespace (bytes are not listed). */
        get {
            if (!call.requireToken(database)) return@get
            val namespace = call.request.queryParameters["namespace"].orEmpty()
            if (!validIdentifier(namespace)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid namespace"))
                return@get
            }
            if (!call.allowedNamespace(namespace)) return@get

            val includeDeleted = call.request.queryParameters["includeDeleted"] == "1"
            val list = database.read { connection ->
                val sql = "SELECT namespace, name, hash, size, content_type, created_time, " +
                    "updated_time, deleted FROM blobs WHERE namespace = ? " +
                    (if (includeDeleted) "" else "AND deleted = 0 ") +
                    "ORDER BY name LIMIT 500"
                connection.queryList(sql, namespace) { it.readBlob() }
            }
            call.respond(BlobListResponse(namespace = namespace, blobs = list))
        }

        /** Upload (or replace) a file. The body is the file, unmodified. */
        put("/{namespace}/{name}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@put
            if (!validName(name)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid file name"))
                return@put
            }
            val declared = call.request.contentLength() ?: 0L
            if (declared > config.maxBlobBytes) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file too large"))
                return@put
            }

            val stored = call.receiveChannel().toInputStream().use { blobs.store(it) }
            if (stored == null) {
                call.respond(HttpStatusCode.PayloadTooLarge, ErrorResponse("file too large"))
                return@put
            }

            val contentType = call.request.headers[HttpHeaders.ContentType]
                ?.take(128)
                ?.takeIf { runCatching { ContentType.parse(it) }.isSuccess }
                ?: "application/octet-stream"

            val now = System.currentTimeMillis()
            val oldHash = database.write { connection ->
                val previous = connection.query(
                    "SELECT hash FROM blobs WHERE namespace = ? AND name = ?", namespace, name
                ) { it.getString(1) }
                val created = connection.query(
                    "SELECT created_time FROM blobs WHERE namespace = ? AND name = ?", namespace, name
                ) { it.getLong(1) } ?: now

                connection.execute(
                    "INSERT INTO blobs(namespace, name, hash, size, content_type, created_time, " +
                        "updated_time, deleted, deleted_at) VALUES(?,?,?,?,?,?,?,0,0) " +
                        "ON CONFLICT(namespace, name) DO UPDATE SET hash = excluded.hash, " +
                        "size = excluded.size, content_type = excluded.content_type, " +
                        "updated_time = excluded.updated_time, deleted = 0, deleted_at = 0",
                    namespace, name, stored.hash, stored.size, contentType, created, now
                )
                previous
            }
            // A replaced file leaves its old bytes behind only if nothing else
            // points at them: the store is content-addressed, so dedupe is real.
            if (oldHash != null && oldHash != stored.hash) blobs.removeIfUnreferenced(database, oldHash)

            call.respond(
                BlobDto(
                    namespace = namespace,
                    name = name,
                    hash = stored.hash,
                    size = stored.size,
                    contentType = contentType,
                    createdTime = now,
                    updatedTime = now
                )
            )
        }

        /** The bytes, streamed from disk; `ETag` makes a re-read a 304. */
        get("/{namespace}/{name}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@get

            val blob = findBlob(database, namespace, name)
            if (blob == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found"))
                return@get
            }
            val path = blobs.file(blob.hash)
            if (!Files.isRegularFile(path)) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file content missing"))
                return@get
            }

            call.response.header(HttpHeaders.ETag, "\"${blob.hash}\"")
            call.response.header(HttpHeaders.CacheControl, "private, max-age=0, must-revalidate")
            if (call.request.headers[HttpHeaders.IfNoneMatch]?.contains(blob.hash) == true) {
                call.respond(HttpStatusCode.NotModified)
                return@get
            }
            call.respond(
                LocalFileContent(
                    path.toFile(),
                    ContentType.parse(blob.contentType)
                        ?: ContentType.Application.OctetStream
                )
            )
        }

        /** Metadata only, so a client can decide whether to download. */
        head("/{namespace}/{name}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@head

            val blob = findBlob(database, namespace, name)
            if (blob == null) {
                call.respond(HttpStatusCode.NotFound)
                return@head
            }
            call.response.header(HttpHeaders.ETag, "\"${blob.hash}\"")
            call.response.header(HttpHeaders.ContentLength, blob.size.toString())
            call.response.header(HttpHeaders.ContentType, blob.contentType)
            call.respond(HttpStatusCode.OK)
        }

        /**
         * Marks the file as deleted (a client sees the marker and removes its
         * copy). The bytes go away when the last name pointing at them is gone.
         */
        delete("/{namespace}/{name}") {
            val namespace = call.parameters["namespace"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            if (!call.requireStoreAccess(database, namespace)) return@delete

            val hash = database.write { connection ->
                val previous = connection.query(
                    "SELECT hash FROM blobs WHERE namespace = ? AND name = ? AND deleted = 0",
                    namespace, name
                ) { it.getString(1) } ?: return@write null
                connection.execute(
                    "UPDATE blobs SET deleted = 1, deleted_at = ?, updated_time = ? " +
                        "WHERE namespace = ? AND name = ?",
                    System.currentTimeMillis(), System.currentTimeMillis(), namespace, name
                )
                previous
            }
            if (hash == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("file not found"))
                return@delete
            }
            blobs.removeIfUnreferenced(database, hash)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

// ---------------------------------------------------------------------------
// Access and validation
// ---------------------------------------------------------------------------

/**
 * Token + scope. The namespace is the app's own space: a token created with a
 * scope only reaches the namespaces it names, so one guest's token cannot read
 * another app's files.
 */
private suspend fun ApplicationCall.requireStoreAccess(
    database: Database,
    namespace: String
): Boolean {
    if (!requireToken(database)) return false
    if (!validIdentifier(namespace)) {
        respond(HttpStatusCode.BadRequest, ErrorResponse("invalid namespace"))
        return false
    }
    if (allowedNamespace(namespace)) return true
    respond(HttpStatusCode.Forbidden, ErrorResponse("token not allowed for this namespace"))
    return false
}

private fun ApplicationCall.allowedNamespace(namespace: String): Boolean {
    // requireStoreAccess already called requireToken, so the scope attribute is
    // the one belonging to the token that actually authenticated this request.
    return scopeAllows(callerScope(), namespace)
}

/** A namespace or collection is a short identifier, not a path. */
private fun validIdentifier(value: String): Boolean =
    value.isNotEmpty() && value.length <= 64 && value.matches(IDENTIFIER)

/** File names may have dots and dashes, never a slash (no traversal). */
private fun validName(value: String): Boolean =
    value.isNotEmpty() && value.length <= 128 && value.matches(BLOB_NAME)

/** Record keys are opaque: anything printable, up to the service limit. */
private fun validKey(value: String): Boolean =
    value.isNotEmpty() && value.length <= StoreService.MAX_KEY &&
        value.all { it.code in 32..126 }

private fun findBlob(database: Database, namespace: String, name: String): BlobDto? = database.read {
    it.query(
        "SELECT namespace, name, hash, size, content_type, created_time, updated_time, deleted " +
            "FROM blobs WHERE namespace = ? AND name = ? AND deleted = 0",
        namespace, name
    ) { row -> row.readBlob() }
}

private fun java.sql.ResultSet.readBlob(): BlobDto = BlobDto(
    namespace = getString("namespace") ?: "",
    name = getString("name") ?: "",
    hash = getString("hash") ?: "",
    size = getLong("size"),
    contentType = getString("content_type") ?: "application/octet-stream",
    createdTime = getLong("created_time"),
    updatedTime = getLong("updated_time"),
    deleted = getInt("deleted") != 0
)

/** A sync body is documents, not a file: keep it bounded. */
private const val MAX_SYNC_BODY = 32L * 1024L * 1024L

private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
private val BLOB_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
