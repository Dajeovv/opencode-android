package com.opencode.launcher;

import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Genera la configuracion extra que se carga con OPENCODE_CONFIG_DIR.
 *
 * Solo fija "shell" (el trampolin nativo) y desactiva autoupdate. No define
 * proveedores ni modelo: el proveedor oficial "opencode" ya trae los modelos
 * free y rota la lista, y el usuario elige el suyo en la UI.
 *
 * El archivo se mantiene separado de opencode.json/opencode.jsonc para no
 * pisar la configuracion del usuario. Solo se pone "shell" si el usuario no
 * ha elegido ya uno.
 */
public final class NativeRuntimeConfig {
    private static final String TAG = "OpenCodeLocal";

    public static final String FILE_NAME = "opencode.json";

    private static final Pattern SHELL_KEY =
            Pattern.compile("(?m)^\\s*\"shell\"\\s*:");

    private NativeRuntimeConfig() {
    }

    /**
     * Escribe la configuracion del shell en {@code destDir}, que el servidor
     * recibe como OPENCODE_CONFIG_DIR. El directorio va separado del global
     * (OPENCODE_CONFIG/OPENCODE_CONFIG_CONTENT no se aplican en "serve"
     * 1.19.x) y se mezcla al final, por eso solo se pone "shell" si el
     * usuario no ha elegido ya uno.
     */
    public static File write(File destDir, File userConfigDir, String shellPath)
            throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("no se pudo crear " + destDir);
        }
        boolean defaultShell = shellPath != null && !shellPath.isEmpty()
                && !hasUserShell(userConfigDir);
        byte[] body = build(defaultShell ? shellPath : null)
                .getBytes(StandardCharsets.UTF_8);
        File dest = new File(destDir, FILE_NAME);
        File tmp = new File(destDir, FILE_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(body);
            out.getFD().sync();
        }
        if (!tmp.renameTo(dest)) {
            try (FileOutputStream out = new FileOutputStream(dest)) {
                out.write(body);
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        dest.setReadable(true, false);
        Log.i(TAG, "config nativa escrita en " + dest + " (shell por defecto="
                + defaultShell + ")");
        return dest;
    }

    private static String build(String shellPath) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"$schema\": \"https://opencode.ai/config.json\",\n");
        sb.append("  \"autoupdate\": false");
        if (shellPath != null) {
            sb.append(",\n  \"shell\": ").append(JSONObject.quote(shellPath));
        }
        sb.append("\n}\n");
        return sb.toString();
    }

    /**
     * true si el usuario ya eligio shell en alguna de las configuraciones
     * globales. En ese caso no se inyecta "shell" para no imponer el nuestro.
     */
    private static boolean hasUserShell(File configDir) {
        String[] names = {"config.json", "opencode.json", "opencode.jsonc"};
        for (String name : names) {
            File file = new File(configDir, name);
            if (!file.isFile()) continue;
            String text = read(file);
            if (text == null || text.trim().isEmpty()) continue;
            try {
                JSONObject json = new JSONObject(stripJsonc(text));
                if (json.has("shell")) return true;
            } catch (Exception e) {
                if (SHELL_KEY.matcher(text).find()) return true;
            }
        }
        return false;
    }

    private static String read(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), 512 * 1024)];
            int off = 0;
            int n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) {
                off += n;
            }
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /** Quita comentarios y comas finales para poder parsear JSONC con org.json. */
    private static String stripJsonc(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean string = false;
        boolean escape = false;
        boolean line = false;
        boolean block = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (line) {
                if (c == '\n') {
                    line = false;
                    out.append(c);
                }
                continue;
            }
            if (block) {
                if (c == '*' && next == '/') {
                    block = false;
                    i++;
                }
                continue;
            }
            if (string) {
                out.append(c);
                if (escape) {
                    escape = false;
                } else if (c == '\\') {
                    escape = true;
                } else if (c == '"') {
                    string = false;
                }
                continue;
            }
            if (c == '"') {
                string = true;
                out.append(c);
                continue;
            }
            if (c == '/' && next == '/') {
                line = true;
                i++;
                continue;
            }
            if (c == '/' && next == '*') {
                block = true;
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().replaceAll(",\\s*([}\\]])", "$1");
    }
}
