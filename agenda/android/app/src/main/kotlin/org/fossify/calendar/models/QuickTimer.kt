package org.fossify.calendar.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A quick timer ("assunto rápido"): a short, named countdown, like the phone's
 * clock timer. "Ir ao mercado em 20 minutos" is one of these, not an event.
 *
 * Only [endTime] matters after creation, so the countdown survives a reboot
 * and can be shared with the other devices. [needsSync] keeps the row in the
 * next push until the hub confirms it; [notified] is local — each device rings
 * once, on its own.
 */
@Entity(tableName = "quick_timers", indices = [(Index(value = ["uuid"], unique = true))])
data class QuickTimer(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Int = 0,
    @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "title") val title: String,
    /** Epoch millis when the countdown reaches zero. */
    @ColumnInfo(name = "end_time") val endTime: Long,
    @ColumnInfo(name = "created_time") val createdTime: Long = 0L,
    @ColumnInfo(name = "updated_time") val updatedTime: Long = 0L,
    @ColumnInfo(name = "needs_sync") val needsSync: Boolean = true,
    @ColumnInfo(name = "notified") val notified: Boolean = false
)

/**
 * A deletion waiting to be pushed. Kept after the timer row is gone, so the
 * hub learns about the deletion instead of handing the timer back on the next
 * pull.
 */
@Entity(tableName = "quick_timer_tombstones")
data class QuickTimerTombstone(
    @PrimaryKey @ColumnInfo(name = "uuid") val uuid: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long
)
