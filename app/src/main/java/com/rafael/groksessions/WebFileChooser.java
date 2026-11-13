package com.rafael.groksessions;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Process;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.Toast;

import java.util.LinkedHashSet;
import java.util.Set;

/** Keeps a single file request tied to the WebView/document that initiated it. */
final class WebFileChooser {
    private static final int FIRST_REQUEST = 0x5000;
    private static final int LAST_REQUEST = 0x5fff;
    private final Activity activity;
    private ValueCallback<Uri[]> callback;
    private WebView owner;
    private int requestCode = FIRST_REQUEST;
    private boolean multiple;

    WebFileChooser(Activity activity) {
        this.activity = activity;
    }

    boolean show(WebView view, ValueCallback<Uri[]> result, WebChromeClient.FileChooserParams params) {
        cancel();
        if (params == null || result == null) {
            if (result != null) result.onReceiveValue(null);
            return true;
        }
        if (params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN
                && params.getMode() != WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            result.onReceiveValue(null);
            return true;
        }
        callback = result;
        owner = view;
        multiple = params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE;
        requestCode = requestCode == LAST_REQUEST ? FIRST_REQUEST : requestCode + 1;
        try {
            Intent picker = params.createIntent();
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            if (picker.getType() == null || picker.getType().isEmpty()) picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
            picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity.startActivityForResult(picker, requestCode);
        } catch (RuntimeException exception) {
            cancel();
            Toast.makeText(activity, "Não foi possível abrir o seletor de arquivos do Android.", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    boolean onResult(int code, int resultCode, Intent data) {
        if (code < FIRST_REQUEST || code > LAST_REQUEST) return false;
        // A result from a canceled/older picker must not complete a newer request.
        if (callback == null || code != requestCode) return true;
        if (resultCode != Activity.RESULT_OK || data == null) {
            cancel();
            return true;
        }
        try {
            Set<Uri> selected = new LinkedHashSet<>();
            ClipData clips = data.getClipData();
            if (clips != null) {
                for (int i = 0; i < clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri();
                    if (uri == null) continue;
                    if (!canReadSelectedUri(uri)) throw new SecurityException("URI sem acesso concedido");
                    selected.add(uri);
                    if (!multiple) break;
                }
            } else if (data.getData() != null) {
                Uri uri = data.getData();
                if (!canReadSelectedUri(uri)) throw new SecurityException("URI sem acesso concedido");
                selected.add(uri);
            }
            finish(selected.isEmpty() ? null : selected.toArray(new Uri[0]));
        } catch (RuntimeException exception) {
            cancel();
            Toast.makeText(activity, "Não foi possível acessar os arquivos selecionados. Tente novamente.", Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private boolean canReadSelectedUri(Uri uri) {
        if (!"content".equals(uri.getScheme()) || uri.getAuthority() == null) return false;
        ProviderInfo provider = activity.getPackageManager().resolveContentProvider(uri.getAuthority(), 0);
        if (provider == null || provider.applicationInfo == null || provider.applicationInfo.uid == Process.myUid()) return false;
        return activity.checkUriPermission(uri, Process.myPid(), Process.myUid(),
                Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    void cancelFor(WebView view) {
        if (owner == view) cancel();
    }

    void cancel() {
        finish(null);
    }

    private void finish(Uri[] selected) {
        ValueCallback<Uri[]> pending = callback;
        callback = null;
        owner = null;
        if (pending != null) pending.onReceiveValue(selected);
    }
}
