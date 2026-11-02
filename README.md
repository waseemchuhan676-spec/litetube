# LiteTube — ultra-lightweight YouTube client for phones and low-spec Android TV boxes

100% Kotlin, no WebView, no server. Search and stream resolution happen
client-side via NewPipeExtractor; playback is AndroidX Media3 (ExoPlayer)
with hardware MediaCodec rendering and small buffers tuned for 1–2 GB RAM
devices. Downloads go to the **public** media library (Movies / Music), so
files appear immediately in the Gallery, VLC and other players.

## Updates (wired in 2026-11-02, user-approved)

The in-app updater is back via the separate update hub
(`../litetube-update-hub`, GUIDE.md): `LiteTubeApp` (Application scope)
runs `UpdateManager`, which checks the hub's `update.json`, downloads a
newer APK over DownloadManager, verifies its SHA-256 and opens the system
installer (one tap, never silent). `MainActivity.onResume` installs an
already-downloaded APK after the user returns from the "Install unknown
apps" settings screen. Before release, set the real Vercel domain in
`LiteTubeApp.updateJsonUrl` — the placeholder serves nothing.

When YouTube changes break extraction, the fix is still a one-line
extractor bump (see below); the updater then ships that build to users.

## Project structure

```
settings.gradle.kts / build.gradle.kts / gradle.properties
app/
  build.gradle.kts            # minSdk 21, targetSdk 34, Java 17, desugaring
  src/main/AndroidManifest.xml
  src/main/java/com/litetube/app/
    MainActivity.kt           # search -> play -> download orchestration
    YoutubeRepository.kt      # NewPipeExtractor search + stream info
    OkHttpDownloader.kt       # extractor's HTTP layer (OkHttp)
    PlayerManager.kt          # Media3 wrapper, low-RAM buffers
    GalleryDownloader.kt      # public-storage downloads (see below)
    QualitySelector.kt        # stream picking + file-name safety
    SearchResultsAdapter.kt   # D-Pad-friendly results list
  src/main/res/
    layout/activity_main.xml  # search, results, PlayerView, quality, download
    layout/player_controls.xml
    layout/item_result.xml
    drawable/focus_border.xml # yellow focus border for TV remotes
    drawable/tv_banner.xml    # placeholder banner (see limitations)
    values/strings.xml, colors.xml, themes.xml
    mipmap-anydpi-v26/        # adaptive launcher icon
```

## How to build

1. Open this folder in Android Studio (Hedgehog or newer, JDK 17).
2. Let Gradle sync. If `gradlew` is missing, generate it once with a local
   Gradle 8.7 install: `gradle wrapper --gradle-version 8.7`.
3. Run on a phone or TV emulator, or install on a TV box:
   `adb connect <tv-ip>:5555` then `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Downloads: how they actually work

- **Android 10+ (scoped storage):** MediaStore insert with `RELATIVE_PATH`
  (`Movies/LiteTube` for video, `Music/LiteTube` for audio) and the
  `IS_PENDING` flag, streamed through OkHttp. The file becomes visible the
  moment `IS_PENDING` clears. DownloadManager is **not** used here because
  it cannot target a MediaStore Uri.
- **Android 9 and below:** DownloadManager into the public Movies/Music
  directory, then `MediaScannerConnection.scanFile()` on completion.
  This path needs the runtime `WRITE_EXTERNAL_STORAGE` permission, which
  the app requests on those versions only.

## Quality options, honestly

- **1080p / 720p / 480p:** the app downloads the best **progressive**
  (combined audio+video) stream at or below the requested height and shows
  the **actual** quality in the UI. YouTube usually only ships progressive
  streams up to 720p; 1080p is typically DASH-only (separate video/audio),
  which a single-file download cannot reproduce — so a 1080p request that
  only has 720p progressive will download 720p and say so, rather than
  silently saving a silent video-only file.
- **Audio only:** downloads the best audio stream as-is (usually M4A or
  Opus). It is **not** transcoded to MP3 — transcoding needs an ffmpeg
  stack that would destroy the ultra-lightweight goal.
- **Playback** is different from download: if no progressive stream exists,
  the player falls back to the DASH manifest URL, which Media3 plays
  natively with audio.

## TV remote navigation

Only actionable controls are focusable (search field, search button, result
rows, quality spinner, download button). PlayerView and text views are
`focusable="false"`, and `nextFocusDown/Up/Left/Right` chains the order
top-to-bottom, so focus can never get trapped. Focused controls get a
bright yellow border (`focus_border.xml`).

## Maintenance: the one thing that breaks

NewPipeExtractor breaks whenever YouTube changes its internals. When search
or playback stops working, bump the pinned version in
`app/build.gradle.kts` (`com.github.TeamNewPipe:NewPipeExtractor:v0.26.0`)
to the latest release tag and rebuild. That is the only maintenance this
app needs.

## Known limitations (v1)

- Search returns the first page of results only (no pagination yet).
- No thumbnails in results — skipped on purpose to keep RAM low; add an
  image loader later if you want them.
- `tv_banner.xml` is a shape placeholder; replace it with a real 320×180
  PNG banner before any store-style release.
- `minifyEnabled` is off: R8 shrinking is unproven against the extractor's
  reflective parsing, so it stays off until a release-test pass proves a
  keep-rules set.
- This app is distributed by sideloading, not through Google Play. Use the
  downloader for content you have the rights to (your own uploads,
  Creative Commons, public domain).
