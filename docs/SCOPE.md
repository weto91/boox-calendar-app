# Scope — App de Calendario y Tareas para Onyx Boox Note Air 5C

> Documento de partida del proyecto. El código puede haberse desviado en
> algunos puntos; las desviaciones están recogidas en el README.

## 1. Contexto y justificación

La app nativa de calendario de BOOX (`com.onyx.mail`, firmware V4.2) sincroniza
eventos con Google Calendar de forma bidireccional, creando un calendario propio
llamado "ONYX Calendar" en la cuenta del usuario. Sus **recordatorios/tareas no
salen del dispositivo por ningún canal**.

Esto se ha verificado empíricamente:

| Vía investigada | Resultado |
|---|---|
| ContentProvider de `com.onyx.mail` | Solo expone `OnyxFileProvider` y `NoteShapeDatabaseContentProvider` (trazos). Ninguno de eventos ni tareas. |
| CalendarProvider estándar de Android | Los eventos creados en la app no aparecen. Solo se leen los 3 calendarios de Google sincronizados hacia el dispositivo. |
| `/sdcard/Android/data/com.onyx.mail/files` | Vacío. |
| CalDAV — subida | Se creó una colección en Radicale. La app subió el evento de prueba (`PUT` → `201 Created`, `BEGIN:VEVENT`, `PRODID:-//com.onyx.mail//iCal4j//EN`). **Ningún VTODO.** |
| CalDAV — bajada | Se inyectó un VTODO manualmente en la colección. La app **no lo muestra**. |
| Google Tasks | No implementado por Onyx. API distinta, sin ContentProvider en Android. |

Conclusión: los datos viven en `/data/data/com.onyx.mail/databases/`,
inaccesibles sin root. No existe app que pueda leerlos o escribirlos.

**Por tanto**, la única forma de tener calendario y tareas unificados, con
escritura a mano, y sincronizados con servicios externos, es construir una app
propia.

## 2. Objetivo

APK propio (sin distribución en Play Store) que unifique en una sola aplicación:

- Calendario con eventos
- Tareas con lista y checkboxes
- Entrada por escritura a mano con OCR en ambos módulos
- Sincronización bidireccional con Google (Calendar + Tasks)

## 3. Dispositivo objetivo y stack

- **Dispositivo**: Onyx Boox Note Air 5C, BooxOS V4.2 (Android), pantalla e-ink color
- **Lenguaje**: Kotlin
- **UI**: Jetpack Compose
- **Persistencia**: Room
- **Sincronización**: WorkManager
- **HTTP**: Retrofit + OkHttp
- **OAuth**: AppAuth for Android
- **Escritura**: `com.onyx.android.sdk:onyxsdk-pen` (repo Maven `https://repo.boox.com/repository/maven-public/`)
- **OCR**: ML Kit Digital Ink Recognition (on-device, modelo español descargable)
- **minSdk**: el del dispositivo (confirmar con `adb shell getprop ro.build.version.sdk`)

Nota: el uso del SDK de Onyx puede impedir la publicación en Google Play. No es
un problema — la distribución es por APK.

## 4. Arquitectura

Arquitectura en capas, con la sincronización tras una interfaz para permitir
añadir backends en versiones posteriores sin reescribir el núcleo.

```
ui/          Compose — calendario, tareas, canvas de escritura
domain/      Modelos y casos de uso, agnósticos de backend
data/
  local/     Room (entidades, DAOs, migraciones)
  remote/
    SyncBackend (interfaz)
    GoogleBackend (v1)
    CaldavBackend (v2 — no implementar aún)
    MicrosoftBackend (v3 — no implementar aún)
  sync/      SyncEngine, WorkManager workers, mapeo de UIDs
ink/         TouchHelper, captura de trazos, ML Kit
```

`SyncBackend` debe definir operaciones genéricas (listar, crear, actualizar,
borrar, para eventos y para tareas) sin filtrar detalles de Google en la
interfaz. Es la condición para que CalDAV y Microsoft entren después limpiamente.

## 5. Modelo de datos

### Tabla `events`
- `id` local, `remote_id`, `backend_id`
- `title`, `description`, `location`
- `start`, `end`, `all_day`
- `calendar_id` (lista/calendario al que pertenece)
- `updated_at`, `deleted` (borrado lógico para sincronizar)
- `ink_note_id` (nullable — nota manuscrita asociada)

### Tabla `tasks`
- `id` local, `remote_id`, `backend_id`
- `title`, `notes`
- `due_date` (nullable)
- `completed_at` (nullable)
- `list_id`
- `updated_at`, `deleted`
- `purged` (boolean — ver §9)
- `ink_note_id` (nullable)

### Tabla `task_lists` / `calendars`
- `id`, `remote_id`, `backend_id`, `name`, `color`
- `retention_days` (nullable — reservado para retención por lista en versiones
  futuras; la v1 usa solo el valor global)

### Tabla `ink_notes`
- `id`, trazos serializados, timestamp, texto OCR resultante

### Tabla `sync_map`
- `local_id`, `remote_id`, `backend_id`, `entity_type`, `etag`, `last_synced`

## 6. Módulo Calendario

