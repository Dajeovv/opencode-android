package com.opencode.launcher;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Caja de herramientas estilo Termux dentro de la app: descarga paquetes del
 * repositorio oficial de Termux (aarch64) y los instala en un prefijo propio
 * (files/usr) que luego se monta en proot como /data/data/com.termux/files/usr.
 *
 * Los binarios de Termux llevan el prefijo absoluto compilado dentro, asi que
 * solo funcionan si el prefijo existe en esa ruta dentro del sandbox: de ahi
 * el montage en proot.
 */
public final class LocalToolbox {
    private static final String TAG = "OpenCodeToolbox";

    /** Prefijo tal y como lo ven las herramientas dentro del sandbox. */
    public static final String SANDBOX_PREFIX = "/data/data/com.termux/files/usr";
    private static final String REPO = "https://packages.termux.dev/apt/termux-main";
    private static final String ABI = "aarch64";
    private static final String INDEX_URL =
            REPO + "/dists/stable/main/binary-" + ABI + "/Packages.gz";
    private static final long INDEX_MAX_AGE = 24L * 3600 * 1000;

    /** Set recomendado: git, python, node, ssh, make, curl, wget (+ clang). */
    public static final List<String> DEFAULT_SET = Collections.unmodifiableList(Arrays.asList(
            "git", "python", "python-pip", "nodejs", "openssh", "make", "curl", "wget",
            "cmake", "ninja", "pkg-config", "binutils", "clang", "coreutils", "bash"));

    private LocalToolbox() {
    }

    public interface Progress {
        void onStep(String message);

        void onBytes(long done, long total);
    }

    public static final class Pkg {
        public final String name;
        public final String version;
        public final String filename;
        public final String sha256;
        public final long size;
        public final List<String> depends;

        Pkg(String name, String version, String filename, String sha256, long size,
                List<String> depends) {
            this.name = name;
            this.version = version;
            this.filename = filename;
            this.sha256 = sha256;
            this.size = size;
            this.depends = depends;
        }
    }

    // ---------------------------------------------------------------- rutas

    /** Prefijo real en disco (fuera del sandbox). */
    public static File prefix(Context ctx) {
        return new File(ctx.getFilesDir(), "usr");
    }

    /** Zona de trabajo: indice, .deb descargados, temporales. */
    public static File workDir(Context ctx) {
        return new File(ctx.getFilesDir(), "toolbox");
    }

    /**
     * ¿Existe el prefijo con herramientas? Sirve para decidir si el sandbox
     * monta el prefijo y lo mete en el PATH (durante la propia instalacion
     * todavia no esta, pero dpkg ya vive dentro).
     */
    public static boolean installed(Context ctx) {
        File bin = new File(prefix(ctx), "bin");
        String[] kids = bin.list();
        return kids != null && kids.length > 0;
    }

    /** ¿Termino la instalacion completa (con el gestor de paquetes)? */
    public static boolean complete(Context ctx) {
        return new File(prefix(ctx), ".opencode-toolbox").isFile();
    }

