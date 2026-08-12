# HardPlay — Product Requirements Document & Build Spec

**Personal Telegram-backed media streaming app (Android, native)**

Version 1.0 | Prepared for Claude Code hand-off

---

## 1. Product Summary

HardPlay is a single-user, sideloaded Android app that turns a private Telegram channel into a personal, professional-grade streaming library. No content leaves Telegram's infrastructure — HardPlay is a client-side viewing layer only. There is no backend server, no hosting cost, and no public exposure of any content or file.

**Core value:** Netflix-style browsing (grid, tags, search, thumbnails) over content that physically lives on Telegram's CDN, streamed on-demand directly to the device.

---

## 2. Goals & Non-Goals

### Goals
- Organize existing Telegram channel content (currently screenshots + full videos with captions) into a browsable, tagged library
- Smooth playback of large HDR video files (300MB+) with full player controls
- Zero recurring cost — no VPS, no cloud storage, no third-party backend
- Zero public exposure — app is sideloaded APK, single Telegram account login, no public endpoints
- Fast local search/filter by tags, date, type

### Non-Goals
- No multi-user support
- No content upload/edit — HardPlay is read-only against the channel
- No iOS version (native Android only, per stack decision)
- No public Play Store listing

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────┐
│              HardPlay (Android APK)          │
│                                               │
│  ┌───────────┐   ┌────────────┐   ┌───────┐  │
│  │  Jetpack  │   │   TDLib     │   │ Room  │  │
│  │  Compose  │◄──┤ (JNI, C++)  │──►│ (SQLite│ │
│  │    UI     │   │  Telegram   │   │  cache)│ │
│  └───────────┘   │   Client    │   └───────┘  │
│        │         └──────┬─────┘               │
│        │                │                      │
│  ┌───────────┐          │                      │
│  │  Media3    │◄────────┘                      │
│  │ (ExoPlayer)│  (chunked file stream)          │
│  └───────────┘                                 │
└────────────────┬──────────────────────────────┘
                  │  MTProto (encrypted, direct)
                  ▼
        ┌───────────────────────┐
        │   Telegram Datacenters │
        │  (your private channel)│
        └───────────────────────┘
