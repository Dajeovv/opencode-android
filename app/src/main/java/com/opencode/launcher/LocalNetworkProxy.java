package com.opencode.launcher;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proxy HTTP CONNECT minimo, solo loopback, para que los binarios musl del
 * runtime nativo resuelvan DNS con Android (netd) en lugar de intentar hablar
 * directamente con /etc/resolv.conf (que no existe fuera de proot).
 *
 * No descifra TLS: solo abre el tunel TCP y reenvia bytes.
 */
public final class LocalNetworkProxy {
    private static final String TAG = "OpenCodeLocal";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int HEADER_TIMEOUT_MS = 20000;
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    private static final Object LOCK = new Object();
    private static final Set<Socket> SOCKETS =
            Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());

    private static ServerSocket server;
    private static Thread acceptThread;
    private static int port;
    private static volatile boolean running;

    private LocalNetworkProxy() {
    }

    /** Arranca en 127.0.0.1 con un puerto libre y devuelve ese puerto. */
    public static int start() throws IOException {
        synchronized (LOCK) {
            if (running && server != null && !server.isClosed()) {
                return port;
            }
            closeQuietly(server);
            server = null;
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 64);
            server = ss;
            port = ss.getLocalPort();
            running = true;
            Thread t = new Thread(() -> acceptLoop(ss), "oc-proxy-accept");
            t.setDaemon(true);
            acceptThread = t;
            t.start();
            Log.i(TAG, "proxy CONNECT en 127.0.0.1:" + port);
            return port;
        }
    }

    public static boolean running() {
        synchronized (LOCK) {
            return running;
        }
    }

    public static int port() {
        synchronized (LOCK) {
            return port;
        }
    }

    public static void stop() {
        ServerSocket ss;
        Thread t;
        synchronized (LOCK) {
            running = false;
            ss = server;
            server = null;
            t = acceptThread;
            acceptThread = null;
            port = 0;
        }
        closeQuietly(ss);
        Socket[] open = SOCKETS.toArray(new Socket[0]);
        for (Socket s : open) {
            closeQuietly(s);
            untrack(s);
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Log.i(TAG, "proxy CONNECT detenido");
    }

    private static void acceptLoop(ServerSocket ss) {
        while (running) {
            try {
                Socket client = ss.accept();
                track(client);
                Thread t = new Thread(() -> {
                    try {
                        handle(client);
                    } catch (Throwable e) {
                        // Una conexion mala no puede tumbar la app entera.
                        Log.w(TAG, "proxy conexion: " + e);
                        closeQuietly(client);
                    }
                }, "oc-proxy-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) Log.w(TAG, "proxy accept: " + e.getMessage());
            }
        }
    }

    private static void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(HEADER_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            String requestLine = readLine(in, 8192);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.trim().split("\\s+");
            if (parts.length < 2) {
                error(client, 400, "Bad Request");
                return;
            }
            String method = parts[0];
            String target = parts[1];
            int headerBytes = 0;
            List<String> headers = new ArrayList<>();
            while (true) {
                String line = readLine(in, 8192);
                if (line == null) return;
                if (line.isEmpty()) break;
                headerBytes += line.length();
                if (headerBytes > MAX_HEADER_BYTES) {
                    error(client, 431, "Request Header Fields Too Large");
                    return;
                }
                headers.add(line);
            }

            if ("CONNECT".equalsIgnoreCase(method)) {
                int colon = target.lastIndexOf(':');
                if (colon <= 0 || colon == target.length() - 1) {
                    error(client, 400, "Bad Request");
                    return;
                }
                String host = target.substring(0, colon).trim();
                int targetPort;
                try {
                    targetPort = Integer.parseInt(target.substring(colon + 1).trim());
                } catch (NumberFormatException e) {
                    error(client, 400, "Bad Request");
                    return;
                }
                if (host.isEmpty() || targetPort < 1 || targetPort > 65535) {
                    error(client, 400, "Bad Request");
                    return;
                }
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }

                try {
                    upstream = connect(host, targetPort);
                } catch (IOException e) {
                    Log.d(TAG, "proxy destino " + host + ":" + targetPort + ": " + e.getMessage());
                    error(client, 502, "Bad Gateway");
                    return;
                }
                track(upstream);
                upstream.setTcpNoDelay(true);

                OutputStream out = client.getOutputStream();
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                out.flush();

                client.setSoTimeout(0);
                upstream.setSoTimeout(0);
                final Socket up = upstream;
                startPipe(client, up, "oc-proxy-c2s");
                pipe(up.getInputStream(), out);
                return;
            }

            // HTTP normal (sin CONNECT): curl/wget con http_proxy mandan la URL
            // absoluta. Se reenvia al destino cambiando solo la linea de
            // peticion; antes se respondia 405 y el diagnostico de red fallaba.
            URI uri;
            try {
                uri = new URI(target);
            } catch (URISyntaxException e) {
                error(client, 400, "Bad Request");
                return;
            }
            if (!"http".equalsIgnoreCase(uri.getScheme())) {
                error(client, 400, "Bad Request");
                return;
            }
            String host = uri.getHost();
            if (host == null || host.isEmpty()) {
                error(client, 400, "Bad Request");
                return;
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            int targetPort = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();

            try {
                upstream = connect(host, targetPort);
            } catch (IOException e) {
                Log.d(TAG, "proxy destino " + host + ":" + targetPort + ": " + e.getMessage());
                error(client, 502, "Bad Gateway");
                return;
            }
            track(upstream);
            upstream.setTcpNoDelay(true);

            StringBuilder req = new StringBuilder(256);
            req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            boolean hasHost = false;
            for (String h : headers) {
                String lower = h.toLowerCase(Locale.ROOT);
                if (lower.startsWith("proxy-connection:")) continue;
                if (lower.startsWith("connection:")) continue;
                if (lower.startsWith("host:")) hasHost = true;
                req.append(h).append("\r\n");
            }
            if (!hasHost) req.append("Host: ").append(host).append("\r\n");
            req.append("Connection: close\r\n\r\n");
            OutputStream upOut = upstream.getOutputStream();
            upOut.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
            upOut.flush();

            client.setSoTimeout(0);
            upstream.setSoTimeout(0);
            final Socket up = upstream;
            startPipe(client, up, "oc-proxy-c2s");
            pipe(up.getInputStream(), client.getOutputStream());
        } catch (Exception e) {
            Log.d(TAG, "proxy conexion: " + e.getMessage());
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
            untrack(client);
            untrack(upstream);
        }
    }

    /** Copia cliente -> destino y cierra el destino al terminar. */
    private static void startPipe(Socket client, Socket up, String name) {
        Thread c2s = new Thread(() -> {
            try {
                pipe(client.getInputStream(), up.getOutputStream());
            } catch (IOException ignored) {
            } finally {
                // Al cerrar un sentido se cierra el otro para desbloquear
                // el hilo principal que esta copiando en direccion contraria.
                closeQuietly(up);
            }
        }, name);
        c2s.setDaemon(true);
        c2s.start();
    }

    private static Socket connect(String host, int targetPort) throws IOException {
        IOException last = null;
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(address, targetPort), CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                last = e;
                closeQuietly(s);
            }
        }
        throw last != null ? last : new IOException("sin direccion para " + host);
    }

    private static void pipe(InputStream in, OutputStream out) {
        byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in, int max) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') sb.append((char) (b & 0xff));
            if (sb.length() > max) throw new IOException("cabecera demasiado larga");
        }
        if (b == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private static void error(Socket socket, int code, String reason) {
        try {
            OutputStream out = socket.getOutputStream();
            String body = code + " " + reason;
            byte[] bytes = ("HTTP/1.1 " + code + " " + reason + "\r\n"
                    + "Content-Type: text/plain; charset=us-ascii\r\n"
                    + "Content-Length: " + body.length() + "\r\n"
                    + "Connection: close\r\n\r\n"
                    + body).getBytes(StandardCharsets.US_ASCII);
            out.write(bytes);
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static void track(Socket socket) {
        if (socket == null) return;
        SOCKETS.add(socket);
        if (!running) {
            untrack(socket);
            closeQuietly(socket);
        }
    }

    private static void untrack(Socket socket) {
        if (socket != null) SOCKETS.remove(socket);
    }

    private static void closeQuietly(ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
