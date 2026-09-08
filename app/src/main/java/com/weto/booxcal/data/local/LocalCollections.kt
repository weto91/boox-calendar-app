package com.weto.booxcal.data.local

import com.weto.booxcal.data.local.entity.CalendarEntity
import com.weto.booxcal.data.local.entity.TaskListEntity

/**
 * Calendario y lista de tareas que viven solo en la tablet.
 *
 * Existen para que la app sirva desde el primer arranque, sin cuenta ni token:
 * sin ninguna colección, `defaultCalendarId()` devuelve null, el formulario no
 * puede guardar y la app queda inservible hasta que alguien configure Google.
 *
 * Llevan un `backendId` propio que ningún backend reclama, y el motor de
 * sincronización recorre las colecciones filtrando por el suyo. El efecto es
 * que estas dos nunca se suben, nunca se borran al desconectar la cuenta y
 * nunca entran en un conflicto.
 */
object LocalCollections {

    const val BACKEND_ID = "local"

    private const val CALENDAR_REMOTE_ID = "local-calendar"
    private const val TASK_LIST_REMOTE_ID = "local-tasks"

    /** Gris. Es el acromático de la paleta: no compite con los de Google. */
    private const val COLOR_ARGB = 0xFF3A3A3A.toInt()

    /**
     * Nombre visible.
     *
     * No dice "solo en la tablet" porque no es verdad y confundía: lo que se
     * apunta aquí se muda al calendario de la cuenta en cuanto hay una, y sube.
     */
    const val NAME = "Sin cuenta (se subirá al conectar)"

    suspend fun ensure(database: AppDatabase) {
        val calendarDao = database.calendarDao()
        if (calendarDao.getByRemote(BACKEND_ID, CALENDAR_REMOTE_ID) == null) {
            calendarDao.insert(
                CalendarEntity(
                    backendId = BACKEND_ID,
                    remoteId = CALENDAR_REMOTE_ID,
                    name = NAME,
                    colorArgb = COLOR_ARGB,
                    // No es primario: en cuanto haya un calendario de Google, el
                    // predeterminado pasa a ser aquel y no este.
                    isPrimary = false,
                    isWritable = true,
                )
            )
        }

        val taskListDao = database.taskListDao()
        if (taskListDao.getByRemote(BACKEND_ID, TASK_LIST_REMOTE_ID) == null) {
            taskListDao.insert(
                TaskListEntity(
                    backendId = BACKEND_ID,
                    remoteId = TASK_LIST_REMOTE_ID,
                    name = NAME,
                    colorArgb = COLOR_ARGB,
                )
            )
        }
    }
}
