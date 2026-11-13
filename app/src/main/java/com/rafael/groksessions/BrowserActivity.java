package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.webkit.Profile;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

public final class BrowserActivity extends Activity {
    static final String DEFAULT_URL = "https://www.google.com/";
    static final String EXTRA_PROFILE_ID = "profile_id";
    static final String EXTRA_PROFILE_NAME = "profile_name";
    static final String EXTRA_URL = "url";
    static final String EXTRA_SOURCE_PACKAGE = "source_package";
    static final String EXTRA_RETURN_RESULT = "return_result";
    static final String EXTRA_REDIRECT_SCHEME = "redirect_scheme";
    static final String EXTRA_REDIRECT_HOST = "redirect_host";
    static final String EXTRA_REDIRECT_PATH = "redirect_path";

    private WebView webView;
    private WebView popupWebView;
    private WebFileChooser fileChooser;
    private BrowserDownloads browserDownloads;
    private View fullscreenView;
    private FrameLayout fullscreenContainer;
    private WebChromeClient.CustomViewCallback fullscreenCallback;
    private int previousSystemUiVisibility;
    private boolean previouslyFullscreen;
    private FrameLayout browserContainer;
    private EditText address;
    private ProgressBar progress;
    private CookieManager profileCookies;
    private String profileId;
    private String privacySeed;
    private boolean privacyActive;
    private TextView sessionLabel;
    private String sourcePackage;
    private boolean returnResultToCaller;
    private String redirectScheme;
    private String redirectHost;
    private String redirectPath;
    private OnBackInvokedCallback backInvokedCallback;

