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

package com.openappslabs.jotter.data.crypto

import com.openappslabs.jotter.data.model.Note
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Per-note encryption.
 *
 * A note that opts in stores its plaintext nowhere: `title` and `content` are
 * blank in the database and the AES-GCM ciphertext lives in `encryptedContent`.
 * The key is derived from the note's own password (PBKDF2-HMAC-SHA256) using a
 * random salt stored next to it, so every note can use a different password and
 * nothing depends on the app lock PIN.
 *
 * Derived keys are cached in memory for the current app run: unlocking a note
 * once keeps it readable until Jotter exits (see `sessionKeys`). The password
 * itself is never stored or cached.
 */
object NoteCrypto {

    /** Shortest password accepted when enabling encryption. */
    const val MIN_PASSWORD_LENGTH = 4

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val random = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    /** Keys derived in this run of the app, indexed by note id. */
    private val sessionKeys = ConcurrentHashMap<Int, SecretKey>()

    @Serializable
    private data class Plaintext(val title: String, val content: String)

    // ---------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------

    fun isUnlocked(noteId: Int): Boolean = sessionKeys.containsKey(noteId)

    fun keyFor(noteId: Int): SecretKey? = sessionKeys[noteId]

    /** Caches the key so the note stays readable during this app run. */
    fun rememberKey(noteId: Int, key: SecretKey) {
        if (noteId > 0) sessionKeys[noteId] = key
    }

    fun forgetKey(noteId: Int) {
        sessionKeys.remove(noteId)
    }

    fun forgetAll() {
        sessionKeys.clear()
    }

    // ---------------------------------------------------------------------
    // Key derivation and payload
    // ---------------------------------------------------------------------

    fun newSalt(): String = encode(ByteArray(SALT_BYTES).also(random::nextBytes))

    fun deriveKey(password: String, salt: String): SecretKey {
        val spec = PBEKeySpec(password.toCharArray(), decode(salt), ITERATIONS, KEY_BITS)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    /** Returns Base64(iv ‖ ciphertext) for the note's title and content. */
    fun encrypt(key: SecretKey, title: String, content: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val plaintext = json.encodeToString(Plaintext(title, content))
        val ciphertext = cipher.doFinal(plaintext.encodeToByteArray())
        return encode(iv + ciphertext)
    }

    /** Returns `null` when the payload cannot be authenticated (wrong key). */
    fun decrypt(key: SecretKey, payload: String): Pair<String, String>? = runCatching {
        val bytes = decode(payload)
        if (bytes.size <= IV_BYTES) return null
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val clear = cipher.doFinal(bytes.copyOfRange(IV_BYTES, bytes.size))
        val plaintext = json.decodeFromString<Plaintext>(clear.decodeToString())
        plaintext.title to plaintext.content
    }.getOrNull()

    /**
     * Derives the key for this note's password and proves it is correct by
     * decrypting the stored payload. Returns `null` for a wrong password.
     */
    fun verifyAndDerive(note: Note, password: String): SecretKey? {
        val salt = note.encryptionSalt ?: return null
        val payload = note.encryptedContent ?: return null
        val key = runCatching { deriveKey(password, salt) }.getOrNull() ?: return null
        return if (decrypt(key, payload) != null) key else null
    }

    /**
     * Read helper: fills in the plaintext when the note is encrypted and its
     * key is available for this run. Locked notes get blanked out so callers
     * can never accidentally show or re-save ciphertext as if it were text.
     */
    fun decryptIfUnlocked(note: Note): Note {
        if (!note.isEncrypted) return note
        val key = sessionKeys[note.id] ?: return note.copy(title = "", content = "")
        val payload = note.encryptedContent ?: return note.copy(title = "", content = "")
        val plaintext = decrypt(key, payload) ?: return note.copy(title = "", content = "")
        return note.copy(title = plaintext.first, content = plaintext.second)
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)
}
