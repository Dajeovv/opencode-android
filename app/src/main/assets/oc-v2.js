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
 */
(function () {
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
