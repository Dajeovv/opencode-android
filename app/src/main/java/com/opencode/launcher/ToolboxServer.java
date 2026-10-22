package com.opencode.launcher;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servidor local minimo (127.0.0.1) para que las herramientas del sandbox
 * puedan pedir paquetes: el shim "pkg" llama a /install por HTTP.
 * No escucha fuera del loopback y no acepta nada que no sea una lista de
 * nombres de paquete.
 */
public final class ToolboxServer {
    private static final String TAG = "OpenCodeToolbox";
    public static final int PORT = 4199;

    private static ServerSocket socket;
    private static Thread thread;
    private static Context appContext;

    private ToolboxServer() {
    }

    public static synchronized void start(Context ctx) {
        if (socket != null) return;
        appContext = ctx.getApplicationContext();
        try {
            socket = new ServerSocket(PORT, 8, java.net.InetAddress.getByName("127.0.0.1"));
        } catch (Exception e) {
            Log.w(TAG, "toolbox server no arranca: " + e);
            socket = null;
            return;
        }
        thread = new Thread(() -> {
            while (true) {
                Socket s;
                try {
                    s = socket.accept();
                } catch (Exception e) {
                    return;
                }
                new Thread(() -> handle(s), "toolbox-http").start();
            }
        }, "toolbox-server");
        thread.setDaemon(true);
        thread.start();
        Log.i(TAG, "toolbox server en 127.0.0.1:" + PORT);
    }

    public static synchronized void stop() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
    }

    private static void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            String path = parts.length > 1 ? parts[1] : "/";
            int contentLength = 0;
            String body = "";
            String h;
            while ((h = r.readLine()) != null && !h.isEmpty()) {
                if (h.toLowerCase(java.util.Locale.US).startsWith("content-length:")) {
                    try {
                        contentLength = Integer.parseInt(h.substring(15).trim());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (contentLength > 0) {
                char[] buf = new char[contentLength];
                int off = 0;
                while (off < buf.length) {
                    int n = r.read(buf, off, buf.length - off);
                    if (n < 0) break;
                    off += n;
                }
                body = new String(buf, 0, off);
            }
            String json = respond(path, body);
            byte[] out = ("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n"
                    + "Content-Length: " + json.getBytes(StandardCharsets.UTF_8).length
                    + "\r\nConnection: close\r\n\r\n" + json).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = sock.getOutputStream()) {
                os.write(out);
                os.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private static String respond(String path, String body) {
        Context ctx = appContext;
        if (ctx == null) return "{\"ok\":false,\"error\":\"no app\"}";
        Map<String, String> q = parseQuery(body);
        if (path.startsWith("/status")) {
            StringBuilder sb = new StringBuilder("{\"ok\":true,\"installed\":[");
            List<String> list = LocalToolbox.installedPackages(ctx);
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(list.get(i)).append('"');
            }
            return sb.append("]}").toString();
        }
        // Ejecutar un comando en el sandbox: pide el token, que solo existe
        // dentro del sandbox (variable OPENCODE_TOOLBOX_TOKEN).
        if (path.startsWith("/run")) {
            if (!authorized(ctx, q)) return "{\"ok\":false,\"error\":\"denied\"}";
            String cmd = urlDecode(q.getOrDefault("cmd", "")).trim();
            if (cmd.isEmpty()) return "{\"ok\":false,\"error\":\"no cmd\"}";
            String out = LocalRuntime.runInSandbox(ctx, cmd, 60000);
            return "{\"ok\":true,\"out\":" + jsonString(out) + "}";
        }
        if (path.startsWith("/install")) {
            if (!authorized(ctx, q)) return "{\"ok\":false,\"error\":\"denied\"}";
            List<String> names = new ArrayList<>();
            for (String n : urlDecode(q.getOrDefault("pkgs", "")).split("[\\s,]+")) {
                if (n.isEmpty()) continue;
                if (!n.matches("[A-Za-z0-9+._-]+")) continue;
                names.add(n);
            }
            if (names.isEmpty()) return "{\"ok\":false,\"error\":\"no packages\"}";
            ToolboxService.install(ctx, names);
            return "{\"ok\":true,\"queued\":" + names.size() + "}";
        }
        return "{\"ok\":false,\"error\":\"not found\"}";
    }

    /** Parsea "a=1&b=2" en un mapa (los valores van sin decodificar). */
    private static Map<String, String> parseQuery(String body) {
        Map<String, String> out = new HashMap<>();
        if (body == null) return out;
        for (String part : body.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            out.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return out;
    }

    /** El token es la contraseña del servidor local: solo la sabe el sandbox. */
    private static boolean authorized(Context ctx, Map<String, String> q) {
        String token = LocalRuntime.serverPassword(ctx);
        return token != null && token.equals(q.get("token"));
    }

    private static String urlDecode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
