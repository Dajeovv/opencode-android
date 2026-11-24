package com.opencode.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Punto unico que decide como se ejecuta un comando en el runtime nativo:
 *
 *  - directo sobre Android para binarios del sistema,
 *  - con /system/bin/linker64 para los ELF del prefijo (git, python, node...),
 *    que Android no deja ejecutar con execve desde el directorio de la app,
 *  - o dentro del sandbox proot para paquetes (pkg/apt/dpkg), scripts del
 *    prefijo y operaciones de git que lanzan ayudantes externos.
 *
 * Genera en el prefijo de la caja de herramientas:
 *   oc-native-env.sh -> funciones que lanzan los ELF con el linker
 *   oc-shell         -> shell que usa el servidor (via trampolin del APK)
 *   oc-proot         -> ejecuta un comando dentro de proot
 *   oc-adb           -> mantiene el servidor adb en un proot aparte y ejecuta el comando
 *
 * Asi no hay "if command == git" repartidos por el codigo: la politica vive
 * aqui y los scripts se regeneran en cada arranque.
 */
public final class CommandResolver {
    private static final String TAG = "OpenCodeLocal";

    public static final String SHELL_NAME = "oc-shell";
    public static final String PROOT_NAME = "oc-proot";
    public static final String NATIVE_ENV_NAME = "oc-native-env.sh";
    /** Ejecutor de comandos del prefijo (scripts) de forma nativa. */
    public static final String RUN_NAME = "oc-run";
    /** Instalador de paquetes verificado: habla con ToolboxServer. */
    public static final String PKG_NAME = "oc-pkg";
    public static final String ADB_NAME = "oc-adb";
    public static final String ADB_SERVER_NAME = "oc-adb-server";

    private static final String LINKER = "/system/bin/linker64";
    private static final String[] SYSTEM_DIRS = {
            "/system/bin", "/system/xbin", "/vendor/bin"
    };

    /** Comandos que necesitan el prefijo completo de Termux (proot).
     *
     *  Son los que leen o escriben el prefijo por ruta absoluta o necesitan
     *  el gestor de paquetes (que asumes /data/data/com.termux/files/usr).
     *  Todo lo demas se ejecuta de forma nativa. */
    private static final String[] PROOT_COMMANDS = {
            "pkg", "apt", "apt-get", "apt-cache", "apt-key", "dpkg", "dpkg-deb",
            "dpkg-query", "dpkg-trigger", "termux-change-repo", "termux-setup-storage",
            "termux-reload-settings", "termux-services", "proot", "proot-distro",
            "sshd", "service",
            // El cliente adb va dentro de proot para conservar el prefijo;
            // oc-adb arranca antes su servidor fuera de proot.
            "adb"
    };

    /**
     * Scripts del prefijo que siguen yendo a proot.
     *
     *  El gestor de paquetes (pkg/apt/dpkg) escribe en el prefijo por su
     *  ruta absoluta de Termux y ejecuta los scripts de mantenimiento de
     *  los paquetes, asi que necesita el sandbox montado. Ademas estan
     *  escritos en bash, y el shell de Android (mksh) no los entiende.
     */
    private static final Set<String> SCRIPT_NAMES = new HashSet<>(Arrays.asList(
            "pkg", "apt", "apt-get", "apt-cache", "apt-key", "apt-mark", "apt-config",
            "dpkg", "dpkg-deb", "dpkg-query", "dpkg-trigger", "dpkg-buildapi",
            "dpkg-buildtree", "dpkg-realpath", "dpkg-fsys-usrunmess", "dpkg-split",
            "dpkg-trigger", "dpkg-genscripts", "add-apt-repository", "apt-mark-showhold"
    ));

    /**
     * Binarios del prefijo que deben ganar a la version de /system/bin.
     * Medido: GNU find tarda 94 ms en 10.059 entradas frente a 158 ms del
     * toybox del sistema; GNU grep ademas soporta mas expresiones.
     */
    private static final Set<String> PREFER_PREFIX = new HashSet<>(Arrays.asList(
            // "am" del prefijo es un envoltorio: el del sistema nunca puede
            // abrir apps (se presenta como com.android.shell y el framework
            // rechaza el UID), asi que el nuestro tiene que ganar.
            // "pm" igual: el nuestro explica que "pm install" no puede
            // funcionar y a que comando hay que ir.
            // "monkey" tambien: el del sistema necesita INJECT_EVENTS y no
            // hace nada; el nuestro abre la app.
            "find", "grep", "am", "pm", "monkey"
    ));

    /** Nombres que no se pueden definir como funcion (builtins o palabras raras). */
    private static final Set<String> SHELL_BUILTINS = new HashSet<>(Arrays.asList(
            "alias", "bg", "break", "cd", "command", "continue", "echo", "eval",
            "exec", "exit", "export", "false", "fc", "fg", "getopts", "hash",
            "jobs", "kill", "let", "local", "printf", "pwd", "read", "readonly",
            "return", "set", "shift", "source", "test", "times", "trap", "true",
            "type", "ulimit", "umask", "unalias", "unset", "wait"
    ));

    private static final Pattern VALID_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** Nombres que se pueden enrutar a proot, incluidos los guiones de termux-*. */
    private static final Pattern SAFE_COMMAND =
            Pattern.compile("[A-Za-z0-9_+.-]+");

    private static final String TERMUX_PREFIX = LocalToolbox.SANDBOX_PREFIX;

    private CommandResolver() {
    }

