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
            ServerWatch.armUpdateCheck(ctx);
            return;
        }
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                // Binario opencode: avisar solo si nadie lo hizo ya para esta version.
                try {
                    String latest = UpdateCheck.latestOpencode();
                    String installed = UpdateCheck.p(ctx).getString("upd_installed", "");
                    if (latest != null && !installed.isEmpty()
                            && UpdateCheck.cmpVer(latest, installed) > 0
                            && !latest.equals(UpdateCheck.p(ctx).getString("upd_notified", ""))) {
                        UpdateCheck.p(ctx).edit().putString("upd_notified", latest).apply();
                        ServerWatch.notifyId(ctx, ServerWatch.NID_OC,
                                ServerWatch.t(ctx, "Actualización de opencode", "opencode update"),
                                installed + " → " + latest + ServerWatch.t(ctx,
                                        ": abre la app para instalarla",
                                        ": open the app to install it"));
                    }
                } catch (Exception ignored) {
                }
                // Launcher: avisar si el release trae APK mas nuevo que esta version.
                try {
                    String[] rel = UpdateCheck.launcherRelease();
                    String cur = UpdateCheck.appVersion(ctx);
                    if (rel != null && !cur.isEmpty() && UpdateCheck.cmpVer(rel[0], cur) > 0
                            && !rel[0].equals(UpdateCheck.p(ctx).getString("appupd_notified", ""))) {
                        UpdateCheck.p(ctx).edit().putString("appupd_notified", rel[0]).apply();
                        ServerWatch.notifyId(ctx, ServerWatch.NID_APP,
                                ServerWatch.t(ctx, "Nueva versión de la app", "New app version"),
                                ServerWatch.t(ctx, "Disponible ", "Available ") + rel[0]
                                        + ServerWatch.t(ctx, ": abre la app para descargarla",
                                                ": open the app to download it"));
                    }
                } catch (Exception ignored) {
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
