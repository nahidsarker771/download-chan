# Download Chan — v1.5 DEBUG DUMP (historical)
> **Historical deep dive from Session 5 (v1.5, 2026-10-14).** For current v2.1 status and
> architecture, read `SESSION_MEMORY.md` first. Cookies, public `Downloads/Download Chan`,
> the multi-thread direct engine, and the clean Details UI were added after this dump.
> The environment facts, v1.5 experiments, and repro commands below are still useful.

---

## 0. TL;DR state
- **Latest build:** `project files/apk/Download-Chan-v1.5.apk` (108 MB, debug-signed).
- **Next build:** v1.6 (`project files/downloadchan/VERSION` currently `1.6`).
- **Repo:** https://github.com/nahid77777771-maker/download-chan @ `eb50371` (main).
- **Installed features:** share-to-download, video/mp3, library, browse, settings,
  online resolver fallback (v1.4), yt-dlp engine auto-update (v1.5).
- **Open problem (real):** login-walled **Facebook/Instagram** videos can't be fetched
  by any method we have. Needs **cookies/login** (NOT implemented). See §5.

---

## 1. Environment facts (this machine: Omarchy/Arch, x86_64)
| Thing | Value |
|---|---|
| Build workdir | `~/Downloads/download-chan/"project files"/downloadchan` |
| Java (system default) | **OpenJDK 26.0.2.1** — WRONG for Gradle, use JDK 17 via `env.sh` |
| JDK 17 | provided by `env.sh` (sets `JAVA_HOME` + `ANDROID_HOME=~/Android/Sdk`) |
| Android SDK | `/home/nahid/Android/Sdk` |
| build-tools | `34.0.0` |
| aapt | `/home/nahid/Android/Sdk/build-tools/34.0.0/aapt` |
| git remote | `origin https://github.com/nahid77777771-maker/download-chan.git` |
| `gh` auth | valid (was once invalid in keyring → `gh auth login`) |

**Host tool quirks (learned the hard way):**
- `git clone github.com` / `git ls-remote` **HANG** → use raw / codeload tarballs.
- `git push` can be slow → allow a long timeout (`timeout 110 git push`).
- Paths contain a space (`project files`) → **always quote**.
- curl DNS flaky → `curl --resolve` with pinned IPs.
- No device attached here (`adb devices` empty) → **user tests on their phone**.
- AI **cannot view images**; probe PNGs with
  `magick <png> -format "%[pixel:p{x,y}]" info:` (master PNGs are 1024² → 512-space ×2).
- Long builds: `nohup ... &` + poll (foreground can hit tool timeout).

---

## 2. Build & verify
```bash
cd ~/Downloads/download-chan/"project files"/downloadchan
source env.sh          # JDK 17 + ANDROID_HOME
./build-apk.sh         # bumps versionCode/Name from VERSION, cap sync, assembleDebug,
                       # copies APK, then bumps VERSION file
# → ../apk/Download-Chan-v<ver>.apk
```
Incremental build ≈ 12–15 s. Verify the APK:
```bash
AAPT=~/Android/Sdk/build-tools/34.0.0/aapt
"$AAPT" dump badging ../apk/Download-Chan-v1.5.apk | grep -E "^package:|INTERNET"
"$AAPT" dump xmltree ../apk/Download-Chan-v1.5.apk AndroidManifest.xml | grep extractNativeLibs
```
Expected: `versionName='1.5'`, `uses-permission ... INTERNET`,
`android:extractNativeLibs=true (0xffffffff)`.

---

## 3. Native engine facts (youtubedl-android 0.18.1)
- Bundled **yt-dlp `2025.11.12`** at `res/raw/ytdlp` inside the library AAR.
- Runs via bundled **python + ffmpeg** `.so` (needs native extraction — see §4).
- Java API confirmed via `javap`:
  - `YoutubeDL.getInstance().init(Context)`
  - `YoutubeDL.getInstance().execute(YoutubeDLRequest, processId, Function3<Float,Long,String,Unit>)`
  - `YoutubeDL.getInstance().updateYoutubeDL(Context, YoutubeDL.UpdateChannel)`
  - **`UpdateChannel` is a Kotlin sealed class** → Java access is
    `YoutubeDL.UpdateChannel.STABLE.INSTANCE` (NOT `.STABLE`).
  - `YoutubeDL.UpdateStatus` is an enum: `DONE`, `ALREADY_UP_TO_DATE`.
- Progress callback Java type: `kotlin.jvm.functions.Function3<Float,Long,String,Unit>`.
- Download dir: `getExternalFilesDir(DIRECTORY_DOWNLOADS)/Download-Chan`.
- Output template: `%(title).100s [%(id)s].%(ext)s`.

---

## 4. Root causes already solved
1. **"failed to initialize"** → AGP 8 defaults `extractNativeLibs=false`, so the bundled
   python/ffmpeg `.so` never extract. FIX (keep BOTH):
   - `AndroidManifest.xml`: `android:extractNativeLibs="true"`
   - `app/build.gradle` (android{} level): `packagingOptions { jniLibs { useLegacyPackaging = true } }`
2. **YouTube "works, added, fails after a while"** → extraction OK, media URL returns
   `HTTP Error 403: Forbidden` (anti-bot/PO-token). Fixes in v1.3: force
   `youtube:player_client=default`, prefer H.264 mp4 + m4a, `--retries/--fragment-retries`,
   auto-retry with `--force-ipv4` + `youtube:player_client=android,default`.
   Only `default`/`android` clients give downloadable URLs; `android_vr`/`tv`/`web_safari`/`mweb`/`ios` 403 or have no formats.

