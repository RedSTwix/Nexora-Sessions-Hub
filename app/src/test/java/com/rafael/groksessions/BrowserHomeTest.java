package com.rafael.groksessions;

import android.view.View;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.ProgressBar;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class BrowserHomeTest {
    private BrowserActivity activity;
    private WebView web;
    private SpeedDialView home;
    private BrowserDownloads downloads;

    @Before public void prepare() {
        activity = Robolectric.buildActivity(BrowserActivity.class).get();
        activity.setTheme(android.R.style.Theme_Material_NoActionBar);
        web = new WebView(activity);
        downloads = new BrowserDownloads(activity);
        home = new SpeedDialView(activity, new SiteShortcutRepository(activity, "test-home"), "Teste", url ->
                ReflectionHelpers.callInstanceMethod(activity, "navigateTo", from(String.class, url)));
        ReflectionHelpers.setField(activity, "webView", web);
        ReflectionHelpers.setField(activity, "speedDial", home);
        ReflectionHelpers.setField(activity, "browserDownloads", downloads);
        ReflectionHelpers.setField(activity, "fileChooser", new WebFileChooser(activity));
        ReflectionHelpers.setField(activity, "address", new EditText(activity));
        ReflectionHelpers.setField(activity, "progress", new ProgressBar(activity));
    }

    @After public void finish() { home.closeDialogs(); downloads.close(); web.destroy(); }

    @Test public void openingHomeDoesNotRequestGoogleOrAnyOtherWebsite() {
        ReflectionHelpers.callInstanceMethod(activity, "showHome", from(boolean.class, false));
        assertNull(shadowOf(web).getLastLoadedUrl());
        assertEquals(View.VISIBLE, home.getVisibility());
        assertEquals(View.GONE, web.getVisibility());
    }

    @Test public void shortcutOpensAWebPageAndBackReturnsHomeThenClosesTheProfile() {
        ReflectionHelpers.callInstanceMethod(activity, "showHome", from(boolean.class, false));
        ReflectionHelpers.callInstanceMethod(activity, "navigateTo", from(String.class, "https://example.test/path"));
        assertEquals("https://example.test/path", shadowOf(web).getLastLoadedUrl());
        assertEquals(View.GONE, home.getVisibility());
        ReflectionHelpers.callInstanceMethod(activity, "handleBackNavigation");
        assertEquals(View.VISIBLE, home.getVisibility());
        assertFalse(activity.isFinishing());
        ReflectionHelpers.callInstanceMethod(activity, "handleBackNavigation");
        assertTrue(activity.isFinishing());
    }

    @Test public void aRequestedGoogleLinkStillNavigatesAndHomeCanReturnToTheOpenPage() {
        String incoming = "https://www.google.com/search?q=example";
        ReflectionHelpers.callInstanceMethod(activity, "navigateTo", from(String.class, incoming));
        assertEquals(incoming, shadowOf(web).getLastLoadedUrl());
        ReflectionHelpers.callInstanceMethod(activity, "showHome", from(boolean.class, true));
        ReflectionHelpers.callInstanceMethod(activity, "handleBackNavigation");
        assertEquals(View.GONE, home.getVisibility());
        assertEquals(incoming, shadowOf(web).getLastLoadedUrl());
        assertFalse(activity.isFinishing());
    }
}
