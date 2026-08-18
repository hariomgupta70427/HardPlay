# HardPlay — working notes for Claude Code

Single-user sideloaded Android app that turns a private Telegram channel into a
Netflix-style streaming library. TDLib is the backend; there is no server.
Full spec in `HardPlay_PRD.md`. This file records decisions the spec left open,
plus the environment quirks that will otherwise bite.

## Locked product decisions

| Decision | Choice |
|---|---|
| Visual identity | **Oxblood & Ember** (tokens below) |
| Device presence | **Discreet disguise** — neutral launcher label/icon, real branding only after biometric unlock, toggleable in Settings |
| Content scope | **Multi-channel**, chosen via in-app picker on first run; library merges sources and offers a source filter |
| Credentials | Not yet issued → **demo mode is a first-class requirement**, not an afterthought |
| Auto-tagging | Rule-based caption parse + manual tag editor (PRD §11.1) |
| Chunk cache cap | 4 GB default, user-adjustable in Settings (PRD §11.2) |

## Design system — Oxblood & Ember

Never introduce a colour outside this set. No purple, no glassmorphism, no
gradient chrome, no emoji — accent gradient is for the accent only.

```
bg      #08070A   ink black
surface #1A0B10   oxblood
accent  #FF4D2E -> #FF8A3D   ember (gradient permitted here only)
type    #F5F0E8   bone
muted   #6B5C5F   ash rose
```

Type: **Archivo** variable (`ofl/archivo/Archivo[wdth,wght].ttf`) at 400–900 for
display and UI, **Instrument Serif Italic** for editorial accents, Archivo with
`tnum` for timecodes. Bundle the TTFs in `res/font` — downloadable Google Fonts
would breach the no-third-party-network guarantee (PRD §9).

Texture: subtle film grain overlay. Motion: `SharedTransitionLayout` for
grid→player, shimmer skeletons, haptic tick on skip/tag. Custom buffering mark —
never a stock `CircularProgressIndicator`.

## Architecture rule that matters most

`libtdjni.so` and the generated `org.drinkless.tdlib` bindings are **build
outputs, not source** (gitignored). The app must compile with or without them:

- Everything depends on the `TelegramGateway` interface in `src/main`.
- `src/tdlib/kotlin` holds the real `TdlibGatewayFactory`; `src/no-tdlib/kotlin`
  holds a same-shaped factory returning `null`.
- `app/build.gradle.kts` wires exactly one of those source sets based on whether
  `src/main/java/org/drinkless/tdlib/TdApi.java` exists, and exposes
  `BuildConfig.HAS_TDLIB`.
- **Nothing else in the codebase may branch on TDLib presence** — only
  `di/TelegramModule.kt` picks between the real gateway and `DemoTelegramGateway`.

## Environment quirks

- **The system JDK is broken** (`C:\Program Files\Java\jdk-20` has no
  `lib/jvm.cfg`). Always build with Android Studio's JBR:
  ```bash
  JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
  ```
- No device was attached during initial setup; `x86_64` is in `abiFilters` so an
  emulator works for testing.
- WSL2 Ubuntu (`wsl -d Ubuntu`, root, 16 cores) is the TDLib build box and has
  all deps installed. `/build/td` holds the checkout.

## Building TDLib

**Always launch it detached.** A plain `wsl -d Ubuntu -- bash ./build-tdlib.sh`
dies the moment the `wsl.exe` client goes away, taking the whole VM with it — it
killed one run at object 372/574. `setsid` reparents the build so it survives:

```bash
wsl -d Ubuntu -- bash -lc 'cd /build && setsid nohup bash ./build-tdlib.sh \
  > "/mnt/d/Work/Github Repo/HardPlay/tools/tdlib-build.log" 2>&1 < /dev/null &'
```

Interruptions are cheap: the script gates each stage on its output artifact and
ninja keeps its object cache, so a rerun skips the SDK fetch and OpenSSL
entirely and resumes mid-compile.

**`setsid` alone is not enough — but the real fix is `.wslconfig`.** The build died
three times (objects 372/574 and 365/574 twice) because WSL2 on Windows 11 shuts
the VM down after `vmIdleTimeout` ms of idle, default **60000** — which matches
the observed teardown exactly. A build launched with `setsid`/`nohup` counts as
idle once its launching `wsl.exe` client exits.

The fix is `%USERPROFILE%\.wslconfig`:

