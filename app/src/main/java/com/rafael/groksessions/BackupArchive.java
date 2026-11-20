package com.rafael.groksessions;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Plain ZIP with persistent app settings and a cold copy of the entire WebView data directory. */
final class BackupArchive {
    private static final String RELEASE_APP_ID = "com.rafael.groksessions";
    static final long MAX_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int MAX_FILES = 100000;
    interface Progress { void update(long bytes, int files) throws IOException; }
    private BackupArchive() {}

    static void write(OutputStream output, JSONObject settings, JSONObject info, File webview, File preferences, Progress progress) throws IOException {
        BackupSettings.validate(settings);
        List<File> files = new ArrayList<>();
        List<File> directories = new ArrayList<>();
        File root = webview.getCanonicalFile();
        if (root.isDirectory()) collect(root, files, directories, 0);
        List<File> nativePreferences = new ArrayList<>();
        if (preferences.isDirectory()) {
            File[] candidates = preferences.getCanonicalFile().listFiles();
            if (candidates == null) throw new IOException("Não foi possível ler as configurações do WebView.");
            for (File candidate : candidates)
                if (candidate.isFile() && !BackupFiles.isLink(candidate) && !isAppPreference(candidate.getName())
                        && (candidate.getName().endsWith(".xml") || candidate.getName().endsWith(".xml.bak"))) nativePreferences.add(candidate);
        }
        int webviewFiles = files.size();
        files.addAll(nativePreferences);
        if (files.size() + directories.size() > MAX_FILES) throw new IOException("Há arquivos demais para este backup.");
        long total = 0;
        JSONArray inventory = new JSONArray();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            info.put("format", 1).put("has_webview", root.isDirectory() || !nativePreferences.isEmpty());
            put(zip, "info.json", info.toString().getBytes(StandardCharsets.UTF_8));
            put(zip, "settings.json", settings.toString().getBytes(StandardCharsets.UTF_8));
            for (File directory : directories) {
                String relative = directory.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace(File.separatorChar, '/');
                validateName("webview/" + relative + "/");
                zip.putNextEntry(new ZipEntry("webview/" + relative + "/")); zip.closeEntry();
            }
            byte[] buffer = new byte[65536];
            int done = 0;
            for (File file : files) {
                long originalSize = file.length(), originalTime = file.lastModified();
                String path = done < webviewFiles ? "webview/" + file.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace(File.separatorChar, '/')
                        : "webview_prefs/" + file.getName();
                ZipEntry entry = new ZipEntry(path);
                validateName(path);
                entry.setTime(file.lastModified());
                zip.putNextEntry(entry);
                MessageDigest digest = digest();
                long size = 0;
                try (InputStream input = new FileInputStream(file)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        total += count; size += count;
                        if (total > MAX_BYTES) throw new IOException("O backup ultrapassa 8 GB de dados.");
                        zip.write(buffer, 0, count);
                        digest.update(buffer, 0, count);
                        progress.update(total, done);
                    }
                }
                if (file.length() != originalSize || file.lastModified() != originalTime || size != originalSize)
                    throw new IOException("Os dados do navegador mudaram durante a cópia. Tente novamente.");
                zip.closeEntry();
                inventory.put(new JSONObject().put("path", entry.getName()).put("size", size).put("sha256", hex(digest.digest())));
                progress.update(total, ++done);
            }
            put(zip, "inventory.json", inventory.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException exception) { throw new IOException("Não foi possível preparar o backup.", exception); }
    }

    static boolean isAppPreference(String name) {
        return name.startsWith("account_manager.xml") || name.startsWith("app_updates.xml") || name.startsWith("encrypted_auth_sessions.xml");
    }

    private static void collect(File directory, List<File> result, List<File> directories, int depth) throws IOException {
        if (depth > 128) throw new IOException("A estrutura dos dados é profunda demais.");
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Não foi possível ler os dados do WebView.");
        for (File file : files) {
            if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) continue;
            String name = file.getName();
            if (name.equals("webview_data.lock") || name.startsWith("Singleton")) continue;
            if (file.isDirectory()) { directories.add(file); collect(file, result, directories, depth + 1); }
            else if (file.isFile()) result.add(file);
            if (result.size() + directories.size() > MAX_FILES) throw new IOException("Há arquivos demais para este backup.");
        }
    }

    static Restored read(InputStream input, File workspace, String appId, int versionCode, Progress progress) throws IOException {
        BackupFiles.mkdir(workspace);
        File stage = new File(workspace, "import-" + UUID.randomUUID());
        BackupFiles.mkdir(stage);
        File webview = new File(stage, "webview");
        BackupFiles.mkdir(webview);
        BackupFiles.mkdir(new File(stage, "webview_prefs"));
        JSONObject info = null, settings = null;
        JSONArray inventory = null;
        JSONObject observed = new JSONObject();
        Set<String> names = new HashSet<>();
        long total = 0;
        int files = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            byte[] buffer = new byte[65536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (!names.add(name) || names.size() > MAX_FILES + 10) throw new IOException("ZIP com entradas duplicadas ou excessivas.");
                if (name.equals("info.json")) info = json(zip, 1024 * 1024);
                else if (name.equals("settings.json")) settings = json(zip, 32 * 1024 * 1024);
                else if (name.equals("inventory.json")) inventory = new JSONArray(new String(BackupFiles.read(zip, 32 * 1024 * 1024), StandardCharsets.UTF_8));
                else if (name.startsWith("webview/") || name.startsWith("webview_prefs/")) {
                    if (name.startsWith("webview_prefs/")) {
                        String preference = name.substring("webview_prefs/".length());
                        if (preference.contains("/") || isAppPreference(preference) || !(preference.endsWith(".xml") || preference.endsWith(".xml.bak")))
                            throw new IOException("Configuração de navegador inválida no ZIP.");
                    }
                    File target = safeTarget(stage, name);
                    if (entry.isDirectory()) { BackupFiles.mkdir(target); continue; }
                    if (++files > MAX_FILES) throw new IOException("Arquivos demais no ZIP.");
                    BackupFiles.mkdir(target.getParentFile());
                    MessageDigest digest = digest();
                    long size = 0;
                    try (OutputStream output = new FileOutputStream(target)) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) {
                            size += count; total += count;
                            if (total > MAX_BYTES) throw new IOException("O ZIP ultrapassa 8 GB de dados extraídos.");
                            output.write(buffer, 0, count); digest.update(buffer, 0, count);
                            progress.update(total, files);
                        }
                    }
                    if (entry.getTime() > 0) target.setLastModified(entry.getTime());
                    observed.put(name, new JSONObject().put("size", size).put("sha256", hex(digest.digest())));
                } else throw new IOException("Este ZIP não é um backup do Nexora.");
                zip.closeEntry();
            }
            if (info == null || settings == null || inventory == null || info.getInt("format") != 1 || !info.has("has_webview")
                    || !acceptsAppId(appId, info.getString("app_id"))) throw new IOException("Formato de backup incompatível.");
            if (!info.getBoolean("has_webview") && observed.length() > 0) throw new IOException("Metadados inconsistentes no backup.");
            if (info.getInt("version_code") > versionCode) throw new IOException("Atualize o Nexora antes de restaurar este backup.");
            BackupSettings.validate(settings);
            if (inventory.length() != observed.length()) throw new IOException("O backup está incompleto.");
            Set<String> indexed = new HashSet<>();
            for (int i = 0; i < inventory.length(); i++) {
                JSONObject expected = inventory.getJSONObject(i);
                String name = expected.getString("path");
                if (!indexed.add(name)) throw new IOException("Inventário duplicado.");
                JSONObject actual = observed.getJSONObject(name);
                if (expected.getLong("size") != actual.getLong("size") || !expected.getString("sha256").equals(actual.getString("sha256")))
                    throw new IOException("O backup está corrompido ou incompleto.");
            }
            return new Restored(stage, webview, info, settings);
        } catch (IOException | JSONException | RuntimeException exception) {
            try { BackupFiles.delete(stage); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
            if (exception instanceof IOException) throw (IOException) exception;
            throw new IOException("O ZIP contém dados inválidos.", exception);
        }
    }

    private static boolean acceptsAppId(String target, String source) {
        // Official and Dev share the same archive schema. Keep this allowlist
        // exact; similarly named applications are not compatible variants.
        return target.equals(source) || (isNexoraVariant(target) && isNexoraVariant(source));
    }

    private static boolean isNexoraVariant(String appId) {
        return RELEASE_APP_ID.equals(appId) || (RELEASE_APP_ID + ".debug").equals(appId);
    }

    private static File safeTarget(File stage, String name) throws IOException {
        validateName(name);
        File target = new File(stage, name);
        if (!target.getCanonicalPath().startsWith(stage.getCanonicalPath() + File.separator)) throw new IOException("Caminho fora do backup.");
        return target;
    }
    private static void validateName(String name) throws IOException {
        if (name.length() > 2048 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw new IOException("Caminho inválido no ZIP.");
        String[] parts = name.split("/");
        if (parts.length > 130) throw new IOException("Estrutura profunda demais no ZIP.");
        for (String part : parts)
            if (part.isEmpty() || part.equals(".") || part.equals("..") || part.contains(":")) throw new IOException("Caminho inválido no ZIP.");
    }
    private static JSONObject json(InputStream input, int limit) throws IOException, JSONException {
        return new JSONObject(new String(BackupFiles.read(input, limit), StandardCharsets.UTF_8));
    }
    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name)); zip.write(bytes); zip.closeEntry();
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException exception) { throw new AssertionError(exception); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder();
        for (byte item : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
        return value.toString();
    }
    static final class Restored {
        final File stage, webview;
        final JSONObject info, settings;
        Restored(File stage, File webview, JSONObject info, JSONObject settings) {
            this.stage = stage; this.webview = webview; this.info = info; this.settings = settings;
        }
    }
}
