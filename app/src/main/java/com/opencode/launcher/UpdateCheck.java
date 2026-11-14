package com.opencode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Chequeos de update que valen con y sin UI (actividad y receptor de alarma).
 *  Sin dependencias nuevas: HttpURLConnection + org.json del framework. */
public class UpdateCheck {
    private static final String TAG = "OC-Update";
    /** Repo donde se publican los APK del launcher (releases con .apk). */
    public static final String LAUNCHER_REPO = "Dajeovv/opencode-android";
    /** Repo del binario opencode (mismo que usa la actividad). */
    public static final String OPENCODE_REPO = "sst/opencode";

    /** Cache en memoria de las respuestas de la API de GitHub.
     *
     *  Sin autenticar, la API permite 60 peticiones por hora y por IP. La
     *  app consultaba la release del runtime y la del launcher por separado
     *  y ademas al abrir y al recibir la alarma: con dos o tres aperturas
     *  seguidas se agotaba el limite, la API contestaba 403 y la app
     *  informaba de que no habia actualizacion (fallo silencioso).
     *
     *  Con esta cache cada URL se pide una vez por sesion y el resultado se
     *  reutiliza. Si la llamada falla por el limite, se conserva el ultimo
     *  resultado bueno para no perder el aviso. */
    private static final java.util.Map<String, String> CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, Long> CACHE_TS = new java.util.HashMap<>();
    /** Validez de la cache: 30 min. */
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;

    /** Cuerpo de la ultima respuesta buena de una URL (para el 403). */
    private static final java.util.Map<String, String> LAST_GOOD = new java.util.HashMap<>();

    /** Peticiones de la API que ya fallaron por el limite: no se repiten
     *  hasta que pase un rato, para no gastar mas cuota. */
    private static final java.util.Map<String, Long> LIMITED = new java.util.HashMap<>();
    /** Espera tras un 403 antes de volver a intentarlo. */
    private static final long LIMIT_BACKOFF_MS = 30 * 60 * 1000L;

    /** Ultimo codigo HTTP de la ultima llamada a una URL (403 = limite). */
    private static final java.util.Map<String, Integer> LAST_CODE = new java.util.HashMap<>();

    /** fetchJson con cache: una peticion por URL y por sesion. */
    static String fetchJsonCached(String url) {
        long now = System.currentTimeMillis();
        Long ts = CACHE_TS.get(url);
        if (ts != null && now - ts < CACHE_TTL_MS) {
            String v = CACHE.get(url);
            if (v != null) return v;
        }
        Long lim = LIMITED.get(url);
        if (lim != null && now - lim < LIMIT_BACKOFF_MS) {
            // Limite agotado: no se vuelve a pedir. Se devuelve lo ultimo
            // bueno que se consiga, para no perder un aviso real.
            String last = LAST_GOOD.get(url);
            Log.d(TAG, "cache: sin peticion nueva a " + url
                    + " (limite de la API)" + (last != null ? ", uso la ultima buena" : ""));
            return last;
        }
        String j = fetchJson(url);
        Integer code = LAST_CODE.get(url);
        if (j == null) {
            // Solo el 403 (limite) merece backoff: un fallo de red se
            // reintenta en la siguiente comprobacion.
            if (code != null && code == 403) LIMITED.put(url, now);
            String last = LAST_GOOD.get(url);
            if (last != null) {
                Log.d(TAG, "cache: uso la ultima respuesta buena de " + url);
                return last;
            }
            return null;
        }
        LIMITED.remove(url);
        CACHE.put(url, j);
        CACHE_TS.put(url, now);
        LAST_GOOD.put(url, j);
        return j;
    }

