package org.fossify.calendar.helpers

import org.json.JSONArray
import org.json.JSONObject

/**
 * Wire format of `POST /api/v1/tools/agenda/sync`.
 *
 * Hand-written with `org.json` on purpose: the app has no serialization
 * dependency, and one endpoint does not justify adding one. Every field has a
 * default, so a client one version behind still syncs.
 */
data class AgendaSyncEventDto(
    val uuid: String = "",
    val title: String = "",
    val description: String = "",
    val location: String = "",
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val allDay: Boolean = false,
    val color: String = "",
    val createdTime: Long = 0L,
    val updatedTime: Long = 0L
)

data class AgendaSyncDeletionDto(
    val uuid: String = "",
    val deletedAt: Long = 0L
)

data class AgendaSyncRequest(
    val cursor: Long = 0L,
    val deviceId: String = "",
    val deviceName: String = "",
    val events: List<AgendaSyncEventDto> = emptyList(),
    val deletions: List<AgendaSyncDeletionDto> = emptyList()
)

data class AgendaSyncResponse(
    val cursor: Long = 0L,
    val serverTime: Long = 0L,
    val events: List<AgendaSyncEventDto> = emptyList(),
    val deletions: List<AgendaSyncDeletionDto> = emptyList()
)

fun AgendaSyncEventDto.toJson(): JSONObject = JSONObject().apply {
    put("uuid", uuid)
    put("title", title)
    put("description", description)
    put("location", location)
    put("startTime", startTime)
    put("endTime", endTime)
    put("allDay", allDay)
    put("color", color)
    put("createdTime", createdTime)
    put("updatedTime", updatedTime)
}

fun AgendaSyncRequest.toJson(): String = JSONObject().apply {
    put("cursor", cursor)
    put("deviceId", deviceId)
    put("deviceName", deviceName)
    put(
        "events", JSONArray().apply {
            events.forEach { put(it.toJson()) }
        }
    )
    put(
        "deletions", JSONArray().apply {
            deletions.forEach {
                put(JSONObject().put("uuid", it.uuid).put("deletedAt", it.deletedAt))
            }
        }
    )
}.toString()

fun JSONObject.toEventDto(): AgendaSyncEventDto = AgendaSyncEventDto(
    uuid = optString("uuid"),
    title = optString("title"),
    description = optString("description"),
    location = optString("location"),
    startTime = optLong("startTime"),
    endTime = optLong("endTime"),
    allDay = optBoolean("allDay"),
    color = optString("color"),
    createdTime = optLong("createdTime"),
    updatedTime = optLong("updatedTime")
)

fun parseSyncResponse(body: String): AgendaSyncResponse {
    val json = JSONObject(body)
    return AgendaSyncResponse(
        cursor = json.optLong("cursor"),
        serverTime = json.optLong("serverTime"),
        events = json.optJSONArray("events").toDtoList { it.toEventDto() },
        deletions = json.optJSONArray("deletions").toDtoList {
            AgendaSyncDeletionDto(uuid = it.optString("uuid"), deletedAt = it.optLong("deletedAt"))
        }
    )
}

private fun <T> JSONArray?.toDtoList(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let(transform)
    }
}
