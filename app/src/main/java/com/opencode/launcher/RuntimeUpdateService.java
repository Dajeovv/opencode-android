package com.opencode.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class RuntimeUpdateService extends Service {
    public static final String ACTION_INSTALL = "com.opencode.launcher.RUNTIME_INSTALL";
    public static final String EXTRA_VERSION = "version";

    private static final String TAG = "OC-RuntimeUpdate";
    private static final String CHANNEL = "runtime_update";
    private static final int NID_PROGRESS = 2202;

    private volatile boolean running;
    private volatile Thread worker;
    private volatile String requestedVersion = "";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || !ACTION_INSTALL.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        requestedVersion = intent.getStringExtra(EXTRA_VERSION);
        if (requestedVersion == null) requestedVersion = "";
        if (running) return START_NOT_STICKY;
        startForeground(NID_PROGRESS, progress(ServerWatch.t(this,
                "Preparando actualización", "Preparing update"), 0, 0, true));
        running = true;
        worker = new Thread(() -> {
            try {
                runUpdate();
            } catch (Throwable t) {
                fail(message(t));
            } finally {
                running = false;
                stopForeground(true);
                stopSelf();
            }
        }, "runtime-update");
        worker.start();
        return START_NOT_STICKY;
    }

    private void runUpdate() throws Exception {
        UpdateCheck.RuntimeAsset asset = UpdateCheck.opencodeRuntimeAsset();
        if (asset == null) {
            fail(ServerWatch.t(this, "No se pudo consultar la release",
                    "Could not read the release"));
            return;
        }
        String active = LocalRuntime.activeVersion(this);
        if (UpdateCheck.cmpVer(asset.version, active) <= 0) {
            markUpToDate(active, asset.version);
            ServerWatch.notifyId(this, ServerWatch.NID_OC,
                    ServerWatch.t(this, "OpenCode al día", "OpenCode is up to date"),
                    active);
            return;
        }
        if (!requestedVersion.isEmpty()
                && UpdateCheck.cmpVer(asset.version, requestedVersion) < 0) {
            fail(ServerWatch.t(this, "La release cambió, reintenta",
                    "The release changed, retry"));
            return;
        }

        File cache = new File(getCacheDir(), "runtime-update");
        try {
            deleteRecursive(cache);
            mkdirs(cache);
            File archive = new File(cache, asset.name);
            File part = new File(cache, asset.name + ".part");
            download(part, asset);
            if (!part.renameTo(archive)) throw new IOException("no se pudo cerrar la descarga");
            String archiveHash = sha256(archive);
            if (!archiveHash.equalsIgnoreCase(asset.sha256)) {
                throw new IOException("SHA-256 no coincide");
            }

            File extracted = new File(cache, "extract-" + asset.version);
            mkdirs(extracted);
            untar(archive, extracted);
            File binary = findBinary(extracted);
            if (binary == null) throw new IOException("no se encontró el binario opencode");
            String binaryHash = sha256(binary);

            File runtimeRoot = new File(getFilesDir(), "runtime");
            File destDir = new File(runtimeRoot, asset.version);
            mkdirs(destDir);
            File dest = new File(destDir, "opencode");
            File destPart = new File(destDir, "opencode.part");
            copy(binary, destPart);
            if (!sha256(destPart).equalsIgnoreCase(binaryHash)) {
                throw new IOException("copia del binario incompleta");
            }
            if (dest.exists() && !dest.delete()) throw new IOException("no se pudo reemplazar el binario");
            if (!destPart.renameTo(dest)) throw new IOException("no se pudo instalar el binario");
            dest.setReadable(true, true);
            dest.setExecutable(true, true);

            SharedPreferences p = getSharedPreferences("app", MODE_PRIVATE);
            p.edit()
                    .putString("opencode_runtime_version", asset.version)
                    .putString("opencode_runtime_path", dest.getAbsolutePath())
                    .putString("opencode_runtime_digest", binaryHash)
                    .putLong("opencode_runtime_installed_at", System.currentTimeMillis())
                    .apply();

            LocalRuntime.stop();
            LocalServerService.start(this);
            if (!waitHealth(asset.version)) {
                rollback(destDir);
                fail(ServerWatch.t(this, "El binario nuevo no arrancó",
                        "The new binary did not start"));
                return;
            }

            markInstalled(asset.version);
            cleanupRuntimes(runtimeRoot, asset.version);
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NID_PROGRESS);
            ServerWatch.notifyId(this, ServerWatch.NID_OC,
                    ServerWatch.t(this, "OpenCode actualizado", "OpenCode updated"),
                    active + " -> " + asset.version);
        } finally {
            deleteRecursive(cache);
        }
    }

    private void download(File part, UpdateCheck.RuntimeAsset asset) throws Exception {
        if (asset.size <= 0) throw new IOException("tamaño de descarga desconocido");
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            long have = part.isFile() ? part.length() : 0;
            if (have > asset.size) {
                if (!part.delete()) throw new IOException("no se pudo limpiar la descarga");
                have = 0;
            }
            if (have == asset.size) return;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(asset.url).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", "opencode-android");
                c.setRequestProperty("Accept", "application/octet-stream");
                c.setRequestProperty("Accept-Encoding", "identity");
                if (have > 0) c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                if (have > 0 && code == 200) {
                    have = 0;
                } else if (code != 200 && code != 206) {
                    throw new IOException("HTTP " + code);
                }
                try (InputStream in = c.getInputStream();
                        OutputStream out = new FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[64 * 1024];
                    long done = have;
                    long lastTick = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastTick > 1000) {
                            updateProgress(done, asset.size);
                            lastTick = now;
                        }
                    }
                }
                if (part.length() == asset.size) return;
                last = new IOException("descarga incompleta");
            } catch (IOException e) {
                last = e;
            } finally {
                if (c != null) c.disconnect();
            }
            if (attempt < 2) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("cancelado");
                }
            }
        }
        throw last != null ? last : new IOException("descarga fallida");
    }

    private void untar(File archive, File dir) throws Exception {
        Process p = new ProcessBuilder("/system/bin/tar", "--restrict", "-xzf",
                archive.getAbsolutePath(), "-C", dir.getAbsolutePath())
                .redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (out) {
                        if (out.length() < 4096) out.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
            }
        }, "runtime-untar");
        reader.start();
        if (!p.waitFor(180, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("tar agotó el tiempo");
        }
        reader.join(2000);
        if (p.exitValue() != 0) {
            String detail;
            synchronized (out) {
                detail = out.toString().trim();
            }
            throw new IOException("tar: " + (detail.isEmpty() ? "error" : detail));
        }
    }

    private File findBinary(File dir) {
        File best = null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (Files.isSymbolicLink(f.toPath())) continue;
            if (f.isDirectory()) {
                File found = findBinary(f);
                if (found != null && (best == null || found.length() > best.length())) best = found;
            } else if (f.isFile() && (f.getName().equals("opencode")
                    || f.getName().startsWith("opencode-"))) {
                if (f.length() > 10L * 1024 * 1024
                        && (best == null || f.length() > best.length())) best = f;
            }
        }
        return best;
    }

    private boolean waitHealth(String version) {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) {
            if (Thread.currentThread().isInterrupted()) return false;
            if (version.equals(healthVersion())) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private String healthVersion() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("http://127.0.0.1:"
                    + BuildConfig.SERVER_PORT + "/global/health").openConnection();
            c.setConnectTimeout(1500);
            c.setReadTimeout(1500);
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
                return new JSONObject(sb.toString()).optString("version", "");
            }
        } catch (Exception e) {
            return "";
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void rollback(File destDir) {
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .remove("opencode_runtime_version")
                .remove("opencode_runtime_path")
                .remove("opencode_runtime_digest")
                .remove("opencode_runtime_installed_at")
                .apply();
        deleteRecursive(destDir);
        LocalRuntime.stop();
        LocalServerService.start(this);
    }

    private void markInstalled(String version) {
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putString("upd_installed", version)
                .putString("upd_latest", version)
                .putString("upd_runtime", "local:" + version)
                .putInt("upd_v", 5)
                .putLong("upd_ts", System.currentTimeMillis())
                .putBoolean("upd_pending", false)
                .putString("upd_notified", version)
                .apply();
    }

    private void markUpToDate(String active, String latest) {
        getSharedPreferences("app", MODE_PRIVATE).edit()
                .putString("upd_installed", active)
                .putString("upd_latest", latest)
                .putString("upd_runtime", "local:" + active)
                .putInt("upd_v", 5)
                .putLong("upd_ts", System.currentTimeMillis())
                .putBoolean("upd_pending", false)
                .putString("upd_notified", latest)
                .apply();
    }

    private void cleanupRuntimes(File root, String keep) {
        File[] files = root.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && !f.getName().equals(keep)) deleteRecursive(f);
        }
    }

    private void updateProgress(long done, long total) {
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NID_PROGRESS, progress(
                ServerWatch.t(this, "Descargando OpenCode ", "Downloading OpenCode ")
                        + pct + "%", done, total, false));
    }

    private Notification progress(String text, long done, long total, boolean indeterminate) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL,
                    ServerWatch.t(this, "Actualización de OpenCode", "OpenCode update"),
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int fl = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) fl |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, NID_PROGRESS, open, fl);
        int pct = total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(ServerWatch.t(this, "Actualizando OpenCode",
                        "Updating OpenCode"))
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setProgress(100, pct, indeterminate)
                .build();
    }

    private void fail(String detail) {
        Log.w(TAG, detail);
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(NID_PROGRESS);
        ServerWatch.notifyOpenCodeUpdate(this,
                ServerWatch.t(this, "No se pudo actualizar OpenCode",
                        "OpenCode update failed"),
                detail, requestedVersion);
    }

    private static String message(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.isEmpty()) m = t.getClass().getSimpleName();
        return m.length() > 240 ? m.substring(0, 240) : m;
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("no se pudo crear " + dir);
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
                OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest()) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory() && !Files.isSymbolicLink(f.toPath())) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        f.delete();
    }

    @Override
    public void onDestroy() {
        running = false;
        Thread t = worker;
        if (t != null) t.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