---

## 5. Facebook "Cannot parse data" — VERIFIED diagnosis (v1.5)
Reported in `~/Downloads/issu.txt`:
```
ERROR: [facebook] 1413081230459468: Cannot parse data; please report this issue ...
WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
```
**Experiments run (all consistent):**
| Method | Result |
|---|---|
| bundled yt-dlp `2025.11.12` | `[facebook] ...: Cannot parse data` |
| latest yt-dlp `2026.08.19` (downloaded to `/tmp/opencode/ytdlp_new`) | **same** `Cannot parse data` |
| online resolver gendownload `/api/extract` | `HTTP 422 {"error":"Could not fetch this link..."}` after ~50 s |
**Conclusion:** that FB video is **login-walled** (private / needs session). Not an app
bug; a version bump does NOT fix it. **Real fix = cookies/login.** Until then, the app's
error hint points users at Online fallback / public links.

**Reproduce on host (bundled binary runs on the host too):**
```bash
cd /tmp/opencode
python3 ytdlp_bin --no-warnings -F "https://www.facebook.com/watch/?v=1413081230459468"
```

---

## 6. Online resolver (v1.4)
- Code: `DownloaderPlugin.resolveRemote()` + `pickRemote()` + `downloadHttp()` +
  `downloadRemote` plugin method. Default resolver `https://gendownload.com`
  (`settings.resolver`), endpoint `POST {resolver}/api/extract`.
- Request: `{"url": "<link>"}`, `Content-Type: application/json`, mobile UA.
- Response: `{ title, thumbnail, duration, source, author, views, likes,
  formats:[{label,type,ext,filesize,url}] }` where `type ∈ {video,audio}`,
  labels `2160p..144p` and `Audio` (mp3). `filesize` values are placeholder — ignore.
- Stream URLs `https://dl.gendownload.com/api/stream?t=<token>&i=<idx>` serve real
  `video/mp4` with `Content-Disposition: attachment`.
- Resolver is **pluggable**, but schema is gendownload-specific. A **cobalt** backend
  returns `status: tunnel|redirect|picker` — needs an adapter, not a drop-in.
- **Test any resolver:**
  ```bash
  curl -sX POST <resolver>/api/extract -H 'Content-Type: application/json' \
    -H 'User-Agent: Mozilla/5.0 (Linux; Android 13) Chrome/120 Mobile Safari/537.36' \
    -d '{"url":"https://www.youtube.com/watch?v=dQw4w9WgXcQ"}'
  ```
- Verified working for **YouTube**. Fails (422) for login-walled FB.

---

## 7. Where things live in the app
### Web (`project files/downloadchan/www/`)
- `index.html` — 4 tabs (download/library/browse/settings); settings has Online-fallback
  toggle (`#remote-on`), resolver (`#resolver`), Update-engine button (`#update-engine`),
  engine status (`#engine-detail`), version (`#appver`), error box (`#last-error`).
- `js/native.js` — `window.DC` wrapper: `init,isReady,getSharedUrl,getInfo,download,
  downloadRemote,updateEngine,cancel,listFiles,deleteFile,openFile,shareFile,openExternal,on`.
- `js/app.js` — all logic. Key fns: `runJob → runJobLocal → runJobRemote` (fallback),
  `succeed/fail`, `maybeUpdateEngine/updateEngineNow`, `loadSettings/saveSettings`
  (localStorage `dc_settings`, `dc_engine_update`).
### Native (`.../java/com/downloadchan/app/`)
- `MainActivity.java` — registers plugin; ACTION_SEND/ACTION_VIEW → setPendingShare.
- `DownloaderPlugin.java` — the plugin. `ensureInit`, `runAsync`, methods above.
- Manifest/build.gradle — see §3 / §4.

---

## 8. Debug decision tree (start here when a link fails)
1. Read the on-screen **"Last error"** box (copyable) — get the exact message.
2. Identify the site from the error tag (`[facebook]`, `[youtube]`, `[instagram]`…).
3. `Cannot parse data` / `login / sign in / private` → **login-walled** → cookies needed (§5).
4. YouTube `403 Forbidden` on media → anti-bot → relies on local retry / online fallback.
5. Resolver `HTTP 4xx` → the resolver itself can't fetch it (often login-walled).
6. `failed to initialize` → native extraction broken → check §4 items still present.
7. Verify network: app has `INTERNET` permission (confirmed in v1.5 badging).

---

## 9. Scratch / tmp (NOT in repo; may be wiped on reboot)
`/tmp/opencode/`: `ytdlp_bin` (2025.11.12 runnable), `ytdlp_new` (2026.08.19),
`ytdlp_aar/` (unzipped lib, incl. `res/raw/ytdlp`), `ffmpeg_aar/`, `dltest/`, `part.bin`.
Re-fetch latest yt-dlp if missing:
`curl -sL -o ytdlp_new https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp`

---

## 10. Highest-value next features
1. **Cookies support** — import `cookies.txt` (SAF) or in-app WebView login → pass
   `--cookies <file>` to yt-dlp. Fixes login-walled FB/IG. (Recommended next.)
2. Swap resolver to self-hosted **cobalt** (needs schema adapter).
3. Smaller release APK (keystore + `assembleRelease` + `minifyEnabled`, or arm64-only).
4. Wire `getInfo` thumbnail/title preview (native already implemented).
5. Multiple concurrent downloads / foreground service + notification.
6. iOS target.
