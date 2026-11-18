/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 */

package com.openappslabs.agenda.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToastRequestsTest {

    @Test
    fun the_first_line_is_the_title_and_the_rest_is_the_body() {
        val request = ToastRequests.parse("n-1-1.req", "Tempo esgotado\nIr ao mercado\nLevar a lista\n")
        assertEquals("Tempo esgotado", request?.title)
        assertEquals("Ir ao mercado\nLevar a lista", request?.body)
        assertEquals(false, request?.urgent)
    }

    @Test
    fun a_title_without_a_body_is_valid() {
        val request = ToastRequests.parse("n-1-1.req", "Compilado\n")
        assertEquals("Compilado", request?.title)
        assertEquals("", request?.body)
    }

    @Test
    fun the_file_name_says_whether_it_is_urgent() {
        assertTrue(ToastRequests.parse("u-1-1.req", "Fim do timer")?.urgent == true)
        assertTrue(ToastRequests.parse("n-1-1.req", "Resumo do dia")?.urgent == false)
    }

    @Test
    fun anything_that_is_not_a_request_is_ignored() {
        assertNull(ToastRequests.parse("n-1-1.tmp", "Metade de um arquivo"))
        assertNull(ToastRequests.parse("n-1-1.req", ""))
        assertNull(ToastRequests.parse("n-1-1.req", "   \n corpo sem título"))
    }

    @Test
    fun a_request_stops_being_fresh_after_five_minutes() {
        assertTrue(ToastRequests.isFresh(ageMillis = 0))
        assertTrue(ToastRequests.isFresh(ageMillis = ToastRequests.MAX_AGE_MS))
        assertTrue(!ToastRequests.isFresh(ageMillis = ToastRequests.MAX_AGE_MS + 1))
        // A clock that went back leaves a negative age; that is still fresh.
        assertTrue(ToastRequests.isFresh(ageMillis = -60_000))
    }
}
