package com.opencode.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Instala la caja de herramientas (paquetes de Termux) en segundo plano, con
 * aviso de progreso. Al terminar reinicia el servidor local para que el
 * sandbox vea el PATH nuevo.
 */
public class ToolboxService extends Service {
    public static final String ACTION_INSTALL = "com.opencode.launcher.TOOLBOX_INSTALL";
    public static final String ACTION_BUNDLED = "com.opencode.launcher.TOOLBOX_BUNDLED";
    public static final String EXTRA_PACKAGES = "packages";
    private static final String TAG = "OpenCodeToolbox";
    private static final String CHANNEL = "toolbox";
    private static final int NID = 2203;

    // Estado del instalador, visible para /install/status y para el hilo de
    // fixup del prefijo. running empieza en install() para que oc-pkg no vea
    // una ventana de "libre" entre la peticion y el arranque del servicio.
    private static volatile boolean running;
    private static volatile String lastError = "";
    private static volatile String lastInstalled = "";
    private static volatile String step = "";
    private static volatile long done;
    private static volatile long total;
    private static final java.util.concurrent.atomic.AtomicBoolean CLAIMED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Pide una instalacion. Devuelve false si ya hay otra en curso.
     * El servicio se arranca en primer plano porque puede tardar minutos.
     */
    public static boolean install(Context ctx, List<String> packages) {
        synchronized (ToolboxService.class) {
            if (running) return false;
            running = true;
            lastError = "";
            lastInstalled = "";
            step = "queued";
            done = 0;
            total = 0;
        }
        Intent i = new Intent(ctx, ToolboxService.class);
        i.setAction(ACTION_INSTALL);
        i.putExtra(EXTRA_PACKAGES, new ArrayList<>(packages));
        try {
            ctx.startForegroundService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "no se pudo arrancar el servicio: " + e);
            synchronized (ToolboxService.class) {
                running = false;
                lastError = String.valueOf(e.getMessage());
            }
            return false;
        }
    }

    /** Instala los paquetes que ya vienen dentro del APK (sin red). */
    public static boolean installBundled(Context ctx) {
        synchronized (ToolboxService.class) {
            if (running) return false;
            running = true;
            lastError = "";
            lastInstalled = "";
            step = "queued";
            done = 0;
            total = 0;
        }
        Intent i = new Intent(ctx, ToolboxService.class);
        i.setAction(ACTION_BUNDLED);
        try {
            ctx.startForegroundService(i);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "no se pudo arrancar el servicio: " + e);
            synchronized (ToolboxService.class) {
                running = false;
                lastError = String.valueOf(e.getMessage());
            }
            return false;
        }
    }

    public static boolean isRunning() {
        return running;
    }

    /** Estado en texto plano: facil de leer para oc-pkg (sin jq ni sed). */
    public static String installStatus() {
        return "running=" + (running ? 1 : 0) + "\n"
                + "step=" + oneLine(step) + "\n"
                + "error=" + oneLine(lastError) + "\n"
                + "installed=" + oneLine(lastInstalled) + "\n"
                + "done=" + done + "\n"
                + "total=" + total + "\n";
    }

    private static String oneLine(String s) {
        if (s == null) return "";
        return s.replace('\r', ' ').replace('\n', ' ').trim();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand action=" + (intent == null ? "null" : intent.getAction()));
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        final boolean bundled = ACTION_BUNDLED.equals(intent.getAction());
        if (!bundled && !ACTION_INSTALL.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        ArrayList<String> pkgs = intent.getStringArrayListExtra(EXTRA_PACKAGES);
        if (pkgs == null || pkgs.isEmpty()) pkgs = new ArrayList<>(LocalToolbox.DEFAULT_SET);
        final ArrayList<String> wanted = pkgs;
        // running ya puede estar en true porque install() lo marco al
        // encolar. CLAIMED distingue ese caso de un arranque duplicado.
        if (!CLAIMED.compareAndSet(false, true)) return START_NOT_STICKY;
        running = true;
        if (!"queued".equals(step)) {
            lastError = "";
            lastInstalled = "";
            done = 0;
            total = 0;
            step = "preparing";
        }
        startForeground(NID, notify(
                ServerWatch.t(this, "Instalando herramientas", "Installing tools"),
                ServerWatch.t(this, "Preparando...", "Preparing..."), 0, 0, true));
        Log.i(TAG, "foreground puesto, hilo de instalacion");
        new Thread(() -> {
            String error = null;
            try {
                LocalToolbox.Progress p = new LocalToolbox.Progress() {
                    @Override
                    public void onStep(String message) {
                        step = oneLine(message);
                        done = 0;
                        total = 0;
                        update(message, 0, 0, true);
                        if (LocalToolbox.uiProgress != null) LocalToolbox.uiProgress.onStep(message);
                    }

                    @Override
                    public void onBytes(long doneBytes, long totalBytes) {
                        done = doneBytes;
                        total = totalBytes;
                        update(ServerWatch.t(ToolboxService.this,
                                "Descargando paquetes", "Downloading packages"),
                                doneBytes, totalBytes, false);
                        if (LocalToolbox.uiProgress != null) {
                            LocalToolbox.uiProgress.onBytes(doneBytes, totalBytes);
                        }
                    }
                };
                if (bundled) {
                    LocalToolbox.installBundled(this, p);
                } else {
                    LocalToolbox.install(this, wanted, p);
                }
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
                Log.e(TAG, "fallo instalando herramientas", t);
            }
            final String err = error;
            Log.i(TAG, "instalacion terminada err=" + err);
            lastError = err == null ? "" : oneLine(err);
            lastInstalled = err == null && !bundled ? String.join(",", wanted) : "";
            step = "";
            running = false;
            CLAIMED.set(false);
            // Quitar primero la notificacion de primer plano: si no, la de
            // resultado hereda el flag "ongoing" y no se puede descartar.
            stopForeground(true);
            if (err == null) {
                NotificationManager nm =
                        (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NID);
                // El prefijo y el resolvedor ya se regeneran al final de
                // LocalToolbox.install(); no hace falta reiniciar el servidor.
                // Solo se reinicia si arranco antes de existir el toolbox,
                // porque entonces su entorno no tiene PREFIX ni el token.
                if (LocalRuntime.running() && !LocalRuntime.startedWithToolbox()) {
                    LocalRuntime.stop();
                    LocalServerService.start(this);
                }
                selfTest(this);
            } else {
                // Error visible pero descartable; nunca deja un aviso fijo.
                update(ServerWatch.t(this, "Fallo: ", "Failed: ") + err, 0, 0, false, false);
            }
            stopSelf();
        }, "toolbox-install").start();
        return START_NOT_STICKY;
    }

    /** Comprobacion de humo: cada herramienta responde dentro del sandbox. */
    private static final java.util.concurrent.atomic.AtomicBoolean SELFTEST_RUNNING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static void selfTest(Context ctx) {
        if (!SELFTEST_RUNNING.compareAndSet(false, true)) return;
        String[] cmds = {
                "echo PATH=$PATH",
                "git --version",
                "python3 --version",
                "node --version",
                "which pkg",
                "dpkg --version | head -1"
        };
        for (String c : cmds) {
            String out = LocalRuntime.runInSandbox(ctx, c, 30000);
            Log.i(TAG, "self-test [" + c + "] -> " + out.trim().replace("\n", " | "));
        }
    }

    private void update(String text, long done, long total, boolean indeterminate) {
        update(text, done, total, indeterminate, true);
    }

    private void update(String text, long done, long total, boolean indeterminate,
            boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID, notify(text, text, done, total, indeterminate, ongoing));
    }

    private Notification notify(String title, String text, long done, long total,
            boolean indeterminate) {
        return notify(title, text, done, total, indeterminate, true);
    }

    private Notification notify(String title, String text, long done, long total,
            boolean indeterminate, boolean ongoing) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ServerWatch.t(this, "Herramientas", "Tools"),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, NID, open, fl);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing);
        if (total > 0) {
            b.setProgress(1000, (int) Math.min(1000, done * 1000 / total), false);
            b.setContentText(text + "  " + LocalToolbox.human(done) + " / "
                    + LocalToolbox.human(total));
        } else {
            b.setProgress(0, 0, indeterminate);
        }
        return b.build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
