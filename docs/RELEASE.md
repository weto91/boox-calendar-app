# Publishing a release

There are two repositories:

- **Private** (`weto91/boox-calendar`): where development happens. Nothing
  leaves it on its own.
- **Public** (`weto91/boox-calendar-app`): the code of the latest published
  version, a copy of the website (`docs/`) and the releases with the APK. The
  official website (the one Google checks) is served at
  <https://wetoteca.duckdns.org/booxcalendar/> from a private server: that is
  where `docs/index.html`, `docs/privacidad.html` and `docs/logo.png` go when
  they change.

What moves from private to public is decided by a `v1.2.3` tag in the private
repository: the `.github/workflows/publish.yml` workflow builds and signs the
APK, copies the code at that point to the public repository as **a single
commit** "Versión 1.2.3" (no development history) and creates the release
there with the APK attached. Before uploading anything it checks that the code
does not mention the tools it is developed with.

The app is signed with its own key (one, forever: Android only updates an app
if the new version is signed with the same key as the installed one).

## Once: the signing key

```bash
keytool -genkeypair -v -keystore booxcal-release.jks -alias booxcal \
  -keyalg RSA -keysize 2048 -validity 10000
```

Keep `booxcal-release.jks` and its passwords outside the repository, with a
backup. It is in `.gitignore`: it is never committed.

The SHA-1 fingerprint of that key is what identifies the app to Google:

```bash
keytool -list -v -keystore booxcal-release.jks -alias booxcal | grep SHA1
```

With it, create in Google Cloud (Google Auth Platform → Clients) an Android
client "release" with package `com.weto.booxcal`, and enable "Custom URI
scheme" in it. Its client ID is `GOOGLE_OAUTH_CLIENT_ID_RELEASE`.

## Full `local.properties`

```properties
sdk.dir=/path/to/your/Android/sdk
GOOGLE_OAUTH_CLIENT_ID=<debug client id>.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_ID_RELEASE=<release client id>.apps.googleusercontent.com
RELEASE_STORE_FILE=/path/booxcal-release.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=booxcal
RELEASE_KEY_PASSWORD=...
```

`RELEASE_STORE_FILE` may be absolute or relative to the project root. Without
these lines `assembleRelease` warns and produces an unsigned APK.

## Every version

1. In `app/build.gradle.kts`, bump `versionCode` (an integer, always greater
   than the previous one) and `versionName` (what people see: `1.0.1`,
   `1.1.0`…).
2. Build and test the release variant on the tablet; it is minified
   (Build → Select Build Variant → release → Assemble Project, or
   `./gradlew assembleRelease`). Connect the account, sync, write a note, push
   it to Drive, import a PDF. If something fails only in release, it is almost
   always a missing rule in `app/proguard-rules.pro`.
3. On GitHub, **private** repository → **Releases** → *Draft a new release*:
   - *Choose a tag* → type `v1.0.1` → *Create new tag: v1.0.1 on publish*.
   - *Target*: `main`.
   - Title: `Boox Calendar 1.0.1`.
   - Description: what changes in this version. It is what people will see
     in the public release.
   - *Publish release*.
4. The private repository's "Publicar" workflow does the rest. In about
   fifteen minutes the public repository has a "Versión 1.0.1" commit, the tag
   and the release with `boox-calendar-1.0.1.apk`.

Secrets the workflow needs, in the **private** repository (Settings → Secrets
and variables → Actions):

- `PUBLIC_REPO_TOKEN`: fine-grained personal access token with *Contents: read
  and write* on the public repository only.
- `RELEASE_KEYSTORE_BASE64`: the keystore in base64 (`certutil -encode` on
  Windows, `base64 -w0` on Linux, without the BEGIN/END lines).
- `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`.
- `GOOGLE_OAUTH_CLIENT_ID_RELEASE`.

### Website only

To update the privacy policy or the home page without publishing code:
Actions → **Publicar** → *Run workflow* → leave "Solo la web" checked. It
copies `docs/` to the public repository and nothing else. Then upload the three
files to the server as well.

## Installing on the Boox

Settings → Apps → allow installing apps from unknown sources for the browser
or the file manager; open the downloaded APK. Updates install on top without
losing data.
