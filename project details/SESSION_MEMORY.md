# Download Chan — Session Memory (resume playbook)
> Read this FIRST in any new session. Update at the END of every session.

**Last dump:** Session 11 · v2.1.1 (pushed + released)
**Next build will be:** v2.1.2 (`VERSION` currently 2.1.2)

---

## 1. Identity
- **Project:** Download Chan — Android download manager. Copy/share/open any link
  (YouTube, Instagram, Facebook, TikTok, Douyin, direct files…) and save it.
- **Stack:** Capacitor 6 (vanilla HTML/CSS/JS in `www/`) + native Java bridge.
  - yt-dlp bundled for social/video sites (`youtubedl-android 0.18.1` + ffmpeg).
  - New native segmented HTTP engine for direct files (ADM/1DM-style multi-thread).
- **Package:** `com.downloadchan.app` (unchanged, so v2.1.1 installs over older builds).
- **App name:** `Download Chan` (space). **Version:** 2.1.1 (versionCode 20101).
- **Repo:** https://github.com/nahid77777771-maker/download-chan (main; v2.1.1 pushed
  2026-10-28 at `9b5a50c`). APKs are gitignored locally but v2.1.1 is attached to the
  GitHub release as `Download-Chan.apk`:
  https://github.com/nahid77777771-maker/download-chan/releases/tag/v2.1.1
- Language: English prompts/replies.

## 2. Folder layout
```
~/Downloads/download-chan/
├── .gitignore
├── README.md
├── project details/
│   ├── CHANGE_HISTORY.md      ← all prompts verbatim + changes (Session 10 = v2.1.1)
│   ├── DEBUG_DUMP.md          ← deep v1.5 debug notes (still useful)
│   ├── icon/                  ← chubby cat SVG masters + gen.sh (icons/web/splash)
│   └── SESSION_MEMORY.md      ← THIS FILE
└── project files/
    ├── apk/                   ← DELIVERABLES (latest = v2.1.1)
    └── downloadchan/          ← app source (@WORKING dir)
        ├── VERSION            currently 2.1.2 (next build)
        ├── env.sh · build-apk.sh
        ├── www/               ← UI
        └── android/           ← Capacitor Android + native plugin
```
Build dir: `~/Downloads/download-chan/"project files"/downloadchan` (quote the space).

## 3. Build workflow (mandatory)
```bash
cd ~/Downloads/download-chan/"project files"/downloadchan
source env.sh        # JDK 17 + ANDROID_HOME=~/Android/Sdk
./build-apk.sh       # VERSION -> versionCode/Name -> cap sync -> gradlew
                     #   assembleDebug -> copy APK -> bump VERSION
```
- Output: `project files/apk/Download-Chan-v<ver>.apk`.
- **JDK 17 required** (system default is newer → Gradle 8.2 fails).
- Incremental build ~11–30 s. ABI filters: arm64-v8a + armeabi-v7a.
- `build-apk.sh 2.1.1` builds v2.1.1 (versionCode 20101) and sets VERSION to 2.1.2.

## 4. Current v2.1 architecture
### Web layer (`www/`)
- `index.html` — 3 tabs: **Home / Files / Settings**. Neutral dark UI, indigo accent.
- `css/style.css` — clean compact design, full-width bottom nav, switches, stepper.
- Progressive disclosure:
  - job summaries show state/progress; expandable Details hold Link/Type/Quality/
    Connections/Save to/Size/Status (+ full error and Copy error on failures);
  - file rows expand to Path/Type/Size/Saved + Share/Delete;
  - Settings keeps Advanced fallback/resolver collapsed.
- Only the active job card has a Cancel button (no duplicate header action).
- `js/native.js` — `window.DC` wrappers:
  `getClipboard`, `downloadAuto`, `storageStatus`, `requestStorage`, `openFolder`,
  `cookiesStatus`, `pickCookies`, `clearCookies`, old file/engine methods.
- `js/app.js` — UI logic:
  - clipboard read on boot + **every resume**; auto-download if setting ON and URL not
    auto-downloaded in the last **24 h** (`dc_last_auto_url` / `dc_last_auto_at`).
  - share + http/https VIEW links → `handleShared` → starts immediately.
  - queue is sequential (one active yt-dlp/direct job at a time), progress + speed + ETA.
  - Settings persisted in `localStorage` key `dc_settings`
    (`mode, quality, threads, autoClipboard, onlineFallback, resolver`).
  - first-run storage sheet; banner while saving private.
  - cookies import/remove; engine auto-update (max once/24 h) + manual button.
  - errors stay as a "Last download" card with Retry / Dismiss / Copy details.

