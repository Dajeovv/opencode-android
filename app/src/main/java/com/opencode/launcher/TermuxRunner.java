package com.opencode.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

/** Arranca opencode en Termux por el canal documentado RUN_COMMAND. */
public class TermuxRunner {

    public static final String SCRIPT = "/data/data/com.termux/files/home/.opencode-android/serve.sh";
    public static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    public static final String[] SERVE_ARGS = new String[]{"-l", "-c", "opencode serve --hostname 127.0.0.1 --port 4096"};
    public static final String WORKDIR = "/data/data/com.termux/files/home";
    /** Sin comas ni comillas simples: via `am` viaja como un solo elemento separado por comas. */
    public static final String UPDATE_SCRIPT =
            "P=/sdcard/Download/opencode-android; mkdir -p $P ~/.opencode-android/bak 2>/dev/null;"
                    + " echo 5:iniciando > $P/update.progress;"
                    + " cp $PREFIX/bin/opencode ~/.opencode-android/bak/ 2>/dev/null;"
                    + " cp $PREFIX/libexec/opencode/opencode.bin ~/.opencode-android/bak/ 2>/dev/null;"
                    + " echo 15:backup-ok > $P/update.progress;"
                     + " echo 30:descargando-instalador > $P/update.progress;"
                      + " curl -fsSL https://raw.githubusercontent.com/Thr45hx/opencode-termux-native/main/install.sh -o ~/.opencode-android/oc-install.sh 2>/dev/null;"
                      + " TU=\"https://github.com/anomalyco/opencode/releases/latest/download/opencode-linux-arm64.tar.gz\";"
                      + " TOTALM=$(curl -sIL --max-time 20 \"$TU\" 2>/dev/null | grep -i '^content-length:' | tail -1 | tr -dc '0-9');"
                      + " [ -n \"$TOTALM\" ] && TOTALM=$((TOTALM/1048576)) || TOTALM=\"\";"
                      + " find $PREFIX/tmp -maxdepth 2 -name o.tar.gz -mmin +3 -delete 2>/dev/null;"
                      + " if [ -n \"$TOTALM\" ] && [ \"$TOTALM\" -gt 0 ] 2>/dev/null; then echo \"45:instalando-opencode 0M/${TOTALM}M\" > $P/update.progress;"
                      + " else echo \"45:instalando-opencode\" > $P/update.progress; fi;"
                      + " (while sleep 10; do if [ -z \"$f\" ]; then f=$(ls -t $PREFIX/tmp/tmp.*/o.tar.gz 2>/dev/null | head -1); fi;"
                      + " if [ -n \"$f\" ]; then D=$(du -m \"$f\" 2>/dev/null | cut -f1);"
                      + " if [ -n \"$D\" ] && [ -n \"$TOTALM\" ] && [ \"$TOTALM\" -gt 0 ] 2>/dev/null; then echo \"45:instalando-opencode ${D}M/${TOTALM}M\" > $P/update.progress;"
                      + " else echo \"45:instalando-opencode\" > $P/update.progress; fi; fi; done) & MBPID=$!;"
                      + " timeout 900 bash ~/.opencode-android/oc-install.sh 2>&1 | tail -3;"
                     + " EC=${PIPESTATUS[0]}; kill $MBPID 2>/dev/null;"
                    + " echo 90:autoupdate-off > $P/update.progress;"
                    + " F=~/.config/opencode/opencode.jsonc; mkdir -p ~/.config/opencode;"
                    + " [ -f \"$F\" ] || echo \"{}\" > \"$F\";"
                    + " cp \"$F\" \"$F.bak\" 2>/dev/null;"
                    + " if grep -q \"\\\"autoupdate\\\"\" \"$F\";"
                    + " then sed -i -E \"s/\\\"autoupdate\\\"([[:space:]]*:[[:space:]]*)(true|false|\\\"[^\\\"]*\\\"|[0-9.]+)/\\\"autoupdate\\\"\\1false/\" \"$F\";"
                    + " elif grep -q \"{[[:space:]]*}\" \"$F\";"
                    + " then sed -i -E \"s/\\{[[:space:]]*\\}/{\\n  \\\"autoupdate\\\": false\\n}/\" \"$F\";"
                    + " else sed -i \"\\$s/}/  \\\"autoupdate\\\": false\\n}/\" \"$F\"; fi;"
                    + " V=$(grep \"autoupdate\" \"$F\" | head -1);"
                    + " echo \"100:fin EXIT:$EC $V\" > $P/update.progress;";

