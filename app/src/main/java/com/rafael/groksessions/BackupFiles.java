package com.rafael.groksessions;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;
import android.util.AtomicFile;

final class BackupFiles {
    private BackupFiles() {}
    static void mkdir(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Não foi possível criar a pasta de trabalho.");
    }
    static byte[] read(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (count > limit - output.size()) throw new IOException("O arquivo ultrapassa o limite permitido.");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
    static JSONObject readJson(File file) throws IOException {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(new String(read(input, 32 * 1024 * 1024), StandardCharsets.UTF_8));
        } catch (JSONException exception) { throw new IOException("Arquivo de dados inválido.", exception); }
    }
    static void writeJson(File file, JSONObject value) throws IOException {
        mkdir(file.getParentFile());
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(output);
        } catch (IOException exception) {
            if (output != null) atomic.failWrite(output);
            throw exception;
        }
    }
    static void move(File source, File target) throws IOException {
        if (target.exists() || !source.renameTo(target)) throw new IOException("Não foi possível mover os dados do navegador.");
    }
    static void delete(File file) throws IOException {
        // A symlink is deleted as a link; its target is never traversed.
        if (file.isDirectory() && !isLink(file)) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Não foi possível ler a pasta temporária.");
            for (File child : children) delete(child);
        }
        if (file.exists() && !file.delete()) throw new IOException("Não foi possível remover os arquivos temporários.");
    }
    static boolean isLink(File file) throws IOException {
        File resolvedParent = file.getParentFile().getCanonicalFile();
        return !file.getCanonicalFile().equals(new File(resolvedParent, file.getName()).getAbsoluteFile());
    }
    static boolean jsonExists(File file) { return file.exists() || new File(file.getPath() + ".bak").exists(); }
}
