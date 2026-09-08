# Credenciales de Google

La app usa **OAuth 2.0 con PKCE a través de AppAuth**, no Google Sign-In.

La razón es el dispositivo: los Boox no siempre traen Google Play Services, y
cuando lo traen suele ser una instalación parcial en la que `GoogleSignIn` falla
de formas difíciles de diagnosticar. AppAuth solo necesita un navegador, y el
mismo código servirá el día que entre CalDAV o Microsoft.

## 1. Proyecto en Google Cloud

1. Entra en <https://console.cloud.google.com/> y crea un proyecto.
2. **APIs y servicios → Biblioteca**, y habilita las tres:
   - Google Calendar API
   - Google Tasks API
   - Google Drive API (para las notas manuscritas como PDF)

## 2. Pantalla de consentimiento

**APIs y servicios → Pantalla de consentimiento de OAuth**:

- Tipo de usuario: **Externo**.
- Rellena nombre de la app y correo de contacto.
- Ámbitos: añade
  - `https://www.googleapis.com/auth/calendar`
  - `https://www.googleapis.com/auth/tasks`
  - `https://www.googleapis.com/auth/drive.file` (solo lo que crea la app: su
    carpeta «Calendario Boox» y sus PDF. Un PDF de otra app entra con
    «Importar» desde el cuaderno, que lo copia a esa carpeta. Es el ámbito
    que Google acepta sin auditoría de seguridad)

> Si la cuenta ya estaba conectada antes de añadir Drive, hay que
> **desconectar y volver a conectar** desde Ajustes para que Google pida el
> permiso nuevo. Ajustes lo avisa.
- Usuarios de prueba: **añade tu propia cuenta de Google** y la de cada persona
  de la familia que vaya a conectar la app (en la consola nueva está en
  *Google Auth Platform → Público → Usuarios de prueba → Añadir usuarios*).

Déjala en modo *Prueba*. No hace falta publicarla ni pasar verificación: la app
no se distribuye por Play Store.

> Si al entrar Google dice **«no ha completado el proceso de verificación…
> Error 403: access_denied»**, es que la cuenta con la que entras no está en esa
> lista de usuarios de prueba. Se añade y se vuelve a intentar; no hay que
> recompilar nada.

> En modo *Prueba*, el refresh token caduca a los **7 días**. Es el
> comportamiento normal de Google y significa volver a conectar la cuenta una vez
> por semana. Si molesta, hay que publicar la aplicación (el estado *En
> producción* con ámbitos sensibles pide verificación de Google; para uso propio
> se puede publicar y aceptar la pantalla de "app no verificada").

## 3. Cliente de OAuth

**APIs y servicios → Credenciales → Crear credenciales → ID de cliente de OAuth**:

- Tipo de aplicación: **Android**
- Nombre del paquete: `com.weto.booxcal`
- Huella SHA-1 del certificado de firma.

Para la clave de depuración:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
        -alias androiddebugkey -storepass android -keypass android
