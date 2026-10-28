# Download-Chan — Change History

Every user prompt is recorded VERBATIM, followed by what was done.

---

## Session 1 — Project creation (v1.0 → v1.1)

### Prompt (verbatim)
> so you know how my app creating and building process is

> tell me how i creat apk from your experince

> i want you apply past learning and creat a new app for android a app that has 4
> tab add option you see fit the main perpous of the app is to download if i shair
> any link it will download  like if i shair a fb link it will download that if i
> shair youtube link i will download it and tiktok douyin instagram and reel any
> now build it later i cn push updates also pu build and surce app in git

### Decisions (confirmed with user)
- **Name:** Download-Chan → package `com.downloadchan.app`.
- **Engine:** bundle yt-dlp inside the app (offline, no server).
- **Git:** new repo under `nahid77777771-maker`.

### What was built
- New Capacitor 6 Android project, cloned from the KanaPet scaffold, renamed
  everywhere (appId, namespace, applicationId, strings, package dir, build script).
- **4 tabs:** Download / Library / Browse / Settings (`www/index.html` + CSS + JS).
- **Native share receiver:** `ACTION_SEND` (text/plain) intent-filter →
  `MainActivity` → `DownloaderPlugin.setPendingShare()` + live `sharedUrl` event.
- **`DownloaderPlugin.java`:** wraps `io.github.junkfood02.youtubedl-android`
  (0.18.1, Maven Central). Methods: `init`, `isReady`, `getSharedUrl`, `getInfo`,
  `download`, `cancel`, `listFiles`, `deleteFile`, `openFile`, `shareFile`,
  `openExternal`. Emits `progress` / `completed` / `error` / `cancelled` events.
- **v1.0 APK** built (214 MB — all 4 ABIs).
- **v1.1 APK** (109 MB) — added `abiFilters 'arm64-v8a','armeabi-v7a'` to drop the
  huge x86/x86_64 ffmpeg/python libs.

### Deliverables
- `project files/apk/Download-Chan-v1.0.apk` (214 MB)
- `project files/apk/Download-Chan-v1.1.apk` (109 MB) ← LATEST

### Notes
- v1.0 shipped before ABI filtering; v1.1 is the recommended build.
- Engine = yt-dlp 0.18.1 (bundled). ffmpeg bundled for merging / MP3 extraction.

---

## Session 2 — Downloads failed to initialize + new icon (v1.2)

### Prompt (verbatim)
> i try to download videos and it says faild to inisilized rechack and fix code and
> change app icon to cuter japanise green hair girl and holding a download sing