    /** Paquetes ya instalados (del manifiesto). */
    public static List<String> installedPackages(Context ctx) {
        List<String> out = new ArrayList<>();
        File f = new File(workDir(ctx), "packages.txt");
        if (!f.isFile()) return out;
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.add(line.substring(0, tab));
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    public static String installedVersion(Context ctx, String name) {
        File f = new File(workDir(ctx), "packages.txt");
        if (!f.isFile()) return "";
        try (java.io.BufferedReader r = new java.io.BufferedReader(
                new java.io.InputStreamReader(new FileInputStream(f), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0 && line.substring(0, tab).equals(name)) {
                    return line.substring(tab + 1);
                }
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    // --------------------------------------------------------------- indice

    private static final Map<String, Pkg> indexCache = new HashMap<>();
    private static long indexStamp = 0;

    /** Descarga (o reutiliza) el indice de paquetes y lo deja en memoria. */
    public static synchronized Map<String, Pkg> index(Context ctx, Progress p)
            throws IOException {
        long now = System.currentTimeMillis();
        if (!indexCache.isEmpty() && now - indexStamp < INDEX_MAX_AGE) {
            return indexCache;
        }
        File work = workDir(ctx);
        if (!work.isDirectory() && !work.mkdirs()) throw new IOException("no toolbox dir");
        File gz = new File(work, "Packages.gz");
        if (!gz.isFile() || now - gz.lastModified() > INDEX_MAX_AGE) {
            if (p != null) p.onStep("Descargando el indice de paquetes...");
            download(REPO + "/dists/stable/main/binary-" + ABI + "/Packages.gz", gz, null, 0);
        }
        Map<String, Pkg> map = parseIndex(gz);
        indexCache.clear();
        indexCache.putAll(map);
        indexStamp = System.currentTimeMillis();
        return map;
    }

    private static Map<String, Pkg> parseIndex(File gz) throws IOException {
        Map<String, Pkg> map = new HashMap<>();
        String text;
        try (InputStream in = new GZIPInputStream(new FileInputStream(gz), 1 << 16)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            text = bo.toString("UTF-8");
        }
        for (String stanza : text.split("\n\n")) {
            if (stanza.trim().isEmpty()) continue;
            Map<String, String> f = new HashMap<>();
            String key = null;
            for (String line : stanza.split("\n")) {
                if (line.isEmpty()) continue;
                if ((line.startsWith(" ") || line.startsWith("\t")) && key != null) {
                    f.put(key, f.get(key) + " " + line.trim());
                    continue;
                }
                int c = line.indexOf(':');
                if (c <= 0) continue;
                key = line.substring(0, c).trim();
                f.put(key, line.substring(c + 1).trim());
            }
            String name = f.get("Package");
            if (name == null || name.isEmpty()) continue;
            long size = 0;
            try {
                size = Long.parseLong(f.getOrDefault("Size", "0").trim());
            } catch (NumberFormatException ignored) {
            }
            List<String> deps = new ArrayList<>();
            for (String alt : f.getOrDefault("Depends", "").split(",")) {
                String a = alt.trim();
                if (a.isEmpty()) continue;
                deps.add(a.split("\\|")[0].trim().split("\\s+")[0]);
            }
            map.put(name, new Pkg(name, f.getOrDefault("Version", ""),
                    f.getOrDefault("Filename", ""), f.getOrDefault("SHA256", ""),
                    size, deps));
        }
        return map;
    }

    // ------------------------------------------------------- resolucion

    /** Nombre real de un paquete pedido (sigue "Provides" como python3). */
    public static String realName(Map<String, Pkg> idx, String wanted) {
        if (idx.containsKey(wanted)) return wanted;
        for (Pkg p : idx.values()) {
            if (p.name.equals(wanted)) return p.name;
        }
        return null;
    }

    /**
     * Cierre de dependencias de lo pedido, en orden de instalacion.
     * Alternativas (a | b) se resuelven con el primero que exista.
     */
    public static List<Pkg> resolve(Map<String, Pkg> idx, List<String> wanted) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> queue = new ArrayList<>();
        for (String w : wanted) queue.add(w);
        while (!queue.isEmpty()) {
            String name = queue.remove(0);
            if (name == null || name.isEmpty() || seen.contains(name)) continue;
            Pkg p = idx.get(name);
            if (p == null) {
                // Puede ser un "Provides" (python3 -> python).
                String alt = findProvider(idx, name);
                if (alt == null || seen.contains(alt)) continue;
                p = idx.get(alt);
                name = alt;
            }
            seen.add(name);
            for (String d : p.depends) queue.add(d);
        }
        List<Pkg> out = new ArrayList<>();
        for (String n : seen) {
            Pkg p = idx.get(n);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static String findProvider(Map<String, Pkg> idx, String name) {
        // Solo se resuelve "Provides" con los paquetes que ya expandedimos: se
        // busca en el campo Provides guardado como dependencia virtual.
        for (Pkg p : idx.values()) {
            for (String d : p.depends) {
                if (d.equals(name)) return p.name;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ instalar

    /**
     * Instala los paquetes pedidos. Bloqueante: llamarlo desde un hilo.
     * Devuelve la lista de paquetes efectivamente instalados.
     */
    public static List<String> install(Context ctx, List<String> wanted, Progress p)
            throws IOException {
        Map<String, Pkg> idx = index(ctx, p);
        List<Pkg> plan = resolve(idx, wanted);
        if (plan.isEmpty()) throw new IOException("no hay paquetes que instalar");

        File work = workDir(ctx);
        File debs = new File(work, "debs");
        if (!debs.isDirectory() && !debs.mkdirs()) throw new IOException("no debs dir");
        File pref = prefix(ctx);
        if (!pref.isDirectory() && !pref.mkdirs()) throw new IOException("no prefix dir");

        long total = 0;
        for (Pkg pkg : plan) total += pkg.size;

        long done = 0;
        Set<String> now = new HashSet<>(installedPackages(ctx));
        File stage = new File(ctx.getCacheDir(), "tmp/toolbox-debs");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("no staging dir");
        for (Pkg pkg : plan) {
            if (p != null) p.onStep("Instalando " + pkg.name + " " + pkg.version);
            File deb = new File(stage, pkg.name + ".deb");
            if (!deb.isFile() || deb.length() != pkg.size) {
                download(REPO + "/" + pkg.filename, deb, p, done);
            }
            done += pkg.size;
            if (p != null) p.onBytes(done, total);
            if (!pkg.sha256.isEmpty()) {
                String h = sha256(deb);
                if (!h.equalsIgnoreCase(pkg.sha256)) {
                    deb.delete();
                    throw new IOException("SHA-256 no coincide en " + pkg.name);
                }
            }
        }
        // Los registra el dpkg de verdad, para que apt/pkg los vean.
        prepareDpkgDirs(prefix(ctx));
        String out = LocalRuntime.runInSandbox(ctx,
                "dpkg --unpack /tmp/toolbox-debs/*.deb >/dev/null 2>&1; "
                        + "dpkg --configure -a 2>&1 | tail -3", 600000);
        Log.i(TAG, "dpkg: " + out.replace("\n", " | "));
        for (Pkg pkg : plan) now.add(pkg.name);
        writeManifest(ctx, now, idx);
        new File(prefix(ctx), ".opencode-toolbox").createNewFile();
        writePkgShim(ctx, prefix(ctx));
        deleteTree(stage);
        return new ArrayList<>(now);
    }

    /**
     * Instala los paquetes que vienen dentro del propio APK (assets/toolbox).
     * Se ejecuta solo, en segundo plano, la primera vez: asi las herramientas
     * estan disponibles nada mas instalar, sin descargas ni pasos extra.
     *
     * Fase 1: se extraen en Java los paquetes de arranque (apt, dpkg y sus
     * dependencias) porque hacen falta para poder usar dpkg.
     * Fase 2: se pasa TODOS los .deb por el dpkg de verdad, para que queden
     * registrados en su base de datos y apt/pkg sean los de siempre.
     * Fase 3: apt-get update, para que apt tenga el indice de paquetes.
     */
    public static List<String> installBundled(Context ctx, Progress p) throws IOException {
        String[] names = ctx.getAssets().list("toolbox");
        if (names == null || names.length == 0) return new ArrayList<>();
        File work = workDir(ctx);
        File debs = new File(work, "debs");
        if (!debs.isDirectory() && !debs.mkdirs()) throw new IOException("no debs dir");
        File pref = prefix(ctx);
        if (!pref.isDirectory() && !pref.mkdirs()) throw new IOException("no prefix dir");
        Map<String, String> versions = readBundledManifest(ctx);
        Set<String> boot = readBootstrapList(ctx);

        // Fase 1: arranque (apt/dpkg y lo que necesitan).
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            String pkg = nameFromDeb(name);
            if (!boot.contains(pkg)) continue;
            if (p != null) p.onStep("Preparando el gestor de paquetes (" + pkg + ")");
            File deb = copyAsset(ctx, debs, name);
            extractDeb(deb, pref);
            deb.delete();
        }
        prepareDpkgDirs(pref);

        // Fase 2: dpkg de verdad con todos los paquetes.
        File stage = new File(ctx.getCacheDir(), "tmp/toolbox-debs");
        deleteTree(stage);
        if (!stage.mkdirs()) throw new IOException("no staging dir");
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            copyAsset(ctx, stage, name);
        }
        if (p != null) p.onStep("Registrando los paquetes (dpkg)...");
        String dpkgOut = LocalRuntime.runInSandbox(ctx,
                "dpkg --unpack /tmp/toolbox-debs/*.deb >/dev/null 2>&1; "
                        + "dpkg --configure -a 2>&1 | tail -5", 600000);
        Log.i(TAG, "dpkg: " + dpkgOut.replace("\n", " | "));

        // Fase 3: el indice de paquetes para apt.
        if (p != null) p.onStep("Actualizando el indice de apt...");
        LocalRuntime.runInSandbox(ctx, "apt-get update -qq 2>&1 | tail -3", 300000);

        List<String> done = new ArrayList<>();
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            done.add(nameFromDeb(name));
        }
        writeManifestBundled(ctx, done, versions);
        new File(pref, ".opencode-toolbox").createNewFile();
        writePkgShim(ctx, pref);
        deleteTree(stage);
        return done;
    }

    /** Crea el arbol de trabajo de dpkg y las carpetas que espera apt. */
    static void prepareDpkgDirs(File pref) {
        File admin = new File(pref, "var/lib/dpkg");
        for (String d : new String[]{"info", "updates", "triggers", "alternatives", "parts"}) {
            File f = new File(admin, d);
            if (!f.isDirectory()) f.mkdirs();
        }
        for (String f : new String[]{"status", "available"}) {
            File file = new File(admin, f);
            if (!file.isFile()) {
                try {
                    file.createNewFile();
                } catch (IOException ignored) {
                }
            }
        }
        File etc = new File(pref, "etc/apt");
        for (String d : new String[]{"apt.conf.d", "preferences.d", "sources.list.d",
                "trusted.gpg.d", "keyrings"}) {
            File f = new File(etc, d);
            if (!f.isDirectory()) f.mkdirs();
        }
        File cache = new File(pref, "var/cache/apt/archives/partial");
        if (!cache.isDirectory()) cache.mkdirs();
        File lists = new File(pref, "var/lib/apt/lists/partial");
        if (!lists.isDirectory()) lists.mkdirs();
        // apt y dpkg escriben aqui; si falta la carpeta se quejan.
        for (String d : new String[]{"var/log", "var/log/apt", "var/log/dpkg",
                "var/cache/apt/archives/partial", "var/lib/apt/lists/partial",
                "var/lib/dpkg/info", "var/lib/dpkg/updates", "var/lib/dpkg/triggers",
                "var/lib/dpkg/alternatives", "var/lib/dpkg/parts"}) {
            File f = new File(pref, d);
            if (!f.isDirectory()) f.mkdirs();
        }
        // apt de Termux usa el cache de la app Termux (/data/data/com.termux/cache),
        // que aqui no existe: se apunta al del propio prefijo.
        File conf = new File(etc, "apt.conf.d/99opencode");
        try (java.io.FileWriter w = new java.io.FileWriter(conf, false)) {
            w.write("Dir::Cache \"" + pref.getAbsolutePath() + "/var/cache/apt\";\n");
            w.write("Dir::Cache::archives \"" + pref.getAbsolutePath()
                    + "/var/cache/apt/archives\";\n");
            w.write("Dir::Cache::pkgcache \"" + pref.getAbsolutePath()
                    + "/var/cache/apt/pkgcache.bin\";\n");
            w.write("Dir::Cache::srcpkgcache \"" + pref.getAbsolutePath()
                    + "/var/cache/apt/srcpkgcache.bin\";\n");
            w.write("Dir::State::status \"" + pref.getAbsolutePath()
                    + "/var/lib/dpkg/status\";\n");
        } catch (IOException ignored) {
        }
    }

    /** Copia un asset del APK al disco. */
    private static File copyAsset(Context ctx, File dir, String name) throws IOException {
        File dest = new File(dir, name);
        try (InputStream in = ctx.getAssets().open("toolbox/" + name);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return dest;
    }

    /** Lista de paquetes de arranque (los que se extraen en Java). */
    private static Set<String> readBootstrapList(Context ctx) {
        Set<String> out = new LinkedHashSet<>();
        try (InputStream in = ctx.getAssets().open("toolbox/bootstrap.txt");
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String n = line.trim();
                if (!n.isEmpty()) out.add(n);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Lee el manifiesto de versiones que viene con los .deb del APK. */
    private static Map<String, String> readBundledManifest(Context ctx) {
        Map<String, String> out = new HashMap<>();
        try (InputStream in = ctx.getAssets().open("toolbox/packages.txt");
             java.io.BufferedReader r = new java.io.BufferedReader(
                     new java.io.InputStreamReader(in, "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (tab > 0) out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void writeManifestBundled(Context ctx, List<String> names,
            Map<String, String> versions) {
        Set<String> all = new LinkedHashSet<>(installedPackages(ctx));
        all.addAll(names);
        StringBuilder sb = new StringBuilder();
        for (String n : all) {
            String v = versions.get(n);
            if (v == null) v = versionFromDeb(n + ".deb");
            sb.append(n).append('\t').append(v).append('\n');
        }
        try (java.io.FileWriter w = new java.io.FileWriter(
                new File(workDir(ctx), "packages.txt"), false)) {
            w.write(sb.toString());
        } catch (IOException ignored) {
        }
    }

    /** Nombre del paquete a partir del fichero: git.deb -> git */
    static String nameFromDeb(String file) {
        String s = file;
        if (s.endsWith(".deb")) s = s.substring(0, s.length() - 4);
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '_' && Character.isDigit(s.charAt(i + 1))) {
                return s.substring(0, i);
            }
        }
        return s;
    }

    /** Version a partir del fichero: git_2.56.0_aarch64.deb -> 2.56.0 */
    static String versionFromDeb(String file) {
        String s = file;
        if (s.endsWith(".deb")) s = s.substring(0, s.length() - 4);
        int end = s.lastIndexOf('_');
        if (end > 0) s = s.substring(0, end);
        int start = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '_' && Character.isDigit(s.charAt(i + 1))) {
                start = i + 1;
                break;
            }
        }
        return start > 0 ? s.substring(start) : "";
    }

    private static void writeManifest(Context ctx, Set<String> names, Map<String, Pkg> idx) {
        File f = new File(workDir(ctx), "packages.txt");
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            Pkg p = idx.get(n);
            sb.append(n).append('\t').append(p == null ? "" : p.version).append('\n');
        }
        try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
            w.write(sb.toString());
        } catch (IOException ignored) {
        }
    }

    /** Reescribe el shim "pkg" (por si ha cambiado la forma de hablar). */
    public static void refreshShim(Context ctx) {
        try {
            if (installed(ctx)) {
                writePkgShim(ctx, prefix(ctx));
                writeDeviceScripts(ctx, prefix(ctx));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Comandos de dispositivo al estilo Termux:API (bateria, portapapeles, voz,
     * avisos, vibracion, brillo, volumen, red, sensores). Cada script llama a la
     * app por HTTP; la app hace la accion con sus permisos.
     */
    private static void writeDeviceScripts(Context ctx, File pref) {
        File bin = new File(pref, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        File libexec = new File(pref, "libexec");
        if (!libexec.isDirectory() && !libexec.mkdirs()) return;

        File client = new File(libexec, "opencode-api");
        String clientBody = "#!/data/data/com.termux/files/usr/bin/sh\n"
                + "# Cliente de la API del dispositivo de OpenCode (mismo uso que\n"
                + "# el termux-api de Termux, pero contra esta app).\n"
                + "EP=$1; shift\n"
                + "PORT=${OPENCODE_TOOLBOX_PORT:-" + ToolboxServer.PORT + "}\n"
                + "TOK=$OPENCODE_TOOLBOX_TOKEN\n"
                + "set -- \"$@\"\n"
                + "nargs=$#\n"
                + "i=0\n"
                + "while [ $i -lt $nargs ]; do\n"
                + "  A=\"$1\"; shift; i=$((i+1))\n"
                + "  set -- \"$@\" -H \"X-OpenCode-Arg-$i: $A\"\n"
                + "done\n"
                + "exec /data/data/com.termux/files/usr/bin/curl -s -X POST \\\n"
                + "  -H \"X-OpenCode-Token: $TOK\" \"$@\" \\\n"
                + "  \"http://127.0.0.1:$PORT/device/$EP\"\n";
        writeScript(client, clientBody);

        String[][] cmds = {
                {"termux-battery-status", "battery"},
                {"termux-clipboard-get", "clipboard-get"},
                {"termux-clipboard-set", "clipboard-set"},
                {"termux-toast", "toast"},
                {"termux-vibrate", "vibrate"},
                {"termux-brightness", "brightness"},
                {"termux-volume", "volume"},
                {"termux-wifi-connectioninfo", "wifi-info"},
                {"termux-sensor", "sensor"},
                {"termux-sensor-list", "sensor-list"}
        };
        for (String[] c : cmds) {
            File f = new File(bin, c[0]);
            String body = "#!/data/data/com.termux/files/usr/bin/sh\n"
                    + "exec /data/data/com.termux/files/usr/libexec/opencode-api " + c[1]
                    + " \"$@\"\n";
            writeScript(f, body);
        }
    }

    private static void writeScript(File f, String body) {
        try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
            w.write(body);
        } catch (IOException ignored) {
        }
        f.setExecutable(true, true);
    }

    /** Shim "pkg" de respaldo: solo si no esta el de verdad de Termux. */
    private static void writePkgShim(Context ctx, File pref) {
        File bin = new File(pref, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        File shim = new File(bin, "pkg");
        if (shim.isFile()) {
            // El paquete termux-tools trae el pkg de verdad: ese manda.
            return;
        }
        String body = "#!/system/bin/sh\n"
                + "# Instalador de paquetes de OpenCode (paquetes Termux).\n"
                + "# uso: pkg install git python ...\n"
                + "VERB=$1; shift\n"
                + "PKGS=\"\"\n"
                + "for p in \"$@\"; do PKGS=\"$PKGS,$p\"; done\n"
                + "PORT=${OPENCODE_TOOLBOX_PORT:-" + ToolboxServer.PORT + "}\n"
                + "TOK=$OPENCODE_TOOLBOX_TOKEN\n"
                + "BODY=\"pkgs=$PKGS&token=$TOK\"\n"
                + "URL=\"http://127.0.0.1:$PORT/install\"\n"
                + "if command -v curl >/dev/null 2>&1; then\n"
                + "  exec curl -s -X POST --data \"$BODY\" \"$URL\"\n"
                + "fi\n"
                + "if /system/bin/toybox wget --help >/dev/null 2>&1; then\n"
                + "  exec /system/bin/toybox wget -q -O - --post-data=\"$BODY\" \"$URL\"\n"
                + "fi\n"
                + "echo \"pkg: falta curl o wget en el sandbox\" >&2\n";
        try (java.io.FileWriter w = new java.io.FileWriter(shim, false)) {
            w.write(body);
        } catch (IOException ignored) {
        }
        shim.setExecutable(true, true);
    }

    // ------------------------------------------------------------ extraccion

    /** Desempaqueta un .deb (ar) y deja su contenido dentro del prefijo. */
    static void extractDeb(File deb, File pref) throws IOException {
        File tmp = new File(deb.getParentFile(), "x-" + deb.getName());
        deleteTree(tmp);
        if (!tmp.mkdirs()) throw new IOException("no tmp dir");
        File data = extractArMember(deb, "data.tar", tmp);
        if (data == null) throw new IOException("deb sin data.tar: " + deb.getName());
        int rc = runTar(data, tmp);
        if (rc != 0) throw new IOException("tar fallo (" + rc + ") en " + deb.getName());
        // El deb trae ./data/data/com.termux/files/usr/... : se mueve al prefijo.
        File src = new File(tmp, "data/data/com.termux/files/usr");
        if (!src.isDirectory()) {
            src = new File(tmp, "data/data/com.termux/files/usr");
        }
        if (src.isDirectory()) mergeTree(src, pref);
        deleteTree(tmp);
        data.delete();
    }

    /** Saca del ar el miembro que empieza por "data.tar" (xz o gz). */
    private static File extractArMember(File deb, String prefix, File dir) throws IOException {
        byte[] raw = readAll(deb, 64 * 1024 * 1024);
        if (raw.length < 8 || !"!<arch>\n".equals(new String(raw, 0, 8))) {
            throw new IOException("no es un ar: " + deb.getName());
        }
        int off = 8;
        while (off + 60 <= raw.length) {
            String name = new String(raw, off, 16, "UTF-8").trim();
            int size;
            try {
                size = Integer.parseInt(new String(raw, off + 48, 10, "UTF-8").trim());
            } catch (Exception e) {
                break;
            }
            int dataOff = off + 60;
            if (name.startsWith(prefix)) {
                // Se conserva el nombre original (data.tar.xz) porque de el
                // depende la compresion que usa el tar de Android.
                String member = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
                File out = new File(dir, member);
                try (OutputStream os = new FileOutputStream(out)) {
                    os.write(raw, dataOff, Math.min(size, raw.length - dataOff));
                }
                return out;
            }
            off = dataOff + size + (size % 2);
        }
        return null;
    }

    /**
     * Extrae el tar con el tar de Android. La compresion se quita aqui (xz en
     * Java, gzip con la libreria de Java) y el tar se le pasa por stdin: el
     * tar de Toybox delega en binarios externos (xz, gzip) que en Android
     * no siempre existen.
     */
    private static int runTar(File data, File dest) throws IOException {
        Process p = new ProcessBuilder("/system/bin/tar", "-x", "-f", "-", "-C",
                dest.getAbsolutePath()).redirectErrorStream(true).start();
        StringBuilder err = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (err.length() < 4000) err.append(line).append('\n');
                }
            } catch (Exception ignored) {
            }
        }, "tar-out");
        reader.setDaemon(true);
        reader.start();
        try (OutputStream os = p.getOutputStream()) {
            InputStream in;
            if (data.getName().endsWith(".xz")) {
                in = new org.tukaani.xz.XZInputStream(new FileInputStream(data));
            } else {
                in = new java.util.zip.GZIPInputStream(new FileInputStream(data));
            }
            try (InputStream src = in) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = src.read(buf)) > 0) os.write(buf, 0, n);
            }
        } catch (Exception e) {
            p.destroy();
            throw new IOException("no se pudo descomprimir " + data.getName() + ": " + e);
        }
        int rc;
        try {
            rc = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rc = -1;
        }
        if (rc != 0) Log.w(TAG, "tar: " + err.toString().trim());
        return rc;
    }

    /** Mueve un arbol dentro de otro, recreando enlaces simbolicos. */
    static void mergeTree(File src, File dst) throws IOException {
        if (!dst.isDirectory() && !dst.mkdirs()) throw new IOException("no mkdir " + dst);
        File[] kids = src.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            File target = new File(dst, k.getName());
            if (isSymlink(k)) {
                if (target.exists() || isSymlink(target)) {
                    try {
                        Os.remove(target.getAbsolutePath());
                    } catch (ErrnoException ignored) {
                    }
                }
                try {
                    Os.symlink(Os.readlink(k.getAbsolutePath()), target.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("no se pudo crear el enlace " + target.getName());
                }
                continue;
            }
            if (k.isDirectory()) {
                mergeTree(k, target);
            } else {
                if (target.exists() && !target.delete()) {
                    // Si esta en uso, se deja el antiguo.
                    continue;
                }
                if (!k.renameTo(target)) {
                    copyFile(k, target);
                    k.delete();
                }
            }
        }
    }

    private static boolean isSymlink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ descargas

    private static void download(String url, File dest, Progress p, long already)
            throws IOException {
        File part = new File(dest.getAbsolutePath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", "opencode-android");
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("HTTP " + code + " en " + url);
        long total = c.getContentLengthLong();
        try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
             OutputStream out = new FileOutputStream(part)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long acc = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                acc += n;
                if (p != null) p.onBytes(already + acc, total > 0 ? already + total : 0);
            }
        }
        if (!part.renameTo(dest)) {
            if (!dest.delete() || !part.renameTo(dest)) {
                throw new IOException("no se pudo guardar " + dest.getName());
            }
        }
    }

    // ------------------------------------------------------------ utilidades

    public static String sha256(File f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256: " + e);
        }
    }

    private static byte[] readAll(File f, int max) throws IOException {
        long len = f.length();
        if (len > max) throw new IOException("fichero demasiado grande: " + f.getName());
        byte[] out = new byte[(int) len];
        try (InputStream in = new FileInputStream(f)) {
            int off = 0;
            while (off < out.length) {
                int n = in.read(out, off, out.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return out;
    }

    static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /** Espacio libre en el almacenamiento de la app (bytes). */
    public static long freeSpace(Context ctx) {
        try {
            android.os.StatFs st = new android.os.StatFs(ctx.getFilesDir().getAbsolutePath());
            return st.getAvailableBlocksLong() * st.getBlockSizeLong();
        } catch (Exception e) {
            return 0;
        }
    }

    public static String human(long bytes) {
        if (bytes <= 0) return "0 MB";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    static {
        Log.i(TAG, "toolbox cargado");
    }
}
