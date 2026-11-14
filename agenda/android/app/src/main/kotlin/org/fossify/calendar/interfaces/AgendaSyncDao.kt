package org.fossify.calendar.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.calendar.models.AgendaSyncEvent
import org.fossify.calendar.models.AgendaSyncTombstone

@Dao
interface AgendaSyncDao {
    @Query("SELECT * FROM agenda_sync_events")
    fun getAll(): List<AgendaSyncEvent>

    @Query("SELECT * FROM agenda_sync_events WHERE uuid = :uuid")
    fun getByUuid(uuid: String): AgendaSyncEvent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertLink(link: AgendaSyncEvent)

    @Query("DELETE FROM agenda_sync_events WHERE event_id = :eventId")
    fun deleteLinkByEventId(eventId: Long)

    @Query("DELETE FROM agenda_sync_events WHERE uuid = :uuid")
    fun deleteLinkByUuid(uuid: String)

    @Query("SELECT * FROM agenda_sync_tombstones")
    fun getTombstones(): List<AgendaSyncTombstone>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTombstone(tombstone: AgendaSyncTombstone)

    @Query("DELETE FROM agenda_sync_tombstones WHERE uuid = :uuid")
    fun deleteTombstone(uuid: String)

    /** After reconnecting to a different hub, everything has to be sent again. */
    @Query("UPDATE agenda_sync_events SET synced_time = 0")
    fun resetAllSyncedTimes()
}