```

No app server. No REST API. TDLib inside the app IS the backend — it's Telegram's own client engine, statically linked into HardPlay.

---

## 4. Tech Stack (Final)

| Layer | Choice | Why |
|---|---|---|
| Language | Kotlin | Native reliability, no bridge overhead for heavy media |
| UI | Jetpack Compose | Declarative, modern, fastest to build polished UI |
| Telegram engine | TDLib (official, JNI) | Only officially-supported full-featured Telegram client library; direct channel history read, no file-size caps |
| Video playback | Media3 (ExoPlayer) | Native HDR, adaptive/chunked streaming, hardware decoding, built-in speed/seek APIs |
| Local DB | Room | Metadata cache (tags, captions, message IDs, thumbnails) |
| Image loading | Coil | Thumbnail loading/caching, Compose-native |
| Dependency injection | Hilt | Standard, keeps TDLib client + repositories testable |
| Background sync | WorkManager | Periodic re-scan of channel for new posts |

---

## 5. TDLib Integration Details

### 5.1 Setup
- Obtain `api_id` and `api_hash` from https://my.telegram.org (personal developer credentials — free)
- Build/include TDLib `.so` binaries for `arm64-v8a` and `armeabi-v7a` (prebuilt binaries available; building from source via NDK is fallback if prebuilt unavailable for target API level)
- Initialize `Client` via JNI, authenticate once using your own phone number (standard Telegram login flow: number → OTP → optional 2FA password)
- Session persists locally (TDLib handles encrypted session storage) — no need to re-login every launch

### 5.2 Channel Indexing
- On first run: call `getChatHistory` / `searchChatMessages` iteratively (paginated, 100 messages per call) against your private channel's chat_id
- For each message extract:
  - `message_id`
  - `date`
  - `caption` (text)
  - `content type` (photo / video)
  - `media file_id` and `remote file reference`
  - Auto-generate a `thumbnail file_id` (Telegram provides low-res thumbnail for videos/photos natively — no extra processing needed)
- Store all of the above in Room. **Media itself is never downloaded during indexing** — only metadata + thumbnails (thumbnails are tiny, cache locally without issue).

### 5.3 Caption → Tag Parsing
- Simple rule-based parser first (split on common delimiters, hashtags if you use them, keywords)
- If your captions don't already follow a pattern, HardPlay ships with a **manual tag editor** in the UI — tap any item, add/edit tags, saved to Room instantly
- Optional future upgrade: on-device or Claude API caption parsing for smarter auto-tagging (kept out of v1 to avoid adding a network dependency)

### 5.4 Streaming Playback
- TDLib exposes `downloadFile` with `offset` + `limit` + `synchronous=false`, enabling **progressive chunked download** — exactly how the official Telegram app streams video without downloading the whole file first
- HardPlay wraps this in a custom `DataSource` for Media3/ExoPlayer, so ExoPlayer requests byte ranges and TDLib fills them from Telegram's CDN on demand
- Downloaded chunks are cached in TDLib's own file storage automatically (scrubbing backward doesn't re-download already-fetched parts)
- Cache size is configurable (TDLib setting) — recommend capping at a few GB with LRU eviction so phone storage doesn't fill up over time

---

## 6. UI/UX Spec

### 6.1 Design Direction
- Dark theme by default (streaming-app convention, also better for OLED battery + HDR content viewing)
- Poster-grid home screen, not a plain list — big thumbnails, minimal text, feels like a real streaming service
- Accent color: pick one strong accent (e.g., deep amber or electric teal) against near-black background — avoid generic "Netflix red" or "Spotify green" clichés per your anti-generic design preference
- Typography: one clean geometric sans (e.g., Inter or Manrope) — confident, no default system font look

### 6.2 Screens

**1. Home / Library Grid**
- Poster-style grid (2–3 columns depending on screen width), thumbnail + duration badge + type icon (photo/video)
- Sticky top bar: search icon, filter/tag icon, sort (newest/oldest/most-tagged)
- Pull-to-refresh triggers incremental re-sync (only fetch messages newer than last synced message_id — fast, not a full re-scan)

**2. Tag/Filter Sheet**
- Bottom sheet, chip-style tag list (multi-select)
- Live count next to each tag
- Tags are fully custom — created inline as you tag items, no fixed taxonomy

**3. Search**
- Debounced local search across captions + tags (Room FTS — full-text search, instant, no network call)

**4. Detail/Player Screen**
- Full-bleed video player (Media3 `PlayerView` wrapped in Compose)
- Controls overlay (auto-hide after 3s, tap to reveal):
  - Play/pause (center, large tap target)
  - Scrubber/seek bar with live thumbnail preview (Media3 supports this via `TrickPlay`/preview frames if you want it — optional polish item)
  - Double-tap left/right zones = -10s / +10s skip, with a small ripple animation for feedback
  - Speed control: tap icon → chip selector (0.5x / 0.75x / 1x / 1.25x / 1.5x / 2x)
  - Pinch-to-zoom / pan on video surface via `InteractiveViewer`-equivalent (Compose `Modifier.pointerInput` with `detectTransformGestures`) — lets you crop into frame if needed
  - Fullscreen/orientation lock toggle
  - Caption text + tags shown below player (collapsible)
- Buffering indicator: subtle, centered, matches app's dark aesthetic (no default spinning circle look — custom animated mark)

**5. Item Tag Editor**
- Simple modal, add/remove tags, autocomplete against existing tag list to avoid duplicate near-identical tags

### 6.3 Motion/Polish Details (for "doesn't feel embedded" feel)
- Shared-element transition: tapping a grid thumbnail should visually expand into the player screen (Compose `SharedTransitionLayout`), not a hard cut — this single detail is what makes it feel like a real app rather than a wrapper
- Skeleton loading shimmer for grid while thumbnails load, not blank white/black flashes
- Haptic feedback on skip/tag actions (short tick)

---

## 7. Data Model (Room)

```kotlin
@Entity
data class MediaItem(
    @PrimaryKey val messageId: Long,
    val chatId: Long,
    val type: String,          // "video" | "photo"
    val caption: String,
    val date: Long,
    val durationSeconds: Int?, // null for photos
    val thumbnailFileId: Int,
    val remoteFileId: String,  // TDLib remote reference for streaming
    val fileSizeBytes: Long,
    val lastSyncedAt: Long
)

