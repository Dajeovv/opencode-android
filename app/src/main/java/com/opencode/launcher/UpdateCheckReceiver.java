package com.opencode.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Chequeo diario de updates (opencode + launcher) aunque la app este cerrada.
 *  Solo avisa (notificacion); la descarga/instalacion se hace desde la app. */
public class UpdateCheckReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ServerWatch.rearmUpdateCheck(ctx);
            return;
        }
        ServerWatch.armUpdateCheck(ctx);
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                // v2: el runtime va dentro del APK; solo se avisa del launcher.
                if (!BuildConfig.V2) {
                // Binario opencode: avisar solo si nadie lo hizo ya para esta version.
                try {
                    boolean local = LocalRuntime.available(ctx);
                    String latest = UpdateCheck.latestOpencode();
                    String installed = local
                            ? LocalRuntime.activeVersion(ctx)
                            : UpdateCheck.p(ctx).getString("upd_installed", "");
                    if (latest != null && !installed.isEmpty()
                            && UpdateCheck.cmpVer(latest, installed) > 0
                            && !latest.equals(UpdateCheck.p(ctx).getString("upd_notified", ""))) {
                        String text = installed + " -> " + latest + (local
                                ? ServerWatch.t(ctx,
                                        ": toca para descargar e instalar",
                                        ": tap to download and install")
                                : ServerWatch.t(ctx,
                                        ": abre la app para instalarla",
                                        ": open the app to install it"));
                        if (ServerWatch.notifyOpenCodeUpdate(ctx,
                                ServerWatch.t(ctx, "Actualización de opencode", "opencode update"),
                                text, latest)) {
                            UpdateCheck.p(ctx).edit().putString("upd_notified", latest).apply();
                        }
                    } else if (latest != null && !installed.isEmpty()
                            && UpdateCheck.cmpVer(latest, installed) <= 0) {
                        // Ya no hay update: retirar un aviso viejo si seguia visible.
                        android.app.NotificationManager nm =
                                (android.app.NotificationManager) ctx.getSystemService(
                                        Context.NOTIFICATION_SERVICE);
                        if (nm != null) nm.cancel(ServerWatch.NID_OC);
                    }
                } catch (Exception ignored) {
                }
                }
                // Launcher: avisar si el release trae APK mas nuevo que esta version.
                try {
                    String[] rel = UpdateCheck.launcherRelease();
                    String cur = UpdateCheck.appVersion(ctx);
                    if (rel != null && !cur.isEmpty() && UpdateCheck.cmpVer(rel[0], cur) > 0
                            && !rel[0].equals(UpdateCheck.p(ctx).getString("appupd_notified", ""))) {
                        if (ServerWatch.notifyId(ctx, ServerWatch.NID_APP,
                                ServerWatch.t(ctx, "Nueva versión de la app", "New app version"),
                                ServerWatch.t(ctx, "Disponible ", "Available ") + rel[0]
                                        + ServerWatch.t(ctx, ": abre la app para descargarla",
                                                ": open the app to download it"))) {
                            UpdateCheck.p(ctx).edit().putString("appupd_notified", rel[0]).apply();
                        }
                    }
                } catch (Exception ignored) {
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
