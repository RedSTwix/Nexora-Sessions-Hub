package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Receives page-owned blobs; the bridge cannot read app files, cookies or arbitrary URLs. */
final class BlobDownloads {
    private static final int FIRST_DOCUMENT = 0x6700, LAST_DOCUMENT = 0x67ff;
    private static final long MAX_SIZE = 1024L * 1024 * 1024;
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService writer = Executors.newSingleThreadExecutor();
    private final Runnable timeout = () -> cancel("O download demorou demais. Tente novamente.");
    private Transfer active;
    private boolean closed;
    private int documentRequest = FIRST_DOCUMENT;

    BlobDownloads(Activity activity) { this.activity = activity; }

    @SuppressLint("RequiresFeature")
    void attach(WebView view) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
                || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try (InputStream input = activity.getResources().openRawResource(R.raw.browser_downloads);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            WebViewCompat.addWebMessageListener(view, "__nexoraDownloads", Collections.singleton("*"),
                    (source, message, origin, mainFrame, reply) -> {
                        if (!mainFrame || !isWebOrigin(origin) || closed
                                || message.getType() != WebMessageCompat.TYPE_STRING) return;
                        receive(source, message.getData(), origin.toString(), reply);
                    });
            WebViewCompat.addDocumentStartJavaScript(view,
                    new String(output.toByteArray(), StandardCharsets.UTF_8), Collections.singleton("*"));
        } catch (IOException | RuntimeException exception) {
            // HTTP(S) downloads remain available when this feature is unsupported.
        }
    }

    private static boolean isWebOrigin(Uri origin) {
        return origin != null && ("https".equals(origin.getScheme()) || "http".equals(origin.getScheme()));
    }

    private void receive(WebView source, String raw, String origin, JavaScriptReplyProxy reply) {
        if (raw == null || raw.length() > 90000) return;
        try {
            JSONObject message = new JSONObject(raw);
            String id = message.getString("id");
            if (id.isEmpty() || id.length() > 100) return;
            String type = message.getString("type");
            if ("error".equals(type) && active == null && message.optBoolean("reading")) {
                toast("Não foi possível ler o arquivo temporário. Tente baixar novamente.");
                return;
            }
            if ("begin".equals(type)) {
                if (active != null) { respond(reply, id, "", "Já existe um download em andamento."); return; }
                long size = message.getLong("size");
                if (size < 0 || size > MAX_SIZE) {
                    respond(reply, id, "", "O limite por arquivo é de 1 GB.");
                    toast("O limite por arquivo é de 1 GB.");
                    return;
                }
                String mime = message.optString("mime", "application/octet-stream");
                if (!mime.matches("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) mime = "application/octet-stream";
                String name = DownloadFileNames.correctExtension(
                        BrowserDownloads.safeName(message.optString("name", "arquivo")), mime);
                active = new Transfer(source, origin, id, mime, name, size, reply);
                showProgress(active);
                armTimeout();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) open(active, null);
                else {
                    documentRequest = documentRequest == LAST_DOCUMENT ? FIRST_DOCUMENT : documentRequest + 1;
                    activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType(mime)
                        .putExtra(Intent.EXTRA_TITLE, name), documentRequest);
                }
                return;
            }
            Transfer transfer = active;
            if (transfer == null || transfer.source != source || !transfer.origin.equals(origin)
                    || !transfer.id.equals(id)) return;
            if ("error".equals(type)) { cancel("Não foi possível salvar este arquivo. Tente novamente."); return; }
            if (!transfer.token.equals(message.optString("token"))) return;
            if (transfer.busy) { cancel("Falha na transferência do arquivo. Tente novamente."); return; }
            if (!"chunk".equals(type) && !"end".equals(type)) return;
            transfer.busy = true;
            armTimeout();
            int sequence = message.getInt("seq");
            String data = "chunk".equals(type) ? message.getString("data") : null;
            writer.execute(() -> write(transfer, sequence, data));
        } catch (Exception exception) {
            if (active != null && active.source == source && active.origin.equals(origin))
                cancel("Não foi possível iniciar o download.");
        }
    }

    private void write(Transfer transfer, int sequence, String data) {
        try {
            if (transfer.canceled) return;
            if (data != null) transfer.stream.write(sequence, Base64.decode(data, Base64.NO_WRAP));
            else {
                transfer.stream.finish(sequence);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.IS_PENDING, 0);
                    if (activity.getContentResolver().update(transfer.uri, values, null, null) != 1)
                        throw new IOException("Arquivo não publicado");
                }
                transfer.committed = true;
            }
            ui.post(() -> {
                if (active != transfer) return;
                transfer.busy = false;
                if (data == null) {
                    active = null;
                    ui.removeCallbacks(timeout);
                    transfer.dialog.dismiss();
                    respond(transfer.reply, transfer.id, transfer.token, null);
                    toast("Arquivo salvo: " + transfer.name);
                } else {
                    long received = transfer.stream.received();
                    transfer.progress.setIndeterminate(false);
                    transfer.progress.setProgress(transfer.size == 0 ? 100 : (int) (received * 100 / transfer.size));
                    transfer.label.setText((received / 1024) + " / " + (transfer.size / 1024) + " KB");
                    respond(transfer.reply, transfer.id, transfer.token, null);
                }
            });
        } catch (IOException | RuntimeException exception) {
            ui.post(() -> { if (active == transfer) cancel("Não foi possível salvar o arquivo. Verifique o espaço disponível."); });
        }
    }

    private void open(Transfer transfer, Uri chosen) {
        transfer.busy = true;
        writer.execute(() -> {
            try {
                if (transfer.canceled) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && chosen == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, transfer.name);
                    values.put(MediaStore.Downloads.MIME_TYPE, transfer.mime);
                    values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    transfer.uri = activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                } else transfer.uri = chosen;
                if (transfer.uri == null) throw new IOException("Destino indisponível");
                OutputStream output = activity.getContentResolver().openOutputStream(transfer.uri, "w");
                if (output == null) throw new IOException("Destino indisponível");
                transfer.stream = new DownloadStream(output, transfer.size);
                ui.post(() -> {
                    if (active != transfer) return;
                    transfer.busy = false;
                    respond(transfer.reply, transfer.id, transfer.token, null);
                });
            } catch (IOException | RuntimeException exception) {
                ui.post(() -> { if (active == transfer) cancel("Não foi possível criar o arquivo em Downloads."); });
            }
        });
    }

    boolean onActivityResult(int code, int result, Intent data) {
        if (code < FIRST_DOCUMENT || code > LAST_DOCUMENT) return false;
        if (active == null || code != documentRequest) return true;
        Uri uri = data == null ? null : data.getData();
        if (result != Activity.RESULT_OK || uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null) {
            cancel(null);
            return true;
        }
        ProviderInfo provider = uri == null ? null : activity.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid == Process.myUid()
                || activity.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) cancel(null);
        else open(active, uri);
        return true;
    }

    private void showProgress(Transfer transfer) {
        int padding = (int) (24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        transfer.label = new TextView(activity);
        transfer.label.setText(transfer.name);
        content.addView(transfer.label);
        transfer.progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        transfer.progress.setIndeterminate(true);
        transfer.progress.setMax(100);
        content.addView(transfer.progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        transfer.dialog = new AlertDialog.Builder(activity).setTitle("Salvando arquivo")
                .setView(content).setNegativeButton("Cancelar", (dialog, which) -> cancel(null)).create();
        transfer.dialog.setCanceledOnTouchOutside(false);
        transfer.dialog.setOnCancelListener(dialog -> cancel(null));
        transfer.dialog.show();
    }

    @SuppressLint("RequiresFeature")
    private static void respond(JavaScriptReplyProxy reply, String id, String token, String error) {
        try {
            JSONObject message = new JSONObject().put("id", id).put("token", token);
            if (error != null) message.put("error", error);
            reply.postMessage(message.toString());
        } catch (Exception ignored) { /* The document may already have closed. */ }
    }

    private void armTimeout() { ui.removeCallbacks(timeout); ui.postDelayed(timeout, 120000); }
    void cancelFor(WebView view) { if (active != null && active.source == view) cancel(null); }

    private void cancel(String message) {
        Transfer transfer = active;
        if (transfer == null) return;
        active = null;
        transfer.canceled = true;
        ui.removeCallbacks(timeout);
        transfer.dialog.dismiss();
        respond(transfer.reply, transfer.id, transfer.token, "Download cancelado.");
        writer.execute(() -> {
            try { if (transfer.stream != null) transfer.stream.close(); } catch (IOException ignored) {}
            if (!transfer.committed && transfer.uri != null) {
                try {
                    if (DocumentsContract.isDocumentUri(activity, transfer.uri))
                        DocumentsContract.deleteDocument(activity.getContentResolver(), transfer.uri);
                    else activity.getContentResolver().delete(transfer.uri, null, null);
                } catch (Exception ignored) {}
            }
        });
        if (message != null) toast(message);
    }

    void close() { if (!closed) { closed = true; cancel(null); writer.shutdown(); } }
    private void toast(String text) { Toast.makeText(activity, text, Toast.LENGTH_LONG).show(); }

    private static final class Transfer {
        final WebView source;
        final String origin, id, mime, name, token = UUID.randomUUID().toString();
        final long size;
        final JavaScriptReplyProxy reply;
        volatile boolean canceled;
        boolean busy, committed;
        Uri uri;
        DownloadStream stream;
        AlertDialog dialog;
        ProgressBar progress;
        TextView label;
        Transfer(WebView source, String origin, String id, String mime, String name, long size, JavaScriptReplyProxy reply) {
            this.source = source; this.origin = origin; this.id = id; this.mime = mime;
            this.name = name; this.size = size; this.reply = reply;
        }
    }
}
