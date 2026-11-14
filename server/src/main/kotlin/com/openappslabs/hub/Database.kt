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

package com.openappslabs.hub

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite storage.
 *
 * Writes are serialized by one lock (one process, one writer) and every reader
 * gets its own connection with WAL enabled, so a pull never waits for a push to
 * finish committing.
 */
class Database(private val file: Path) {

    private val log = LoggerFactory.getLogger(Database::class.java)
    private val writeLock = ReentrantLock()

    @Volatile
    private var token: String = ""

    /** Last time the activity ledger was trimmed, so it is not done per sync. */
    @Volatile
    private var lastPurge: Long = 0L

    /** Creates the schema when needed and resolves the sync token. */
    fun init(tokenOverride: String?) {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        open().use { connection ->
            connection.createStatement().use { it.execute("PRAGMA journal_mode=WAL") }
            createSchema(connection)
            token = if (tokenOverride != null) {
                setMeta(connection, KEY_TOKEN, tokenOverride)
                log.info("using the token from the environment (ATENA_TOKEN)")
                tokenOverride
            } else {
                val existing = meta(connection, KEY_TOKEN)
                if (existing != null) {
                    existing
                } else {
                    val generated = newToken()
                    setMeta(connection, KEY_TOKEN, generated)
                    log.info("generated a new sync token (it is stored in the database)")
                    generated
                }
            }
            // The first boot is the "birth" of this database; it labels the
            // master token in the admin screen, so a restart does not reset it.
            if (meta(connection, KEY_CREATED) == null) {
                setMeta(connection, KEY_CREATED, System.currentTimeMillis().toString())
            }
            val removed = purgeOldTombstones(connection)
            if (removed > 0) log.info("purged {} tombstones older than {} days", removed, TOMBSTONE_DAYS)
        }
        log.info("database ready at {}", file.toAbsolutePath())
    }

    fun authToken(): String = token

    /** Constant-time check of the boot token, so it cannot be probed byte by byte. */
    private fun matchesMaster(provided: String?): Boolean {
        val expected = token
        if (provided == null || expected.isEmpty() || provided.length != expected.length) return false
        var diff = 0
        for (i in provided.indices) diff = diff or (provided[i].code xor expected[i].code)
        return diff == 0
    }

    /**
     * Resolves the token a client presented to a label (and its namespace
     * scope): [MASTER_LABEL] for the token printed at boot, the label the admin
     * typed for one created in the admin screen, and `null` when it is wrong or
     * was revoked. A revoked token stops working on the very next request — no
     * restart needed.
     */
    fun tokenIdentity(provided: String?): TokenIdentity? {
        val candidate = provided?.trim()
        if (candidate.isNullOrEmpty()) return null
        if (matchesMaster(candidate)) return TokenIdentity(MASTER_LABEL, "")
        return read { connection ->
            connection.query(
                "SELECT label, scope FROM tokens WHERE token = ? AND revoked = 0", candidate
            ) { TokenIdentity(it.getString(1) ?: "", it.getString(2) ?: "") }
        }
    }

    fun tokenLabel(provided: String?): String? = tokenIdentity(provided)?.label

    fun tokenMatches(provided: String?): Boolean = tokenIdentity(provided) != null

    fun <T> read(block: (Connection) -> T): T = open().use(block)

