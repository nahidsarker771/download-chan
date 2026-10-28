# Download Chan — APK Build Instructions

**Tech stack:** vanilla HTML/CSS/JS UI (`www/`) wrapped with **Capacitor 6** for
Android, plus a native Java bridge that bundles **yt-dlp** via
`io.github.junkfood02.youtubedl-android:0.18.1` (extractor) and ffmpeg (merge/MP3).

## Project layout
```
downloadchan/
├── package.json            # Capacitor dependencies
├── capacitor.config.json   # appId com.downloadchan.app, webDir = www
├── build-apk.sh            # versioned build script (use this)
├── env.sh                  # JDK 17 + Android SDK env
├── VERSION                 # next build version
├── www/                    # the whole UI (opens in any browser)
│   ├── index.html
│   ├── css/style.css
│   └── js/{native,app}.js
└── android/                # Capacitor Android project + native plugin
    └── app/src/main/java/com/downloadchan/app/
        ├── MainActivity.java        # share-intent receiver
        └── DownloaderPlugin.java    # yt-dlp bridge (/Downloader plugin)
```

## Prerequisites (one-time)
1. **Node.js 18+** and npm.
2. **Android SDK** + **JDK 17** (Gradle 8.x fails on newer JDKs). `env.sh` sets
   `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` and `ANDROID_HOME=~/Android/Sdk`.
3. SDK licenses accepted.

## Exact build commands
```bash
cd downloadchan

# 1. Install Capacitor + deps
npm install

# 2. (first time only) add the Android platform
npx cap add android

# 3. Copy www/ into the Android project + update plugins
npx cap sync android

# 4. debug APK
cd android && ./gradlew assembleDebug
#    output: android/app/build/outputs/apk/debug/app-debug.apk
```

## ✅ PREFERRED: versioned build script (use this every time)
```bash
cd downloadchan
source env.sh
./build-apk.sh          # builds VERSION, names APK Download-Chan-v<ver>.apk, bumps VERSION
./build-apk.sh 2.1.0    # optional: force a specific version
```
It: (1) reads `VERSION`, (2) writes `versionName`/`versionCode` into
`android/app/build.gradle`, (3) runs `npx cap sync android` + `./gradlew assembleDebug`,
(4) copies to **`project files/apk/Download-Chan-v<version>.apk`**, (5) bumps `VERSION`.

### Signing a release APK
```bash
keytool -genkey -v -keystore downloadchan.keystore -alias downloadchan -keyalg RSA -keysize 2048 -validity 10000
$ANDROID_HOME/build-tools/<version>/apksigner sign --ks downloadchan.keystore \
  --out Download-Chan-release.apk app/build/outputs/apk/release/app-release-unsigned.apk
```

## CRITICAL: native-library extraction (why downloads initialize)
youtubedl-android loads its bundled Python/FFmpeg `.so` files from
`applicationInfo.nativeLibraryDir` at runtime. Under AGP 8 the default is
`extractNativeLibs=false`, so those libs are **not** extracted and init throws
`failed to initialize`. Both of these are required and already set:
- `android/app/src/main/AndroidManifest.xml`: `android:extractNativeLibs="true"`
- `android/app/build.gradle`: `packagingOptions { jniLibs { useLegacyPackaging = true } }`

## Share / link targets
`AndroidManifest.xml` declares an `ACTION_SEND` (`text/plain`) intent-filter and a
`VIEW` filter for http/https links on `MainActivity`; links arrive via
`DownloaderPlugin.setPendingShare()` and the `sharedUrl` event.

## Icons / splash
Icon art is authored as SVG in `../../project details/icon/` and rasterized into all
mipmap densities with `bash gen.sh` (needs `rsvg-convert` + ImageMagick). Splash
screens are gradient + centered logo under `res/drawable*/splash.png`.

## Notes
- Only download content you own or have rights to save. Respect each site's terms.
- Private/login-walled Facebook/Instagram posts need a browser-exported `cookies.txt`
  imported from Settings. Public posts can work without it.
- Public `Downloads/Download Chan` requires the sideload-only All-files-access
  permission; otherwise the app falls back to its private folder.
- Direct HTTP(S) files use 1–16 parallel range connections when the server supports it.
