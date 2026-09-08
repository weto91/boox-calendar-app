package com.weto.booxcal.data.remote.google

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// --- Google Calendar API v3 -------------------------------------------------

@Serializable
data class GCalendarListResponse(
    val items: List<GCalendarListEntry> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GCalendarListEntry(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val primary: Boolean = false,
    val selected: Boolean = false,
    val deleted: Boolean = false,
    /** "owner", "writer", "reader", "freeBusyReader". */
    val accessRole: String? = null,
) {
    val displayName: String get() = summaryOverride ?: summary ?: id
    val writable: Boolean get() = accessRole == "owner" || accessRole == "writer"
}

@Serializable
data class GEventsResponse(
    val items: List<GEvent> = emptyList(),
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
)

@Serializable
data class GEventDateTime(
    /** Presente solo en eventos de día completo: "2026-09-04". */
    val date: String? = null,
    /** Presente solo en eventos con hora: RFC 3339 con offset. */
    val dateTime: String? = null,
    val timeZone: String? = null,
)

@Serializable
data class GReminderOverride(
    val method: String = "popup",
    val minutes: Int = 10,
)

@Serializable
data class GReminders(
    val useDefault: Boolean = true,
    val overrides: List<GReminderOverride> = emptyList(),
)

@Serializable
data class GEvent(
    val id: String? = null,
    val etag: String? = null,
    /** "confirmed", "tentative", "cancelled". "cancelled" == borrado. */
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: GEventDateTime? = null,
    val end: GEventDateTime? = null,
    val recurrence: List<String> = emptyList(),
    val recurringEventId: String? = null,
    val reminders: GReminders? = null,
    /** Color propio del evento ("1".."11"), si se le puso uno distinto del calendario. */
    val colorId: String? = null,
    val updated: String? = null,
)

// --- Google Tasks API v1 ----------------------------------------------------

@Serializable
data class GTaskListsResponse(
    val items: List<GTaskList> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GTaskList(
    val id: String,
    val title: String? = null,
    val updated: String? = null,
)

@Serializable
data class GTasksResponse(
    val items: List<GTask> = emptyList(),
    val nextPageToken: String? = null,
)

@Serializable
data class GTask(
    val id: String? = null,
    val etag: String? = null,
    val title: String? = null,
    val notes: String? = null,
    /** "needsAction" o "completed". */
    val status: String? = null,
    /** RFC 3339. Google ignora la parte de hora, siempre. */
    val due: String? = null,
    val completed: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val position: String? = null,
    val parent: String? = null,
    val updated: String? = null,
    @SerialName("selfLink") val selfLink: String? = null,
)