```ini
[wsl2]
vmIdleTimeout=-1
```

Then `wsl --shutdown` for it to take effect. **Verified**: the build now survives
with no client attached at all. Delete the file to restore the default.

The previous workaround was to keep a client attached for the whole build. It
works, but it is fragile in exactly the way that cost those three runs: anything
that kills the watcher kills the VM 60s later, and therefore the build.

```bash
# launch (literal paths only — see the $LOG trap below)
wsl -d Ubuntu -- bash -lc 'cd /build && setsid nohup bash ./build-tdlib.sh \
  > "/mnt/d/Work/Github Repo/HardPlay/tools/tdlib-build.log" 2>&1 < /dev/null &'

# optional progress watcher — informational now, not load-bearing
wsl -d Ubuntu -- bash -lc 'sed "s/\r$//" \
  "/mnt/d/Work/Github Repo/HardPlay/tools/watch-tdlib.sh" > /tmp/watch-tdlib.sh \
  && bash /tmp/watch-tdlib.sh'
```

Three more traps, all of which look like the build silently not starting:

- **`tail -F` does not work on `/mnt/d`.** It fails with `tail: error reading
  '...': No data available`, because drvfs has no inotify — and it takes the
  watcher down with it. `tools/watch-tdlib.sh` polls a byte offset instead; reads
  on drvfs are fine, only notification is missing.
- **`pgrep -f "build-tdlib.sh"` matches the watcher itself.** Its own command
  line contains that string, so an `if ! pgrep …; then launch; fi` guard finds
  "a match", concludes the build is already running, and never launches
  anything. Keep launching and watching in separate commands.
- **`$LOG` gets expanded by the outer shell before `wsl.exe` sees it**, even
  inside single quotes, leaving `> ""`. Use literal paths in anything passed to
  `wsl.exe -- bash -lc`, or put the script in a file.

Confirm with `wsl -l --running` (VM up) *and*
`pgrep -af 'bash ./build-tdlib'` (build actually running) — the log's mtime is
the fastest tell of all.

**Watch the disk.** `C:` had 10.3 GB free at the time of writing and the Ubuntu
VHDX grows on it; the three ABIs need roughly 1.8 GB of objects each. A build that
dies with no error in the log is worth checking `df -h /` inside WSL for.

`tools/build-tdlib.sh` (also copied to `/build`) fetches its own SDK/NDK, builds
OpenSSL then TDLib, verifies 16 KB LOAD alignment, and installs artifacts into
the app module. Deviations from TDLib's stock scripts — NDK 27.1, OpenSSL 3.5.x
instead of EOL 1.1.1w, three ABIs instead of four — are documented in the script
header. Cold run 60–150 min; re-runs are cached. Log: `tools/tdlib-build.log`.

## Credentials

`api_id` is an **int32** — confirmed against the generated bindings, where
`TdApi.SetTdlibParameters.apiId` is `public int`. A value that doesn't fit cannot
be a real one. `app/build.gradle.kts` validates it and falls back to demo mode
with a named warning rather than emitting `BuildConfig.java` that won't compile
(`error: integer number too large`, with no hint as to where the number came from).

The credentials in `local.properties` are **valid** — an 8-digit `api_id` and a 32-hex
`api_hash` — and real streaming works. Two earlier failures are worth remembering, because
both looked like something else:

- A 10-digit value in `apiId` overflows int32 and used to surface as `error: integer number
  too large` in generated `BuildConfig.java`, with nothing pointing at the file that
  produced it.
- A @BotFather token (`8856503031:AAHc…`) split at its colon puts a 10-digit id in `apiId`
  and a 35-character secret in `apiHash`. Both halves look vaguely plausible, and a bot
  account cannot read channel history the way this app needs to. `build.gradle.kts` now
  detects that shape by name rather than reporting two separate format complaints.

## Decisions worth not re-litigating

Recorded because each one has a cheaper-looking alternative that is wrong:

