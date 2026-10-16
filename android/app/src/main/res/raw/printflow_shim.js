/*
 * PrintFlow — adaptaciones de la web del servidor al WebView de Android.
 *
 * La app carga la web de cada servidor FilaOps/PrintFlow tal cual. Esa web está
 * pensada para un navegador de escritorio, y en un WebView hay cosas que no
 * existen: descargar ficheros (<a download>, blobs), abrir pestañas nuevas
 * (window.open, target="_blank") e imprimir (window.print). Sin esto, "Descargar
 * PDF" no hace nada y "Ver albarán" sustituye la pantalla del ERP por el PDF.
 *
 * Este script se inyecta al empezar cada página (MainActivity) y pasa esas
 * acciones al lado nativo (window.PrintFlowNative):
 *   - ficheros (blob:, data:, descargas del propio servidor) → se guardan en
 *     Descargas y se abren con la app del móvil que corresponda (visor de PDF…);
 *   - enlaces a otras webs → navegador del sistema;
 *   - páginas del propio servidor en "pestaña nueva" → en la misma vista;
 *   - window.print() → diálogo de impresión de Android.
 */
(function () {
  "use strict";
  if (window.__printflowShim) return;
  window.__printflowShim = true;

  var native = window.PrintFlowNative;
  if (!native) return;

  // ------------------------------------------------------------- blobs vivos
  // Las webs suelen hacer createObjectURL → a.click() → revokeObjectURL en el
  // mismo tic. Guardamos el Blob para poder leerlo después, y retrasamos la
  // revocación para no perderlo mientras se procesa.
  var blobs = Object.create(null);
  var origCreate = URL.createObjectURL.bind(URL);
  var origRevoke = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var url = origCreate(obj);
    if (obj instanceof Blob) blobs[url] = obj;
    return url;
  };
  URL.revokeObjectURL = function (url) {
    setTimeout(function () {
      delete blobs[url];
      origRevoke(url);
    }, 60000);
  };

  function toast(msg) {
    try { native.toast(String(msg)); } catch (e) { /* sin toast */ }
  }

  function sameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; } catch (e) { return false; }
  }

  function absolute(url) {
    try { return new URL(url, location.href).href; } catch (e) { return String(url); }
  }

  function nameFromDisposition(header) {
    if (!header) return null;
    var star = /filename\*\s*=\s*(?:UTF-8'')?([^;]+)/i.exec(header);
    if (star) {
      try { return decodeURIComponent(star[1].trim().replace(/^"|"$/g, "")); } catch (e) { /* sigue */ }
    }
    var plain = /filename\s*=\s*("?)([^";]+)\1/i.exec(header);
    return plain ? plain[2].trim() : null;
  }

  var EXT = {
    "application/pdf": "pdf", "text/csv": "csv", "application/json": "json",
    "text/plain": "txt", "application/zip": "zip", "image/png": "png", "image/jpeg": "jpg",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": "xlsx",
  };

  function guessName(name, mime, url) {
    if (name) return name;
    var last = "";
    try { last = decodeURIComponent(new URL(url, location.href).pathname.split("/").pop() || ""); } catch (e) { /* sin nombre */ }
    if (/\.[a-z0-9]{2,5}$/i.test(last)) return last;
    var ext = EXT[(mime || "").split(";")[0].trim()] || "bin";
    // Los blob: no traen nombre (su "nombre" es un UUID): título de la página + fecha
    var d = new Date();
    var pad = function (n) { return (n < 10 ? "0" : "") + n; };
    var stamp = d.getFullYear() + pad(d.getMonth() + 1) + pad(d.getDate()) + "-" + pad(d.getHours()) + pad(d.getMinutes()) + pad(d.getSeconds());
    var fallback = (document.title || "PrintFlow").replace(/[^\w\- ]+/g, "").trim().replace(/\s+/g, "-") + "-" + stamp;
    return (last && last.length < 60 && !/^[0-9a-f-]{20,}$/i.test(last) ? last : fallback) + "." + ext;
  }

  function saveBlob(blob, name, open) {
    var reader = new FileReader();
    reader.onload = function () {
      var data = String(reader.result);
      var comma = data.indexOf(",");
      native.saveFile(name, blob.type || "application/octet-stream", data.slice(comma + 1), !!open);
    };
    reader.onerror = function () { toast("No se ha podido leer el fichero"); };
    reader.readAsDataURL(blob);
  }

  /** Descarga `url` (blob:, data: o del propio servidor, con su sesión) y la guarda. */
  function fetchAndSave(url, name, open) {
    var abs = absolute(url);
    if (blobs[abs]) {
      var b = blobs[abs];
      saveBlob(b, guessName(name, b.type, abs), open);
      return Promise.resolve(true);
    }
    toast("Descargando…");
    return fetch(abs, { credentials: "include" })
      .then(function (res) {
        if (!res.ok) throw new Error("HTTP " + res.status);
        var fileName = nameFromDisposition(res.headers.get("Content-Disposition")) || name;
        return res.blob().then(function (b) {
          saveBlob(b, guessName(fileName, b.type || res.headers.get("Content-Type"), abs), open);
          return true;
        });
      })
      .catch(function (err) {
        toast("No se ha podido descargar (" + (err && err.message ? err.message : err) + ")");
        return false;
      });
  }

  // Lo que una página del servidor abre en "pestaña nueva": si es un fichero
  // (PDF, CSV…) se guarda y se abre; si es una página, se navega aquí mismo.
  function openSameOrigin(url) {
    var abs = absolute(url);
    fetch(abs, { method: "GET", credentials: "include" })
      .then(function (res) {
        var type = (res.headers.get("Content-Type") || "").toLowerCase();
        var disp = res.headers.get("Content-Disposition") || "";
        if (res.ok && (type.indexOf("text/html") === -1 || /attachment/i.test(disp))) {
          var fileName = nameFromDisposition(disp);
          return res.blob().then(function (b) {
            saveBlob(b, guessName(fileName, b.type || type, abs), true);
          });
        }
        if (!res.ok && type.indexOf("application/json") !== -1) {
          return res.json().then(function (j) {
            toast(typeof j.detail === "string" ? j.detail : "Error " + res.status);
          });
        }
        location.assign(abs);
      })
      .catch(function () { location.assign(abs); });
  }

  function route(url, opts) {
    opts = opts || {};
    if (url == null || url === "" || url === "about:blank") return;
    var abs = absolute(url);
    if (/^(blob|data):/i.test(abs)) return void fetchAndSave(abs, opts.download, true);
    if (/^https?:/i.test(abs) && sameOrigin(abs)) {
      if (opts.download != null) return void fetchAndSave(abs, opts.download || null, true);
      return void openSameOrigin(abs);
    }
    native.openExternal(abs); // otra web, mailto:, tel:…
  }

  // ------------------------------------------------------------- window.open
  // Devuelve un objeto parecido a una ventana: hay webs que hacen
  // w = window.open("about:blank") y luego w.location.href = url.
  window.open = function (url) {
    route(url);
    var fake = {
      closed: false,
      close: function () { fake.closed = true; },
      focus: function () {},
      blur: function () {},
      print: function () {},
      postMessage: function () {},
      document: { write: function () {}, close: function () {} },
    };
    var loc = { assign: function (u) { route(u); }, replace: function (u) { route(u); } };
    Object.defineProperty(loc, "href", { get: function () { return absolute(url || "about:blank"); }, set: function (u) { route(u); } });
    fake.location = loc;
    fake.window = fake;
    return fake;
  };

  // ------------------------------------------------------------- enlaces
  function handleAnchor(a, ev) {
    var href = a.getAttribute("href");
    if (!href || href.charAt(0) === "#" || /^javascript:/i.test(href)) return false;
    var abs = absolute(href);
    var isDownload = a.hasAttribute("download");
    var isFile = /^(blob|data):/i.test(abs);
    var blank = (a.getAttribute("target") || "").toLowerCase() === "_blank";
    var external = /^https?:/i.test(abs) && !sameOrigin(abs);
    var otherScheme = !/^(https?|blob|data):/i.test(abs);

    if (!(isDownload || isFile || blank || external || otherScheme)) return false;
    if (ev) ev.preventDefault();
    route(abs, isDownload ? { download: a.getAttribute("download") || "" } : {});
    return true;
  }

  // Enlaces que están en la página (clic real)
  document.addEventListener("click", function (ev) {
    if (ev.defaultPrevented) return;
    var el = ev.target;
    while (el && el.nodeName !== "A") el = el.parentElement;
    if (el && el.hasAttribute("href")) handleAnchor(el, ev);
  }, false);

  // Enlaces creados al vuelo y nunca insertados (patrón típico de descarga):
  // su click() no llega al document, así que se intercepta el método.
  var origClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    if (!this.isConnected && handleAnchor(this, null)) return;
    return origClick.apply(this, arguments);
  };

  // ------------------------------------------------------------- imprimir
  window.print = function () { native.print(document.title || "PrintFlow"); };
})();
