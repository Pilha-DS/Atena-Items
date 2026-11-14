package org.fossify.calendar.helpers

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fossify.calendar.databases.EventsDatabase
import org.fossify.calendar.extensions.cancelNotification
import org.fossify.calendar.extensions.cancelPendingIntent
import org.fossify.calendar.extensions.config
import org.fossify.calendar.extensions.scheduleNextEventReminder
import org.fossify.calendar.extensions.updateWidgets
import org.fossify.calendar.interfaces.AgendaSyncDao
import org.fossify.calendar.interfaces.EventsDao
import org.fossify.calendar.models.AgendaSyncEvent
import org.fossify.calendar.models.AgendaSyncTombstone
import org.fossify.calendar.models.Event
import java.util.UUID

data class AgendaSyncResult(
    val configured: Boolean = false,
    val ok: Boolean = false,
    val pushed: Int = 0,
    val pulled: Int = 0,
    val cursor: Long = 0L,
    val error: AgendaSyncErrorKind? = null,
    val errorDetail: String? = null
)

/**
 * Runs one round of the Atena Hub sync.
 *
 * The app's Room database is the source of truth. A side table
 * ([AgendaSyncEvent]) gives every local event the uuid the hub knows it by and
 * remembers the version the server already has; tombstones carry deletions
 * after the row is gone. Conflicts are last-write-wins on `Event.lastUpdated`,
 * the same rule the Linux app and the hub use.
 *
 * Only local, single, non-repeating events take part. Events from a CalDAV
 * account or an imported file have their own sync, and repeating events have
 * no representation on the hub (the recurrence would be silently lost).
 */
object AgendaSyncEngine {
    private const val TAG = "AgendaSync"

    /** One round at a time, so a periodic tick and a manual tap never overlap. */
    private val mutex = Mutex()

    @Suppress("TooGenericExceptionCaught")
    suspend fun sync(context: Context, client: AgendaSyncClient = AgendaSyncClient()): AgendaSyncResult {
        val appContext = context.applicationContext
        val config = appContext.config
        if (!config.agendaSyncConfigured) {
            return AgendaSyncResult(configured = false)
        }

        return mutex.withLock {
            val database = EventsDatabase.getInstance(appContext)
            try {
                val outgoing = database.withTransaction { collectOutgoing(database) }
                val request = AgendaSyncRequest(
                    cursor = config.agendaSyncCursor,
                    deviceId = config.agendaSyncDeviceId,
                    deviceName = config.agendaSyncDeviceName,
                    events = outgoing.events,
                    deletions = outgoing.deletions
                )

                val response = client.sync(
                    url = config.agendaSyncUrl,
                    token = config.agendaSyncToken,
                    request = request
                )

                val applied = database.withTransaction {
                    applyResponse(appContext, database, response, outgoing)
                }

                // Alarms and widgets are outside the transaction: a read there
                // could race with the still-open write.
                applied.updatedEvents.forEach { appContext.scheduleNextEventReminder(it, false) }
                if (applied.touched) {
                    appContext.updateWidgets()
                }

                config.agendaSyncCursor = response.cursor
                config.agendaSyncLastAt = System.currentTimeMillis()

                Log.i(
                    TAG,
                    "sync ok: pushed ${outgoing.events.size}/${outgoing.deletions.size} " +
                        "pulled ${response.events.size}/${response.deletions.size} " +
                        "cursor=${response.cursor}"
                )
                AgendaSyncResult(
                    configured = true,
                    ok = true,
                    pushed = outgoing.events.size + outgoing.deletions.size,
                    pulled = response.events.size + response.deletions.size,
                    cursor = response.cursor
                )
            } catch (error: AgendaSyncException) {
                Log.w(TAG, "sync failed (${error.kind}): ${error.message}")
                AgendaSyncResult(
                    configured = true,
                    error = error.kind,
                    errorDetail = error.message
                )
            } catch (error: Exception) {
                // Anything unexpected (a database error, a broken cursor) is
                // reported instead of crashing the worker or the settings screen.
                Log.w(TAG, "sync failed: ${error.message}", error)
                AgendaSyncResult(
                    configured = true,
                    error = AgendaSyncErrorKind.NETWORK,
                    errorDetail = error.message
                )
            }
        }
    }