### Diagnosis
- Symptom: "failed to initialize". Root cause: under **AGP 8** the manifest defaults
  to `android:extractNativeLibs="false"`, so youtubedl-android's bundled
  Python/FFmpeg `.so` files are never extracted to `nativeLibraryDir` → init throws
  (`YoutubeDLException: failed to initialize`, caused by `NoSuchFileException ...
  libpython.zip.so`). Well-known youtubedl-android issue (#105/#300).

### Fixes
- `AndroidManifest.xml`: added `android:extractNativeLibs="true"` on `<application>`.
- `android/app/build.gradle`: added `packagingOptions { jniLibs { useLegacyPackaging
  = true } }` at the `android {}` level (forces install-time extraction).
- `DownloaderPlugin.java`: errors now surface the **full cause chain**
  (`fullMessage()`), logged with `Log.e` — easier to diagnose next time.
- **New app icon:** cute green-haired chibi girl holding a download sign, authored
  as SVG (`rsvg-convert`) → all mipmap densities (legacy square + round + adaptive
  foreground). Adaptive background = pink→blue gradient VectorDrawable.
- **Splash screens** regenerated: gradient background + centered logo (were blank
  white with a leftover default logo).

### Deliverables
- `project files/apk/Download-Chan-v1.2.apk` (108 MB) ← LATEST (working)
- Removed the broken v1.0/v1.1 APKs.

### Notes
- Verified in the built APK: `extractNativeLibs=0xffffffff` (true), libs legacy
  packaged, all icon densities present.

---

## Session 3 — Downloads failing mid-way + cuter icon (v1.3)

### Prompt (verbatim)
> issu the link work and add download but its some how cant start after some time
> it says failed change the app icon cuter anc choby anime stle one

### Diagnosis (reproduced with the bundled yt-dlp on the same network)
- Bundled yt-dlp is recent (`2025.11.12`), so it's NOT an outdated-extractor issue.
- Format extraction/metadata SUCCEEDS ("the link work, add download"), but the
  **actual media download returns `HTTP Error 403: Forbidden`** right after
  "Sleeping 5.00 seconds as required by the site..." → matches "after some time it
  says failed". This is YouTube's anti-bot / PO-token enforcement.
- Different player clients behave very differently: `default` + `android` give
  downloadable URLs; `android_vr`, `tv`, `web_safari`, `mweb`, `ios` returned 403 or
  "format not available".

### Fixes
- `DownloaderPlugin.java` hardened:
  - format selector now prefers **H.264 mp4 video + m4a audio** (universally
    playable, remuxes cleanly, avoids SABR/av01 pitfalls), with fallbacks.
  - forces `--extractor-args youtube:player_client=default` (the client that exposes
    a directly downloadable URL).
  - `--retries 10`, `--fragment-retries 10`, `--socket-timeout 30`.
  - **auto-retry** on failure with `--force-ipv4` + `youtube:player_client=android,default`.
- UI now shows a **persistent, copyable "Last error"** box (was a 2.6s toast), so
  the exact yt-dlp error is readable/reportable.

### Icon (cuter chibi anime style)
- Redesigned `project details/icon/art.svg`: bigger head (chibi), large sparkling
  eyes with double highlights, **twin tails** with star ties, a pink **hair flower**,
  blush, open smile with tongue, tiny body hugging the download sign.
- Regenerated all mipmap densities (square + round + adaptive foreground).

### Deliverables
- `project files/apk/Download-Chan-v1.3.apk` (108 MB) ← LATEST
- Removed v1.2 (superseded).

### Notes
- If a specific YouTube link still 403s, it's an IP-level bot flag; retries/VPN or
  cookies are the only remaining levers. Report the full "Last error" text.

---

## Session 4 — Online resolver fallback (v1.4)

### Prompt (verbatim)
> i have a idea use exsting websites who let past link and download videso and add
> their api or what need to fix the issu whats your plan tell me first

### Decisions (confirmed with user)
- Approach: let a **server-side resolver** handle the anti-bot problem; the phone
  downloads a direct file URL.
- Backend: **free public API** (tested `gendownload.com` — works, no key).
- Fallback mode: **Auto** — try bundled yt-dlp first, then the online resolver.

### What was built
- Verified `POST https://gendownload.com/api/extract {url}` returns
  `{ title, thumbnail, formats:[{label,type,ext,url}] }` and the returned stream URL
  serves a real `video/mp4`/`audio/mp3` (`Content-Disposition: attachment`). No API key.
- `DownloaderPlugin.java`: new **`downloadRemote`** method — POSTs to the resolver,
  picks the format by mode/quality, then downloads the direct URL with a plain
  `HttpURLConnection` stream (Content-Length progress), saving into the same folder.
  Resolver URL is configurable; defaults to gendownload.com.
- `www/js/native.js`: exposes `downloadRemote`.
- `www/js/app.js`: **Auto fallback** — `runJobLocal` → on failure → `runJobRemote`
  (toast + card shows "Trying online…"); both messages shown if both fail.
- Settings UI: **Online fallback** toggle + **Online resolver** URL field.

### Deliverables
- `project files/apk/Download-Chan-v1.4.apk` (108 MB) ← LATEST
- Removed v1.3.

### Notes / caveats
- The shared link leaves the phone to the resolver (privacy) and the service may
  rate-limit or vanish — that's why local yt-dlp stays as the first attempt.
- Resolver is pluggable: pointing it at a self-hosted cobalt instance later is a
  Settings change (cobalt's response schema differs, so it'd need an adapter).
- Legal: personal use; respect each site's terms.

---

## Session 5 — Facebook "Cannot parse data" diagnosis + engine auto-update (v1.5)

### Prompt (verbatim)
> chack download folder issu.txt

### The reported error (`~/Downloads/issu.txt`)
```
YoutubeDLException: WARNING: Your yt-dlp version (2025.11.12) is older than 90 days!
ERROR: [facebook] 1413081230459468: Cannot parse data; please report this issue ...
```

### Root-cause findings (verified)
- The failing link is **Facebook**, not YouTube. Facebook now **login-walls** most
  `watch/?v=` videos, so:
  - bundled yt-dlp `2025.11.12` → `Cannot parse data`;
  - **latest** yt-dlp `2026.08.19` → **also `Cannot parse data`** (so not just stale);
  - online resolver (gendownload) → **HTTP 422** "Could not fetch this link" after ~50s.
- Conclusion: **this specific video needs an authenticated session** — not an app bug.
  A version bump alone does NOT fix it; proper fix = **cookies/login support**.
- But the bundled yt-dlp IS ~10 months stale (2025.11.12 vs 2026.08.19) → worth updating.

### What was built
- `DownloaderPlugin.java`: new **`updateEngine`** method — calls
  `YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE.INSTANCE)`
  (Kotlin sealed class → `.INSTANCE`), returns `{updated, status}`.
- `www/js/native.js`: exposes `updateEngine`.
- `www/js/app.js`: **auto-update on boot** (max once / 24h via `dc_engine_update` in
  localStorage) + a **Settings → "Update download engine"** button; engine status shown
  in Settings ("up to date" / "just updated").
- Better error hint: if the error matches `cannot parse data|not available|login|sign
  in|private`, append a hint pointing at Online fallback / public links.

### Deliverables
- `project files/apk/Download-Chan-v1.5.apk` ← LATEST (removed v1.3/v1.4).

### Next (optional)
- **Cookies support** (import a `cookies.txt` or in-app WebView login → `--cookies`) is
  the real fix for login-walled Facebook/Instagram. Not yet implemented.

---

## Session 6 — v2.0 from-scratch rebuild: clipboard auto-download, public folder, multi-thread, cookies

### Prompt (verbatim)
> now see download chan and build it from scrach i need thos funtation when i open the app automaticly grap link from clip board and start downloading and if download is not specifed or set defult download is download folder with afolder name download chan any link download able fb insta youtube any and add setting for multi thred downlalod like adm or 1dm download manager give is morder look and simple just woork tool ok

### Decisions
- **Rebuilt the app as v2.0** (same package `com.downloadchan.app` so it installs over
  v1.5). New 3-tab UI (Home / Files / Settings) and a much larger native engine.
- **Save folder:** public `Downloads/Download Chan` when Android "All files access" is
  granted; otherwise falls back to the app-private folder. A first-run sheet + banner
  ask for access. Old v1.5 files in the private `Download-Chan` folder are still listed.
- **Clipboard:** native `getClipboard` bridge; on launch/resume a link is detected and
  auto-downloaded (setting on by default). Dedupe window = 24 h per URL; the same link
  will not auto-download again after a restart. Manual Download always works.
- **Engine choice:** known video/social hosts → yt-dlp; other links are probed and direct
  media files go through the new native **segmented HTTP range engine** (true multi-thread).
- **Multi-thread:** Settings → Connections 1–16 (default 8). Direct files split into N
  range chunks; servers without Range fall back to a single stream. yt-dlp gets
  `--concurrent-fragments N`.
- **Login walls:** added **cookies.txt import** (SAF picker) → passed to yt-dlp
  `--cookies`. This is the real fix path for private Facebook/Instagram.
- Online resolver fallback kept in Advanced, now **off by default** (privacy).

### What was built
- `DownloaderPlugin.java` rewritten (~1,000 lines):
  - `downloadAuto` route: direct segmented downloader vs yt-dlp.
  - `probe()` / `directDownload()` / `segmentedDownload()` / `singleStream()`; progress
    with bytes, total, speed, ETA; range fallback; cancel support.
  - `storageStatus`, `requestStorage` (opens All-files-access settings), `openFolder`.
  - `getClipboard`, `cookiesStatus`, `pickCookies` (@ActivityCallback SAF import),
    `clearCookies`.
  - `listFiles` merges public + private + legacy folders; per-file `dir`; robust delete.
  - yt-dlp jobs use a unique `.job_<id>` subfolder, then move the produced file into the
    real folder — no more "newest file in folder" mix-ups.
  - yt-dlp progress now emits estimated bytes + speed; `--concurrent-fragments N`; cookies.
  - Kept `updateEngine`, `getInfo`, `downloadRemote`; `download` aliases `downloadAuto`.
- `MainActivity.java`: ACTION_VIEW + ClipData fallback for shared text.
- `AndroidManifest.xml`: `MANAGE_EXTERNAL_STORAGE`; VIEW intent filter for http/https
  (open links directly with Download Chan); existing SEND filter preserved.
- `strings.xml` / `styles.xml`: app name **Download Chan**; dark status/navigation bars.
- `www/` rewritten from scratch:
  - `index.html` — modern dark UI, 3 tabs, storage permission sheet, error card.
  - `css/style.css` — new violet/mint design, safe-area bottom nav, switches, stepper.
  - `js/app.js` — clipboard auto-flow, queue, progress/speed, storage + cookies UI,
    retries, copy error, recent files, engine auto-update.
  - `js/native.js` — wrappers for all new native methods.
- APK: `project files/apk/Download-Chan-v2.0.apk`
  - 108,128,348 bytes (~104 MB), versionCode 20000, versionName 2.0.
  - SHA-256 `a5c1df52b9a1456155a93ed2e9a35a6a09c5a480ddbc3e2d46b05825f525c52e`.
  - Debug-signed. `VERSION` now 2.1 for the next build.

### Verification
- `./build-apk.sh 2.0` succeeds (Gradle assembleDebug).
- APK manifest verified: label `Download Chan`, minSdk 30/target 34,
  `MANAGE_EXTERNAL_STORAGE`, SEND + VIEW http/https filters.
- New `www` assets verified inside the APK; JS passes `node --check`;
  Home/Settings rendered in headless Chromium at 390×844 (no JS errors).
- **Not device-tested** — no adb device/emulator attached this session.

### Known limits
- Private/login-walled Facebook & Instagram still need a valid cookies.txt; public
  posts are handled by yt-dlp + engine update.
- Multi-thread only applies to direct HTTP(S) files whose server supports Range.
- All-files access is required for the public Downloads folder; without it the app
  works but saves in the app-private folder.
- `MANAGE_EXTERNAL_STORAGE` is a sideload permission (Play Store would reject it).

---

## Session 7 — v2.0.1: Facebook videos downloaded without audio

### Prompt (verbatim)
> app works but the fb link donlowd video has no audio chack issu and fix it

### Root cause
- The app called only `YoutubeDL.getInstance().init()` and never initialized the bundled
  FFmpeg package.
- Social downloads (Facebook Reels/DASH, YouTube, etc.) arrive as **separate video and
  audio streams**; yt-dlp merges them with FFmpeg. With FFmpeg uninitialized, the merge
  cannot run, so yt-dlp leaves the video-only stream and the app saved that file — the
  picture played but had no sound.
- The library requires `FFmpeg.getInstance().init(context)` after `YoutubeDL.init()`
  (confirmed in the youtubedl-android README). `libffmpeg.so` also needs the `libav*.so`
  libraries that the FFmpeg package extracts; YoutubeDL's environment already points at
  that extraction directory, so initializing FFmpeg makes merging work.
- v1.5 had the same latent bug (only YouTube/TikTok muxed formats hid it).

### Fix
- `DownloaderPlugin.java`: import `com.yausername.ffmpeg.FFmpeg`.
- `ensureInit()` now runs `YoutubeDL.init()` **then** `FFmpeg.getInstance().init()`.
  FFmpeg init failures are logged but do not break the app (`Log.e`), so muxed formats
  still download even on a damaged install.
- No selector change was needed: `bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b` +
  `--merge-output-format mp4` produces a single MP4 with AAC audio once FFmpeg works.

### Build / verification
- Built **v2.0.1** (versionCode 20001) over v2.0; `VERSION` now 2.0.2.
  - `project files/apk/Download-Chan-v2.0.1.apk`
  - 108,075,672 bytes, SHA-256
    `822aa6745901644b543acaf48202f4a67ed55a98fcfe48ea087dd81ac7ae977d`.
- `aapt` confirms versionName 2.0.1 / code 20001, label `Download Chan`, minSdk 30,
  target 34. DEX contains `com/yausername/ffmpeg/FFmpeg`; ffmpeg/ffprobe libs packaged.
- Not device-tested (still no adb device/emulator). Existing no-audio files must be
  deleted and downloaded again.

### User-facing
- Install v2.0.1 over v2.0, then download the Facebook link again: it should now merge
  video + audio into one MP4. First yt-dlp operation may take a few extra seconds while
  the FFmpeg package extracts on first use.

---

## Session 8 — v2.1.0: clean UI refresh + progressive "details" levels

### Prompt (verbatim)
> video audio works now polish the app and leveling simple and clean so detals levels

### Context
- User confirmed the v2.0.1 Facebook/social audio fix works.
- Interpreted "details levels" as progressive disclosure: compact summaries first,
  expandable details only when wanted. No native engine behavior changes were needed.

### What was built
- `www/index.html`, `www/css/style.css`, `www/js/app.js` redesigned:
  - neutral near-black theme with indigo accent (removed the old violet/mint look);
  - Home: "Download anything", compact link input, Video/Audio segment, quality
    dropdown, one primary Download button;
  - active job: compact title/status/progress/speed/ETA plus one card-level Cancel;
  - job Details expander: Link, Type, Quality, Connections, Save to, Size, Status;
  - failures: concise error preview with Retry/Dismiss; full error + Copy error in Details;
  - Files: folder card, file rows with open + details chevron; Details shows Path, Type,
    Size, Saved plus Share/Delete;
  - Settings: grouped Downloads / Save location / Login cookies / Engine; Advanced
    fallback/resolver collapsed by default;
  - fixed bottom Home/Files/Settings nav with an active pill.
- Polish fixes:
  - removed the duplicate section-header Cancel button; only the card action remains;
  - job titles now prefer the completed filename, then a URL filename, then hostname
    (instead of showing a raw path such as `facebook.com/watch/`);
  - result heading supports Active download / Download complete / Last download;
  - native status/navigation bars aligned to `#0A0B0F`.

### Verification
- `node --check` passes for `app.js` and `native.js`.
- Headless Chromium: no JS errors; no horizontal overflow at 390×844 or 360×800.
  Checked Home active/progress, expanded job details, failure, Files, expanded file
  details, Settings, and expanded Advanced settings.
- Preview-only `window.__dcPreview` hooks live in `/tmp/opencode/dcprev`; production
  `www/` has no debug hooks.
- Built v2.1.0 (versionCode 20100), 108,077,112 bytes, SHA-256
  `e2352d1d65b20b84e5964c52e6f4f7eabbe07bb1f43e4d4f967c7483378dc369`.
  `aapt` confirms versionName 2.1.0 / code 20100; APK assets contain the new UI and no
  `cancel-all`; `VERSION` now 2.1.1.
- Not device-tested (still no adb device/emulator).

### User-facing
- Install v2.1.0 over v2.0.1; downloads, cookies, storage and the audio fix are unchanged.
  The UI is cleaner and details are collapsed until tapped.

---

## Session 9 — publish v2.1.0 source

### Prompt (verbatim)
> works now push update

### Result
- User confirmed v2.1.0 works on-device.
- Committed the v2.0/v2.0.1/v2.1.0 source and documentation, then pushed `main` to
  https://github.com/nahid77777771-maker/download-chan.
- APK files remain gitignored; the v2.1.0 build is unchanged at
  `project files/apk/Download-Chan-v2.1.0.apk`.

---

## Session 10 — v2.1.1: chubby cat logo (no anime/human)

### Prompt (verbatim)
> change the logo for download chan use a cute choby type logo no anime or human

### Interpretation
- Treated "choby" as chubby/chibi-cute.
- Replaced the green-haired anime girl with a round, chubby peach cat mascot holding a
  white download card. No human features, no anime styling.

### What changed
- New SVG masters `project details/icon/art.svg` + `bg.svg`:
  chubby cat, big simple eyes, blush, curled tail, white download card with indigo arrow,
  deep indigo gradient background and soft glow.
- Regenerated every launcher icon density (`ic_launcher`, `ic_launcher_round`,
  `ic_launcher_foreground`) and updated the adaptive background gradient.
- Replaced all `res/drawable*/splash.png` screens with an indigo gradient, floating cat,
  "Download Chan" wordmark and "save anything" tagline.
- Added `www/img/logo.png` and replaced the old header arrow badge with the new mascot.
- Updated the in-app version display to `2.1.1` in both `index.html` and `app.js`.
- `gen.sh` now regenerates icons, the web header logo, and all splash sizes reproducibly.

### Verification
- `node --check` passes; headless Chromium at 360×800 shows the new header logo with no
  JS errors and no horizontal overflow.
- Built v2.1.1 (versionCode 20101), 109,138,901 bytes, SHA-256
  `a897c628c627fb117e2ae7267f9224f9054e8b5a5381701eab388a91e622eebf`.
- `aapt` confirms versionName 2.1.1 / code 20101 and label `Download Chan`; APK contains
  the new launcher PNGs, all splash PNGs, and `assets/public/img/logo.png`.
- Not device-tested (no adb device/emulator attached).

### User-facing
- Install v2.1.1 over v2.1.0; downloads, cookies, storage and the audio fix are unchanged.
  If the launcher still shows the old icon, restart the launcher or re-add the shortcut.
  APK: `project files/apk/Download-Chan-v2.1.1.apk`.

---

## Session 11 — publish v2.1.1 + create the missing release APK

### Prompts (verbatim)
> after finishing logo update git repo of download chan i can t find y=the relse apk build see and solve the issu shy is that
> if issu resolved turn off pc

### Root cause
- `project files/apk/*.apk` is deliberately gitignored, so the APK was never in the repo.
- No GitHub release had been created for Download Chan; the repo only had source
  (v2.1.0 at `73b0d84`).
- The build script produces a debug-signed APK, not a Gradle release variant; no Android
  release keystore exists for this project yet.

### Fix
- Committed and pushed the v2.1.1 logo source/docs to `main`:
  `9b5a50c v2.1.1: chubby cat mascot logo + splash`.
- Created GitHub release **v2.1.1** with the stable asset name `Download-Chan.apk`:
  https://github.com/nahid77777771-maker/download-chan/releases/tag/v2.1.1
- Verified the release asset: 109,138,901 bytes; GitHub digest
  `sha256:a897c628c627fb117e2ae7267f9224f9054e8b5a5381701eab388a91e622eebf`
  (matches the local APK).
- Added the stable latest-download link to `README.md`:
  https://github.com/nahid77777771-maker/download-chan/releases/latest/download/Download-Chan.apk
- Updated docs to state that the APK is debug-signed, installs over v2.1.0 with data
  preserved, and is not a Play Store/release-keystore build.
