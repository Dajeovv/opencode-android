package com.opencode.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Vigía: si el puerto no responde y no hay pausa manual, avisa o rearranca. */
public class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                String mode = ServerWatch.wdMode(ctx);
                if ("off".equals(mode)) return;
                if (ServerWatch.portOpen()) return;
                boolean hold;
                try {
                    hold = ServerWatch.p(ctx).getBoolean(ServerWatch.PREF_WD_HOLD, false);
                } catch (Exception e) {
                    hold = false;
                }
                if (hold) return; // parado a mano: no tocar hasta el próximo arranque
                if ("notify".equals(mode)) {
                    ServerWatch.notify(ctx,
                            ServerWatch.t(ctx, "Servidor caído", "Server down"),
                            ServerWatch.t(ctx, "Toca para abrir la app y arrancarlo",
                                    "Tap to open the app and start it"));
                } else if ("restart".equals(mode)) {
                    ServerWatch.startHeadless(ctx);
                }
            } finally {
                pr.finish();
            }
        }).start();
    }
}
