package com.downloadchan.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.webkit.MimeTypeMap;

import androidx.activity.result.ActivityResult;
import androidx.core.content.FileProvider;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import kotlin.jvm.functions.Function3;

/**
 * Download Chan native bridge.
 *
 *  - yt-dlp engine for social/video sites (YouTube, Instagram, Facebook, TikTok, ...)
 *  - native multi-thread (segmented HTTP range) engine for direct file links
 *  - public Downloads/Download Chan folder when "All files access" is granted
 *  - clipboard reader for automatic link capture
 *  - cookies.txt import for login-walled Facebook/Instagram content
 */
@CapacitorPlugin(name = "Downloader")
public class DownloaderPlugin extends Plugin {

    public static final String DIR_NAME = "Download Chan";
    private static final String LEGACY_DIR_NAME = "Download-Chan";
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";
    private static final int MAX_THREADS = 16;

    private static volatile String pendingShare = null;
    private static volatile DownloaderPlugin instance = null;

    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final Object INIT_LOCK = new Object();
    private static final Set<String> CANCELLED = Collections.synchronizedSet(new HashSet<>());

    private static final Pattern SPEED_RE =
            Pattern.compile("at\\s+([0-9.]+)\\s*([KMGTP]?i?B)/s", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOTAL_RE =
            Pattern.compile("of\\s+~?\\s*([0-9.]+)\\s*([KMGTP]?i?B)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_STAR_RE =
            Pattern.compile("filename\\*=UTF-8''([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME_RE =
            Pattern.compile("filename=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);

    private static final Set<String> DIRECT_EXTENSIONS = new HashSet<>();
    static {
        Collections.addAll(DIRECT_EXTENSIONS,
                "mp4", "m4v", "mkv", "webm", "avi", "mov", "flv", "wmv", "ts", "3gp",
                "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma",
                "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
                "pdf", "zip", "rar", "7z", "tar", "gz", "apk", "xapk", "iso", "dmg", "exe", "msi");
    }

    private static final String[] KNOWN_SITES = {
            "youtube.com", "youtu.be", "instagram.com", "facebook.com", "fb.watch",
            "tiktok.com", "douyin.com", "twitter.com", "x.com", "vimeo.com",
            "reddit.com", "dailymotion.com", "twitch.tv", "pinterest.com",
            "threads.net", "vk.com", "bilibili.com", "soundcloud.com", "spotify.com",
            "linkedin.com", "snapchat.com", "likee.video", "kwai.com"
    };

    @Override
    public void load() {
        instance = this;
    }

    public static void setPendingShare(String url) {
        pendingShare = url;
    }

    /* ---- generic helpers ---------------------------------------------- */

    private void runAsync(final PluginCall call, final ThrowingRunnable task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.e("DownloaderPlugin", "task failed", t);
                call.reject(fullMessage(t));
            }
        }).start();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    static String fullMessage(Throwable t) {
        StringBuilder sb = new StringBuilder();
        Throwable c = t;
        int depth = 0;
        while (c != null && depth < 6) {
            if (sb.length() > 0) sb.append("  <-  ");
            String m = c.getMessage();
            sb.append(c.getClass().getSimpleName()).append(": ").append(m == null ? "" : m);
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static long parseSize(String number, String unit) {
        try {
            double n = Double.parseDouble(number);
            String u = unit.toUpperCase(Locale.US);
            double mult = 1;
            if (u.startsWith("K")) mult = 1024d;
            else if (u.startsWith("M")) mult = 1024d * 1024d;
            else if (u.startsWith("G")) mult = 1024d * 1024d * 1024d;
            else if (u.startsWith("T")) mult = 1024d * 1024d * 1024d * 1024d;
            return (long) (n * mult);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long parseSpeed(String line) {
        if (line == null) return 0;
        Matcher m = SPEED_RE.matcher(line);
        if (m.find()) return parseSize(m.group(1), m.group(2));
        return 0;
    }

    private static long parseTotal(String line) {
        if (line == null) return 0;
        Matcher m = TOTAL_RE.matcher(line);
        if (m.find()) return parseSize(m.group(1), m.group(2));
        return 0;
    }

    /* ---- directories + storage ---------------------------------------- */

    private boolean hasAllFiles() {
        try {
            return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    private File publicDir() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR_NAME);
    }

    private File privateDir() {
        File base = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getContext().getFilesDir();
        return new File(base, DIR_NAME);
    }

    private File legacyPrivateDir() {
        File base = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base == null) base = getContext().getFilesDir();
        return new File(base, LEGACY_DIR_NAME);
    }

    private File downloadDir() {
        if (hasAllFiles()) {
            File d = publicDir();
            if (d.isDirectory() || d.mkdirs()) return d;
        }
        File d = privateDir();
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    private List<File> scanDirs() {
        Map<String, File> map = new LinkedHashMap<>();
        addDir(map, downloadDir());
        if (hasAllFiles()) addDir(map, privateDir());
        addDir(map, legacyPrivateDir());
        return new ArrayList<>(map.values());
    }

    private void addDir(Map<String, File> map, File dir) {
        try {
            if (dir.isDirectory()) map.put(dir.getCanonicalPath(), dir);
        } catch (Exception ignored) {
        }
    }

    private static boolean isTempName(String name) {
        String n = name.toLowerCase(Locale.US);
        return n.startsWith(".") || n.endsWith(".part") || n.endsWith(".ytdl")
                || n.endsWith(".tmp") || n.endsWith(".download");
    }

    private static List<File> mediaFiles(File dir) {
        List<File> out = new ArrayList<>();
        File[] all = dir.listFiles();
        if (all == null) return out;
        for (File f : all) {
            if (!f.isFile() || isTempName(f.getName())) continue;
            out.add(f);
        }
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private List<File> allMediaFiles() {
        Map<String, File> map = new LinkedHashMap<>();
        for (File dir : scanDirs()) {
            for (File f : mediaFiles(dir)) {
                try {
                    map.put(f.getCanonicalPath(), f);
                } catch (Exception ignored) {
                }
            }
        }
        List<File> out = new ArrayList<>(map.values());
        out.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    private static String mimeFor(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            String ext = name.substring(dot + 1).toLowerCase(Locale.US);
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    private boolean isInsideDownloadDirs(File f) {
        try {
            String path = f.getCanonicalPath();
            for (File dir : scanDirs()) {
                if (path.startsWith(dir.getCanonicalPath() + File.separator)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /* ---- engine -------------------------------------------------------- */

    private void ensureInit() throws YoutubeDLException {
        if (INITIALIZED.get()) return;
        synchronized (INIT_LOCK) {
            if (INITIALIZED.get()) return;
            YoutubeDL.getInstance().init(getContext());
            try {
                FFmpeg.getInstance().init(getContext());
            } catch (Throwable t) {
                Log.e("DownloaderPlugin", "ffmpeg init failed", t);
            }
            INITIALIZED.set(true);
        }
    }

    @PluginMethod
    public void isReady(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ready", INITIALIZED.get());
        call.resolve(r);
    }

    @PluginMethod
    public void init(final PluginCall call) {
        runAsync(call, () -> {
            ensureInit();
            JSObject r = new JSObject();
            r.put("ready", true);
            call.resolve(r);
        });
    }

    @PluginMethod
    public void updateEngine(final PluginCall call) {
        runAsync(call, () -> {
            ensureInit();
            JSObject r = new JSObject();
            try {
                YoutubeDL.UpdateStatus status = YoutubeDL.getInstance()
                        .updateYoutubeDL(getContext(), YoutubeDL.UpdateChannel.STABLE.INSTANCE);
                r.put("updated", status == YoutubeDL.UpdateStatus.DONE);
                r.put("status", String.valueOf(status));
            } catch (Throwable t) {
                r.put("updated", false);
                r.put("status", "error");
                r.put("message", fullMessage(t));
            }
            call.resolve(r);
        });
    }

    /* ---- clipboard ----------------------------------------------------- */

    @PluginMethod
    public void getClipboard(final PluginCall call) {
        final Activity activity = getActivity();
        if (activity == null) {
            JSObject r = new JSObject();
            r.put("text", "");
            call.resolve(r);
            return;
        }
        activity.runOnUiThread(() -> {
            String text = "";
            try {
                ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence cs = clip.getItemAt(0).coerceToText(activity);
                        if (cs != null) text = cs.toString();
                    }
                }
                JSObject r = new JSObject();
                r.put("text", text);
                call.resolve(r);
            } catch (Throwable t) {
                JSObject r = new JSObject();
                r.put("text", "");
                call.resolve(r);
            }
        });
    }

    /* ---- storage access ------------------------------------------------ */

    @PluginMethod
    public void storageStatus(PluginCall call) {
        File primary = downloadDir();
        JSObject r = new JSObject();
        boolean granted = hasAllFiles();
        r.put("granted", granted);
        r.put("publicPath", publicDir().getAbsolutePath());
        r.put("privatePath", privateDir().getAbsolutePath());
        r.put("dir", primary.getAbsolutePath());
        r.put("usingPublic", granted && primary.getAbsolutePath().equals(publicDir().getAbsolutePath()));
        call.resolve(r);
    }

    @PluginMethod
    public void requestStorage(PluginCall call) {
        try {
            if (hasAllFiles()) {
                JSObject r = new JSObject();
                r.put("requested", false);
                r.put("granted", true);
                call.resolve(r);
                return;
            }
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            JSObject r = new JSObject();
            r.put("requested", true);
            call.resolve(r);
        } catch (Throwable t) {
            try {
                Intent fallback = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                getActivity().startActivity(fallback);
                JSObject r = new JSObject();
                r.put("requested", true);
                call.resolve(r);
            } catch (Throwable t2) {
                call.reject("Could not open storage settings: " + fullMessage(t2));
            }
        }
    }

    @PluginMethod
    public void openFolder(final PluginCall call) {
        try {
            File dir = downloadDir();
            if (!dir.isDirectory()) dir.mkdirs();
            String rel = "Download/" + dir.getName();
            Uri uri = Uri.parse("content://com.android.externalstorage.documents/document/"
                    + Uri.encode("primary:" + rel));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getContext().startActivity(i);
            call.resolve();
        } catch (Throwable t) {
            call.reject("Could not open the folder. Path: " + downloadDir().getAbsolutePath());
        }
    }

    /* ---- cookies ------------------------------------------------------- */

    private File cookiesFile() {
        return new File(getContext().getFilesDir(), "cookies.txt");
    }

    @PluginMethod
    public void cookiesStatus(PluginCall call) {
        File f = cookiesFile();
        JSObject r = new JSObject();
        r.put("exists", f.isFile() && f.length() > 0);
        r.put("size", f.isFile() ? f.length() : 0);
        call.resolve(r);
    }

    @PluginMethod
    public void pickCookies(PluginCall call) {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(call, intent, "cookiesPicked");
        } catch (Throwable t) {
            call.reject(fullMessage(t));
        }
    }

    @ActivityCallback
    private void cookiesPicked(PluginCall call, ActivityResult result) {
        if (call == null) return;
        try {
            if (result == null || result.getResultCode() != Activity.RESULT_OK
                    || result.getData() == null || result.getData().getData() == null) {
                call.reject("cancelled");
                return;
            }
            Uri uri = result.getData().getData();
            File tmp = new File(getContext().getFilesDir(), "cookies.tmp");
            long total = 0;
            try (InputStream in = getContext().getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) throw new IOException("Could not read the selected file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > 8L * 1024L * 1024L) throw new IOException("Cookies file is too large (max 8 MB)");
                    out.write(buf, 0, n);
                }
            }
            if (total == 0) throw new IOException("The selected file is empty");
            File dest = cookiesFile();
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                copyFile(tmp, dest);
                tmp.delete();
            }
            JSObject r = new JSObject();
            r.put("exists", true);
            r.put("size", dest.length());
            call.resolve(r);
        } catch (Throwable t) {
            call.reject(fullMessage(t));
        }
    }

    @PluginMethod
    public void clearCookies(PluginCall call) {
        File f = cookiesFile();
        boolean ok = !f.exists() || f.delete();
        JSObject r = new JSObject();
        r.put("cleared", ok);
        call.resolve(r);
    }

    /* ---- share intent -------------------------------------------------- */

    @PluginMethod
    public void getSharedUrl(PluginCall call) {
        String url = pendingShare;
        pendingShare = null;
        JSObject r = new JSObject();
        r.put("url", url == null ? "" : url);
        call.resolve(r);
    }

    /* ---- media info ---------------------------------------------------- */

    @PluginMethod
    public void getInfo(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url required");
            return;
        }
        runAsync(call, () -> {
            ensureInit();
            YoutubeDLRequest request = new YoutubeDLRequest(url);
            request.addOption("--no-playlist");
            addCookies(request);
            VideoInfo info = YoutubeDL.getInstance().getInfo(request);
            JSObject r = new JSObject();
            r.put("title", info.getTitle() == null ? info.getFulltitle() : info.getTitle());
            r.put("thumbnail", info.getThumbnail());
            r.put("duration", info.getDuration());
            r.put("uploader", info.getUploader());
            r.put("extractor", info.getExtractor());
            call.resolve(r);
        });
    }

    /* ---- download entry points ---------------------------------------- */

    private static final class CanceledDownload extends Exception {
        CanceledDownload() {
            super("cancelled");
        }
    }

    private static final class DownloadResult {
        final File file;
        final int threads;

        DownloadResult(File file, int threads) {
            this.file = file;
            this.threads = threads;
        }
    }

    @PluginMethod
    public void downloadAuto(final PluginCall call) {
        final String url = call.getString("url") == null ? "" : call.getString("url").trim();
        if (url.isEmpty()) {
            call.reject("url required");
            return;
        }
        final String id = call.getString("id", String.valueOf(System.currentTimeMillis()));
        final String mode = call.getString("mode", "video");
        final String quality = call.getString("quality", "best");
        final int threads = clamp(call.getInt("threads", 8), 1, MAX_THREADS);

        runAsync(call, () -> {
            CANCELLED.remove(id);
            ensureInit();
            File dir = downloadDir();

            Probe probe = null;
            boolean knownSite = isKnownSite(url);
            if (!knownSite) {
                try {
                    probe = probe(url);
                } catch (Throwable t) {
                    Log.w("DownloaderPlugin", "probe failed: " + fullMessage(t));
                }
            }

            boolean direct = !knownSite
                    && ((probe != null && probe.isDownloadable()) || looksLikeDirectUrl(url));

            if (direct) {
                try {
                    DownloadResult r = directDownload(url, probe, dir, id, threads, null);
                    finish(call, id, r);
                    return;
                } catch (CanceledDownload ce) {
                    cancelResult(call, id);
                    return;
                } catch (Throwable t) {
                    if (CANCELLED.contains(id)) {
                        cancelResult(call, id);
                        return;
                    }
                    Log.w("DownloaderPlugin", "direct download failed, trying yt-dlp: " + fullMessage(t));
                }
            }

            try {
                DownloadResult r = ytdlpDownload(url, dir, id, mode, quality, threads);
                finish(call, id, r);
            } catch (CanceledDownload ce) {
                cancelResult(call, id);
            } catch (Throwable t) {
                if (CANCELLED.contains(id)) {
                    cancelResult(call, id);
                    return;
                }
                // Last-chance direct attempt for links that don't look like a known site.
                if (!knownSite && !direct && probe != null && probe.isDownloadable()) {
                    try {
                        DownloadResult r = directDownload(url, probe, dir, id, threads, null);
                        finish(call, id, r);
                        return;
                    } catch (CanceledDownload ce) {
                        cancelResult(call, id);
                        return;
                    } catch (Throwable t2) {
                        fail(call, id, t2);
                        return;
                    }
                }
                fail(call, id, t);
            }
        });
    }

    /** Backwards-compatible alias used by older web bundles. */
    @PluginMethod
    public void download(PluginCall call) {
        downloadAuto(call);
    }

    private void finish(PluginCall call, String id, DownloadResult r) {
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("success", true);
        d.put("path", r.file.getAbsolutePath());
        d.put("name", r.file.getName());
        d.put("size", r.file.length());
        d.put("threads", r.threads);
        emit("completed", d);
        call.resolve(d);
    }

    private void cancelResult(PluginCall call, String id) {
        emitCancelled(id);
        call.reject("cancelled");
    }

    private void fail(PluginCall call, String id, Throwable t) {
        String msg = fullMessage(t);
        Log.e("DownloaderPlugin", "download failed", t);
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("message", msg);
        emit("error", d);
        call.reject(msg);
    }

    private void emitCancelled(final String id) {
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("cancelled", true);
        emit("cancelled", d);
    }

    @PluginMethod
    public void cancel(final PluginCall call) {
        final String id = call.getString("id");
        boolean ok = false;
        if (id != null && !id.isEmpty()) {
            CANCELLED.add(id);
            try {
                ok = YoutubeDL.getInstance().destroyProcessById(id);
            } catch (Throwable ignored) {
            }
        }
        JSObject r = new JSObject();
        r.put("cancelled", ok || (id != null && !id.isEmpty()));
        call.resolve(r);
    }

    /* ---- yt-dlp path --------------------------------------------------- */

    private void addCookies(YoutubeDLRequest request) {
        File cookies = cookiesFile();
        if (cookies.isFile() && cookies.length() > 0) {
            request.addOption("--cookies", cookies.getAbsolutePath());
        }
    }

    private static String videoFormat(String quality) {
        String cap;
        switch (quality) {
            case "1080": cap = "[height<=1080]"; break;
            case "720": cap = "[height<=720]"; break;
            case "480": cap = "[height<=480]"; break;
            case "360": cap = "[height<=360]"; break;
            default: cap = ""; break;
        }
        if (cap.isEmpty()) {
            return "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b";
        }
        return "bv*" + cap + "[ext=mp4]+ba[ext=m4a]/b" + cap + "[ext=mp4]/bv*" + cap + "+ba/b" + cap;
    }

    private void addCommon(YoutubeDLRequest request, String outputTemplate, int threads) {
        request.addOption("-o", outputTemplate);
        request.addOption("--no-playlist");
        request.addOption("--newline");
        request.addOption("--no-mtime");
        request.addOption("--retries", 10);
        request.addOption("--fragment-retries", 10);
        request.addOption("--socket-timeout", 30);
        request.addOption("--concurrent-fragments", String.valueOf(threads));
        request.addOption("--extractor-args", "youtube:player_client=default");
        addCookies(request);
    }

    private YoutubeDLResponse execDownload(String url, String outputTemplate, String mode,
                                           String quality, boolean retry, String id,
                                           int threads, Function3<Float, Long, String, Unit> cb)
            throws Exception {
        YoutubeDLRequest request = new YoutubeDLRequest(url);
        addCommon(request, outputTemplate, threads);
        if (retry) {
            request.addOption("--force-ipv4");
            request.addOption("--extractor-args", "youtube:player_client=android,default");
        }
        if ("audio".equals(mode)) {
            request.addOption("-f", "ba/b");
            request.addOption("-x");
            request.addOption("--audio-format", "mp3");
            request.addOption("--audio-quality", "0");
        } else {
            request.addOption("-f", videoFormat(quality));
            request.addOption("--merge-output-format", "mp4");
        }
        return YoutubeDL.getInstance().execute(request, id, cb);
    }

    private DownloadResult ytdlpDownload(String url, File dir, String id, String mode,
                                         String quality, int threads) throws Exception {
        File jobDir = new File(dir, ".job_" + sanitizeName(id));
        if (jobDir.exists()) deleteRecursive(jobDir);
        if (!jobDir.mkdirs() && !jobDir.isDirectory()) {
            throw new IOException("Could not create download folder: " + jobDir.getAbsolutePath());
        }
        final String template = jobDir.getAbsolutePath() + "/%(title).100s [%(id)s].%(ext)s";

        final long[] lastEmit = {0L};
        Function3<Float, Long, String, Unit> cb = (progress, eta, line) -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastEmit[0] >= 250 || progress >= 100 || progress < 0) {
                lastEmit[0] = now;
                long speed = parseSpeed(line);
                long total = parseTotal(line);
                long bytes = (total > 0 && progress >= 0)
                        ? Math.round(total * (progress / 100.0)) : -1;
                emitProgress(id, progress, eta, bytes, total, speed);
            }
            return Unit.INSTANCE;
        };

        YoutubeDLResponse response = null;
        try {
            response = execDownload(url, template, mode, quality, false, id, threads, cb);
        } catch (YoutubeDL.CanceledException ce) {
            deleteRecursive(jobDir);
            throw new CanceledDownload();
        } catch (Exception e) {
            if (CANCELLED.contains(id)) {
                deleteRecursive(jobDir);
                throw new CanceledDownload();
            }
            Log.w("DownloaderPlugin", "download attempt 1 failed: " + fullMessage(e));
            try {
                response = execDownload(url, template, mode, quality, true, id, threads, cb);
            } catch (YoutubeDL.CanceledException ce) {
                deleteRecursive(jobDir);
                throw new CanceledDownload();
            } catch (Exception e2) {
                deleteRecursive(jobDir);
                throw e2;
            }
        }

        if (CANCELLED.contains(id)) {
            deleteRecursive(jobDir);
            throw new CanceledDownload();
        }

        File result = moveProducedFiles(jobDir, dir);
        if (result == null) {
            throw new IOException("The engine finished but produced no file. The link may be private or unsupported.");
        }
        return new DownloadResult(result, threads);
    }

    private File moveProducedFiles(File jobDir, File dir) {
        File[] files = jobDir.listFiles();
        File best = null;
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    deleteRecursive(f);
                    continue;
                }
                if (isTempName(f.getName())) {
                    f.delete();
                    continue;
                }
                File target = uniqueFile(dir, f.getName());
                boolean moved = f.renameTo(target);
                if (!moved) {
                    try {
                        copyFile(f, target);
                        f.delete();
                        moved = true;
                    } catch (Throwable t) {
                        Log.w("DownloaderPlugin", "could not move " + f, t);
                    }
                }
                if (moved && (best == null || target.length() > best.length())) best = target;
            }
        }
        deleteRecursive(jobDir);
        return best;
    }

    /* ---- direct multi-thread HTTP engine ------------------------------- */

    private static final class Probe {
        String url;
        String contentType;
        String contentDisposition;
        long length = -1;
        boolean ranges = false;

        boolean isHtmlLike() {
            String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            return ct.contains("text/html") || ct.contains("application/xhtml")
                    || ct.contains("application/json") || ct.contains("text/xml")
                    || ct.contains("application/xml");
        }

        boolean isDownloadable() {
            if (isHtmlLike()) return false;
            String ct = contentType == null ? "" : contentType.toLowerCase(Locale.US);
            if (ct.startsWith("video/") || ct.startsWith("audio/") || ct.startsWith("image/")) return true;
            if (ct.contains("octet-stream") || ct.contains("zip") || ct.contains("pdf")
                    || ct.contains("android.package") || ct.contains("x-rar")
                    || ct.contains("x-7z") || ct.contains("msword") || ct.contains("officedocument")) {
                return true;
            }
            return looksLikeDirectUrl(url);
        }
    }

    private static boolean looksLikeDirectUrl(String url) {
        try {
            String path = new URL(url).getPath();
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) return false;
            String ext = path.substring(dot + 1).toLowerCase(Locale.US);
            return DIRECT_EXTENSIONS.contains(ext);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isKnownSite(String url) {
        try {
            String host = new URL(url).getHost().toLowerCase(Locale.US);
            for (String site : KNOWN_SITES) {
                if (host.equals(site) || host.endsWith("." + site)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private HttpURLConnection openConnection(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(true);
        c.setConnectTimeout(30000);
        c.setReadTimeout(45000);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept", "*/*");
        return c;
    }

    private Probe probe(String url) throws IOException {
        HttpURLConnection c = openConnection(url);
        try {
            c.setRequestProperty("Range", "bytes=0-0");
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code == 405 || code == 501) {
                c.disconnect();
                c = openConnection(url);
                c.setRequestProperty("Range", "bytes=0-0");
                c.setRequestProperty("Accept-Encoding", "identity");
                code = c.getResponseCode();
            }
            if (code < 200 || code >= 400) {
                throw new IOException("HTTP " + code);
            }
            Probe p = new Probe();
            p.url = c.getURL().toString();
            p.contentType = c.getContentType();
            p.contentDisposition = c.getHeaderField("Content-Disposition");
            long len = c.getContentLengthLong();
            if (code == 206) {
                String cr = c.getHeaderField("Content-Range");
                if (cr != null) {
                    int slash = cr.lastIndexOf('/');
                    if (slash > 0 && slash < cr.length() - 1) {
                        try {
                            len = Long.parseLong(cr.substring(slash + 1).trim());
                        } catch (Throwable ignored) {
                        }
                    }
                }
                p.ranges = len > 0;
            } else {
                String ar = c.getHeaderField("Accept-Ranges");
                p.ranges = ar != null && ar.toLowerCase(Locale.US).contains("bytes");
            }
            p.length = len;
            return p;
        } finally {
            c.disconnect();
        }
    }

    private DownloadResult directDownload(String url, Probe p, File dir, String id,
                                          int threads, String preferredName) throws Exception {
        if (p == null) p = probe(url);
        if (p == null || !p.isDownloadable()) {
            throw new IOException("This link is not a direct file");
        }
        String name = preferredName != null && !preferredName.trim().isEmpty()
                ? preferredName
                : fileNameFor(p);
        name = ensureExtension(name, p.contentType, p.url);
        File out = uniqueFile(dir, name);
        File part = new File(out.getAbsolutePath() + ".part");
        if (part.exists()) part.delete();

        int n = clamp(threads, 1, MAX_THREADS);
        boolean split = p.ranges && p.length > 2L * 1024L * 1024L && n > 1;
        emitStage(id, split ? ("Downloading with " + n + " connections") : "Downloading");
        if (split) {
            try {
                segmentedDownload(p, part, id, n);
            } catch (CanceledDownload ce) {
                part.delete();
                throw ce;
            } catch (Throwable t) {
                if (CANCELLED.contains(id)) {
                    part.delete();
                    throw new CanceledDownload();
                }
                Log.w("DownloaderPlugin", "multi-thread failed, retrying single: " + fullMessage(t));
                part.delete();
                emitStage(id, "Retrying with one connection");
                singleStream(p, part, id);
            }
        } else {
            try {
                singleStream(p, part, id);
            } catch (CanceledDownload ce) {
                part.delete();
                throw ce;
            } catch (Throwable t) {
                part.delete();
                throw t;
            }
        }

        if (CANCELLED.contains(id)) {
            part.delete();
            throw new CanceledDownload();
        }
        if (!part.renameTo(out)) {
            copyFile(part, out);
            part.delete();
        }
        return new DownloadResult(out, split ? n : 1);
    }

    private void segmentedDownload(Probe p, File part, String id, int n) throws Exception {
        final long total = p.length;
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        raf.setLength(total);
        raf.close();

        final AtomicLong done = new AtomicLong(0);
        final AtomicBoolean stop = new AtomicBoolean(false);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        final List<Thread> workers = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            final long start = total * i / n;
            final long end = (i == n - 1) ? total - 1 : (total * (i + 1) / n - 1);
            if (end < start) continue;
            Thread t = new Thread(() -> {
                HttpURLConnection c = null;
                try {
                    if (CANCELLED.contains(id)) throw new CanceledDownload();
                    c = openConnection(p.url);
                    c.setRequestProperty("Range", "bytes=" + start + "-" + end);
                    c.setRequestProperty("Accept-Encoding", "identity");
                    int code = c.getResponseCode();
                    if (code != 206) throw new IOException("Server refused range (HTTP " + code + ")");
                    try (InputStream in = c.getInputStream();
                         RandomAccessFile rf = new RandomAccessFile(part, "rw")) {
                        rf.seek(start);
                        byte[] buf = new byte[65536];
                        long pos = start;
                        int read;
                        while (pos <= end && (read = in.read(buf)) > 0) {
                            if (stop.get() || CANCELLED.contains(id)) throw new CanceledDownload();
                            int allowed = (int) Math.min(read, end - pos + 1);
                            rf.write(buf, 0, allowed);
                            pos += allowed;
                            done.addAndGet(allowed);
                            if (allowed < read) break;
                        }
                        if (pos <= end) throw new IOException("Range ended early");
                    }
                } catch (Throwable e) {
                    errors.add(e);
                    stop.set(true);
                } finally {
                    if (c != null) c.disconnect();
                }
            });
            t.start();
            workers.add(t);
        }

        Thread monitor = new Thread(() -> {
            long lastBytes = 0;
            long lastTime = SystemClock.elapsedRealtime();
            while (!stop.get() && !CANCELLED.contains(id)) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    break;
                }
                long now = SystemClock.elapsedRealtime();
                long bytes = done.get();
                long speed = now > lastTime ? (long) ((bytes - lastBytes) * 1000.0 / (now - lastTime)) : 0;
                lastBytes = bytes;
                lastTime = now;
                emitProgress(id, total > 0 ? bytes * 100.0 / total : -1, -1, bytes, total, speed);
                boolean alive = false;
                for (Thread t : workers) {
                    if (t.isAlive()) {
                        alive = true;
                        break;
                    }
                }
                if (!alive) break;
            }
        });
        monitor.start();

        for (Thread t : workers) t.join();
        stop.set(true);
        monitor.interrupt();
        monitor.join(500);

        if (CANCELLED.contains(id)) throw new CanceledDownload();
        if (!errors.isEmpty()) {
            Throwable real = null;
            for (Throwable e : errors) {
                if (!(e instanceof CanceledDownload)) {
                    real = e;
                    break;
                }
            }
            if (real == null) real = errors.get(0);
            throw new IOException("Multi-thread download failed: " + fullMessage(real));
        }
        if (done.get() < total) {
            throw new IOException("Only " + done.get() + " of " + total + " bytes were downloaded");
        }
        emitProgress(id, 100, 0, total, total, 0);
    }

    private void singleStream(Probe p, File part, String id) throws Exception {
        HttpURLConnection c = openConnection(p.url);
        try {
            c.setRequestProperty("Accept-Encoding", "identity");
            int code = c.getResponseCode();
            if (code < 200 || code >= 400) throw new IOException("HTTP " + code);
            long total = c.getContentLengthLong();
            if (total <= 0) total = p.length;
            long got = 0;
            long lastBytes = 0;
            long lastTime = SystemClock.elapsedRealtime();
            try (InputStream in = c.getInputStream();
                 OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[65536];
                int read;
                while ((read = in.read(buf)) > 0) {
                    if (CANCELLED.contains(id)) throw new CanceledDownload();
                    out.write(buf, 0, read);
                    got += read;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastTime >= 300) {
                        long speed = (long) ((got - lastBytes) * 1000.0 / (now - lastTime));
                        lastBytes = got;
                        lastTime = now;
                        emitProgress(id, total > 0 ? got * 100.0 / total : -1,
                                speed > 0 ? (long) ((total - got) / (double) speed) : -1,
                                got, total, speed);
                    }
                }
            }
            if (total > 0 && got != total) {
                throw new IOException("Incomplete download: " + got + " of " + total + " bytes");
            }
            emitProgress(id, 100, 0, got, total, 0);
        } finally {
            c.disconnect();
        }
    }

    /* ---- file naming --------------------------------------------------- */

    private static String fileNameFor(Probe p) {
        String cd = p.contentDisposition;
        if (cd != null) {
            Matcher m = FILENAME_STAR_RE.matcher(cd);
            if (m.find()) {
                try {
                    return URLDecoder.decode(m.group(1), "UTF-8");
                } catch (Throwable ignored) {
                }
            }
            m = FILENAME_RE.matcher(cd);
            if (m.find()) {
                String name = m.group(1).trim();
                if (name.toLowerCase(Locale.US).startsWith("utf-8''")) {
                    name = name.substring(7);
                }
                try {
                    return URLDecoder.decode(name, "UTF-8");
                } catch (Throwable ignored) {
                    return name;
                }
            }
        }
        try {
            String path = new URL(p.url).getPath();
            String last = path.substring(path.lastIndexOf('/') + 1);
            if (!last.isEmpty()) return URLDecoder.decode(last, "UTF-8");
        } catch (Throwable ignored) {
        }
        return "download";
    }

    private static String extensionFor(String contentType) {
        if (contentType == null) return "";
        String ct = contentType.toLowerCase(Locale.US);
        if (ct.contains("video/mp4")) return "mp4";
        if (ct.contains("video/webm")) return "webm";
        if (ct.contains("video/quicktime")) return "mov";
        if (ct.contains("video/x-matroska")) return "mkv";
        if (ct.contains("audio/mpeg")) return "mp3";
        if (ct.contains("audio/mp4") || ct.contains("audio/x-m4a")) return "m4a";
        if (ct.contains("audio/aac")) return "aac";
        if (ct.contains("audio/flac")) return "flac";
        if (ct.contains("audio/wav") || ct.contains("audio/x-wav")) return "wav";
        if (ct.contains("audio/ogg")) return "ogg";
        if (ct.contains("image/jpeg")) return "jpg";
        if (ct.contains("image/png")) return "png";
        if (ct.contains("image/gif")) return "gif";
        if (ct.contains("image/webp")) return "webp";
        if (ct.contains("application/pdf")) return "pdf";
        if (ct.contains("application/zip")) return "zip";
        if (ct.contains("android.package")) return "apk";
        return "";
    }

    private static String ensureExtension(String name, String contentType, String url) {
        name = sanitizeName(name);
        int dot = name.lastIndexOf('.');
        boolean hasExt = dot > 0 && name.length() - dot <= 6;
        if (hasExt) return name;
        String ext = extensionFor(contentType);
        if (ext.isEmpty()) {
            try {
                String path = new URL(url).getPath();
                int pdot = path.lastIndexOf('.');
                if (pdot > 0 && path.length() - pdot <= 6) {
                    ext = path.substring(pdot + 1).toLowerCase(Locale.US);
                }
            } catch (Throwable ignored) {
            }
        }
        return ext.isEmpty() ? name : name + "." + ext;
    }

    private static String sanitizeName(String raw) {
        if (raw == null) raw = "";
        String name = raw.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_")
                .replaceAll("\\s+", " ")
                .trim();
        while (name.startsWith(".")) name = name.substring(1);
        if (name.length() > 115) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 && name.length() - dot <= 6 ? name.substring(dot) : "";
            name = name.substring(0, Math.max(1, 115 - ext.length())).trim() + ext;
        }
        return name.isEmpty() ? "download" : name;
    }

    private static File uniqueFile(File dir, String rawName) {
        String name = sanitizeName(rawName);
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            File c = new File(dir, base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    /* ---- progress events ----------------------------------------------- */

    private void emitProgress(final String id, final double pct, final long eta,
                              final long bytes, final long total, final long speed) {
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("progress", pct);
        d.put("eta", eta);
        d.put("bytes", bytes);
        d.put("total", total);
        d.put("speed", speed);
        d.put("line", "");
        emit("progress", d);
    }

    private void emitStage(final String id, final String stage) {
        JSObject d = new JSObject();
        d.put("id", id);
        d.put("stage", stage);
        emit("stage", d);
    }

    /* ---- remote resolver (optional fallback) --------------------------- */

    private static final String DEFAULT_RESOLVER = "https://gendownload.com";

    private static int labelHeight(String label) {
        if (label == null) return -1;
        String digits = label.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private JSONObject resolveRemote(String resolver, String url) throws Exception {
        URL endpoint = new URL(resolver.replaceAll("/+$", "") + "/api/extract");
        HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", UA);
        byte[] body = ("{\"url\":" + JSONObject.quote(url) + "}").getBytes("UTF-8");
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body);
        }
        int code = conn.getResponseCode();
        String resp = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
        conn.disconnect();
        if (code < 200 || code >= 300) {
            throw new IOException("resolver HTTP " + code + ": "
                    + (resp.length() > 200 ? resp.substring(0, 200) : resp));
        }
        return new JSONObject(resp);
    }

    private static JSONObject pickRemote(JSONObject info, String mode, String quality) throws Exception {
        JSONArray formats = info.optJSONArray("formats");
        if (formats == null || formats.length() == 0) {
            throw new Exception("resolver returned no formats");
        }
        JSONObject audioFallback = null;
        JSONObject videoBest = null;
        JSONObject videoAnyBest = null;
        int videoBestH = -1;
        int videoAnyBestH = -1;
        int wantH = labelHeight(quality);

        for (int i = 0; i < formats.length(); i++) {
            JSONObject f = formats.getJSONObject(i);
            String type = f.optString("type", "");
            if ("audio".equals(type)) {
                if (audioFallback == null || "mp3".equalsIgnoreCase(f.optString("ext"))) audioFallback = f;
                continue;
            }
            if (!"video".equals(type)) continue;
            int h = labelHeight(f.optString("label", ""));
            if (h > videoAnyBestH) {
                videoAnyBestH = h;
                videoAnyBest = f;
            }
            if (wantH <= 0 || h <= wantH) {
                if (h > videoBestH) {
                    videoBestH = h;
                    videoBest = f;
                }
            }
        }
        if ("audio".equals(mode)) {
            if (audioFallback == null) throw new Exception("no audio format available");
            return audioFallback;
        }
        if (videoBest != null) return videoBest;
        if (videoAnyBest != null) return videoAnyBest;
        throw new Exception("no video format available");
    }

    @PluginMethod
    public void downloadRemote(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.trim().isEmpty()) {
            call.reject("url required");
            return;
        }
        final String id = call.getString("id", String.valueOf(System.currentTimeMillis()));
        final String mode = call.getString("mode", "video");
        final String quality = call.getString("quality", "best");
        final String resolver = call.getString("resolver", DEFAULT_RESOLVER);
        final int threads = clamp(call.getInt("threads", 8), 1, MAX_THREADS);

        runAsync(call, () -> {
            JSONObject info = resolveRemote(resolver, url);
            JSONObject chosen = pickRemote(info, mode, quality);
            String mediaUrl = chosen.getString("url");
            String ext = chosen.optString("ext", "audio".equals(mode) ? "mp3" : "mp4");
            String title = sanitizeName(info.optString("title", "download"));
            String preferred = title.toLowerCase(Locale.US).endsWith("." + ext.toLowerCase(Locale.US))
                    ? title : title + "." + ext;
            DownloadResult r = directDownload(mediaUrl, null, downloadDir(), id, threads, preferred);
            finish(call, id, r);
        });
    }

    /* ---- file actions -------------------------------------------------- */

    @PluginMethod
    public void listFiles(PluginCall call) {
        JSArray arr = new JSArray();
        File primary = downloadDir();
        for (File f : allMediaFiles()) {
            JSObject o = new JSObject();
            o.put("name", f.getName());
            o.put("path", f.getAbsolutePath());
            o.put("size", f.length());
            o.put("date", f.lastModified());
            o.put("mime", mimeFor(f));
            o.put("dir", f.getParent());
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("files", arr);
        r.put("dir", primary.getAbsolutePath());
        r.put("publicPath", publicDir().getAbsolutePath());
        r.put("granted", hasAllFiles());
        r.put("usingPublic", hasAllFiles() && primary.getAbsolutePath().equals(publicDir().getAbsolutePath()));
        call.resolve(r);
    }

    @PluginMethod
    public void deleteFile(final PluginCall call) {
        final String path = call.getString("path");
        if (path == null) {
            call.reject("path required");
            return;
        }
        try {
            File f = new File(path);
            if (!isInsideDownloadDirs(f)) {
                call.reject("refused: outside download folders");
                return;
            }
            boolean ok = f.exists() && f.delete();
            JSObject r = new JSObject();
            r.put("deleted", ok);
            call.resolve(r);
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void openFile(final PluginCall call) {
        final String path = call.getString("path");
        if (path == null) {
            call.reject("path required");
            return;
        }
        try {
            File f = new File(path);
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mimeFor(f));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void shareFile(final PluginCall call) {
        final String path = call.getString("path");
        if (path == null) {
            call.reject("path required");
            return;
        }
        try {
            File f = new File(path);
            Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(mimeFor(f));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "Share");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(chooser);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    @PluginMethod
    public void openExternal(final PluginCall call) {
        final String url = call.getString("url");
        if (url == null || url.isEmpty()) {
            call.reject("url required");
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject(e.getMessage());
        }
    }

    /* ---- utilities ----------------------------------------------------- */

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursive(c);
            }
        }
        f.delete();
    }

    /* ---- event emission ------------------------------------------------ */

    private void emit(final String event, final JSObject data) {
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            if (getActivity() != null) notifyListeners(event, data);
        });
    }

    public static void emitShareToJs(final String url) {
        DownloaderPlugin p = instance;
        if (p == null) return;
        JSObject d = new JSObject();
        d.put("url", url);
        p.emit("sharedUrl", d);
    }
}
