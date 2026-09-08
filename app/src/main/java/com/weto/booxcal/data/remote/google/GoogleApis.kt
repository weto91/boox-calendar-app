package com.weto.booxcal.data.remote.google

import kotlinx.serialization.json.JsonObject
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Sin valores por defecto en los parámetros: Retrofit y los argumentos por
 * defecto de Kotlin conviven mal en interfaces, y el fallo se manifiesta en
 * tiempo de ejecución. Todas las llamadas pasan todo explícito.
 */
interface GoogleCalendarApi {

    @GET("calendar/v3/users/me/calendarList")
    suspend fun calendarList(
        @Query("maxResults") maxResults: Int,
        @Query("pageToken") pageToken: String?,
        @Query("showDeleted") showDeleted: Boolean,
    ): GCalendarListResponse

    /**
     * Con `syncToken` Google prohíbe enviar `timeMin`, `timeMax` y `orderBy`.
     * El backend se encarga de mandar uno u otro juego de parámetros, nunca los
     * dos. Un token caducado devuelve 410.
     */
    @GET("calendar/v3/calendars/{calendarId}/events")
    suspend fun events(
        @Path("calendarId") calendarId: String,
        @Query("timeMin") timeMin: String?,
        @Query("timeMax") timeMax: String?,
        @Query("syncToken") syncToken: String?,
        @Query("pageToken") pageToken: String?,
        @Query("singleEvents") singleEvents: Boolean,
        @Query("showDeleted") showDeleted: Boolean,
        @Query("maxResults") maxResults: Int,
    ): GEventsResponse

    @POST("calendar/v3/calendars/{calendarId}/events")
    suspend fun createEvent(
        @Path("calendarId") calendarId: String,
        @Body body: JsonObject,
    ): GEvent

    @PATCH("calendar/v3/calendars/{calendarId}/events/{eventId}")
    suspend fun patchEvent(
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
        @Body body: JsonObject,
    ): GEvent

    @DELETE("calendar/v3/calendars/{calendarId}/events/{eventId}")
    suspend fun deleteEvent(
        @Path("calendarId") calendarId: String,
        @Path("eventId") eventId: String,
    )
}

interface GoogleTasksApi {

    @GET("tasks/v1/users/@me/lists")
    suspend fun taskLists(
        @Query("maxResults") maxResults: Int,
        @Query("pageToken") pageToken: String?,
    ): GTaskListsResponse

    /**
     * `showDeleted` + `showHidden` son imprescindibles: sin ellos una tarea
     * completada o borrada en otro cliente nunca llega y se queda para siempre
     * en la base local.
     */
    @GET("tasks/v1/lists/{tasklist}/tasks")
    suspend fun tasks(
        @Path("tasklist") taskList: String,
        @Query("updatedMin") updatedMin: String?,
        @Query("pageToken") pageToken: String?,
        @Query("maxResults") maxResults: Int,
        @Query("showCompleted") showCompleted: Boolean,
        @Query("showDeleted") showDeleted: Boolean,
        @Query("showHidden") showHidden: Boolean,
    ): GTasksResponse

    @POST("tasks/v1/lists/{tasklist}/tasks")
    suspend fun createTask(
        @Path("tasklist") taskList: String,
        @Body body: JsonObject,
    ): GTask

    @PATCH("tasks/v1/lists/{tasklist}/tasks/{task}")
    suspend fun patchTask(
        @Path("tasklist") taskList: String,
        @Path("task") taskId: String,
        @Body body: JsonObject,
    ): GTask

    @DELETE("tasks/v1/lists/{tasklist}/tasks/{task}")
    suspend fun deleteTask(
        @Path("tasklist") taskList: String,
        @Path("task") taskId: String,
    )
}
