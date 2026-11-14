package org.fossify.calendar.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links a local event to the id it has on the Atena Hub.
 *
 * The app never had a uuid: `_ID` is local and changes when a row is recreated.
 * This side table carries the uuid and the version the server already knows, so
 * a sync can tell a new local event from an edited one.
 */
@Entity(tableName = "agenda_sync_events", indices = [(Index(value = ["uuid"], unique = true))])
data class AgendaSyncEvent(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: Long,
    @ColumnInfo(name = "uuid") val uuid: String,
    /** Event.lastUpdated of the version the server acknowledged. */
    @ColumnInfo(name = "synced_time") val syncedTime: Long
)

/**
 * A deletion waiting to be pushed. Kept after the event row is gone, so the
 * hub learns about it instead of handing the event back on the next pull.
 */
@Entity(tableName = "agenda_sync_tombstones")
data class AgendaSyncTombstone(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long
)