| Area | Decision | Why the obvious thing fails |
|---|---|---|
| FTS | Standalone `media_fts`, rebuilt by `INSERT … SELECT` in `MediaDao.reindex` | An `contentEntity` external-content table syncs captions for free via triggers, but can only mirror columns on the content table — and tags live in a join table, so they could never be indexed |
| Sorting | `ORDER BY CASE :sort WHEN …` ladder | A dynamic `ORDER BY` means `@RawQuery`, which gives up Room's compile-time SQL verification |
| Media keys | Synthetic `localId` PK + unique `(chatId, messageId)` | Telegram message ids are unique per chat, not globally, so multi-channel collides. `localId` also doubles as the FTS `rowid` |
| Tokenising | `[^\p{L}\p{N}\p{M}]` — marks included | Without `\p{M}`, Devanagari `रात` splits at its matra (category Mc) and Indic captions become unsearchable. Caught by a test |
| Video view | `TextureView`, not `SurfaceView` | `SurfaceView` is better for HDR but lives in its own window layer and won't transform with its Compose parent, which breaks the required pinch-zoom and pan |
| Launcher aliases | Enable incoming *before* disabling outgoing | Both disabled for even an instant removes the launcher icon with no way back short of a reinstall |
| Resume writes | Application-scoped coroutine, throttled by distance moved | `viewModelScope` is already cancelled during teardown, dropping the one save that matters; a time-based throttle keeps rewriting the row while paused, invalidating every query watching it |
| Coil | No network component registered at all | Coil ships an OkHttp fetcher by default; leaving it in place means one careless `AsyncImage(model = someUrl)` breaks the PRD §9 guarantee |
| Chat lists | `loadChannels` walks **Main *and* Archive** | Archiving a chat moves it out of `ChatListMain` entirely, so an archived channel vanished from the picker and its next sync reported "not reachable". Archiving is inbox tidiness, not a permission change |
| Poster art | 3-rung fallback: paired still → Telegram thumbnail → `minithumbnail` bytes | Many Telegram videos carry no `thumbnail` file at all but nearly all carry a ~40px inline `minithumbnail`, which needs no network. Without that rung the grid was mostly fallback initials |
| Photo rung | Smallest size ≥ 320px, not the smallest outright | Telegram's ladder starts at a 90px placeholder; using it as poster art is most of why the grid looked cheap |
| Paired stills | Recorded via `posterForMessageId`, folded out of the grid by default | A channel posting "screenshot, then video" indexes two rows per item, so the grid showed everything twice |
| Room views | The migration must `CREATE VIEW` itself, and the SQL constant must carry **no leading or trailing whitespace** | Room's generated `onPostMigrate` is **empty** while `onValidateSchema` *does* check views, so a migration that skips it crashes on launch with "Migration didn't properly handle: library_row". The SQL lives in `LibraryRowSql` so annotation and migration cannot drift — and it is written pre-trimmed because Room *trims* the annotation value when generating the expected `CREATE VIEW … AS <sql>` while the migration stores what it is handed. A constant in the natural triple-quoted shape therefore differs from Room's expectation by a single newline, which shipped once and crashed **every device that had a database to upgrade** while every clean install was fine. `MigrationTest` (instrumented) is the only thing that catches this class of bug |
| Migration testing | Instrumented `MigrationTest` via `MigrationTestHelper`, with `schemas/` wired in as androidTest assets | A clean install creates the newest schema directly and runs **no** migrations, so unit tests, a fresh install and an emulator smoke test can all pass while an upgrade — which is what every existing user gets — dies on launch. `runMigrationsAndValidate` is the check; nothing else compares the migrated database against the exported schema |
| Card shape | `CardAspect`, defaulting to **16:9**, columns derived per-aspect | 2:3 was the wrong default for the content: landscape video in a portrait cell letterboxes to a thin strip and starves the caption. A 16:9 cell needs ~2× the width of a 2:3 cell, so the column rule is per-aspect, not shared |
| Photo rungs | **Two** stored per photo — `thumbnailFileId` (≥320px) and `previewFileId` (≥1024px) — and the Coil keyer *and* fetcher pick between them from `Options.size` | One rung cannot serve both: sized for a 3-column grid it is visibly upscaled in a full-width card, and sized for the card it downloads megabytes per cell. The keyer has to make the same choice as the fetcher or an image is cached under another image's key |
| Photo viewer | `PosterSource.preferOriginal` → `gateway.downloadOriginal` | The viewer used to draw the same `PosterSource` the grid did, i.e. a ~320px thumbnail upscaled to full screen. This was the user's loudest quality complaint and no amount of rung tuning fixes it — the viewer wants the original file |
| Video artwork | Decoded frames in `media.posterPath`; free capture from the player's `TextureView`, plus a bounded `FrameHarvester.sweep()` for artless videos | Telegram gives a video **one** thumbnail (~320px) and no ladder, so there is nothing larger to ask for. Decoding every video would pull gigabytes; the sweep is therefore capped (8/call, 24/process, 6 MB/item, unmetered only) and restricted to videos with no artwork at all |
| `getRemoteFile` type | `fileIdForRemoteId(remoteFileId, kind)` | TDLib validates the reference against the `fileType` argument, so the hardcoded `FileTypeVideo` silently broke lazy repair for every photo |
| Artwork filenames | `<localId>-<millis>.jpg` | `PosterSource.Rung.Local`'s cache key is the path, so a stable filename leaves Coil serving the previous frame from memory for the life of the process |
| Migration v2→v3 | Adds the columns **and rewinds the tail cursor**, and `RootViewModel` resumes an unfinished backfill on launch | New columns are null for existing rows and neither sync direction revisits them — head only looks above the floor, a finished backfill never walks again. Without the rewind *and* something to drive it, the better artwork would never appear for anything already indexed |
| Bottom bar | Drawn iff `HomeTab.forRoute(currentRoute) != null`; tab switches `popUpTo(graph.startDestinationId)` | A list of routes to hide the bar on rots the first time a full-screen destination is added. And `popUpTo` by *route* silently matches nothing, because Library's registered pattern carries its optional `tagId` argument |
| Media lists | One `MediaGrid`, used by Library, Saved, History and search | Four grids were on their way to four sets of padding, four single-column treatments and four shared-element wirings |
| Per-item actions | One `MediaActionsViewModel` per screen + one `MediaActionHost` composable | A ViewModel per card is dozens of database subscriptions for a shut sheet. And the three-dot control shipped drawn-but-inert for a release: one composable that wires the whole feature makes half-wiring it hard |
| Saved / History | Their own pagers, not `pageLibrary` with a flag | Both are ordered by *when you acted* — saved at, last played — so routing them through the shared query leaves them obeying the Library sort control, and changing the grid's sort silently reshuffles Saved |
| FileProvider root | `<files-path path="../no_backup/tdlib-files/">` | FileProvider canonicalises each root, so this resolves to TDLib's real media directory. Relocating `filesDirectory` under `files/` instead would orphan every chunk already downloaded |
| `FLAG_SECURE` | A setting (`blockScreenshots`, default on) rather than a constant | The flag marks the window as protected content and some devices render picture-in-picture black because of it. Silently dropping the flag to make PiP work would trade a privacy promise for a feature without saying so |

