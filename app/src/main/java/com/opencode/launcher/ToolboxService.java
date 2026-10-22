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

    private volatile boolean running;

    public static void install(Context ctx, List<String> packages) {
        Intent i = new Intent(ctx, ToolboxService.class);
        i.setAction(ACTION_INSTALL);
        i.putExtra(EXTRA_PACKAGES, new ArrayList<>(packages));
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo arrancar el servicio: " + e);
        }
    }

    /** Instala los paquetes que ya vienen dentro del APK (sin red). */
    public static void installBundled(Context ctx) {
        Intent i = new Intent(ctx, ToolboxService.class);
        i.setAction(ACTION_BUNDLED);
        try {
            ctx.startForegroundService(i);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo arrancar el servicio: " + e);
        }
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
        if (running) return START_NOT_STICKY;
        running = true;
        startForeground(NID, notify("Instalando herramientas", "Preparando...", 0, 0, true));
        Log.i(TAG, "foreground puesto, hilo de instalacion");
        new Thread(() -> {
            String error = null;
            try {
                LocalToolbox.Progress p = new LocalToolbox.Progress() {
                    @Override
                    public void onStep(String message) {
                        update(message, 0, 0, true);
                    }

                    @Override
                    public void onBytes(long done, long total) {
                        update("Descargando paquetes", done, total, false);
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
            if (err == null) {
                // Se reinicia el servidor para que el sandbox coja el PATH nuevo.
                LocalRuntime.stop();
                LocalServerService.start(this);
                update("Herramientas listas", 0, 0, false);
                selfTest(this);
            } else {
                update("Fallo: " + err, 0, 0, false);
            }
            running = false;
            stopForeground(true);
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
                "pip3 --version",
                "node --version",
                "make --version | head -1",
                "ssh -V",
                "curl --version | head -1",
                "wget --version | head -1",
                "which pkg",
                "dpkg --version | head -1",
                "apt-get --version | head -1",
                "dpkg -l | tail -1",
                "apt-cache policy git | head -3",
                "pkg list-installed 2>&1 | tail -1",
                "termux-battery-status | head -c 120",
                "termux-clipboard-get",
                "termux-wifi-connectioninfo | head -c 120",
                "termux-sensor-list | head -c 120",
                "termux-clipboard-set 'texto de prueba'",
                "termux-clipboard-get",
                "termux-tts-speak 'hola' | head -c 120",
                "termux-tts-engines | head -c 150",
                "termux-brightness",
                "termux-volume",
                "termux-sensor accelerometer | head -c 150",
                "termux-toast 'hola desde opencode'",
                "termux-vibrate 200"
        };
        for (String c : cmds) {
            String out = LocalRuntime.runInSandbox(ctx, c, 30000);
            Log.i(TAG, "self-test [" + c + "] -> " + out.trim().replace("\n", " | "));
        }
    }

    private void update(String text, long done, long total, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID, notify(text, text, done, total, indeterminate));
    }

    private Notification notify(String title, String text, long done, long total,
            boolean indeterminate) {
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
                .setOngoing(true);
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
