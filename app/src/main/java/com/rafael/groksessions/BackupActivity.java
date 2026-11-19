package com.rafael.groksessions;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

/** A separate process keeps every WebView closed while profile files are copied or replaced. */
public final class BackupActivity extends Activity {
    private static final int PICK_EXPORT = 7010, PICK_IMPORT = 7011;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private ProgressBar progress;
    private Button action;
    private JSONObject sourceInfo;
    private BackupArchive.Restored staged;
    private boolean busy;
    private boolean awaitingPicker;
    private volatile boolean ownsReservation;
    private long lastProgress;
    private OnBackInvokedCallback backCallback;
    private AlertDialog confirmation;
    private final Handler lifecycle = new Handler(Looper.getMainLooper());
    private final Runnable waitForPrevious = new Runnable() {
        @Override public void run() {
            if (BackupCoordinator.isActive(BackupActivity.this)) lifecycle.postDelayed(this, 500);
            else done("A operação anterior terminou. Volte ao Nexora para conferir os dados e iniciar uma nova operação, se necessário.");
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean previousActive = BackupCoordinator.isActive(this);
        if (!previousActive && new File(getNoBackupFilesDir(), "restore-rollback").exists()) {
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finishAndRemoveTask();
            return;
        }
        if (!previousActive) BackupCoordinator.discardAbandonedStages(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(28));
        content.setBackgroundColor(Color.rgb(3, 11, 17));
        TextView title = new TextView(this);
        title.setText("Backup e restauração"); title.setTextColor(Color.WHITE); title.setTextSize(24);
        content.addView(title);
        status = new TextView(this);
        status.setTextColor(Color.rgb(142, 164, 179)); status.setTextSize(15);
        status.setPadding(0, dp(18), 0, dp(18));
        content.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true); progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));
        action = new Button(this);
        action.setText("Voltar ao Nexora"); action.setOnClickListener(view -> leave());
        content.addView(action, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(content);
        if (Build.VERSION.SDK_INT >= 33) {
            backCallback = this::handleBack;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
        }
        if (previousActive) {
            startWork("Aguardando a operação anterior terminar com segurança…");
            lifecycle.post(waitForPrevious);
            return;
        }
        try {
            sourceInfo = new JSONObject(getIntent().getStringExtra("source_info"));
        } catch (Exception exception) {
            status.setText("Volte ao Nexora para conferir os dados ou iniciar uma nova operação.");
            return;
        }
        awaitingPicker = state != null && state.getBoolean("awaiting_picker");
        if (awaitingPicker) { status.setText("Aguardando a seleção do arquivo…"); return; }
        if (getIntent().getBooleanExtra("restore", false)) {
            status.setText("Escolha um ZIP criado pelo Nexora. Você poderá conferir o conteúdo antes de substituir os dados atuais.");
            action.setText("Selecionar backup ZIP");
            action.setOnClickListener(view -> pickImport());
        } else {
            status.setText("O ZIP sem senha inclui contas, pastas, atalhos, configurações, cookies e dados dos sites. Ele contém dados de sessão: guarde-o em local privado. Os perfis serão fechados durante a cópia.");
            action.setText("Escolher onde salvar");
            action.setOnClickListener(view -> pickExport());
        }
    }

    private void pickExport() {
        String name = "Nexora-backup-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".zip";
        launchPicker(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), PICK_EXPORT);
    }
    private void pickImport() {
        launchPicker(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK_IMPORT);
    }
    private void launchPicker(Intent intent, int request) {
        try { awaitingPicker = true; startActivityForResult(intent, request); }
        catch (RuntimeException exception) { awaitingPicker = false; fail("Não foi possível abrir o seletor de arquivos."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_EXPORT && request != PICK_IMPORT) return;
        awaitingPicker = false;
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int permission = request == PICK_EXPORT ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (!isGrantedDocument(uri, permission)) { fail("O arquivo não recebeu a permissão necessária do Android."); return; }
        if (request == PICK_EXPORT) export(uri); else inspect(uri);
    }

    private boolean isGrantedDocument(Uri uri, int permission) {
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null) return false;
        ProviderInfo provider = getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        return provider != null && provider.applicationInfo != null && provider.applicationInfo.uid != Process.myUid()
                && checkUriPermission(uri, Process.myPid(), Process.myUid(), permission) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void export(Uri uri) {
        startWork("Fechando os perfis para criar uma cópia consistente…");
        worker.execute(() -> {
            boolean completed = false;
            String failure = null;
            try {
                BackupCoordinator.begin(this);
                ownsReservation = true;
                JSONObject settings = BackupSettings.export(getSharedPreferences("account_manager", MODE_PRIVATE));
                try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("Não foi possível abrir o destino.");
                    BackupArchive.write(output, settings, sourceInfo, BackupTransaction.webview(this),
                            BackupTransaction.preferences(this), this::onProgress);
                }
                completed = true;
            } catch (Exception exception) { failure = message(exception); }
            finally {
                if (!completed) try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) {}
                BackupCoordinator.end(this);
                ownsReservation = false;
            }
            String error = failure;
            runOnUiThread(() -> { if (error == null) done("Backup ZIP criado com os dados dos perfis, cookies e armazenamento dos sites."); else fail(error); });
        });
    }

    private void inspect(Uri uri) {
        startWork("Lendo e verificando o ZIP antes de alterar qualquer dado…");
        worker.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Não foi possível abrir o ZIP.");
                BackupCoordinator.reserve(this);
                ownsReservation = true;
                staged = BackupArchive.read(input, new File(getNoBackupFilesDir(), "backup-staging"), getPackageName(),
                        sourceInfo.getInt("version_code"), this::onProgress);
                requireCompatible(staged.info, sourceInfo);
                Map<String, Integer> counts = BackupSettings.counts(staged.settings);
                runOnUiThread(() -> confirmRestore(counts));
            } catch (Exception exception) {
                cleanupStage();
                BackupCoordinator.end(this);
                ownsReservation = false;
                runOnUiThread(() -> fail(message(exception)));
            }
        });
    }

    static void requireCompatible(JSONObject backup, JSONObject target) throws IOException, JSONException {
        if (!backup.optBoolean("has_webview")) return;
        if (backup.getInt("sdk") > target.getInt("sdk")
                || !backup.getString("webview_package").equals(target.getString("webview_package"))
                || compareVersions(target.getString("webview_version"), backup.getString("webview_version")) < 0)
            throw new IOException("Para restaurar os perfis completos, use Android e WebView compatíveis, da mesma versão ou mais recentes. Nenhum dado atual foi substituído.");
    }

    static int compareVersions(String first, String second) throws IOException {
        if (!first.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,4}") || !second.matches("[0-9]{1,9}(\\.[0-9]{1,9}){0,4}"))
            throw new IOException("Não foi possível identificar a versão do WebView do backup.");
        String[] a = first.split("\\."), b = second.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int compared = Integer.compare(i < a.length ? Integer.parseInt(a[i]) : 0, i < b.length ? Integer.parseInt(b[i]) : 0);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private void confirmRestore(Map<String, Integer> counts) {
        busy = false;
        progress.setVisibility(View.GONE);
        String preview = counts.get("folders") + " pastas e " + counts.get("accounts") + " contas.\n\n"
                + "A restauração substitui a organização, as configurações e os dados dos perfis atuais pelos do ZIP. Cookies e dados dos sites serão restaurados. Sites podem exigir nova autenticação se a sessão tiver vencido ou estiver vinculada ao aparelho.";
        status.setText(preview);
        action.setEnabled(true); action.setText("Restaurar este backup");
        action.setOnClickListener(view -> {
            confirmation = new AlertDialog.Builder(this).setTitle("Substituir os dados atuais?")
                .setMessage(preview).setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar", (dialog, which) -> applyRestore()).create();
            confirmation.show();
        });
    }

    private void applyRestore() {
        startWork("Restaurando os perfis completos. Aguarde até concluir…");
        worker.execute(() -> {
            String failure = null;
            try {
                BackupCoordinator.begin(this);
                ownsReservation = true;
                BackupTransaction.restore(this, staged);
            } catch (Exception exception) { failure = message(exception); }
            finally { cleanupStage(); BackupCoordinator.end(this); ownsReservation = false; }
            String error = failure;
            runOnUiThread(() -> { if (error == null) done("Restauração concluída. Contas, pastas, atalhos, configurações e arquivos dos perfis foram recuperados."); else fail(error); });
        });
    }

    private void onProgress(long bytes, int files) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastProgress < 200) return;
        lastProgress = now;
        runOnUiThread(() -> { if (busy) status.setText(String.format(Locale.getDefault(), "Processando %.2f MB · %d arquivos…", bytes / 1048576.0, files)); });
    }
    private void startWork(String text) { busy = true; lastProgress = 0; status.setText(text); progress.setVisibility(View.VISIBLE); action.setEnabled(false); }
    private void done(String text) { busy = false; progress.setVisibility(View.GONE); status.setText(text); action.setEnabled(true); action.setText("Voltar ao Nexora"); action.setOnClickListener(view -> leave()); }
    private void fail(String text) { done("Não foi possível concluir a operação.\n\n" + text); }
    private static String message(Exception exception) {
        return exception instanceof IOException && exception.getMessage() != null ? exception.getMessage() : "Confira o arquivo e o espaço livre no aparelho.";
    }
    private void cleanupStage() {
        if (staged != null) { try { BackupFiles.delete(staged.stage); } catch (IOException ignored) {} staged = null; }
    }
    private void handleBack() {
        if (busy) Toast.makeText(this, "Aguarde a operação terminar para manter os dados consistentes.", Toast.LENGTH_SHORT).show();
        else leave();
    }
    private void leave() {
        if (busy) return;
        cleanupStage();
        if (ownsReservation) { BackupCoordinator.end(this); ownsReservation = false; }
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finishAndRemoveTask();
    }
    @Override protected void onSaveInstanceState(Bundle state) { state.putBoolean("awaiting_picker", awaitingPicker); super.onSaveInstanceState(state); }
    @SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { handleBack(); }
    @Override protected void onDestroy() {
        lifecycle.removeCallbacks(waitForPrevious);
        if (confirmation != null) confirmation.dismiss();
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
        // A destroyed screen must not strand a staged import or clear a live job's marker early.
        worker.execute(() -> { cleanupStage(); if (ownsReservation) { BackupCoordinator.end(this); ownsReservation = false; } });
        worker.shutdown();
        super.onDestroy();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
