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
 * (files/usr).
 *
 * Los binarios de Termux son ELF de Android (bionic), asi que se pueden
 * ejecutar de forma nativa con el linker del sistema: no hace falta el
 * sandbox de proot para usarlos. El prefijo se expone ademas en la ruta
 * /data/data/com.termux/files/usr dentro de proot, que sigue haciendo falta
 * para el gestor de paquetes (apt/dpkg), que escribe por ruta absoluta.
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

    /** Callback opcional hacia la UI (MainActivity) para mostrar progreso. */
    public static volatile Progress uiProgress = null;

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

    /**
     * Firma barata del prefijo para detectar instalaciones.
     *
     *  Solo mirar $PREFIX.lastModified() no vale: al instalar un paquete
     *  cambian bin/, lib/ y var/lib/dpkg/status, no la raiz. La firma
     *  combina mtime, numero de entradas y tamano, suficiente para disparar
     *  fixupPrefix + CommandResolver.refresh sin recorrer el arbol entero.
     */
    public static String prefixSignature(File pref) {
        if (pref == null) return "";
        return sigPart(pref)
                + "|" + sigPart(new File(pref, "bin"))
                + "|" + sigPart(new File(pref, "lib"))
                + "|" + sigPart(new File(pref, "var/lib/dpkg/status"))
                + "|" + sigPart(new File(pref, "etc/apt"));
    }

    private static String sigPart(File f) {
        if (f == null || !f.exists()) return "0:0:0";
        String[] kids = f.list();
        return f.lastModified() + ":" + (kids == null ? 0 : kids.length) + ":" + f.length();
    }

    /**
     * Deja el prefijo listo para usarse de forma nativa.
     *
     *  Los scripts vienen con la ruta de Termux en el interprete. Se
     *  reescriben una sola vez (se marca con un fichero) para que el
     *  prefijo no necesite proot: asi el agente ve la ruta real y los
     *  comandos van mas rapido.
     *
     *  Es idempotente y barato: si no hay nada que reescribir no hace
     *  nada. */
    public static void ensureNativeScripts(Context ctx) {
        try {
            File pref = prefix(ctx);
            File marker = new File(pref, ".opencode-native-scripts");
            if (marker.isFile()) return;
            if (!installed(ctx)) return;
            rewriteScriptHeaders(pref);
            fixTermuxSymlinks(pref);
            installPipShortcut(ctx, pref);
            // Los scripts del toolbox (el comando "opencode" con la version)
            // se regeneran para que el agente los tenga.
            writePkgShim(ctx, pref);
            writeRuntimeVersion(ctx, pref);
            marker.createNewFile();
        } catch (Exception e) {
            Log.w(TAG, "no se pudieron reescribir los scripts: " + e);
        }
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
            if (p != null) p.onStep(ServerWatch.t(ctx,
                    "Descargando el indice de paquetes...",
                    "Downloading the package index..."));
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
        // Restos de extracciones interrumpidas: se intentan limpiar, pero un
        // fallo aqui ya no impide instalar (las nuevas usan nombre unico).
        File[] stale = debs.listFiles();
        if (stale != null) {
            for (File f : stale) {
                if (f.getName().startsWith("x-")) deleteTree(f);
            }
        }
        File pref = prefix(ctx);
        if (!pref.isDirectory() && !pref.mkdirs()) throw new IOException("no prefix dir");

        Set<String> now = new HashSet<>(installedPackages(ctx));
        // Ya instalados: no hace falta re-descargar ni re-registrar. Asi, si
        // un pkg install se corta por timeout, el siguiente es inmediato.
        List<Pkg> toInstall = new ArrayList<>();
        for (Pkg pkg : plan) if (!now.contains(pkg.name)) toInstall.add(pkg);
        if (toInstall.isEmpty()) {
            Log.i(TAG, "todos los paquetes ya estaban instalados");
            writeAgentNotes(ctx);
            if (CommandResolver.refresh(ctx) == null) {
                Log.w(TAG, "no se pudo regenerar el resolvedor tras instalar");
            }
            return new ArrayList<>(now);
        }
        plan = toInstall;
        long total = 0;
        for (Pkg pkg : plan) total += pkg.size;

        long done = 0;
        // Se conserva entre ejecuciones para reanudar descargas interrumpidas.
        File stage = new File(ctx.getCacheDir(), "tmp/toolbox-debs");
        if (!stage.isDirectory() && !stage.mkdirs()) {
            throw new IOException("no staging dir: " + stage.getName());
        }
        for (Pkg pkg : plan) {
            if (p != null) p.onStep(ServerWatch.t(ctx, "Instalando ", "Installing ")
                    + pkg.name + " " + pkg.version);
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
        // dpkg vuelve a desempaquetar los scripts con la ruta de Termux:
        // se reescriben para que el prefijo quede nativo.
        rewriteScriptHeaders(prefix(ctx));
        fixTermuxSymlinks(prefix(ctx));
        // Atajo de pip para las consultas de solo lectura.
        installPipShortcut(ctx, prefix(ctx));
        for (Pkg pkg : plan) now.add(pkg.name);
        writeManifest(ctx, now, idx);
        new File(prefix(ctx), ".opencode-toolbox").createNewFile();
        writePkgShim(ctx, prefix(ctx));
        writeRuntimeVersion(ctx, prefix(ctx));
        // Los binarios y wrappers recien instalados (java, aapt2...) deben
        // entrar en oc-native-env.sh ya, sin reiniciar el servidor.
        if (CommandResolver.refresh(ctx) == null) {
            Log.w(TAG, "no se pudo regenerar el resolvedor tras instalar");
        }
        // Las instrucciones del entorno deben estar donde OpenCode las busca.
        ensurePlatformJar(ctx, pref);
        writeAgentNotes(ctx);
        // stage se conserva para reanudar si se corta por timeout.
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
        // Restos de extracciones interrumpidas: se intentan limpiar, pero un
        // fallo aqui ya no impide instalar (las nuevas usan nombre unico).
        File[] stale = debs.listFiles();
        if (stale != null) {
            for (File f : stale) {
                if (f.getName().startsWith("x-")) deleteTree(f);
            }
        }
        File pref = prefix(ctx);
        if (!pref.isDirectory() && !pref.mkdirs()) throw new IOException("no prefix dir");
        Map<String, String> versions = readBundledManifest(ctx);
        Set<String> boot = readBootstrapList(ctx);

        // Fase 1: arranque (apt/dpkg y lo que necesitan).
        int totalDebs = 0;
        for (String n : names) if (n.endsWith(".deb")) totalDebs++;
        int bootDone = 0;
        int bootTotal = 0;
        for (String n : names) if (n.endsWith(".deb")) {
            String pp = nameFromDeb(n);
            if (boot.contains(pp)) bootTotal++;
        }
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            String pkg = nameFromDeb(name);
            if (!boot.contains(pkg)) continue;
            if (p != null) p.onStep(ServerWatch.t(ctx,
                    "Preparando el gestor de paquetes (",
                    "Preparing the package manager (") + pkg + ")");
            File deb = copyAsset(ctx, debs, name);
            extractDeb(deb, pref);
            deb.delete();
            bootDone++;
            if (p != null && totalDebs > 0) {
                // Fase 1 ocupa del 0 al 30 %.
                long done = (long) (bootDone * 30.0 / Math.max(1, bootTotal));
                if (LocalToolbox.uiProgress != null) LocalToolbox.uiProgress.onBytes(done, 100);
            }
        }
        prepareDpkgDirs(pref);

        // Fase 2: dpkg de verdad con todos los paquetes.
        File stage = new File(ctx.getCacheDir(), "tmp/toolbox-debs");
        deleteTree(stage);
        if (!stage.mkdirs() && !stage.isDirectory()) {
            throw new IOException("no staging dir: " + stage.getName());
        }
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            copyAsset(ctx, stage, name);
        }
        if (p != null) p.onStep(ServerWatch.t(ctx,
                "Registrando los paquetes (dpkg)...",
                "Registering packages (dpkg)..."));
        if (LocalToolbox.uiProgress != null) LocalToolbox.uiProgress.onBytes(30, 100);
        String dpkgOut = LocalRuntime.runInSandbox(ctx,
                "dpkg --unpack /tmp/toolbox-debs/*.deb >/dev/null 2>&1; "
                        + "dpkg --configure -a 2>&1 | tail -5", 600000);
        Log.i(TAG, "dpkg: " + dpkgOut.replace("\n", " | "));
        if (LocalToolbox.uiProgress != null) LocalToolbox.uiProgress.onBytes(85, 100);

        // Los scripts vuelven a venir con la ruta de Termux: dpkg los
        // acaba de desempaquetar. Se reescriben otra vez, ya con la
        // instalacion completa, para que todo el prefijo quede nativo.
        rewriteScriptHeaders(pref);
        // Los enlaces simbolicos a la ruta de Termux (el certificado de
        // certifi) quedan rotos fuera del sandbox: pip falla al instalar.
        fixTermuxSymlinks(pref);
        // Atajo de pip para las consultas de solo lectura.
        installPipShortcut(ctx, pref);

        // Fase 3: el indice de paquetes para apt. Se lanza en segundo plano
        // para no bloquear la instalacion: el toolbox se marca como completo
        // en cuanto dpkg termina, y el indice se actualiza en paralelo.
        // Las instalaciones del agente no dependen de este update: pkg install
        // usa el indice propio del instalador verificado.
        if (LocalToolbox.uiProgress != null) LocalToolbox.uiProgress.onBytes(100, 100);
        final Context bgCtx = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                LocalRuntime.runInSandbox(bgCtx, "apt-get update -qq 2>&1 | tail -3", 300000);
                Log.i(TAG, "apt-get update en segundo plano completado");
            } catch (Exception e) {
                Log.w(TAG, "apt-get update en segundo plano fallo: " + e);
            }
        }, "apt-update-bg").start();

        List<String> done = new ArrayList<>();
        for (String name : names) {
            if (!name.endsWith(".deb")) continue;
            done.add(nameFromDeb(name));
        }
        writeManifestBundled(ctx, done, versions);
        new File(pref, ".opencode-toolbox").createNewFile();
        writePkgShim(ctx, pref);
        writeRuntimeVersion(ctx, pref);
        // Deja el resolvedor listo con el prefijo completo, sin esperar al
        // hilo periodico ni a un reinicio del servidor.
        if (CommandResolver.refresh(ctx) == null) {
            Log.w(TAG, "no se pudo regenerar el resolvedor del toolbox");
        }
        // Deja las instrucciones del entorno donde OpenCode las busca:
        // global (OPENCODE_CONFIG_DIR) y proyecto (raiz del workspace).
        ensurePlatformJar(ctx, pref);
        writeAgentNotes(ctx);
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
                ensurePlatformJar(ctx, prefix(ctx));
                writePkgShim(ctx, prefix(ctx));
        writeRuntimeVersion(ctx, prefix(ctx));
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
    /**
     * Preambulo para que los scripts nativos usen el curl del prefijo con el
     * linker del sistema: Android no permite execve directo en files/.
     */
    private static String nativeCurlPrelude(Context ctx, File pref) {
        String prefix = pref.getAbsolutePath();
        String home = new File(ctx.getFilesDir(), "home").getAbsolutePath();
        String nd = ctx.getApplicationInfo().nativeLibraryDir;
        // Dentro de proot el prefijo esta montado en su ruta de Termux y el
        // curl funciona directo. En el shell nativo hay que lanzarlo con el
        // linker del sistema (Android bloquea execve en files/).
        return "if [ -x /data/data/com.termux/files/usr/bin/curl ]; then\n"
                + "  CURL=/data/data/com.termux/files/usr/bin/curl\n"
                + "  oc_curl() { \"$CURL\" \"$@\"; }\n"
                + "else\n"
                + "  CURL='" + prefix + "/bin/curl'\n"
                + "  OC_LIBS='" + prefix + "/lib:" + home + "/lib:" + nd + "'\n"
                + "  OC_CA='" + prefix + "/etc/tls/cert.pem'\n"
                + "  oc_curl() { LD_LIBRARY_PATH=\"$OC_LIBS\" CURL_CA_BUNDLE=\"$OC_CA\" "
                + "SSL_CERT_FILE=\"$OC_CA\" /system/bin/linker64 \"$CURL\" \"$@\"; }\n"
                + "fi\n";
    }

    private static void writeDeviceScripts(Context ctx, File pref) {
        File bin = new File(pref, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) return;
        File libexec = new File(pref, "libexec");
        if (!libexec.isDirectory() && !libexec.mkdirs()) return;

        File client = new File(libexec, "opencode-api");
        String clientBody = "#!/system/bin/sh\n"
                + nativeCurlPrelude(ctx, pref)
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
                + "oc_curl -s -X POST \\\n"
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
                {"termux-sensor-list", "sensor-list"},
                {"termux-open-url", "open-url"},
                {"termux-open-app", "open-app"},
                {"termux-open-file", "open-file"},
                {"termux-list-apps", "list-apps"},
                {"termux-install-apk", "install-apk"},
                {"termux-install-status", "install-status"},
                {"termux-alias", "alias"}
        };
        for (String[] c : cmds) {
            File f = new File(bin, c[0]);
            String body = "#!/system/bin/sh\n"
                    + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                    + "exec /system/bin/sh \"$OC_PREFIX/libexec/opencode-api\" " + c[1]
                    + " \"$@\"\n";
            writeScript(f, body);
        }

        // "monkey -p <paquete>" es como se lanza una app en Termux, pero aqui
        // no hace nada (el monkey del sistema necesita INJECT_EVENTS). Este
        // envoltorio lo traduce a la API de la app, que si puede abrirla.
        File monkey = new File(bin, "monkey");
        try {
            java.nio.file.Files.deleteIfExists(monkey.toPath());
        } catch (Exception ignored) {
        }
        String monkeyBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "# monkey -p <paquete> [-c <categoria>] [n]: abre la app.\n"
                + "PKG=\"\"\n"
                + "PREV=\"\"\n"
                + "for a in \"$@\"; do\n"
                + "  if [ \"$PREV\" = \"-p\" ]; then PKG=$a; fi\n"
                + "  PREV=$a\n"
                + "done\n"
                + "if [ -n \"$PKG\" ]; then\n"
                + "  exec /system/bin/sh \"$OC_PREFIX/libexec/opencode-api\" open-app \"$PKG\"\n"
                + "fi\n"
                + "exec /system/bin/monkey \"$@\"\n";
        writeScript(monkey, monkeyBody);

        // Envoltorio de "pm": "pm install" no puede funcionar desde la app
        // (el servidor del sistema no puede leer sus ficheros y el modo
        // reverse es de shell/root). En vez de soltar un muro de errores de
        // SELinux, se dice que use el comando que si funciona: asi el
        // agente no se queda probando variantes del mismo camino.
        File pm = new File(bin, "pm");
        try {
            java.nio.file.Files.deleteIfExists(pm.toPath());
        } catch (Exception ignored) {
        }
        String pmBody = "#!/system/bin/sh\n"
                + "# pm install no funciona desde una app normal.\n"
                + "if [ \"$1\" = \"install\" ] || [ \"$1\" = \"install-create\" ]"
                + " || [ \"$1\" = \"install-write\" ] || [ \"$1\" = \"install-commit\" ]"
                + " || [ \"$1\" = \"install-session\" ]; then\n"
                + "  echo \"pm $1 cannot work from this app: the system server\"\n"
                + "  echo \"cannot read its files, and reverse mode is reserved\"\n"
                + "  echo \"for shell/root. Use: termux-install-apk <path> [sha256]\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "# Sin las libs del prefijo: si no, los binarios del sistema no\n"
                + "# enlazan (\"cannot locate symbol Xzs_Construct\").\n"
                + "unset LD_LIBRARY_PATH\n"
                + "exec /system/bin/pm \"$@\"\n";
        writeScript(pm, pmBody);

        // Envoltorio compuesto: descarga + verifica hash + instala en un solo
        // paso, para que el agente no tenga que encadenar varios comandos.
        File installUrl = new File(bin, "termux-install-url");
        try {
            java.nio.file.Files.deleteIfExists(installUrl.toPath());
        } catch (Exception ignored) {
        }
        String installUrlBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "URL=$1\n"
                + "SHA=$2\n"
                + "OPEN=$3\n"
                + "if [ -z \"$URL\" ]; then\n"
                + "  echo \"usage: termux-install-url <url> [sha256] [open]\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "TMP=/storage/emulated/0/Download/termux-download-$$.apk\n"
                + "echo \"downloading $URL\"\n"
                + "if ! curl -sL -o \"$TMP\" \"$URL\"; then\n"
                + "  echo \"download failed\" >&2\n"
                + "  rm -f \"$TMP\"\n"
                + "  exit 1\n"
                + "fi\n"
                + "if [ -n \"$SHA\" ]; then\n"
                + "  REAL=$(sha256sum \"$TMP\" | awk '{print $1}')\n"
                + "  if [ \"$REAL\" != \"$SHA\" ]; then\n"
                + "    echo \"sha256 mismatch: expected $SHA got $REAL\" >&2\n"
                + "    rm -f \"$TMP\"\n"
                + "    exit 1\n"
                + "  fi\n"
                + "fi\n"
                + "if [ -n \"$SHA\" ]; then\n"
                + "  termux-install-apk \"$TMP\" \"$SHA\" \"$OPEN\"\n"
                + "else\n"
                + "  termux-install-apk \"$TMP\" \"\" \"$OPEN\"\n"
                + "fi\n"
                + "RC=$?\n"
                + "rm -f \"$TMP\"\n"
                + "exit $RC\n";
        writeScript(installUrl, installUrlBody);

        // Comandos con root: el agente los manda por HTTP y la app usa su.
        // "am start" del sistema no vale desde una app normal: el comando se
        // presenta como com.android.shell (UID 2000) y el framework rechaza la
        // llamada porque el UID que la hace no es ese. Este envoltorio lo
        // traduce a la API de la app, que si puede abrir apps porque lo hace
        // en su nombre y con sus permisos.
        File am = new File(bin, "am");
        try {
            // Si hay un enlace simbolico a /system/bin/am hay que borrarlo
            // antes de escribir: el fichero se escribiria a traves del enlace.
            java.nio.file.Files.deleteIfExists(am.toPath());
        } catch (Exception ignored) {
        }
        String amBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "# am start -> la API de la app (el am del sistema no vale).\n"
                + "SUB=$1\n"
                + "[ $# -gt 0 ] && shift\n"
                + "case \"$SUB\" in\n"
                + "  start|start-activity)\n"
                + "    DATA=\"\"; COMP=\"\"; PKG=\"\"\n"
                + "    while [ $# -gt 0 ]; do\n"
                + "      case \"$1\" in\n"
                + "        --data=*) DATA=${1#--data=} ;;\n"
                + "        --data|-d) shift; DATA=${1:-} ;;\n"
                + "        --component=*) COMP=${1#--component=} ;;\n"
                + "        --component|-n) shift; COMP=${1:-} ;;\n"
                + "        --package=*) PKG=${1#--package=} ;;\n"
                + "        --package|-p) shift; PKG=${1:-} ;;\n"
                + "        -a|--action|--type|-t|-c|--category|--user|--display|-e|--es"
                + "|--ei|--el|--ez|--ed|--esa|--f|--selector|--uri) shift ;;\n"
                + "        *) : ;;\n"
                + "      esac\n"
                + "      shift\n"
                + "    done\n"
                + "    if [ -n \"$COMP\" ]; then\n"
                + "      exec /system/bin/sh \"$OC_PREFIX/libexec/opencode-api\" open-app \"$COMP\"\n"
                + "    fi\n"
                + "    if [ -n \"$PKG\" ]; then\n"
                + "      # am start -a MAIN -c LAUNCHER -p <paquete>: abrir la app.\n"
                + "      exec /system/bin/sh \"$OC_PREFIX/libexec/opencode-api\" open-app \"$PKG\"\n"
                + "    fi\n"
                + "    if [ -n \"$DATA\" ]; then\n"
                + "      exec /system/bin/sh \"$OC_PREFIX/libexec/opencode-api\" open-url \"$DATA\"\n"
                + "    fi\n"
                + "    echo '{\"error\":\"am start needs -d <url>, -n <package/activity> or -p <package>\"}' >&2\n"
                + "    exit 1\n"
                + "    ;;\n"
                + "  *)\n"
                + "    unset LD_LIBRARY_PATH\n"
                + "    exec /system/bin/am \"$SUB\" \"$@\"\n"
                + "    ;;\n"
                + "esac\n";
        writeScript(am, amBody);

        // Comando "opencode": el agente no tenia forma de saber que version
        // del runtime estaba usando (no hay binario en el PATH). Este
        // envoltorio la dice y, con otros argumentos, pasa al binario real.
        File opencode = new File(bin, "opencode");
        String opencodeBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "# Version del runtime de opencode que usa la app.\n"
                + "OC_VER=$(cat \"$OC_PREFIX/lib/termux-version\" 2>/dev/null)\n"
                + "if [ -z \"$OC_VER\" ]; then OC_VER=desconocida; fi\n"
                + "case \"${1:-}\" in\n"
                + "  --version|-v|version)\n"
                + "    echo \"opencode $OC_VER\"\n"
                + "    exit 0 ;;\n"
                + "esac\n"
                + "OC_BIN=\"$OC_PREFIX/runtime/$OC_VER/opencode\"\n"
                + "if [ ! -f \"$OC_BIN\" ]; then\n"
                + "  echo \"opencode $OC_VER (binary not found)\" >&2\n"
                + "  exit 127\n"
                + "fi\n"
                + "exec /system/bin/linker64 \"$OC_BIN\" \"$@\"\n";
        writeScript(opencode, opencodeBody);

        File root = new File(bin, "opencode-root");
        String rootBody = "#!/system/bin/sh\n"
                + nativeCurlPrelude(ctx, pref)
                + "# Ejecuta un comando como root (la app pide el permiso de su).\n"
                + "PORT=${OPENCODE_TOOLBOX_PORT:-" + ToolboxServer.PORT + "}\n"
                + "TOK=$OPENCODE_TOOLBOX_TOKEN\n"
                + "oc_curl -s -X POST \\\n"
                + "  -H \"X-OpenCode-Token: $TOK\" --data-urlencode \"cmd=$*\" \\\n"
                + "  \"http://127.0.0.1:$PORT/root\"\n";
        writeScript(root, rootBody);

        // El su del rootfs (util-linux) no puede elevar dentro de proot y
        // confunde al agente. Lo sustituimos por un envoltorio del su real.
        File su = new File(bin, "su");
        try {
            java.nio.file.Files.deleteIfExists(su.toPath());
        } catch (Exception ignored) {
        }
        String suBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "# su del sandbox: usa el su real de la app (Magisk).\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -c) shift ;;\n"
                + "    -s|-t|-g|-G|-Z) shift; [ $# -gt 0 ] && shift ;;\n"
                + "    -*) shift ;;\n"
                + "    0|root) shift ;;\n"
                + "    *) break ;;\n"
                + "  esac\n"
                + "done\n"
                + "CMD=\"\"\n"
                + "for a in \"$@\"; do CMD=\"$CMD$a \"; done\n"
                + "exec /system/bin/sh \"$OC_PREFIX/bin/opencode-root\" $CMD\n";
        writeScript(su, suBody);

        // Binarios de Android que cargan librerias del sistema: el
        // LD_LIBRARY_PATH del prefijo los rompe (libunwindstack/libzstd).
        // Se envuelven para limpiar el entorno; no se pisan los que ya
        // existen en el prefijo (settings, pm, getprop, ...).
        String[] sysCmds = {
                "appops", "content", "dumpsys", "input", "monkey", "screencap",
                "svc", "uiautomator", "wm", "ip", "ss", "ifconfig", "netstat",
                "mount"
        };
        for (String c : sysCmds) {
            File f = new File(bin, c);
            if (f.exists()) continue;
            if (!new File("/system/bin", c).exists()) continue;
            String body = "#!/system/bin/sh\n"
                    + "unset LD_LIBRARY_PATH LD_PRELOAD\n"
                    + "PATH=/system/bin exec /system/bin/" + c + " \"$@\"\n";
            writeScript(f, body);
        }

        // sudo no existe en Android; se manda al mismo su de la app.
        // Se reescribe siempre: los envoltorios viejos tenian un shebang de
        // Termux que no existe en el shell nativo.
        File sudo = new File(bin, "sudo");
        String sudoBody = "#!/system/bin/sh\n"
                + "OC_PREFIX=${PREFIX:-'" + pref.getAbsolutePath() + "'}\n"
                + "# sudo del sandbox: usa el su real de la app (Magisk).\n"
                + "while [ $# -gt 0 ]; do\n"
                + "  case \"$1\" in\n"
                + "    -E|-H|-n|-S) shift ;;\n"
                + "    -u) shift; [ $# -gt 0 ] && shift ;;\n"
                + "    -*) shift ;;\n"
                + "    *) break ;;\n"
                + "  esac\n"
                + "done\n"
                + "CMD=\"\"\n"
                + "for a in \"$@\"; do CMD=\"$CMD$a \"; done\n"
                + "exec /system/bin/sh \"$OC_PREFIX/bin/opencode-root\" $CMD\n";
        writeScript(sudo, sudoBody);

        writeAgentNotes(ctx);
    }

    /** Notas para el agente: root, API del telefono y bateria. */
    private static void writeAgentNotes(Context ctx) {
        boolean lab = "com.opencode.launcher.v2lab".equals(ctx.getPackageName());
        String rootNotes = lab
                ? "## Root\nRoot is intentionally denied for this lab app. Do not use `su`,\n"
                + "`opencode-root` or `sudo`: they return\n"
                + "`{\"ok\":false,\"error\":\"sin permiso de su\"}`. Use non-root tools instead.\n"
                + "\n"
                : "## Root\nThe phone has root through Magisk. Use:\n"
                + "\n"
                + "    opencode-root <command>\n"
                + "\n"
                + "`su` is a wrapper for the same thing, so `su -c \"<command>\"` also\n"
                + "works. Magisk 31.1+ no longer shows a prompt: the user grants root\n"
                + "manually from Options > Root (su) > Grant, which opens Magisk >\n"
                + "Superuser; there they tap + and pick this app. On Magisk 31.0 and\n"
                + "older the prompt still appears; if denied, enable this app in\n"
                + "Superuser. If a command returns\n"
                + "`{\"ok\":false,\"error\":\"sin permiso de su\"}`, tell the user that\n"
                + "path, or to enable Root (su) in Options.\n"
                + "\n"
                + "`sudo <command>` also works and uses the same root.\n"
                + "\n"
                + "The `su`, `sudo`, `opencode-root` and `termux-*` scripts are generated by\n"
                + "the app. If one fails with `No such file or directory`, it is a stale\n"
                + "wrapper; tell the user instead of assuming root is unavailable.\n"
                + "\n";
        String androidNotes = lab
                ? "Permission denied or Access denied, do not retry with\n"
                + "`opencode-root` in this lab; use a non-root alternative.\n"
                : "Permission denied or Accessed denied, retry with opencode-root.\n";
        String batteryNotes = lab
                ? "Cycle count needs root, which is denied in this lab. `termux-battery-status`\n"
                + "works without root and reports the battery percentage, status, temperature,\n"
                + "voltage, technology, health and plugged state.\n"
                : "Cycle count (when the kernel exposes it) needs root:\n"
                + "\n"
                + "    opencode-root cat /sys/class/power_supply/battery/cycle_count\n"
                + "\n"
                + "termux-battery-status includes `cycle_count` when root is granted.\n";
        String body = "# This phone (OpenCode on Android)\n"
                + "\n"
                + "You run inside a Termux-like sandbox owned by the OpenCode app.\n"
                + "\n"
                + "## What you can do on this phone\n"
                + "These commands talk to the app, which does it with your\n"
                + "permissions. Use them, they just work:\n"
                + "\n"
                + "    termux-open-url <url>       open a link (browser)\n"
                + "    termux-open-app <package>   open an installed app\n"
                + "    termux-open-file <path>     open a file with any viewer\n"
                + "    termux-list-apps            list the installed apps\n"
                + "    termux-install-apk <path>       install an APK\n"
                + "    termux-install-url <url>        download + verify + install in one step\n"
                + "    termux-install-status           state of the last install\n"
                + "    termux-install-status           state of the last install\n"
                + "\n"
                + "The app name is enough: termux-open-app brave opens\n"
                + "com.brave.browser. `am start -n <pkg/activity>` and\n"
                + "`monkey -p <pkg>` also work (they go through the app).\n"
                + "\n"
                + "If termux-open-app says \"not launchable\" but the app IS\n"
                + "installed under another name (Fennec is Firefox, for\n"
                + "example), teach the app the name once:\n"
                + "\n"
                + "    termux-alias firefox org.mozilla.fennec_fdroid\n"
                + "\n"
                + "From then on that name works. termux-list-apps shows every\n"
                + "installed app with its name and package.\n"
                + "\n"
                + "Do not waste the turn trying these, they cannot work here:\n"
                + "\n"
                + "- `pm install`: the system server cannot read the app's files\n"
                + "  and the streaming mode is reserved for shell/root.\n"
                + "- `su`/`sudo`: only work if the user granted Magisk root to\n"
                + "  this app (see Root below).\n"
                + "- `adb`: there is no adb on this phone.\n"
                + "- `am start`: the system's `am` identifies as com.android.shell\n"
                + "  and Android rejects the UID. The app wraps it, so\n"
                + "  `am start -d <url>` and `am start -n <pkg/activity>` do work.\n"
                + "\n"
                + "When something like that fails, use the command above instead\n"
                + "of looking for another way round it.\n"
                + "\n"
                + "## Running compiled tools (aapt2, java, javac, d8, apksigner, clang, etc.)\n"
                + "\n"
                + "Compiled tools from pkg/apt (aapt2, java, javac, d8, apksigner,\n"
                + "clang, rustc, go, ffmpeg, ...) work directly in the shell. The app's\n"
                + "shell runs them natively; there is no need for oc-proot unless a\n"
                + "command fails with Permission denied or inaccessible or not found.\n"
                + "In that case, retry with oc-proot '<command>' as a fallback.\n"
                + "\n"
                + "Do NOT use oc-proot by default: it adds overhead and is only needed\n"
                + "for package management (pkg, apt, dpkg) or when a tool fails natively.\n"
                + "\n"
                + "## Building an Android app (APK) in this environment\n"
                + "\n"
                + "There is no Android Studio/Gradle SDK here, but you can build a\n"
                + "minimal APK with the tools of the prefix. The platform stubs are\n"
                + "at $PREFIX/share/android.jar. If java/aapt2/d8/apksigner are\n"
                + "missing, install them first:\n"
                + "\n"
                + "    pkg install openjdk-21 aapt2 d8 apksigner\n"
                + "\n"
                + "Then, for a minimal app (Java, no AndroidX):\n"
                + "\n"
                + "    mkdir -p app/src/main/java/com/example/holamundo\n"
                + "    mkdir -p app/src/main/res/layout app/src/main/res/values\n"
                + "    # ... write AndroidManifest.xml, MainActivity.java, layout, strings ...\n"
                + "    javac -cp \"$PREFIX/share/android.jar\" -d out/classes \\\n"
                + "      app/src/main/java/com/example/holamundo/MainActivity.java\n"
                + "    d8 --lib \"$PREFIX/share/android.jar\" --min-api 26 \\\n"
                + "      --output out/dex $(find out/classes -name '*.class')\n"
                + "    aapt2 link -I \"$PREFIX/share/android.jar\" -o out/base.apk \\\n"
                + "      --manifest app/src/main/AndroidManifest.xml \\\n"
                + "      --min-sdk-version 26 --target-sdk-version 34\n"
                + "    python3 -c 'import zipfile; z=zipfile.ZipFile(\"out/base.apk\",\"a\"); \\\n"
                + "      z.write(\"out/dex/classes.dex\",\"classes.dex\"); z.close()'\n"
                + "    keytool -genkeypair -keystore out/debug.keystore -storepass android \\\n"
                + "      -keypass android -alias androiddebugkey \\\n"
                + "      -dname \"CN=Android Debug,O=Android,C=US\" -keyalg RSA \\\n"
                + "      -keysize 2048 -validity 10000\n"
                + "    apksigner sign --ks out/debug.keystore --ks-pass pass:android \\\n"
                + "      --key-pass pass:android --out out/app.apk out/base.apk\n"
                + "    apksigner verify --verbose out/app.apk\n"
                + "\n"
                + "Keep the manifest minimal and avoid AndroidX/AppCompat (no\n"
                + "network jars for them). Use android.app.Activity and plain views.\n"
                + "Install the resulting APK with termux-install-apk <path>.\n"
                + "\n"
                + "## Installing additional packages (pkg/apt)\n"
                + "\n"
                + "Run pkg install (or apt install) normally:\n"
                + "\n"
                + "    pkg install openjdk-21 aapt2 d8 apksigner\n"
                + "\n"
                + "The app handles it with its verified installer: resolves\n"
                + "dependencies, checks SHA-256 and registers the packages with dpkg.\n"
                + "The command waits until it finishes and returns a real exit code,\n"
                + "so check it before using the new tool. It prints the current step.\n"
                + "\n"
                + "Do NOT use apt/dpkg directly, do not set APT_CONFIG or DPKG_ROOT,\n"
                + "and do not extract .deb files by hand: those binaries have the\n"
                + "Termux prefix compiled in and fail outside proot.\n"
                + "\n"
                + "Run pkg install as its own command, not inside a pipeline or\n"
                + "after &&; the wrapper only sees a command that starts with it.\n"
                + "\n"
                + "pkg install can take several minutes (JDK, aapt2...). Pass\n"
                + "timeout 1800000 (30 min) to the shell tool; the default 120 s\n"
                + "kills it. If it times out, rerun the same command: downloads\n"
                + "are reused and already-installed packages are skipped.\n"
                + "\n"
                + "New commands (java, aapt2, ...) are usable as soon as pkg install\n"
                + "returns: the shell environment is regenerated automatically.\n"
                + "\n"
                + "## Environment facts\n"
                + "\n"
                + "- Check the Android version with `getprop ro.build.version.release` (and\n"
                + "  `getprop ro.build.version.sdk`). Do not infer it from `uname -a`: the\n"
                + "  kernel string may say `android12` even on a newer Android release.\n"
                + "- There is no readable `/etc/resolv.conf`. HTTP(S) from `curl`/`wget` goes\n"
                + "  through the app's local proxy (`http_proxy`, `https_proxy`, `all_proxy`\n"
                + "  point at 127.0.0.1). `curl` reporting `remote_ip=127.0.0.1` and\n"
                + "  sub-millisecond DNS is normal: the proxy resolves DNS through Android.\n"
                + "- The proxy supports both CONNECT (HTTPS) and plain HTTP. If one host times\n"
                + "  out, try another host before concluding that the proxy blocks ports or\n"
                + "  requires CONNECT. `curl --noproxy '*'` also works.\n"
                + "- ICMP (`ping <ip>`) goes direct, not through the proxy.\n"
                + "- `ip addr`, `ip route show table all`, `ss` and `/proc/net/tcp` may fail\n"
                + "  with netlink/permission errors in the native shell. They work inside\n"
                + "  the proot sandbox (commands such as `su`, `pkg` and `termux-*` start\n"
                + "  there). Do not conclude the network is down from a netlink error.\n"
                + "\n"
                + rootNotes
                + "## Android commands\n"
                + "settings, getprop, logcat, dumpsys, input, wm, svc,\n"
                + "screencap, uiautomator, appops, ip, ss and mount run the real\n"
                + "Android tools (the sandbox wraps them so its libraries do not\n"
                + "break them). `am` is wrapped as well: use it to open apps and\n"
                + "links. `pm` only serves to list packages; to install an APK use\n"
                + "termux-install-apk. Many still need root: if you see SecurityException,\n"
                + androidNotes
                + "\n"
                + "/sdcard and /storage are readable and writable.\n"
                + "\n"
                + "Not available on purpose: SMS, contacts, calls, camera,\n"
                + "microphone, location, notifications, TTS and fingerprint.\n"
                + "\n"
                + "## Phone API\n"
                + "termux-battery-status, termux-clipboard-get, termux-clipboard-set,\n"
                + "termux-toast, termux-vibrate, termux-brightness, termux-volume,\n"
                + "termux-wifi-connectioninfo, termux-sensor and termux-sensor-list talk\n"
                + "to the phone app.\n"
                + "\n"
                + "## Opening apps\n"
                + "You can open apps and links on the phone:\n"
                + "\n"
                + "    termux-open-url <url>          open a link (browser)\n"
                + "    termux-open-app <package>      open an installed app\n"
                + "    termux-open-file <path>        open a file with any viewer\n"
                + "    termux-list-apps               list the installed apps\n"
                + "\n"
                + "`am start -d <url>` and `am start -n <package/activity>` work too:\n"
                + "the app replaces the system `am`, whose real one always fails here\n"
                + "(it identifies as com.android.shell and Android rejects the UID).\n"
                + "Do not report that opening apps is impossible.\n"
                + "\n"
                + "Package ids are exact: run termux-list-apps to see them (for\n"
                + "example com.brave.browser, not \"brave\"). A package that is not\n"
                + "listed has no launchable activity.\n"
                + "\n"
                + "If the app is not on screen the request becomes a notification:\n"
                + "the user taps it and the app opens.\n"
                + "\n"
                + "## Installing an APK\n"
                + "    termux-install-apk <path> [sha256] [open]\n"
                + "\n"
                + "It installs the APK with the system's installer; the user has to\n"
                + "press \"Install\" in the dialog. With a sha256 as second argument\n"
                + "the file is checked first and the install is aborted if it does\n"
                + "not match. With \"open\" as third argument the app is opened\n"
                + "right after it is installed.\n"
                + "\n"
                + "If this app has root granted (Magisk), the install is silent:\n"
                + "no dialog, the answer is {\"status\":\"installed\"}. The answer\n"
                + "says which mode was used (\"mode\":\"root\" or \"mode\":\"dialog\").\n"
                + "\n"
                + "If you already have the URL and (optionally) the sha256, use\n"
                + "termux-install-url <url> [sha256] [open] to do it in one step\n"
                + "(download + verify + install), instead of chaining commands.\n"
                + "\n"
                + "Afterwards, termux-install-status tells you how it ended:\n"
                + "installed, failed (with the reason) or pending (the user has\n"
                + "not confirmed yet).\n"
                + "\n"
                + "`pm install` does not work from here (the system server cannot\n"
                + "read the app's private files and the reverse mode is reserved for\n"
                + "shell/root). Use termux-install-apk instead.\n"
                + "\n"
                + "If the answer is \"need_unknown_sources\", the user has to grant\n"
                + "\"Install unknown apps\" to this app in Settings > Apps > Special\n"
                + "access > Install unknown apps. The app opens that screen itself.\n"
                + "\n"
                + "## Battery\n"
                + batteryNotes;
        // OpenCode carga las instrucciones globales desde OPENCODE_CONFIG_DIR
        // (home/.config/opencode-android) y las de proyecto desde la raiz del
        // workspace (home/). Se escriben en todos esos sitios para que el
        // agente las encuentre aunque cambie la ruta de configuracion.
        String[] noteDirs = new String[] {
            "home/.config/opencode",
            "home/.config/opencode-android",
            "home"
        };
        for (String d : noteDirs) {
            File dir = new File(ctx.getFilesDir(), d);
            if (!dir.isDirectory() && !dir.mkdirs()) continue;
            File f = new File(dir, "AGENTS.md");
            try (java.io.FileWriter w = new java.io.FileWriter(f, false)) {
                w.write(body);
            } catch (IOException ignored) {
            }
            f.setReadable(true, false);
        }
    }

    /** Extrae android.jar (stubs de la plataforma) a $PREFIX/share/android.jar
     *  para que el agente pueda compilar apps sin Android SDK. */
    private static void ensurePlatformJar(Context ctx, File pref) {
        File target = new File(pref, "share/android.jar");
        if (target.isFile() && target.length() > 1024 * 1024) return;
        try {
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory()) parent.mkdirs();
            try (java.io.InputStream in = ctx.getAssets().open("platform/android.jar");
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Log.i(TAG, "android.jar instalado en " + target);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo instalar android.jar: " + e);
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
        String ocPkg = new File(bin, CommandResolver.PKG_NAME).getAbsolutePath();
        String body = "#!/system/bin/sh\n"
                + "# Instalador de paquetes de OpenCode (paquetes Termux).\n"
                + "# uso: pkg install git python ...\n"
                + "# Si oc-pkg existe, se delega en el: espera, muestra el paso y\n"
                + "# devuelve un codigo de salida real.\n"
                + "if [ -f " + q(ocPkg) + " ]; then\n"
                + "  exec /system/bin/sh " + q(ocPkg) + " \"pkg $*\"\n"
                + "fi\n"
                + nativeCurlPrelude(ctx, pref)
                + "VERB=$1; shift\n"
                + "PKGS=\"\"\n"
                + "for p in \"$@\"; do PKGS=\"$PKGS,$p\"; done\n"
                + "PORT=${OPENCODE_TOOLBOX_PORT:-" + ToolboxServer.PORT + "}\n"
                + "TOK=$OPENCODE_TOOLBOX_TOKEN\n"
                + "BODY=\"pkgs=$PKGS&token=$TOK\"\n"
                + "URL=\"http://127.0.0.1:$PORT/install\"\n"
                + "oc_curl -s -X POST --data \"$BODY\" \"$URL\"\n";
        try (java.io.FileWriter w = new java.io.FileWriter(shim, false)) {
            w.write(body);
        } catch (IOException ignored) {
        }
        shim.setExecutable(true, true);
    }

    // ------------------------------------------------------------ extraccion

    /** Desempaqueta un .deb (ar) y deja su contenido dentro del prefijo. */
    static void extractDeb(File deb, File pref) throws IOException {
        // Nombre unico: si un intento anterior dejo la carpeta a medias (o el
        // proceso murio durante la extraccion), no bloquea este intento.
        File tmp = new File(deb.getParentFile(),
                "x-" + deb.getName() + "-" + System.nanoTime());
        deleteTree(tmp);
        if (!tmp.mkdirs() && !tmp.isDirectory()) {
            throw new IOException("no tmp dir: " + tmp.getName());
        }
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
        // Los scripts del paquete apuntan a la ruta de Termux
        // (/data/data/com.termux/files/usr/bin/sh), que aqui no existe. Se
        // reescriben a la ruta real para que corran con el shell de Android
        // sin necesidad de proot.
        rewriteScriptHeaders(pref);
        fixTermuxSymlinks(pref);
        deleteTree(tmp);
        data.delete();
    }

    /**
     * Escribe la version del runtime en el prefijo, para que el comando
     * "opencode" pueda decirla.
     */
    private static void writeRuntimeVersion(Context ctx, File pref) {
        try {
            File lib = new File(pref, "lib");
            if (!lib.isDirectory() && !lib.mkdirs()) return;
            String v = LocalRuntime.activeVersion(ctx);
            if (v == null || v.isEmpty()) return;
            java.io.FileOutputStream out = new java.io.FileOutputStream(
                    new File(lib, "termux-version"));
            out.write(v.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.flush();
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "no se pudo escribir la version del runtime: " + e);
        }
    }

    /**
     * Instala el atajo de pip para las consultas de solo lectura.
     *
     *  pip arrastra toda la cadena de su interfaz (rich, logging,
     *  traceback) y tarda unos 750 ms en arrancar aunque solo quiera listar
     *  paquetes. Para esas consultas (list, show, freeze, --version) vale
     *  con importlib.metadata, que es de la libreria estandar y tarda una
     *  cuarta parte. El atajo devuelve 2 si la orden no es de solo lectura,
     *  y entonces la ejecuta el pip de verdad.
     */
    private static void installPipShortcut(Context ctx, File pref) {
        try {
            File site = new File(pref, "lib/python3.14/site-packages");
            if (!site.isDirectory()) {
                // El nombre de la version de python puede cambiar: se busca.
                File lib = new File(pref, "lib");
                File[] kids = lib.listFiles();
                if (kids != null) {
                    for (File k : kids) {
                        if (k.isDirectory() && k.getName().startsWith("python3")) {
                            site = new File(k, "site-packages");
                            break;
                        }
                    }
                }
            }
            if (!site.isDirectory()) return;
            try (java.io.InputStream in = ctx.getAssets().open("ocpip.py");
                 java.io.OutputStream out = new java.io.FileOutputStream(
                         new File(site, "ocpip.py"))) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "no se pudo instalar el atajo de pip: " + e);
        }
    }

    /**
     * Repunta los enlaces simbolicos que apuntan a la ruta de Termux.
     *
     *  Los .deb de Termux traen enlaces absolutos a su propio prefijo (por
     *  ejemplo el certificado de certifi, que apunta a
     *  /data/data/com.termux/files/usr/etc/tls/cert.pem). Esa ruta solo
     *  existe dentro del sandbox de proot: fuera, el enlace queda roto.
     *  Python lo detecta como inexistente y pip falla con un error de SSL
     *  al instalar cualquier paquete.
     *
     *  Se reapuntan al prefijo real de la app, que si existe. */
    private static void fixTermuxSymlinks(File pref) {
        String fake = SANDBOX_PREFIX;
        String real = pref.getAbsolutePath().replace("/data/user/0/", "/data/data/");
        int fixed = 0;
        try {
            fixed = fixSymlinksIn(pref, fake, real);
        } catch (Exception e) {
            Log.w(TAG, "no se pudieron arreglar los enlaces: " + e);
        }
        if (fixed > 0) {
            Log.i(TAG, "enlaces simbolicos reapuntados: " + fixed);
        }
    }

    /** Recorre el arbol y reapunta los enlaces que apuntan a la ruta falsa. */
    private static int fixSymlinksIn(File dir, String fake, String real) {
        int fixed = 0;
        File[] kids = dir.listFiles();
        if (kids == null) return 0;
        for (File k : kids) {
            try {
                if (java.nio.file.Files.isSymbolicLink(k.toPath())) {
                    String target = Os.readlink(k.getAbsolutePath());
                    if (target != null && target.startsWith(fake)) {
                        String nuevo = real + target.substring(fake.length());
                        Os.remove(k.getAbsolutePath());
                        Os.symlink(nuevo, k.getAbsolutePath());
                        fixed++;
                    }
                    continue;
                }
                if (k.isDirectory()) {
                    fixed += fixSymlinksIn(k, fake, real);
                }
            } catch (Exception ignored) {
            }
        }
        return fixed;
    }

    /**
     * Reescribe la primera linea de los scripts del prefijo para que usen el
     * shell real de la app en vez de la ruta de Termux.
     *
     * Los .deb de Termux llevan el interprete compilado dentro
     * (#!/data/data/com.termux/files/usr/bin/sh). Como los binarios son
     * bionic, en realidad solo hace falta /system/bin/sh: el shebang se
     * cambia y asi los scripts pueden ejecutarse de forma nativa, sin el
     * sandbox de proot.
     *
     * Solo se tocan ficheros de texto que empiezan por "#!" y cuya primera
     * linea mencione la ruta de Termux: el resto no se toca.
     */
    /** Arreglo completo del prefijo para ejecucion nativa (sin proot).
     *
     *  Se ejecuta en cada arranque del servidor: reescribe shebangs, reapunta
     *  enlaces simbolicos, sustituye rutas de Termux por las del prefijo real
     *  en el cuerpo de los scripts y crea envoltorios para la JVM (java/javac),
     *  que de otro modo quedan inaccesibles por los enlaces rotos de
     *  update-alternatives. */
    public static void fixupPrefix(File pref) {
        if (pref == null || !pref.isDirectory()) return;
        try { rewriteScriptHeaders(pref); } catch (Exception e) { Log.w(TAG, "rewriteScriptHeaders: " + e); }
        try { fixTermuxSymlinks(pref); } catch (Exception e) { Log.w(TAG, "fixTermuxSymlinks: " + e); }
        try { fixScriptPaths(pref); } catch (Exception e) { Log.w(TAG, "fixScriptPaths: " + e); }
        try { ensureJavaWrappers(pref); } catch (Exception e) { Log.w(TAG, "ensureJavaWrappers: " + e); }
    }

    /** Sustituye /data/data/com.termux/files/usr por el prefijo real dentro del
     *  cuerpo de los scripts, para que las rutas embebidas (jars, configs...)
     *  apunten al prefijo que si existe en disco. */
    private static void fixScriptPaths(File pref) {
        File bin = new File(pref, "bin");
        if (!bin.isDirectory()) return;
        String realPrefix = pref.getAbsolutePath().replace("/data/user/0/", "/data/data/");
        String fakePrefix = SANDBOX_PREFIX;
        File[] files = bin.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isFile() || f.length() > 1024 * 1024) continue;
            try {
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                if (!text.contains(fakePrefix)) continue;
                // Solo se reescribe si es texto (sin bytes nulos).
                if (text.indexOf('\u0000') >= 0) continue;
                String replaced = text.replace(fakePrefix, realPrefix);
                if (replaced.equals(text)) continue;
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(f)) {
                    out.write(replaced.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** Crea envoltorios java/javac que invocan la JVM real con el linker del
     *  sistema, evitando los enlaces rotos de update-alternatives. */
    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /** Envoltorios para TODO el toolchain del JDK (java, javac, jar,
     *  jarsigner, keytool, d8...), no solo java/javac. update-alternatives
     *  deja enlaces rotos a la ruta de Termux y, ademas, Android bloquea
     *  execve en files/: sin envoltorio, el comando no existe. */
    private static void ensureJavaWrappers(File pref) {
        File jvmBase = new File(pref, "lib/jvm");
        if (!jvmBase.isDirectory()) return;
        File[] jvms = jvmBase.listFiles();
        if (jvms == null) return;
        File jvmDir = null;
        for (File j : jvms) {
            if (j.isDirectory() && j.getName().startsWith("java-")) {
                if (jvmDir == null || j.getName().compareTo(jvmDir.getName()) > 0) {
                    jvmDir = j;
                }
            }
        }
        if (jvmDir == null) return;
        File realBin = new File(jvmDir, "bin");
        if (!new File(realBin, "java").isFile()) return;
        String libPath = new File(pref, "lib").getAbsolutePath();
        File[] tools = realBin.listFiles();
        if (tools == null) return;
        for (File tool : tools) {
            String name = tool.getName();
            if (!tool.isFile() || !isElf(tool)) continue;
            if (!name.matches("[A-Za-z0-9_.+-]+")) continue;
            String target;
            try {
                target = tool.getCanonicalPath().replace("/data/user/0/", "/data/data/");
            } catch (Exception e) {
                target = tool.getAbsolutePath().replace("/data/user/0/", "/data/data/");
            }
            File w = new File(new File(pref, "bin"), name);
            // Borra enlace roto o fichero anterior.
            try { w.delete(); } catch (Exception ignored) { }
            String body = "#!/system/bin/sh\n"
                    + "LD_LIBRARY_PATH=" + q(libPath) + " /system/bin/linker64 "
                    + q(target) + " \"$@\"\n";
            try {
                java.io.FileWriter fw = new java.io.FileWriter(w, false);
                fw.write(body);
                fw.close();
                w.setExecutable(true, true);
                w.setReadable(true, true);
            } catch (Exception e) {
                Log.w(TAG, "no se pudo crear wrapper " + name + ": " + e);
            }
        }
    }

    /** ¿Es un binario ELF? Los scripts se arreglan con rewriteScriptHeaders. */
    private static boolean isElf(File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[4];
            if (in.read(b) != 4) return false;
            return b[0] == 0x7f && b[1] == 'E' && b[2] == 'L' && b[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static void rewriteScriptHeaders(File pref) {
        File bin = new File(pref, "bin");
        if (!bin.isDirectory()) return;
        // En Android /data/user/0/<app> es un enlace a /data/data/<app>: el
        // mismo sitio. Se usa la forma /data/data, que es la que emplea el
        // resto de la app, para que las rutas que imprime el prefijo (por
        // ejemplo en la ayuda de pip) sean las de siempre.
        String realPrefix = pref.getAbsolutePath().replace("/data/user/0/", "/data/data/");
        File[] files = bin.listFiles();
        if (files == null) return;
        int changed = 0;
        for (File f : files) {
            if (!f.isFile()) continue;
            try {
                long len = f.length();
                if (len < 4 || len > 256 * 1024) continue;
                // Se lee el fichero entero: asi el resto nunca se calcula mal
                // (antes, con menos de 512 bytes, se perdia todo salvo el
                // shebang: d8, apksigner, top... quedaban vacios).
                byte[] data = java.nio.file.Files.readAllBytes(f.toPath());
                String text = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                int nl = text.indexOf('\n');
                String first = nl >= 0 ? text.substring(0, nl) : text;
                if (!first.startsWith("#!")) continue;
                String newFirst = first;
                if (first.contains("com.termux") || first.contains("/data/user/0/")) {
                    // Los scripts de shell usan el shell de Android (mksh). Los
                    // de bash se dejan con la ruta de Termux: se ejecutan dentro
                    // de proot, donde si hay bash y el prefijo esta montado.
                    //
                    // Los de python y perl apuntan al prefijo real de la app,
                    // que si existe en disco y se puede ejecutar con el linker.
                    boolean bash = newFirst.contains("/bin/bash")
                            || newFirst.contains("/bin/ba ");
                    if (bash) continue;
                    if (newFirst.contains("/bin/sh")) {
                        newFirst = newFirst.replaceAll("^#!\\s*/\\S*/bin/sh",
                                "#!/system/bin/sh");
                    }
                    newFirst = newFirst.replace(SANDBOX_PREFIX, realPrefix);
                    // /data/user/0/<app> y /data/data/<app> son el mismo sitio:
                    // se deja la forma que usa el resto de la app.
                    newFirst = newFirst.replace("/data/user/0/", "/data/data/");
                    if (newFirst.contains("com.termux")) {
                        // No se ha podido reescribir: se deja como estaba.
                        continue;
                    }
                }
                String body = nl >= 0 ? text.substring(nl + 1) : "";
                String out = newFirst + "\n" + body;
                if ("#!/system/bin/sh".equals(newFirst)) {
                    // d8, apksigner, r8... hacen "exec java". Un execve del
                    // wrapper del prefijo esta prohibido (SELinux/W^X), asi
                    // que se pasa por /system/bin/sh, igual que oc-shell.
                    out = out.replace("exec java ",
                            "exec /system/bin/sh " + realPrefix + "/bin/java ");
                }
                byte[] nb = out.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                if (java.util.Arrays.equals(nb, data)) continue;
                try (java.io.FileOutputStream os = new java.io.FileOutputStream(f)) {
                    os.write(nb);
                }
                f.setExecutable(true, true);
                changed++;
            } catch (Exception ignored) {
            }
        }
        if (changed > 0) {
            Log.i(TAG, "scripts del prefijo reescritos: " + changed);
        }
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
        if (f == null) return;
        // Primero los enlaces: un enlace roto "no existe" para File.exists(),
        // y si se ignora deja la carpeta padre sin poder borrarse.
        if (isSymlink(f)) {
            if (!f.delete()) Log.w(TAG, "no se pudo borrar " + f.getAbsolutePath());
            return;
        }
        if (!f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        if (!f.delete() && f.exists()) {
            Log.w(TAG, "no se pudo borrar " + f.getAbsolutePath());
        }
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
