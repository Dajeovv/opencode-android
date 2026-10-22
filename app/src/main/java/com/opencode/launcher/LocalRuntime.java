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
import java.net.Inet4Address;
import java.net.InetAddress;
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
    private static Process process;
    private static String activeBinPath;

    private LocalRuntime() {
    }

    public static String activeVersion(Context ctx) {
        try {
            SharedPreferences sp = ctx.getApplicationContext()
                    .getSharedPreferences("app", Context.MODE_PRIVATE);
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
                    .getSharedPreferences("app", Context.MODE_PRIVATE);
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

    public static boolean available(Context ctx) {
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

    /** Contraseña Basic estable por instalación para el servidor local. */
    public static String serverPassword(Context ctx) {
        SharedPreferences sp = ctx.getApplicationContext()
                .getSharedPreferences("app", Context.MODE_PRIVATE);
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
            File nd = new File(app.getApplicationInfo().nativeLibraryDir);
            if (!available(app)) {
                throw new IOException("faltan binarios del runtime local");
            }

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
            writeNetworkOverrides(etc);

            File bin = runtimeBinary(app);
            if (bin == null) bin = new File(nd, "libopencode2_bin.so");
            activeBinPath = bin.getAbsolutePath();
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

            Process p = pb.start();
            process = p;
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
            return true;
        }
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
            if (!available(app)) return "no hay runtime local";
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