## Next session — pick up here

**Read `docs/ui-overhaul-plan.md` first.** It records what the overhaul did, the defects
found on the way, and what is genuinely left. This section is only the summary.

TDLib **1.8.66** is built and installed (OpenSSL 3.5.7, NDK 27.1, three ABIs, 16 KB LOAD
alignment verified). Credentials are valid. `assembleDebug` is clean from a `clean`, with
**zero compiler warnings** and **59 unit tests, 0 failures**.

Landed this round, none of it yet run on hardware:

- **Artwork quality**, the reported defect. Two photo rungs chosen by target size; a
  full-resolution photo viewer; decoded video frames in `media.posterPath` — free from the
  player's own surface, plus a bounded sweep for artless videos. Migration v2 → v3 rewinds
  the tail cursor and `RootViewModel` resumes the backfill, which is what makes the new
  artwork appear for rows already indexed.
- **Bottom navigation** — Library / Discover / Saved / History / Manage, custom bar.
- **Discover** — search plus five recommendation shelves and a tag cloud, all local SQL.
- **The per-card overflow menu**, which had been drawn but inert for a release.
- **Player** — photo viewer, real track selection (no fake quality menu), open-in-another-app
  via FileProvider, PiP, and an options sheet so the chrome is transport and time only.
- **Visual pass** — mastheads in the display face, and three real defects fixed: the unlock
  screen faded its only control to 14% opacity, every switch in Settings was a 40×22dp
  target, and pinch-to-zoom in the player had never fired at all.

Outstanding:

1. **Device testing.** The nav graph, the frame sweep and PiP are what reasoning cannot
   finish.
2. **Swipe-down-to-dismiss** on the photo viewer — deferred, needs a device.
3. **Release build.** R8 is on and `proguard-rules.pro` has never been exercised; TDLib's
   JNI bindings and Room's generated code are where that usually bites. `assembleRelease`
   falls back to the debug key unless `hardplay.keystore.*` is set.
