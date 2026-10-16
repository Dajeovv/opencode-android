package com.opencode.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class RootUtil {

    /** Igual que exec pero con tope: si el proceso no termina, lo mata y lo dice. */
    public static String exec(String... cmd) {
        return execTimeout(30, cmd);
    }

    public static String execTimeout(int secs, String... cmd) {
        final StringBuilder out = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            Thread drain = new Thread(() -> {
                try {
                    BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append("\n");
                        }
                    }
                } catch (Exception ignored) {
                }
            });
            drain.setDaemon(true);
            drain.start();
            boolean done = p.waitFor(secs, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                synchronized (out) {
                    out.append("TIMEOUT tras " + secs + "s (proceso matado)\n");
                }
            }
            drain.join(3000);
            synchronized (out) {
                try {
                    out.append("[exit=").append(p.exitValue()).append("]");
                } catch (Exception e) {
                    out.append("[exit=?]");
                }
            }
        } catch (Exception e) {
            out.append("ERROR: ").append(e.getMessage());
        }
        return out.toString();
    }

    /** Existe binario su en el dispositivo. */
    public static String hasSu() {
        return exec("sh", "-c", "command -v su");
    }

    /** Pide root ejecutando `su -c id`. Si el sistema muestra el dialogo de Magisk/KernelSU, el usuario lo concede aqui. */
    public static String requestRoot() {
        return exec("su", "-c", "id");
    }

    public static boolean isRooted(String suIdOutput) {
        return suIdOutput != null && suIdOutput.contains("uid=0");
    }

    /**
     * Via principal con root: manda el MISMO intent RUN_COMMAND pero via `am`
     * desde una shell root, apuntando al script de Termux (login-shell, resuelve
     * `opencode` igual que tu terminal manual).
     */
    public static String startServerViaAm() {
        String cmd = "am startservice --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c,opencode serve --hostname 127.0.0.1 --port 4096'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    /** Igual que startServerViaAm pero como foreground-service (evita "app is in background"). */
    public static String startViaSuForeground() {
        String cmd = "am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c,opencode serve --hostname 127.0.0.1 --port 4096'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    /** Despierta Termux sin arrancar nada (para que nsenter tenga proceso).
     *  Sin comas ni comillas simples: viaja en --esa por am. */
    public static String pingTermux() {
        String cmd = "am startservice --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c,echo despierta-termux'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    /** Preflight: Termux con bootstrap? opencode visible? Primera linea PREFLIGHT=OK|EMPTY|NOBIN. */
    public static String preflight() {
        return exec("su", "-c",
                "export H=/data/data/com.termux/files/home P=/data/data/com.termux/files/usr;"
                        + " if [ ! -x $P/bin/bash ]; then echo PREFLIGHT=EMPTY-termux;"
                        + " echo 'com.termux sin usr/bin (ni bash): abre la app Termux para completar su instalacion.'; exit 0; fi;"
                        + " F=''; for d in $P/bin $H/.opencode/bin $H/.npm-global/bin $H/bin $H/.local/bin $H/go/bin $H/.cargo/bin $H/agents/opencode; do"
                        + " for b in opencode opencode-ai; do [ -x $d/$b ] && F=$d/$b; done; done;"
                        + " [ -z \"$F\" ] && F=$(PATH=$P/bin:$H/.opencode/bin:$H/.npm-global/bin:$H/.local/bin:$H/bin:$PATH command -v opencode opencode-ai 2>/dev/null | head -1);"
                        + " if [ -z \"$F\" ]; then echo PREFLIGHT=NOBIN;"
                        + " echo 'Bootstrap OK pero no veo opencode desde fuera (tu shell manual si lo ve). Intento igual con login-shell.';"
                        + " else echo PREFLIGHT=OK; echo binario:$F; fi");
    }

    /** Crea/actualiza el script de arranque: busca el binario en 6 sitios (npm-global, etc). */
    public static String ensureServeScript() {
        String home = "/data/data/com.termux/files/home";
        String dir = home + "/.opencode-android";
        return exec("su", "-c",
                "mkdir -p " + dir
                        + " && printf '%s\\n' '#!/data/data/com.termux/files/usr/bin/bash -l'"
                        + " 'for d in \"$PREFIX/bin\" \"$HOME/.opencode/bin\" \"$HOME/.npm-global/bin\" \"$HOME/bin\" \"$HOME/.local/bin\" \"$HOME/go/bin\" \"$HOME/.cargo/bin\" \"$HOME/agents/opencode\"; do'"
                        + " '  [ -x \"$d/opencode\" ] && exec \"$d/opencode\" serve --hostname 127.0.0.1 --port 4096'"
                        + " '  [ -x \"$d/opencode-ai\" ] && exec \"$d/opencode-ai\" serve --hostname 127.0.0.1 --port 4096'"
                        + " 'done'"
                        + " 'exec opencode serve --hostname 127.0.0.1 --port 4096'"
                        + " > " + dir + "/serve.sh"
                        + " && chmod 755 " + dir + "/serve.sh"
                        + " && cat " + dir + "/serve.sh");
    }

    /** Ejecuta `script` con el sh de Termux DENTRO de su namespace (el root ve su disco vacio).
     *  Via stdin+base64: sin lios de comillas entre capas. HOME/PREFIX explicitos. */
    public static String nsenterTermux(String script) {
        String full = "export HOME=/data/data/com.termux/files/home"
                + " PREFIX=/data/data/com.termux/files/usr"
                + " PATH=/data/data/com.termux/files/usr/bin:$PATH; cd ~;" + script;
        String b64 = android.util.Base64.encodeToString(
                full.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        return exec("su", "-c",
                "P=$(pidof -s com.termux);"
                        + " if [ -z \"$P\" ]; then echo termux-no-activo-abrelo-una-vez; exit 0; fi;"
                        + " echo " + b64 + " | base64 -d"
                        + " | nsenter -m -t \"$P\" /data/data/com.termux/files/usr/bin/sh");
    }

    /** Aplica el interruptor PROPIO de permisos de forma SINCRONA (root): entra al
     *  namespace de Termux, fusiona la linea permission y devuelve el dueño
     *  original al fichero (lo que se crea como root Termux no podria leerlo). */
    public static String applyPermSync(boolean allow) {
        String f = "/data/data/com.termux/files/home/.config/opencode/opencode.jsonc";
        return nsenterTermux("F=" + f + ";"
                + " sed -i \"s/\\\"autoupdate\\\"[[:space:]]*::/\\\"autoupdate\\\":/\" \"$F\" 2>/dev/null;"
                + " U=$(stat -c %u:%g \"" + f + "\" 2>/dev/null);"
                + " [ -n \"$U\" ] || U=$(stat -c %u:%g ~/.config/opencode 2>/dev/null);"
                + TermuxRunner.permScript(allow)
                + " [ -n \"$U\" ] && chown \"$U\" \"" + f + "\" \"" + f + ".launcher-bak\" 2>/dev/null;"
                + " ls -l \"" + f + "\" 2>/dev/null | head -1;");
    }

    /** Comprueba con root real si opencode quedo instalado en Termux (verificacion del setup). */
    public static String checkOpencodeInstalled() {
        return nsenterTermux(
                "(command -v opencode >/dev/null 2>&1 && echo opencode-ok || echo opencode-FALTA);"
                        + " echo BINDIR:; ls $PREFIX/bin 2>&1 | head -20;"
                        + " echo HOME:; ls -a ~ 2>&1 | head -30;"
                        + " echo CONFIG:; ls -l ~/.config/opencode/ 2>&1 | head -10");
    }

    /** Bootstrap allow-external-apps con root real: entra al namespace de Termux
     *  (el root ve su disco vacio) y escribe la propiedad. Si cambia, reinicia Termux
     *  en frio y ESPERA su muerte real: mandar el setup antes mataba la sesion al nacer. */
    public static String bootstrapExternalApps() {
        String out = nsenterTermux("mkdir -p ~/.termux;"
                + " if grep -qi \"^allow-external-apps\" ~/.termux/termux.properties 2>/dev/null;"
                + " then echo prop-ya-ok;"
                + " else echo \"allow-external-apps = true\" >> ~/.termux/termux.properties; echo prop-escrita; fi;"
                + " grep \"^allow-external-apps\" ~/.termux/termux.properties 2>/dev/null");
        if (out.contains("prop-escrita")) {
            exec("su", "-c", "am force-stop com.termux");
            boolean dead = false;
            for (int k = 0; k < 20; k++) {
                if (!termuxAlive()) { dead = true; break; }
                try { Thread.sleep(500); } catch (InterruptedException e) { break; }
            }
            out += dead ? "\ntermux-muerto-confirmado" : "\ntermux-sigue-vivo-aviso";
        }
        return out;
    }

    /** Hay proceso com.termux vivo (para serializar reinicio antes del setup). */
    public static boolean termuxAlive() {
        String o = exec("su", "-c", "pidof com.termux");
        if (o == null) return false;
        for (String ln : o.split("\n")) {
            if (ln.trim().matches("[0-9]+")) return true;
        }
        return false;
    }

    /**
     * Activa allow-external-apps (lo exige RunCommandService) y reinicia Termux SOLO si
     * la propiedad es nueva, para que la lea al arrancar. Idempotente.
     */
    public static String ensureTermuxSetup() {
        return exec("su", "-c",
                "export H=/data/data/com.termux/files/home;"
                        + " mkdir -p $H/.termux;"
                        + " if grep -q '^allow-external-apps *= *true' $H/.termux/termux.properties 2>/dev/null;"
                        + " then echo already:true;"
                        + " else echo 'allow-external-apps = true' >> $H/.termux/termux.properties;"
                        + " echo written:true; am force-stop com.termux 2>&1; echo termux_restarted; sleep 2; fi;"
                        + " grep '^allow-external-apps' $H/.termux/termux.properties 2>&1");
    }

    /**
     * Auto-reparacion del opencode.jsonc: un sed viejo dejaba `"autoupdate"::`
     * (doble colon) y el servidor moria al arrancar sin decir nada. Colapsa
     * `::` a `:` valido antes de lanzar. Sin comas ni comillas simples: viaja
     * dentro de --esa separado por comas.
     */
    public static String repairConfig() {
        String script = "F=/data/data/com.termux/files/home/.config/opencode/opencode.jsonc;"
                + " sed -i \"s/\\\"autoupdate\\\"[[:space:]]*::/\\\"autoupdate\\\":/\" $F 2>/dev/null;"
                + " grep \"autoupdate\" $F 2>/dev/null || echo cfg-sin-autoupdate";
        String cmd = "am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c," + script + "'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    /** Actualizacion por root: mismo RUN_COMMAND en segundo plano (sin saltar a Termux). */    public static String startUpdateViaAmBg() {
        String cmd = "am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c," + TermuxRunner.UPDATE_SCRIPT + "'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    /** Ultimo recurso: demonio directo con setsid para que sobreviva a la sesion su. */
    public static String startDaemonDirect() {
        return exec("su", "-c",
                "export HOME=/data/data/com.termux/files/home PREFIX=/data/data/com.termux/files/usr;"
                        + " setsid nohup $PREFIX/bin/bash -l '" + TermuxRunner.SCRIPT + "'"
                        + " >/dev/null 2>&1 < /dev/null & echo launched:$!");
    }

    /** Compara la vista del namespace root propio vs el de init: detecta aislamiento de montajes. */
    public static String namespaceViews() {
        String target = "/data/data/com.termux/files/usr/bin";
        StringBuilder sb = new StringBuilder();
        sb.append("vista-ns-propio:\n")
                .append(exec("su", "-c", "ls " + target + " 2>&1 | head -5"))
                .append("\n");
        String which = exec("su", "-c", "command -v nsenter 2>&1");
        if (which.contains("nsenter")) {
            sb.append("vista-ns-init:\n")
                    .append(exec("su", "-c",
                            "nsenter --mount=/proc/1/ns/mnt ls " + target + " 2>&1 | head -5"))
                    .append("\n");
        } else {
            sb.append("sin-nsenter: ").append(which).append("\n");
        }
        sb.append("montajes-termux:\n")
                .append(exec("su", "-c", "cat /proc/self/mountinfo 2>/dev/null | grep -i -m5 termux; echo fin-mounts"));
        return sb.toString();
    }

    /** Diagnostico acotado: paquetes termux, que hay en bin/home y donde esta opencode. */
    public static String diagnoseEnv() {
        return exec("su", "-c",
                "export H=/data/data/com.termux/files/home P=/data/data/com.termux/files/usr;"
                        + " echo pkgs:; pm list packages 2>/dev/null | grep -i termux;"
                        + " echo datadirs:; ls /data/data 2>/dev/null | grep -i termux;"
                        + " echo home_dot:; ls -a $H 2>&1 | head -20;"
                        + " echo bindir:; ls $P/bin 2>&1 | head -40;"
                        + " echo npm_local:; ls $H/.npm-global/bin $H/.local/bin $H/bin 2>&1 | head -20;"
                        + " echo lookup:; PATH=$P/bin:$H/.opencode/bin:$H/.npm-global/bin:$H/.local/bin:$H/bin:$PATH"
                        + " command -v opencode opencode-ai 2>&1;"
                        + " echo script:; ls -l $H/.opencode-android/serve.sh 2>&1");
    }

    /** Comando rapido en contexto Termux sin esperar nada. `inner` sin comas ni comillas simples. */
    public static String fireTermuxCmd(String inner) {
        if (!WAKE_ON.equals(inner) && !WAKE_OFF.equals(inner)) {
            return "ERROR: comando no permitido";
        }
        String cmd = "am start-foreground-service --user 0 -n com.termux/com.termux.app.RunCommandService"
                + " -a com.termux.RUN_COMMAND"
                + " --es com.termux.RUN_COMMAND_PATH '/data/data/com.termux/files/usr/bin/bash'"
                + " --esa com.termux.RUN_COMMAND_ARGUMENTS '-l,-c," + inner + "'"
                + " --es com.termux.RUN_COMMAND_WORKDIR '/data/data/com.termux/files/home'"
                + " --ez com.termux.RUN_COMMAND_BACKGROUND true";
        return exec("su", "-c", cmd);
    }

    public static final String WAKE_ON =
            "command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock && echo wakelock-on || echo no-termux-api";
    public static final String WAKE_OFF =
            "command -v termux-wake-unlock >/dev/null 2>&1 && termux-wake-unlock && echo wakelock-off || echo no-termux-api";

    /** Desactiva el phantom-process-killer (Android 12+) que deja a los agentes pensando. */
    public static String stabilizeAndroid() {
        return exec("su", "-c",
                "settings put global settings_enable_monitor_phantom_procs false 2>&1;"
                        + " device_config put activity_manager max_phantom_processes 2147483647 2>&1;"
                        + " echo phantom_monitor=$(settings get global settings_enable_monitor_phantom_procs 2>&1);"
                        + " echo max_phantom=$(device_config get activity_manager max_phantom_processes 2>&1)");
    }

    /**
     * Detiene opencode recorriendo PIDs (acotado y rapido, sin pkill ni
     * barridos /proc-fd): mata por coincidencia de cmdline con patrones
     * construidos por partes para no casar consigo mismo. La verificacion
     * final la hace la app por socket.
     */
    public static String stopServer() {
        return exec("su", "-c",
                "S='INODE'; S=$S'2=';"
                        + " for p in /proc/[0-9]*; do"
                        + " c=$(tr '\\0' ' ' < $p/cmdline 2>/dev/null);"
                        + " case \"$c\" in"
                        + " *opencode\\ serve*) kill -9 ${p#/proc/} 2>/dev/null; echo killed-server:${p#/proc/};;"
                        + " *$S*) kill -9 ${p#/proc/} 2>/dev/null; echo killed-spinner:${p#/proc/};;"
                        + " esac; done; echo fin-parada");
    }
}