### Native layer (`android/app/src/main/java/com/downloadchan/app/`)
- `MainActivity.java` — registers plugin; handles `ACTION_SEND`, `ACTION_VIEW`,
  ClipData fallback; `setPendingShare` + live `sharedUrl` event.
- `DownloaderPlugin.java` — `@CapacitorPlugin(name="Downloader")`:
  - **Folders:** public `Environment.getExternalStoragePublicDirectory(DOWNLOADS)/Download Chan`
    when `Environment.isExternalStorageManager()` (All files access) is true; else
    `getExternalFilesDir(DOWNLOADS)/Download Chan`. Also scans old private `Download-Chan`.
  - **Engine route (`downloadAuto`):** known social hosts (YouTube/IG/FB/TikTok/etc.) →
    yt-dlp. Other URLs are probed (`Range: bytes=0-0`): direct media → native multi-thread.
    Direct attempts fall back to yt-dlp on failure; yt-dlp failures append hints.
  - **Direct engine:** probe size/content-type/range; split into 1–16 chunks, each thread
    writes its byte range into a `.part` RandomAccessFile; progress/bytes/speed events;
    Range unsupported → single stream; unique filenames; public/private folder support.
  - **yt-dlp:** unique `.job_<id>/` output dir, then moves the produced file to the real
    folder (prevents picking an old file). Adds
    `--concurrent-fragments <threads>`, cookies, mp4/m4a preference, retry with
    `--force-ipv4` + `android,default` client.
  - **FFmpeg:** `ensureInit()` calls `YoutubeDL.init()` then `FFmpeg.getInstance().init()`
    (REQUIRED for merging split video+audio and for MP3 extraction). v2.0.1 fixed this.
  - **Cookies:** `pickCookies` uses SAF (`ACTION_OPEN_DOCUMENT` via `@ActivityCallback`),
    copies ≤8 MB to `filesDir/cookies.txt`; `addCookies()` passes `--cookies`.
  - **Storage:** `storageStatus`, `requestStorage` (opens All-files-access settings),
    `openFolder` (DocumentsUI).
  - **Clipboard:** `getClipboard` reads `ClipboardManager` on the UI thread.
  - Lists/deletes/opens/shares files from public + private + legacy roots.
- Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, **`MANAGE_EXTERNAL_STORAGE`**;
  `extractNativeLibs="true"`; SEND text/plain + VIEW http/https; FileProvider.
- `build.gradle`: ABI filters + `packagingOptions { jniLibs { useLegacyPackaging = true } }`
  (both required by youtubedl-android).

## 5. Deliverables
| Version | What | Size |
|---|---|---|
| v1.0 | Initial app (all ABIs) — broken init | 214 MB |
| v1.1 | ABI filter — broken init | 109 MB |
| v1.2 | Fix init, green-hair chibi icon/splash | 108 MB |
| v1.3 | YouTube 403 hardening, persistent error box | 108 MB |
| v1.4 | Online resolver fallback (gendownload) | 108 MB |
| v1.5 | yt-dlp engine auto-update, login-wall hint | 108 MB |
| v2.0 | From-scratch rebuild: clipboard auto-start, public `Downloads/Download Chan`, multi-thread direct engine, cookies import, modern 3-tab UI | 104 MB |
| v2.0.1 | Facebook/social audio fix — bundled FFmpeg is initialized, so yt-dlp merges video+audio | 104 MB |
| v2.1.0 | Clean UI refresh + progressive Details levels; audio fix retained | 104 MB |
| v2.1.1 | **Chubby cat mascot logo + new splash/header mark; no anime/human** ← LATEST | 104 MB |

- v2.1.1 APK: `project files/apk/Download-Chan-v2.1.1.apk` ← INSTALL THIS
  - GitHub release v2.1.1: https://github.com/nahid77777771-maker/download-chan/releases/tag/v2.1.1
    (stable asset `Download-Chan.apk`; latest: `/releases/latest/download/Download-Chan.apk`).
  - bytes `109138901`; SHA-256 `a897c628c627fb117e2ae7267f9224f9054e8b5a5381701eab388a91e622eebf`.
  - Debug-signed (same debug keystore) → installs over v2.1.0/v2.0.1/v2.0/v1.5,
    data preserved. No Android release keystore exists yet.
  - Logo/header checked in headless Chromium at 360×800; not device-tested yet.
