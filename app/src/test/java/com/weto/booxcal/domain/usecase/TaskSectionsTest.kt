package com.weto.booxcal.domain.usecase

import com.weto.booxcal.data.local.dao.TaskWithList
import com.weto.booxcal.data.local.entity.TaskEntity
import com.weto.booxcal.util.toDayMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 9, 4)

private fun task(
    id: Long,
    title: String,
    due: LocalDate? = null,
    completedAt: Long? = null,
    listName: String = "Mis tareas",
    sortIndex: Long = 0,
) = TaskWithList(
    task = TaskEntity(
        id = id,
        taskListId = 1,
        title = title,
        dueDayMillis = due?.toDayMillis(),
        completedAt = completedAt,
        sortIndex = sortIndex,
        updatedAt = 0,
    ),
    listName = listName,
    listColorArgb = 0,
)

class TaskSectionsTest {

    @Test
    fun `agrupa por fecha y deja las completadas al final`() {
        val sections = buildTaskSections(
            tasks = listOf(
                task(1, "Vencida", due = TODAY.minusDays(3)),
                task(2, "De hoy", due = TODAY),
                task(3, "De mañana", due = TODAY.plusDays(1)),
                task(4, "Sin fecha"),
                task(5, "Hecha", due = TODAY, completedAt = 1_000L),
            ),
            today = TODAY,
            grouping = TaskGrouping.BY_DATE,
        )

        assertEquals(
            listOf(SectionKind.OVERDUE, SectionKind.TODAY, SectionKind.TOMORROW, SectionKind.UNDATED, SectionKind.COMPLETED),
            sections.map { it.kind },
        )
        assertTrue(sections.first().overdue)
        assertTrue(sections.last().completed)
        assertEquals("Hecha", sections.last().tasks.single().task.title)
    }

    @Test
    fun `las secciones vacias no se muestran`() {
        val sections = buildTaskSections(
            tasks = listOf(task(1, "Sola", due = TODAY)),
            today = TODAY,
            grouping = TaskGrouping.BY_DATE,
        )

        assertEquals(listOf(SectionKind.TODAY), sections.map { it.kind })
    }

    @Test
    fun `una tarea completada no aparece tambien en su seccion de fecha`() {
        val sections = buildTaskSections(
            tasks = listOf(task(1, "Hecha hoy", due = TODAY, completedAt = 1_000L)),
            today = TODAY,
            grouping = TaskGrouping.BY_DATE,
        )

        assertEquals(listOf(SectionKind.COMPLETED), sections.map { it.kind })
        assertNull(sections.firstOrNull { it.kind == SectionKind.TODAY })
    }

    @Test
    fun `al agrupar por lista las completadas siguen separadas`() {
        val sections = buildTaskSections(
            tasks = listOf(
                task(1, "Casa", listName = "Hogar"),
                task(2, "Informe", listName = "Trabajo"),
                task(3, "Vieja", listName = "Trabajo", completedAt = 1L),
            ),
            today = TODAY,
            grouping = TaskGrouping.BY_LIST,
        )

        assertEquals(listOf("Hogar", "Trabajo", ""), sections.map { it.title })
        assertEquals(listOf(SectionKind.LIST, SectionKind.LIST, SectionKind.COMPLETED), sections.map { it.kind })
    }

    @Test
    fun `dentro de un dia manda el orden manual`() {
        val sections = buildTaskSections(
            tasks = listOf(
                task(1, "Segunda", due = TODAY, sortIndex = 200),
                task(2, "Primera", due = TODAY, sortIndex = 100),
            ),
            today = TODAY,
            grouping = TaskGrouping.BY_DATE,
        )

        assertEquals(
            listOf("Primera", "Segunda"),
            sections.single().tasks.map { it.task.title },
        )
    }
}
