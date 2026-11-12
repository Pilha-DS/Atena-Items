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

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Server configuration. Everything comes from environment variables so the
 * container needs no config file.
 *
 * The `ATENA_*` names are the current ones; the older `JOTTER_*` names keep
 * working so an existing deployment can upgrade without touching its compose
 * file (or its saved clients).
 *
 *  - `ATENA_PORT` / `JOTTER_PORT`            port to listen on (default 8080)
 *  - `ATENA_HOST` / `JOTTER_HOST`            bind address (default 0.0.0.0)
 *  - `ATENA_DATA_DIR` / `JOTTER_DATA_DIR`    where the SQLite file lives
 *  - `ATENA_TOKEN` / `JOTTER_SERVER_TOKEN`   fixed token; otherwise generated
 *  - `ATENA_PUBLIC_URL` / `JOTTER_PUBLIC_URL` shown on the start page
 */
data class Config(
    val port: Int,
    val host: String,
    val dataDir: Path,
    val tokenOverride: String?,
    val publicUrl: String?
) {
    /**
     * The hub keeps one SQLite file. A deployment that already has
     * `jotter-server.db` keeps using it, so upgrading the image never looks
     * like an empty server to the apps that are syncing.
     */
    val databaseFile: Path
        get() {
            val current = dataDir.resolve("atena-hub.db")
            if (Files.exists(current)) return current
            val legacy = dataDir.resolve(LEGACY_DATABASE)
            if (Files.exists(legacy)) return legacy
            return current
        }

    val isUsingLegacyDatabase: Boolean
        get() = databaseFile.fileName.toString() == LEGACY_DATABASE

    companion object {
        const val LEGACY_DATABASE = "jotter-server.db"

        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun value(vararg keys: String): String? = keys
                .firstNotNullOfOrNull { env[it]?.trim()?.takeIf(String::isNotEmpty) }

            return Config(
                port = value("ATENA_PORT", "JOTTER_PORT")?.toIntOrNull() ?: 8080,
                host = value("ATENA_HOST", "JOTTER_HOST") ?: "0.0.0.0",
                dataDir = Paths.get(value("ATENA_DATA_DIR", "JOTTER_DATA_DIR") ?: "/data"),
                tokenOverride = value("ATENA_TOKEN", "JOTTER_SERVER_TOKEN"),
                publicUrl = value("ATENA_PUBLIC_URL", "JOTTER_PUBLIC_URL")
            )
        }
    }
}

/** Build-time identity, shown by `/health`, `/api/v1/ping` and the start page. */
object BuildInfo {
    const val NAME = "atena-hub"
    const val VERSION = "2.0.0"
}

internal fun newToken(): String = UUID.randomUUID().toString().replace("-", "")
