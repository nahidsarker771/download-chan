/* Download Chan 2.0 — UI + download orchestration */
(function () {
  "use strict";

  var APP_VERSION = "2.1.1";
  var DEFAULT_RESOLVER = "https://gendownload.com";
  var AUTO_DEDUPE_MS = 24 * 60 * 60 * 1000;

  var settings = {
    mode: "video",
    quality: "best",
    threads: 8,
    autoClipboard: true,
    onlineFallback: false,
    resolver: DEFAULT_RESOLVER
  };

  var state = {
    active: null,
    queue: [],
    lastErrorJob: null,
    files: [],
    storage: null,
    cookies: null,
    clipboardUrl: "",
    lastAutoUrl: "",
    lastAutoAt: 0,
    engineReady: false,
    booted: false,
    checkingClipboard: false
  };

  var jobEls = null;
  var toastTimer = null;

  function $(sel) { return document.querySelector(sel); }
  function $$(sel) { return Array.prototype.slice.call(document.querySelectorAll(sel)); }

  function el(tag, cls, html) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (html != null) e.innerHTML = html;
    return e;
  }

  function esc(s) {
    return String(s == null ? "" : s)
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
  }

  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

  function errText(err) {
    if (!err) return "Unknown error";
    if (typeof err === "string") return err;
    return String(err.message || err.errorMessage || err.error || err);
  }

  function firstUrl(text) {
    var s = String(text || "").trim();
    if (!s) return "";
    var m = s.match(/https?:\/\/[^\s<>"']+/i);
    if (!m) m = s.match(/www\.[^\s<>"']+/i);
    if (!m) m = s.match(/(?:[a-z0-9-]+\.)+[a-z]{2,}\/[^\s<>"']+/i);
    if (!m) return "";
    var url = m[0].replace(/[)\].,;!?]+$/, "");
    if (!/^https?:\/\//i.test(url)) url = "https://" + url;
    return url;
  }

  function hostOf(url) {
    try { return new URL(url).hostname.replace(/^www\./i, ""); }
    catch (e) { return String(url || "").slice(0, 40); }
  }

  function shortUrl(url) {
    try {
      var u = new URL(url);
      var path = u.pathname && u.pathname !== "/" ? u.pathname.slice(0, 30) : "";
      return u.hostname.replace(/^www\./i, "") + path;
    } catch (e) {
      var s = String(url || "");
      return s.length > 44 ? s.slice(0, 44) + "…" : s;
    }
  }

  function fileNameOf(url) {
    try {
      var parts = new URL(url).pathname.split("/").filter(Boolean);
      var last = decodeURIComponent(parts.length ? parts[parts.length - 1] : "");
      return /\.[a-z0-9]{2,5}$/i.test(last) ? last : "";
    } catch (e) { return ""; }
  }

  function jobTitle(job) {
    return job.name || fileNameOf(job.url) || hostOf(job.url);
  }

  function fmtBytes(b) {
    if (b == null || isNaN(b) || b < 0) return "";
    if (b < 1024) return Math.round(b) + " B";
    if (b < 1048576) return (b / 1024).toFixed(1) + " KB";
    if (b < 1073741824) return (b / 1048576).toFixed(1) + " MB";
    return (b / 1073741824).toFixed(2) + " GB";
  }

  function fmtSpeed(bps) {
    if (!bps || bps <= 0) return "";
    return fmtBytes(bps) + "/s";
  }

  function fmtEta(sec) {
    var s = Math.round(Number(sec) || 0);
    if (s <= 0) return "";
    if (s < 60) return s + "s";
    if (s < 3600) return Math.floor(s / 60) + "m " + (s % 60) + "s";
    return Math.floor(s / 3600) + "h " + Math.floor((s % 3600) / 60) + "m";
  }

  function fmtDate(ms) {
    try {
      var d = new Date(ms);
      var now = new Date();
      var sameDay = d.toDateString() === now.toDateString();
      return sameDay
        ? d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })
        : d.toLocaleDateString([], { month: "short", day: "numeric" });
    } catch (e) { return ""; }
  }

  function toast(msg) {
    var t = $("#toast");
    if (!t) return;
    t.textContent = msg;
    t.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.classList.remove("show"); }, 2600);
  }

  /* ---------------- settings ---------------- */

  function loadSettings() {
    try {
      var s = JSON.parse(localStorage.getItem("dc_settings") || "{}");
      if (s.mode === "video" || s.mode === "audio") settings.mode = s.mode;
      if (["best", "1080", "720", "480", "360"].indexOf(s.quality) >= 0) settings.quality = s.quality;
      if (s.threads) settings.threads = clamp(parseInt(s.threads, 10) || 8, 1, 16);
      if (typeof s.autoClipboard === "boolean") settings.autoClipboard = s.autoClipboard;
      if (typeof s.onlineFallback === "boolean") settings.onlineFallback = s.onlineFallback;
      if (s.resolver) settings.resolver = s.resolver;
    } catch (e) {}
    try {
      state.lastAutoUrl = localStorage.getItem("dc_last_auto_url") || "";
      state.lastAutoAt = parseInt(localStorage.getItem("dc_last_auto_at") || "0", 10) || 0;
    } catch (e) {}
  }

  function saveSettings() {
    try { localStorage.setItem("dc_settings", JSON.stringify(settings)); } catch (e) {}
  }

  function syncSettingsUI() {
    $$("#mode-seg .seg-btn").forEach(function (b) {
      b.classList.toggle("active", b.dataset.mode === settings.mode);
    });
    $$("#def-mode-seg .seg-btn").forEach(function (b) {
      b.classList.toggle("active", b.dataset.defmode === settings.mode);
    });
    $("#quality").value = settings.quality;
    $("#def-quality").value = settings.quality;
    $("#auto-clip").checked = !!settings.autoClipboard;
    $("#threads").value = settings.threads;
    $("#remote-on").checked = !!settings.onlineFallback;
    $("#resolver").value = settings.resolver || DEFAULT_RESOLVER;
    $("#resolver-wrap").classList.toggle("hidden", !settings.onlineFallback);
  }

  function setThreads(v) {
    var n = parseInt(v, 10);
    if (isNaN(n)) n = settings.threads;
    n = clamp(n, 1, 16);
    settings.threads = n;
    $("#threads").value = n;
    saveSettings();
  }

  function rememberAuto(url) {
    state.lastAutoUrl = url;
    state.lastAutoAt = Date.now();
    try {
      localStorage.setItem("dc_last_auto_url", url);
      localStorage.setItem("dc_last_auto_at", String(state.lastAutoAt));
    } catch (e) {}
  }

  /* ---------------- tabs ---------------- */

  function switchTab(name) {
    $$(".tab").forEach(function (t) { t.classList.toggle("active", t.id === "tab-" + name); });
    $$(".tabbtn").forEach(function (b) { b.classList.toggle("active", b.dataset.tab === name); });
    window.scrollTo(0, 0);
  }

  /* ---------------- clipboard ---------------- */

  function readClipboardText() {
    if (window.DC.available && window.DC.getClipboard) {
      return window.DC.getClipboard().then(function (r) {
        var text = (r && r.text) || "";
        if (text) return text;
        return webClipboard();
      }).catch(webClipboard);
    }
    return webClipboard();
  }

  function webClipboard() {
    if (navigator.clipboard && navigator.clipboard.readText) {
      return navigator.clipboard.readText().catch(function () { return ""; });
    }
    return Promise.resolve("");
  }

  function setInput(url) {
    $("#url").value = url;
  }

  function showClipBanner(url, auto) {
    var banner = $("#clip-banner");
    $("#clip-text").textContent = auto
      ? "Link captured — download started."
      : "Tap Download to save this link.";
    $("#clip-download").classList.toggle("hidden", !!auto);
    banner.classList.remove("hidden");
  }

  function hideClipBanner() {
    $("#clip-banner").classList.add("hidden");
  }

  function checkClipboard(autoStart) {
    if (state.checkingClipboard) return;
    state.checkingClipboard = true;
    readClipboardText().then(function (text) {
      state.checkingClipboard = false;
      var url = firstUrl(text);
      if (!url) {
        hideClipBanner();
        return;
      }
      setInput(url);
      state.clipboardUrl = url;
      var fresh = !(url === state.lastAutoUrl && (Date.now() - state.lastAutoAt) < AUTO_DEDUPE_MS);
      if (autoStart && settings.autoClipboard && fresh) {
        rememberAuto(url);
        showClipBanner(url, true);
        $("#status-line").textContent = "Link captured — starting…";
        setTimeout(function () {
          if (state.clipboardUrl === url) startDownload(url);
        }, 400);
      } else {
        showClipBanner(url, false);
      }
    }).catch(function () {
      state.checkingClipboard = false;
    });
  }

  /* ---------------- download jobs ---------------- */

  function startDownload(rawUrl) {
    var url = firstUrl(rawUrl) || String(rawUrl || "").trim();
    if (!/^https?:\/\//i.test(url)) {
      toast("That doesn't look like a link");
      return;
    }
    if (!window.DC.available) {
      toast("Native engine unavailable");
      return;
    }
    if (state.active && state.active.url === url && state.active.status === "downloading") {
      toast("Already downloading this link");
      switchTab("home");
      return;
    }
    if (state.queue.some(function (j) { return j.url === url; })) {
      toast("Already in the queue");
      switchTab("home");
      return;
    }

    state.lastErrorJob = null;
    rememberAuto(url);
    setInput(url);
    hideClipBanner();

    var job = {
      id: "j" + Date.now().toString(36) + Math.random().toString(36).slice(2, 6),
      url: url,
      mode: settings.mode,
      quality: settings.quality,
      threads: settings.threads,
      status: "queued",
      progress: -1,
      bytes: 0,
      total: 0,
      speed: 0,
      eta: -1,
      stage: "Queued",
      path: "",
      name: "",
      size: 0,
      error: "",
      remoteTried: false
    };

    if (state.active) {
      state.queue.push(job);
      renderJobs();
      toast("Added to queue");
    } else {
      runJob(job);
    }
    switchTab("home");
  }

  function runJob(job) {
    state.active = job;
    job.status = "downloading";
    job.stage = "Starting engine…";
    job.progress = -1;
    $("#status-line").textContent = "Downloading " + hostOf(job.url) + "…";
    renderJobs();

    var opts = {
      url: job.url,
      id: job.id,
      mode: job.mode,
      quality: job.quality,
      threads: job.threads
    };
    var runner = window.DC.downloadAuto || window.DC.download;
    runner(opts).then(function (res) {
      succeedJob(job, res);
    }).catch(function (err) {
      handleJobError(job, err);
    });
  }

  function handleJobError(job, err) {
    if (job.status === "cancelled") {
      finishActive();
      return;
    }
    var msg = errText(err);
    if (settings.onlineFallback && !job.remoteTried && window.DC.downloadRemote) {
      job.remoteTried = true;
      job.stage = "Local engine failed — trying online…";
      renderJobs();
      window.DC.downloadRemote({
        url: job.url,
        id: job.id,
        mode: job.mode,
        quality: job.quality,
        threads: job.threads,
        resolver: settings.resolver
      }).then(function (res) {
        succeedJob(job, res);
      }).catch(function (err2) {
        if (job.status === "cancelled") { finishActive(); return; }
        failJob(job, msg + "\n\nOnline resolver: " + errText(err2));
      });
    } else {
      failJob(job, msg);
    }
  }

  function succeedJob(job, res) {
    if (job.status === "cancelled") return;
    job.status = "done";
    job.progress = 100;
    job.stage = "";
    job.error = "";
    if (res) {
      job.path = res.path || job.path;
      job.name = res.name || job.name;
      job.size = res.size || job.size;
      if (res.threads) job.threads = res.threads;
    }
    state.lastErrorJob = null;
    $("#status-line").textContent = "Saved to Downloads";
    toast("Saved: " + (job.name || "file"));
    finishActive();
    refreshFiles();
  }

  function failJob(job, msg) {
    job.status = "error";
    job.error = explain(msg);
    job.stage = "";
    state.lastErrorJob = job;
    $("#status-line").textContent = "Download failed";
    toast("Download failed — open Details");
    finishActive();
  }

  function explain(msg) {
    var out = String(msg || "Download failed");
    if (/cancelled|canceled/i.test(out)) return out;
    if (/cannot parse data|login|sign in|requires authentication|private|cookies|not available/i.test(out)
        && !/cookies\.txt/i.test(out)) {
      out += "\n\nThis post probably needs a login. Import a cookies.txt in Settings, or try a public link.";
    } else if (/403|forbidden|not a bot|sign in to confirm/i.test(out)) {
      out += "\n\nThe site blocked this request. Try Settings → Update engine, or use a different link.";
    } else if (/unsupported|no video|no formats|no media/i.test(out)) {
      out += "\n\nThe link may be a page without a downloadable video, or the site changed. Updating the engine can help.";
    }
    return out;
  }

  function finishActive() {
    state.active = null;
    jobEls = null;
    if (state.queue.length) {
      var next = state.queue.shift();
      runJob(next);
    } else {
      renderJobs();
    }
  }

  function cancelJob(job) {
    job.status = "cancelled";
    try { window.DC.cancel({ id: job.id }); } catch (e) {}
    if (state.active === job) state.active = null;
    toast("Cancelled");
    $("#status-line").textContent = "Ready to save";
    finishActive();
  }

  function retryJob(job) {
    state.lastErrorJob = null;
    startDownload(job.url);
  }

  function processQueue() {
    if (!state.active && state.queue.length) {
      var next = state.queue.shift();
      runJob(next);
    }
  }

  /* ---------------- job rendering ---------------- */

  function jobStatusText(job) {
    if (job.status === "queued") return "Queued";
    if (job.status === "done") return "Saved";
    if (job.status === "error") return "Failed";
    if (job.stage) return job.stage;
    return job.progress >= 0 ? "Downloading" : "Starting…";
  }

  function qualityLabel(q) {
    return !q || q === "best" ? "Best available" : (q + "p or lower");
  }

  function parentDir(path) {
    var s = String(path || "");
    var i = s.lastIndexOf("/");
    return i > 0 ? s.slice(0, i) : s;
  }

  function firstLine(text) {
    return String(text || "").split("\n")[0];
  }

  function jobCard(job, current) {
    var done = job.status === "done";
    var err = job.status === "error";
    var queued = job.status === "queued";
    var active = current && job.status === "downloading";
    var ext = done && job.name ? extOf(job.name) : (job.mode === "audio" ? "MP3" : "MP4");
    var iconClass = done ? "done" : err ? "err" : (job.mode === "audio" ? "audio" : "");
    var title = jobTitle(job);
    var pct = job.progress < 0 ? 0 : clamp(job.progress, 0, 100);

    var subBits = [job.mode === "audio" ? "Audio" : "Video"];
    if (job.quality && job.quality !== "best") subBits.push(job.quality + "p");
    if (!done && !err) subBits.push(job.threads + " connections");
    if (done && job.size) subBits.push(fmtBytes(job.size));

    var stateHtml = "";
    if (active || queued) {
      stateHtml = '<div class="job-state">' +
        (active ? '<b class="pct">' + (job.progress >= 0 ? pct.toFixed(0) + "%" : "") + "</b>" : "") +
        '<span class="state-label">' + (queued ? "Queued" : "Downloading") + "</span>" +
        "</div>";
    } else if (done) {
      stateHtml = '<div class="job-state"><span class="state-label done">Saved</span></div>';
    } else if (err) {
      stateHtml = '<div class="job-state"><span class="state-label failed">Failed</span></div>';
    }

    var meta = "";
    if (active || queued) {
      var bits = [];
      if (job.bytes > 0 && job.total > 0) bits.push(fmtBytes(job.bytes) + " / " + fmtBytes(job.total));
      else if (job.bytes > 0) bits.push(fmtBytes(job.bytes));
      if (job.speed > 0) bits.push(fmtSpeed(job.speed));
      if (job.eta > 0) bits.push("ETA " + fmtEta(job.eta));
      if (!bits.length) bits.push(queued ? "Waiting" : (job.stage || "Starting…"));
      meta = '<div class="job-meta">' + bits.map(function (b) {
        return "<span>" + esc(b) + "</span>";
      }).join("") + "</div>";
    } else if (done) {
      var doneBits = [];
      if (job.size) doneBits.push(fmtBytes(job.size));
      doneBits.push(hostOf(job.url));
      meta = '<div class="job-meta">' + doneBits.map(function (b) {
        return "<span>" + esc(b) + "</span>";
      }).join("") + "</div>";
    }

    var actions = "";
    if (done) {
      actions += '<button class="mini-btn" data-act="open">Open</button>';
      actions += '<button class="mini-btn ghost" data-act="share">Share</button>';
    }
    if (err) {
      actions += '<button class="mini-btn" data-act="retry">Retry</button>';
      actions += '<button class="mini-btn ghost" data-act="dismiss">Dismiss</button>';
    }
    if (active) {
      actions += '<button class="mini-btn danger" data-act="cancel">Cancel</button>';
    }

    var rows = [
      ["Link", job.url],
      ["Type", job.mode === "audio" ? "Audio · MP3" : "Video · " + ext],
      ["Quality", qualityLabel(job.quality)],
      ["Connections", job.threads + " parallel"]
    ];
    if (job.path) rows.push(["Save to", parentDir(job.path)]);
    if (job.size) rows.push(["Size", fmtBytes(job.size)]);
    rows.push(["Status", jobStatusText(job)]);

    var detailHtml = rows.map(function (r) {
      return '<div class="detail-row"><span>' + esc(r[0]) + "</span><span>" + esc(r[1]) + "</span></div>";
    }).join("");

    if (err) {
      detailHtml += '<div class="detail-error">' + esc(job.error) + "</div>" +
        '<div class="detail-actions"><button class="mini-btn ghost" data-act="copy">Copy error</button></div>';
    }

    var card = el("div", "job-card");
    card.innerHTML =
      '<div class="job-top">' +
        '<div class="job-icon ' + iconClass + '">' + esc(ext) + "</div>" +
        '<div class="job-main">' +
          '<div class="job-title">' + esc(title) + "</div>" +
          '<div class="job-sub">' + esc(subBits.join(" · ")) + "</div>" +
        "</div>" +
        stateHtml +
      "</div>" +
      (active ? '<div class="bar' + (job.progress < 0 ? " indet" : "") + '"><i style="width:' + pct + '%"></i></div>' : "") +
      meta +
      (err ? '<div class="job-error">' + esc(firstLine(job.error)) + "</div>" : "") +
      (actions ? '<div class="job-actions">' + actions + "</div>" : "") +
      '<details class="details"' + (job._detailsOpen ? " open" : "") + ">" +
        '<summary><span>Details</span><svg class="chev" viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg></summary>' +
        '<div class="details-body"><div class="detail-grid">' + detailHtml + "</div></div>" +
      "</details>";

    var det = card.querySelector("details.details");
    det.addEventListener("toggle", function () { job._detailsOpen = det.open; });

    card.querySelectorAll("[data-act]").forEach(function (btn) {
      btn.addEventListener("click", function () {
        var act = btn.getAttribute("data-act");
        if (act === "cancel") cancelJob(job);
        else if (act === "open") window.DC.openFile({ path: job.path }).catch(function (e) { toast(errText(e)); });
        else if (act === "share") window.DC.shareFile({ path: job.path }).catch(function (e) { toast(errText(e)); });
        else if (act === "copy") copyText(job.error).then(function () { toast("Error copied"); });
        else if (act === "retry") retryJob(job);
        else if (act === "dismiss") { state.lastErrorJob = null; renderJobs(); }
      });
    });

    if (active) {
      jobEls = {
        bar: card.querySelector(".bar"),
        fill: card.querySelector(".bar i"),
        pct: card.querySelector(".pct"),
        meta: card.querySelector(".job-meta"),
        sub: card.querySelector(".job-sub")
      };
    }
    return card;
  }

  function renderJobs() {
    var activeTitle = $("#active-wrap h3");
    var current = state.active || state.lastErrorJob;
    var showResult = !!current;
    $("#active-wrap").classList.toggle("hidden", !showResult);
    if (showResult) {
      activeTitle.textContent = current.status === "error"
        ? "Last download"
        : current.status === "done" ? "Download complete" : "Active download";
      var box = $("#active");
      box.innerHTML = "";
      box.appendChild(jobCard(current, true));
    } else {
      $("#active").innerHTML = "";
      jobEls = null;
    }

    var q = state.queue;
    $("#queue-wrap").classList.toggle("hidden", q.length === 0);
    $("#queue-count").textContent = q.length ? q.length + " waiting" : "";
    var qbox = $("#queue");
    qbox.innerHTML = "";
    q.forEach(function (job, idx) {
      var row = el("div", "queue-card");
      row.innerHTML =
        '<div class="job-icon ' + (job.mode === "audio" ? "audio" : "") + '">' +
          (job.mode === "audio" ? "MP3" : "VID") + "</div>" +
        '<div class="job-main"><div class="job-title">' + esc(shortUrl(job.url)) + "</div>" +
        '<div class="job-sub">Queued · ' + job.threads + "×</div></div>" +
        '<button class="icon-btn small" data-q="' + idx + '" aria-label="Remove"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6 6 18"/></svg></button>';
      row.querySelector("[data-q]").addEventListener("click", function () {
        state.queue.splice(idx, 1);
        renderJobs();
      });
      qbox.appendChild(row);
    });

    renderRecent();
    updateEmpty();
  }

  function updateActiveProgress(d) {
    if (!jobEls || !state.active) return;
    var job = state.active;
    if (d.id !== job.id) return;
    job.progress = typeof d.progress === "number" ? d.progress : job.progress;
    job.bytes = d.bytes || 0;
    job.total = d.total || 0;
    job.speed = d.speed || 0;
    job.eta = d.eta || -1;

    var pct = job.progress < 0 ? 0 : clamp(job.progress, 0, 100);
    if (jobEls.bar) jobEls.bar.classList.toggle("indet", job.progress < 0);
    if (jobEls.fill) jobEls.fill.style.width = pct + "%";
    if (jobEls.pct) jobEls.pct.textContent = job.progress >= 0 ? pct.toFixed(0) + "%" : "";
    if (jobEls.meta) {
      var bits = [];
      if (job.bytes > 0 && job.total > 0) bits.push("<span>" + fmtBytes(job.bytes) + " / " + fmtBytes(job.total) + "</span>");
      else if (job.bytes > 0) bits.push("<span>" + fmtBytes(job.bytes) + "</span>");
      if (job.speed > 0) bits.push("<span>" + fmtSpeed(job.speed) + "</span>");
      if (job.eta > 0) bits.push("<span>ETA " + fmtEta(job.eta) + "</span>");
      if (!bits.length) bits.push("<span>" + esc(job.stage || "Starting…") + "</span>");
      jobEls.meta.innerHTML = bits.join("");
    }
  }

  /* ---------------- files ---------------- */

  function extOf(name) {
    var s = String(name || "");
    var dot = s.lastIndexOf(".");
    return dot > 0 ? s.slice(dot + 1, dot + 5).toUpperCase() : "FILE";
  }

  function fileKind(f) {
    var mime = String(f.mime || "");
    if (mime.indexOf("video/") === 0) return "vid";
    if (mime.indexOf("audio/") === 0) return "aud";
    var ext = extOf(f.name);
    if (["MP4", "MKV", "WEBM", "MOV", "AVI"].indexOf(ext) >= 0) return "vid";
    if (["MP3", "M4A", "AAC", "FLAC", "WAV", "OGG"].indexOf(ext) >= 0) return "aud";
    return "";
  }

  function fileTypeLabel(kind) {
    return kind === "vid" ? "Video" : kind === "aud" ? "Audio" : "File";
  }

  function fileRow(f, compact) {
    var item = el("div", "file-item");
    var row = el("div", "file-row");
    var kind = fileKind(f);
    row.innerHTML =
      '<div class="file-ext ' + kind + '">' + esc(extOf(f.name)) + "</div>" +
      '<div class="file-main">' +
        '<div class="file-name">' + esc(f.name) + "</div>" +
        '<div class="file-sub">' + esc(fmtBytes(f.size) || "—") + " · " + esc(fmtDate(f.date)) + "</div>" +
      "</div>" +
      '<div class="file-actions">' +
        '<button class="icon-btn small" data-a="open" aria-label="Open"><svg viewBox="0 0 24 24"><path d="M5 3l14 9-14 9z"/></svg></button>' +
        (compact ? "" : '<button class="icon-btn small filetoggle" data-a="info" aria-label="File details" aria-expanded="false"><svg viewBox="0 0 24 24"><path d="m6 9 6 6 6-6"/></svg></button>') +
      "</div>";
    item.appendChild(row);

    if (!compact) {
      var panel = el("div", "file-details hidden");
      panel.innerHTML =
        '<div class="detail-grid">' +
          '<div class="detail-row"><span>Path</span><span>' + esc(f.path) + "</span></div>" +
          '<div class="detail-row"><span>Type</span><span>' + esc(fileTypeLabel(kind)) + "</span></div>" +
          '<div class="detail-row"><span>Size</span><span>' + esc(fmtBytes(f.size) || "—") + "</span></div>" +
          '<div class="detail-row"><span>Saved</span><span>' + esc(fmtDate(f.date)) + "</span></div>" +
        "</div>" +
        '<div class="detail-actions">' +
          '<button class="mini-btn ghost" data-a="share">Share</button>' +
          '<button class="mini-btn danger" data-a="del">Delete</button>' +
        "</div>";
      item.appendChild(panel);

      var info = row.querySelector('[data-a="info"]');
      info.addEventListener("click", function (e) {
        e.stopPropagation();
        var hidden = panel.classList.toggle("hidden");
        item.classList.toggle("open", !hidden);
        info.setAttribute("aria-expanded", String(!hidden));
      });

      panel.querySelector('[data-a="share"]').addEventListener("click", function () {
        window.DC.shareFile({ path: f.path }).catch(function (err) { toast(errText(err)); });
      });
      panel.querySelector('[data-a="del"]').addEventListener("click", function () {
        if (!confirm("Delete " + f.name + "?")) return;
        window.DC.deleteFile({ path: f.path }).then(function () {
          toast("Deleted");
          refreshFiles();
        }).catch(function (err) { toast(errText(err)); });
      });
    }

    row.querySelector('[data-a="open"]').addEventListener("click", function (e) {
      e.stopPropagation();
      window.DC.openFile({ path: f.path }).catch(function (err) { toast(errText(err)); });
    });
    row.addEventListener("click", function () {
      window.DC.openFile({ path: f.path }).catch(function (err) { toast(errText(err)); });
    });
    return item;
  }

  function renderRecent() {
    var wrap = $("#recent-wrap");
    var box = $("#recent");
    if (!state.files.length) {
      wrap.classList.add("hidden");
      box.innerHTML = "";
      return;
    }
    wrap.classList.remove("hidden");
    box.innerHTML = "";
    state.files.slice(0, 3).forEach(function (f) {
      box.appendChild(fileRow(f, true));
    });
  }

  function renderFiles() {
    var box = $("#files");
    box.innerHTML = "";
    $("#files-empty").classList.toggle("hidden", state.files.length > 0);
    state.files.forEach(function (f) { box.appendChild(fileRow(f, false)); });
    updateEmpty();
  }

  function updateEmpty() {
    var empty = !state.active && !state.lastErrorJob && !state.queue.length && !state.files.length;
    $("#home-empty").classList.toggle("hidden", !empty);
  }

  function refreshFiles() {
    if (!window.DC.available) return;
    window.DC.listFiles().then(function (res) {
      state.files = (res && res.files) || [];
      if (res && res.dir) $("#folder-path").textContent = res.dir;
      renderRecent();
      renderFiles();
    }).catch(function () {});
  }

  /* ---------------- storage ---------------- */

  function renderStorage() {
    var s = state.storage;
    if (!s) return;
    var granted = !!s.granted;
    $("#folder-path").textContent = s.dir || "—";
    $("#folder").textContent = s.dir || "—";
    $("#folder-state").textContent = granted ? "PUBLIC" : "PRIVATE";
    $("#folder-state").className = "badge " + (granted ? "ok" : "warn");
    $("#storage-status").textContent = granted
      ? "Public Downloads · files survive uninstall"
      : "App-private folder · removed if you uninstall";
    $("#storage-status").className = "path-state " + (granted ? "ok" : "warn");
    $("#storage-banner").classList.toggle("hidden", granted);
    $("#grant-storage").classList.toggle("hidden", granted);
    var prompted = false;
    try { prompted = !!localStorage.getItem("dc_storage_prompted"); } catch (e) {}
    if (state.booted && !granted && !prompted) {
      try { localStorage.setItem("dc_storage_prompted", "1"); } catch (e) {}
      setTimeout(showStorageModal, 650);
    }
  }

  function refreshStorage() {
    if (!window.DC.available) return;
    window.DC.storageStatus().then(function (s) {
      state.storage = s;
      renderStorage();
    }).catch(function () {});
  }

  function showStorageModal() {
    $("#storage-modal").classList.remove("hidden");
  }

  function hideStorageModal() {
    $("#storage-modal").classList.add("hidden");
  }

  function requestStorage() {
    hideStorageModal();
    window.DC.requestStorage().then(function () {
      toast("Allow Download Chan in the next screen");
    }).catch(function (err) {
      toast(errText(err));
    });
  }

  /* ---------------- cookies ---------------- */

  function renderCookies() {
    var c = state.cookies;
    var box = $("#cookie-state");
    if (c && c.exists) {
      box.textContent = "Cookies imported · " + fmtBytes(c.size) + " (used for private posts)";
      box.className = "cookie-state ok";
      $("#clear-cookies").classList.remove("hidden");
    } else {
      box.textContent = "No cookies imported";
      box.className = "cookie-state";
      $("#clear-cookies").classList.add("hidden");
    }
  }

  function refreshCookies() {
    if (!window.DC.available) return;
    window.DC.cookiesStatus().then(function (c) {
      state.cookies = c;
      renderCookies();
    }).catch(function () {});
  }

  /* ---------------- engine ---------------- */

  function setEnginePill(text, kind) {
    $("#engine-text").textContent = text;
    $("#engine-pill").className = "pill" + (kind ? " " + kind : "");
  }

  function setEngineDetail(text) {
    $("#engine-detail").textContent = text;
  }

  function initEngine() {
    if (!window.DC.available) {
      setEnginePill("Web preview", "bad");
      setEngineDetail("Native engine unavailable in the browser.");
      $("#engine-status").textContent = "OFF";
      return;
    }
    setEnginePill("Starting", "");
    setEngineDetail("Starting bundled yt-dlp…");
    window.DC.init().then(function () {
      state.engineReady = true;
      setEnginePill("Ready", "ok");
      setEngineDetail("yt-dlp · bundled engine");
      $("#engine-status").textContent = "READY";
      $("#engine-status").className = "badge ok";
      maybeUpdateEngine();
    }).catch(function (err) {
      setEnginePill("Engine error", "bad");
      setEngineDetail(errText(err));
      $("#engine-status").textContent = "ERROR";
      $("#engine-status").className = "badge warn";
    });
  }

  function updateEngineNow(manual) {
    if (!window.DC.available) { if (manual) toast("Engine unavailable"); return; }
    var btn = $("#update-engine");
    if (manual) {
      btn.disabled = true;
      btn.textContent = "Updating…";
      setEngineDetail("Updating yt-dlp…");
    }
    window.DC.updateEngine().then(function (r) {
      try { localStorage.setItem("dc_engine_update", String(Date.now())); } catch (e) {}
      var updated = !!(r && r.updated);
      setEngineDetail(updated ? "yt-dlp · updated just now" : "yt-dlp · up to date");
      setEnginePill("Ready", "ok");
      if (manual) toast(updated ? "Engine updated" : "Engine is already up to date");
    }).catch(function (err) {
      setEngineDetail("yt-dlp · bundled (update failed)");
      if (manual) toast("Update failed: " + errText(err));
    }).then(function () {
      if (manual) {
        btn.disabled = false;
        btn.textContent = "Update engine";
      }
    });
  }

  function maybeUpdateEngine() {
    var last = 0;
    try { last = parseInt(localStorage.getItem("dc_engine_update") || "0", 10) || 0; } catch (e) {}
    if (Date.now() - last > 24 * 60 * 60 * 1000) updateEngineNow(false);
  }

  function checkEngine() {
    if (!window.DC.available || state.engineReady) return;
    window.DC.isReady().then(function (r) {
      if (r && r.ready) {
        state.engineReady = true;
        setEnginePill("Ready", "ok");
        $("#engine-status").textContent = "READY";
        $("#engine-status").className = "badge ok";
      }
    }).catch(function () {});
  }

  /* ---------------- clipboard copy ---------------- */

  function copyText(text) {
    var s = String(text || "");
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(s).catch(function () { return legacyCopy(s); });
    }
    return Promise.resolve(legacyCopy(s));
  }

  function legacyCopy(s) {
    try {
      var ta = document.createElement("textarea");
      ta.value = s;
      ta.style.position = "fixed";
      ta.style.opacity = "0";
      document.body.appendChild(ta);
      ta.select();
      document.execCommand("copy");
      document.body.removeChild(ta);
      return true;
    } catch (e) {
      return false;
    }
  }

  /* ---------------- share handling ---------------- */

  function handleShared(text) {
    var url = firstUrl(text) || String(text || "").trim();
    if (!/^https?:\/\//i.test(url)) return;
    rememberAuto(url);
    setInput(url);
    state.clipboardUrl = url;
    hideClipBanner();
    $("#status-line").textContent = "Shared link — starting…";
    startDownload(url);
    switchTab("home");
  }

  /* ---------------- event binding ---------------- */

  function bindEvents() {
    $$(".tabbtn").forEach(function (btn) {
      btn.addEventListener("click", function () { switchTab(btn.dataset.tab); });
    });

    $("#paste").addEventListener("click", function () {
      readClipboardText().then(function (text) {
        var url = firstUrl(text);
        if (!url) { toast("Clipboard has no link"); $("#url").focus(); return; }
        setInput(url);
        state.clipboardUrl = url;
        showClipBanner(url, false);
      }).catch(function () {
        toast("Long-press the box and paste");
        $("#url").focus();
      });
    });

    $("#clear").addEventListener("click", function () {
      $("#url").value = "";
      hideClipBanner();
      $("#url").focus();
    });

    $("#download").addEventListener("click", function () {
      var url = firstUrl($("#url").value);
      if (!url) { toast("Paste a link first"); $("#url").focus(); return; }
      startDownload(url);
    });

    $$("#mode-seg .seg-btn").forEach(function (b) {
      b.addEventListener("click", function () {
        settings.mode = b.dataset.mode;
        saveSettings();
        syncSettingsUI();
      });
    });

    $("#quality").addEventListener("change", function () {
      settings.quality = this.value;
      saveSettings();
      syncSettingsUI();
    });

    $$("#def-mode-seg .seg-btn").forEach(function (b) {
      b.addEventListener("click", function () {
        settings.mode = b.dataset.defmode;
        saveSettings();
        syncSettingsUI();
      });
    });

    $("#def-quality").addEventListener("change", function () {
      settings.quality = this.value;
      saveSettings();
      syncSettingsUI();
    });

    $("#auto-clip").addEventListener("change", function () {
      settings.autoClipboard = this.checked;
      saveSettings();
      if (settings.autoClipboard) checkClipboard(false);
    });

    $("#threads").addEventListener("change", function () { setThreads(this.value); });
    $("#threads").addEventListener("blur", function () { setThreads(this.value); });

    $$(".stepper button").forEach(function (b) {
      b.addEventListener("click", function () {
        setThreads(settings.threads + parseInt(b.dataset.step, 10));
      });
    });

    $("#remote-on").addEventListener("change", function () {
      settings.onlineFallback = this.checked;
      saveSettings();
      syncSettingsUI();
    });

    $("#resolver").addEventListener("change", function () {
      var v = this.value.trim();
      settings.resolver = v || DEFAULT_RESOLVER;
      this.value = settings.resolver;
      saveSettings();
    });

    $("#refresh").addEventListener("click", function () {
      refreshFiles();
      refreshStorage();
      toast("Refreshed");
    });

    $("#see-all").addEventListener("click", function () { switchTab("files"); });

    $("#grant-storage").addEventListener("click", requestStorage);
    $("#banner-grant").addEventListener("click", requestStorage);
    $("#modal-allow").addEventListener("click", requestStorage);
    $("#modal-skip").addEventListener("click", hideStorageModal);
    $("#storage-modal").addEventListener("click", function (e) {
      if (e.target === this) hideStorageModal();
    });

    $("#open-folder").addEventListener("click", function () {
      window.DC.openFolder().catch(function (err) { toast(errText(err)); });
    });

    $("#pick-cookies").addEventListener("click", function () {
      window.DC.pickCookies().then(function () {
        toast("Cookies imported");
        refreshCookies();
      }).catch(function (err) {
        var msg = errText(err);
        if (!/cancelled|canceled/i.test(msg)) toast(msg);
      });
    });

    $("#clear-cookies").addEventListener("click", function () {
      window.DC.clearCookies().then(function () {
        toast("Cookies removed");
        refreshCookies();
      }).catch(function (err) { toast(errText(err)); });
    });

    $("#update-engine").addEventListener("click", function () { updateEngineNow(true); });

    $("#open-github").addEventListener("click", function () {
      var url = "https://github.com/nahid77777771-maker/download-chan";
      if (window.DC.available) window.DC.openExternal({ url: url }).catch(function () { window.open(url, "_blank"); });
      else window.open(url, "_blank");
    });

    $("#clip-download").addEventListener("click", function () {
      if (state.clipboardUrl) startDownload(state.clipboardUrl);
    });

    window.DC.on("sharedUrl", function (d) {
      if (d && d.url) handleShared(d.url);
    });

    window.DC.on("progress", function (d) {
      if (d) updateActiveProgress(d);
    });

    window.DC.on("stage", function (d) {
      if (!state.active || !d || d.id !== state.active.id) return;
      state.active.stage = d.stage || "";
      if (jobEls && jobEls.sub) {
        var bits = [state.active.mode === "audio" ? "Audio" : "Video",
          state.active.threads + "×", state.active.stage];
        jobEls.sub.textContent = bits.join(" · ");
      }
    });

    document.addEventListener("visibilitychange", function () {
      if (document.hidden) return;
      setTimeout(function () {
        refreshStorage();
        refreshCookies();
        refreshFiles();
        checkEngine();
        window.DC.getSharedUrl().then(function (r) {
          if (r && r.url) handleShared(r.url);
          else checkClipboard(true);
        }).catch(function () { checkClipboard(true); });
      }, 350);
    });
  }

  /* ---------------- boot ---------------- */

  function boot() {
    loadSettings();
    syncSettingsUI();
    $("#appver").textContent = APP_VERSION;
    bindEvents();
    renderJobs();
    updateEmpty();

    refreshFiles();
    refreshStorage();
    refreshCookies();
    initEngine();

    setTimeout(function () {
      state.booted = true;
      if (state.storage) renderStorage();
    }, 1200);

    if (window.DC.available) {
      window.DC.getSharedUrl().then(function (r) {
        if (r && r.url) handleShared(r.url);
        else checkClipboard(true);
      }).catch(function () { checkClipboard(true); });
    } else {
      checkClipboard(false);
    }

    setInterval(checkEngine, 6000);
  }

  boot();
})();
