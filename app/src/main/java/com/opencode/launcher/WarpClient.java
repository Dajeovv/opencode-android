package com.opencode.launcher;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Cliente WARP propio, sin la app oficial de Cloudflare y sin root.
 *
 * Registra una identidad WARP nueva contra la API publica, escribe una
 * configuracion de WireGuard y arranca wireproxy (binario arm64 empaquetado
 * como libwireproxy.so) en espacio de usuario. wireproxy expone un proxy HTTP
 * solo en loopback; LocalNetworkProxy manda por ahi el trafico de opencode.ai.
 *
 * Asi el cupo free se cuenta contra la IP de WARP, no contra la del WiFi, y
 * cambiar de identidad cambia tambien la IP de salida.
 */
public final class WarpClient {

    public interface Callback {
        void done(boolean ok, String message, String before, String after);
    }

    private static final String TAG = "OpenCodeWarp";
    private static final String API = "https://api.cloudflareclient.com/v0a2158/reg";
    private static final String TRACE = "https://connectivity.cloudflareclient.com/cdn-cgi/trace";
    private static final String ENDPOINT = "162.159.192.1:2408";
    private static final String IDENTITY = "identity.json";
    private static final String CONFIG = "wireproxy.conf";
    private static final String LOG = "wireproxy.log";
    private static final String ROTATE_CONFIG = "wireproxy.rotate.conf";
    private static final String ROTATE_LOG = "wireproxy.rotate.log";
    private static final String PREF = "warp_enabled";

    private static final Object LOCK = new Object();
    private static Context app;
    private static Process process;
    private static int proxyPort;
    private static volatile String state = "off";
    private static volatile String lastError = "";
    private static volatile String lastEgress = "";
    private static volatile boolean starting;
    private static volatile boolean rotating;

    private WarpClient() {
    }

    public static void init(Context ctx) {
        app = ctx.getApplicationContext();
    }

    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
                .getBoolean(PREF, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        ctx.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
                .putBoolean(PREF, on).apply();
    }

    public static boolean running() {
        Process p = process;
        return p != null && p.isAlive() && "on".equals(state);
    }

    /** Hay un arranque o cambio de IP en curso. */
    public static boolean busy() {
        return starting || rotating;
    }

    public static String egress() {
        return lastEgress;
    }