    /**
     * Registers a uuid for every local event that does not have one yet, then
     * picks what has to go up: new/edited events and everything deleted since
     * the last round.
     */
    private fun collectOutgoing(database: EventsDatabase): Outgoing {
        val eventsDao = database.EventsDao()
        val syncDao = database.AgendaSyncDao()

        val syncable = eventsDao.getAllEvents().filter { it.id != null && it.isSyncable() }
        val syncableIds = syncable.mapTo(HashSet()) { it.id!! }
        val links = syncDao.getAll().associateBy { it.eventId }

        val events = ArrayList<AgendaSyncEventDto>(syncable.size)
        val pushedVersions = ArrayList<Pair<String, Long>>(syncable.size)

        for (event in syncable) {
            val id = event.id!!
            if (event.lastUpdated <= 0L) {
                // Very old rows may predate the timestamp; give them one now,
                // otherwise they would look older than every server version.
                event.lastUpdated = System.currentTimeMillis()
                eventsDao.update(event)
            }

            val link = links[id]
            if (link == null) {
                // Never synced: mint its identity now, so the push and any future
                // edit talk about the same event.
                val uuid = UUID.randomUUID().toString()
                syncDao.insertLink(AgendaSyncEvent(eventId = id, uuid = uuid, syncedTime = 0L))
                events.add(AgendaEventMapper.toDto(event, uuid))
                pushedVersions.add(uuid to event.lastUpdated)
            } else if (event.lastUpdated > link.syncedTime) {
                events.add(AgendaEventMapper.toDto(event, link.uuid))
                pushedVersions.add(link.uuid to event.lastUpdated)
            }
        }

        // A link without a matching row means the event was deleted (or stopped
        // being syncable). The tombstone outlives the row.
        val pending = syncDao.getTombstones().associateBy { it.uuid }.toMutableMap()
        for ((eventId, link) in links) {
            if (eventId in syncableIds) {
                continue
            }

            syncDao.deleteLinkByEventId(eventId)
            val previous = pending[link.uuid]?.deletedAt ?: 0L
            val tombstone = AgendaSyncTombstone(
                uuid = link.uuid,
                deletedAt = maxOf(System.currentTimeMillis(), previous + 1)
            )
            pending[link.uuid] = tombstone
            syncDao.insertTombstone(tombstone)
        }

        val deletions = pending.values.map { AgendaSyncDeletionDto(it.uuid, it.deletedAt) }
        return Outgoing(events, deletions, pushedVersions)
    }

    /**
     * Applies the pull and then acknowledges the push, in a single transaction:
     * either the whole step lands or the cursor is not advanced, so a crash in
     * the middle cannot lose a change.
     */
    private fun applyResponse(
        context: Context,
        database: EventsDatabase,
        response: AgendaSyncResponse,
        outgoing: Outgoing
    ): Applied {
        val syncDao = database.AgendaSyncDao()
        val eventsDao = database.EventsDao()

        val pulled = applyPulledEvents(context, eventsDao, syncDao, response.events)
        var deleted = false
        for (deletion in response.deletions) {
            if (deletion.uuid.isNotEmpty() &&
                applyPulledDeletion(context, eventsDao, syncDao, deletion)
            ) {
                deleted = true
            }
        }
        acknowledgePushed(syncDao, outgoing, pulled.serverVersions)

        return Applied(
            touched = pulled.updatedEvents.isNotEmpty() || deleted,
            updatedEvents = pulled.updatedEvents
        )
    }

    private fun applyPulledEvents(
        context: Context,
        eventsDao: EventsDao,
        syncDao: AgendaSyncDao,
        events: List<AgendaSyncEventDto>
    ): Pulled {
        val serverVersions = HashMap<String, Long>()
        val updatedEvents = ArrayList<Event>()

        for (dto in events) {
            if (!dto.isUsable()) {
                continue
            }
            serverVersions[dto.uuid] = dto.updatedTime
            val changes = applyPulledEvent(context, eventsDao, syncDao, dto)
            if (changes != null) {
                updatedEvents.add(changes)
            }
        }

        return Pulled(updatedEvents = updatedEvents, serverVersions = serverVersions)
    }

