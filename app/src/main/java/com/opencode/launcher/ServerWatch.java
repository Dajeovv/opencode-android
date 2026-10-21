package com.opencode.launcher;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Arranque sin UI (boot, vigía) + alarma del vigía + avisos. Sin dependencias nuevas. */
public class ServerWatch {
    public static final String PREF_BOOT = "boot_start";
    public static final String PREF_WD_MODE = "watchdog_mode"; // off|notify|restart
    public static final String PREF_WD_MIN = "watchdog_min"; // 15|30|60
    public static final String PREF_WD_HOLD = "watchdog_hold";
    static final String CH = "vigia";
    static final int NID = 2101;
    static final String CH_UPD = "actualizaciones";
    public static final int NID_OC = 2102;
    public static final int NID_APP = 2103;
    static final int UPD_REQ = 7;
    static final String HOST = "127.0.0.1";
    static final int PORT = BuildConfig.SERVER_PORT;

    static SharedPreferences p(Context c) {
        return c.getSharedPreferences("app", Context.MODE_PRIVATE);
    }

    public static boolean bootOn(Context c) {
        try {
            return p(c).getBoolean(PREF_BOOT, false);
        } catch (Exception e) {
            return false;
        }
    }

    public static String wdMode(Context c) {
        try {
            String m = p(c).getString(PREF_WD_MODE, "off");
            return ("notify".equals(m) || "restart".equals(m)) ? m : "off";
        } catch (Exception e) {
            return "off";
        }
    }

    public static int wdMin(Context c) {
        try {
            int m = p(c).getInt(PREF_WD_MIN, 30);
            return (m == 15 || m == 60) ? m : 30;
        } catch (Exception e) {
            return 30;
        }
    }

    /** ES/EN fuera de la actividad (los receptores no tienen T()). */
    public static String t(Context c, String es, String en) {
        try {
            String l = p(c).getString("lang", "auto");
            if ("es".equals(l)) return es;
            if ("en".equals(l)) return en;
        } catch (Exception ignored) {
        }
        String dl = java.util.Locale.getDefault().getLanguage();
        return (dl != null && dl.startsWith("es")) ? es : en;
    }

    public static boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(HOST, PORT), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean rooted() {
        try {
            return RootUtil.isRooted(RootUtil.requestRoot());
        } catch (Exception e) {
            return false;
        }
    }

    /** Arranca el servidor sin UI (boot o vigía). Respeta root/sin-root y el
     *  interruptor permisos-auto. No espera al HTTP: quien llama decide. */
    public static String startHeadless(Context ctx) {
        StringBuilder t = new StringBuilder();
        boolean allow;
        try {
            allow = p(ctx).getBoolean("auto_perm", true);
        } catch (Exception e) {
            allow = true;
        }
        if (LocalRuntime.available(ctx)) {
            try {
                LocalServerService.start(ctx);
                return "local";
            } catch (Exception e) {
                return "local-fallo:" + e.getMessage();
            }
        }
        if (!TermuxRunner.isTermuxInstalled(ctx)) return "sin-termux";
        boolean root = rooted();
        t.append(root ? "root" : "noroot").append(";");
        try {
            if (root) {
                // Despierta Termux primero (nsenter exige proceso vivo),
                // luego merge sincrono, luego serve.
                t.append(RootUtil.pingTermux().split("\n")[0]).append(";");
                Thread.sleep(9000);
                t.append(RootUtil.applyPermSync(allow).split("\n")[0]).append(";");
                t.append(RootUtil.startViaSuForeground().split("\n")[0]).append(";");
            } else {
                t.append(TermuxRunner.repairViaService(ctx)).append(";");
                t.append(TermuxRunner.applyPermViaService(ctx, allow)).append(";");
                Thread.sleep(2500);
                t.append(TermuxRunner.startViaService(ctx)).append(";");
            }
        } catch (InterruptedException e) {
            t.append("interrumpido;");
        } catch (Exception e) {
            t.append("fallo:").append(String.valueOf(e.getMessage()));
        }
        return t.toString();
    }

    static PendingIntent wdIntent(Context ctx) {
        Intent i = new Intent(ctx, WatchdogReceiver.class);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, 0, i, fl);
    }

    /** Arma (o desarma con off) la alarma inexacta del vigía. Idempotente. */
    public static void arm(Context ctx) {
        try {
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = wdIntent(ctx);
            try {
                am.cancel(pi);
            } catch (Exception ignored) {
            }
            if ("off".equals(wdMode(ctx))) return;
            long interval = (long) wdMin(ctx) * 60L * 1000L;
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + interval, interval, pi);
        } catch (Exception ignored) {
        }
    }

    static PendingIntent updIntent(Context ctx) {
        Intent i = new Intent(ctx, UpdateCheckReceiver.class);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(ctx, UPD_REQ, i, fl);
    }

    /** Alarma diaria de updates (una sola vez; el receptor rearma la siguiente).
     *  No se reprograma en cada apertura para que no se posponga sin fin. */
    public static void armUpdateCheck(Context ctx) {
        try {
            SharedPreferences sp = p(ctx);
            if (sp.getLong("appupd_alarm_at", 0) > System.currentTimeMillis()) return;
            AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = updIntent(ctx);
            long next = System.currentTimeMillis() + AlarmManager.INTERVAL_DAY;
            am.set(AlarmManager.RTC_WAKEUP, next, pi);
            sp.edit().putLong("appupd_alarm_at", next).apply();
        } catch (Exception ignored) {
        }
    }

    /** Tras reiniciar las alarmas se pierden aunque la marca siga en prefs. */
    public static void rearmUpdateCheck(Context ctx) {
        p(ctx).edit().remove("appupd_alarm_at").apply();
        armUpdateCheck(ctx);
    }

    /** Aviso en el canal de actualizaciones. Devuelve true solo si se publico
     *  (permiso concedido, avisos activos y canal no bloqueado). */
    public static boolean notifyId(Context ctx, int nid, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return false;
            NotificationChannel ch = new NotificationChannel(CH_UPD, "Actualizaciones",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            NotificationChannel cur = nm.getNotificationChannel(CH_UPD);
            if (cur != null && cur.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, nid, open, fl);
            nm.notify(nid, new NotificationCompat.Builder(ctx, CH_UPD)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean notifyOpenCodeUpdate(Context ctx, String title, String text,
            String version) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !nm.areNotificationsEnabled()) return false;
            NotificationChannel ch = new NotificationChannel(CH_UPD, "Actualizaciones",
                    NotificationManager.IMPORTANCE_DEFAULT);
            nm.createNotificationChannel(ch);
            NotificationChannel cur = nm.getNotificationChannel(CH_UPD);
            if (cur != null && cur.getImportance() == NotificationManager.IMPORTANCE_NONE)
                return false;
            Intent install = new Intent(ctx, RuntimeUpdateService.class)
                    .setAction(RuntimeUpdateService.ACTION_INSTALL)
                    .putExtra(RuntimeUpdateService.EXTRA_VERSION, version);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getForegroundService(ctx, NID_OC, install, fl);
            nm.notify(NID_OC, new NotificationCompat.Builder(ctx, CH_UPD)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentIntent(pi)
                    .addAction(0, t(ctx, "Descargar", "Download"), pi)
                    .setAutoCancel(true)
                    .build());
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void notify(Context ctx, String title, String text) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx,
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(CH, "Vigia",
                        NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int fl = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(ctx, 0, open, fl);
            nm.notify(NID, new NotificationCompat.Builder(ctx, CH)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build());
        } catch (Exception ignored) {
        }
    }
}
