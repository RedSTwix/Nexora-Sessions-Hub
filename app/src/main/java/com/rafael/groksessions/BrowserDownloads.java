package com.rafael.groksessions;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browser downloads must use the triggering WebView's cookies, not the default WebView profile. */
final class BrowserDownloads {
    private static final int STORAGE_PERMISSION_REQUEST = 0x6601;
    private final Activity activity;
    private final BlobDownloads blobs;
    private final ExecutorService metadata = Executors.newSingleThreadExecutor();
    private boolean closed;
    private Pending pending;
    private int sequence;

    BrowserDownloads(Activity activity) {
        this.activity = activity;
        blobs = new BlobDownloads(activity);
    }

    void attach(WebView source) { blobs.attach(source); }
    void cancelFor(WebView source) { blobs.cancelFor(source); }
    void close() { closed = true; blobs.close(); metadata.shutdownNow(); pending = null; }
    boolean onActivityResult(int code, int result, Intent data) { return blobs.onActivityResult(code, result, data); }

    void download(WebView source, String url, String userAgent, String disposition, String mimeType) {
        Uri uri = url == null ? null : Uri.parse(url);
        String scheme = uri == null ? null : uri.getScheme();
        if ("blob".equalsIgnoreCase(scheme)) {
            source.evaluateJavascript("(function(){if(!window.__nexoraSaveBlob)return false;window.__nexoraSaveBlob("
                    + JSONObject.quote(url) + ",\"video\");return true;})()", supported -> {
                if (!"true".equals(supported)) Toast.makeText(activity,
                        "Atualize o Android System WebView para baixar arquivos temporários deste site.", Toast.LENGTH_LONG).show();
            });
            return;
        }
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            Toast.makeText(activity, "Este site usa um tipo de download que o Android não suporta diretamente.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            Toast.makeText(activity, "O endereço do arquivo é inválido.", Toast.LENGTH_LONG).show();
            return;
        }
        Pending request = new Pending(source, url, userAgent, disposition, mimeType);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pending = request;
            activity.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST);
            return;
        }
        enqueue(request);
    }

    void onPermissionResult(int requestCode, int[] grants) {
        if (requestCode != STORAGE_PERMISSION_REQUEST) return;
        Pending requested = pending;
        pending = null;
        if (requested == null) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) {
            enqueue(requested);
        } else {
            Toast.makeText(activity, "Permita acesso ao armazenamento para salvar na pasta Downloads.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void enqueue(Pending pending) {
        if (closed) return;
        try {
            String cookies = WebViewCompat.getProfile(pending.source).getCookieManager().getCookie(pending.url);
            String mime = DownloadFileNames.mediaType(pending.mimeType, pending.url);
            if (mime.equals("application/octet-stream") || mime.equals("binary/octet-stream")) {
                Toast.makeText(activity, "Preparando download…", Toast.LENGTH_SHORT).show();
                metadata.execute(() -> {
                    String detected = DownloadMimeProbe.inspect(pending.url, pending.userAgent, cookies, mime);
                    activity.runOnUiThread(() -> { if (!closed) enqueueResolved(pending, cookies, detected); });
                });
            } else enqueueResolved(pending, cookies, mime);
        } catch (RuntimeException exception) {
            Toast.makeText(activity, "Não foi possível iniciar o download deste arquivo.", Toast.LENGTH_LONG).show();
        }
    }

    private void enqueueResolved(Pending pending, String cookies, String mimeType) {
        try {
            DownloadManager downloads = (DownloadManager)
                    activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloads == null) throw new IllegalStateException("DownloadManager indisponível");
            String filename = uniqueName(DownloadFileNames.choose(pending.url, pending.disposition, mimeType));
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(pending.url))
                    .setTitle(filename)
                    .setDescription("Nexora Sessions Hub")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            request.setMimeType(mimeType);
            if (pending.userAgent != null && !pending.userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", headerValue(pending.userAgent));
            }
            // A profile-specific cookie jar is essential: profiles cannot share login cookies.
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", headerValue(cookies));
            }
            downloads.enqueue(request);
            Toast.makeText(activity, "Download iniciado: " + filename, Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            Toast.makeText(activity, "Não foi possível iniciar o download deste arquivo.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private String uniqueName(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) dot = filename.length();
        String suffix = "-" + System.currentTimeMillis() + "-" + (++sequence);
        return filename.substring(0, dot) + suffix + filename.substring(dot);
    }

    static String safeName(String name) {
        if (name == null) return "arquivo";
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (cleaned.startsWith(".")) cleaned = cleaned.substring(1);
        while (cleaned.endsWith(".")) cleaned = cleaned.substring(0, cleaned.length() - 1);
        if (cleaned.isEmpty()) return "arquivo";
        if (cleaned.length() <= 100) return cleaned;
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0 && cleaned.length() - dot <= 12) {
            return cleaned.substring(0, 100 - cleaned.length() + dot) + cleaned.substring(dot);
        }
        return cleaned.substring(0, 100);
    }

    private static String headerValue(String value) {
        return value.replace("\r", "").replace("\n", "");
    }

    private static final class Pending {
        final WebView source;
        final String url;
        final String userAgent;
        final String disposition;
        final String mimeType;

        Pending(WebView source, String url, String userAgent, String disposition, String mimeType) {
            this.source = source;
            this.url = url;
            this.userAgent = userAgent;
            this.disposition = disposition;
            this.mimeType = mimeType;
        }
    }
}
