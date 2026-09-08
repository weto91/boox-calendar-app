# Publicar una versión

Hay dos repositorios:

- **Privado** (`weto91/boox-calendar`): donde se desarrolla. Nada sale de él
  por sí solo.
- **Público** (`weto91/boox-calendar-app`): el código de la última versión
  publicada, la web (GitHub Pages desde `docs/`) y las releases con el APK.

Lo que pasa del privado al público lo decide una etiqueta `v1.2.3` en el
privado: el flujo `.github/workflows/publish.yml` compila y firma el APK,
copia el código de ese momento al público como **un solo commit** «Versión
1.2.3» (sin el historial de desarrollo) y crea allí la release con el APK.
Antes de subir nada comprueba que el código no menciona las herramientas con
las que se desarrolla.

La app se firma con una clave propia (una sola, para siempre: Android solo
actualiza una app si la nueva versión va firmada con la misma clave que la
instalada).

## Una sola vez: la clave de firma

```bash
keytool -genkeypair -v -keystore booxcal-release.jks -alias booxcal \
  -keyalg RSA -keysize 2048 -validity 10000
```

Guarda `booxcal-release.jks` y sus contraseñas fuera del repositorio y con
copia de seguridad. Está en `.gitignore`: nunca se sube.

La huella SHA-1 de esa clave es lo que identifica a la app ante Google:

```bash
keytool -list -v -keystore booxcal-release.jks -alias booxcal | grep SHA1
```

Con ella se crea en Google Cloud (Google Auth Platform → Clientes) un cliente
Android «release», con el paquete `com.weto.booxcal`, y se activa en él
«Habilitar esquema de URI personalizado». Su client ID es
`GOOGLE_OAUTH_CLIENT_ID_RELEASE`.

## `local.properties` completo

```properties
sdk.dir=/ruta/a/tu/Android/sdk
GOOGLE_OAUTH_CLIENT_ID=<client id de depuración>.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_ID_RELEASE=<client id de release>.apps.googleusercontent.com
RELEASE_STORE_FILE=/ruta/booxcal-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=booxcal
RELEASE_KEY_PASSWORD=...
```

`RELEASE_STORE_FILE` puede ser una ruta absoluta o relativa a la raíz del
proyecto. Sin estas líneas `assembleRelease` avisa y saca un APK sin firmar.

## Cada versión

1. En `app/build.gradle.kts`, sube `versionCode` (un entero, siempre mayor que
   el anterior) y `versionName` (lo que ve la gente: `1.0.1`, `1.1.0`…).
2. Compila y prueba en la tablet la versión de release, que va minificada
   (Build → Select Build Variant → release → Assemble Project, o
   `./gradlew assembleRelease`). Conecta la cuenta, sincroniza, escribe una
   nota, súbela a Drive, importa un PDF. Si algo falla solo en release, casi
   siempre es una regla que falta en `app/proguard-rules.pro`.
3. Confirma en `main` y crea la etiqueta **anotada**: su mensaje son las notas
   de la versión que verá la gente en la release.
   ```bash
   git tag -a v1.0.1 -m "Qué cambia en esta versión, en una o varias líneas."
   git push origin v1.0.1
   ```
4. El flujo «Publicar» del privado hace el resto. En un cuarto de hora, en el
   público hay un commit «Versión 1.0.1», la etiqueta y la release con
   `boox-calendar-1.0.1.apk`.

Secretos que necesita el flujo, en el repositorio **privado** (Settings →
Secrets and variables → Actions):

- `PUBLIC_REPO_TOKEN`: token de acceso personal (fine-grained) con permiso
  *Contents: read and write* solo sobre el repositorio público.
- `RELEASE_KEYSTORE_BASE64`: el keystore en base64
  (`certutil -encode` en Windows, `base64 -w0` en Linux, sin las líneas
  BEGIN/END).
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- `GOOGLE_OAUTH_CLIENT_ID_RELEASE`.

### Solo la web

Para actualizar la política de privacidad o la página de inicio sin publicar
código: Actions → **Publicar** → *Run workflow* → deja marcado «Solo la web».
Copia `docs/` al público y nada más.

## Instalar en el Boox

Ajustes → Aplicaciones → permitir instalar apps de fuentes desconocidas para el
navegador o el gestor de archivos; abrir el APK descargado. Las actualizaciones
se instalan encima sin perder datos.
