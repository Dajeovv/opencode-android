/*
 * Shim de la UI v2 dentro del WebView del launcher (OpenCode v2 / opencode 2.x).
 *
 * 1) La UI registra un service worker (/sw.js) que cachea la app. En el
 *    launcher ese worker no debe existir: cuando controla la pagina, el
 *    WebView de Android deja de pasar las peticiones por
 *    shouldInterceptRequest, asi que el launcher dejaria de servir su HTML
 *    (con el viewport bloqueado y este mismo shim) y la pagina se quedaria
 *    a medias. Ademas la peticion del script del worker salta la
 *    interceptacion del launcher (limitation de Android WebView), asi que
 *    devolver un 404 en /sw.js no basta: el worker real se instalaba igual.
 *    Aqui se bloquea el registro, se borra el que hubiera y sus caches.
 *
 * 2) La app lee ?auth_token= al arrancar y acto seguido lo borra de la URL
 *    (clearAuthToken en packages/app/src/entry.tsx). Si despues navega o
 *    recarga, la peticion llega al servidor sin token; el launcher entonces
 *    tiene que matar la carga y repetirla, y eso se ve como un parpadeo (la
 *    pagina se queda en blanco y vuelve a pintar desde cero).
 *    Aqui el token se conserva en cada cambio del historial, de forma que
 *    ninguna navegacion puede perderlo y no hace falta ninguna recarga.
 *
 * 3) Descargas. Cuando el agente crea un fichero, la web lo ofrece con un
 *    <a download> apuntando a un "blob:" generado en memoria. El WebView de
 *    Android no sabe guardar un blob: (no tiene donde escribirlo y el
 *    gestor de descargas del sistema tampoco lo entiende), asi que el
 *    fichero se pierde en silencio y el usuario no puede guardarlo nunca.
 *    Aqui se intercepta el clic, se lee el blob y se pasa al launcher por el
 *    puente ocDownload, que lo escribe en Descargas/opencode.
 */
