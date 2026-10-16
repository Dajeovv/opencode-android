package com.opencode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Chequeos de update que valen con y sin UI (actividad y receptor de alarma).
 *  Sin dependencias nuevas: HttpURLConnection + org.json del framework. */
public class UpdateCheck {
    /** Repo donde se publican los APK del launcher (releases con .apk). */
    public static final String LAUNCHER_REPO = "Dajeovv/opencode-android";
    /** Repo del binario opencode (mismo que usa la actividad). */
    public static final String OPENCODE_REPO = "sst/opencode";

    static String fetchJson(String url) {
        try {
            java.net.HttpURLConnection c = (java.net.HttpURLConnection)
                    new java.net.URL(url).openConnection();
            c.setRequestProperty("User-Agent", "opencode-android");
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            StringBuilder sb = new StringBuilder();
            try (java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
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

    /** Ultimo tag del binario opencode ("X.Y.Z" o null). */
    public static String latestOpencode() {
        String j = fetchJson("https://api.github.com/repos/" + OPENCODE_REPO + "/releases/latest");
        if (j == null) return null;
        try {
            return parseVer(new JSONObject(j).optString("tag_name", ""));
        } catch (Exception e) {
            return null;
        }
    }

    /** Release del launcher: [tag, urlApk] o null. Sin .apk no hay nada que instalar. */
    public static String[] launcherRelease() {
        String j = fetchJson("https://api.github.com/repos/" + LAUNCHER_REPO + "/releases/latest");
        if (j == null) return null;
        try {
            JSONObject o = new JSONObject(j);
            String tag = parseVer(o.optString("tag_name", ""));
            if (tag == null) return null;
            String apk = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String url = a.optString("browser_download_url", "");
                    if (name.endsWith(".apk") && !url.isEmpty()) {
                        apk = url;
                        break;
                    }
                }
            }
            if (apk == null) return null;
            return new String[]{tag, apk};
        } catch (Exception e) {
            return null;
        }
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
