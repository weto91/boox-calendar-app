# Google credentials

The app uses **OAuth 2.0 with PKCE through AppAuth**, not Google Sign-In.

The reason is the device: Boox tablets do not always ship Google Play
Services, and when they do it is often a partial install where `GoogleSignIn`
fails in ways that are hard to diagnose. AppAuth only needs a browser, and the
same code will serve the day CalDAV or Microsoft come in.

## 1. Google Cloud project

1. Go to <https://console.cloud.google.com/> and create a project.
2. **APIs & Services → Library**, enable the three:
   - Google Calendar API
   - Google Tasks API
   - Google Drive API (handwritten notes as PDF)

## 2. Consent screen (Google Auth Platform)

- **Branding**: app name "Boox Calendar", support e-mail, logo
  (`docs/logo.png`), home page
  `https://wetoteca.duckdns.org/booxcalendar/`, privacy policy and terms
  `https://wetoteca.duckdns.org/booxcalendar/privacidad.html`, authorised
  domain `wetoteca.duckdns.org`. The domain must be verified in Google Search
  Console as a **Domain property** (DNS TXT record); a URL-prefix property is
  not enough, and shared domains such as `github.io` cannot be verified that
  way.
- **Data access** (scopes):
  - `https://www.googleapis.com/auth/calendar`
  - `https://www.googleapis.com/auth/tasks`
  - `https://www.googleapis.com/auth/drive.file` (only what the app creates:
    its "Calendario Boox" folder and its PDFs. A PDF from another app enters
    through **Import** in the notebook, which copies it to that folder. It is
    the scope Google accepts without a security assessment.)
- **Audience**: *In production*. Verification for the sensitive scopes
  (Calendar, Tasks) is requested from the Verification Centre with a
  justification for each scope and an unlisted demo video showing the consent
  screen and each scope in use. While the app is unverified it works, but users
  see the "Google hasn't verified this app" screen and there is a cap of 100
  users.

> In *Testing* status only the listed test users can sign in and the refresh
> token expires after **7 days**. Production removes both limits.

> If Google answers **"Error 403: access_denied"** while in testing, the
> account is not in the test-user list.

## 3. OAuth client

**Google Auth Platform → Clients → Create client**:

- Application type: **Android**
- Package name: `com.weto.booxcal`
- SHA-1 fingerprint of the signing certificate.

For the debug key:

```bash
keytool -list -v -keystore ~/.android/debug.keystore \
        -alias androiddebugkey -storepass android -keypass android
```

The release build is signed with another key, so it needs its own client with
that key's SHA-1 (see `RELEASE.md`). An Android client holds a single
fingerprint.

**Essential, and off by default:** open the client, expand **Advanced
settings** and enable **"Enable custom URI scheme"**. Without it Google answers
`Error 400: invalid_request` when returning to the app, because the return
goes through `com.googleusercontent.apps.…:/`.

Also check that the client type is **Android**, not "Web application": a web
client does not accept custom schemes and gives the same error.

## 4. Put it in the project

Copy the client ID to `local.properties` at the repository root (the file is in
`.gitignore` and must not be committed):

```properties
sdk.dir=/path/to/your/Android/sdk
GOOGLE_OAUTH_CLIENT_ID=1234567890-abcdefghijk.apps.googleusercontent.com
GOOGLE_OAUTH_CLIENT_ID_RELEASE=<release client id>.apps.googleusercontent.com
```

Alternatively, export the same names as environment variables before building.

`app/build.gradle.kts` derives two things from them:

- `BuildConfig.OAUTH_CLIENT_ID` (per build type).
- The redirect scheme, which for Android clients Google requires to be the
  reversed client ID:
  `1234-abc.apps.googleusercontent.com` → `com.googleusercontent.apps.1234-abc`

That scheme is injected as `manifestPlaceholders["appAuthRedirectScheme"]`,
consumed by the `RedirectUriReceiverActivity` AppAuth declares in its own
manifest. Nothing has to be declared by hand.

The full redirect URI the app uses is:

```
com.googleusercontent.apps.<your-client-id>:/oauth2redirect
```

## 5. Check

Build, install, open **Settings → Connect my Google account**. The device's
browser opens. On return you should see your e-mail, and after the first sync
your calendars and task lists appear in Settings.

If the screen says *"OAuth client ID missing"*, `local.properties` was not
read: check it is at the repository root, not inside `app/`.

## About tokens

- AppAuth's `AuthState` is stored serialised in the private SharedPreferences
  `booxcal_auth`, excluded from Android backup (`res/xml/backup_rules.xml` and
  `data_extraction_rules.xml`).
- The authorisation request carries `access_type=offline` and
  `prompt=consent`. Without both, Google returns no refresh token and sync
  dies within the hour.
- A 401 invalidates the access token and retries **once**, in case the token
  was still within its window but had been revoked from the account.

## 6. Notes in Google Drive

**Settings → Notes in Google Drive → Enable** creates (or reuses) the folder
"Calendario Boox" in "My Drive". With `drive.file` the app cannot list the
user's folders, so there is nothing to choose; the folder can be renamed or
moved in Drive afterwards, the app tracks it by id. From then on:

- Every notebook note is uploaded as an **editable vector PDF** to the mirror
  of its folder: `Calendario Boox/NOTAS DEL DÍA/2026-01-25/Nota ….pdf`. The
  PDF embeds the notebook, so it comes back exact.
- **Import** in the notebook opens the system file picker (the user's Drive if
  the Drive app is installed, local storage, USB…). Each chosen PDF is copied
  to the mirror folder and becomes a note owned by the app: editable on top,
  movable, renamable, deletable, tagged "importado" as a reminder of its
  origin. A PDF that is not from Boox (a OneNote export, a scan) keeps each
  page as a background image; its typed text is read from the PDF and the rest
  (handwriting, text in images) is recognised by ML Kit on the page; all of it
  goes to the search index, with the page of each match. When the app writes
  such a note back, the page image and the typed text (invisible) go inside the
  PDF, so the file in Drive still looks like the original and stays searchable.
- Changing a note here replaces its PDF's content; changing it in Drive pulls
  it again. If both changed, the most recent wins.
- Moving a note between folders here moves the PDF in Drive, and vice versa.
- Drive folders appear here with the same path, but only those with a PDF
  inside (directly or in subfolders). Empty ones are not shown.
- Deleting is symmetric: a note deleted here sends its PDF to Drive's bin, and
  a PDF deleted in Drive deletes the note here.