(function () {
  /*
   * 0) El shell del agente y del terminal es fijo: el trampolin nativo del
   *    APK (libocshell.so). Si se cambia a "sh", el agente pierde el
   *    resolvedor de comandos y todo va mucho mas lento; ademas opencode
   *    --version deja de funcionar. La UI v2 ofrece ese ajuste en
   *    Ajustes > Servidor > Preferencias y lo guarda con un PATCH a
   *    /api/experimental/config. Aqui se oculta la fila y se reescribe
   *    cualquier intento para que el valor no pueda cambiar.
   */
  try {
    if (!window.__ocShellLock && window.fetch) {
      window.__ocShellLock = 1;
      var nativeFetch = window.fetch.bind(window);

      var shellPath = function () {
        try {
          if (window.ocConfig && ocConfig.shellPath) {
            return String(ocConfig.shellPath() || "");
          }
        } catch (e) {}
        return "";
      };

      window.fetch = function (input, init) {
        try {
          var u = new URL(
            typeof input === "string" ? input : (input && input.url) || "",
            location.href
          );
          var method = String(
            (init && init.method) || (input && input.method) || "GET"
          ).toUpperCase();
          if (u.origin === location.origin &&
              u.pathname === "/api/experimental/config" &&
              method === "PATCH") {
            var shell = shellPath();
            if (shell) {
              var opts = {};
              if (init) {
                for (var k in init) {
                  if (Object.prototype.hasOwnProperty.call(init, k)) opts[k] = init[k];
                }
              }
              opts.method = "PATCH";
              opts.body = JSON.stringify({ shell: shell });
              return nativeFetch(input, opts);
            }
            /* Sin puente: se responde como si se hubiera guardado, pero no
               se reenvia nada al servidor. */
            return Promise.resolve(new Response(null, { status: 204 }));
          }
        } catch (e) { /* se deja pasar la peticion original */ }
        return nativeFetch(input, init);
      };
    }
  } catch (e) { /* sin bloqueo de fetch */ }

  try {
    var shellStyle = document.createElement("style");
    shellStyle.textContent =
      '[data-component="settings-row"]:has([data-action="settings-shell"])' +
      "{display:none!important}";
    (document.head || document.documentElement).appendChild(shellStyle);

    var hasSelector = false;
    try {
      hasSelector = !!(window.CSS && CSS.supports && CSS.supports("selector(:has(*))"));
    } catch (e) {}
    if (!hasSelector) {
      /* WebView antiguo sin :has(): se oculta la fila en cuanto aparece. */
      var hideShellRow = function () {
        var el = document.querySelector('[data-action="settings-shell"]');
        if (!el) return false;
        var row = el.closest ? el.closest('[data-component="settings-row"]') : null;
        if (row) row.style.setProperty("display", "none", "important");
        return true;
      };
      if (!hideShellRow()) {
        var shellObs = new MutationObserver(function () {
          if (hideShellRow()) shellObs.disconnect();
        });
        shellObs.observe(document.documentElement, { childList: true, subtree: true });
      }
    }
  } catch (e) { /* sin ocultar la fila */ }

  try {
    /* 3) descargas: el <a download> con blob: se resuelve aqui */
    if (!window.__ocDownloadShim && window.ocDownload) {
      window.__ocDownloadShim = 1;

      var toBase64 = function (blob) {
        return new Promise(function (resolve, reject) {
          var fr = new FileReader();
          fr.onload = function () {
            var s = String(fr.result || "");
            var i = s.indexOf(",");
            resolve(i >= 0 ? s.substring(i + 1) : s);
          };
          fr.onerror = function () { reject(fr.error); };
          fr.readAsDataURL(blob);
        });
      };

      var handle = function (anchor) {
        try {
          var href = anchor.getAttribute("href") || "";
          var name = anchor.getAttribute("download") || "opencode-download";
          /* Tope de tamano: el contenido cruza del navegador a Java como
             texto, y un fichero enorme puede atascar el puente. A partir de
             ahi se deja como estaba (que tampoco funcionaba) en vez de
             dejar la app colgada. */
          var MAX = 12 * 1024 * 1024;
          if (href.indexOf("data:") === 0) {
            /* La UI incrusta el contenido en el propio enlace (data:). Se
               pasa tal cual al launcher. */
            var comma = href.indexOf(",");
            if (comma < 0) return;
            if (href.length > MAX) return;
            try {
              window.ocDownload.save(name, href.substring(comma + 1), "");
            } catch (e) { /* puente no disponible */ }
            return;
          }
          if (href.indexOf("blob:") !== 0) return;   /* solo blob: y data: */
          fetch(href)
            .then(function (r) { return r.blob(); })
            .then(function (blob) {
              if (blob && blob.size > MAX) return;
              return toBase64(blob).then(function (b64) {
                try {
                  window.ocDownload.save(name, b64, blob.type || "");
                } catch (e) { /* puente no disponible */ }
              });
            })
            .catch(function () { /* no se pudo leer el blob */ });
        } catch (e) { /* sin shim de descargas */ }
      };

      /* El clic se captura en la fase de captura, antes de que la pagina
         llegue a pulsar el <a>. */
      document.addEventListener("click", function (ev) {
        var el = ev.target;
        if (!el || !el.closest) return;
        var a = el.closest("a[download]");
        if (!a) return;
        var href = a.getAttribute("href") || "";
        if (href.indexOf("blob:") !== 0 && href.indexOf("data:") !== 0) return;
        /* Se anula el clic: el blob no se puede descargar desde aqui y se
           pasa al launcher. */
        ev.preventDefault();
        ev.stopPropagation();
        handle(a);
      }, true);
    }
  } catch (e) { /* sin shim de descargas */ }

  try {
    /* 1) service workers: ni registro, ni restos, ni caches */
    if (navigator.serviceWorker) {
      var blocked = function () {
        return Promise.reject(new Error("service worker deshabilitado por el launcher"));
      };
      try {
        Object.defineProperty(navigator.serviceWorker, "register", {
          value: blocked,
          configurable: true,
          writable: true,
        });
      } catch (e) {
        navigator.serviceWorker.register = blocked;
      }
    }

    if (navigator.serviceWorker && navigator.serviceWorker.getRegistrations) {
      navigator.serviceWorker.getRegistrations().then(function (regs) {
        regs.forEach(function (r) {
          try {
            r.unregister();
          } catch (e) {}
        });
      }).catch(function () {});
    }

    if (window.caches && window.caches.keys) {
      window.caches.keys().then(function (keys) {
        keys.forEach(function (k) {
          try {
            window.caches.delete(k);
          } catch (e) {}
        });
      }).catch(function () {});
    }

    /* 2) el token se conserva en cada cambio del historial */
    var m = /[?&]auth_token=([^&#]*)/.exec(window.location.search || "");
    if (!m || !m[1]) return;
    if (window.__ocTokShim) return;
    window.__ocTokShim = 1;

    var token = m[1];

    function withToken(url) {
      if (typeof url !== "string" || url === "") return url;
      if (url.indexOf("auth_token=") >= 0) return url;
      if (url.charAt(0) === "#") return url;
      return url + (url.indexOf("?") >= 0 ? "&" : "?") + "auth_token=" + token;
    }

    var push = history.pushState;
    var replace = history.replaceState;

    history.pushState = function (state, title, url) {
      return push.call(this, state, title, withToken(url));
    };

    history.replaceState = function (state, title, url) {
      return replace.call(this, state, title, withToken(url));
    };
  } catch (e) {
    /* sin shim: el launcher tiene el respaldo de onPageStarted */
  }
})();
