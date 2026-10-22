package com.opencode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root "normal": usa el su del sistema (Magisk/KernelSU piden permiso con su
 * dialogo de siempre) para ampliar lo que el agente puede hacer. No requiere
 * permisos en el manifest ni binarios propios.
 */
public final class RootShell {
    private static final String TAG = "OpenCodeRoot";
    private static final String[] PATHS = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su",
            "/product/bin/su", "/system_ext/bin/su", "/vendor/bin/su",
            "/debug_ramdisk/su", "/apex/com.android.sbin/su"
    };

    private static volatile Boolean granted;
    private static volatile String resolved;

    private RootShell() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        try {
            return prefs(c).getBoolean("root_use", true);
        } catch (Exception e) {
            return true;
        }
    }

    public static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("root_use", on).apply();
        if (!on) granted = false;
    }

    public static String suPath() {
        for (String p : PATHS) {
            java.io.File f = new java.io.File(p);
            if (f.isFile()) return p;
        }
        return null;
    }

    /** Resuelve "su" con el PATH del proceso de la app (Magisk lo pone ahi). */
    private static synchronized String resolveSu() {
        if (resolved != null) return resolved.isEmpty() ? null : resolved;
        resolved = "";
        try {
            Process p = new ProcessBuilder("/system/bin/sh", "-c", "command -v su")
                    .redirectErrorStream(true).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = r.readLine()) != null) sb.append(l);
            }
            p.waitFor(3, TimeUnit.SECONDS);
            String out = sb.toString().trim();
            if (out.startsWith("/")) resolved = out;
            if (resolved.isEmpty()) Log.i(TAG, "sin su en PATH");
        } catch (Exception e) {
            Log.w(TAG, "resolveSu: " + e);
        }
        return resolved.isEmpty() ? null : resolved;
    }

    public static boolean available() {
        return suPath() != null || resolveSu() != null;
    }

    private static String suCmd() {
        String p = suPath();
        return p != null ? p : (resolveSu() != null ? resolved : "su");
    }

    /** Estado sin pedir nada: no | off | idle | on | denied. */
    public static String state(Context c) {
        if (!available()) return "no";
        if (!enabled(c)) return "off";
        Boolean g = granted;
        if (g == null) return "idle";
        return g ? "on" : "denied";
    }

    /** Pide el permiso de su (una vez por sesion) y lo cachea. */
    public static synchronized boolean request(Context c) {
        if (!enabled(c) || !available()) {
            granted = false;
            return false;
        }
        String out = runSu("id", 20000);
        boolean ok = out != null && out.contains("uid=0");
        granted = ok;
        Log.i(TAG, "su " + (ok ? "concedido" : "denegado/sin respuesta"));
        return ok;
    }

    public static boolean isGranted(Context c) {
        Boolean g = granted;
        if (g != null && g) return true;
        return request(c);
    }

    /** Ejecuta un comando como root. Devuelve null si no hay permiso. */
    public static String run(Context c, String cmd, int timeoutMs) {
        if (!enabled(c) || !available()) return null;
        if (!isGranted(c)) return null;
        return runSu(cmd, timeoutMs);
    }

    private static String runSu(String cmd, int timeoutMs) {
        String su = suCmd();
        if (su == null) return null;
        Process p = null;
        try {
            p = new ProcessBuilder(su, "-c", cmd).redirectErrorStream(true).start();
            final Process proc = p;
            final StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        synchronized (sb) {
                            sb.append(l).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "su-reader");
            reader.setDaemon(true);
            reader.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroy();
                Log.w(TAG, "su sin respuesta en " + timeoutMs + " ms");
                return null;
            }
            reader.join(1500);
            synchronized (sb) {
                return sb.toString().trim();
            }
        } catch (Exception e) {
            Log.w(TAG, "su fallo: " + e);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }
}
