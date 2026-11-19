# VOXORA Media Player

**Play Beyond Language** — A modern, dark-themed native Android video player built with Kotlin, Jetpack Compose, Material 3, AndroidX Media3 ExoPlayer, Room, and DataStore.

## Highlights
- **Local Video Discovery**: Automatic MediaStore scanning with folder grouping, search, sorting (date, name, duration, size), and Storage Access Framework (SAF) file picker support.
- **Pro-Grade Full-Screen Player**: Play/pause, double-tap seek, horizontal scrub preview, brightness & volume swipe gestures, screen lock, orientation toggle, aspect ratio modes (`Fit`, `Fill`, `Zoom`, `16:9`, `4:3`, `Stretch`), playback speed (`0.25x–3.0x`), and audio track selection.
- **Subtitle Engine**: Embedded subtitles plus external `.SRT` and `.VTT` loading with text size, vertical position, background opacity, and millisecond sync adjustment.
- **Playback History & Continue Watching**: Local Room database tracking recently played videos, resume positions, preferred speeds, and attached external subtitles.
- **Player Battery Saver**: Monitors real-time battery level, charging state, and Android Battery Saver to cap display refresh rate to `60Hz`, throttle UI polling, and disable non-essential player animations.
- **In-App GitHub Releases Updater**: Automatically checks `stupidboytanvir-droid/Voxora` releases, posts an Android update notification when a newer version arrives, and lets users download and install the new APK directly inside the app.
- **AI Dubbing Studio (Phase 1 — English → বাংলা)**: In-player studio featuring English-to-Bengali configuration, live original/dubbed volume mixer, millisecond sync controls, Male & Female multi-character Bengali voice profiles, on-device `MediaExtractor` audio track inspection/extraction, and a zero-cost 7-stage offline pipeline architecture.

## Building Locally
```bash
gradle :app:assembleDebug
```
The built APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
