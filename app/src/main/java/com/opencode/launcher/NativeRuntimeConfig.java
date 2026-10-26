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
 * Genera la configuracion extra que se carga con OPENCODE_CONFIG.
 *
 * El servidor standalone 1.19.x no ejecuta modelos cuyo api.type sea "native".
 * Para no parchear el binario, se expone el mismo endpoint como proveedor
 * OpenAI-compatible (aisdk), que si funciona en Android.
 *
 * El archivo se mantiene separado de opencode.json/opencode.jsonc para no
 * pisar la configuracion del usuario. Solo se pone "model" por defecto si el
 * usuario no ha elegido ya uno.
 */
public final class NativeRuntimeConfig {
    private static final String TAG = "OpenCodeLocal";

    public static final String FILE_NAME = "opencode.json";
    public static final String PROVIDER_ID = "opencode-aisdk";
    public static final String MODEL_ID = "deepseek-v4.1-flash-free";
    public static final String DEFAULT_MODEL = PROVIDER_ID + "/" + MODEL_ID;

    private static final String API_URL = "https://opencode.ai/inference/openai/v1";
    private static final Pattern MODEL_KEY =
            Pattern.compile("(?m)^\\s*\"model\"\\s*:");
    private static final Pattern SHELL_KEY =
            Pattern.compile("(?m)^\\s*\"shell\"\\s*:");

    private NativeRuntimeConfig() {
    }

    /**
     * Escribe la configuracion del proveedor y del shell en {@code destDir},
     * que el servidor recibe como OPENCODE_CONFIG_DIR. El directorio va
     * separado del global (OPENCODE_CONFIG/OPENCODE_CONFIG_CONTENT no se
     * aplican en "serve" 1.19.x) y se mezcla al final, por eso solo se pone
     * "model" o "shell" si el usuario no ha elegido ya uno.
     */
    public static File write(File destDir, File userConfigDir, String shellPath)
            throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs() && !destDir.isDirectory()) {
            throw new IOException("no se pudo crear " + destDir);
        }
        boolean defaultModel = !hasUserModel(userConfigDir);
        boolean defaultShell = shellPath != null && !shellPath.isEmpty()
                && !hasUserShell(userConfigDir);
        byte[] body = build(defaultModel, defaultShell ? shellPath : null)
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
        Log.i(TAG, "config nativa escrita en " + dest + " (modelo por defecto="
                + defaultModel + ", shell por defecto=" + defaultShell + ")");
        return dest;
    }

    private static String build(boolean withDefaultModel, String shellPath) {
        return "{\n"
                + "  \"$schema\": \"https://opencode.ai/config.json\",\n"
                + "  \"autoupdate\": false,\n"
                + (withDefaultModel
                        ? "  \"model\": \"" + DEFAULT_MODEL + "\",\n"
                        : "")
                + (shellPath != null
                        ? "  \"shell\": " + JSONObject.quote(shellPath) + ",\n"
                        : "")
                + "  \"provider\": {\n"
                + "    \"" + PROVIDER_ID + "\": {\n"
                + "      \"name\": \"OpenCode AISDK\",\n"
                + "      \"npm\": \"@ai-sdk/openai-compatible\",\n"
                + "      \"api\": \"" + API_URL + "\",\n"
                + "      \"options\": {\n"
                + "        \"apiKey\": \"public\",\n"
                + "        \"baseURL\": \"" + API_URL + "\"\n"
                + "      },\n"
                + "      \"models\": {\n"
                + "        \"" + MODEL_ID + "\": {\n"
                + "          \"id\": \"" + MODEL_ID + "\",\n"
                + "          \"name\": \"DeepSeek V4.1 Flash Free\",\n"
                + "          \"tool_call\": true,\n"
                + "          \"reasoning\": true,\n"
                + "          \"attachment\": true,\n"
                + "          \"temperature\": true,\n"
                + "          \"modalities\": {\n"
                + "            \"input\": [\"text\", \"image\"],\n"
                + "            \"output\": [\"text\"]\n"
                + "          },\n"
                + "          \"limit\": {\n"
                + "            \"context\": 200000,\n"
                + "            \"output\": 32000\n"
                + "          },\n"
                + "          \"provider\": {\n"
                + "            \"npm\": \"@ai-sdk/openai-compatible\",\n"
                + "            \"api\": \"" + API_URL + "\"\n"
                + "          }\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}\n";
    }

    /**
     * true si el usuario ya eligio modelo en alguna de las configuraciones
     * globales. En ese caso no se inyecta "model" para no imponer el nuestro.
     */
    private static boolean hasUserModel(File configDir) {
        String[] names = {"config.json", "opencode.json", "opencode.jsonc"};
        for (String name : names) {
            File file = new File(configDir, name);
            if (!file.isFile()) continue;
            String text = read(file);
            if (text == null || text.trim().isEmpty()) continue;
            try {
                JSONObject json = new JSONObject(stripJsonc(text));
                if (json.has("model")) return true;
            } catch (Exception e) {
                if (MODEL_KEY.matcher(text).find()) return true;
            }
        }
        return false;
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
