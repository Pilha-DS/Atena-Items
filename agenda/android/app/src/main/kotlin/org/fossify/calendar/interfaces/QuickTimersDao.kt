package org.fossify.calendar.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.calendar.models.QuickTimer
import org.fossify.calendar.models.QuickTimerTombstone

/**
 * Quick timers ("assuntos rápidos") plus the deletions still to be pushed.
 *
 * Each device rings its own timers: [QuickTimer.notified] never travels, only
 * the deadline and the sync bookkeeping do.
 */
@Suppress("TooManyFunctions")
@Dao
interface QuickTimersDao {
    /** Ascending, so the timer that rings first is on top. */
    @Query("SELECT * FROM quick_timers ORDER BY end_time, title")
    fun getAll(): List<QuickTimer>

    @Query("SELECT * FROM quick_timers WHERE uuid = :uuid")
    fun getByUuid(uuid: String): QuickTimer?

    @Query("SELECT * FROM quick_timers WHERE needs_sync = 1 ORDER BY updated_time")
    fun getPending(): List<QuickTimer>

    @Insert
    fun insert(timer: QuickTimer): Long

    @Query("DELETE FROM quick_timers WHERE uuid = :uuid")
    fun deleteByUuid(uuid: String)

    @Query("UPDATE quick_timers SET notified = 1 WHERE uuid = :uuid")
    fun markNotified(uuid: String)

    @Query("UPDATE quick_timers SET needs_sync = 0 WHERE uuid IN (:uuids)")
    fun markSynced(uuids: List<String>)

    /** Re-arming after a new deadline: the countdown has to ring again. */
    @Query(
        "UPDATE quick_timers SET title = :title, end_time = :endTime, " +
            "created_time = :createdTime, updated_time = :updatedTime, " +
            "needs_sync = 0, notified = 0 WHERE uuid = :uuid"
    )
    fun applyPulledEdit(
        uuid: String,
        title: String,
        endTime: Long,
        createdTime: Long,
        updatedTime: Long
    )

    /** A newer version that keeps the same deadline does not ring twice. */
    @Query(
        "UPDATE quick_timers SET title = :title, created_time = :createdTime, " +
            "updated_time = :updatedTime, needs_sync = 0 WHERE uuid = :uuid"
    )
    fun applyPulledRename(uuid: String, title: String, createdTime: Long, updatedTime: Long)

    @Query("SELECT * FROM quick_timer_tombstones ORDER BY deleted_at")
    fun getTombstones(): List<QuickTimerTombstone>

    @Query("SELECT * FROM quick_timer_tombstones WHERE uuid = :uuid")
    fun getTombstone(uuid: String): QuickTimerTombstone?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertTombstone(tombstone: QuickTimerTombstone)

    @Query("DELETE FROM quick_timer_tombstones WHERE uuid IN (:uuids)")
    fun deleteTombstones(uuids: List<String>)

    @Query("DELETE FROM quick_timer_tombstones WHERE uuid = :uuid")
    fun deleteTombstone(uuid: String)

    /** After reconnecting to a different hub, everything has to be sent again. */
    @Query("UPDATE quick_timers SET needs_sync = 1")
    fun resetAllPending()
}