    /**
     * Runs [block] inside a transaction. Everything that changes the dataset
     * (applying a push and reading the response) happens under the same lock,
     * which keeps the sequence numbers gap-free and monotonic.
     */
    fun <T> write(block: (Connection) -> T): T = writeLock.withLock {
        open().use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (error: Throwable) {
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    private fun open(): Connection {
        val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
        connection.createStatement().use { statement ->
            // A pull and a push can overlap; wait instead of failing with BUSY.
            statement.execute("PRAGMA busy_timeout = 5000")
            // WAL only protects against a process crash; FULL also survives a
            // power loss, which matters because a client clears its "pending"
            // flag as soon as this server confirmed the write.
            statement.execute("PRAGMA synchronous = FULL")
        }
        return connection
    }

    private fun createSchema(connection: Connection) {
        val statements = listOf(
            """
            CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS notes (
                uuid TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                content TEXT NOT NULL DEFAULT '',
                category TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                is_pinned INTEGER NOT NULL DEFAULT 0,
                is_locked INTEGER NOT NULL DEFAULT 0,
                is_trashed INTEGER NOT NULL DEFAULT 0,
                open_in_edit_mode INTEGER DEFAULT NULL,
                is_encrypted INTEGER NOT NULL DEFAULT 0,
                encryption_salt TEXT DEFAULT NULL,
                encrypted_content TEXT DEFAULT NULL,
                format TEXT NOT NULL DEFAULT 'text',
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_notes_sync_seq ON notes (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS tombstones (
                uuid TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_tombstones_sync_seq ON tombstones (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS categories (
                name TEXT PRIMARY KEY,
                created_time INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_categories_sync_seq ON categories (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS agenda_events (
                uuid TEXT PRIMARY KEY,
                title TEXT NOT NULL DEFAULT '',
                description TEXT NOT NULL DEFAULT '',
                location TEXT NOT NULL DEFAULT '',
                start_time INTEGER NOT NULL,
                end_time INTEGER NOT NULL,
                is_all_day INTEGER NOT NULL DEFAULT 0,
                color TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_agenda_events_sync_seq ON agenda_events (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS agenda_tombstones (
                uuid TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_agenda_tombstones_sync_seq ON agenda_tombstones (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS links (
                uuid TEXT PRIMARY KEY,
                url TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                last_opened_time INTEGER NOT NULL DEFAULT 0,
                open_count INTEGER NOT NULL DEFAULT 0,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_links_sync_seq ON links (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS link_tombstones (
                uuid TEXT PRIMARY KEY,
                deleted_at INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_link_tombstones_sync_seq ON link_tombstones (sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL DEFAULT '',
                last_seen INTEGER NOT NULL,
                last_ip TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS tokens (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                label TEXT NOT NULL DEFAULT '',
                token TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                revoked INTEGER NOT NULL DEFAULT 0,
                scope TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            """
            CREATE TABLE IF NOT EXISTS activity (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                at INTEGER NOT NULL,
                token_label TEXT NOT NULL DEFAULT '',
                device_id TEXT NOT NULL DEFAULT '',
                device_name TEXT NOT NULL DEFAULT '',
                ip TEXT NOT NULL DEFAULT '',
                stream TEXT NOT NULL DEFAULT '',
                pushed INTEGER NOT NULL DEFAULT 0,
                pulled INTEGER NOT NULL DEFAULT 0,
                bytes_in INTEGER NOT NULL DEFAULT 0,
                bytes_out INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_activity_at ON activity (at)",
            "CREATE INDEX IF NOT EXISTS index_activity_device ON activity (device_id)",
            /**
             * The universal store: any app, any collection, opaque payload.
             * The composite key is what keeps one app's data apart from another
             * without the server knowing either of them.
             */
            """
            CREATE TABLE IF NOT EXISTS records (
                namespace TEXT NOT NULL,
                collection TEXT NOT NULL,
                record_key TEXT NOT NULL,
                payload TEXT NOT NULL DEFAULT '',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL,
                PRIMARY KEY (namespace, collection, record_key)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_records_stream " +
                "ON records (namespace, collection, sync_seq)",
            """
            CREATE TABLE IF NOT EXISTS record_tombstones (
                namespace TEXT NOT NULL,
                collection TEXT NOT NULL,
                record_key TEXT NOT NULL,
                deleted_at INTEGER NOT NULL,
                sync_seq INTEGER NOT NULL,
                PRIMARY KEY (namespace, collection, record_key)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_record_tombstones_stream " +
                "ON record_tombstones (namespace, collection, sync_seq)",
            /**
             * File metadata. The bytes live on disk, addressed by `hash`, so a
             * download never loads a database row and the same content stored
             * twice occupies one file.
             */
            """
            CREATE TABLE IF NOT EXISTS blobs (
                namespace TEXT NOT NULL,
                name TEXT NOT NULL,
                hash TEXT NOT NULL,
                size INTEGER NOT NULL,
                content_type TEXT NOT NULL DEFAULT 'application/octet-stream',
                created_time INTEGER NOT NULL,
                updated_time INTEGER NOT NULL,
                deleted INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY (namespace, name)
            )
            """.trimIndent(),
            "CREATE INDEX IF NOT EXISTS index_blobs_hash ON blobs (hash)",
            "CREATE INDEX IF NOT EXISTS index_blobs_updated ON blobs (namespace, updated_time)"
        )
        connection.createStatement().use { statement ->
            statements.forEach(statement::execute)
        }
        // Additive migrations for databases created before these columns
        // existed: SQLite has no "ADD COLUMN IF NOT EXISTS", so check first.
        ensureColumn(connection, "tokens", "scope", "TEXT NOT NULL DEFAULT ''")
    }

    /** Adds [column] to [table] when an older database does not have it yet. */
    private fun ensureColumn(connection: Connection, table: String, column: String, ddl: String) {
        val exists = connection.queryList("PRAGMA table_info($table)") { it.getString("name") }
            .contains(column)
        if (!exists) {
            connection.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $ddl") }
            log.info("added column {}.{} (schema upgrade)", table, column)
        }
    }

    /**
     * Remembers which device talked to the server, so the start page can list
     * them. The name is whatever the user typed in the app on first connect;
     * an empty name keeps the previous one, so older clients do not blank it.
     */
    fun recordDevice(connection: Connection, id: String, name: String, ip: String) {
        val key = deviceKey(id, ip)
        val cleanName = name.trim().take(64)
        connection.execute(
            "INSERT INTO devices(id, name, last_seen, last_ip) VALUES(?,?,?,?) " +
                "ON CONFLICT(id) DO UPDATE SET " +
                "name = CASE WHEN excluded.name = '' THEN devices.name ELSE excluded.name END, " +
                "last_seen = excluded.last_seen, last_ip = excluded.last_ip",
            key, cleanName, System.currentTimeMillis(), ip.take(64)
        )
    }

    fun devices(connection: Connection): List<DeviceDto> = connection.queryList(
        "SELECT id, name, last_seen, last_ip FROM devices ORDER BY last_seen DESC LIMIT 50"
    ) { DeviceDto(it.getString(1), it.getString(2), it.getLong(3), it.getString(4)) }

    // ---------------------------------------------------------------------
    // Tokens created by the admin
    // ---------------------------------------------------------------------

    /** Creates a token for one more client and returns the secret to show once. */
    fun createToken(label: String, scope: String = ""): TokenDto {
        val cleanLabel = label.trim().take(64).ifEmpty { "sem nome" }
        // Empty scope = every namespace; otherwise a comma-separated allowlist.
        val cleanScope = scope.trim().take(256)
        val secret = newToken()
        val createdAt = System.currentTimeMillis()
        write { connection ->
            connection.execute(
                "INSERT INTO tokens(label, token, created_at, revoked, scope) VALUES(?,?,?,0,?)",
                cleanLabel, secret, createdAt, cleanScope
            )
        }
        log.info("token created for \"{}\" (scope: {})", cleanLabel, cleanScope.ifEmpty { "all" })
        return TokenDto(
            id = 0,
            label = cleanLabel,
            prefix = mask(secret),
            createdAt = createdAt,
            revoked = false,
            scope = cleanScope,
            secret = secret
        )
    }

    fun revokeToken(id: Long): Boolean = write { connection ->
        connection.execute("UPDATE tokens SET revoked = 1 WHERE id = ?", id) > 0
    }

    fun tokens(connection: Connection): List<TokenDto> = connection.queryList(
        "SELECT id, label, token, created_at, revoked, scope FROM tokens ORDER BY created_at DESC"
    ) {
        TokenDto(
            id = it.getLong(1),
            label = it.getString(2),
            prefix = mask(it.getString(3)),
            createdAt = it.getLong(4),
            revoked = it.getInt(5) != 0,
            scope = it.getString(6) ?: ""
        )
    }

    /**
     * The boot token, shown in the admin screen like any other: the admin needs
     * it to configure a client, and the page is already behind the login.
     */
    fun masterTokenDto(connection: Connection): TokenDto = TokenDto(
        id = 0,
        label = MASTER_LABEL,
        prefix = mask(token),
        createdAt = connection.query(
            "SELECT value FROM meta WHERE key = ?", KEY_CREATED
        ) { it.getString(1)?.toLongOrNull() } ?: 0L,
        revoked = false,
        secret = token
    )

    // ---------------------------------------------------------------------
    // Activity: who talked to the hub, what they sent and what they got
    // ---------------------------------------------------------------------

    /**
     * One line per sync round. Written inside the sync transaction, so the
     * dashboard never shows a transfer the server did not commit.
     */
    fun recordActivity(connection: Connection, entry: SyncActivity) {
        connection.execute(
            "INSERT INTO activity(at, token_label, device_id, device_name, ip, stream, " +
                "pushed, pulled, bytes_in, bytes_out) VALUES(?,?,?,?,?,?,?,?,?,?)",
            System.currentTimeMillis(), entry.tokenLabel.take(64),
            entry.deviceId.take(128), entry.deviceName.take(64), entry.ip.take(64),
            entry.stream, entry.pushed, entry.pulled, entry.bytesIn, entry.bytesOut
        )
        purgeOldActivity(connection)
    }

    fun recentActivity(connection: Connection, limit: Int = 60): List<ActivityDto> =
        connection.queryList(
            "SELECT at, token_label, device_id, device_name, ip, stream, pushed, pulled, " +
                "bytes_in, bytes_out FROM activity ORDER BY at DESC, id DESC LIMIT ?",
            limit
        ) {
            ActivityDto(
                at = it.getLong(1),
                tokenLabel = it.getString(2),
                deviceId = it.getString(3),
                deviceName = it.getString(4),
                ip = it.getString(5),
                stream = it.getString(6),
                pushed = it.getInt(7),
                pulled = it.getInt(8),
                bytesIn = it.getLong(9),
                bytesOut = it.getLong(10)
            )
        }

    /**
     * One row per device: when it last synced, on which stream, how much it
     * sent and received in total, and which token it used last.
     */
    fun deviceSummaries(connection: Connection): List<DeviceSummaryDto> =
        connection.queryList(
            """
            SELECT d.id, d.name, d.last_ip, a.stream, a.at, a.token_label,
                   s.pushed, s.pulled, s.bytes_in, s.bytes_out, s.rounds
            FROM devices d
            LEFT JOIN activity a ON a.id = (
                SELECT id FROM activity WHERE device_id = d.id ORDER BY id DESC LIMIT 1
            )
            LEFT JOIN (
                SELECT device_id, SUM(pushed) AS pushed, SUM(pulled) AS pulled,
                       SUM(bytes_in) AS bytes_in, SUM(bytes_out) AS bytes_out, COUNT(*) AS rounds
                FROM activity GROUP BY device_id
            ) s ON s.device_id = d.id
            ORDER BY COALESCE(a.at, d.last_seen) DESC
            LIMIT 100
            """.trimIndent()
        ) {
            DeviceSummaryDto(
                id = it.getString(1),
                name = it.getString(2),
                ip = it.getString(3),
                lastStream = it.getString(4) ?: "",
                lastSync = it.getLong(5),
                tokenLabel = it.getString(6) ?: "",
                pushed = it.getInt(7),
                pulled = it.getInt(8),
                bytesIn = it.getLong(9),
                bytesOut = it.getLong(10),
                rounds = it.getInt(11)
            )
        }

    /** Keeps the ledger bounded: it is a dashboard, not an audit trail. */
    private fun purgeOldActivity(connection: Connection) {
        val now = System.currentTimeMillis()
        if (now - lastPurge < PURGE_INTERVAL_MS) return
        lastPurge = now
        val cutoff = now - ACTIVITY_DAYS * 24L * 60L * 60L * 1000L
        val removed = connection.execute("DELETE FROM activity WHERE at < ?", cutoff)
        if (removed > 0) log.info("purged {} activity rows older than {} days", removed, ACTIVITY_DAYS)
    }

    private fun purgeOldTombstones(connection: Connection): Int {
        val cutoff = System.currentTimeMillis() - TOMBSTONE_DAYS * 24L * 60L * 60L * 1000L
        var removed = 0
        for (table in listOf("tombstones", "agenda_tombstones", "link_tombstones", "record_tombstones")) {
            removed += connection.prepareStatement("DELETE FROM $table WHERE deleted_at < ?").use { statement ->
                statement.setLong(1, cutoff)
                statement.executeUpdate()
            }
        }
        return removed
    }

    private fun meta(connection: Connection, key: String): String? =
        connection.prepareStatement("SELECT value FROM meta WHERE key = ?").use { statement ->
            statement.setString(1, key)
            statement.executeQuery().use { result -> if (result.next()) result.getString(1) else null }
        }

    private fun setMeta(connection: Connection, key: String, value: String): String {
        connection.prepareStatement(
            "INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value"
        ).use { statement ->
            statement.setString(1, key)
            statement.setString(2, value)
            statement.executeUpdate()
        }
        return value
    }

    companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_SEQ = "sync_seq"

        /** The agenda has its own stream, so its clients' cursors are independent. */
        const val KEY_AGENDA_SEQ = "agenda_sync_seq"

        /** The link inbox is a third stream, with its own cursor. */
        const val KEY_LINKS_SEQ = "links_sync_seq"

        /** Label that identifies the token printed at boot in the admin screen. */
        const val MASTER_LABEL = "master"

        private const val KEY_CREATED = "created_at"
        private const val TOMBSTONE_DAYS = 180L
        private const val ACTIVITY_DAYS = 30L
        private const val PURGE_INTERVAL_MS = 60L * 60L * 1000L
    }
}

/** A secret is never printed whole: the admin screen shows these. */
internal fun mask(secret: String): String = when {
    secret.length <= 8 -> "••••"
    else -> secret.take(4) + "…" + secret.takeLast(4)
}

/**
 * Who a request is: the label shown in the admin, and the namespace scope of
 * the token itself. The scope is carried from authentication, never looked up
 * by label — two tokens may share a label.
 */
data class TokenIdentity(val label: String, val scope: String = "")

/**
 * The key a device is known by. A client that sends no id still shows up, keyed
 * by its address, so the dashboard can list it instead of hiding the traffic.
 */
internal fun deviceKey(id: String, ip: String): String = id.trim().ifEmpty { "ip:$ip" }.take(128)

// ---------------------------------------------------------------------------
// Universal store: the namespace/collection cursors and its metadata.
// ---------------------------------------------------------------------------

/** The store cursor of one collection; `0` when nothing was ever written. */
internal fun storeSeqKey(namespace: String, collection: String): String =
    "store_seq:$namespace/$collection"

/**
 * Whether a token's scope covers [namespace]. An empty scope means "everything"
 * (the boot token, and the tokens created before scopes existed); otherwise the
 * scope is a comma-separated list of namespaces, each optionally ending in `*`.
 */
internal fun scopeAllows(scope: String, namespace: String): Boolean {
    val trimmed = scope.trim()
    if (trimmed.isEmpty() || trimmed == "*") return true
    return trimmed.split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .any { entry ->
            when {
                entry == "*" -> true
                entry.endsWith("*") -> namespace.startsWith(entry.dropLast(1))
                else -> namespace == entry
            }
        }
}

/** Small JDBC helpers, kept local to the server. */
internal fun Connection.execute(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeUpdate()
    }

internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result -> if (result.next()) map(result) else null }
    }

internal fun <T> Connection.queryList(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        args.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
        statement.executeQuery().use { result ->
            buildList {
                while (result.next()) add(map(result))
            }
        }
    }
