package com.rafael.groksessions;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Process;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class WebFileChooserTest {
    private PickerActivity activity;
    private WebFileChooser chooser;
    private WebView owner;
    private final List<Uri[]> results = new ArrayList<>();

    public static class PickerActivity extends Activity {
        Intent picker;
        int code;
        boolean failLaunch;
        final Set<Uri> granted = new HashSet<>();

        @Override public void startActivityForResult(Intent intent, int requestCode) {
            if (failLaunch) throw new ActivityNotFoundException();
            picker = intent;
            code = requestCode;
        }

        @Override public int checkUriPermission(Uri uri, int pid, int uid, int flags) {
            return granted.contains(uri) ? PackageManager.PERMISSION_GRANTED : PackageManager.PERMISSION_DENIED;
        }
    }

    @Before public void prepare() {
        activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        chooser = new WebFileChooser(activity);
        owner = new WebView(activity);
        ProviderInfo provider = new ProviderInfo();
        provider.authority = "test.documents";
        provider.packageName = "test.documents.provider";
        provider.name = "TestDocumentProvider";
        provider.applicationInfo = new ApplicationInfo();
        provider.applicationInfo.uid = Process.myUid() + 1;
        provider.applicationInfo.packageName = provider.packageName;
        shadowOf(activity.getPackageManager()).addOrUpdateProvider(provider);
    }

    private WebChromeClient.FileChooserParams params(boolean multiple) {
        return new WebChromeClient.FileChooserParams() {
            @Override public int getMode() { return multiple ? MODE_OPEN_MULTIPLE : MODE_OPEN; }
            @Override public String[] getAcceptTypes() { return new String[]{"image/*"}; }
            @Override public boolean isCaptureEnabled() { return false; }
            @Override public CharSequence getTitle() { return null; }
            @Override public String getFilenameHint() { return null; }
            @Override public Intent createIntent() { return new Intent(Intent.ACTION_GET_CONTENT).setType("image/*"); }
        };
    }

    private Uri granted(String path) {
        Uri uri = Uri.parse("content://test.documents/" + path);
        activity.granted.add(uri);
        return uri;
    }

    @Test public void opensTheSystemPickerWithTheRequestedTypeAndSelectionMode() {
        chooser.show(owner, results::add, params(true));
        assertEquals(Intent.ACTION_GET_CONTENT, activity.picker.getAction());
        assertEquals("image/*", activity.picker.getType());
        assertTrue(activity.picker.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(activity.picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertTrue((activity.picker.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test public void deliversSingleSelectedFileExactlyOnce() {
        chooser.show(owner, results::add, params(false));
        Uri file = granted("first.png");
        assertTrue(chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(file)));
        assertArrayEquals(new Uri[]{file}, results.get(0));
        chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(file));
        chooser.cancel();
        assertEquals(1, results.size());
    }

    @Test public void preservesMultipleSelectionOrderAndRemovesDuplicates() {
        chooser.show(owner, results::add, params(true));
        Uri first = granted("one.jpg");
        Uri second = granted("two.png");
        ClipData clips = ClipData.newRawUri("Images", first);
        clips.addItem(new ClipData.Item(second));
        clips.addItem(new ClipData.Item(first));
        Intent result = new Intent();
        result.setClipData(clips);
        chooser.onResult(activity.code, Activity.RESULT_OK, result);
        assertArrayEquals(new Uri[]{first, second}, results.get(0));
    }

    @Test public void singleSelectionDoesNotDeliverExtraFilesFromThePicker() {
        chooser.show(owner, results::add, params(false));
        Uri first = granted("one.jpg");
        Uri second = granted("two.png");
        ClipData clips = ClipData.newRawUri("Images", first);
        clips.addItem(new ClipData.Item(second));
        Intent result = new Intent();
        result.setClipData(clips);
        chooser.onResult(activity.code, Activity.RESULT_OK, result);
        assertArrayEquals(new Uri[]{first}, results.get(0));
    }

    @Test public void cancelsThePendingRequestWhenTheUserClosesThePicker() {
        chooser.show(owner, results::add, params(false));
        chooser.onResult(activity.code, Activity.RESULT_CANCELED, null);
        assertEquals(1, results.size());
        assertNull(results.get(0));
        assertFalse(chooser.onResult(4001, Activity.RESULT_OK, null));
    }

    @Test public void ignoresAnOldPickerResultAfterANewRequest() {
        chooser.show(owner, results::add, params(false));
        int oldCode = activity.code;
        chooser.show(owner, results::add, params(false));
        assertEquals(1, results.size());
        assertNull(results.get(0));
        chooser.onResult(oldCode, Activity.RESULT_OK, new Intent().setData(granted("old.png")));
        assertEquals(1, results.size());
        Uri current = granted("current.png");
        chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(current));
        assertArrayEquals(new Uri[]{current}, results.get(1));
    }

    @Test public void cancelsOnlyTheWebViewWhoseDocumentClosedOrNavigated() {
        chooser.show(owner, results::add, params(false));
        chooser.cancelFor(new WebView(activity));
        assertTrue(results.isEmpty());
        chooser.cancelFor(owner);
        assertEquals(1, results.size());
        assertNull(results.get(0));
    }

    @Test public void rejectsFileUrlsAndContentWithoutAReadGrant() {
        for (Uri uri : new Uri[]{Uri.parse("file:///data/data/private/session.xml"), Uri.parse("content://test.documents/not-granted.png")}) {
            chooser.show(owner, results::add, params(false));
            chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(uri));
            assertNull(results.get(results.size() - 1));
        }
    }

    @Test public void rejectsItsOwnPrivateProviderEvenIfItCanReadTheUri() {
        ProviderInfo provider = new ProviderInfo();
        provider.authority = "test.private";
        provider.packageName = activity.getPackageName();
        provider.name = "PrivateProvider";
        provider.applicationInfo = activity.getApplicationInfo();
        provider.applicationInfo.uid = Process.myUid();
        shadowOf(activity.getPackageManager()).addOrUpdateProvider(provider);
        Uri privateFile = Uri.parse("content://test.private/secrets");
        activity.granted.add(privateFile);
        chooser.show(owner, results::add, params(false));
        chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(privateFile));
        assertNull(results.get(0));
    }

    @Test public void missingPickerCancelsTheCallbackSoThePageCanRetry() {
        activity.failLaunch = true;
        chooser.show(owner, results::add, params(false));
        assertEquals(1, results.size());
        assertNull(results.get(0));
        activity.failLaunch = false;
        chooser.show(owner, results::add, params(false));
        Uri file = granted("retry.png");
        chooser.onResult(activity.code, Activity.RESULT_OK, new Intent().setData(file));
        assertArrayEquals(new Uri[]{file}, results.get(1));
    }
}