    public static String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", running());
            o.put("state", state);
            o.put("port", proxyPort);
            o.put("egress", lastEgress == null ? "" : lastEgress);
            o.put("error", lastError == null ? "" : lastError);
            o.put("rotating", rotating);
            return o.toString();
        } catch (Exception e) {
            return "{\"running\":false}";
        }
    }

    /** Arranca WARP si hace falta. No bloquea la UI. */
    public static void startAsync(Context ctx, Callback cb) {
        init(ctx);
        new Thread(() -> {
            String before = lastEgress;
            try {
                startBlocking();
                String after = egressIp();
                if (after != null) lastEgress = after;
                if (cb != null) cb.done(true, msg("WARP activo", "WARP active"), before, after);
            } catch (Throwable t) {
                lastError = String.valueOf(t.getMessage());
                state = "error";
                Log.w(TAG, "start", t);
                if (cb != null) cb.done(false,
                        msg("No se pudo activar WARP: ", "Could not enable WARP: ")
                                + t.getMessage(), before, null);
            }
        }, "warp-start").start();
    }

    /**
     * Cambia la identidad sin cortar el servicio: el tunel viejo sigue activo
     * hasta que el nuevo arranca y da una IP distinta. Si Cloudflare limita el
     * registro (429), se descarta el intento y se mantiene el tunel anterior.
     */
    public static void rotateAsync(Context ctx, Callback cb) {
        init(ctx);
        new Thread(() -> {
            synchronized (LOCK) {
                String before = running() ? egressIp() : lastEgress;
                if (!running()) {
                    // Sin tunel activo no hay nada que conservar: arranque normal.
                    try {
                        startBlocking();
                        String after = egressIp();
                        if (after != null) lastEgress = after;
                        notifyDone(cb, true, msg("WARP activo", "WARP active"), before, after);
                    } catch (Throwable t) {
                        lastError = String.valueOf(t.getMessage());
                        state = "error";
                        Log.w(TAG, "rotate-start", t);
                        notifyDone(cb, false, rotateMessage(t, false), before, null);
                    }
                    return;
                }

                rotating = true;
                Candidate cand = null;
                try {
                    String after = null;
                    for (int attempt = 0; attempt < 2; attempt++) {
                        cand = openCandidate();
                        String ip = egressIp(cand.port);
                        if (ip == null) {
                            throw new IOException(msg("el tunel nuevo no dio IP", "the new tunnel did not provide an IP"));
                        }
                        if (before != null && ip.equals(before)) {
                            // Cloudflare puede repetir la salida; se prueba una vez mas.
                            cand.close();
                            cand = null;
                            continue;
                        }
                        after = ip;
                        break;
                    }
                    if (cand == null || after == null) {
                        notifyDone(cb, false,
                                msg("La IP no cambio; se mantiene la anterior. WARP sigue activo",
                                        "The IP did not change; keeping the previous one. WARP stays active"),
                                before, before);
                        return;
                    }

                    // El nuevo ya esta verificado: sustituir el activo.
                    Process oldProcess = process;
                    int newPort = cand.port;
                    File newLog = cand.log;
                    writeConfig(cand.identity, newPort, new File(warpDir(), CONFIG));
                    saveIdentity(cand.identity);
                    process = cand.process;
                    proxyPort = newPort;
                    state = "on";
                    lastError = "";
                    lastEgress = after;
                    LocalNetworkProxy.setUpstream(newPort);
                    cand = null; // ya es el proceso activo
                    destroy(oldProcess);

                    File activeLog = new File(warpDir(), LOG);
                    //noinspection ResultOfMethodCallIgnored
                    activeLog.delete();
                    //noinspection ResultOfMethodCallIgnored
                    newLog.renameTo(activeLog);
                    //noinspection ResultOfMethodCallIgnored
                    new File(warpDir(), ROTATE_CONFIG).delete();

                    Log.i(TAG, "IP cambiada: " + before + " -> " + after
                            + " puerto " + newPort);
                    notifyDone(cb, true, msg("IP cambiada", "IP changed"), before, after);
                } catch (Throwable t) {
                    if (cand != null) cand.close();
                    Log.w(TAG, "rotate", t);
                    notifyDone(cb, false, rotateMessage(t, true), before, before);
                } finally {
                    rotating = false;
                }
            }
        }, "warp-rotate").start();
    }

    public static void stopAsync(Callback cb) {
        new Thread(() -> {
            try {
                stopBlocking();
                if (cb != null) cb.done(true, msg("WARP desactivado", "WARP disabled"),
                        lastEgress, null);
            } catch (Throwable t) {
                if (cb != null) cb.done(false,
                        msg("Error al parar WARP: ", "Error stopping WARP: ") + t.getMessage(),
                        lastEgress, null);
            }
        }, "warp-stop").start();
    }

    // ------------------------------------------------------------ ciclo de vida

    private static void startBlocking() throws Exception {
        synchronized (LOCK) {
            if (starting) return;
            if (running()) return;
            starting = true;
            state = "starting";
            lastError = "";
            try {
                File dir = warpDir();
                JSONObject identity = loadIdentity();
                if (identity == null) {
                    identity = register();
                    saveIdentity(identity);
                }
                int port = freePort();
                File conf = new File(dir, CONFIG);
                writeConfig(identity, port, conf);
                File log = new File(dir, LOG);
                Process p = spawnWireproxy(conf, log);
                process = p;
                waitForPort(p, port, 20000, log);
                proxyPort = port;
                LocalNetworkProxy.setUpstream(port);
                state = "on";
                Log.i(TAG, "WARP propio listo en 127.0.0.1:" + port
                        + " cliente=" + identity.optString("client_id"));
            } catch (Exception e) {
                lastError = String.valueOf(e.getMessage());
                state = "error";
                stopProcessQuietly();
                throw e;
            } finally {
                starting = false;
            }
        }
    }

    private static void stopBlocking() {
        synchronized (LOCK) {
            LocalNetworkProxy.clearUpstream();
            state = "off";
            stopProcessQuietly();
        }
    }

    private static void stopProcessQuietly() {
        proxyPort = 0;
        Process p = process;
        process = null;
        destroy(p);
    }

    private static void destroy(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (Exception ignored) {
        }
    }

    private static void notifyDone(Callback cb, boolean ok, String message,
                                   String before, String after) {
        if (cb == null) return;
        try {
            cb.done(ok, message, before, after);
        } catch (Throwable ignored) {
        }
    }

    private static String rotateMessage(Throwable t, boolean keepOld) {
        String msg = String.valueOf(t.getMessage());
        String suffix = keepOld
                ? msg("; se mantiene el WARP anterior", "; keeping the previous WARP")
                : "";
        if (msg.contains("HTTP 429") || msg.contains("1015")
                || msg.toLowerCase(Locale.US).contains("rate limit")) {
            return msg("Cloudflare limito los cambios; espera un minuto",
                    "Cloudflare rate-limited the changes; wait a minute") + suffix;
        }
        return msg("No se pudo cambiar la IP: ", "Could not change the IP: ") + msg + suffix;
    }

    /** Mismo criterio que MainActivity: override manual o idioma del movil. */
    private static boolean useEn() {
        Context ctx = app;
        if (ctx == null) return false;
        String l = ctx.getSharedPreferences("app", Context.MODE_PRIVATE)
                .getString("lang", "auto");
        if ("es".equals(l)) return false;
        if ("en".equals(l)) return true;
        String dl = Locale.getDefault().getLanguage();
        return dl == null || !dl.startsWith("es");
    }

    private static String msg(String es, String en) {
        return useEn() ? en : es;
    }

    private static final class Candidate {
        final JSONObject identity;
        final int port;
        final Process process;
        final File conf;
        final File log;

        Candidate(JSONObject identity, int port, Process process, File conf, File log) {
            this.identity = identity;
            this.port = port;
            this.process = process;
            this.conf = conf;
            this.log = log;
        }

        void close() {
            destroy(process);
            //noinspection ResultOfMethodCallIgnored
            if (conf != null) conf.delete();
        }
    }

    /** Registra y arranca un tunel candidato sin tocar el que ya esta activo. */
    private static Candidate openCandidate() throws Exception {
        JSONObject identity = register();
        int port = freePort();
        File conf = new File(warpDir(), ROTATE_CONFIG);
        File log = new File(warpDir(), ROTATE_LOG);
        //noinspection ResultOfMethodCallIgnored
        conf.delete();
        try {
            writeConfig(identity, port, conf);
            Process p = spawnWireproxy(conf, log);
            try {
                waitForPort(p, port, 20000, log);
            } catch (Exception e) {
                destroy(p);
                throw e;
            }
            return new Candidate(identity, port, p, conf, log);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            conf.delete();
            throw e;
        }
    }

    private static File wireproxyBinary() throws IOException {
        File bin = new File(app.getApplicationInfo().nativeLibraryDir,
                "libwireproxy.so");
        if (!bin.isFile()) {
            throw new IOException(msg("falta libwireproxy.so en la app", "libwireproxy.so is missing from the app"));
        }
        if (!bin.canExecute()) {
            // En algunas instalaciones el .so extraido no trae +x.
            //noinspection ResultOfMethodCallIgnored
            bin.setExecutable(true, false);
        }
        return bin;
    }

    private static Process spawnWireproxy(File conf, File log) throws IOException {
        File bin = wireproxyBinary();
        ProcessBuilder pb = new ProcessBuilder(
                bin.getAbsolutePath(), "-s", "-c", conf.getAbsolutePath());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.to(log));
        return pb.start();
    }

    // ------------------------------------------------------------ registro

    private static JSONObject register() throws Exception {
        byte[] priv = new byte[32];
        new SecureRandom().nextBytes(priv);
        clamp(priv);
        byte[] pub = x25519(priv, basePoint());
        String privB64 = Base64.encodeToString(priv, Base64.NO_WRAP);
        String pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP);

        JSONObject body = new JSONObject();
        body.put("key", pubB64);
        body.put("install_id", "");
        body.put("fcm_token", "");
        body.put("to_proxy", false);
        body.put("locale", "en_US");
        body.put("tos", isoNow());

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setConnectTimeout(30000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("CF-Client-Version", "a-6.10-2158");
            c.setRequestProperty("User-Agent", "okhttp/3.12.13");
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("Connection", "Keep-Alive");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(bytes.length);
            try (java.io.OutputStream out = c.getOutputStream()) {
                out.write(bytes);
                out.flush();
            }
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300
                    ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            if (code < 200 || code >= 300) {
                throw new IOException(msg("registro WARP HTTP ", "WARP registration HTTP ") + code + ": " + shorten(text));
            }
            JSONObject resp = new JSONObject(text);
            JSONObject cfg = resp.getJSONObject("config");
            JSONObject addr = cfg.getJSONObject("interface").getJSONObject("addresses");
            JSONArray peers = cfg.getJSONArray("peers");
            if (peers.length() == 0) throw new IOException(msg("WARP sin peers", "WARP returned no peers"));
            JSONObject peer = peers.getJSONObject(0);

            JSONObject id = new JSONObject();
            id.put("private_key", privB64);
            id.put("peer_public_key", peer.getString("public_key"));
            id.put("v4", addr.getString("v4"));
            id.put("v6", addr.getString("v6"));
            id.put("client_id", resp.optString("id", ""));
            id.put("created", System.currentTimeMillis());
            Log.i(TAG, "identidad WARP registrada");
            return id;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String isoNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    // ------------------------------------------------------------ config

    private static void writeConfig(JSONObject id, int port, File conf) throws Exception {
        String text = "[Interface]\n"
                + "PrivateKey = " + id.getString("private_key") + "\n"
                + "Address = " + id.getString("v4") + "/32, " + id.getString("v6") + "/128\n"
                + "MTU = 1280\n"
                + "DNS = 1.1.1.1\n"
                + "\n"
                + "[Peer]\n"
                + "PublicKey = " + id.getString("peer_public_key") + "\n"
                + "Endpoint = " + ENDPOINT + "\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "\n"
                + "[http]\n"
                + "BindAddress = 127.0.0.1:" + port + "\n";
        try (FileOutputStream out = new FileOutputStream(conf)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        //noinspection ResultOfMethodCallIgnored
        conf.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        conf.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        conf.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        conf.setWritable(true, true);
    }

    private static int freePort() throws IOException {
        ServerSocket ss = new ServerSocket();
        try {
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
            return ss.getLocalPort();
        } finally {
            try {
                ss.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void waitForPort(Process p, int port, long timeoutMs) throws Exception {
        waitForPort(p, port, timeoutMs, new File(warpDir(), LOG));
    }

    private static void waitForPort(Process p, int port, long timeoutMs, File log)
            throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        InetAddress loop = InetAddress.getByName("127.0.0.1");
        while (System.currentTimeMillis() < end) {
            if (!p.isAlive()) {
                throw new IOException(msg("wireproxy termino: ", "wireproxy exited: ") + logTail(log));
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress(loop, port), 300);
                return;
            } catch (IOException ignored) {
            }
            Thread.sleep(150);
        }
        throw new IOException(msg("wireproxy no abrio el proxy: ", "wireproxy did not open the proxy: ") + logTail(log));
    }

    // ------------------------------------------------------------ identidad

    private static File warpDir() {
        File dir = new File(app.getFilesDir(), "warp");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static JSONObject loadIdentity() {
        File f = new File(warpDir(), IDENTITY);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            JSONObject o = new JSONObject(new String(readAllBytes(in), StandardCharsets.UTF_8));
            if (o.optString("private_key").isEmpty()
                    || o.optString("peer_public_key").isEmpty()
                    || o.optString("v4").isEmpty()
                    || o.optString("v6").isEmpty()) {
                return null;
            }
            return o;
        } catch (Exception e) {
            Log.w(TAG, "identidad ilegible: " + e.getMessage());
            return null;
        }
    }

    private static void saveIdentity(JSONObject id) throws IOException {
        File dir = warpDir();
        File f = new File(dir, IDENTITY);
        File tmp = new File(dir, IDENTITY + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(id.toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.setReadable(false, false);
        //noinspection ResultOfMethodCallIgnored
        tmp.setReadable(true, true);
        //noinspection ResultOfMethodCallIgnored
        tmp.setWritable(false, false);
        //noinspection ResultOfMethodCallIgnored
        tmp.setWritable(true, true);
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                throw new IOException(msg("no se pudo guardar la identidad", "could not save the identity"));
            }
        }
    }

    private static void deleteIdentity() {
        //noinspection ResultOfMethodCallIgnored
        new File(warpDir(), IDENTITY).delete();
    }

    // ------------------------------------------------------------ IP publica

    /** Pregunta la IP de salida al trace de Cloudflare a traves del tunel. */
    public static String egressIp() {
        return egressIp(proxyPort);
    }

    private static String egressIp(int port) {
        if (port <= 0) return null;
        HttpURLConnection c = null;
        try {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            c = (HttpURLConnection) new URL(TRACE).openConnection(proxy);
            c.setConnectTimeout(20000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "opencode-android-warp");
            String ip = null;
            String warp = null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(),
                            StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("ip=")) ip = line.substring(3).trim();
                    else if (line.startsWith("warp=")) warp = line.substring(5).trim();
                }
            }
            if (ip == null) return null;
            Log.i(TAG, "salida WARP: " + ip + " warp=" + warp);
            return ip;
        } catch (Exception e) {
            Log.w(TAG, "trace: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------ utilidades

    private static String logTail() {
        return logTail(new File(warpDir(), LOG));
    }

    private static String logTail(File f) {
        try {
            if (!f.isFile()) return msg("(sin log)", "(no log)");
            long len = f.length();
            try (FileInputStream in = new FileInputStream(f)) {
                long skip = Math.max(0, len - 800);
                //noinspection ResultOfMethodCallIgnored
                in.skip(skip);
                return new String(readAllBytes(in), StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            return msg("(log ilegible)", "(unreadable log)");
        }
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream i = in) {
            return new String(readAllBytes(i), StandardCharsets.UTF_8);
        }
    }

    private static String shorten(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }

    // ------------------------------------------------------------ X25519

    private static final BigInteger P =
            BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);

    private static byte[] basePoint() {
        byte[] b = new byte[32];
        b[0] = 9;
        return b;
    }

    private static void clamp(byte[] k) {
        k[0] &= (byte) 248;
        k[31] &= (byte) 127;
        k[31] |= (byte) 64;
    }

    /** X25519 en Java puro (Montgomery ladder), valido desde API 26. */
    private static byte[] x25519(byte[] scalar, byte[] u) {
        BigInteger x1 = decodeLE(u);
        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;

        for (int t = 254; t >= 0; t--) {
            int kt = (scalar[t >> 3] >> (t & 7)) & 1;
            swap ^= kt;
            if (swap != 0) {
                BigInteger tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);

            BigInteger t1 = da.add(cb).mod(P);
            x3 = t1.multiply(t1).mod(P);
            BigInteger t2 = da.subtract(cb).mod(P);
            z3 = x1.multiply(t2.multiply(t2).mod(P)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e)).mod(P)).mod(P);
        }

        if (swap != 0) {
            BigInteger tmp = x2;
            x2 = x3;
            x3 = tmp;
            tmp = z2;
            z2 = z3;
            z3 = tmp;
        }

        BigInteger inv = z2.modPow(P.subtract(BigInteger.valueOf(2)), P);
        return encodeLE(x2.multiply(inv).mod(P));
    }

    private static BigInteger decodeLE(byte[] b) {
        byte[] r = new byte[b.length];
        for (int i = 0; i < b.length; i++) r[i] = b[b.length - 1 - i];
        return new BigInteger(1, r);
    }

    private static byte[] encodeLE(BigInteger v) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < b.length; i++) {
            out[i] = b[b.length - 1 - i];
        }
        return out;
    }
}
