package com.opencode.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Al encender: rearma el vigía y, si el usuario lo activó, arranca el servidor. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        ServerWatch.arm(ctx);
        ServerWatch.rearmUpdateCheck(ctx);
        if (!ServerWatch.bootOn(ctx)) return;
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                ServerWatch.startHeadless(ctx);
            } finally {
                pr.finish();
            }
        }).start();
    }
}
