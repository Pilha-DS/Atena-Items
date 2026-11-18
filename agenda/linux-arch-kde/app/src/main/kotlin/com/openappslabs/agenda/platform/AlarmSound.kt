/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Agenda
 *
 * Agenda is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Agenda is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Agenda.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.agenda.platform

import java.awt.Toolkit
import kotlin.concurrent.thread

/**
 * The ring of a quick timer.
 *
 * The toast is silent by nature, so the alarm has to come from here: the
 * system's own alarm event through `canberra-gtk-play` (the freedesktop sound
 * theme Plasma uses), then the sound file directly through whichever player
 * exists, and finally the toolkit beep. None of them is a dependency: without
 * a player the beep still rings.
 *
 * The call returns at once — the player is a separate process and the timer
 * thread must not wait for it.
 */
object AlarmSound {

    private const val THEME_EVENT = "alarm-clock-elapsed"
    private const val SOUND_FILE = "/usr/share/sounds/freedesktop/stereo/alarm-clock-elapsed.oga"

    fun play() {
        thread(name = "agenda-alarm-sound", isDaemon = true) {
            runCatching { tryPlayers() }
                .onFailure { runCatching { Toolkit.getDefaultToolkit().beep() } }
        }
    }

    /** First command that exists and succeeds wins; the beep is the last resort. */
    private fun tryPlayers() {
        val players = listOf(
            listOf("canberra-gtk-play", "-i", THEME_EVENT),
            listOf("paplay", SOUND_FILE),
            listOf("pw-play", SOUND_FILE),
            listOf("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", SOUND_FILE)
        )
        for (player in players) {
            if (run(player)) return
        }
        Toolkit.getDefaultToolkit().beep()
    }

    private fun run(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        process.waitFor() == 0
    }.getOrDefault(false)
}