    /** Returns the event when its row changed, so its alarm can be rescheduled. */
    private fun applyPulledEvent(
        context: Context,
        eventsDao: EventsDao,
        syncDao: AgendaSyncDao,
        dto: AgendaSyncEventDto
    ): Event? {
        val link = syncDao.getByUuid(dto.uuid)
        val local = link?.let { eventsDao.getEventOrTaskWithId(it.eventId) }

        if (local == null) {
            if (link == null) {
                return insertPulledEvent(context, eventsDao, syncDao, dto)
            }

            // The row existed when the push was prepared and is gone now: the
            // user deleted it during the round trip. The deletion is the newer
            // intent, so it becomes a tombstone instead of being resurrected.
            syncDao.deleteLinkByUuid(dto.uuid)
            syncDao.insertTombstone(AgendaSyncTombstone(dto.uuid, System.currentTimeMillis()))
            return null
        }

        // Local is the same version or newer: it stays dirty and wins the next
        // push (LWW by lastUpdated), but the server version is known.
        if (dto.updatedTime <= local.lastUpdated) {
            if (link.syncedTime != dto.updatedTime) {
                syncDao.insertLink(link.copy(syncedTime = dto.updatedTime))
            }
            return null
        }

        val updated = AgendaEventMapper.applyDto(local, dto)
        eventsDao.update(updated)
        syncDao.insertLink(
            AgendaSyncEvent(eventId = updated.id!!, uuid = dto.uuid, syncedTime = dto.updatedTime)
        )
        return updated
    }

    private fun insertPulledEvent(
        context: Context,
        eventsDao: EventsDao,
        syncDao: AgendaSyncDao,
        dto: AgendaSyncEventDto
    ): Event {
        val fresh = AgendaEventMapper.toNewEvent(dto)
        val id = eventsDao.insertOrUpdate(fresh)
        fresh.id = id
        syncDao.insertLink(
            AgendaSyncEvent(eventId = id, uuid = dto.uuid, syncedTime = dto.updatedTime)
        )

        // Show the calendar, otherwise the pull looks like it did nothing.
        val config = context.config
        val calendar = fresh.calendarId.toString()
        if (!config.displayCalendars.contains(calendar)) {
            config.displayCalendars = config.displayCalendars.plus(calendar)
        }
        return fresh
    }

    private fun applyPulledDeletion(
        context: Context,
        eventsDao: EventsDao,
        syncDao: AgendaSyncDao,
        deletion: AgendaSyncDeletionDto
    ): Boolean {
        val link = syncDao.getByUuid(deletion.uuid) ?: return false
        val local = eventsDao.getEventOrTaskWithId(link.eventId)
        if (local == null) {
            syncDao.deleteLinkByUuid(deletion.uuid)
            return false
        }

        // An edit made after the deletion is newer than it: the edit wins and the
        // event comes back on the next push.
        if (deletion.deletedAt < local.lastUpdated) {
            return false
        }

        eventsDao.deleteEvents(listOf(local.id!!))
        syncDao.deleteLinkByUuid(deletion.uuid)
        context.cancelNotification(local.id!!)
        context.cancelPendingIntent(local.id!!)
        return true
    }

    /**
     * Only acknowledges what the server actually kept. When the server holds a
     * newer version of a pushed event, the pull already overwrote the local copy
     * and its version is the one to remember.
     */
    private fun acknowledgePushed(
        syncDao: AgendaSyncDao,
        outgoing: Outgoing,
        serverVersions: Map<String, Long>
    ) {
        for ((uuid, localVersion) in outgoing.pushed) {
            val serverVersion = serverVersions[uuid] ?: -1L
            if (serverVersion <= localVersion) {
                val link = syncDao.getByUuid(uuid)
                if (link != null && link.syncedTime < localVersion) {
                    syncDao.insertLink(link.copy(syncedTime = localVersion))
                }
            }
        }

        for (deletion in outgoing.deletions) {
            syncDao.deleteTombstone(deletion.uuid)
        }
    }

    private fun Event.isSyncable(): Boolean = type == TYPE_EVENT &&
        parentId == 0L &&
        repeatInterval == 0 &&
        source == SOURCE_SIMPLE_CALENDAR &&
        !importId.startsWith(MANAGED_HOLIDAY_PREFIX)

    private fun AgendaSyncEventDto.isUsable(): Boolean =
        uuid.isNotEmpty() && startTime > 0L && endTime > startTime

    private data class Outgoing(
        val events: List<AgendaSyncEventDto>,
        val deletions: List<AgendaSyncDeletionDto>,
        val pushed: List<Pair<String, Long>>
    )

    private data class Pulled(
        val updatedEvents: List<Event>,
        val serverVersions: Map<String, Long>
    )

    private data class Applied(
        val touched: Boolean,
        val updatedEvents: List<Event>
    )
}
