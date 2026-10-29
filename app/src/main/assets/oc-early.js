/*
 * Script temprano del launcher (se sirve desde /oc-early.js y se inyecta
 * antes del bundle de la SPA). Hace dos cosas:
 *
 *  1. Cachea y poda /provider. La UI v1 descarga 7,2 MB (232 proveedores,
 *     8.951 modelos) cada vez que cambia el directorio del proyecto; el
 *     selector de modelo tardaba ~2,5 s por eso. Solo los proveedores
 *     conectados necesitan sus modelos: el resto conserva sus metadatos
 *     (el dialogo de conexion usa id/nombre, no los modelos).
 *
 *  2. Antes de que una peticion con "directory" llegue al servidor, crea el
 *     repo git de ese proyecto si falta. Sin .git, upstream resuelve el
 *     worktree como "/" y el watcher recorre todo el sistema de archivos.
 *     El puente ocConfig lo hace con el git nativo del APK.
 *
 * Todo es defensivo: si algo falla, se deja pasar la peticion original.
 */
(function () {
  'use strict';
  if (window.__ocEarlyV1) return;
  window.__ocEarlyV1 = true;

  var nativeFetch = window.fetch ? window.fetch.bind(window) : null;
  if (!nativeFetch || typeof Response === 'undefined') return;

  var PROVIDER_TTL = 10 * 60 * 1000;
  var cache = Object.create(null);
  var inflight = Object.create(null);
  var ensured = Object.create(null);
  var generation = 0;

  function dbg(msg) {
    try {
      if (window.ocConfig && ocConfig.debug) ocConfig.debug('early: ' + msg);
    } catch (e) {}
  }

  /*
   * Instalaciones anteriores guardaban el proyecto como /data/user/0/...,
   * mientras el servidor devuelve las sesiones como /data/data/.... Son la
   * misma ruta, pero la UI compara cadenas y las sesiones desaparecian de la
   * barra lateral. Se migran los valores de localStorage antes del bundle.
   */
  function migrateLegacyPaths() {
    try {
      if (!window.ocConfig || !ocConfig.homeDir) return;
      var canonical = ocConfig.homeDir();
      if (!canonical) return;
      var legacy = canonical.replace('/data/data/', '/data/user/0/');
      if (legacy === canonical) return;
      var migrated = 0;
      for (var i = 0; i < localStorage.length; i++) {
        var key = localStorage.key(i);
        var value = localStorage.getItem(key);
        if (!value || value.indexOf(legacy) < 0) continue;
        localStorage.setItem(key, value.split(legacy).join(canonical));
        migrated++;
      }
      if (migrated > 0) dbg('paths migrated ' + migrated);
    } catch (e) {
      dbg('path migration error ' + e);
    }
  }

  migrateLegacyPaths();

  function parseUrl(input) {
    try {
      if (typeof input === 'string') return new URL(input, location.href);
      if (input && typeof input.href === 'string') {
        return new URL(input.href, location.href);
      }
      if (input && input.url) return new URL(input.url, location.href);
    } catch (e) {}
    return null;
  }

  function dirFromUrl(u) {
    if (!u) return '';
    // URLSearchParams ya devuelve el valor decodificado.
    var d = u.searchParams.get('directory');
    if (!d) d = u.searchParams.get('location[directory]');
    if (!d) d = u.searchParams.get('location%5Bdirectory%5D');
    return d || '';
  }

  function ensureProject(dir) {
    if (!dir) return '';
    if (ensured[dir] !== undefined) return ensured[dir];
    var root = dir;
    try {
      if (window.ocConfig && ocConfig.ensureProjectGit) {
        root = ocConfig.ensureProjectGit(dir) || dir;
      }
    } catch (e) {
      dbg('git error ' + dir + ': ' + e);
    }
    ensured[dir] = root;
    dbg('project ' + dir + ' -> ' + root);
    return root;
  }

  function clearCache() {
    generation++;
    cache = Object.create(null);
    inflight = Object.create(null);
  }

  function prune(obj) {
    if (!obj || !Array.isArray(obj.all) || !Array.isArray(obj.connected)) return obj;
    var connected = Object.create(null);
    for (var i = 0; i < obj.connected.length; i++) {
      connected[obj.connected[i]] = true;
    }
    var all = new Array(obj.all.length);
    for (var j = 0; j < obj.all.length; j++) {
      var p = obj.all[j];
      if (!p || connected[p.id]) {
        all[j] = p;
        continue;
      }
      var copy = {};
      for (var k in p) {
        if (Object.prototype.hasOwnProperty.call(p, k)) copy[k] = p[k];
      }
      copy.models = {};
      all[j] = copy;
    }
    obj.all = all;
    return obj;
  }

  function pruneText(text) {
    try { return JSON.stringify(prune(JSON.parse(text))); } catch (e) { return text; }
  }

  function jsonResponse(body) {
    return new Response(body, {
      status: 200,
      statusText: 'OK',
      headers: { 'Content-Type': 'application/json' }
    });
  }

  function isProvider(u, method) {
    return u && u.origin === location.origin && u.pathname === '/provider' &&
      (!method || method === 'GET' || method === 'HEAD');
  }

  /*
   * El selector de proyectos empieza a buscar en "/" y va bajando. Android
   * no deja al servidor listar "/", "/storage" ni "/storage/emulated", asi
   * que el buscador nunca llegaba a /storage/emulated/0 (almacenamiento
   * compartido) y decia "No folders found". Se completan esos tres niveles;
   * a partir de /storage/emulated/0 lista el servidor de verdad.
   */
  var SYNTHETIC_DIRS = {
    '/': [{ name: 'storage', path: 'storage/', absolute: '/storage',
      type: 'directory', ignored: false }],
    '/storage': [{ name: 'emulated', path: 'emulated/', absolute: '/storage/emulated',
      type: 'directory', ignored: false }],
    '/storage/emulated': [{ name: '0', path: '0/', absolute: '/storage/emulated/0',
      type: 'directory', ignored: false }]
  };

  function syntheticFileList(u, method) {
    if (!u || u.origin !== location.origin || u.pathname !== '/file') return null;
    if (method && method !== 'GET' && method !== 'HEAD') return null;
    var dir = u.searchParams.get('directory') || '';
    dir = dir.replace(/\/+$/, '') || '/';
    var list = SYNTHETIC_DIRS[dir];
    if (!list) return null;
    dbg('file synthetic ' + dir);
    return jsonResponse(JSON.stringify(list));
  }

  /*
   * El selector muestra cualquier carpeta, incluida .git, y quien no conoce
   * git acaba abriendola como proyecto. Las carpetas ocultas no son
   * proyectos: se quitan de los listados del selector. Los ficheros ocultos
   * (.env, .gitignore...) se conservan.
   */
  function baseName(path) {
    var name = String(path || '').replace(/\/+$/, '');
    return name.substring(name.lastIndexOf('/') + 1);
  }

  function hiddenName(path) {
    return baseName(path).charAt(0) === '.';
  }

  function appHome() {
    try {
      if (window.ocConfig && ocConfig.homeDir) {
        return String(ocConfig.homeDir() || '').replace(/\/+$/, '');
      }
    } catch (e) {}
    return '';
  }

  function hiddenDir(entry) {
    // /find/file?dirs=true devuelve cadenas; /file devuelve objetos.
    if (typeof entry === 'string') return hiddenName(entry);
    // /find?type=directory no siempre incluye "type"; /file si lo incluye.
    if (!entry || (entry.type && entry.type !== 'directory')) return false;
    return hiddenName(entry.name || entry.path || '');
  }

  function stripHiddenDirs(text, dir) {
    try {
      var list = JSON.parse(text);
      if (!Array.isArray(list)) return text;
      var home = appHome();
      var inHome = home && dir &&
        String(dir).replace(/\/+$/, '') === home;
      var out = list.filter(function (e) {
        if (typeof e === 'string') {
          // El selector solo quiere carpetas. En v1 /find/file?dirs=true
          // mezcla ficheros ("nota.txt") con carpetas ("docs/").
          if (e !== '/' && e.charAt(e.length - 1) !== '/') return false;
        } else if (e && e.type && e.type !== 'directory') {
          return true;
        }
        // "lib" es la carpeta nativa del runtime dentro de HOME; no es un
        // proyecto del usuario. Solo se oculta en la raiz de HOME.
        if (inHome && baseName(typeof e === 'string' ? e : (e.name || e.path)) === 'lib') {
          return false;
        }
        return !hiddenDir(e);
      });
      return out.length === list.length ? text : JSON.stringify(out);
    } catch (e) {
      return text;
    }
  }

  function filterJson(r, fn) {
    if (!r || !r.ok) return r;
    var ct = r.headers.get('content-type') || '';
    if (ct.indexOf('application/json') < 0) return r;
    return r.text().then(function (text) {
      var body = fn(text);
      return new Response(body, {
        status: r.status,
        statusText: r.statusText,
        headers: r.headers
      });
    });
  }

  function isPickerList(u, method) {
    // En v1 el selector pide /file?path=&directory=<carpeta>. El arbol de
    // ficheros usa path="." o una ruta real, asi que solo se filtra el
    // listado del selector (path vacio y directory presente).
    return u && u.origin === location.origin && u.pathname === '/file' &&
      (!method || method === 'GET' || method === 'HEAD') &&
      !(u.searchParams.get('path') || '') &&
      u.searchParams.has('directory');
  }

  function isPickerFind(u, method) {
    if (!u || u.origin !== location.origin) return false;
    if (method && method !== 'GET' && method !== 'HEAD') return false;
    // v1: /find/file?dirs=true. v2: /find?type=directory.
    if (u.pathname === '/find/file') return u.searchParams.get('dirs') === 'true';
    return u.pathname === '/find' && u.searchParams.get('type') === 'directory';
  }

  function isMutation(u, method) {
    if (!u || u.origin !== location.origin) return false;
    if (!method || method === 'GET' || method === 'HEAD') return false;
    var p = u.pathname;
    return p === '/config' || p === '/global/config' ||
      p === '/provider' || p === '/global/dispose' ||
      p.indexOf('/auth') === 0 || p.indexOf('/provider/') === 0;
  }

  function fetchProvider(u, input, init, key) {
    var hit = cache[key];
    var gen = generation;
    if (hit) {
      var age = Date.now() - hit.at;
      if (age < PROVIDER_TTL) {
        dbg('provider hit ' + key + ' ' + hit.body.length);
        return Promise.resolve(jsonResponse(hit.body));
      }
      // Caducado: se sirve al instante y se refresca en segundo plano.
      dbg('provider stale ' + key);
      nativeFetch(input, init).then(function (r) {
        if (!r || !r.ok) return;
        var ct = r.headers.get('content-type') || '';
        if (ct.indexOf('application/json') < 0) return;
        return r.text().then(function (text) {
          if (gen !== generation) return;
          var body = pruneText(text);
          cache[key] = { at: Date.now(), body: body };
          dbg('provider refresh ' + text.length + ' -> ' + body.length);
        });
      }).catch(function () {});
      return Promise.resolve(jsonResponse(hit.body));
    }

    if (inflight[key]) return inflight[key];
    dbg('provider fetch ' + key);
    inflight[key] = nativeFetch(input, init).then(function (r) {
      if (!r || !r.ok) return r;
      var ct = r.headers.get('content-type') || '';
      if (ct.indexOf('application/json') < 0) return r;
      return r.text().then(function (text) {
        var body = pruneText(text);
        if (gen === generation) cache[key] = { at: Date.now(), body: body };
        dbg('provider pruned ' + text.length + ' -> ' + body.length);
        return jsonResponse(body);
      });
    }).then(function (r) {
      delete inflight[key];
      return r;
    }, function (e) {
      delete inflight[key];
      throw e;
    });
    return inflight[key];
  }

  window.fetch = function (input, init) {
    var u = parseUrl(input);
    if (!u || u.origin !== location.origin) return nativeFetch(input, init);

    var method = (init && init.method) || (input && input.method) || 'GET';
    if (isMutation(u, method)) {
      clearCache();
      dbg('cache clear ' + method + ' ' + u.pathname);
      return nativeFetch(input, init);
    }

    var dir = dirFromUrl(u);
    ensureProject(dir);

    var synth = syntheticFileList(u, method);
    if (synth) return synth;

    if (isPickerList(u, method) || isPickerFind(u, method)) {
      return nativeFetch(input, init).then(function (r) {
        return filterJson(r, function (text) {
          return stripHiddenDirs(text, dir);
        });
      });
    }

    if (isProvider(u, method)) {
      // El catalogo v1 es global: el servidor devuelve el mismo JSON para
      // cualquier directorio. Una sola clave evita el segundo fetch de 7 MB.
      return fetchProvider(u, input, init, 'global');
    }
    return nativeFetch(input, init);
  };

  dbg('loaded');
})();
