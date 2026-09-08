# Notas del dispositivo — Onyx Boox Note Air 5C, BooxOS V4.2

## 1. Lo primero: la optimización de energía

BooxOS mata servicios en segundo plano de forma agresiva. Es la causa habitual
de que la sincronización "no funcione" en apps de terceros en estos
dispositivos, y no hay nada que se pueda hacer desde el código.

**Ajustes del sistema → Energía → Optimización de apps → desactivar para
«Calendario».**

La app lo recuerda en el primer arranque, en su propia pantalla de Ajustes,
hasta que se marca como entendido.

## 2. Confirmar el nivel de API

```bash
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.release
adb shell getprop ro.product.manufacturer
```

El proyecto va con `minSdk = 26`, que da `java.time` nativo sin desugaring y
cubre dispositivos Onyx anteriores. Si el Note Air 5C reporta un SDK menor
—no debería—, hay que bajar `minSdk` y añadir
`coreLibraryDesugaringEnabled = true` con `desugar_jdk_libs`.

`EinkRefresh.isOnyxDevice` se apoya en que `ro.product.manufacturer` o
`ro.product.brand` valgan `onyx`. Si el comando de arriba devuelve otra cosa,
hay que ajustar esa comprobación o el camino del `TouchHelper` no se activará
nunca.

## 3. SDK de Onyx

Repositorio Maven, ya declarado en `settings.gradle.kts`:

```
https://repo.boox.com/repository/maven-public/
```

Versiones declaradas en `app/build.gradle.kts`:

```kotlin
implementation("com.onyx.android.sdk:onyxsdk-pen:1.4.11.1")
implementation("com.onyx.android.sdk:onyxsdk-device:1.2.30")
```

**Sin verificar contra el dispositivo.** Si Gradle no las resuelve, mira el
índice del repositorio y coge las últimas:

```
https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-pen/
https://repo.boox.com/repository/maven-public/com/onyx/android/sdk/onyxsdk-device/
```

### Si `RawInputCallback` no compila

Es una clase abstracta y **el juego de métodos abstractos ha cambiado entre
versiones**. `PenCanvasView` implementa los ocho clásicos:

```
onBeginRawDrawing / onEndRawDrawing
onRawDrawingTouchPointMoveReceived / onRawDrawingTouchPointListReceived
onBeginRawErasing / onEndRawErasing
onRawErasingTouchPointMoveReceived / onRawErasingTouchPointListReceived
```

Las versiones nuevas añaden `onPenUpRefresh(RectF)` y `onPenActive(TouchPoint)`.
Si el compilador se queja de métodos sin implementar, añádelos; si se queja de
`override` sobre algo que no existe, quítalos.

### Si `EpdController` no está

No pasa nada: `EinkRefresh` va por reflexión y se desactiva sola. Se pierde el
control fino del refresco, no la funcionalidad.

## 4. Cómo se dibuja el trazo

El SDK pinta directamente en el panel mientras se escribe. La app **no** repinta
la superficie después de cada trazo: solo lo vuelca al bitmap de respaldo. Si se
repintara, se vería un parpadeo en cada línea.

La superficie sí se repinta al deshacer, limpiar, borrar o recargar una nota, y
ahí sí se desactiva temporalmente el render del SDK
(`setRawDrawingRenderEnabled(false)`) para que no peleen por la superficie.

Los puntos del SDK llegan en **coordenadas de pantalla**; el bitmap está en
coordenadas de la vista. `PenCanvasView` resta el origen de la vista. Si los
trazos aparecen desplazados, es ahí donde hay que mirar.

## 5. Diagnóstico rápido

```bash
# Instalar
./gradlew installDebug

# Ver los registros de la app
adb logcat -s PenCanvasView:* EinkRefresh:* SyncEngine:* SyncWorker:* \
              GoogleAuthManager:* InkRecognizer:*

# Estado de WorkManager
adb shell dumpsys jobscheduler | grep -A5 com.weto.booxcal
```

Síntomas y dónde mirar:

| Síntoma | Causa probable |
|---|---|
| El trazo va con retardo | `TouchHelper` no se creó. Mira `PenCanvasView` en logcat y comprueba `ro.product.manufacturer`. |
| La sincronización solo funciona con la app abierta | Optimización de energía. Punto 1. |
| Las tareas se mueven un día | Zona horaria en el vencimiento. Empieza por `Rfc3339Test`. |
| "Modelo no descargado" en el OCR | Falta red para la descarga inicial. Botón en Ajustes. |
| Fantasmas en pantalla al cambiar de vista | `EpdController` no disponible; comprueba la versión de `onyxsdk-device`. |

## 6. Sobre la app nativa

`com.onyx.mail` crea un calendario propio, *ONYX Calendar*, en la cuenta de
Google del usuario. Si tienes la app nativa configurada, ese calendario
aparecerá también aquí como uno más y se puede ocultar desde Ajustes.

Sus **recordatorios** no aparecerán, porque no salen del dispositivo por ningún
canal: ni ContentProvider, ni CalendarProvider, ni CalDAV, ni ficheros en
`Android/data`. Está comprobado en §1 del scope. Las tareas hay que volver a
crearlas aquí o en Google Tasks.
