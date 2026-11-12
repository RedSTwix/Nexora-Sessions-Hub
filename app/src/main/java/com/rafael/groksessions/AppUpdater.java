package com.rafael.groksessions;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Activity-scoped UI controller; DownloadManager owns transfers across process restarts. */
final class AppUpdater {
    enum Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, ERROR }

    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String APK_PATH = "updates/update.apk";
    private static final long CHECK_INTERVAL = TimeUnit.HOURS.toMillis(6);
    private static final long RETRY_INTERVAL = TimeUnit.MINUTES.toMillis(15);

    private final Activity activity;
    private final Runnable listener;
    private final SharedPreferences preferences;
    private final DownloadManager downloads;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PackageInfo installed;
    private final Runnable poll = this::pollDownload;
    private long downloadId;
    private int generation;
    private boolean resumed;
    private boolean closed;
    private boolean autoInstall;
    private boolean pollingDownload;

    Phase phase = Phase.IDLE;
    GitHubRelease release;
    String message = "Consulte as novas versões publicadas no GitHub.";
    long downloadedBytes;
    long totalBytes = -1;

    AppUpdater(Activity activity, Runnable listener) {
        this.activity = activity;
        this.listener = listener;
        preferences = activity.getSharedPreferences("app_updates", Context.MODE_PRIVATE);
        downloads = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        try {
            installed = activity.getPackageManager().getPackageInfo(activity.getPackageName(), signingFlags());
        } catch (PackageManager.NameNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
        try {
            String cached = preferences.getString("release", null);
            release = cached == null ? null : GitHubRelease.parse(cached);
        } catch (JSONException exception) {
            release = null;
        }
        downloadId = preferences.getLong("download_id", -1);
        if (release != null && release.isNewerThan(installed.versionName)) {
            phase = downloadId > 0 ? Phase.DOWNLOADING : Phase.AVAILABLE;
            message = downloadId > 0 ? "Retomando o acompanhamento do download…" : availableMessage();
        } else {
            clearDownload();
            release = null;
            preferences.edit().remove("release").apply();
        }
    }

    String installedVersion() {
        return installed.versionName;
    }

    void onResume(boolean allowAutomaticCheck) {
        resumed = true;
        if (preferences.getBoolean("waiting_permission", false)) {
            preferences.edit().remove("waiting_permission").apply();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || activity.getPackageManager().canRequestPackageInstalls()) {
                install();
                return;
            }
            message = "Permissão não concedida. Toque em Instalar para tentar novamente.";
        }
        listener.run();
        if (downloadId > 0 && phase != Phase.VERIFYING && phase != Phase.READY) {
            main.removeCallbacks(poll);
            main.post(poll);
        } else if (allowAutomaticCheck && downloadId <= 0) {
            check(false);
        }
    }

    void onPause() {
        resumed = false;
        main.removeCallbacks(poll);
    }

    void close() {
        closed = true;
        generation++;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
    }

    void check(boolean manual) {
        if (phase == Phase.CHECKING || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING
                || phase == Phase.READY || closed) {
            return;
        }
        long now = System.currentTimeMillis();
        long nextCheck = preferences.getLong("next_check", 0);
        if (!manual && nextCheck > now && nextCheck - now <= CHECK_INTERVAL) {
            return;
        }
        phase = Phase.CHECKING;
        message = "Verificando novas versões…";
        listener.run();
        int request = ++generation;
        worker.execute(() -> {
            try {
                GitHubRelease latest = fetchLatest();
                String cached = latest == null ? null : latest.toJson();
                post(request, () -> {
                    preferences.edit().putLong("next_check", System.currentTimeMillis() + CHECK_INTERVAL)
                            .putString("release", cached).apply();
                    release = latest != null && latest.isNewerThan(installed.versionName) ? latest : null;
                    phase = release == null ? Phase.IDLE : Phase.AVAILABLE;
                    message = release == null ? "Você está usando a versão mais recente disponível." : availableMessage();
                    listener.run();
                });
            } catch (IOException | JSONException exception) {
                post(request, () -> {
                    preferences.edit().putLong("next_check", System.currentTimeMillis() + RETRY_INTERVAL).apply();
                    phase = Phase.ERROR;
                    message = "Não foi possível consultar o GitHub. Verifique a conexão e tente novamente.";
                    listener.run();
                });
            }
        });
    }

    private static GitHubRelease fetchLatest() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(GitHubRelease.API_URL).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "Nexora-Sessions-Hub-Updater");
        try {
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return null;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("GitHub HTTP " + status);
            }
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (output.size() + count > 524288) {
                        throw new IOException("Resposta de release excede o limite");
                    }
                    output.write(buffer, 0, count);
                }
                return GitHubRelease.parse(new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    void download() {
        if (release == null || closed || phase == Phase.DOWNLOADING || phase == Phase.VERIFYING) {
            return;
        }
        if (phase == Phase.READY) {
            install();
            return;
        }
        try {
            clearDownload();
            File file = apkFile();
            File directory = file.getParentFile();
            if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
                throw new IOException("Nao foi possivel preparar a pasta de atualizacoes");
            }
            if (file.exists() && !file.delete()) {
                throw new IOException("Nao foi possivel remover o download anterior");
            }
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(release.downloadUrl))
                    .setTitle("Nexora Sessions Hub " + release.version)
                    .setDescription("Baixando atualização do aplicativo")
                    .setMimeType(APK_MIME)
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, APK_PATH);
            downloadId = downloads.enqueue(request);
            preferences.edit().putLong("download_id", downloadId).putString("release", release.toJson()).apply();
            autoInstall = true;
            downloadedBytes = 0;
            totalBytes = release.size;
            phase = Phase.DOWNLOADING;
            message = "Iniciando download…";
            listener.run();
            main.post(poll);
        } catch (IOException | JSONException | RuntimeException exception) {
            fail("Não foi possível iniciar o download. Verifique o espaço livre e tente novamente.");
        }
    }

    void cancelDownload() {
        generation++;
        autoInstall = false;
        pollingDownload = false;
        main.removeCallbacks(poll);
        clearDownload();
        phase = release == null ? Phase.IDLE : Phase.AVAILABLE;
        message = "Download cancelado. Você pode baixar a atualização quando quiser.";
        listener.run();
    }

    private void pollDownload() {
        if (!resumed || closed || pollingDownload || downloadId <= 0
                || phase == Phase.VERIFYING || phase == Phase.READY) {
            return;
        }
        pollingDownload = true;
        int request = generation;
        long id = downloadId;
        worker.execute(() -> {
            try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
                if (cursor == null || !cursor.moveToFirst()) {
                    post(request, () -> {
                        pollingDownload = false;
                        downloadFailed("O download foi removido. Toque em Baixar para tentar novamente.");
                    });
                    return;
                }
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                long downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                long total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
                post(request, () -> {
                    pollingDownload = false;
                    downloadedBytes = downloaded;
                    totalBytes = total > 0 ? total : release.size;
                    if (status == DownloadManager.STATUS_SUCCESSFUL) {
                        verifyDownload(autoInstall);
                    } else if (status == DownloadManager.STATUS_FAILED) {
                        downloadFailed(reason == DownloadManager.ERROR_INSUFFICIENT_SPACE
                                ? "Sem espaço suficiente. Libere espaço e tente novamente."
                                : "O download falhou. Verifique a conexão e tente novamente.");
                    } else {
                        phase = Phase.DOWNLOADING;
                        message = status == DownloadManager.STATUS_PAUSED
                                ? "Download pausado. Aguardando conexão para continuar…"
                                : status == DownloadManager.STATUS_PENDING ? "Aguardando início do download…" : "Baixando atualização…";
                        listener.run();
                        if (resumed) {
                            main.postDelayed(poll, 500);
                        }
                    }
                });
            } catch (RuntimeException exception) {
                post(request, () -> {
                    pollingDownload = false;
                    downloadFailed("Não foi possível acompanhar o download. Tente novamente.");
                });
            }
        });
    }

    void install() {
        if (release != null && downloadId > 0 && phase != Phase.VERIFYING && !closed) {
            verifyDownload(true);
        }
    }

    private void verifyDownload(boolean installAfterVerification) {
        phase = Phase.VERIFYING;
        message = "Verificando a integridade e a assinatura da atualização…";
        listener.run();
        GitHubRelease candidate = release;
        int request = generation;
        worker.execute(() -> {
            try {
                File apk = apkFile();
                if (!UpdateIntegrity.matches(apk, candidate.size, candidate.sha256)) {
                    throw new IOException("O arquivo baixado está incompleto ou foi alterado. Baixe novamente.");
                }
                verifyPackage(apk, candidate);
                post(request, () -> {
                    phase = Phase.READY;
                    message = "Download verificado. A atualização preservará suas contas e sessões.";
                    listener.run();
                    if (installAfterVerification && resumed) {
                        launchInstaller();
                    }
                });
            } catch (IOException | RuntimeException exception) {
                post(request, () -> downloadFailed(exception instanceof IOException
                        ? exception.getMessage() : "Não foi possível verificar o APK. Baixe novamente."));
            }
        });
    }

    @SuppressWarnings("deprecation")
    private void verifyPackage(File apk, GitHubRelease candidate) throws IOException {
        PackageInfo archive = activity.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), signingFlags());
        if (archive == null || !activity.getPackageName().equals(archive.packageName)) {
            throw new IOException("Este APK não corresponde ao aplicativo instalado. Use a versão oficial para receber atualizações.");
        }
        if (!candidate.version.equals(archive.versionName)) {
            throw new IOException("A versão do APK não corresponde à release anunciada. Baixe novamente mais tarde.");
        }
        long archiveCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? archive.getLongVersionCode() : archive.versionCode;
        long installedCode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? installed.getLongVersionCode() : installed.versionCode;
        if (archiveCode <= installedCode) {
            throw new IOException("O APK não é uma atualização desta versão. Aguarde uma nova release.");
        }
        if (archive.applicationInfo != null
                && archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) {
            throw new IOException("Esta atualização exige uma versão mais recente do Android.");
        }
        Signature[] current = signatures(installed);
        Signature[] incoming = signatures(archive);
        if (current == null || incoming == null || current.length == 0 || current.length != incoming.length
                || !Arrays.asList(current).containsAll(Arrays.asList(incoming))) {
            throw new IOException("A assinatura do APK é diferente da versão instalada. A atualização foi interrompida para preservar seus dados.");
        }
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners();
        }
        return info.signatures;
    }

    @SuppressWarnings("deprecation")
    private static int signingFlags() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    }

    private void launchInstaller() {
        autoInstall = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            message = "Permita atualizações desta fonte na tela do Android e volte ao aplicativo.";
            listener.run();
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
            try {
                preferences.edit().putBoolean("waiting_permission", true).apply();
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (ActivityNotFoundException | SecurityException exception) {
                preferences.edit().remove("waiting_permission").apply();
                message = "Abra as configurações do Android e permita instalar atualizações pelo Nexora.";
                listener.run();
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", apkFile());
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Atualização do Nexora", uri));
            activity.startActivity(intent);
            message = "Confirme a atualização na tela do Android. Se cancelar, toque em Instalar para tentar novamente.";
        } catch (IOException | RuntimeException exception) {
            message = "Não foi possível abrir o instalador. Toque em Instalar para tentar novamente.";
        }
        listener.run();
    }

    private File apkFile() throws IOException {
        File directory = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (directory == null) {
            throw new IOException("O armazenamento para downloads não está disponível.");
        }
        return new File(directory, APK_PATH);
    }

    private String availableMessage() {
        return "A versão " + release.version + " está disponível para atualizar.";
    }

    private void downloadFailed(String reason) {
        clearDownload();
        autoInstall = false;
        fail(reason);
    }

    private void fail(String reason) {
        phase = Phase.ERROR;
        message = reason;
        listener.run();
    }

    private void clearDownload() {
        if (downloadId > 0) {
            downloads.remove(downloadId);
        }
        downloadId = -1;
        preferences.edit().remove("download_id").remove("waiting_permission").apply();
    }

    private void post(int request, Runnable action) {
        main.post(() -> {
            if (!closed && request == generation) {
                action.run();
            }
        });
    }
}