```

Si más adelante firmas una release con otro keystore, crea un segundo ID de
cliente con su SHA-1.

**Imprescindible, y no viene marcado por defecto:** en el mismo cliente
Android, despliega **Configuración avanzada** y activa **«Habilitar esquema de
URI personalizado»**. Sin esto Google responde `Error 400: invalid_request` al
volver a la app, porque la vuelta es por `com.googleusercontent.apps.…:/`.

Comprueba también que el tipo del cliente es **Android**, no «Aplicación
web»: un cliente web no admite esquemas propios y da el mismo error.

**Y un paso que no está a la vista:** una vez creado el cliente, ábrelo, despliega
**Configuración avanzada** y activa **«Habilitar esquema de URI personalizado»**.
Guarda. Sin eso, al conectar desde la tablet Google responde
*«Error 400: invalid_request»*: la app vuelve por `com.googleusercontent.apps.…:/`
y Google solo acepta esa vuelta si el interruptor está activado.

## 4. Meterlo en el proyecto

Copia el client ID a `local.properties`, en la raíz del repositorio (ese fichero
está en `.gitignore` y no debe subirse):

```properties
sdk.dir=/ruta/a/tu/Android/sdk
GOOGLE_OAUTH_CLIENT_ID=1234567890-abcdefghijk.apps.googleusercontent.com
```

Alternativamente, exporta `GOOGLE_OAUTH_CLIENT_ID` como variable de entorno
antes de compilar.

`app/build.gradle.kts` deriva de ahí dos cosas:

- `BuildConfig.OAUTH_CLIENT_ID`
- El esquema de redirección, que para clientes Android Google exige que sea el
  client ID invertido:
  `1234-abc.apps.googleusercontent.com` → `com.googleusercontent.apps.1234-abc`

Ese esquema se inyecta como `manifestPlaceholders["appAuthRedirectScheme"]`, que
es lo que consume el `RedirectUriReceiverActivity` que AppAuth declara en su
propio manifest. No hay que declarar nada a mano.

La URI de redirección completa que usa la app es:

```
com.googleusercontent.apps.<tu-client-id>:/oauth2redirect
```

## 5. Comprobar

Compila, instala, abre **Ajustes → Conectar con Google**. Se abrirá el navegador
del dispositivo. Al volver deberías ver tu correo, y tras la primera
sincronización aparecerán tus calendarios y listas de tareas en Ajustes.

Si la pantalla dice *"Falta el client ID de OAuth"*, `local.properties` no se
leyó: comprueba que está en la raíz del repositorio, no dentro de `app/`.

## Sobre los tokens

- `AuthState` de AppAuth se guarda serializado en las SharedPreferences privadas
  `booxcal_auth`, excluidas de la copia de seguridad de Android
  (`res/xml/backup_rules.xml` y `data_extraction_rules.xml`).
- La petición de autorización lleva `access_type=offline` y `prompt=consent`.
  Sin las dos, Google no devuelve refresh token y la sincronización muere en una
  hora.
- Un 401 invalida el token de acceso y reintenta **una** vez, por si el token
  seguía dentro de su ventana pero había sido revocado desde la cuenta.

## 4. Notas en Google Drive

En **Ajustes → Notas en Google Drive → Elegir…** se navega por «Mi unidad» y
se elige la carpeta. A partir de ahí:

- Cada nota del cuaderno sube como **PDF vectorial editable** a la carpeta
  espejo de la suya: `<carpeta>/NOTAS DEL DÍA/2026-01-25/Nota ….pdf`. El PDF
  lleva el cuaderno embebido, así que al volver se recupera exacto.
- Los PDF que otra app deje en esa carpeta (o en sus subcarpetas) entran como
  notas con la etiqueta «importado», que no se quita. Son de **solo lectura**:
  no se escriben, ni se mueven, ni se renombran, ni se borran desde la app
  (la app que creó el PDF no se enteraría de los cambios). Sí se les pueden
  poner más etiquetas, que se quedan en la app, y su texto se reconoce para
  poder buscarlas. Las carpetas que contienen alguna nota importada tampoco
  se renombran, mueven ni borran desde aquí: se cambian en Drive. Las notas
  de esta app sí pueden ir dentro de esas carpetas, con todo lo demás.
- Cambiar una nota aquí sustituye el contenido de su PDF; cambiarlo en Drive
  vuelve a bajarlo. Si cambian los dos, se queda el más reciente.
- Mover una nota de carpeta aquí mueve el PDF en Drive, y al revés. Renombrar
  renombra el PDF solo si lo creó esta app.
- Un PDF que no viene de Boox (una exportación de OneNote, un escaneo…) no
  se convierte en trazos: cada página se guarda como imagen y se enseña tal
  cual, de solo lectura. Su texto a máquina se lee exacto del PDF y lo demás
  (manuscrito, texto en imágenes) lo reconoce ML Kit sobre la página; todo
  va al índice de búsqueda, con la página de cada coincidencia.
- Las carpetas de Drive aparecen aquí con su misma ruta, pero solo las que
  tienen algún PDF dentro (directo o en subcarpetas). Las vacías y las
  auxiliares que Boox deja junto a cada PDF (una carpeta del mismo nombre con
  un HTML de propiedades) no se enseñan. Una carpeta de Drive sin PDF que se
  hubiera colado aquí se quita sola en la siguiente pasada, si aquí también
  está vacía.
- Borrar es simétrico: una nota borrada aquí manda su PDF a la papelera de
  Drive, y un PDF borrado en Drive borra la nota de aquí.
