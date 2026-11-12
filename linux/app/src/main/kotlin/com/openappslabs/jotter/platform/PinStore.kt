/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Jotter
 *
 * Jotter is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.jotter.platform

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Desktop replacement for Android biometrics: notes are protected by a PIN.
 *
 * NOTE: the SQLite database itself is not encrypted (same as the Android app).
 * The PIN is only an access gate; it is stored as a salted PBKDF2 hash.
 */
object PinStore {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256

    private val random = SecureRandom()

    fun hasPin(): Boolean = readProperties()["hash"] != null

    fun setPin(pin: String): Boolean {
        if (pin.length < 4) return false
        val salt = ByteArray(16).also(random::nextBytes)
        val hash = hash(pin, salt)
        val props = Properties().apply {
            setProperty("salt", Base64.getEncoder().encodeToString(salt))
            setProperty("hash", Base64.getEncoder().encodeToString(hash))
            setProperty("iterations", ITERATIONS.toString())
        }
        Xdg.pinFile.parent?.let { File(it.toString()).mkdirs() }
        Xdg.pinFile.toFile().outputStream().use { props.store(it, "Jotter app lock") }
        return true
    }

    fun verify(pin: String): Boolean {
        val props = readProperties()
        val salt = props.getProperty("salt")?.let { Base64.getDecoder().decode(it) } ?: return false
        val expected = props.getProperty("hash")?.let { Base64.getDecoder().decode(it) } ?: return false
        val actual = hash(pin, salt)
        return actual.contentEquals(expected)
    }

    fun clear() {
        Xdg.pinFile.toFile().delete()
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun readProperties(): Properties {
        val props = Properties()
        val file = Xdg.pinFile.toFile()
        if (file.exists()) {
            runCatching { file.inputStream().use(props::load) }
        }
        return props
    }
}
