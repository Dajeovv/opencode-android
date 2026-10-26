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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

public class LocalServerService extends Service {
    public static final String ACTION_START = "com.opencode.launcher.LOCAL_START";
    public static final String ACTION_STOP = "com.opencode.launcher.LOCAL_STOP";
    private static final String CHANNEL = "local_runtime";
    private static final int NID = 2201;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, LocalServerService.class).setAction(ACTION_START);
        ContextCompat.startForegroundService(ctx, i);
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, LocalServerService.class).setAction(ACTION_STOP);
        try {
            ctx.startService(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            LocalRuntime.stop();
            ToolboxServer.stop();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        // La caja de herramientas viaja dentro del APK: se instala sola la
        // primera vez, sin que el usuario tenga que hacer nada.
        if (!LocalToolbox.complete(this)) {
            ToolboxService.installBundled(this);
        } else {
            // Autodiagnostico una vez, para dejar constancia en el registro
            // de que las herramientas responden dentro del sandbox.
            SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
            if (!sp.getBoolean("toolbox_selftest", false)) {
                sp.edit().putBoolean("toolbox_selftest", true).apply();
                new Thread(() -> ToolboxService.selfTest(this), "toolbox-selftest").start();
            }
        }
        ToolboxServer.start(this);
        LocalToolbox.refreshShim(this);
        startForeground(NID, notification());
        // El arranque nativo hace health checks HTTP: nunca en el hilo
        // principal (NetworkOnMainThreadException). El servicio ya es
        // foreground, asi que puede esperar en segundo plano.
        new Thread(() -> {
            try {
                LocalRuntime.start(getApplicationContext(), BuildConfig.SERVER_PORT);
            } catch (Exception e) {
                Log.w("OpenCodeLocal", "no se pudo arrancar el runtime: " + e, e);
                new Handler(Looper.getMainLooper()).post(() -> {
                    NotificationManager nm =
                            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null) {
                        nm.notify(NID, new NotificationCompat.Builder(this, CHANNEL)
                                .setSmallIcon(android.R.drawable.stat_notify_error)
                                .setContentTitle(ServerWatch.t(this, "OpenCode no arranc\u00f3", "OpenCode did not start"))
                                .setContentText(String.valueOf(e.getMessage()))
                                .setAutoCancel(true)
                                .build());
                    }
                    stopForeground(true);
                    stopSelf();
                });
            }
        }, "local-runtime-start").start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        LocalRuntime.stop();
        ToolboxServer.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        ServerWatch.t(this, "Servidor local", "Local server"), NotificationManager.IMPORTANCE_LOW);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, fl);

        Intent stop = new Intent(this, LocalServerService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, fl);

        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(ServerWatch.t(this, "OpenCode local", "OpenCode local"))
                .setContentText(ServerWatch.t(this, "Servidor en 127.0.0.1:", "Server on 127.0.0.1:") + BuildConfig.SERVER_PORT)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, ServerWatch.t(this, "Detener", "Stop"), stopPi)
                .build();
    }
}