    /** Mata el servidor opencode DENTRO de Termux (sin root no se puede matar proceso ajeno). */
    public static final String STOP_SCRIPT =
            "pkill -9 -f 'opencode serve' 2>/dev/null;"
                    + " sleep 1;"
                    + " (ss -ltn 2>/dev/null || netstat -ltn 2>/dev/null) | grep -q ':4096 '"
                    + " && echo 'stop:4096-sigue-abierto' || echo 'stop:4096-cerrado';";

    /** Repara opencode.jsonc DENTRO de Termux: doble :: y autoupdate=false (sin root). */
    public static final String REPAIR_SCRIPT =
            "F=~/.config/opencode/opencode.jsonc; mkdir -p ~/.config/opencode;"
                    + " [ -f \"$F\" ] || echo '{}' > \"$F\";"
                    + " cp \"$F\" \"$F.bak\" 2>/dev/null;"
                    + " sed -i 's/\"autoupdate\"[[:space:]]*::/\"autoupdate\":/' \"$F\";"
                    + " if grep -q '\"autoupdate\"' \"$F\";"
                    + " then sed -i -E 's/\"autoupdate\"([[:space:]]*:[[:space:]]*)(true|false|\"[^\"]*\"|[0-9.]+)/\"autoupdate\"\\1false/' \"$F\";"
                    + " elif grep -q '{[[:space:]]*}' \"$F\";"
                    + " then sed -i -E 's/\\{[[:space:]]*\\}/{\\n  \"autoupdate\": false\\n}/' \"$F\";"
                    + " else sed -i '1s/{/&\\n  \"autoupdate\": false,/' \"$F\"; fi;"
                    + " grep '\"autoupdate\"' \"$F\" | head -1; echo repair-ok;";

