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
`tools/build-tdlib.sh` (also copied to `/build`) fetches its own SDK/NDK, builds
OpenSSL then TDLib, verifies 16 KB LOAD alignment, and installs artifacts into
the app module. Deviations from TDLib's stock scripts — NDK 27.1, OpenSSL 3.5.x
instead of EOL 1.1.1w, three ABIs instead of four — are documented in the script
header. Cold run 60–150 min; re-runs are cached. Log: `tools/tdlib-build.log`.

## Build order remaining

3. Design system (tokens, fonts, grain, poster card, chips, sheets, buffering mark, shimmer)
4. Data layer (Room entities + FTS4 over caption/tags, DAOs, sync state, paging repo)
5. `TelegramGateway` + auth state machine + channel enumeration + indexing + `DemoTelegramGateway`
6. UI (biometric gate, login, channel picker, poster grid, search, tag filter sheet, tag editor)
7. Playback (TDLib-backed Media3 `DataSource`, player screen, speed/skip/zoom/fullscreen)
8. Polish (shared transitions, haptics, WorkManager sync, `FLAG_SECURE`, verify `assembleDebug`)
