# TorrentPro — Android BitTorrent Client & Video Streamer

**TorrentPro** is a modern, lightweight, battery-efficient BitTorrent client and streaming video player for Android. Built with **Kotlin**, **Jetpack Compose (Material3)**, **libtorrent4j (Rasterbar libtorrent 2.x)**, and **AndroidX Media3 (ExoPlayer)**.

---

## What's New in v1.1.0

### 1. Robust Video Streaming & Player Controls
- **Fix for Stream Video Crash**: Debounced navigation, guarded local HTTP range server, and lifecycle-safe Media3 ExoPlayer lifecycle prevents crashes.
- **Double-Tap Seek**: Double-tap left side to rewind 10s (`-10s`), double-tap right side to fast-forward 10s (`+10s`) with animated visual feedback.
- **Swipe Gestures**:
  - Vertical swipe on the left half adjusts screen brightness (0–100%) with on-screen HUD.
  - Vertical swipe on the right half adjusts media volume (0–100%) with on-screen HUD.
- **Subtitle & Audio Track Selection**: Quick dialogs to inspect and switch between embedded subtitle and audio languages or turn subtitles off.
- **Playback Speed Selector**: 0.5x, 0.75x, 1.0x, 1.25x, 1.5x, 2.0x.
- **Audio Focus & Noisy Audio**: Automatic pause when headphones are disconnected (`ACTION_AUDIO_BECOMING_NOISY`) or phone calls arrive.
- **Orientation Toggle**: Smooth rotation between portrait and landscape.
- **External Player Support**: Stream into VLC, MX Player, or Just Player via `FileProvider` without `FileUriExposedException`.

### 2. Individual Destination per Download & Transparent Path Display
- **Per-Download Destination**: Choose a custom destination folder for any download without altering the global default.
- **Human-Readable Storage Display**: Displays friendly paths such as `Internal Storage > Download > TorrentPro` or `SD Card > Movies` instead of raw `content://` URIs or fake filesystem paths.
- **Persistable SAF Permissions**: Uses `ContentResolver.takePersistableUriPermission()` so folder access persists across app restarts and device reboots.

### 3. Investigation & Fix for External File Manager Deletion Issue
- **Root Cause Identified**: Previous versions defaulted to app-specific external storage (`context.getExternalFilesDir()`), placing files in `Android/data/com.torrentpro.app/files/`. Android 11+ Scoped Storage strictly blocks third-party File Managers from accessing or deleting files inside `Android/data/`. In addition, active disk file descriptors in the libtorrent engine remained open while paused.
- **Fix Applied**: 
  1. Default destination moved to public storage: `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)/TorrentPro`.
  2. Libtorrent disk cache is explicitly flushed with `saveResumeData(TorrentHandle.FLUSH_DISK_CACHE)` on pause and interruption, releasing active file handles.
  3. External file managers can now freely view, manage, and delete interrupted and completed files.
  4. If a partial file is deleted externally, TorrentPro detects it gracefully, updating the UI to *"Partial file removed externally"* with a *"Restart Download"* button instead of crashing.

### 4. Customizable Connection & Network Settings
- Configurable listen port (1–65535 with validation).
- Global max connections (10–200) and per-torrent max connections (5–100).
- Download & upload rate limiters (KB/s).
- DHT bootstrap node customization.
- **Test Connection** tool: Non-blocking socket verification to check network reachability to bootstrap nodes.

---

## APK Location

* [TorrentPro.apk](file:///c:/Users/p.k.palanisamy/OneDrive%20-%20Accenture/study/practice/VibeCoding-PrototypeApp/TorrentPro.apk) (Signed Release APK)
* Also available on GitHub: [https://github.com/pravinkumar2k02/TorrentPro](https://github.com/pravinkumar2k02/TorrentPro)
