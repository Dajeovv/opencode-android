package com.opencode.launcher;

import android.content.Context;
import android.content.SharedPreferences;
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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class LocalRuntime {
    private static final String TAG = "OpenCodeLocal";
    private static final Object LOCK = new Object();
    private static final String PREF = "app";
    private static final String PREF_NATIVE = "native_runtime_enabled";
    private static final long NATIVE_HEALTH_TIMEOUT_MS = 45_000;

    private static Process process;
    private static String activeBinPath;

    private LocalRuntime() {
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
    public static String serverPassword(Context ctx) {
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

        File home = new File(app.getFilesDir(), "home");
        File config = new File(home, ".config/opencode");
        File tmp = new File(app.getCacheDir(), "tmp");
        File lib = new File(home, "lib");
        File etc = new File(home, "etc");
        mkdirs(home);
        mkdirs(config);
        mkdirs(tmp);
        mkdirs(lib);
        mkdirs(etc);
        writeConfig(config);
        installTools(app, config);
        writeNetworkOverrides(etc);
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
        cmd.add("INFO");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(home);
        pb.redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = spawn(pb, bin.getAbsolutePath());
        Log.i(TAG, "runtime nativo arrancado; proxy=" + proxyPort + " puerto=" + port);

        if (!waitForHealthy(port, p, NATIVE_HEALTH_TIMEOUT_MS)) {
            Log.w(TAG, "runtime nativo sin respuesta en /global/health");
            stopCurrent();
            return false;
        }
        Log.i(TAG, "runtime nativo listo en 127.0.0.1:" + port);
        return true;
    }

    private static boolean startProot(Context app, int port) throws IOException {
        File nd = new File(app.getApplicationInfo().nativeLibraryDir);
        File home = new File(app.getFilesDir(), "home");
        File config = new File(home, ".config/opencode");
        File tmp = new File(app.getCacheDir(), "tmp");
        File prootTmp = new File(tmp, "proot");
        File lib = new File(home, "lib");
        File etc = new File(home, "etc");
        mkdirs(home);
        mkdirs(config);
        mkdirs(tmp);
        mkdirs(prootTmp);
        mkdirs(lib);
        mkdirs(etc);
        writeConfig(config);
        installTools(app, config);
        writeNetworkOverrides(etc);

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
            File home = new File(app.getFilesDir(), "home");
            File tmp = new File(app.getCacheDir(), "tmp");
            File prootTmp = new File(tmp, "proot");
            File lib = new File(home, "lib");
            File etc = new File(home, "etc");
            mkdirs(home);
            mkdirs(tmp);
            mkdirs(prootTmp);
            mkdirs(lib);
            mkdirs(etc);
            writeNetworkOverrides(etc);
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
        env.put("OPENCODE_RUNTIME", "native");
        env.put("OPENCODE_NATIVE_LIB_DIR", nd.getAbsolutePath());

        File prefix = LocalToolbox.prefix(app);
        boolean hasToolbox = LocalToolbox.installed(app);
        // El sistema primero: los binarios del prefijo no se pueden ejecutar
        // fuera de proot (Android bloquea execve en el directorio de datos).
        // Los que solo existen en el prefijo los detecta CommandResolver y
        // los manda a proot.
        String path = "/system/bin:/system/xbin:/vendor/bin"
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

    private static boolean waitForHealthy(int port, Process p, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (p != null && !p.isAlive()) return false;
            if (healthy(port)) return true;
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static boolean healthy(int port) {
        HttpURLConnection c = null;
        try {
            URL url = new URL("http://127.0.0.1:" + port + "/global/health");
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(800);
            c.setReadTimeout(1200);
            c.setUseCaches(false);
            c.setRequestMethod("GET");
            if (c.getResponseCode() != 200) return false;
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n = in.read(buf);
                if (n <= 0) return false;
                String body = new String(buf, 0, n, StandardCharsets.UTF_8);
                return body.contains("\"healthy\":true")
                        || body.contains("\"healthy\": true");
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

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
