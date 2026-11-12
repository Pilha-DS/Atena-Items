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

package com.openappslabs.jotter.server

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Server configuration, all of it from environment variables so the container
 * needs no config file:
 *
 *  - `JOTTER_PORT`          port to listen on (default 8080)
 *  - `JOTTER_HOST`          bind address (default 0.0.0.0)
 *  - `JOTTER_DATA_DIR`      where the SQLite file lives (default /data)
 *  - `JOTTER_SERVER_TOKEN`  fixed sync token; when unset one is generated on
 *                           first start and printed in the log
 *  - `JOTTER_PUBLIC_URL`    shown on the start page as a hint for the clients
 */
data class Config(
    val port: Int,
    val host: String,
    val dataDir: Path,
    val tokenOverride: String?,
    val publicUrl: String?
) {
    val databaseFile: Path get() = dataDir.resolve("jotter-server.db")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun value(key: String): String? = env[key]?.trim()?.takeIf { it.isNotEmpty() }
            return Config(
                port = value("JOTTER_PORT")?.toIntOrNull() ?: 8080,
                host = value("JOTTER_HOST") ?: "0.0.0.0",
                dataDir = Paths.get(value("JOTTER_DATA_DIR") ?: "/data"),
                tokenOverride = value("JOTTER_SERVER_TOKEN"),
                publicUrl = value("JOTTER_PUBLIC_URL")
            )
        }
    }
}