@Entity
data class Tag(
    @PrimaryKey(autoGenerate = true) val id: Int,
    val name: String
)

@Entity(primaryKeys = ["messageId", "tagId"])
data class MediaTagCrossRef(
    val messageId: Long,
    val tagId: Int
)
```

---

## 8. Sync Strategy

- **Initial sync**: full channel history scan, paginated, runs once with a progress indicator ("Indexing 240 / 1,800 items…")
- **Incremental sync**: on app open + manual pull-to-refresh, fetch only messages after the last stored `message_id` — near-instant
- **Background sync**: optional WorkManager job every few hours to keep library current without opening the app
- All sync is read-only against the channel — HardPlay never posts, edits, or deletes anything in Telegram

---

## 9. Security & Privacy

- No public network endpoints of any kind — app talks only to Telegram's MTProto servers, same as the official Telegram app
- TDLib session encrypted at rest (handled by TDLib itself, same mechanism the real Telegram app uses)
- APK is sideloaded, not published — no Play Store review exposure, no public listing
- Recommend enabling Android's built-in app-lock (biometric/PIN via `BiometricPrompt`) on HardPlay's launch, since there's no separate HardPlay login — an extra local gate in case the phone is unlocked/handed to someone
- No analytics, no crash reporting SDK, no third-party network calls of any kind — keeps the "nothing leaves the device except to Telegram" guarantee airtight

---

## 10. Build Phases (for Claude Code hand-off)

**Phase 1 — TDLib Foundation**
- Project setup, Gradle config, TDLib `.so` integration, JNI bridge
- Phone number login flow, session persistence
- Fetch and log raw channel message list (console/logcat only, no UI yet)
- *Exit criteria: can authenticate and print channel history to logs*

**Phase 2 — Data Layer**
- Room schema + DAOs
- Full channel indexing pipeline (metadata + thumbnails, no media download)
- Incremental sync logic
- *Exit criteria: library fully indexed in local DB, visible via simple debug list*

**Phase 3 — Core UI**
- Home grid, search, tag filter sheet, tag editor
- Dark theme, typography, base design system (colors, spacing, components)
- *Exit criteria: browsable, taggable, searchable library — no playback yet*

**Phase 4 — Playback Engine**
- Media3 + custom TDLib-backed `DataSource` for chunked streaming
- Player screen with full controls (seek, speed, zoom, skip)
- *Exit criteria: smooth playback of a 300MB+ HDR file with all controls working*

**Phase 5 — Polish**
- Shared-element transitions, shimmer loading, haptics, buffering UI
- Biometric app-lock
- Background sync via WorkManager
- *Exit criteria: feels like a finished product end-to-end*

---

## 11. Open Decisions (confirm before build starts)

1. Auto-tagging from captions: rule-based only for v1, or worth adding manual-only tagging first and revisit auto-parsing later?
2. Cache size cap for downloaded video chunks (recommend 3–5GB depending on your phone's storage)
3. Accent color / exact visual identity — want a quick moodboard pass before Phase 3, or should Claude Code pick within the dark-theme direction above?

---

## 12. Estimated Effort

| Phase | Estimate |
|---|---|
| 1 — TDLib Foundation | 3–4 days |
| 2 — Data Layer | 2–3 days |
| 3 — Core UI | 4–5 days |
| 4 — Playback Engine | 3–4 days |
| 5 — Polish | 2–3 days |
| **Total** | **~2.5–3 weeks** |
