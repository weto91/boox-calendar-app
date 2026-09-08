package com.weto.booxcal.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.weto.booxcal.data.local.entity.EventEntity
import kotlinx.coroutines.flow.Flow

data class EventWithCalendar(
    @Embedded val event: EventEntity,
    val calendarName: String,
    val calendarColorArgb: Int,
    val calendarWritable: Boolean,
) {
    /**
     * Color con el que se pinta: el propio del evento si en Google se le puso
     * uno, y si no el de su calendario. Es lo que hace la app de Google.
     */
    val accentArgb: Int get() = event.colorArgb ?: calendarColorArgb
}

@Dao
interface EventDao {

    /**
     * Eventos que solapan [fromMillis, toMillis). El rango debe venir holgado
     * (±1 día): los eventos de día completo se guardan en medianoche UTC y los
     * de hora en instante real, así que en los bordes la comparación en SQL no
     * es exacta. El filtrado fino se hace en Kotlin, con la zona del
     * dispositivo. Ver `EventRepository.observeRange`.
     */
    @Query(
        """
        SELECT events.*,
               calendars.name AS calendarName,
               calendars.colorArgb AS calendarColorArgb,
               calendars.isWritable AS calendarWritable
        FROM events
        JOIN calendars ON calendars.id = events.calendarId
        WHERE events.deleted = 0
          AND calendars.isVisible = 1
          AND events.startMillis < :toMillis
          AND events.endMillis >= :fromMillis
        ORDER BY events.allDay DESC, events.startMillis ASC, events.title COLLATE NOCASE
        """
    )
    fun observeRange(fromMillis: Long, toMillis: Long): Flow<List<EventWithCalendar>>

    @Query(
        """
        SELECT events.*,
               calendars.name AS calendarName,
               calendars.colorArgb AS calendarColorArgb,
               calendars.isWritable AS calendarWritable
        FROM events
        JOIN calendars ON calendars.id = events.calendarId
        WHERE events.id = :id
        """
    )
    fun observeById(id: Long): Flow<EventWithCalendar?>

    @Query("SELECT * FROM events WHERE id = :id")
    suspend fun getById(id: Long): EventEntity?

    /**
     * Busca en título, descripción y lugar. `LIKE` es suficiente: la base cabe
     * en unos miles de filas y montar FTS obligaría a mantener una tabla espejo
     * sincronizada con cada bajada del servidor.
     */
    @Query(
        """
        SELECT events.*,
               calendars.name AS calendarName,
               calendars.colorArgb AS calendarColorArgb,
               calendars.isWritable AS calendarWritable
        FROM events
        JOIN calendars ON calendars.id = events.calendarId
        WHERE events.deleted = 0
          AND calendars.isVisible = 1
          AND (events.title LIKE '%' || :query || '%'
               OR IFNULL(events.description, '') LIKE '%' || :query || '%'
               OR IFNULL(events.location, '') LIKE '%' || :query || '%')
        ORDER BY events.startMillis DESC
        LIMIT 100
        """
    )
    suspend fun search(query: String): List<EventWithCalendar>

    @Query("SELECT * FROM events WHERE calendarId = :calendarId AND (dirty = 1 OR deleted = 1)")
    suspend fun getPending(calendarId: Long): List<EventEntity>

    @Query("SELECT COUNT(*) FROM events WHERE dirty = 1 OR deleted = 1")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(event: EventEntity): Long

    @Update
    suspend fun update(event: EventEntity)

    /**
     * Traslada al calendario [to] todo lo que se creó sin cuenta.
     *
     * Se marcan sucios para que la siguiente subida los lleve a Google: hasta
     * ahora vivían en el calendario local, que ningún backend reclama, y se
     * quedaban en la tablet para siempre.
     */
    @Query(
        "UPDATE events SET calendarId = :to, dirty = 1, updatedAt = :now " +
            "WHERE calendarId = :from AND deleted = 0"
    )
    suspend fun moveToCalendar(from: Long, to: Long, now: Long): Int

    @Query("UPDATE events SET deleted = 1, dirty = 1, updatedAt = :now WHERE id = :id")
    suspend fun markDeleted(id: Long, now: Long)

    @Query("UPDATE events SET dirty = 0 WHERE id = :id")
    suspend fun clearDirty(id: Long)

    @Query("DELETE FROM events WHERE id = :id")
    suspend fun deleteRow(id: Long)

    /** Reinicio de calendario tras un syncToken caducado (HTTP 410). */
    @Query("DELETE FROM events WHERE calendarId = :calendarId AND dirty = 0 AND deleted = 0")
    suspend fun deleteCleanRows(calendarId: Long)
}
