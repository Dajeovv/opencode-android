package com.opencode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class LocalRuntime {
    private static final String TAG = "OpenCodeLocal";
    private static final Object LOCK = new Object();
    private static final String PREF = "app";
    private static final String PREF_NATIVE = "native_runtime_enabled";
    private static final String PREF_PROJECTS = "projects";
    private static final String PREF_PROJECT_DIRS = "dirs";
    /** Carpeta visible en el almacenamiento compartido para los proyectos. */
    public static final String PROJECTS_DIR_NAME = "OpenCode.android-projects";
    private static final int MAX_REMEMBERED_PROJECTS = 8;
    private static final long NATIVE_HEALTH_TIMEOUT_MS = 45_000;
    private static final Object GIT_LOCK = new Object();

    private static Process process;
    private static String activeBinPath;

    private LocalRuntime() {
    }

    /**
     * Directorio HOME del runtime. Se resuelve a su forma canonica
     * (/data/data/...) para que coincida con las rutas que devuelve el
     * servidor; /data/user/0 y /data/data son el mismo sitio, pero la UI
     * compara cadenas y descartaria las sesiones si no coinciden.
     */
    public static File homeDir(Context ctx) {
        File home = new File(ctx.getFilesDir(), "home");
        try {
            return home.getCanonicalFile();
        } catch (IOException e) {
            return home;
        }
    }

    public static String activeVersion(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String version = sp.getString("opencode_runtime_version", "");
            String path = sp.getString("opencode_runtime_path", "");
            if (version != null && !version.isEmpty() && path != null
                    && new File(path).isFile()
                    && UpdateCheck.cmpVer(version, BuildConfig.OPENCODE_VERSION) > 0) {
                return version;
            }
        } catch (Exception ignored) {
        }
        return BuildConfig.OPENCODE_VERSION;
    }

    public static File runtimeBinary(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences(PREF, Context.MODE_PRIVATE);
            String version = sp.getString("opencode_runtime_version", "");
            String path = sp.getString("opencode_runtime_path", "");
            if (version != null && !version.isEmpty() && path != null
                    && UpdateCheck.cmpVer(version, BuildConfig.OPENCODE_VERSION) > 0) {
                File f = new File(path);
                if (f.isFile() && f.length() > 10L * 1024 * 1024) return f;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Binarios necesarios para ejecutar el servidor musl sin proot. */
    public static boolean nativeAvailable(Context ctx) {
        File nd = new File(ctx.getApplicationInfo().nativeLibraryDir);
        return new File(nd, "libopencode2_bin.so").isFile()
                && new File(nd, "libld_musl.so").isFile()
                && new File(nd, "libstdcxx.so").isFile()
                && new File(nd, "libgcc_s.so").isFile();
    }

    /** Binarios necesarios para el modo proot clasico. */
    public static boolean prootAvailable(Context ctx) {
        File nd = new File(ctx.getApplicationInfo().nativeLibraryDir);
        return new File(nd, "libopencode2_bin.so").isFile()
                && new File(nd, "libld_musl.so").isFile()
                && new File(nd, "libstdcxx.so").isFile()
                && new File(nd, "libgcc_s.so").isFile()
                && new File(nd, "libproot.so").isFile()
                && new File(nd, "libproot_loader.so").isFile()
                && new File(nd, "libtalloc.so").isFile()
                && new File(nd, "libandroid-shmem.so").isFile();
    }

    public static boolean available(Context ctx) {
        return nativeAvailable(ctx) || prootAvailable(ctx);
    }

    /** Interruptor de seguridad: true usa el servidor nativo; si falla, proot. */
    public static boolean nativeEnabled(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getBoolean(PREF_NATIVE, true);
    }

    public static void setNativeEnabled(Context ctx, boolean enabled) {
        ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_NATIVE, enabled).apply();
    }

    /** Contraseña Basic estable por instalación para el servidor local. */
    public static synchronized String serverPassword(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String p = sp.getString("local_server_password", null);
        if (p == null || p.length() < 24) {
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            p = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
            sp.edit().putString("local_server_password", p).apply();
        }
        return p;
    }

    public static boolean running() {
        synchronized (LOCK) {
            return process != null && process.isAlive();
        }
    }

    public static boolean start(Context ctx, int port) throws IOException {
        synchronized (LOCK) {
            if (process != null && process.isAlive()) {
                return true;
            }
            Context app = ctx.getApplicationContext();
            if (nativeEnabled(app) && nativeAvailable(app)) {
                try {
                    if (startNative(app, port)) {
                        return true;
                    }
                    Log.w(TAG, "el runtime nativo no quedo sano; se usa proot");
                } catch (Exception e) {
                    Log.w(TAG, "el runtime nativo fallo; se usa proot: " + e, e);
                }
                // Limpia el proceso nativo a medias y su proxy antes de proot.
                stopCurrent();
            }
            if (!prootAvailable(app)) {
                throw new IOException("faltan binarios del runtime local");
            }
            boolean ok = startProot(app, port);
            if (ok) Log.i(TAG, "runtime proot iniciado en 127.0.0.1:" + port);
            return ok;
        }
    }

    /**
     * Servidor musl ejecutado directamente sobre Android, con un proxy CONNECT
     * local para que resuelva DNS mediante netd. Sin proot y sin root.
     */
    private static boolean startNative(Context app, int port) throws IOException {
        File nd = new File(app.getApplicationInfo().nativeLibraryDir);
        File musl = new File(nd, "libld_musl.so");
        File bin = runtimeBinary(app);
        if (bin == null) bin = new File(nd, "libopencode2_bin.so");
        if (!musl.isFile() || !bin.isFile()) {
            Log.w(TAG, "faltan libld_musl.so o el binario del servidor");
            return false;
        }
        if (!bin.canExecute()) {
            // El updater ya lo marca; por si acaso, un binario bajado a files/.
            //noinspection ResultOfMethodCallIgnored
            bin.setExecutable(true, true);
        }

        File home = homeDir(app);
        File config = new File(home, ".config/opencode");
        File tmp = new File(app.getCacheDir(), "tmp");
        File lib = new File(home, "lib");
        File etc = new File(home, ".oc-etc");
        mkdirs(home);
        mkdirs(config);
        mkdirs(tmp);
        mkdirs(lib);
        mkdirs(etc);
        writeConfig(config);
        writeGitConfig(home);
        installTools(app, config);
        prepareNetworkOverrides(home, etc);
        copyIfNeeded(new File(nd, "libstdcxx.so"), new File(lib, "libstdc++.so.6"));
        copyIfNeeded(new File(nd, "libgcc_s.so"), new File(lib, "libgcc_s.so.1"));

        int proxyPort = LocalNetworkProxy.start();
        // OPENCODE_CONFIG/OPENCODE_CONFIG_CONTENT se ignoran en "serve"
        // 1.19.x; OPENCODE_CONFIG_DIR si se mezcla (y al final, sin pisar
        // claves nuestras si el usuario ya tiene las suyas).
        File nativeConfigDir = new File(home, ".config/opencode-android");
        mkdirs(nativeConfigDir);
        File binDir = LocalToolbox.installed(app)
                ? new File(LocalToolbox.prefix(app), "bin")
                : new File(home, "bin");
        File wrapper = CommandResolver.install(home, nd, tmp, etc, binDir);
        // Android no deja hacer execve de scripts en files/ (untrusted_app).
        // El unico sitio ejecutable es nativeLibraryDir, donde va este
        // trampolin; desde ahi se lanza el envoltorio con /system/bin/sh.
        File trampoline = new File(nd, "libocshell.so");
        File agentShell = trampoline.isFile() ? trampoline : new File("/system/bin/sh");
        if (!trampoline.isFile()) {
            Log.w(TAG, "falta libocshell.so en nativeLibraryDir; se usa /system/bin/sh");
        }
        NativeRuntimeConfig.write(nativeConfigDir, config, agentShell.getAbsolutePath());
        Map<String, String> env = buildNativeEnv(app, home, tmp, nd, proxyPort);
        env.put("OPENCODE_CONFIG_DIR", nativeConfigDir.getAbsolutePath());
        // Las funciones que lanzan los ELF del prefijo con linker64 tambien
        // valen para bash/sh no interactivos que arranque el agente.
        File nativeEnv = new File(binDir, CommandResolver.NATIVE_ENV_NAME);
        env.put("BASH_ENV", nativeEnv.getAbsolutePath());
        env.put("ENV", nativeEnv.getAbsolutePath());
        // El shell del agente pasa por el resolvedor: comandos de paquetes o
        // con rutas de Termux se ejecutan en proot; el resto, nativo.
        env.put("OPENCODE_WRAPPER", wrapper.getAbsolutePath());
        env.put("SHELL", agentShell.getAbsolutePath());

        // El repo debe existir antes de "serve": si no, upstream resuelve el
        // worktree como "/" y el watcher recorre todo el sistema de archivos.
        ensureNativeGit(home, nd);
        ensureNativeRg(home, nd);
        ensureProjectsDir(app, home);
        ensureGitRepo(home, nd, LocalToolbox.prefix(app), env);

        List<String> cmd = new ArrayList<>();
        cmd.add(musl.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(nativeLibraryPath(app, lib, nd));
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--hostname");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(Integer.toString(port));
        cmd.add("--print-logs");
        cmd.add("--log-level");
        cmd.add("info");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = spawn(pb, bin.getAbsolutePath());
        Log.i(TAG, "runtime nativo arrancado; proxy=" + proxyPort + " puerto=" + port);

        if (!waitForHealthy(port, p, NATIVE_HEALTH_TIMEOUT_MS, serverPassword(app))) {
            Log.w(TAG, "runtime nativo sin respuesta en /api/info");
            stopCurrent();
            return false;
        }
        Log.i(TAG, "runtime nativo listo en 127.0.0.1:" + port);
        if (!BuildConfig.V2) ensureProjectWorktree(port);
        // Precalienta los proyectos ya abiertos sin retrasar el arranque: el
        // primer request de cada uno tambien los prepara desde oc-early.js.
        if (!BuildConfig.V2) {
            Thread warm = new Thread(() -> ensureRememberedProjects(app), "project-warmup");
            warm.setDaemon(true);
            warm.start();
        }
        return true;
    }

    private static boolean startProot(Context app, int port) throws IOException {
        File nd = new File(app.getApplicationInfo().nativeLibraryDir);
        File home = homeDir(app);
        File config = new File(home, ".config/opencode");
        File tmp = new File(app.getCacheDir(), "tmp");
        File prootTmp = new File(tmp, "proot");
        File lib = new File(home, "lib");
        File etc = new File(home, ".oc-etc");
        mkdirs(home);
        mkdirs(config);
        mkdirs(tmp);
        mkdirs(prootTmp);
        mkdirs(lib);
        mkdirs(etc);
        writeConfig(config);
        writeGitConfig(home);
        installTools(app, config);
        prepareNetworkOverrides(home, etc);

        File bin = runtimeBinary(app);
        if (bin == null) bin = new File(nd, "libopencode2_bin.so");
        File musl = new File(nd, "libld_musl.so");
        File proot = new File(nd, "libproot.so");
        File loader = new File(nd, "libproot_loader.so");
        copyIfNeeded(new File(nd, "libstdcxx.so"), new File(lib, "libstdc++.so.6"));
        copyIfNeeded(new File(nd, "libgcc_s.so"), new File(lib, "libgcc_s.so.1"));

        Map<String, String> env = buildEnv(app, home, tmp, etc, lib, nd, loader, prootTmp);
        List<String> cmd = prootCmd(app, home, tmp, etc, musl, proot, env);
        cmd.add(bin.getAbsolutePath());
        cmd.add("serve");
        cmd.add("--hostname");
        cmd.add("127.0.0.1");
        cmd.add("--port");
        cmd.add(Integer.toString(port));
        cmd.add("--print-logs");
        cmd.add("--log-level");
        cmd.add("info");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        spawn(pb, bin.getAbsolutePath());
        return true;
    }

    /**
     * Ejecuta un comando dentro del mismo sandbox (proot) en el que vive el
     * agente, y devuelve su salida. Lo usa el servidor de herramientas para
     * comprobar el entorno y para el shim "pkg".
     */
    public static String runInSandbox(Context ctx, String command, int timeoutMs) {
        try {
            Context app = ctx.getApplicationContext();
            File nd = new File(app.getApplicationInfo().nativeLibraryDir);
            if (!prootAvailable(app)) return "no hay runtime proot";
            File home = homeDir(app);
            File tmp = new File(app.getCacheDir(), "tmp");
            File prootTmp = new File(tmp, "proot");
            File lib = new File(home, "lib");
            File etc = new File(home, ".oc-etc");
            mkdirs(home);
            mkdirs(tmp);
            mkdirs(prootTmp);
            mkdirs(lib);
            mkdirs(etc);
            prepareNetworkOverrides(home, etc);
            File musl = new File(nd, "libld_musl.so");
            File proot = new File(nd, "libproot.so");
            File loader = new File(nd, "libproot_loader.so");
            Map<String, String> env = buildEnv(app, home, tmp, etc, lib, nd, loader, prootTmp);
            List<String> cmd = prootCmd(app, home, tmp, etc, musl, proot, env);
            cmd.add("/system/bin/sh");
            cmd.add("-c");
            cmd.add(command);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(home);
            pb.redirectErrorStream(true);
            pb.environment().clear();
            pb.environment().putAll(env);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            Thread t = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null && out.length() < 20000) {
                        out.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "sandbox-cmd");
            t.setDaemon(true);
            t.start();
            boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!done) {
                p.destroyForcibly();
                out.append("[timeout]\n");
            }
            return out.toString();
        } catch (Exception e) {
            return "error: " + e;
        }
    }

    /**
     * Deja "git" resoluble por el servidor nativo: un enlace en home/.native-bin
     * apunta al trampolin libgit.so del directorio nativo. Android no permite
     * execve de nada dentro de files/, pero si del .so empaquetado en el APK.
     * El enlace se recrea en cada arranque porque nativeLibraryDir cambia con
     * las actualizaciones.
     */
    private static File ensureNativeGit(File home, File nd) {
        File tramp = new File(nd, "libgit.so");
        File dir = new File(home, ".native-bin");
        File link = new File(dir, "git");
        if (!tramp.isFile()) {
            Log.w(TAG, "falta libgit.so en nativeLibraryDir");
            return null;
        }
        try {
            mkdirs(dir);
            if (Files.isSymbolicLink(link.toPath()) || link.exists()) {
                //noinspection ResultOfMethodCallIgnored
                link.delete();
            }
            Os.symlink(tramp.getAbsolutePath(), link.getAbsolutePath());
            return link;
        } catch (Exception e) {
            Log.w(TAG, "no se pudo enlazar git nativo: " + e);
            return null;
        }
    }

    /**
     * Deja "rg" resoluble por el servidor nativo y por el agente: un enlace
     * en home/.native-bin apunta al trampolin librg_tramp.so del directorio
     * nativo. Tambien se sustituye el rg que el servidor v2 descarga en
     * ~/.cache/opencode/bin/rg: ese binario es glibc y Android no lo puede
     * ejecutar, por eso Glob/Grep fallaban con "ripgrep execution failed".
     */
    private static File ensureNativeRg(File home, File nd) {
        File tramp = new File(nd, "librg_tramp.so");
        File dir = new File(home, ".native-bin");
        File link = new File(dir, "rg");
        if (!tramp.isFile()) {
            Log.w(TAG, "falta librg_tramp.so en nativeLibraryDir");
            return null;
        }
        try {
            mkdirs(dir);
            if (Files.isSymbolicLink(link.toPath()) || link.exists()) {
                //noinspection ResultOfMethodCallIgnored
                link.delete();
            }
            Os.symlink(tramp.getAbsolutePath(), link.getAbsolutePath());

            File cacheDir = new File(home, ".cache/opencode/bin");
            mkdirs(cacheDir);
            File cache = new File(cacheDir, "rg");
            if (Files.isSymbolicLink(cache.toPath()) || cache.exists()) {
                //noinspection ResultOfMethodCallIgnored
                cache.delete();
            }
            Os.symlink(tramp.getAbsolutePath(), cache.getAbsolutePath());
            return link;
        } catch (Exception e) {
            Log.w(TAG, "no se pudo enlazar rg nativo: " + e);
            return null;
        }
    }

    /** Carpeta de proyectos visible en /sdcard. */
    public static File projectsDir(Context ctx) {
        return new File(Environment.getExternalStorageDirectory(), PROJECTS_DIR_NAME);
    }

    /**
     * Crea la carpeta de proyectos en el almacenamiento compartido y un enlace
     * en HOME para que aparezca en el selector de proyectos. El servidor v2 no
     * lista enlaces simbolicos en /api/fs/list, por eso MainActivity añade una
     * entrada sintetica para este nombre.
     */
    private static void ensureProjectsDir(Context app, File home) {
        File dir = projectsDir(app);
        try {
            if (!dir.isDirectory()) {
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
            }
            if (!dir.isDirectory()) {
                Log.w(TAG, "no se pudo crear la carpeta de proyectos: " + dir);
                return;
            }
            File link = new File(home, PROJECTS_DIR_NAME);
            if (Files.isSymbolicLink(link.toPath())) {
                String target = Os.readlink(link.getAbsolutePath());
                if (dir.getAbsolutePath().equals(target)) return;
                //noinspection ResultOfMethodCallIgnored
                link.delete();
            } else if (link.exists()) {
                // Un directorio real del usuario: no se toca.
                return;
            }
            Os.symlink(dir.getAbsolutePath(), link.getAbsolutePath());
            Log.i(TAG, "carpeta de proyectos: " + dir);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo preparar la carpeta de proyectos: " + e);
        }
    }

    /**
     * Crea el repositorio git de home antes de arrancar el servidor. Sin repo,
     * upstream resuelve el worktree como "/" y el watcher recorre todo el
     * sistema de archivos (el bloqueo de ~5 s al abrir una sesion).
     */
    private static void ensureGitRepo(File home, File nd, File prefix,
            Map<String, String> env) {
        File gitReal = new File(prefix, "bin/git");
        File tramp = new File(nd, "libgit.so");
        File dotGit = new File(home, ".git");
        if (!gitReal.isFile() || !tramp.isFile()) {
            Log.w(TAG, "git no disponible; se omite la inicializacion del repo");
            return;
        }
        try {
            if (!new File(dotGit, "HEAD").isFile()) {
                ProcessBuilder pb = new ProcessBuilder(tramp.getAbsolutePath(), "init", "-q");
                pb.directory(home);
                pb.redirectErrorStream(true);
                pb.environment().clear();
                pb.environment().putAll(env);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null && out.length() < 4000) {
                        out.append(line).append('\n');
                    }
                }
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    Log.w(TAG, "git init sin terminar: " + out);
                    return;
                }
                Log.i(TAG, "git init en home: " + (out.length() == 0 ? "ok" : out));
            }
            ensureExclude(dotGit);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo preparar el repo git: " + e);
        }
    }

    /**
     * Mantiene el exclude de home sin tocar las lineas del usuario. Las
     * versiones anteriores ignoraban "etc/", que es una carpeta real del
     * proyecto; ahora las overrides viven en ".oc-etc/".
     */
    private static void ensureExclude(File dotGit) throws IOException {
        File info = new File(dotGit, "info");
        File exclude = new File(info, "exclude");
        List<String> lines = new ArrayList<>();
        if (exclude.isFile()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(exclude), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) lines.add(line);
            }
        }
        List<String> out = new ArrayList<>();
        boolean hasOcEtc = false;
        for (String line : lines) {
            String t = line.trim();
            if (t.equals("etc/") || t.equals("etc")) continue;
            if (t.equals(".oc-etc/")) hasOcEtc = true;
            out.add(line);
        }
        if (!hasOcEtc) out.add(".oc-etc/");
        if (!exclude.isFile()) {
            out.clear();
            out.add(".cache/");
            out.add(".config/");
            out.add(".local/");
            out.add(".npm/");
            out.add(".run/");
            out.add(".ssh/");
            out.add("lib/");
            out.add(".native-bin/");
            out.add(".oc-etc/");
        }
        mkdirs(info);
        StringBuilder body = new StringBuilder();
        for (String line : out) body.append(line).append('\n');
        try (FileOutputStream fos = new FileOutputStream(exclude)) {
            fos.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    /** Raiz git de un directorio, o el propio directorio si no hay repo. */
    public static String projectRoot(Context ctx, String dirPath) {
        if (dirPath == null || dirPath.isEmpty()) return "";
        File dir = new File(dirPath);
        File cur = dir;
        while (cur != null) {
            if (new File(cur, ".git").exists()) return canonical(cur);
            cur = cur.getParentFile();
        }
        return canonical(dir);
    }

    /**
     * Se llama desde la SPA antes de mandar un "directory" al servidor. Si el
     * proyecto no tiene repo, lo crea con el git nativo del APK. Devuelve la
     * raiz para que el cache de /provider use una clave estable.
     */
    public static String ensureProjectGit(Context ctx, String dirPath) {
        Context app = ctx.getApplicationContext();
        if (dirPath == null || dirPath.isEmpty()) return "";
        File dir = new File(dirPath);
        // Si alguien elige la carpeta .git (el selector ya la oculta), se
        // abre el proyecto que la contiene, nunca la carpeta interna.
        if (".git".equals(dir.getName())) {
            File parent = dir.getParentFile();
            if (parent != null && parent.isDirectory()) {
                dir = parent;
                dirPath = parent.getAbsolutePath();
            }
        }
        if (!dir.isDirectory()) return canonical(dir);
        String root = projectRoot(app, dirPath);
        if (new File(root, ".git").exists()) {
            rememberProject(app, root);
            return root;
        }
        String path = canonical(dir);
        if ("/".equals(path) || "/sdcard".equals(path)
                || "/storage/emulated/0".equals(path)) {
            Log.w(TAG, "no se inicializa git en la raiz " + path);
            return path;
        }
        synchronized (GIT_LOCK) {
            root = projectRoot(app, dirPath);
            if (new File(root, ".git").exists()) {
                rememberProject(app, root);
                return root;
            }
            File nd = new File(app.getApplicationInfo().nativeLibraryDir);
            File tramp = new File(nd, "libgit.so");
            if (!tramp.isFile()) {
                Log.w(TAG, "sin libgit.so; no se prepara " + path);
                return path;
            }
            try {
                File home = homeDir(app);
                File tmp = new File(app.getCacheDir(), "tmp");
                Map<String, String> env = buildNativeEnv(app, home, tmp, nd, 0);
                ProcessBuilder pb = new ProcessBuilder(tramp.getAbsolutePath(), "init", "-q");
                pb.directory(new File(path));
                pb.redirectErrorStream(true);
                pb.environment().clear();
                pb.environment().putAll(env);
                Process p = pb.start();
                StringBuilder out = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null && out.length() < 4000) {
                        out.append(line).append('\n');
                    }
                }
                if (!p.waitFor(20, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    Log.w(TAG, "git init sin terminar en " + path + ": " + out);
                    return path;
                }
                boolean ok = p.exitValue() == 0 && new File(dir, ".git").exists();
                Log.i(TAG, "git init " + path + ": " + (ok ? "ok" : out));
                if (ok) rememberProject(app, path);
            } catch (Exception e) {
                Log.w(TAG, "git init fallo en " + path + ": " + e);
            }
            return path;
        }
    }

    private static void rememberProject(Context app, String dir) {
        if (dir == null || dir.isEmpty()) return;
        try {
            // /data/data y /data/user/0 son el mismo sitio: una sola entrada.
            String key;
            try {
                key = new File(dir).getCanonicalPath();
            } catch (Exception e) {
                key = dir;
            }
            SharedPreferences sp = app.getSharedPreferences(
                    PREF_PROJECTS, Context.MODE_PRIVATE);
            Set<String> old = sp.getStringSet(PREF_PROJECT_DIRS, null);
            LinkedHashSet<String> set = new LinkedHashSet<>();
            if (old != null) set.addAll(old);
            if (set.contains(key)) return;
            set.add(key);
            while (set.size() > MAX_REMEMBERED_PROJECTS) {
                java.util.Iterator<String> it = set.iterator();
                it.next();
                it.remove();
            }
            sp.edit().putStringSet(PREF_PROJECT_DIRS, set).apply();
            Log.i(TAG, "proyecto recordado: " + dir);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo recordar el proyecto: " + e);
        }
    }

    private static void ensureRememberedProjects(Context app) {
        try {
            SharedPreferences sp = app.getSharedPreferences(
                    PREF_PROJECTS, Context.MODE_PRIVATE);
            Set<String> dirs = sp.getStringSet(PREF_PROJECT_DIRS, null);
            if (dirs == null || dirs.isEmpty()) return;
            for (String dir : new ArrayList<>(dirs)) {
                try {
                    ensureProjectGit(app, dir);
                } catch (Exception e) {
                    Log.w(TAG, "proyecto recordado " + dir + ": " + e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no se pudieron preparar los proyectos recordados: " + e);
        }
    }

    /**
     * Migracion para instalaciones que ya tenian el proyecto "global" con
     * worktree "/". Solo actua si /project lo confirma; en instalaciones
     * nuevas el repo ya existe antes del primer arranque.
     */
    private static void ensureProjectWorktree(int port) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/project").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(2500);
            c.setUseCaches(false);
            String body = readAll(c);
            if (!body.contains("\"worktree\":\"/\"")
                    && !body.contains("\"worktree\": \"/\"")) {
                return;
            }
            HttpURLConnection post = (HttpURLConnection) new URL(
                    "http://127.0.0.1:" + port + "/project/git/init").openConnection();
            post.setConnectTimeout(2000);
            post.setReadTimeout(12000);
            post.setUseCaches(false);
            post.setRequestMethod("POST");
            post.setDoOutput(true);
            post.setFixedLengthStreamingMode(0);
            int code = post.getResponseCode();
            Log.i(TAG, "worktree migrado a git: HTTP " + code);
            post.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "no se pudo migrar el worktree: " + e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(HttpURLConnection c) throws IOException {
        try (InputStream in = c.getInputStream()) {
            byte[] buf = new byte[8192];
            StringBuilder out = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0 && out.length() < 65536) {
                out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return out.toString();
        }
    }

    private static Map<String, String> buildNativeEnv(Context app, File home, File tmp,
            File nd, int proxyPort) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        mkdirs(new File(home, ".local/share"));
        mkdirs(new File(home, ".cache"));
        mkdirs(new File(home, ".local/state"));
        mkdirs(new File(home, ".run"));
        env.put("HOME", home.getAbsolutePath());
        env.put("TMPDIR", tmp.getAbsolutePath());
        env.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
        env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
        env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
        env.put("XDG_STATE_HOME", new File(home, ".local/state").getAbsolutePath());
        env.put("XDG_RUNTIME_DIR", new File(home, ".run").getAbsolutePath());
        env.put("ANDROID_ROOT", "/system");
        env.put("ANDROID_DATA", "/data");
        env.put("TERMUX_VERSION", "0.118.0");
        env.put("SHELL", "/system/bin/sh");
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        env.put("TERM", "xterm-256color");
        String appUser = "u0_a" + (android.os.Process.myUid() % 100000);
        env.put("USER", appUser);
        env.put("LOGNAME", appUser);
        env.put("NO_COLOR", "1");
        env.put("CI", "1");
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
        // v2: contraseña fija del servidor local. La UI la recibe por
        // auth_token y el WebView la usa para Basic auth.
        env.put("OPENCODE_PASSWORD", serverPassword(app));
        env.put("OPENCODE_RUNTIME", "native");
        env.put("OPENCODE_NATIVE_LIB_DIR", nd.getAbsolutePath());

        File prefix = LocalToolbox.prefix(app);
        boolean hasToolbox = LocalToolbox.installed(app);
        // El sistema primero: los binarios del prefijo no se pueden ejecutar
        // fuera de proot (Android bloquea execve en el directorio de datos).
        // Los que solo existen en el prefijo los detecta CommandResolver y
        // los manda a proot.
        // .native-bin va primero: el servidor resuelve "git" por PATH y solo
        // puede ejecutar el trampolin del directorio nativo (SELinux bloquea
        // execve en files/). El git real del prefijo sigue intacto para proot.
        String path = new File(home, ".native-bin").getAbsolutePath()
                + ":/system/bin:/system/xbin:/vendor/bin"
                + (hasToolbox ? ":" + prefix.getAbsolutePath() + "/bin" : "")
                + ":" + nd;
        env.put("PATH", path);
        // LD_LIBRARY_PATH no se exporta al shell del agente: los binarios
        // del sistema (dumpsys, pm, am...) cargan libs incompatibles del
        // prefijo y fallan al enlazar. Las funciones de oc-native-env.sh lo
        // ponen solo al lanzar un ELF del prefijo con linker64; proot tiene
        // su propio entorno en oc-proot.
        if (hasToolbox) {
            env.put("PREFIX", prefix.getAbsolutePath());
            env.put("TERMUX_HOME", prefix.getAbsolutePath());
            env.put("GIT_EXEC_PATH",
                    new File(prefix, "libexec/git-core").getAbsolutePath());
            env.put("GIT_CONFIG_NOSYSTEM", "1");
            env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
            // Los binarios de Termux traen compilada la ruta
            // /data/data/com.termux/files/usr/etc/tls/cert.pem, que aqui no
            // existe. Sin esto, curl/wget/git fallan con "error adding trust
            // anchors" en cuanto la URL es https.
            File ca = new File(prefix, "etc/tls/cert.pem");
            if (ca.isFile()) {
                env.put("CURL_CA_BUNDLE", ca.getAbsolutePath());
                env.put("SSL_CERT_FILE", ca.getAbsolutePath());
                env.put("GIT_SSL_CAINFO", ca.getAbsolutePath());
            }
            env.put("OPENCODE_TOOLBOX_TOKEN", serverPassword(app));
            env.put("OPENCODE_TOOLBOX_PORT", Integer.toString(ToolboxServer.PORT));
        }

        String proxy = "http://127.0.0.1:" + proxyPort;
        env.put("HTTP_PROXY", proxy);
        env.put("HTTPS_PROXY", proxy);
        env.put("ALL_PROXY", proxy);
        env.put("http_proxy", proxy);
        env.put("https_proxy", proxy);
        env.put("all_proxy", proxy);
        env.put("NO_PROXY", "127.0.0.1,localhost");
        return env;
    }

    private static String nativeLibraryPath(Context app, File lib, File nd) {
        StringBuilder sb = new StringBuilder();
        if (LocalToolbox.installed(app)) {
            sb.append(LocalToolbox.prefix(app).getAbsolutePath()).append("/lib:");
        }
        sb.append(lib.getAbsolutePath()).append(':').append(nd);
        return sb.toString();
    }

    private static Process spawn(ProcessBuilder pb, String binPath) throws IOException {
        Process p = pb.start();
        process = p;
        activeBinPath = binPath;
        Thread log = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Log.i(TAG, line);
                }
            } catch (IOException ignored) {
            }
        }, "local-runtime-log");
        log.setDaemon(true);
        log.start();
        return p;
    }

    private static boolean waitForHealthy(int port, Process p, long timeoutMs,
            String password) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (p != null && !p.isAlive()) return false;
            if (healthy(port, password)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** v2: /api/info exige Basic auth y devuelve {"version":"2.2.0",...}. */
    private static boolean healthy(int port, String password) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/api/info");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            String raw = "opencode:" + password;
            c.setRequestProperty("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n <= 0) return false;
                String body = new String(buf, 0, n, StandardCharsets.UTF_8);
                return body.contains("\"version\"");
            }
        } catch (Exception e) {
            Log.d(TAG, "health " + port + ": " + e);
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Map<String, String> buildEnv(Context app, File home, File tmp, File etc,
            File lib, File nd, File loader, File prootTmp) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("XDG_CONFIG_HOME", new File(home, ".config").getAbsolutePath());
            env.put("XDG_DATA_HOME", new File(home, ".local/share").getAbsolutePath());
            env.put("XDG_CACHE_HOME", new File(home, ".cache").getAbsolutePath());
            env.put("XDG_STATE_HOME", new File(home, ".local/state").getAbsolutePath());
            env.put("XDG_RUNTIME_DIR", new File(home, ".run").getAbsolutePath());
            env.put("ANDROID_ROOT", "/system");
            env.put("ANDROID_DATA", "/data");
            env.put("TERMUX_VERSION", "0.118.0");
            // Caja de herramientas: si esta instalada, su prefijo va delante
            // del PATH y sus librerias delante del resto.
            File toolbox = LocalToolbox.prefix(app);
            boolean hasToolbox = LocalToolbox.installed(app);
            String path = (hasToolbox ? LocalToolbox.SANDBOX_PREFIX + "/bin:" : "")
                    + nd + ":/system/bin:/system/xbin:/vendor/bin";
            env.put("PATH", path);
            env.put("SHELL", "/system/bin/sh");
            env.put("LANG", "C.UTF-8");
            env.put("LC_ALL", "C.UTF-8");
            env.put("TERM", "xterm-256color");
            String appUser = "u0_a" + (android.os.Process.myUid() % 100000);
            env.put("USER", appUser);
            env.put("LOGNAME", appUser);
            env.put("NO_COLOR", "1");
            env.put("CI", "1");
            env.put("OPENCODE_DISABLE_AUTOUPDATE", "1");
            env.put("OPENCODE_PASSWORD", serverPassword(app));
            env.put("OPENCODE_RUNTIME", "proot");
            env.put("OPENCODE_NATIVE_LIB_DIR", nd.getAbsolutePath());
            String libPath = (hasToolbox ? LocalToolbox.SANDBOX_PREFIX + "/lib:" : "")
                    + lib.getAbsolutePath() + ":" + nd;
            env.put("LD_LIBRARY_PATH", libPath);
            if (hasToolbox) {
                env.put("PREFIX", LocalToolbox.SANDBOX_PREFIX);
                env.put("TERMUX_HOME", LocalToolbox.SANDBOX_PREFIX);
                env.put("TMPDIR", tmp.getAbsolutePath());
                // El pkg de Termux re-selecciona mirror (cosa de su app); aqui
                // el mirror ya esta en sources.list, asi que se le dice que no.
                env.put("TERMUX_PKG_NO_MIRROR_SELECT", "1");
                env.put("OPENCODE_TOOLBOX_TOKEN", serverPassword(app));
                env.put("OPENCODE_TOOLBOX_PORT", Integer.toString(ToolboxServer.PORT));
            }
            env.put("PROOT_LOADER", loader.getAbsolutePath());
            env.put("PROOT_TMP_DIR", prootTmp.getAbsolutePath());
            mkdirs(new File(home, ".local/share"));
            mkdirs(new File(home, ".cache"));
            mkdirs(new File(home, ".local/state"));
            mkdirs(new File(home, ".run"));
            return env;
    }

    private static List<String> prootCmd(Context app, File home, File tmp, File etc,
            File musl, File proot, Map<String, String> env) throws IOException {
        File toolbox = LocalToolbox.prefix(app);
        boolean hasToolbox = LocalToolbox.installed(app);
        StringBuilder script = new StringBuilder();
        for (Map.Entry<String, String> e : env.entrySet()) {
            script.append("export ").append(e.getKey()).append('=')
                    .append(quote(e.getValue())).append("; ");
        }
        script.append("exec \"$@\"");
        List<String> cmd = new ArrayList<>();
        cmd.add("/system/bin/sh");
        cmd.add("-c");
        cmd.add(script.toString());
        cmd.add("opencode");
        cmd.add(proot.getAbsolutePath());
        cmd.add("--kill-on-exit");
        cmd.add("-b");
        cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-b");
        cmd.add(musl.getAbsolutePath() + ":/lib/ld-musl-aarch64.so.1");
        if (hasToolbox) {
            // Prefijo estilo Termux: las herramientas llevan dentro rutas
            // absolutas (/data/data/com.termux/files/usr) y solo funcionan
            // si ese directorio existe con ese nombre dentro del sandbox.
            cmd.add("-b");
            cmd.add(toolbox.getAbsolutePath() + ":" + LocalToolbox.SANDBOX_PREFIX);
        }
        cmd.add("-b");
        cmd.add(new File(etc, "hosts").getAbsolutePath() + ":/etc/hosts");
        cmd.add("-b");
        cmd.add(new File(etc, "resolv.conf").getAbsolutePath() + ":/etc/resolv.conf");
        cmd.add("-w");
        cmd.add(home.getAbsolutePath());
        return cmd;
    }

    public static void stop() {
        stopCurrent();
    }

    private static void stopCurrent() {
        Process p;
        String binPath;
        synchronized (LOCK) {
            p = process;
            process = null;
            binPath = activeBinPath;
            activeBinPath = null;
        }
        if (p != null) {
            p.destroy();
        }
        killMatching("-TERM", "libopencode2_bin.so serve --hostname 127.0.0.1 --port "
                + BuildConfig.SERVER_PORT);
        if (binPath != null && !binPath.endsWith("libopencode2_bin.so")) {
            killMatching("-TERM", binPath + " serve --hostname 127.0.0.1 --port "
                    + BuildConfig.SERVER_PORT);
        }
        if (p != null) {
            try {
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        killMatching("-KILL", "libopencode2_bin.so serve --hostname 127.0.0.1 --port "
                + BuildConfig.SERVER_PORT);
        if (binPath != null && !binPath.endsWith("libopencode2_bin.so")) {
            killMatching("-KILL", binPath + " serve --hostname 127.0.0.1 --port "
                    + BuildConfig.SERVER_PORT);
        }
        LocalNetworkProxy.stop();
    }

    private static void killMatching(String signal, String pattern) {
        try {
            Process k = new ProcessBuilder("/system/bin/pkill", signal, "-f", pattern)
                    .redirectErrorStream(true).start();
            k.waitFor(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    private static void copyIfNeeded(File src, File dst) throws IOException {
        if (dst.isFile() && dst.length() == src.length()) {
            return;
        }
        try (InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("no se pudo crear " + dir);
        }
    }

    private static void prepareNetworkOverrides(File home, File etc) throws IOException {
        migrateNetworkOverrides(home);
        writeNetworkOverrides(etc);
    }

    /**
     * Instalaciones anteriores guardaban hosts/resolv.conf dentro de home/etc,
     * que es una carpeta del proyecto del usuario. Solo se borran si el
     * contenido es exactamente el que generaba el launcher; cualquier edicion
     * del usuario se conserva.
     */
    private static void migrateNetworkOverrides(File home) {
        File old = new File(home, "etc");
        File hosts = new File(old, "hosts");
        if (hosts.isFile() && isOurHosts(hosts)) {
            if (hosts.delete()) Log.i(TAG, "hosts antiguo retirado de etc");
        }
        File resolv = new File(old, "resolv.conf");
        if (resolv.isFile() && isOurResolv(resolv)) {
            if (resolv.delete()) Log.i(TAG, "resolv.conf antiguo retirado de etc");
        }
    }

    private static boolean isOurHosts(File file) {
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) lines.add(line.trim());
                }
            }
            if (lines.size() != 3) return false;
            if (!lines.get(0).equals("127.0.0.1 localhost")) return false;
            boolean opencode = false;
            boolean models = false;
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split("\\s+");
                if (parts.length != 2) return false;
                if (parts[1].equals("opencode.ai")) opencode = true;
                else if (parts[1].equals("models.opencode.ai")) models = true;
                else return false;
            }
            return opencode && models;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isOurResolv(File file) {
        try {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.trim().isEmpty()) lines.add(line.trim());
                }
            }
            return lines.size() == 2
                    && lines.get(0).equals("nameserver 1.1.1.1")
                    && lines.get(1).equals("nameserver 8.8.8.8");
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeNetworkOverrides(File etc) throws IOException {
        String[][] hosts = {
                {"opencode.ai", "172.65.90.20"},
                {"models.opencode.ai", "172.66.173.149"}
        };
        StringBuilder body = new StringBuilder("127.0.0.1 localhost\n");
        for (String[] host : hosts) {
            String ip = null;
            try {
                for (InetAddress address : InetAddress.getAllByName(host[0])) {
                    if (address instanceof Inet4Address) {
                        ip = address.getHostAddress();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
            body.append(ip == null ? host[1] : ip).append(' ').append(host[0]).append('\n');
        }
        try (FileWriter w = new FileWriter(new File(etc, "hosts"), false)) {
            w.write(body.toString());
        }
        try (FileWriter w = new FileWriter(new File(etc, "resolv.conf"), false)) {
            w.write("nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        }
    }

    /**
     * Instala los tools que sustituyen a grep/glob. Viven en el directorio
     * global de configuracion y llaman al rg Bionic empaquetado con linker64,
     * asi que funcionan sin root y sin descargar el rg glibc del servidor.
     * Si el usuario ya tiene un fichero con ese nombre, no se pisa.
     */
    private static void installTools(Context app, File config) {
        try {
            File dir = new File(config, "tools");
            mkdirs(dir);
            installTool(app, dir, "grep.ts");
            installTool(app, dir, "glob.ts");
        } catch (Exception e) {
            Log.w(TAG, "no se pudieron instalar los tools grep/glob: " + e);
        }
    }

    private static void installTool(Context app, File dir, String name) throws IOException {
        File dest = new File(dir, name);
        if (dest.isFile() && !isOurTool(dest)) {
            Log.i(TAG, "se conserva el tool de usuario " + dest);
            return;
        }
        File tmp = new File(dir, name + ".tmp");
        try (InputStream in = app.getAssets().open("tools/" + name);
                OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
        if (!tmp.renameTo(dest)) {
            try (InputStream in = new FileInputStream(tmp);
                    OutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        Log.i(TAG, "tool instalado " + dest);
    }

    private static boolean isOurTool(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) return false;
            return new String(buf, 0, n, StandardCharsets.UTF_8)
                    .contains("OPENCODE_ANDROID_TOOL");
        } catch (Exception e) {
            return false;
        }
    }

    private static void writeConfig(File dir) throws IOException {
        File f = new File(dir, "opencode.jsonc");
        if (f.isFile()) {
            return;
        }
        try (FileWriter w = new FileWriter(f, false)) {
            w.write("{\n  \"$schema\": \"https://opencode.ai/config.json\",\n");
            w.write("  \"autoupdate\": false\n}\n");
        }
    }

    /**
     * El FUSE de /sdcard presenta los archivos como de media_rw, no del uid
     * de la app. Git lo interpreta como "dubious ownership" y se niega a
     * trabajar, con lo que un proyecto externo acababa con worktree "/".
     * Se marca safe.directory para todos los repos: el sandbox de la app ya
     * esta aislado y el usuario elige que proyectos abre.
     */
    private static void writeGitConfig(File home) {
        File f = new File(home, ".gitconfig");
        try {
            String body = f.isFile()
                    ? new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)
                    : "";
            if (body.contains("[safe]")) return;
            StringBuilder sb = new StringBuilder(body);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append('\n');
            sb.append("[safe]\n\tdirectory = *\n");
            try (FileOutputStream fos = new FileOutputStream(f, false)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "no se pudo escribir .gitconfig: " + e.getMessage());
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
