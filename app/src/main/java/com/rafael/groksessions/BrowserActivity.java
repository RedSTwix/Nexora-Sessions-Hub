package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.app.Activity;
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
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
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
    static final String EXTRA_PROFILE_ID = "profile_id";
    static final String EXTRA_PROFILE_NAME = "profile_name";
    static final String EXTRA_URL = "url";
    static final String EXTRA_SOURCE_PACKAGE = "source_package";
    static final String EXTRA_RETURN_RESULT = "return_result";
    static final String EXTRA_REDIRECT_SCHEME = "redirect_scheme";
    static final String EXTRA_REDIRECT_HOST = "redirect_host";
    static final String EXTRA_REDIRECT_PATH = "redirect_path";

    private WebView webView;
    private EditText address;
    private ProgressBar progress;
    private CookieManager profileCookies;
    private String profileId;
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

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    private void handleBackNavigation() {
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
            if (webView.canGoBack()) webView.goBack();
        });
        navigation.addView(back, new LinearLayout.LayoutParams(dp(42), dp(44)));

        TextView reload = toolbarButton("↻");
        reload.setTextSize(19);
        reload.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(17, 45, 59), 12));
        reload.setOnClickListener(view -> webView.reload());
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
                webView.loadUrl(normalizeUrl(address.getText().toString()));
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

        root.addView(webView, new LinearLayout.LayoutParams(
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
        settings.setSupportMultipleWindows(false);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        Profile profile = WebViewCompat.getProfile(view);
        profileCookies = profile.getCookieManager();
        profileCookies.setAcceptCookie(true);
        profileCookies.setAcceptThirdPartyCookies(view, true);

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
                address.setText(url);
            }

            @Override
            public void onPageFinished(WebView webView, String url) {
                address.setText(url);
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
            public void onProgressChanged(WebView webView, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onReceivedTitle(WebView webView, String title) {
                if (title != null && !title.isEmpty()) {
                    setTitle(title);
                }
            }
        });
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
            return "https://grok.com/";
        }
        String value = input.trim();
        Uri uri = Uri.parse(value);
        if (uri.getScheme() == null) {
            return "https://" + value;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())) {
            return "https://grok.com/";
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
