/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToastSpoolTest {

    private val dir: Path = Files.createTempDirectory("agenda-spool")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun request(name: String, content: String, ageMillis: Long = 0): Path {
        val file = dir.resolve(name).createFile()
        file.writeText(content)
        if (ageMillis > 0) {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() - ageMillis))
        }
        return file
    }

    @Test
    fun drains_fresh_requests_and_forgets_the_files() {
        val first = request("n-1-1.req", "Primeiro\ncorpo um\n")
        val second = request("u-2-2.req", "Segundo\ncorpo dois\n")

        val requests = ToastSpool.drain(dir, System.currentTimeMillis())

        assertEquals(listOf("Primeiro", "Segundo"), requests.map { it.title })
        assertEquals(listOf(false, true), requests.map { it.urgent })
        assertFalse(Files.exists(first))
        assertFalse(Files.exists(second))
    }

    @Test
    fun drops_expired_requests_without_showing_them() {
        request("n-1-1.req", "De ontem\n", ageMillis = ToastRequests.MAX_AGE_MS + 1_000)
        request("n-2-2.req", "De agora\n")

        val requests = ToastSpool.drain(dir, System.currentTimeMillis())

        assertEquals(listOf("De agora"), requests.map { it.title })
        assertEquals(0, Files.list(dir).use { it.count() })
    }

    @Test
    fun ignores_files_that_are_not_requests() {
        request("n-1-1.req.tmp", "Escrita em andamento\n")
        request("readme.txt", "nada a ver\n")
        request("u-3-3.req", "Válido\n")

        val requests = ToastSpool.drain(dir, System.currentTimeMillis())

        assertEquals(listOf("Válido"), requests.map { it.title })
        assertTrue(Files.exists(dir.resolve("n-1-1.req.tmp")))
        assertTrue(Files.exists(dir.resolve("readme.txt")))
    }

    @Test
    fun a_missing_directory_is_an_empty_spool() {
        val missing = dir.resolve("nope")
        assertTrue(ToastSpool.drain(missing, System.currentTimeMillis()).isEmpty())
    }
}
