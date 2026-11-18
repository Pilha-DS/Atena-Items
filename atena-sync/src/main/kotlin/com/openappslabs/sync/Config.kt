/*
 * Copyright (c) 2026 Open Apps Labs
 *
 * This file is part of Atena Sync
 *
 * Atena Sync is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * Atena Sync is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with Atena Sync.
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.openappslabs.sync

import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID

/**
 * Daemon configuration from environment + XDG paths.
 *
 *  - `ATENA_SYNC_LOOPBACK_PORT`  default 18765 (127.0.0.1 only)
 *  - `ATENA_SYNC_LAN_PORT`       default 18766
 *  - `ATENA_SYNC_DATA_DIR`       default ~/.local/share/atena-sync
 *  - `ATENA_SYNC_TOKEN`          pairing token; otherwise generated and stored
 *  - `ATENA_SYNC_HUB_URL`        optional internet fallback (Atena Hub)
 *  - `ATENA_SYNC_HUB_TOKEN`      token for the hub
 *  - `ATENA_SYNC_DEVICE_NAME`    shown to peers
 *  - `ATENA_SYNC_DEVICE_ID`      stable id; otherwise generated
 *  - `ATENA_SYNC_DISABLE_MDNS`   set to 1 to skip discovery
 */
data class Config(
    val loopbackPort: Int,
    val lanPort: Int,
    val dataDir: Path,
    val tokenOverride: String?,
    val hubUrl: String?,
    val hubToken: String?,
    val deviceName: String,
    val deviceIdOverride: String?,
    val disableMdns: Boolean
) {
    val databaseFile: Path get() = dataDir.resolve("atena-sync.db")

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun value(vararg keys: String): String? = keys
                .firstNotNullOfOrNull { env[it]?.trim()?.takeIf(String::isNotEmpty) }

            val xdgData = value("XDG_DATA_HOME")?.let { Paths.get(it) }
                ?: Paths.get(System.getProperty("user.home"), ".local", "share")
            val defaultData = xdgData.resolve("atena-sync")

            return Config(
                loopbackPort = value("ATENA_SYNC_LOOPBACK_PORT")?.toIntOrNull() ?: DEFAULT_LOOPBACK_PORT,
                lanPort = value("ATENA_SYNC_LAN_PORT")?.toIntOrNull() ?: DEFAULT_LAN_PORT,
                dataDir = Paths.get(value("ATENA_SYNC_DATA_DIR") ?: defaultData.toString()),
                tokenOverride = value("ATENA_SYNC_TOKEN"),
                hubUrl = value("ATENA_SYNC_HUB_URL")?.let { normalizeHubUrl(it) },
                hubToken = value("ATENA_SYNC_HUB_TOKEN"),
                deviceName = value("ATENA_SYNC_DEVICE_NAME")
                    ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.take(40)
                    ?: "atena-sync",
                deviceIdOverride = value("ATENA_SYNC_DEVICE_ID"),
                disableMdns = value("ATENA_SYNC_DISABLE_MDNS") in setOf("1", "true", "yes")
            )
        }
    }
}

internal fun newToken(): String = UUID.randomUUID().toString().replace("-", "")

internal fun normalizeHubUrl(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
    val host = trimmed.substringBefore('/')
    val isPrivate = host.startsWith("192.168.") ||
        host.startsWith("10.") ||
        host.matches(Regex("""127\..*""")) ||
        host.startsWith("172.") ||
        host.startsWith("[") ||
        host.all { it.isDigit() || it == '.' || it == ':' }
    val scheme = if (isPrivate) "http" else "https"
    return "$scheme://$trimmed"
}

internal fun ensureDir(path: Path) {
    Files.createDirectories(path)
}
