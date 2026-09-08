# Boox Calendar

Calendar, reminders and handwritten notes in a single app for **Onyx Boox**
e-ink tablets, synchronised with your Google account. Designed and tested on
the **Note Air 5C** (BooxOS 4.2, Android 13).

- **Calendar** from Google Calendar: month, week and day views; quick creation
  with a single "when" picker.
- **Reminders** from Google Tasks, inside the calendar and in their lists.
- **Handwritten notes** with the stylus: a quick note of the day on the home
  screen, a notebook with folders and tags, on-device handwriting recognition
  and text search. Strokes come from the Onyx SDK: grainy pencil, ballpoint,
  highlighter.
- **Notes in Google Drive** as editable PDF, in a folder owned by the app.
  **Import PDFs** from other apps (OneNote, scans), write over them and search
  their text.
- **Widgets**: today, week, agenda, notes of the day and search.
- Built for e-ink: black and white, no animations, minimal refreshes,
  everything on one screen.

Website: <https://wetoteca.duckdns.org/booxcalendar/> ·
[Privacy policy](docs/privacidad.md)

---

## Install

1. Download the APK of the [latest release](https://github.com/weto91/boox-calendar-app/releases/latest)
   from the tablet's browser.
2. If BooxOS asks, allow the browser to install apps from unknown sources.
3. Open the app → Settings → **Connect my Google account**. The browser opens;
   pick your account and accept the permissions.
4. Settings → Notes in Google Drive → **Enable**: the app creates the folder
   "Calendario Boox" in your Drive.
5. Exclude the app from BooxOS power optimisation so it can sync in the
   background. The app itself reminds you.

### Google permissions it asks for, and why

| Permission | Used for |
|---|---|
| Google Calendar | Reading and writing your events. |
| Google Tasks | Reading and writing your reminders. |
| Google Drive (app files only) | Storing your notes as PDF in the "Calendario Boox" folder. It cannot see the rest of your Drive. |
| Account e-mail | Showing which account is connected. |

Data lives only on the tablet and in your Google account. There are no
third-party servers, no analytics, no advertising. Details in the
[privacy policy](docs/privacidad.md).

---

## For developers

### Getting started

1. **Android Studio** (Ladybug or later), JDK 17 or 21. The bytecode target
   is 17.
2. **Google credentials.** Create `local.properties` at the root with:

   ```properties
   sdk.dir=/path/to/your/Android/sdk
   GOOGLE_OAUTH_CLIENT_ID=1234567890-abcdefg.apps.googleusercontent.com
   ```

   Full steps in [`docs/SETUP_GOOGLE.md`](docs/SETUP_GOOGLE.md). Without a
   client ID the app builds and runs locally, but does not sync.
3. **Build and install**: `./gradlew installDebug`.
4. **Publish a release**: [`docs/RELEASE.md`](docs/RELEASE.md) (signing key,
   release client ID, `v*` tag and GitHub Actions).

The Onyx SDK is downloaded from Boox's Maven repository, declared in
`settings.gradle.kts`. Device notes in
[`docs/DEVICE_NOTES.md`](docs/DEVICE_NOTES.md); the origin and scope of the
project in [`docs/SCOPE.md`](docs/SCOPE.md) (both in Spanish, historical).

### Versions

They all live in [`gradle/libs.versions.toml`](gradle/libs.versions.toml). If
something fails to resolve, bump it there and nowhere else. Three ties that
cannot be broken:

- `kotlin-compose` **always** matches the `kotlin` version. Since Kotlin 2.0
  the Compose compiler is applied as a plugin; the old
  `composeOptions.kotlinCompilerExtensionVersion` no longer exists.
- `ksp` carries the Kotlin version as a prefix (`2.0.21-1.0.25`). When bumping
  Kotlin, bump KSP to its matching pair or the build fails at startup.
- AGP 8.7 requires Gradle 8.9 or later (`gradle/wrapper/gradle-wrapper.properties`).

Room 2.6 is not ready for KSP2, so `gradle.properties` forces KSP1. If Room is
ever bumped to 2.7+, that line can go.

---

## Architecture

```
ui/          Compose: calendar, tasks, editors, canvas, settings
domain/      Pure models and use cases, no Android, no backend
data/
  local/     Room: entities, DAOs
  remote/    SyncBackend (interface) + google/ (v1 implementation)
  repository/
  sync/      SyncEngine, SyncWorker, SyncScheduler, NoteDriveSync
  settings/  DataStore
ink/         Onyx TouchHelper, stroke codec, PDF reader/writer, ML Kit
widget/      Home-screen widgets
di/          Graph: service locator
```

No Hilt. There are half a dozen dependencies, all application-scoped, and code
generation would have added one more source of failures to a project that
already depends on a proprietary SDK with fragile versions. `Graph` builds them
and ViewModels take them as default arguments, which leaves them an empty
constructor for `viewModel()` without losing injectability in tests.

### The `SyncBackend` interface

`data/remote/SyncBackend.kt` never mentions Google. It speaks of neutral
collections, events and tasks, and of a `SyncCursor` with two fields, `token`
and `sinceMillis`, that cover Google Calendar's `syncToken`, Google Tasks'
`updatedMin` and, later, CalDAV's sync-token (RFC 6578). Adding a backend means
writing one class and adding it to the list in `Graph`.

### Remote identity

The local ↔ remote mapping lives **only** in `sync_map`, not duplicated in
`events` and `tasks`. This is a deliberate departure from §5 of the scope,
which listed `remote_id` and `backend_id` in both places: with two copies there
are two truths and sooner or later they diverge. The key
`(entityType, localId, backendId)` also lets the same task exist tomorrow in
Google and in CalDAV at once without touching the schema, which is why the
table exists.

Collections (`calendars`, `task_lists`) do carry their `remoteId` directly: a
collection belongs to a backend by definition.

### Dates

Everything is stored as UTC epoch millis, with two meanings depending on
`allDay`:

| `allDay` | Meaning of `startMillis` / `endMillis` |
|---|---|
| `false` | A real instant. Shown in the device's zone. |
| `true`  | **UTC** midnight of the day (`epochDay * 86400000`). Shown in UTC. |

So an all-day event on 4 September still falls on 4 September after a flight.
All conversions go through `util/DateTimeX.kt`. The end of an all-day event is
**exclusive**, as in iCalendar and Google.

Because both kinds of value coexist in SQL, range queries ask for one extra
day on each side and the exact trimming is done by `buildDayBuckets` with the
real zone. It is a pure function and has tests.

### Synchronisation

Per collection, in this order: **push first, then pull**. The other way round,
an unpushed local change would be overwritten by the server's version before
having a chance to travel.

Conflicts: the most recent `updated` wins. Since pushing comes before pulling,
by the time we pull the only dirty rows are the ones whose push failed; there
the timestamps are compared and the conflict is recorded and shown in
Settings.

Deletions are logical (`deleted = 1`): the row survives until the backend
confirms, because if it were deleted first the sync would not know what to
delete there.

An expired `syncToken` (HTTP 410) rebuilds that collection from scratch,
keeping the rows with pending local changes.

A single sync pass runs at a time (a mutex in `SyncEngine`): WorkManager
schedules the periodic, "now" and "soon" passes as separate jobs and can run
them concurrently, and two overlapping passes used to create the same new task
twice on the server.

### Notes in Google Drive

`data/sync/NoteDriveSync.kt`. The app asks for the `drive.file` scope, so it
only sees files it created itself. It creates the folder "Calendario Boox" in
the user's Drive and mirrors the notebook's folder tree under it. Each note is
a PDF that embeds the notebook (strokes, texts, page backgrounds) and renders
as Boox's NeoPdf format, so it can also be opened and annotated in Boox Notes.
PDFs from other apps enter through **Import** in the notebook: they are copied
to the app folder, each page is kept as a background image plus its typed text,
and the note is owned by the app (editable, movable, deletable).

### Retention of completed tasks (§9)

```
purge_at = (due IS NULL ? completed : MAX(due, completed)) + retention
```

Computed at query time, never stored. The `MAX` is what keeps a task that was
due months ago and completed today from disappearing on the spot. Purging
marks `purged = true` instead of deleting: deleting would make the next sync
pull it again and it would reappear.

30 days by default, configurable, with an option to never purge. Raising the
period brings back what the previous one had hidden.

### Handwriting

`PenCanvasView` has two paths:

- **Onyx**: `TouchHelper` paints the stroke straight onto the panel, bypassing
  Android's graphics stack. We only collect the points, and repaint with the
  SDK's own brushes (charcoal, fountain, marker) as the official pen demo does,
  so a stroke never changes its look after the fact.
- **Any other device**: plain `onTouchEvent`, with the usual latency. Lets you
  develop on an emulator.

Strokes are handed to ML Kit **as points**, not rasterised to a bitmap: it
recognises far better with the trajectory and timing, and rasterising would
throw away exactly the information it uses. Recognised text is **proposed**,
always editable, and the original stroke is kept alongside the text.

### The home screen

Startup is not a full-page calendar view but a panel of three cards, like the
native app's:

```
┌─ Calendar ────────  ‹ April 2026 ›  ── 🔍 ☁ 📅 ☰ ─┐
│ ┌── month ───────────┐ ┌── items of the day ───┐ │
│ │ 01                 │ │ EVENTS                 │ │
│ │ Wednesday          │ │ 09:45  Meeting         │ │
│ │ 01 Apr 2026        │ │ REMINDERS              │ │
│ │  M  T  W  T  F  S  S│ │ ○ Send the report     │ │
│ │  …  ·  ·   ·        │ │                  1/1  │ │
│ └────────────────────┘ └────────────────────────┘ │
│ ┌── module ────────────────────────────────────┐  │
│ │ [✎][✔][▤][📓]  Reminders            All ›   │  │
│ │ ○ Document, without overthinking it          │  │
│ │ ▼ Completed (4)                              │  │
│ └──────────────────────────────────────────────┘  │
└────────────────────────────────────────── (+) ────┘
```

Everything consulted daily fits without navigating. On e-ink that matters more
than on any other screen: every jump costs a full refresh.

- **Month card** (`ui/home/MiniMonthCard.kt`): the selected day in large type
  and the compact grid below. Each day carries up to three dots instead of its
  entries' titles: at that size a title does not fit, and a dot already says
  the only thing you need at a glance.
- **Items of the day** (`ui/home/EventsCard.kt`): the selected day's events,
  reminders due that day and notes, grouped, five rows per page with an `n/m`
  indicator. Paging is not decorative: scrolling a list on e-ink is a
  continuous refresh that leaves ghosts; turning a page repaints once.
- **Tabbed module** (`ui/home/ModuleCard.kt`): the day's handwritten note,
  reminders, notes of the day, and what is overdue or due today. The completed
  section folds, with its count.

Full-page month, week and day views are still there, from the menu (☰) or by
tapping a day twice.

### E-ink and colour

**Every background is white**, the page's and the cards'. What separates a
card from the rest is its 1 dp border, never a grey fill: a tinted background
eats contrast and forces refreshing more surface than needed.

The Note Air 5C is Kaleido 3, so there is colour, but with two limitations
that shape the palette (`ui/theme/EinkPalette.kt`): the filter drops
saturation to less than half of what an LCD would show, and the colour layer
runs at a third of the black-and-white resolution. Hence the rules:

- Eight tones, all dark and saturated. A pastel turns into dirty grey. Being
  below 45 % luminance, they also work as distinguishable greys if colour
  fails.
- **Colour never carries information alone.** It is redundant with shape: an
  event's accent bar, a day's dot and a task's checkbox read the same in
  greyscale.
- Text is always black on white. No palette tone is used for running text.
- The colour Google sends for each calendar is stored as is and snapped to the
  nearest tone **when painting**, not when saving: if the panel or the palette
  ever changes, the data is still right.

### Scaling

BooxOS declares a low density to make the most of the panel's 1404 px, so
Android believes it has some 900 dp of width and lays things out as if it were
a huge screen: tiny text, buttons impossible to hit with a finger and spare
air everywhere.

`ui/theme/EinkScale.kt` fixes it by **changing the density once**, at the root
of the app, to bring the short side to about 620 logical dp. dp and sp scale
together, the layout keeps its proportions, and the same code still works on an
emulator or on a Boox of another size; going size by size would have been
endless and tied to this device. Settings has a manual fine adjustment on top,
in case the automatic figure is off.

No window animations, no navigation transitions, no ripple. Controls are our
own (`ui/theme/EinkComponents.kt`) instead of Material's, which animate. So are
the icons (`ui/theme/EinkGlyphs.kt`): trivial shapes drawn on a 24×24 canvas,
so the stroke weight, which on e-ink is the difference between legible and
smudged, is under control regardless of what each BOM version ships.

`EinkRefresh` asks for a full refresh when switching screens and fast mode
while writing; it goes through reflection because `EpdController` has moved
between SDK versions and the control is an improvement, not a requirement.

---

## What v1 does not do

Besides what §13 of the scope excludes (CalDAV, Microsoft, sharing,
invitations, attachments):

- **Manual task order is not pushed to Google.** Google Tasks only accepts
  reordering through `tasks.move`, not `PATCH`. Order is local; the server's
  `position` is used as the initial order.
- **No local notifications are scheduled.** Alerts are stored and synced;
  Google delivers them on the account's devices. Scheduling them on the Boox
  would collide head-on with its power manager.
- **No event series are created.** Instances of a series pulled from Google
  are shown and can be edited one by one; the recurrence is preserved intact
  when saving, but there is no interface to define it.
- **No mail.** The native app has an envelope icon in its bar because
  `com.onyx.mail` is also a mail client. This one is not; the bar has "today"
  instead.
- **Reminders have no time.** The native app shows one, but Google Tasks only
  stores the day. It is their data model, not a shortcoming of the app.

---

## Known risks

| Risk | Status |
|---|---|
| **Onyx power manager** kills synchronisation | Mitigated with a notice on first run. There is no way to fix it from the app. |
| **Onyx SDK versions** (`onyxsdk-pen:1.5.4.3`, `onyxsdk-device:1.3.5.2`) | Verified on the Note Air 5C. `RawInputCallback` has changed abstract methods between versions. |
| **Writing latency** without the SDK | The app detects the device and warns in Settings when it falls back to the touch path. |
| **Google Tasks model**: date only, no time | Assumed in the data model and explained in the task editor. |
| **OCR model download** needs network the first time | Explicit button in Settings. |

---

## Tests

```bash
./gradlew test
```

They cover what breaks most easily in silence: bucketing events per day with
multi-day events, events crossing midnight and events ending exactly at
midnight; the RFC 3339 round trip and task due dates (the case that shifts
tasks one day in any zone west of Greenwich); the month grid with 4, 5 and 6
weeks; and the task-list sections.

GitHub Actions builds every push and runs these tests (`.github/workflows/build.yml`).

---

## Licence

MIT. See [`LICENSE`](LICENSE).
