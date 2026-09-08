package com.weto.booxcal.data.repository

import com.weto.booxcal.data.local.AppDatabase
import com.weto.booxcal.data.local.dao.EventWithCalendar
import com.weto.booxcal.data.local.entity.EventEntity
import com.weto.booxcal.data.local.entity.SyncEntityType
import com.weto.booxcal.util.MILLIS_PER_DAY
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

class EventRepository(
    private val db: AppDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val eventDao = db.eventDao()
    private val calendarDao = db.calendarDao()
    private val syncMapDao = db.syncMapDao()

    /**
     * El rango se ensancha un día por cada lado. En SQL conviven millis de
     * instante y millis de día UTC, así que en los bordes la comparación puede
     * fallar por el desfase horario; el recorte exacto lo hace
     * [com.weto.booxcal.domain.usecase.buildDayBuckets] con la zona real.
     */
    fun observeRange(from: LocalDate, toExclusive: LocalDate, zone: ZoneId): Flow<List<EventWithCalendar>> {
        val fromMillis = from.atStartOfDay(zone).toInstant().toEpochMilli() - MILLIS_PER_DAY
        val toMillis = toExclusive.atStartOfDay(zone).toInstant().toEpochMilli() + MILLIS_PER_DAY
        return eventDao.observeRange(fromMillis, toMillis)
    }

    fun observeById(id: Long): Flow<EventWithCalendar?> = eventDao.observeById(id)

    suspend fun getById(id: Long): EventEntity? = eventDao.getById(id)

    suspend fun defaultCalendarId(preferred: Long): Long? {
        if (preferred != 0L && calendarDao.getById(preferred)?.isWritable == true) return preferred
        return calendarDao.getDefaultWritable()?.id
    }

    suspend fun create(event: EventEntity): Long =
        eventDao.insert(event.copy(id = 0, dirty = true, deleted = false, updatedAt = now()))

    suspend fun update(event: EventEntity) {
        eventDao.update(event.copy(dirty = true, updatedAt = now()))
    }

    /**
     * Borrado lógico. La fila sobrevive hasta que el backend confirma; si se
     * borrara aquí, la sincronización no sabría qué borrar allí.
     */
    suspend fun delete(id: Long) {
        val event = eventDao.getById(id) ?: return
        val mapped = syncMapDao.forLocal(SyncEntityType.EVENT, id, backendOf(event.calendarId))
        if (mapped == null) {
            // Nunca llegó a subir: no hay nada que borrar en el servidor.
            eventDao.deleteRow(id)
            syncMapDao.deleteAllForLocal(SyncEntityType.EVENT, id)
        } else {
            eventDao.markDeleted(id, now())
        }
    }

    private suspend fun backendOf(calendarId: Long): String =
        calendarDao.getById(calendarId)?.backendId.orEmpty()
}