    /** true si el comando debe correr dentro de proot. */
    public static boolean needsProot(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) return false;
        String normalized = commandLine.replace('\n', ' ')
                .replace(';', ' ').replace('|', ' ').replace('&', ' ');
        for (String raw : normalized.split("\\s+")) {
            String word = raw.replace("\"", "").replace("'", "");
            if (word.isEmpty()) continue;
            String base = word.substring(word.lastIndexOf('/') + 1);
            for (String cmd : PROOT_COMMANDS) {
                if (base.equals(cmd)) return true;
            }
            if (word.contains(TERMUX_PREFIX)) return true;
        }
        return false;
    }

    /**
     * Escribe los envoltorios y devuelve la ruta de oc-shell.
     */
    public static File install(File home, File nd, File tmp, File etc, File binDir)
            throws IOException {
        if (!binDir.isDirectory() && !binDir.mkdirs() && !binDir.isDirectory()) {
            throw new IOException("no se pudo crear " + binDir);
        }
        File prefixDir = binDir.getParentFile();
        // Arreglo del prefijo para ejecucion nativa (sin proot): reescribe
        // shebangs, rutas de Termux y crea envoltorios java/javac. Se hace
        // antes del scan() para que los scripts arreglados se registren como
        // funciones nativas. Idempotente: no hace nada si el prefijo no ha
        // cambiado.
        try {
            LocalToolbox.fixupPrefix(prefixDir);
        } catch (Exception e) {
            Log.w(TAG, "fixupPrefix fallo: " + e);
        }
        File prefixLib = prefixDir == null ? new File(TERMUX_PREFIX, "lib")
                : new File(prefixDir, "lib");
        LinkedHashSet<String> libs = new LinkedHashSet<>();
        libs.add(prefixLib.getAbsolutePath());
        libs.add(new File(home, "lib").getAbsolutePath());
        libs.add(nd.getAbsolutePath());
        String libPath = String.join(":", libs);
        Scan scan = scan(binDir, libPath);
        write(new File(binDir, NATIVE_ENV_NAME), nativeEnv(scan.functions, libPath));
        write(new File(binDir, RUN_NAME), runScript(libPath,
                prefixDir == null ? new File(TERMUX_PREFIX, "bin").getPath()
                        : prefixDir.getPath(),
                LINKER, pipShortcut(prefixDir)));
        File proot = new File(binDir, PROOT_NAME);
        write(proot, prootScript(home, nd, tmp, etc));
        write(new File(binDir, ADB_SERVER_NAME), adbServerScript(binDir, home));
        write(new File(binDir, ADB_NAME), adbScript(binDir, home));
        File shell = new File(binDir, SHELL_NAME);
        write(shell, shellScript(scan.prootNames));
        write(new File(binDir, PKG_NAME), pkgScript(binDir, home, nd, prefixDir));
        Log.i(TAG, "resolvedor listo: " + scan.functions.size() + " nativos, "
                + scan.prootNames.size() + " a proot; " + shell);
        return shell;
    }

    /**
     * Regenera el resolvedor con el prefijo actual.
     *
     *  Se llama cuando el prefijo cambia (pkg/apt, instalacion del toolbox)
     *  para que los binarios recien instalados aparezcan sin reiniciar el
     *  servidor. Es idempotente y barato si no hay nada nuevo.
     */
    public static File refresh(Context ctx) {
        if (ctx == null) return null;
        File home = new File(ctx.getFilesDir(), "home");
        File nd = new File(ctx.getApplicationInfo().nativeLibraryDir);
        File tmp = new File(ctx.getCacheDir(), "tmp");
        File etc = new File(home, ".oc-etc");
        File binDir = LocalToolbox.installed(ctx)
                ? new File(LocalToolbox.prefix(ctx), "bin")
                : new File(home, "bin");
        try {
            return install(home, nd, tmp, etc, binDir);
        } catch (IOException e) {
            Log.w(TAG, "no se pudo regenerar el resolvedor: " + e);
            return null;
        }
    }

    private static void write(File file, String body) throws IOException {
        // Escritura atomica: el shell del agente puede estar leyendo oc-shell
        // o el env mientras se regeneran. Un rename entrega siempre una
        // version completa, nunca un script a medias.
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (FileWriter w = new FileWriter(tmp, false)) {
            w.write(body);
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.setExecutable(true, true);
        //noinspection ResultOfMethodCallIgnored
        tmp.setReadable(true, false);
        if (!tmp.renameTo(file)) {
            // Sistema de archivos sin rename atomico: se escribe directo.
            try (FileWriter w = new FileWriter(file, false)) {
                w.write(body);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        file.setExecutable(true, true);
        //noinspection ResultOfMethodCallIgnored
        file.setReadable(true, false);
    }

    /**
     * Un ELF del prefijo no se puede execve desde untrusted_app, pero el
     * linker del sistema si puede leerlo y mapearlo como datos. Se expone una
     * funcion por comando; los scripts (npm, pip...) y lo que necesita el
     * prefijo completo siguen yendo a proot.
     */
    private static final class Scan {
        final List<String> functions = new ArrayList<>();
        final Set<String> prootNames = new LinkedHashSet<>();
    }

    private static Scan scan(File binDir, String libPath) {
        Scan scan = new Scan();
        Set<String> proot = new LinkedHashSet<>(Arrays.asList(PROOT_COMMANDS));
        boolean linker = new File(LINKER).isFile();
        File[] files = binDir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            Set<String> seen = new HashSet<>();
            for (File file : files) {
                String name = file.getName();
                if (name.isEmpty() || name.startsWith(".") || name.startsWith("oc-")) {
                    continue;
                }
                if (!SAFE_COMMAND.matcher(name).matches()) continue;
                if (!file.isFile() || !file.canExecute()) continue;
                if (proot.contains(name)) continue;
                if (systemHas(name) && !PREFER_PREFIX.contains(name)) continue;
                if (!isElf(file)) {
                    // Script de verdad (npm, pip, pkg, termux-*...): se
                    // ejecuta de forma nativa con oc-run, que pone
                    // LD_LIBRARY_PATH y usa el shell del sistema. Antes
                    // iban a proot, y arrancar el sandbox costaba mas que
                    // el comando.
                    if (needsBash(file)) {
                        // Escrito en bash: el shell de Android (mksh) no lo
                        // entiende. A proot, donde si hay bash.
                        proot.add(name);
                    } else if (SCRIPT_NAMES.contains(name)) {
                        // pkg/apt/dpkg: necesitan el prefijo montado en su
                        // ruta de Termux y ejecutan mantenimiento de paquetes.
                        proot.add(name);
                    } else if (VALID_NAME.matcher(name).matches()) {
                        if (!seen.add(name)) continue;
                        scan.functions.add(scriptFunction(name, file.getAbsolutePath()));
                    } else {
                        // Guion o punto: no es un nombre POSIX valido. Si se
                        // emitiera una funcion, dash rompe TODO el env con
                        // "Bad function name" (dpkg-buildapi, dpkg-realpath...).
                        proot.add(name);
                    }
                    continue;
                }
                if (SHELL_BUILTINS.contains(name)) continue;
                if (!VALID_NAME.matcher(name).matches()) {
                    // Un ELF con guion no puede ser funcion POSIX: a proot.
                    proot.add(name);
                    continue;
                }
                if (!linker) {
                    proot.add(name);
                    continue;
                }
                File target;
                try {
                    target = file.getCanonicalFile();
                } catch (IOException e) {
                    proot.add(name);
                    continue;
                }
                if (!target.isFile() || !isElf(target)) {
                    proot.add(name);
                    continue;
                }
                if (!seen.add(name)) continue;
                scan.functions.add(name + "() { LD_LIBRARY_PATH=\"$OC_NATIVE_LIB_PATH\" "
                        + LINKER + " " + q(target.getAbsolutePath()) + " \"$@\"; }");
            }
        }
        scan.prootNames.addAll(proot);
        return scan;
    }

    /**
     * true si el script esta escrito en bash.
     *
     *  El shell de Android es mksh, que no entiende la sintaxis de bash
     *  (arrays, [[ ]], here-docs conResize, etc). Esos scripts tienen que
     *  ir a proot, donde si hay bash.
     */
    private static boolean needsBash(File file) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[64];
            int n = in.read(head);
            if (n <= 0) return false;
            String line = new String(head, 0, n, java.nio.charset.StandardCharsets.UTF_8);
            int nl = line.indexOf('\n');
            if (nl > 0) line = line.substring(0, nl);
            return line.contains("/bash") || line.contains("/ba ")
                    || line.contains("env bash") || line.contains("env -S bash")
                    || line.contains("env -s bash");
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean systemHas(String name) {
        for (String dir : SYSTEM_DIRS) {
            if (new File(dir, name).isFile()) return true;
        }
        return false;
    }

    private static boolean isElf(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[4];
            int off = 0;
            while (off < head.length) {
                int n = in.read(head, off, head.length - off);
                if (n < 0) return false;
                off += n;
            }
            return (head[0] & 0xff) == 0x7f && head[1] == 'E'
                    && head[2] == 'L' && head[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Ejecutor de comandos del prefijo (scripts de shell, npm, pip...).
     *
     *  Los scripts del prefijo son de shell y necesitan las librerias del
     *  prefijo para sus interpretes (bash, python...). Android no deja
     *  ejecutar nada del directorio de datos de la app, pero si dejar que el
     *  linker del sistema lo lea. Este envoltorio pone LD_LIBRARY_PATH solo
     * para el comando y ejecuta el script con el shell del sistema.
     *
     *  Con esto los scripts ya no necesitan el sandbox de proot.
     */
    /**
     * Ejecuta un comando del prefijo (script) de forma nativa, sin proot.
     *
     *  Los scripts del prefijo son de shell y algunos (pip, pydoc...) tienen
     *  un interprete ELF (python) que necesita las librerias del prefijo.
     *  Android no deja ejecutar nada del directorio de datos de la app, pero
     *  si deja que el linker del sistema lo lea: por eso se lanza con
     *  linker64 y LD_LIBRARY_PATH acotado.
     *
     *  El script solo usa comandos internos del shell (read, case): ninguna
     *  utilidad externa, porque las del prefijo no se pueden ejecutar y las
     *  del sistema no estan en el PATH de forma fiable.
     */
    private static String runScript(String libPath, String realPrefix, String linker,
            String pipShortcut) {
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver. No editar a mano.\n"
                + "# Ejecuta un comando del prefijo de forma nativa, sin proot.\n"
                + "OC_NATIVE_LIB_PATH=" + q(libPath) + "\n"
                + "export LD_LIBRARY_PATH=\"$OC_NATIVE_LIB_PATH\"\n"
                + "CMD=\"$1\"\n"
                + "shift 2>/dev/null\n"
                + "# Primera linea del script, con el comando interno read (sin\n"
                + "# utilidades externas: las del prefijo no se pueden ejecutar).\n"
                + "INTERP=\"\"\n"
                + "if [ -f \"$CMD\" ]; then IFS= read -r INTERP < \"$CMD\" 2>/dev/null; fi\n"
                + "INTERP=${INTERP#\\#!}\n"
                + "while [ \"${INTERP# }\" != \"$INTERP\" ]; do INTERP=${INTERP# }; done\n"
                + "# /usr/bin/env python3 -> python3; /usr/bin/env -S python3 -u -> python3\n"
                + "case \"$INTERP\" in\n"
                + "  \"*/env -S \"*|\"*/env -s \"*)\n"
                + "    INTERP=${INTERP#*/env }; INTERP=${INTERP#-S }; INTERP=${INTERP#-s }\n"
                + "    INTERP=${INTERP%% *} ;;\n"
                + "  \"*/env \"*)\n"
                + "    INTERP=${INTERP#*/env }; INTERP=${INTERP%% *} ;;\n"
                + "  *) INTERP=${INTERP%% *} ;;\n"
                + "esac\n"
                + "# Si el shebang aun apunta al prefijo de Termux, se traduce al real.\n"
                + "case \"$INTERP\" in\n"
                + "  /data/data/com.termux/files/usr/*)\n"
                + "    INTERP=" + q(realPrefix) + "\"${INTERP#/data/data/com.termux/files/usr}\" ;;\n"
                + "  /data/user/0/com.termux/files/usr/*)\n"
                + "    INTERP=" + q(realPrefix) + "\"${INTERP#/data/user/0/com.termux/files/usr}\" ;;\n"
                + "esac\n"
                + "# Un interprete sin barra (python3, bash) se busca en el prefijo.\n"
                + "case \"$INTERP\" in\n"
                + "  */*) ;;\n"
                + "  python*|python)\n"
                + "    if [ -f " + q(realPrefix + "/bin/") + "\"$INTERP\" ]; then\n"
                + "      INTERP=" + q(realPrefix + "/bin/") + "\"$INTERP\"\n"
                + "    fi ;;\n"
                + "esac\n"
                + "case \"$INTERP\" in\n"
                + "  */bin/python*)\n"
                + "    MOD=${CMD##*/}\n"
                + (pipShortcut == null ? "" :
                    "    if [ \"$MOD\" = pip ] && [ -f " + q(pipShortcut) + " ]; then\n"
                        + "      # Atajo de pip para las consultas de solo lectura: el\n"
                        + "      # atajo devuelve 2 si la orden no es de esas y entonces\n"
                        + "      # se ejecuta el pip de verdad.\n"
                        + "      " + linker + " \"$INTERP\" " + q(pipShortcut) + " \"$@\"\n"
                        + "      RC=$?\n"
                        + "      if [ $RC -ne 2 ]; then exit $RC; fi\n"
                        + "    fi\n")
                + "    # Se ejecuta el script tal cual: -m solo vale para entry\n"
                + "    # points de consola, no para un script python cualquiera.\n"
                + "    exec " + linker + " \"$INTERP\" \"$CMD\" \"$@\" ;;\n"
                + "esac\n"
                + "# Un script no necesita las libs del prefijo, pero si las hereda\n"
                + "# los binarios del sistema que lance fallan al enlazar\n"
                + "# (\"cannot locate symbol Xzs_Construct\").\n"
                + "unset LD_LIBRARY_PATH\n"
                + "exec /system/bin/sh \"$CMD\" \"$@\"\n";
    }

    /**
     * Funcion de shell para un script del prefijo: se ejecuta con oc-run,
     * que pone LD_LIBRARY_PATH y usa el shell del sistema.
     *
     *  Antes cada script iba a proot (arrancar el sandbox costaba mas que
     *  el comando). Como los scripts son de shell, se pueden ejecutar de
     *  forma nativa: mucho mas rapido y sin la ruta falsa de Termux.
     */
    /**
     * Ruta del atajo de pip para las consultas de solo lectura, o null si no
     * esta instalado.
     */
    private static String pipShortcut(File prefixDir) {
        if (prefixDir == null) return null;
        File lib = new File(prefixDir, "lib");
        File[] kids = lib.listFiles();
        if (kids == null) return null;
        for (File k : kids) {
            if (!k.isDirectory() || !k.getName().startsWith("python3")) continue;
            File f = new File(new File(k, "site-packages"), "ocpip.py");
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    /**
     * Funcion de shell para un script del prefijo: se ejecuta con oc-run,
     * que pone LD_LIBRARY_PATH y usa el shell del sistema.
     *
     *  Antes cada script iba a proot (arrancar el sandbox costaba mas que
     *  el comando). Como los scripts son de shell, se pueden ejecutar de
     *  forma nativa: mucho mas rapido y sin la ruta falsa de Termux.
     */
    private static String scriptFunction(String name, String path) {
        // oc-run se invoca con /system/bin/sh, no directamente: Android
        // bloquea execve en el directorio de datos de la app (untrusted_app),
        // asi que un "oc-run ..." directo falla con Permission denied.
        // Leer y ejecutar con el shell del sistema si que se permite.
        File parent = new File(path).getParentFile();
        String runner = parent == null ? RUN_NAME
                : new File(parent, RUN_NAME).getAbsolutePath();
        return name + "() { /system/bin/sh " + q(runner) + " " + q(path) + " \"$@\"; }";
    }

    private static String nativeEnv(List<String> functions, String libPath) {
        StringBuilder out = new StringBuilder();
        out.append("#!/system/bin/sh\n")
                .append("# Generado por CommandResolver. No editar a mano.\n")
                .append("# Lanza los ELF del prefijo con el linker del sistema:\n")
                .append("# Android bloquea execve en el directorio de datos de la app.\n")
                .append("# LD_LIBRARY_PATH solo se aplica al linker y al ELF del prefijo;\n")
                .append("# los binarios del sistema no deben heredar las libs de Termux.\n")
                .append("OC_NATIVE_LIB_PATH=").append(q(libPath)).append('\n');
        for (String function : functions) {
            out.append(function).append('\n');
        }
        return out.toString();
    }

    private static String shellScript(Set<String> prootNames) {
        StringBuilder cases = new StringBuilder();
        for (String cmd : prootNames) {
            // adb tiene su propio envoltorio: el servidor debe vivir fuera
            // de proot para sobrevivir entre comandos.
            if ("adb".equals(cmd)) continue;
            if (cases.length() > 0) cases.append('|');
            cases.append(cmd);
        }
        StringBuilder prootCase = new StringBuilder();
        if (cases.length() > 0) {
            prootCase.append("    ").append(cases).append(")\n")
                    .append("      exec /system/bin/sh \"$OWN/").append(PROOT_NAME)
                    .append("\" \"$CMD\" ;;\n");
        }
        // El envoltorio se ejecuta con /system/bin/sh. No usa utilidades del
        // prefijo (dirname, printf, tr...) porque Android no las deja
        // ejecutar: solo builtins y ordenes del sistema.
        //
        // Se revisan todas las palabras, no solo la primera: asi un
        // "id; git status" tambien detecta git. Un falso positivo (p. ej.
        // "echo git") solo cuesta arrancar proot; un falso negativo rompe el
        // comando con Permission denied.
        //
        // El prefijo real (files/usr) ya no necesita proot: sus binarios son
        // ELF de Android y se lanzan con el linker del sistema (ver
        // oc-native-env.sh). Solo van a proot los comandos que el gestor de
        // paquetes y el cliente adb necesitan.
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver. No editar a mano.\n"
                + "# El shell del agente no hereda las librerias de Termux: los\n"
                + "# binarios del sistema (dumpsys, pm, am...) cargan las suyas.\n"
                + "# Las funciones de oc-native-env.sh ponen LD_LIBRARY_PATH solo\n"
                + "# cuando lanzan un ELF del prefijo con linker64.\n"
                + "unset LD_LIBRARY_PATH LD_PRELOAD\n"
                + "# La contrasena del servidor local no hace falta aqui: el agente\n"
                + "# habla con el por el token del toolbox, no con su contrasena.\n"
                + "unset OPENCODE_PASSWORD\n"
                + "CMD=\"\"\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -c|-lc) CMD=\"$2\"; shift 2 ;;\n"
                + "    -l|--login) shift ;;\n"
                + "    *) if [ -z \"$CMD\" ]; then CMD=\"$1\"; fi; shift ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ -z \"$CMD\" ]; then\n"
                + "  case \"$0\" in */*) OWN=${0%/*} ;; *) OWN=. ;; esac\n"
                + "  exec /system/bin/sh \"$OWN/" + PROOT_NAME + "\" --interactive\n"
                + "fi\n"
                + "case \"$0\" in */*) OWN=${0%/*} ;; *) OWN=. ;; esac\n"
                + "APP_FILES=${OWN%/usr/bin}\n"
                + "# Rutas absolutas del prefijo al principio del comando: se quitan\n"
                + "# para que el comando pase por la funcion/trampolin de PATH. Ej:\n"
                + "#   $PREFIX/bin/aapt2 version -> aapt2 version\n"
                + "case \"$CMD\" in\n"
                + "  '$PREFIX'/bin/*) CMD=${CMD#'$PREFIX'/bin/} ;;\n"
                + "  '${PREFIX}'/bin/*) CMD=${CMD#'${PREFIX}'/bin/} ;;\n"
                + "  /data/data/*/files/usr/bin/*|/data/user/0/*/files/usr/bin/*)\n"
                + "    CMD=${CMD#*/files/usr/bin/} ;;\n"
                + "esac\n"
                + "# pkg/apt install: instalador verificado de la app (indice,\n"
                + "# dependencias, SHA-256 y extraccion en Java). No se usa apt/dpkg\n"
                + "# a pelo: su prefijo compilado es el de Termux.\n"
                + "case \"$CMD\" in\n"
                + "  \"pkg install \"*|\"pkg add \"*|\"apt install \"*|\"apt-get install \"*)\n"
                + "    if [ -f \"$OWN/" + PKG_NAME + "\" ]; then\n"
                + "      exec /system/bin/sh \"$OWN/" + PKG_NAME + "\" \"$CMD\"\n"
                + "    fi ;;\n"
                + "esac\n"
                + "FIRSTWORD=\"\"\n"
                + "HAS_GIT=0\n"
                + "GIT_HELPER=0\n"
                + "for RAW in $CMD; do\n"
                + "  W=${RAW#\\\"}; W=${W%\\\"}; W=${W#\\'}; W=${W%\\'}\n"
                + "  W=${W#\\(}; W=${W%\\)}; W=${W%;}; W=${W%|}; W=${W%&}\n"
                 + "  if [ -z \"$FIRSTWORD\" ]; then FIRSTWORD=\"$W\"; fi\n"
                + "  case \"$W\" in\n"
                + "    adb|" + TERMUX_PREFIX + "/bin/adb|\"$APP_FILES/usr/bin/adb\")\n"
                + "      exec /system/bin/sh \"$OWN/" + ADB_NAME + "\" \"$CMD\" ;;\n"
                + prootCase
                + "  esac\n"
                + "  case \"$W\" in git) HAS_GIT=1 ;; esac\n"
                + "  case \"$W\" in\n"
                + "    clone|fetch|pull|push|ls-remote|submodule|commit|merge|rebase)\n"
                + "      GIT_HELPER=1 ;;\n"
                + "    cherry-pick|revert|am|send-email|difftool|mergetool|credential)\n"
                + "      GIT_HELPER=1 ;;\n"
                + "    maintenance|gc|repack|prune)\n"
                + "      GIT_HELPER=1 ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [ $HAS_GIT -eq 1 ] && [ $GIT_HELPER -eq 1 ]; then\n"
                + "  exec /system/bin/sh \"$OWN/" + PROOT_NAME + "\" \"$CMD\"\n"
                + "fi\n"
                + "if [ -f \"$OWN/" + NATIVE_ENV_NAME + "\" ]; then\n"
                + "  . \"$OWN/" + NATIVE_ENV_NAME + "\"\n"
                + "fi\n"
                + "if [ -d \"$APP_FILES/usr/lib/go\" ]; then\n"
                + "  export GOROOT=\"$APP_FILES/usr/lib/go\"\n"
                + "fi\n"
                + "OC_ENGINE=native\n"
                + "export OC_ENGINE\n"
                + ": \"${OC_NATIVE_LIB_PATH:=$PREFIX/lib:$HOME/lib}\"\n"
                + "ERRF=\"$OWN/.oc-err-$$\"\n"
                + "# Camino rapido: un solo comando simple que es un ELF del prefijo.\n"
                + "# Se resuelve antes de eval para no depender del codigo de salida\n"
                + "# (un pipeline o un 2>&1 ocultan el fallo de exec).\n"
                + "SIMPLE=1\n"
                + "case \"$CMD\" in\n"
                + "  *\"|\"*|*\"<\"*|*\"&\"*|*'$('*|*'`'*) SIMPLE=0 ;;\n"
                + "  *\">\"*|*';'*) SIMPLE=0 ;;\n"
                + "esac\n"
                + "if [ $SIMPLE -eq 1 ]; then\n"
                + "  set -f\n"
                + "  set -- $CMD\n"
                + "  set +f\n"
                + "  W=${1:-}\n"
                + "  W=${W#\\\"}; W=${W%\\\"}; W=${W#\\'}; W=${W%\\'}\n"
                + "  case \"$W\" in ''|*=*) W=\"\" ;; esac\n"
                + "  if [ -n \"$W\" ]; then\n"
                + "    BASE=${W##*/}\n"
                + "    for CAND in \"$PREFIX/bin/$BASE\" \"$APP_FILES/usr/bin/$BASE\"; do\n"
                + "      if [ -f \"$CAND\" ]; then\n"
                + "        case \"$CMD\" in \"$W\"*) ARGS=${CMD#\"$W\"} ;; *) W=\"\" ;; esac\n"
                + "        if [ -n \"$W\" ]; then\n"
                + "          MAGIC=$(/system/bin/dd if=\"$CAND\" bs=1 count=4 2>/dev/null | /system/bin/od -An -tx1 | /system/bin/tr -d \" \\n\")\n"
                + "          if [ \"$MAGIC\" = \"7f454c46\" ]; then\n"
                + "            set -f; eval \"set -- $ARGS\"; set +f\n"
                + "            LD_LIBRARY_PATH=\"$OC_NATIVE_LIB_PATH\" /system/bin/linker64 \"$CAND\" \"$@\"\n"
                + "            exit $?\n"
                + "          fi\n"
                + "        fi\n"
                + "        break\n"
                + "      fi\n"
                + "    done\n"
                + "  fi\n"
                + "fi\n"
                + "eval \"$CMD\" 2>\"$ERRF\"\n"
                + "RC=$?\n"
                + "BAD=0\n"
                + "if [ $RC -eq 126 ] || [ $RC -eq 127 ]; then BAD=1; fi\n"
                + "if [ $BAD -eq 0 ] && [ $RC -ne 0 ] && [ -s \"$ERRF\" ]; then\n"
                + "  if /system/bin/grep -q -E 'CANNOT LINK EXECUTABLE|library .* not found|Permission denied|inaccessible or not found|not executable' \"$ERRF\" 2>/dev/null; then\n"
                + "    BAD=1\n"
                + "  fi\n"
                + "fi\n"
                + "if [ $BAD -eq 0 ]; then\n"
                + "  if [ -s \"$ERRF\" ]; then /system/bin/cat \"$ERRF\" >&2; fi\n"
                + "  /system/bin/rm -f \"$ERRF\"\n"
                + "  exit $RC\n"
                + "fi\n"
                + "# Fallback: el comando existe pero no se pudo ejecutar (ELF del\n"
                + "# prefijo sin linker, o script nuevo). Se prueba linker64/oc-run\n"
                + "# antes de proot.\n"
                + "BASE=${FIRSTWORD##*/}\n"
                + "BASE=${BASE#\\\"}; BASE=${BASE%\\\"}; BASE=${BASE#\\'}; BASE=${BASE%\\'}\n"
                + "MATCH=0\n"
                + "case \"$CMD\" in \"$FIRSTWORD\"*) ARGS=${CMD#\"$FIRSTWORD\"}; MATCH=1 ;; esac\n"
                + "# No reinterpretar redirecciones, tuberias ni sustituciones: si\n"
                + "# las hay, el comando no es un argv simple y va a proot.\n"
                + "case \"$ARGS\" in *'|'*|*'&'*|*';'*|*'<'*|*'>'*|*'$('*|*'`'*) MATCH=0 ;; esac\n"
                + "if [ $MATCH -eq 1 ]; then\n"
                + "  for CAND in \"$PREFIX/bin/$BASE\" \"$APP_FILES/usr/bin/$BASE\"; do\n"
                + "    if [ -f \"$CAND\" ]; then\n"
                + "      MAGIC=$(/system/bin/dd if=\"$CAND\" bs=1 count=4 2>/dev/null | /system/bin/od -An -tx1 | /system/bin/tr -d \" \\n\")\n"
                + "      if [ \"$MAGIC\" = \"7f454c46\" ]; then\n"
                + "        set -f; eval \"set -- $ARGS\"; set +f\n"
                + "        LD_LIBRARY_PATH=\"$OC_NATIVE_LIB_PATH\" /system/bin/linker64 \"$CAND\" \"$@\"\n"
                + "        RC2=$?\n"
                + "        if [ $RC2 -ne 126 ] && [ $RC2 -ne 127 ]; then /system/bin/rm -f \"$ERRF\"; exit $RC2; fi\n"
                + "      fi\n"
                + "      if [ -x \"$OWN/" + RUN_NAME + "\" ]; then\n"
                + "        set -f; eval \"set -- $ARGS\"; set +f\n"
                + "        /system/bin/sh \"$OWN/" + RUN_NAME + "\" \"$CAND\" \"$@\"\n"
                + "        RC2=$?\n"
                + "        if [ $RC2 -ne 126 ] && [ $RC2 -ne 127 ]; then /system/bin/rm -f \"$ERRF\"; exit $RC2; fi\n"
                + "      fi\n"
                + "      break\n"
                + "    fi\n"
                + "  done\n"
                + "fi\n"
                + "if [ -s \"$ERRF\" ]; then /system/bin/cat \"$ERRF\" >&2; fi\n"
                + "/system/bin/rm -f \"$ERRF\"\n"
                + "/system/bin/sh \"$OWN/" + PROOT_NAME + "\" \"$CMD\"\n"
                + "exit $?\n";
    }

    /**
     * Mantiene el servidor adb dentro de un proot permanente. Al no salir
     * nunca, proot no aplica --kill-on-exit y el daemon sobrevive entre
     * invocaciones. El cliente arranca este supervisor solo si hace falta.
     */
    private static String adbServerScript(File binDir, File home) {
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver. No editar a mano.\n"
                + "echo $$ > " + q(new File(home, ".adb-server.pid").getAbsolutePath()) + "\n"
                + "exec /system/bin/sh " + q(new File(binDir, PROOT_NAME).getAbsolutePath())
                + " \"adb nodaemon server\"\n";
    }

    /**
     * Envoltorio de adb: asegura el supervisor externo y ejecuta el cliente
     * dentro de proot. Asi la conexion se mantiene entre invocaciones.
     */
    private static String adbScript(File binDir, File home) {
        String pidFile = new File(home, ".adb-server.pid").getAbsolutePath();
        String logFile = new File(home, ".adb-server.log").getAbsolutePath();
        String server = new File(binDir, ADB_SERVER_NAME).getAbsolutePath();
        String proot = new File(binDir, PROOT_NAME).getAbsolutePath();
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver. No editar a mano.\n"
                + "# El cliente adb corre dentro de proot, pero el servidor vive en\n"
                + "# un proot permanente aparte: asi el daemon sobrevive entre\n"
                + "# invocaciones y la conexion no se pierde.\n"
                + "CMD=\"${1:-adb}\"\n"
                + "ADB=" + q(new File(binDir, "adb").getAbsolutePath()) + "\n"
                + "PIDFILE=" + q(pidFile) + "\n"
                + "if [ -x \"$ADB\" ]; then\n"
                + "  PID=\"\"\n"
                + "  if [ -f \"$PIDFILE\" ]; then PID=$(/system/bin/cat \"$PIDFILE\" 2>/dev/null); fi\n"
                + "  case \"$PID\" in ''|*[!0-9]*) PID=\"\" ;; esac\n"
                + "  if [ -z \"$PID\" ] || ! /system/bin/kill -0 \"$PID\" 2>/dev/null; then\n"
                + "    /system/bin/rm -f \"$PIDFILE\"\n"
                + "    if [ -x " + q("/system/bin/setsid") + " ]; then\n"
                + "      ( /system/bin/setsid /system/bin/sh " + q(server)
                + " </dev/null >" + q(logFile) + " 2>&1 & )\n"
                + "    else\n"
                + "      ( /system/bin/sh " + q(server)
                + " </dev/null >" + q(logFile) + " 2>&1 & )\n"
                + "    fi\n"
                + "    i=0\n"
                + "    while [ $i -lt 20 ]; do\n"
                + "      /system/bin/ss -ltn 2>/dev/null | /system/bin/grep -q \"127.0.0.1:5037\" && break\n"
                + "      sleep 0.1\n"
                + "      i=$((i+1))\n"
                + "    done\n"
                + "  fi\n"
                + "fi\n"
                + "exec /system/bin/sh " + q(proot) + " \"$CMD\"\n";
    }

    private static String prootScript(File home, File nd, File tmp, File etc) {
        File prefix = new File(home.getParentFile(), "usr");
        File prootTmp = new File(tmp, "proot");
        //noinspection ResultOfMethodCallIgnored
        prootTmp.mkdirs();
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver: comando dentro del sandbox proot.\n"
                + "# Sin argumentos (o con --interactive) abre un shell interactivo\n"
                + "# dentro del sandbox, para que pkg/apt y los scripts de Termux\n"
                + "# encuentren su prefijo real. Los comandos del agente siguen\n"
                + "# entrando por la rama -c.\n"
                + "MODE=command\n"
                + "if [ \"$1\" = \"--interactive\" ]; then MODE=interactive; shift; fi\n"
                + "CMD=\"${1:-}\"\n"
                + "unset BASH_ENV ENV LD_PRELOAD\n"
                + "# Nunca heredar configuraciones de apt/dpkg inventadas fuera del\n"
                + "# sandbox: el prefijo compilado de estos binarios es el de Termux\n"
                + "# y dentro de proot ya esta montado en esa ruta.\n"
                + "unset APT_CONFIG DPKG_ADMIN_DIR DPKG_ROOT DPKG_INSTDIR\n"
                + "if [ ! -d " + q(prefix.getAbsolutePath()) + " ]; then\n"
                + "  echo \"proot: la caja de herramientas aun se esta instalando\" >&2\n"
                + "  exit 127\n"
                + "fi\n"
                + "export HOME=" + q(home.getAbsolutePath()) + "\n"
                + "export TMPDIR=" + q(tmp.getAbsolutePath()) + "\n"
                + "export XDG_CONFIG_HOME=" + q(new File(home, ".config").getAbsolutePath()) + "\n"
                + "export XDG_DATA_HOME=" + q(new File(home, ".local/share").getAbsolutePath()) + "\n"
                + "export XDG_CACHE_HOME=" + q(new File(home, ".cache").getAbsolutePath()) + "\n"
                + "export XDG_STATE_HOME=" + q(new File(home, ".local/state").getAbsolutePath()) + "\n"
                + "export XDG_RUNTIME_DIR=" + q(new File(home, ".run").getAbsolutePath()) + "\n"
                + "export PREFIX=" + q(TERMUX_PREFIX) + "\n"
                + "export TERMUX_HOME=" + q(TERMUX_PREFIX) + "\n"
                + "export TERMUX_VERSION=0.118.0\n"
                + "export TERMUX_PKG_NO_MIRROR_SELECT=1\n"
                + "export DEBIAN_FRONTEND=noninteractive\n"
                + "export APT_LISTCHANGES_FRONTEND=none\n"
                + "export PATH=" + q(TERMUX_PREFIX + "/bin:/system/bin:/system/xbin:/vendor/bin") + "\n"
                + "export LD_LIBRARY_PATH=" + q(TERMUX_PREFIX + "/lib:" + new File(home, "lib").getAbsolutePath()
                        + ":" + nd.getAbsolutePath()) + "\n"
                + "export SHELL=/system/bin/sh\n"
                + "export OC_ENGINE=proot\n"
                + "export LANG=C.UTF-8\n"
                + "export LC_ALL=C.UTF-8\n"
                + "export TERM=xterm-256color\n"
                + "export NO_COLOR=1\n"
                + "export PROOT_LOADER=" + q(new File(nd, "libproot_loader.so").getAbsolutePath()) + "\n"
                + "export PROOT_TMP_DIR=" + q(prootTmp.getAbsolutePath()) + "\n"
                + "if [ \"$MODE\" = command ] && [ -z \"$CMD\" ]; then MODE=interactive; fi\n"
                + "WORKDIR=" + q(home.getAbsolutePath()) + "\n"
                + "if [ \"$MODE\" = interactive ]; then\n"
                + "  if [ -n \"$PWD\" ] && [ -d \"$PWD\" ]; then WORKDIR=\"$PWD\"; fi\n"
                + "  if [ -x " + q(new File(prefix, "bin/bash").getAbsolutePath()) + " ]; then\n"
                + "    set -- " + q(TERMUX_PREFIX + "/bin/bash") + " -i\n"
                + "    export SHELL=" + q(TERMUX_PREFIX + "/bin/bash") + "\n"
                + "  else\n"
                + "    set -- /system/bin/sh -i\n"
                + "    export SHELL=/system/bin/sh\n"
                + "  fi\n"
                + "  unset NO_COLOR\n"
                + "else\n"
                + "  set -- /system/bin/sh -c \"$CMD\"\n"
                + "fi\n"
                + "exec " + q(new File(nd, "libproot.so").getAbsolutePath()) + " --kill-on-exit \\\n"
                + "  -b " + q(tmp.getAbsolutePath() + ":/tmp") + " \\\n"
                + "  -b " + q(new File(nd, "libld_musl.so").getAbsolutePath()
                        + ":/lib/ld-musl-aarch64.so.1") + " \\\n"
                + "  -b " + q(prefix.getAbsolutePath() + ":" + TERMUX_PREFIX) + " \\\n"
                + "  -b " + q(new File(etc, "hosts").getAbsolutePath() + ":/etc/hosts") + " \\\n"
                + "  -b " + q(new File(etc, "resolv.conf").getAbsolutePath() + ":/etc/resolv.conf") + " \\\n"
                + "  -w \"$WORKDIR\" \\\n"
                + "  \"$@\"\n";
    }

    /**
     * Instalador de paquetes verificado (oc-pkg).
     *
     *  Sustituye a "pkg install" / "apt install" dentro del shell nativo: la
     *  app resuelve dependencias contra su indice, verifica SHA-256, extrae y
     *  registra con dpkg dentro de proot. Asi el agente no pelea con el
     *  prefijo compilado de Termux ni inventa APT_CONFIG.
     *
     *  Espera a que termine y devuelve un codigo de salida real, para que el
     *  agente sepa si el paquete quedo instalado antes de usarlo.
     */
    private static String pkgScript(File binDir, File home, File nd, File prefixDir) {
        String realPrefix = prefixDir == null ? TERMUX_PREFIX
                : prefixDir.getAbsolutePath().replace("/data/user/0/", "/data/data/");
        String homePath = home.getAbsolutePath().replace("/data/user/0/", "/data/data/");
        String libPath = realPrefix + "/lib:" + homePath + "/lib:" + nd.getAbsolutePath();
        String ca = realPrefix + "/etc/tls/cert.pem";
        return "#!/system/bin/sh\n"
                + "# Generado por CommandResolver. No editar a mano.\n"
                + "# Instalador verificado de OpenCode: indice propio, dependencias,\n"
                + "# SHA-256 y extraccion en Java. No usa apt/dpkg a pelo.\n"
                + "CMD=\"$*\"\n"
                + "case \"$CMD\" in\n"
                + "  pkg\\ install\\ *|pkg\\ add\\ *|pkg\\ i\\ *|apt\\ install\\ *|apt-get\\ install\\ *)\n"
                + "    ;;\n"
                + "  pkg\\ update*|apt\\ update*|apt-get\\ update*)\n"
                + "    echo 'oc-pkg: the index is refreshed automatically on install.'\n"
                + "    exit 0 ;;\n"
                + "  '')\n"
                + "    echo 'usage: pkg install <package>...' >&2\n"
                + "    exit 2 ;;\n"
                + "  *)\n"
                + "    echo \"oc-pkg: only install is handled here: $CMD\" >&2\n"
                + "    exit 2 ;;\n"
                + "esac\n"
                + "SKIP=2\n"
                + "PKGS=\"\"\n"
                + "BAD=\"\"\n"
                + "set -f\n"
                + "for A in $CMD; do\n"
                + "  if [ $SKIP -gt 0 ]; then SKIP=$((SKIP-1)); continue; fi\n"
                + "  A=${A#\\\"}; A=${A%\\\"}; A=${A#\\'}; A=${A%\\'}\n"
                + "  case \"$A\" in --) continue ;; -*) continue ;; esac\n"
                + "  case \"$A\" in *[!A-Za-z0-9+._-]*) BAD=\"$A\"; break ;; esac\n"
                + "  case \",$PKGS,\" in *\",$A,\"*) continue ;; esac\n"
                + "  PKGS=\"$PKGS,$A\"\n"
                + "done\n"
                + "set +f\n"
                + "if [ -n \"$BAD\" ]; then\n"
                + "  echo \"oc-pkg: invalid package name: $BAD\" >&2\n"
                + "  exit 2\n"
                + "fi\n"
                + "PKGS=${PKGS#,}\n"
                + "if [ -z \"$PKGS\" ]; then\n"
                + "  echo 'usage: pkg install <package>...' >&2\n"
                + "  exit 2\n"
                + "fi\n"
                + "PORT=${OPENCODE_TOOLBOX_PORT:-" + ToolboxServer.PORT + "}\n"
                + "TOK=${OPENCODE_TOOLBOX_TOKEN:-}\n"
                + "if [ -z \"$TOK\" ]; then\n"
                + "  echo 'oc-pkg: the toolbox token is missing; restart the local server' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "CURL=" + q(realPrefix + "/bin/curl") + "\n"
                + "if [ ! -f \"$CURL\" ]; then\n"
                + "  echo 'oc-pkg: curl is not installed yet; finish the toolbox install' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "oc_curl() { LD_LIBRARY_PATH=" + q(libPath) + " CURL_CA_BUNDLE=" + q(ca)
                + " SSL_CERT_FILE=" + q(ca) + " " + LINKER + " \"$CURL\" --noproxy '*'"
                + " -H \"X-OpenCode-Token: $TOK\" \"$@\"; }\n"
                + "URL=\"http://127.0.0.1:$PORT\"\n"
                + "field() {\n"
                + "  printf '%s\\n' \"$1\" | while IFS= read -r L; do\n"
                + "    case \"$L\" in\n"
                + "      \"$2\"=*) printf '%s' \"${L#*=}\"; break ;;\n"
                + "    esac\n"
                + "  done\n"
                + "}\n"
                + "wait_idle() {\n"
                + "  N=0\n"
                + "  MISS=0\n"
                + "  LAST=''\n"
                + "  while :; do\n"
                + "    S=$(oc_curl -s --max-time 5 \"$URL/install/status\" 2>/dev/null)\n"
                + "    if [ -z \"$S\" ]; then\n"
                + "      # dpkg puede reemplazar curl mientras se instala: un fallo\n"
                + "      # aislado no significa que la app se haya caido.\n"
                + "      MISS=$((MISS+1))\n"
                + "      if [ $MISS -ge 30 ]; then\n"
                + "        echo 'oc-pkg: the app stopped responding' >&2\n"
                + "        return 1\n"
                + "      fi\n"
                + "      sleep 1\n"
                + "      continue\n"
                + "    fi\n"
                + "    MISS=0\n"
                + "    case \"$S\" in *\"running=\"*) ;; *)\n"
                + "      echo \"oc-pkg: unexpected status: $S\" >&2\n"
                + "      return 1 ;;\n"
                + "    esac\n"
                + "    case \"$S\" in *\"running=0\"*) return 0 ;; esac\n"
                + "    ST=$(field \"$S\" step)\n"
                + "    if [ -n \"$ST\" ] && [ \"$ST\" != \"$LAST\" ]; then\n"
                + "      echo \"oc-pkg: $ST\"\n"
                + "      LAST=\"$ST\"\n"
                + "    fi\n"
                + "    N=$((N+1))\n"
                + "    if [ $N -ge 3600 ]; then\n"
                + "      echo 'oc-pkg: timed out waiting for the installer' >&2\n"
                + "      return 1\n"
                + "    fi\n"
                + "    sleep 1\n"
                + "  done\n"
                + "}\n"
                + "S=$(oc_curl -s --max-time 5 \"$URL/install/status\" 2>/dev/null)\n"
                + "if [ -z \"$S\" ]; then\n"
                + "  echo 'oc-pkg: no response from the app; is the local server running?' >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "case \"$S\" in *\"running=1\"*)\n"
                + "  echo 'oc-pkg: another install is running; waiting for it to finish...'\n"
                + "  wait_idle || exit 1 ;;\n"
                + "esac\n"
                + "echo \"oc-pkg: installing $PKGS\"\n"
                + "TRY=0\n"
                + "RESP=''\n"
                + "while [ $TRY -lt 3 ]; do\n"
                + "  RESP=$(oc_curl -s --max-time 10 -X POST \\\n"
                + "    --data \"pkgs=$PKGS&token=$TOK\" \"$URL/install\" 2>/dev/null)\n"
                + "  case \"$RESP\" in\n"
                + "    *'\"ok\":true'*) break ;;\n"
                + "    *busy*) wait_idle || exit 1 ;;\n"
                + "    *)\n"
                + "      echo \"oc-pkg: install request failed: $RESP\" >&2\n"
                + "      exit 1 ;;\n"
                + "  esac\n"
                + "  TRY=$((TRY+1))\n"
                + "done\n"
                + "case \"$RESP\" in *'\"ok\":true'*) ;; *)\n"
                + "  echo 'oc-pkg: the installer stayed busy; try again' >&2\n"
                + "  exit 1 ;;\n"
                + "esac\n"
                + "wait_idle || exit 1\n"
                + "FINAL=$(oc_curl -s --max-time 5 \"$URL/install/status\" 2>/dev/null)\n"
                + "ERR=$(field \"$FINAL\" error)\n"
                + "INST=$(field \"$FINAL\" installed)\n"
                + "if [ -n \"$ERR\" ]; then\n"
                + "  echo \"oc-pkg: install failed: $ERR\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "echo \"oc-pkg: installed: ${INST:-$PKGS}\"\n";
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
