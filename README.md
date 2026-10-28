# Download Chan

A simple Android download manager that just works. Copy a link, open the app, and
it starts downloading. Share or open a link with Download Chan and it starts too.

Supports YouTube, Instagram, Facebook, TikTok, Douyin, X/Twitter, Vimeo, Reddit
and direct files (MP4, MKV, MP3, ZIP, APK, PDF...). Video downloads as MP4, audio
can be extracted as MP3.

## Download

[Latest APK](https://github.com/nahid77777771-maker/download-chan/releases/latest/download/Download-Chan.apk)
— v2.1.1, debug-signed sideload build. Installs over older builds with data preserved.

## Features

- **Clipboard auto-download** — a copied link is detected on launch/resume and starts
  automatically (can be turned off in Settings, 24 h duplicate guard).
- **Downloads/Download Chan** — saves to the public Downloads folder when Android
  "All files access" is granted; otherwise uses the app-private folder and asks nicely.
- **Multi-thread engine** — 1–16 parallel connections for direct links, ADM/1DM style,
  with automatic single-connection fallback when the server does not support ranges.
- **yt-dlp for social sites** — bundled extractor + ffmpeg, no server needed; engine can
  update itself. Concurrent fragments use the same Connections setting.
- **Cookies import** — import a `cookies.txt` from your browser for private/login-walled
  Facebook and Instagram posts.
- **Share sheet + link opener** — receives `ACTION_SEND` text links and opens http/https
  links directly.
- **Clean 3-tab UI** — Home, Files, Settings. Compact download cards and file rows with
  expandable Details, queue/cancel/retry, copy error details, open/share/delete files.

## Build

```bash
cd "project files/downloadchan"
source env.sh          # JDK 17 + Android SDK
./build-apk.sh         # VERSION -> cap sync -> gradlew assembleDebug -> APK
```

Output: `project files/apk/Download-Chan-v<version>.apk`.
Requires **JDK 17** (Gradle 8.2). ABI filters: `arm64-v8a` + `armeabi-v7a`.

## Layout

```
project files/downloadchan/
├── www/                    ← web UI
│   ├── index.html
│   ├── css/style.css
│   ├── img/logo.png        (generated header mascot)
│   └── js/{app,native}.js
├── android/                ← Capacitor Android + native plugin
│   └── app/src/main/java/com/downloadchan/app/
│       ├── MainActivity.java       (share/view intent receiver)
│       └── DownloaderPlugin.java   (yt-dlp + multi-thread + storage + cookies)
├── env.sh · build-apk.sh · VERSION
```

## Permissions

- `INTERNET` / `ACCESS_NETWORK_STATE` — downloads and engine updates.
- `MANAGE_EXTERNAL_STORAGE` — writes to the public `Download/Download Chan` folder.
  This is a sideload-only permission. If denied, the app still works in its private
  folder (files are removed if you uninstall).

## Notes / limitations

- Download only content you own or have permission to save. Respect each site's terms
  and local copyright law.
- Private/login-walled Facebook and Instagram posts need cookies.txt; public ones can
  work without it. Some sites may still block automated access.
- Multi-thread only applies to direct files whose server supports HTTP Range.
- APKs here are debug-signed. For a release build, create a keystore and a release
  variant.