- v2.1.0 APK kept at `project files/apk/Download-Chan-v2.1.0.apk` (UI refresh; user-confirmed).
- v2.0.1 APK kept at `project files/apk/Download-Chan-v2.0.1.apk` (previous audio fix).
- v2.0 APK kept at `project files/apk/Download-Chan-v2.0.apk` (no-audio bug for split
  audio/video sites).

## 6. Gotchas / decisions
- **All files access:** public Downloads requires the sideload-only
  `MANAGE_EXTERNAL_STORAGE` + user toggle (Settings → Apps → Special access → All files
  access → Download Chan). Without it, the app silently uses the private folder and the
  UI shows a banner. Play Store would reject this permission (we are sideload-only).
- **Clipboard:** Android 10+ only returns clipboard while the app has focus; the bridge
  reads on launch/resume. Auto-download dedupes the same URL for 24 h. Manual Download
  always works; Settings has an off switch.
- **Private FB/IG:** public posts may work via yt-dlp + engine update; login-walled ones
  need **cookies.txt** (Settings → Import). Gendownload resolver returned 422 on the old
  FB issue, so it is not a reliable fix (now Advanced + OFF by default).
- **Multi-thread:** only direct HTTP(S) files with server Range support. HLS/DASH/social
  links use yt-dlp fragments (`--concurrent-fragments`). Servers that ignore Range fall
  back to one connection.
- **"failed to initialize"** = youtubedl-android native libs not extracted. Keep
  `android:extractNativeLibs="true"` AND `useLegacyPackaging = true`.
- **YouTube 403 after extraction** = anti-bot/PO-token; mitigations: mp4/m4a selectors,
  `player_client=default`, retry `--force-ipv4` + `android,default`. Cookies/VPN may be
  needed. Don't set `player_client` blindly.
- **No audio in a video:** the bundled FFmpeg was not initialized in v2.0/v1.5, so yt-dlp
  could not merge separate video+audio streams and saved the video-only file. v2.0.1
  initializes FFmpeg; re-download old no-audio files. If a future build removes this call,
  the bug returns for Facebook Reels, YouTube, TikTok, etc.
- **First use delay:** FFmpeg extraction (~78 MB of libs) happens on first engine init in
  the new version; a few seconds is normal, then it is cached in `noBackupFilesDir`.
- **Old files:** v1.5 saved in app-private `Download-Chan`; v2.0 lists that legacy folder
  too. New downloads go to `Download Chan` (space) in public/private.
- **Public file sharing:** FileProvider has `external-path` root + `external-files-path`,
  so public and private files both open/share.
- **UI testing:** no adb device/emulator. Headless preview uses a temporary copy at
  `/tmp/opencode/dcprev` with `window.__dcPreview`; never add those hooks to `www/`.
  Screenshot drivers live in `/tmp/opencode/`. Native bars are `#0A0B0F` in `styles.xml`.
- **Logo/splash:** source of truth is `project details/icon/{art.svg,bg.svg}`. Run
  `bash project details/icon/gen.sh` to regenerate every mipmap, `www/img/logo.png`,
  the adaptive background gradient, and all portrait/landscape splash sizes. Keep it a
  cute non-human mascot (chubby cat), no anime/human art. The splash uses `art.png`
  transparent; the header uses `square.png`.
- **JDK 17**; quote `project files`; `git clone/ls-remote` hangs on this host (use
  codeload/raw); `git push` slow. GitHub token in keyring was invalid previously.
- Native plugin is registered manually (no npm package).
- **See `DEBUG_DUMP.md`** for v1.5 experiments/repro commands.
- `CHANGE_HISTORY.md` Sessions 6–11 have the verbatim prompts + full change lists.

## 7. Typical next moves
1. **Install the v2.1.1 release** over v2.1.0 and confirm the new chubby-cat launcher
   icon, header mark, and splash. If the launcher caches the old icon, restart it.
2. Fix anything reported; likely areas: Android 11 clipboard timing, All-files-access
   UX, OEM DocumentsUI, direct-range servers, YouTube 403.
3. Test cookies.txt with a login-walled FB/IG link and update the error hints.
4. Optional: background service + notification, resume support, thumbnail preview,
   arm64-only smaller APK, release keystore, GitHub release (only if asked).
5. Next build is v2.1.2; update `PROJECT_DATA.txt` / AGENTS when it ships.