- Vistas de **mes**, **semana** y **día**
- Creación y edición de eventos, con y sin hora
- Recordatorios (VALARM / reminders de Google)
- Las tareas con `due_date` se muestran **también** en las vistas de calendario,
  visualmente diferenciadas de los eventos. Este es el punto diferencial del
  proyecto.
- Nota manuscrita anclada a un día, equivalente a Calendar Memo

## 7. Módulo Tareas

- Listas múltiples (mapeadas a las listas de Google Tasks)
- Checkbox para completar
- Agrupación por lista y por fecha de vencimiento
- Sección separada de **Completadas**, con el texto tachado
- Orden manual dentro de cada lista

## 8. Escritura a mano y OCR

- Canvas con `TouchHelper` de `onyxsdk-pen`: `TouchHelper.create(view, callback)`,
  `setStrokeWidth`, `setLimitRect`, `openRawDrawing`, y
  `setRawDrawingEnabled(true/false)` para pausar durante la interacción con la UI.
- Los trazos capturados por `RawInputCallback` se pasan a ML Kit Digital Ink
  Recognition **directamente como puntos**, sin rasterizar a bitmap.
- El texto reconocido se propone como título del evento o tarea, siempre
  **editable manualmente** antes de guardar.
- Los trazos originales se conservan junto al texto: el OCR no destruye la nota.

## 9. Retención de tareas completadas

Al completar una tarea, pasa a la sección de Completadas, tachada. Permanece
hasta su fecha de purga, calculada **en tiempo de consulta** (no almacenada):

```
purge_at = (due_date IS NULL
              ? completed_at
              : MAX(due_date, completed_at)) + retention_days
```

El `MAX` cubre completar una tarea ya vencida: sin él, una tarea vencida hace
meses y completada hoy desaparecería de inmediato.

- Valor por defecto: **30 días**
- **Editable por el usuario** en ajustes
- Opción **"nunca purgar"**
- La purga marca `purged = true` en lugar de borrar la fila, para evitar que la
  tarea reaparezca en la siguiente sincronización.
- Google gestiona sus propias completadas con sus reglas. La app no intenta
  imponerle su política de retención ni replicar la suya.

## 10. Sincronización (v1: Google)

- OAuth con AppAuth. Scopes:
  - `https://www.googleapis.com/auth/calendar`
  - `https://www.googleapis.com/auth/tasks`
- APIs a habilitar en Google Cloud Console: Google Calendar API y Google Tasks API
- Credenciales OAuth de tipo aplicación Android (SHA-1 del keystore de firma)
- Periodicidad: `WorkManager` cada 15 minutos (mínimo permitido por Android),
  más sincronización manual bajo demanda
- Delta sync: `updatedMin` en Tasks, `syncToken` en Calendar
- Resolución de conflictos: gana el `updated` más reciente; conflicto real
  registrado en log y expuesto al usuario
- Google Tasks **no tiene push**: la dirección remoto→local es polling obligado

## 11. Consideraciones e-ink

- Sin animaciones ni transiciones
- Alto contraste, tipografía grande, áreas táctiles amplias
- Control explícito del modo de refresco (`onyxsdk-device`) según la vista:
  rápido durante la escritura, completo al cambiar de pantalla
- **Excluir la app de la optimización de energía de Onyx** (Ajustes → Energía →
  Optimización de apps), o el sistema matará el servicio de sincronización.
  Documentarlo en el primer arranque.

## 12. Fases de desarrollo

Cada fase produce un APK instalable y probable.

1. **Canvas de escritura**: `TouchHelper` funcionando fluido, captura y
   persistencia de trazos. Si esto no se siente bien, el resto no importa.
2. **OCR**: ML Kit sobre los trazos, con corrección manual del texto.
3. **Núcleo local**: Room, módulo de tareas con listas y checkboxes, módulo de
   calendario con las tres vistas. Todo offline.
4. **Sincronización Google**: OAuth, Calendar API, Tasks API, motor de
   sincronización y resolución de conflictos.
5. **Pulido**: retención configurable, ajustes, refresco e-ink afinado.

## 13. Fuera de alcance en v1

- CalDAV (v2) — la interfaz `SyncBackend` debe dejarlo preparado
- Microsoft / Outlook (v3)
- Compartir calendarios o listas
- Invitaciones y asistentes a eventos
- Adjuntos
- Widgets de pantalla de inicio

## 14. Riesgos conocidos

- **Gestor de energía de Onyx**: mata servicios en segundo plano de forma
  agresiva. Es la causa habitual de que la sincronización "no funcione" en apps
  de terceros en estos dispositivos.
- **Compatibilidad del SDK de Onyx**: las versiones publicadas en el repo Maven
  de Boox son antiguas; verificar comportamiento real en el Note Air 5C antes de
  construir sobre ellas.
- **Modelo de datos de Google Tasks**: una tarea solo tiene fecha de vencimiento
  (`due`), y la interfaz de Google ignora la hora. El mapeo tarea↔evento es
  asimétrico por diseño.
- **Latencia de escritura**: sin el SDK de Onyx, el trazo va con retardo y la app
  deja de tener sentido frente a la nativa.
