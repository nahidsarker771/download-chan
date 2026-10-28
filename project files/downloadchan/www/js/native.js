/* Thin wrapper over the native Capacitor "Downloader" plugin. */
(function () {
  "use strict";

  var Plugins = (window.Capacitor && window.Capacitor.Plugins) || {};
  var D = Plugins.Downloader || null;

  function missing() {
    return Promise.reject(new Error("Native engine not available in this build"));
  }

  function call(name, opts) {
    if (!D || !D[name]) return missing();
    return opts === undefined ? D[name]() : D[name](opts);
  }

  window.DC = {
    available: !!D,
    isReady: function () { return D ? D.isReady() : Promise.resolve({ ready: false }); },
    init: function () { return call("init"); },
    getClipboard: function () { return D ? D.getClipboard() : Promise.resolve({ text: "" }); },
    storageStatus: function () {
      return D ? D.storageStatus() : Promise.resolve({
        granted: false, publicPath: "", privatePath: "", dir: "", usingPublic: false
      });
    },
    requestStorage: function () { return call("requestStorage"); },
    openFolder: function () { return call("openFolder"); },
    cookiesStatus: function () {
      return D ? D.cookiesStatus() : Promise.resolve({ exists: false, size: 0 });
    },
    pickCookies: function () { return call("pickCookies"); },
    clearCookies: function () { return call("clearCookies"); },
    getSharedUrl: function () {
      return D ? D.getSharedUrl() : Promise.resolve({ url: "" });
    },
    getInfo: function (o) { return call("getInfo", o); },
    downloadAuto: function (o) { return call("downloadAuto", o); },
    download: function (o) { return call("download", o); },
    downloadRemote: function (o) { return call("downloadRemote", o); },
    updateEngine: function () { return call("updateEngine"); },
    cancel: function (o) { return call("cancel", o); },
    listFiles: function () {
      return D ? D.listFiles() : Promise.resolve({ files: [], dir: "", granted: false });
    },
    deleteFile: function (o) { return call("deleteFile", o); },
    openFile: function (o) { return call("openFile", o); },
    shareFile: function (o) { return call("shareFile", o); },
    openExternal: function (o) { return call("openExternal", o); },
    on: function (event, cb) {
      if (!D) return { remove: function () {} };
      var handle = D.addListener(event, cb);
      return {
        remove: function () {
          handle.then(function (h) { h.remove(); }).catch(function () {});
        }
      };
    }
  };
})();