    private static final int COLOR_PAGE = Color.rgb(3, 11, 17);
    private static final int COLOR_SURFACE = Color.rgb(10, 23, 33);
    private static final int COLOR_SURFACE_ALT = Color.rgb(13, 30, 42);
    private static final int COLOR_LINE = Color.rgb(23, 54, 70);
    private static final int COLOR_TEXT = Color.rgb(242, 248, 252);
    private static final int COLOR_MUTED = Color.rgb(142, 164, 179);
    private static final int COLOR_CYAN = Color.rgb(34, 238, 226);
    private static final int COLOR_VIOLET = Color.rgb(139, 92, 246);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        fileChooser = new WebFileChooser(this);
        browserDownloads = new BrowserDownloads(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backInvokedCallback = this::handleBackNavigation;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                    backInvokedCallback
            );
        }

        profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        String profileName = getIntent().getStringExtra(EXTRA_PROFILE_NAME);
        String initialUrl = getIntent().getStringExtra(EXTRA_URL);
        sourcePackage = getIntent().getStringExtra(EXTRA_SOURCE_PACKAGE);
        returnResultToCaller = getIntent().getBooleanExtra(EXTRA_RETURN_RESULT, false);
        redirectScheme = getIntent().getStringExtra(EXTRA_REDIRECT_SCHEME);
        redirectHost = getIntent().getStringExtra(EXTRA_REDIRECT_HOST);
        redirectPath = getIntent().getStringExtra(EXTRA_REDIRECT_PATH);
        if (profileId == null || profileId.isEmpty() || !supportsProfiles()) {
            Toast.makeText(this, "Não foi possível abrir o perfil separado.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        for (Account account : new AccountRepository(this).getAll()) {
            if (profileId.equals(account.id)) {
                privacySeed = account.privacySeed;
                break;
            }
        }
        buildBrowser(profileName == null ? "Conta" : profileName, profileId);

        Bundle webState = savedInstanceState == null ? null : savedInstanceState.getBundle("web_state");
        if (webState != null) {
            webView.restoreState(webState);
        } else {
            webView.loadUrl(normalizeUrl(initialUrl));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            Bundle webState = new Bundle();
            webView.saveState(webState);
            outState.putBundle("web_state", webState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        exitFullscreen(true);
        if (fileChooser != null) fileChooser.cancel();
        closePopup();
        flushCookies();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && backInvokedCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backInvokedCallback);
            backInvokedCallback = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        flushCookies();
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (fileChooser != null && fileChooser.onResult(requestCode, resultCode, data)) return;
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (browserDownloads != null) browserDownloads.onPermissionResult(requestCode, grantResults);
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void handleBackNavigation() {
        if (fullscreenView != null) {
            exitFullscreen(true);
            return;
        }
        if (popupWebView != null) {
            closePopup();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            if (returnResultToCaller) {
                setResult(RESULT_CANCELED);
            }
            finish();
        }
    }

    @SuppressLint("RequiresFeature")
    private void buildBrowser(String profileName, String profileId) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_PAGE);

        LinearLayout titleBar = new LinearLayout(this);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        titleBar.setPadding(dp(10), dp(8), dp(12), dp(7));
        titleBar.setBackgroundColor(COLOR_PAGE);

        TextView close = toolbarButton("‹");
        close.setTextSize(28);
        close.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(17, 45, 59), 12));
        close.setOnClickListener(view -> finish());
        titleBar.addView(close, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setPadding(dp(12), 0, 0, 0);
        TextView label = new TextView(this);
        sessionLabel = label;
        label.setText(R.string.isolated_session_label);
        label.setTextColor(COLOR_CYAN);
        label.setTextSize(9);
        label.setTypeface(null, Typeface.BOLD);
        label.setLetterSpacing(0.14f);
        identity.addView(label, matchWrap());

        TextView profile = new TextView(this);
        profile.setText(profileName);
        profile.setTextColor(COLOR_TEXT);
        profile.setTextSize(15);
        profile.setSingleLine(true);
        profile.setTypeface(null, Typeface.BOLD);
        identity.addView(profile, matchWrap());
        titleBar.addView(identity, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView shield = toolbarButton("◆");
        shield.setTextColor(COLOR_CYAN);
        shield.setTextSize(14);
        shield.setBackground(roundedBackground(Color.rgb(7, 40, 46), 12, Color.rgb(23, 94, 98), 1));
        shield.setContentDescription(getString(R.string.profile_privacy_title));
        shield.setOnClickListener(view -> new AlertDialog.Builder(this)
                .setTitle(R.string.profile_privacy_title)
                .setMessage(privacySeed == null ? R.string.profile_privacy_legacy
                        : privacyActive ? R.string.profile_privacy_active : R.string.profile_privacy_unavailable)
                .setPositiveButton("OK", null).show());
        titleBar.addView(shield, new LinearLayout.LayoutParams(dp(38), dp(38)));
        root.addView(titleBar, matchWrap());

        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(dp(10), dp(5), dp(10), dp(9));
        navigation.setBackgroundColor(COLOR_PAGE);

        TextView back = toolbarButton("‹");
        back.setTextSize(25);
        back.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(17, 45, 59), 12));
        back.setOnClickListener(view -> {
            if (popupWebView != null) {
                closePopup();
            } else if (webView.canGoBack()) {
                webView.goBack();
            }
        });
        navigation.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));

        TextView reload = toolbarButton("↻");
        reload.setTextSize(19);
        reload.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(17, 45, 59), 12));
        reload.setOnClickListener(view -> {
            WebView active = activeWebView();
            if (active != null) active.reload();
        });
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(dp(42), dp(44));
        reloadParams.setMargins(dp(6), 0, dp(8), 0);
        navigation.addView(reload, reloadParams);

        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextSize(12);
        address.setTextColor(COLOR_TEXT);
        address.setHintTextColor(COLOR_MUTED);
        address.setHint("Pesquisar ou digitar endereço");
        address.setPadding(dp(14), 0, dp(14), 0);
        address.setBackground(roundedBackground(COLOR_SURFACE, 13, COLOR_LINE, 1));
        address.setSelectAllOnFocus(true);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                WebView active = activeWebView();
                if (active != null) active.loadUrl(normalizeUrl(address.getText().toString()));
                address.clearFocus();
                return true;
            }
            return false;
        });
        navigation.addView(address, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(navigation, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.getProgressDrawable().setTint(COLOR_CYAN);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)));

        webView = new WebView(this);

        // Esta precisa ser a primeira operação feita com a WebView.
        WebViewCompat.setProfile(webView, profileId);
        configureWebView(webView);

        browserContainer = new FrameLayout(this);
        browserContainer.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        root.addView(browserContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));
        setContentView(root);
    }

    @SuppressLint({"RequiresFeature", "SetJavaScriptEnabled"})
    private void configureWebView(WebView view) {
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportMultipleWindows(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        boolean protectedView = ProfilePrivacy.apply(this, view, privacySeed);
        if (view == webView) {
            privacyActive = protectedView;
            sessionLabel.setText(privacyActive ? R.string.private_identity_label : R.string.isolated_session_label);
        }

        Profile profile = WebViewCompat.getProfile(view);
        profileCookies = profile.getCookieManager();
        profileCookies.setAcceptCookie(true);
        profileCookies.setAcceptThirdPartyCookies(view, true);

        view.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                browserDownloads.download(view, url, userAgent, contentDisposition, mimeType));

        view.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, String url) {
                return handleNavigation(Uri.parse(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView webView, WebResourceRequest request) {
                return handleNavigation(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView webView, String url, Bitmap favicon) {
                fileChooser.cancelFor(webView);
                if (webView == activeWebView()) address.setText(url);
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                if (webView == activeWebView()) address.setText(url);
                flushCookies();
            }

            @Override
            public boolean onRenderProcessGone(WebView webView, RenderProcessGoneDetail detail) {
                Toast.makeText(BrowserActivity.this, "O navegador foi reiniciado pelo Android.", Toast.LENGTH_LONG).show();
                finish();
                return true;
            }
        });

        view.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View customView, CustomViewCallback callback) {
                showFullscreen(customView, callback);
            }

            @Override
            public void onHideCustomView() {
                exitFullscreen(false);
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback, FileChooserParams params) {
                return fileChooser.show(webView, callback, params);
            }

            @Override
            public boolean onCreateWindow(WebView parent, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (popupWebView != null || browserContainer == null
                        || resultMsg == null
                        || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                    return false;
                }

                WebView popup = new WebView(BrowserActivity.this);
                // A janela do login deve compartilhar somente o perfil desta conta.
                WebViewCompat.setProfile(popup, profileId);
                configureWebView(popup);
                popupWebView = popup;
                browserContainer.addView(popup, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));

                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (window == popupWebView) closePopup();
            }

            @Override
            public void onProgressChanged(WebView webView, int newProgress) {
                if (webView != activeWebView()) return;
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView webView, String title) {
                if (webView == activeWebView() && title != null && !title.isEmpty()) {
                    setTitle(title);
                }
            }
        });
    }

    private void showFullscreen(View customView, WebChromeClient.CustomViewCallback callback) {
        if (customView == null || fullscreenView != null) {
            if (callback != null) callback.onCustomViewHidden();
            return;
        }
        View decor = getWindow().getDecorView();
        previousSystemUiVisibility = decor.getSystemUiVisibility();
        previouslyFullscreen = (getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
        fullscreenView = customView;
        fullscreenCallback = callback;
        fullscreenContainer = new FrameLayout(this);
        fullscreenContainer.setBackgroundColor(Color.BLACK);
        fullscreenContainer.addView(customView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        ((ViewGroup) decor).addView(fullscreenContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        decor.setSystemUiVisibility(previousSystemUiVisibility
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void exitFullscreen(boolean notifyPage) {
        if (fullscreenView == null) return;
        FrameLayout container = fullscreenContainer;
        WebChromeClient.CustomViewCallback callback = fullscreenCallback;
        fullscreenView = null;
        fullscreenContainer = null;
        fullscreenCallback = null;
        if (container != null) {
            ViewGroup parent = (ViewGroup) container.getParent();
            if (parent != null) parent.removeView(container);
            container.removeAllViews();
        }
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(previousSystemUiVisibility);
        if (!previouslyFullscreen) {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        if (notifyPage && callback != null) callback.onCustomViewHidden();
    }

    private WebView activeWebView() {
        return popupWebView != null ? popupWebView : webView;
    }

    private void closePopup() {
        if (popupWebView == null) return;
        if (fullscreenView != null) exitFullscreen(true);
        WebView popup = popupWebView;
        if (fileChooser != null) fileChooser.cancelFor(popup);
        popupWebView = null;
        if (browserContainer != null) browserContainer.removeView(popup);
        popup.stopLoading();
        popup.destroy();
        if (webView != null && address != null && webView.getUrl() != null) {
            address.setText(webView.getUrl());
        }
        flushCookies();
    }

    private boolean handleNavigation(Uri uri) {
        String scheme = uri.getScheme();
        if (isExpectedAuthRedirect(uri)) {
            returnAuthResult(uri);
            return true;
        }
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (returnResultToCaller) {
            returnAuthResult(uri);
            return true;
        }
        openExternal(uri);
        return true;
    }

    private boolean isExpectedAuthRedirect(Uri uri) {
        if (!returnResultToCaller) {
            return false;
        }
        String scheme = uri.getScheme();
        if (redirectScheme != null && redirectScheme.equalsIgnoreCase(scheme)) {
            return true;
        }
        if (redirectHost == null || !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        if (!redirectHost.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        return redirectPath == null || redirectPath.isEmpty() || redirectPath.equals(uri.getPath());
    }

    private void returnAuthResult(Uri uri) {
        flushCookies();
        Intent result = new Intent();
        result.setData(uri);
        setResult(RESULT_OK, result);
        finish();
    }

    private void flushCookies() {
        if (profileCookies != null) {
            profileCookies.flush();
        }
    }

    private void openExternal(Uri uri) {
        Intent externalIntent = null;
        try {
            if ("intent".equalsIgnoreCase(uri.getScheme())) {
                externalIntent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } else {
                externalIntent = new Intent(Intent.ACTION_VIEW, uri);
            }
            externalIntent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(externalIntent);
        } catch (ActivityNotFoundException exception) {
            if (openBrowserFallback(externalIntent) || returnToSourceApp(uri)) {
                return;
            }
            Toast.makeText(this, "Nenhum aplicativo aceita este retorno.", Toast.LENGTH_LONG).show();
        } catch (Exception exception) {
            Toast.makeText(this, "Não foi possível abrir o link externo.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean openBrowserFallback(Intent parsedIntent) {
        if (parsedIntent == null) {
            return false;
        }
        String fallbackUrl = parsedIntent.getStringExtra("browser_fallback_url");
        if (fallbackUrl == null || fallbackUrl.isEmpty()) {
            return false;
        }
        Uri fallback = Uri.parse(fallbackUrl);
        String scheme = fallback.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            return false;
        }
        webView.loadUrl(fallbackUrl);
        return true;
    }

    private boolean returnToSourceApp(Uri callbackUri) {
        if (sourcePackage == null || sourcePackage.isEmpty()) {
            return false;
        }
        Intent launchIntent = getPackageManager().getLaunchIntentForPackage(sourcePackage);
        if (launchIntent == null) {
            return false;
        }
        launchIntent.setAction(Intent.ACTION_VIEW);
        launchIntent.setData(callbackUri);
        launchIntent.addCategory(Intent.CATEGORY_BROWSABLE);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(launchIntent);
            return true;
        } catch (ActivityNotFoundException exception) {
            return false;
        }
    }

    private String normalizeUrl(String input) {
        if (input == null || input.trim().isEmpty()) {
            return DEFAULT_URL;
        }
        String value = input.trim();
        Uri uri = Uri.parse(value);
        if (uri.getScheme() == null) {
            return "https://" + value;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            return DEFAULT_URL;
        }
        return value;
    }

    private boolean supportsProfiles() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
    }

    private TextView toolbarButton(String label) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(COLOR_TEXT);
        view.setTextSize(16);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private Drawable roundedBackground(int fillColor, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fillColor);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private Drawable pressableBackground(int normalColor, int pressedColor, int radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                roundedBackground(pressedColor, radiusDp, COLOR_CYAN, 1)
        );
        states.addState(
                new int[]{},
                roundedBackground(normalColor, radiusDp, COLOR_LINE, 1)
        );
        return states;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
