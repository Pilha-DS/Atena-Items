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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/**
 * Files for the universal store.
 *
 * **Content-addressed**: the bytes of a file are stored under the SHA-256 of
 * their own content, so uploading the same photo from the phone and the desktop
 * costs one copy, and a re-upload of an unchanged file costs one hash. The
 * database only holds metadata; a download never touches a row, it streams the
 * file from disk.
 *
 * **Bounded**: the caller passes the limit and the stream is copied until it
 * hits it, so a client bug cannot fill the disk.
 */
class BlobStore(private val root: Path, private val maxBytes: Long) {

    private val log = LoggerFactory.getLogger(BlobStore::class.java)
    private val temp = root.resolve("tmp")

    init {
        Files.createDirectories(temp)
    }

    /** Where the bytes for [hash] live. Two levels keep the directory small. */
    fun file(hash: String): Path = root.resolve(hash.take(2)).resolve(hash)

    fun exists(hash: String): Boolean = Files.isRegularFile(file(hash))

    /**
     * Reads [input] to the end, writing it to a temporary file and hashing on
     * the way. Returns `null` when the content is larger than the limit; the
     * temporary file is deleted in that case.
     *
     * The blocking copy runs on the IO dispatcher: it must not occupy a Netty
     * event-loop thread, or one upload would delay every other client.
     */
    suspend fun store(input: java.io.InputStream): StoredBlob? = withContext(Dispatchers.IO) {
        val temporary = temp.resolve(UUID.randomUUID().toString() + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var tooLarge = false
        try {
            Files.newOutputStream(temporary).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    if (size > maxBytes) {
                        // Stops reading here; the caller deletes the temporary
                        // file and answers 413.
                        tooLarge = true
                        return@use
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            if (tooLarge) {
                Files.deleteIfExists(temporary)
                return@withContext null
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = file(hash)
            Files.createDirectories(destination.parent)
            if (Files.exists(destination)) {
                // Same content is already stored: the upload only refreshed
                // metadata, so the temporary copy is thrown away.
                Files.deleteIfExists(temporary)
            } else {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            StoredBlob(hash = hash, size = size)
        } catch (error: Throwable) {
            Files.deleteIfExists(temporary)
            throw error
        }
    }

    /** Removes the bytes of [hash]; the caller already checked no row uses it. */
    fun remove(hash: String) {
        runCatching { Files.deleteIfExists(file(hash)) }
            .onFailure { log.warn("could not remove blob {}: {}", hash, it.message) }
    }

    /**
     * Drops the bytes only when no live name points at [hash]. Two names can
     * share one file (same content), and one of them going away must not break
     * the other.
     */
    fun removeIfUnreferenced(database: Database, hash: String) {
        val referenced = database.read { connection ->
            connection.query(
                "SELECT 1 FROM blobs WHERE hash = ? AND deleted = 0 LIMIT 1", hash
            ) { true } != null
        }
        if (!referenced) remove(hash)
    }

    /** Deletes leftovers from uploads that died mid-way (process killed). */
    fun purgeStaleTemporaries() {
        runCatching {
            Files.list(temp).use { entries ->
                entries.filter { Files.isRegularFile(it) }.forEach { stale ->
                    runCatching { Files.deleteIfExists(stale) }
                }
            }
        }
    }
}

data class StoredBlob(val hash: String, val size: Long)