    /**
     * Via oficial (wiki termux-app RUN_COMMAND-Intent): startService EXPLICITO a
     * com.termux.app.RunCommandService. Un sendBroadcast a un Service no hace nada.
     */
    public static String startViaService(Context ctx) {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
            i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", SERVE_ARGS);
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", WORKDIR);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            // startService primero (sin deadline de foreground); fallback a foreground.
            try {
                ctx.startService(i);
            } catch (IllegalStateException e) {
                ctx.startForegroundService(i);
            }
            return "RUN_COMMAND enviado por servicio a com.termux";
        } catch (IllegalStateException e) {
            return "RUN_COMMAND bloqueado por Android (background): " + e.getMessage();
        } catch (SecurityException e) {
            return "RUN_COMMAND sin permiso: " + e.getMessage();
        } catch (Exception e) {
            return "RUN_COMMAND fallo: " + e.getMessage();
        }
    }

    /** Generico: ejecuta `inner` con bash dentro de Termux por intent (sin root).
     *  `inner` viaja como UN elemento del array: aqui SI valen comillas y espacios. */
    public static String runInTermux(Context ctx, String inner, boolean background, String tag) {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
            i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-l", "-c", inner});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", WORKDIR);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
            if (!background) i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
            // startService primero (sin deadline de foreground: inmune al
            // ForegroundServiceDidNotStartInTime si Termux tarda en arrancar);
            // si el sistema lo bloquea por background, fallback a foreground.
            try {
                ctx.startService(i);
            } catch (IllegalStateException e) {
                ctx.startForegroundService(i);
            }
            return tag + " enviado a Termux (intent)";
        } catch (IllegalStateException e) {
            return tag + " bloqueado por Android (background): " + e.getMessage();
        } catch (SecurityException e) {
            return tag + " sin permiso: falta allow-external-apps en Termux. Usa el boton Setup,"
                    + " pega el comando UNA vez en Termux y reintenta. (" + e.getMessage() + ")";
        } catch (Exception e) {
            return tag + " fallo: " + e.getMessage();
        }
    }

    /** Detener servidor sin root (pkill dentro de Termux). */
    public static String stopViaService(Context ctx) {
        return runInTermux(ctx, STOP_SCRIPT, true, "Detener");
    }

    /** Reparar config sin root (sed dentro de Termux). */
    public static String repairViaService(Context ctx) {
        return runInTermux(ctx, REPAIR_SCRIPT, true, "Reparar-config");
    }

    /** Linea permission para opencode.jsonc segun el interruptor PROPIO de la app.
     *  El interruptor de la web de opencode no hace nada en modo serve (solo guarda
     *  en localStorage y nada lo lee), asi que este si escribe la config de verdad:
     *  ON = "permission": "allow" (todo automatico); OFF = solo lo que por defecto
     *  ya pregunta (doom_loop, external_directory). Siempre una sola linea: al
     *  reemplazar se conserva la coma que hubiera, y al insertar se pone la que toca.
     *  Si el bloque permission es multilinea (hecho a mano) no se toca y se avisa. */
    public static String permScript(boolean allow) {
        String v = allow ? "\\\"allow\\\""
                : "{\\\"doom_loop\\\": \\\"ask\\\", \\\"external_directory\\\": \\\"ask\\\"}";
        return "F=~/.config/opencode/opencode.jsonc; mkdir -p ~/.config/opencode;"
                + " [ -f \"$F\" ] || echo '{}' > \"$F\";"
                + " [ -f \"$F.launcher-bak\" ] || cp \"$F\" \"$F.launcher-bak\" 2>/dev/null;"
                + " if grep -qE '\"permission\"[[:space:]]*:[[:space:]]*(\"[^\"]*\"|\\{[^}]*\\})[[:space:]]*,?[[:space:]]*$' \"$F\";"
                + " then sed -i -E 's/\"permission\"([[:space:]]*:[[:space:]]*)(\"[^\"]*\"|\\{[^}]*\\})/\"permission\"\\1" + v + "/' \"$F\";"
                + " elif grep -q '\"permission\"' \"$F\";"
                + " then echo 'perm-manual: el bloque permission es multilinea, editalo a mano';"
                + " elif grep -q '{[[:space:]]*}' \"$F\";"
                + " then sed -i -E 's/\\{[[:space:]]*\\}/{\\n  \"permission\": " + v + "\\n}/' \"$F\";"
                + " elif grep -q ':' \"$F\";"
                + " then sed -i '1s/{/&\\n  \"permission\": " + v + ",/' \"$F\";"
                + " else sed -i '$s/^[[:space:]]*}[[:space:]]*$/  \"permission\": " + v + "\\n}/' \"$F\"; fi;"
                + " grep '\"permission\"' \"$F\" | head -1;"
                + " grep -q '\"permission\"' \"$F\" && echo perm-ok || echo perm-FALLO;";
    }

    /** Aplica el interruptor propio dentro de Termux por intent (vale con y sin root). */
    public static String applyPermViaService(Context ctx, boolean allow) {
        return runInTermux(ctx, permScript(allow), true, "Permisos-auto");
    }

    /** Wake-lock sin root (necesita app Termux:API instalada). */
    public static String wakeViaService(Context ctx, boolean on) {
        String inner = on
                ? "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock && echo wakelock-on || echo no-termux-api"
                : "command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock && echo wakelock-off || echo no-termux-api";
        return runInTermux(ctx, inner, true, "Wake-lock");
    }
    public static boolean isTermuxInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Overlay de Termux ("mostrar sobre otras apps"): lo exige para sesiones VISIBLES por intent. */
    public static boolean isOverlayGranted(Context ctx) {
        try {
            int uid = ctx.getPackageManager().getPackageUid("com.termux", 0);
            android.app.AppOpsManager ops =
                    (android.app.AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            return ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    uid, "com.termux") == android.app.AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /** Actualizacion en SEGUNDO PLANO de Termux (tarda minutos; el usuario
     *  se queda en la app viendo la barra de %; Termux no salta al frente).
     *  El progreso se sigue por /sdcard/Download/opencode-android/update.progress. */
    public static String startUpdateViaService(Context ctx) {
        try {
            Intent i = new Intent("com.termux.RUN_COMMAND");
            i.setComponent(new ComponentName("com.termux", "com.termux.app.RunCommandService"));
            i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
            i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS",
                    new String[]{"-l", "-c", UPDATE_SCRIPT});
            i.putExtra("com.termux.RUN_COMMAND_WORKDIR", WORKDIR);
            i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            // startService primero (sin deadline de foreground: inmune al
            // ForegroundServiceDidNotStartInTime); fallback a foreground.
            try {
                ctx.startService(i);
            } catch (IllegalStateException e) {
                ctx.startForegroundService(i);
            }
            return "Actualizacion enviada a Termux (segundo plano, mira el % en la app)";
        } catch (Exception e) {
            return "Actualizacion fallo: " + e.getMessage();
        }
    }

    public static String openTermuxApp(Context ctx) {        try {
            Intent i = ctx.getPackageManager().getLaunchIntentForPackage("com.termux");
            if (i == null) return "Termux no instalado (com.termux no encontrado)";
            ctx.startActivity(i);
            return "Termux abierto";
        } catch (Exception e) {
            return "No se pudo abrir Termux: " + e.getMessage();
        }
    }
}
