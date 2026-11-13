# TorrentPro — Android BitTorrent Client & Video Streamer

**TorrentPro** is a modern, lightweight, battery-efficient BitTorrent client for Android inspired by BitTorrent Pro. Powered by **libtorrent4j** (Rasterbar libtorrent 2.x) and **AndroidX Media3 (ExoPlayer)**, it offers high-speed downloads, sequential video streaming while downloading, and automatic thermal/battery optimization.

---

## Key Features

1. **Magnet & .torrent Support**:
   - Paste magnet links (`magnet:?xt=...`) with clipboard auto-detection.
   - Open and parse local `.torrent` files via system document picker.
   - Android Deep Linking: Tapping magnet links or `.torrent` files in browsers or messengers automatically launches TorrentPro.

2. **Auto-Stop Seeding & Auto-Remove on Completion**:
   - When a download reaches 100%, upload bandwidth is immediately cut to 0 and seeding stops.
   - Finished torrents are automatically removed from the active downloading queue and placed into the **Completed** history tab.
   - High-priority notification alerts the user: *"Download Complete: [Name] has finished downloading. Seeding stopped."*

3. **Stream Videos While Downloading**:
   - Detects video media (`.mp4`, `.mkv`, `.avi`, `.webm`, `.mov`, `.ts`, etc.).
   - Enables **Sequential Download Mode** (`TorrentFlags.SEQUENTIAL_DOWNLOAD`) and prioritizes container header & index pieces.
   - Embedded lightweight local HTTP streaming proxy (`127.0.0.1:8828`) supporting HTTP Range requests.
   - Built-in in-app video player powered by **AndroidX Media3 (ExoPlayer)** with 10s rewind/forward, timeline scrubber, and auto-buffering.
   - "Play with External Player" button to stream directly into **VLC**, **MX Player**, or **Just Player**.

4. **Lightweight & Thermal Protection (No Mobile Heating)**:
   - Conservative connection limits (default 60 global max, 25 per torrent) prevents mobile radio thrashing and thermal throttling.
   - Background WakeLock is acquired **only** while active downloads are running, and immediately released when downloads pause or complete, allowing the device to enter Android deep sleep (Doze).
   - Zero background seeding prevents continuous battery and cellular data drain.
   - Polling loop throttled to 1000ms.

5. **Download Location Picker**:
   - Select custom download folders using Android's **Storage Access Framework (SAF)**.
   - Default path: `/storage/emulated/0/Download/TorrentPro`.

6. **Pause & Resume Controls**:
   - Per-torrent Pause and Resume buttons.
   - Global **Pause All** and **Resume All** in the top bar and ongoing notification.

---

## APK Location

The compiled and signed APK is ready to install:
* [TorrentPro.apk](file:///c:/Users/p.k.palanisamy/OneDrive%20-%20Accenture/study/practice/VibeCoding-PrototypeApp/TorrentPro.apk)

### How to Install on Your Android Mobile:
1. Connect your phone via USB or send `TorrentPro.apk` to your phone (via Google Drive, WhatsApp, Quick Share, or ADB).
2. On your phone, tap the APK to install.
3. If prompted, enable **"Install unknown apps"** for your file manager.
4. Launch **TorrentPro** and start downloading!
