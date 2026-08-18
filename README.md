# HardPlay

A sideloaded Android app that turns private Telegram channels into a personal,
Netflix-style streaming library.

HardPlay indexes the media in channels **your own Telegram account can already read**,
builds a searchable local catalogue from the captions, and streams video straight out of
Telegram by byte range — so a 300 GB library costs a few megabytes of local index and
nothing is downloaded until you press play.

There is no server, no account, and no third-party service. The app talks to Telegram and
to nothing else.

---

## Contents

- [What it does](#what-it-does)
- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Building](#building)
- [Project layout](#project-layout)
- [Design system](#design-system)
- [Privacy](#privacy)
- [Testing](#testing)
- [Known limitations](#known-limitations)
- [Scope and intent](#scope-and-intent)
- [Third-party components](#third-party-components)
- [Licence](#licence)

---

## What it does

**Library.** A poster grid over every channel you have added, merged into one catalogue
with a per-source filter. Cell shape (16:9, 2:3, 1:1) and column count are yours to set;
one column becomes a full-width list row with a complete caption.

**Streaming, not downloading.** Playback maps onto TDLib's ranged download, so a large
file starts in a second or two and seeking backwards costs nothing because the bytes are
already cached. The chunk cache has a user-set cap, 4 GB by default.

**Search that works offline.** Captions and tags are indexed with SQLite FTS4. The
tokeniser keeps Unicode combining marks, which is what makes Indic and other scripts
searchable rather than splitting a word at its matra.

**Recommendations, computed locally.** The Discover tab ranks unwatched items by how many
tags they share with what you have recently played. It is a SQL query over your own index —
no model, no request, nothing leaves the device.

**Tags.** Captions are parsed into tags on a rule basis (hashtags, bracketed labels,
`Key: value` lines, quality tokens), and every item has a manual tag editor with
autocomplete ordered by existing usage, so you do not end up with `bts`,
`Behind The Scenes` and `behind the scenes` as three categories.

**Artwork.** Telegram is inconsistent about previews, so posters fall back through five
rungs: a decoded video frame, a large photo rung, a neighbouring screenshot the channel
posted as a preview, Telegram's own thumbnail, and finally the ~40px inline
`minithumbnail` that arrives inside the message. Videos that Telegram gave no artwork at
all get a real frame decoded from the file.

**Player.** Custom chrome over a bare video surface — scrubber with a buffered band,
double-tap skip, speed, pinch-zoom and pan, audio and subtitle track selection,
picture-in-picture, and open-in-another-app via `FileProvider`. Photos get a full
resolution viewer with progressive loading.

**Discreet presence.** The launcher shows a neutral name and icon by default; the real
identity appears only after the biometric gate. Window content is marked
`FLAG_SECURE`, so screenshots and the recents thumbnail are blank. Both are switchable.

**Five tabs.** Library, Discover, Saved, History, Manage.

---

## How it works

```
┌─────────────────────────────────────────────────────────────┐
│  Compose UI  ·  five tabs, one player, one media grid       │
├─────────────────────────────────────────────────────────────┤
│  ViewModels  ·  one query object per screen, Paging 3       │
├─────────────────────────────────────────────────────────────┤
│  Repositories                                               │
├──────────────────────────────┬──────────────────────────────┤
│  Room  ·  metadata only      │  TelegramGateway (interface) │
│  media / tags / playback     │      ├── TdlibTelegramGateway│
│  favourites / FTS4 / view    │      └── DemoTelegramGateway │
├──────────────────────────────┴──────────────────────────────┤
│  TDLib (JNI)  ─────────────── MTProto ──────────▶  Telegram │
└─────────────────────────────────────────────────────────────┘
```

Three decisions shape everything else:

**`TelegramGateway` is the only seam.** `libtdjni.so` and the generated
`org.drinkless.tdlib` bindings are build outputs, not source, so the app must compile with
or without them. Everything depends on the interface; exactly one file (`di/TelegramModule`)
chooses between the real gateway and `DemoTelegramGateway`. Nothing else in the codebase
branches on whether TDLib is present.

**Demo mode is a first-class configuration, not a stub.** It generates a deterministic
library with real rendered artwork, so the UI can be built and reviewed before credentials
exist. It does not fake video bytes — asking for a range says so plainly rather than
hanging in a buffering state.

**The database never holds media.** Indexing writes metadata only. The one exception is
the inline `minithumbnail`, which is a couple of kilobytes Telegram already sent with the
message.

For the reasoning behind individual choices — why FTS is a standalone table, why sorting
is a `CASE` ladder, why the video view is a `TextureView`, why Room views must be created
by the migration itself — see [`CLAUDE.md`](CLAUDE.md), which records the decisions that
have a cheaper-looking alternative that is wrong.

---

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer; targets API 35 |
| ABIs | `arm64-v8a`, `armeabi-v7a`, `x86_64` |
| Telegram | Your own `api_id` / `api_hash` from [my.telegram.org](https://my.telegram.org) |
| Build host | JDK 17, Android SDK 35, NDK 27.1 |
| TDLib build host | Linux or WSL2 with `cmake`, `ninja`, `gperf`, `php` |

HardPlay signs in as a **user** (phone number + one-time code), because reading channel
history requires it. A bot token will not work, and the build rejects one by name if it
finds one in `local.properties`.

---

## Building

### 1. Credentials

Copy the template and fill it in:

```bash
cp local.properties.example local.properties
```

```properties
sdk.dir=/path/to/Android/Sdk
hardplay.telegram.apiId=1234567          # 6-8 digits, from my.telegram.org
hardplay.telegram.apiHash=0123456789abcdef0123456789abcdef   # exactly 32 hex chars
```

Both values are validated at configure time. Anything unusable falls back to demo mode
with a named warning rather than producing a build that fails somewhere far from the cause.

### 2. TDLib

The native library and its Java bindings are not in this repository. Build them:

```bash
bash tools/build-tdlib.sh
```

The script fetches its own SDK and NDK, builds OpenSSL 3.5.x and then TDLib 1.8.66 for
three ABIs, verifies 16 KB LOAD alignment (required by Android 15), and installs the
artifacts into the app module. A cold run takes 60–150 minutes; every stage is gated on its
output artifact, so a re-run resumes rather than restarting.

Without this step the app still builds and runs — in demo mode.

### 3. The app

```bash
./gradlew assembleDebug          # per-ABI plus a universal APK
./gradlew testDebugUnitTest      # 59 unit tests
./gradlew connectedDebugAndroidTest   # migration tests; needs a device or emulator
```

Install the APK matching your device's ABI from
`app/build/outputs/apk/debug/`. The universal APK is the one to reach for if you are
unsure.

### 4. Release builds

Signing is read from `local.properties`:

```properties
hardplay.keystore.path=/absolute/path/to/release.jks
hardplay.keystore.storePassword=...
hardplay.keystore.keyAlias=...
hardplay.keystore.keyPassword=...
```

With no keystore configured, `assembleRelease` falls back to the debug key so the task
still works out of the box — useful for testing, **not** for anything you intend to update
later, since a debug-signed install cannot be upgraded by a properly signed build.

---

## Project layout

```
app/src/main/java/com/hardplay/
├── core/            Formatting shared across screens (durations, sizes, dates)
├── data/
│   ├── db/          Room: entities, DAOs, FTS, the library_row view, migrations
│   ├── model/       Query and card-shape models
│   ├── prefs/       DataStore settings
│   ├── repo/        Reads and writes the UI is allowed to see
│   └── tagging/     Caption parser
├── di/              Hilt modules. TelegramModule is the only TDLib-aware file
├── playback/        Media3 DataSource over TDLib ranges, external-open helper
├── sync/            The indexer: two cursors, budgeted, resumable
├── telegram/        The gateway interface, its vocabulary, and demo mode
└── ui/
    ├── components/  The design system's parts, including one shared media grid
    ├── discover/ history/ library/ manage/ saved/    the five tabs
    ├── image/       Poster rungs, Coil fetcher, frame extraction
    ├── media/       Per-item action sheet, used by every list
    ├── nav/         Routes, the bottom bar, cross-tab handoff
    ├── player/      Playback, photo viewer, tracks, PiP
    └── theme/       Colour, type, shape, motion, grain

app/src/tdlib/kotlin/       Real gateway. Wired in only when the bindings exist
app/src/no-tdlib/kotlin/    Same-shaped factory returning null
app/schemas/                Exported Room schemas. Required by the migration tests
tools/build-tdlib.sh        Reproducible TDLib build
```

---

## Design system

Five colours, and no sixth. State is carried by weight, opacity and an ember edge rather
than by a hue — there is no green success or amber warning anywhere in the app.

```
bg      #08070A   ink black
surface #1A0B10   oxblood
accent  #FF4D2E → #FF8A3D   ember   (the only gradient in the app)
type    #F5F0E8   bone
muted   #6B5C5F   ash rose
```

Type is Archivo variable — narrow and heavy for display, normal for UI, tabular figures
for anything numeric so a running timecode does not jitter — with Instrument Serif Italic
for editorial lines. Both are bundled rather than fetched, because a downloadable font is
a network request.

Radii are 3–6dp and nothing is pill-shaped. Motion decelerates. A film-grain overlay
dithers the banding that a flat near-black fill shows on OLED. The buffering indicator is
a custom mark, not a `CircularProgressIndicator`.

Debug builds include a design gallery — every component on one sheet — reachable from
Settings.

---

## Privacy

The app is built so that this list is short and checkable:

- **No analytics, no crash reporting, no ads, no remote config.** The `Application` class
  contains dependency injection and nothing else.
- **Coil has no network component registered at all.** Every image comes from TDLib or from
  resources, so a stray `AsyncImage(model = someUrl)` cannot make a request.
- **The only permissions** are `INTERNET`, `ACCESS_NETWORK_STATE`, biometric, wake lock,
  notifications and foreground-service for playback.
- **No cleartext traffic**, and TDLib's log is disabled entirely in release builds because
  it records phone numbers and chat ids.
- **The Telegram session is encrypted** by TDLib with a key held in the Android Keystore,
  and lives under `noBackupFilesDir` so it is excluded from backups and transfers.
- **`allowBackup=false`**, and `FLAG_SECURE` by default.
- **The metadata database is not encrypted**, deliberately: it holds captions and tags,
  while the thing worth protecting is the session, which is already encrypted. Adding
  SQLCipher would mean a passphrase to manage in exchange for protecting text that the
  biometric gate already stands in front of.

---

## Testing

```bash
./gradlew testDebugUnitTest            # 59 tests
./gradlew connectedDebugAndroidTest    # 2 migration tests, on a device or emulator
```

The instrumented migration tests are not optional, and the reason is worth repeating: a
clean install creates the newest schema directly and runs **no** migrations, so unit tests,
a fresh install and an emulator smoke test can all pass while an *upgrade* — which is what
every existing install gets — crashes on launch. `MigrationTestHelper` compares the
migrated database against the exported schema column by column, index by index and view by
view. That check is the only thing that catches it.

---

## Known limitations

- **Not distributed through any store.** Sideload only.
- **One `api_id` per installation.** The credentials are compiled in, so an APK built by
  someone else carries their `api_id`. Build your own.
- **No adaptive quality.** A Telegram video is one file at one resolution; there is no
  ladder to switch between, and the player states the resolution as a fact instead of
  offering a menu that could not do anything.
- **Frame extraction is best-effort.** A video whose `moov` atom sits at the end of the
  file will exceed the byte budget and keep the thumbnail it had.
- **`FLAG_SECURE` and picture-in-picture conflict** on some devices, which render the
  floating window black. That is why screenshot blocking is a setting rather than a
  constant.
- **Backfilling a large channel takes several sessions** by design. Telegram pages history
  backwards only, so each run spends a bounded number of pages and persists its cursor.
- **Release builds are minified.** R8 is enabled; if you add reflection-dependent code,
  add keep rules.

---

## Scope and intent

HardPlay is a personal media client. It reads channels the signed-in account already has
access to and it adds no capability that the official Telegram client does not have — it
presents that content as a browsable library instead of a chat log, and caches metadata so
the result is searchable offline.

It does not bypass access controls, join channels on your behalf, scrape, redistribute, or
export anything off the device. Use it with content you are entitled to access, and observe
Telegram's Terms of Service and the copyright law that applies where you are.

---

## Third-party components

| Component | Licence |
|---|---|
| [TDLib](https://github.com/tdlib/td) | Boost Software License 1.0 |
| [OpenSSL](https://www.openssl.org) 3.5.x | Apache License 2.0 |
| AndroidX, Compose, Room, Media3, Paging, DataStore, WorkManager | Apache License 2.0 |
| [Hilt / Dagger](https://dagger.dev/hilt/) | Apache License 2.0 |
| [Coil](https://coil-kt.github.io/coil/) | Apache License 2.0 |
| [Archivo](https://fonts.google.com/specimen/Archivo) | SIL Open Font License 1.1 |
| [Instrument Serif](https://fonts.google.com/specimen/Instrument+Serif) | SIL Open Font License 1.1 |

Both bundled typefaces are under the SIL Open Font License 1.1, which requires the licence
text to travel with the fonts; it is in [`licenses/OFL-1.1.txt`](licenses/OFL-1.1.txt).

---

## Licence

HardPlay's own source is released under the [MIT License](LICENSE).

That covers the code in this repository and nothing else. The bundled typefaces stay under
the SIL OFL 1.1, and TDLib and OpenSSL keep their own terms — all four are permissive and
compatible with MIT, so a build combining them can be redistributed, but each licence
travels with its own component.