    static String fetchJson(String url) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            // Las mismas cabeceras que usa la descarga del runtime, que si
            // funciona desde el movil: Accept-Encoding: identity evita la
            // compresion, que en algunas redes (datos del operador) cortaba
            // la respuesta a mitad con "unexpected end of stream".
            c.setRequestProperty("User-Agent", "opencode-android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("Accept-Encoding", "identity");
            c.setInstanceFollowRedirects(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            int code = c.getResponseCode();
            LAST_CODE.put(url, code);
            if (code < 200 || code >= 300) {
                // La API de GitHub sin token limita a 60 peticiones por hora y
                // por IP: cuando se pasa, contesta 403 y getInputStream()
                // lanza. Sin este registro el fallo era invisible: la app
                // simplemente decia que no hayActualizacion.
                Log.d(TAG, "fetchJson " + code + " en " + url
                        + (code == 403 ? " (limite de la API de GitHub)" : ""));
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            Log.d(TAG, "fetchJson fallo en " + url + ": " + e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Primera "X.Y[.Z]" del texto; null si no hay. */
    public static String parseVer(String s) {
        if (s == null) return null;
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(s);
        return m.find() ? m.group(1) : null;
    }

    /** Compara versiones: >0 si a es mayor, 0 iguales, <0 si menor. */
    public static int cmpVer(String a, String b) {
        try {
            String[] pa = a.split("\\."), pb = b.split("\\.");
            for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
                int x = i < pa.length ? Integer.parseInt(pa[i]) : 0;
                int y = i < pb.length ? Integer.parseInt(pb[i]) : 0;
                if (x != y) return x - y;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Canal de releases del launcher: la v1 sigue la rama 4.x y la v2 la 5.x. */
    static String launcherChannel() {
        return BuildConfig.V2 ? "v5." : "v4.";
    }

    /** Mayor del runtime opencode que usa esta app: 1.x en v1, 2.x en v2. */
    static int runtimeMajor() {
        return BuildConfig.V2 ? 2 : 1;
    }

    /** Release mas nueva del runtime dentro de su rama (1.x o 2.x). */
    static JSONObject runtimeRelease() {
        String j = fetchJsonCached("https://api.github.com/repos/" + OPENCODE_REPO
                + "/releases?per_page=100");
        if (j == null) {
            // Plan B: la API puede fallar (limite de peticiones, cortes en
            // la red). El feed de releases es un fichero estatico de
            // github.com y suele responder aunque la API no.
            String tag = latestFromFeed("https://github.com/" + OPENCODE_REPO
                    + "/releases.atom");
            if (tag == null) return null;
            try {
                return new JSONObject().put("tag_name", tag);
            } catch (Exception ignored) {
                return null;
            }
        }
        try {
            JSONArray list = new JSONArray(j);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                String tag = parseVer(o.optString("tag_name", ""));
                if (tag == null) continue;
                if (Integer.parseInt(tag.split("\\.")[0]) != runtimeMajor()) continue;
                return o;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static final class RuntimeAsset {
        public final String version;
        public final String name;
        public final String url;
        public final String sha256;
        public final long size;

        RuntimeAsset(String version, String name, String url, String sha256, long size) {
            this.version = version;
            this.name = name;
            this.url = url;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    public static RuntimeAsset opencodeRuntimeAsset() {
        JSONObject o = runtimeRelease();
        if (o == null) return null;
        try {
            String tag = parseVer(o.optString("tag_name", ""));
            if (tag == null) return null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets == null) return null;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.optJSONObject(i);
                if (a == null) continue;
                String name = a.optString("name", "");
                if (!name.equals("opencode-linux-arm64-musl.tar.gz")) continue;
                String url = a.optString("browser_download_url", "");
                String digest = a.optString("digest", "");
                if (digest.startsWith("sha256:")) digest = digest.substring(7);
                long size = a.optLong("size", 0);
                if (url.isEmpty() || digest.isEmpty() || size <= 0) return null;
                return new RuntimeAsset(tag, name, url, digest, size);
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** Ultimo tag del binario opencode de esta rama ("X.Y.Z" o null). */
    public static String latestOpencode() {
        JSONObject o = runtimeRelease();
        if (o == null) return null;
        try {
            return parseVer(o.optString("tag_name", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** Version mas nueva del feed Atom de releases (plan B).
     *
     *  El feed es un XML estatico que GitHub sirve desde github.com, sin la
     *  API y sin limite de peticiones. Se buscan los tags de la rama que
     *  usa la app (2.x en v2, 1.x en v1) y se devuelve el mayor.
     *
     *  Formato: <entry><id>tag:github.com,2008:Repository/123/v2.2.7</id>
     *  ... </entry>. */
    static String latestFromFeed(String feedUrl) {
        String xml = fetchJsonCached(feedUrl);
        if (xml == null) return null;
        String best = null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<id>tag:github\\.com,[^<]*/(v?\\d+\\.\\d+(?:\\.\\d+)?)</id>")
                .matcher(xml);
        while (m.find()) {
            String tag = parseVer(m.group(1));
            if (tag == null) continue;
            // Solo la rama que usa esta app (2.x en v2, 1.x en v1).
            String major = tag.split("\\.")[0];
            if (!major.equals(BuildConfig.V2 ? "2" : "1")) continue;
            if (best == null || cmpVer(tag, best) > 0) best = tag;
        }
        Log.d(TAG, "feed: ultima version " + best);
        return best;
    }

    /** Release del launcher de este canal: [tag, urlApk] o null.
     *  Sin .apk no hay nada que instalar. */
    public static String[] launcherRelease() {
        String j = fetchJsonCached("https://api.github.com/repos/" + LAUNCHER_REPO
                + "/releases?per_page=30");
        if (j == null) return null;
        try {
            JSONArray list = new JSONArray(j);
            for (int i = 0; i < list.length(); i++) {
                JSONObject o = list.optJSONObject(i);
                if (o == null || o.optBoolean("draft") || o.optBoolean("prerelease")) continue;
                String rawTag = o.optString("tag_name", "");
                if (!rawTag.startsWith(launcherChannel())) continue;
                String tag = parseVer(rawTag);
                if (tag == null) continue;
                String apk = null;
                JSONArray assets = o.optJSONArray("assets");
                if (assets != null) {
                    for (int k = 0; k < assets.length(); k++) {
                        JSONObject a = assets.optJSONObject(k);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        String url = a.optString("browser_download_url", "");
                        if (name.endsWith(".apk") && !url.isEmpty()) {
                            apk = url;
                            break;
                        }
                    }
                }
                if (apk != null) return new String[]{tag, apk};
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** versionName de esta APK ("" si no se puede leer). */
    public static String appVersion(Context ctx) {
        try {
            String v = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
            return v != null ? v : "";
        } catch (Exception e) {
            return "";
        }
    }

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE);
    }
}
