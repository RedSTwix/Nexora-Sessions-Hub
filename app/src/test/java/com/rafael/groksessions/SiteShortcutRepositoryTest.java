package com.rafael.groksessions;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.Collections;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public final class SiteShortcutRepositoryTest {
    private Context context;
    private SiteShortcutRepository first, second;

    @Before public void prepare() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("account_manager", 0).edit().clear().commit();
        first = new SiteShortcutRepository(context, "first");
        second = new SiteShortcutRepository(context, "second");
    }

    @Test public void eachProfileStartsEmptyAndRetainsItsOwnSitesAfterReopening() {
        assertTrue(first.getAll().isEmpty());
        SiteShortcut site = first.add("Meu site", "example.com/path?one=1#part", null);
        assertEquals("https://example.com/path?one=1#part", site.url);
        assertTrue(second.getAll().isEmpty());
        second.add("Outro nome", site.url, null);
        assertEquals("Meu site", new SiteShortcutRepository(context, "first").getAll().get(0).name);
        assertEquals("Outro nome", new SiteShortcutRepository(context, "second").getAll().get(0).name);
    }

    @Test public void editingMovingAndRemovingNeverChangeAnotherProfile() {
        SiteShortcut one = first.add("Um", "one.example.test", null);
        SiteShortcut two = first.add("Dois", "two.example.test", null);
        SiteShortcut other = second.add("Outro", one.url, null);
        first.move(two.id, -1);
        assertEquals(two.id, first.getAll().get(0).id);
        first.edit(one.id, "Renomeado", "https://one.example.test/new", null);
        assertEquals(one.id, first.getAll().get(1).id);
        assertEquals("Renomeado", first.getAll().get(1).name);
        first.remove(two.id);
        assertEquals(1, first.getAll().size());
        assertEquals(other.url, second.getAll().get(0).url);
        assertEquals("Outro", second.getAll().get(0).name);
    }

    @Test public void duplicateAddressesAreRejectedOnlyWithinTheirProfile() {
        SiteShortcut one = first.add("Um", "example.com", null);
        assertThrows(IllegalArgumentException.class, () -> first.add("Duplicado", one.url, null));
        assertThrows(IllegalArgumentException.class, () -> first.add("Duplicado", "HTTPS://EXAMPLE.COM:443/", null));
        SiteShortcut two = first.add("Dois", "other.example.test", null);
        assertThrows(IllegalArgumentException.class, () -> first.edit(two.id, "Dois", one.url, null));
        second.add("Mesmo site", one.url, null);
        first.edit(one.id, "Novo nome", one.url, null);
        assertEquals("Novo nome", first.getAll().get(0).name);
    }

    @Test public void faviconsAreScopedToTheProfileAndOriginAndClearAfterChangingDomains() {
        SiteShortcut one = first.add("Um", "https://example.test/one", null);
        first.add("Outro caminho", "https://example.test/two", null);
        first.add("HTTP", "http://example.test/", null);
        second.add("Outro perfil", one.url, null);
        first.updateIcon("https://example.test:443/visited", Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888));
        assertNotNull(first.getAll().get(0).icon);
        assertEquals(first.getAll().get(0).icon, first.getAll().get(1).icon);
        assertNull(first.getAll().get(2).icon);
        assertNull(second.getAll().get(0).icon);
        first.edit(one.id, one.name, "https://different.example.test/", null);
        assertNull(first.getAll().get(0).icon);
    }

    @Test public void profileRenamingAndMovingKeepShortcutsAndDeletionCleansOnlyThatProfile() {
        AccountRepository accounts = new AccountRepository(context);
        AccountFolder folder = accounts.addFolder("Pasta");
        Account account = accounts.add("Conta", folder.id);
        SiteShortcutRepository sites = new SiteShortcutRepository(context, account.id);
        sites.add("Site", "example.test", null);
        second.add("Outro", "example.test", null);
        accounts.renameAccount(account.id, "Outro nome");
        accounts.moveAccounts(Collections.singleton(account.id), accounts.addFolder("Destino").id);
        assertEquals(1, sites.getAll().size());
        accounts.remove(account.id);
        assertTrue(sites.getAll().isEmpty());
        assertEquals(1, second.getAll().size());
    }

    @Test public void corruptEntriesDoNotHideValidShortcutsAndInvalidUrlsCannotBeStored() throws Exception {
        JSONArray values = new JSONArray().put(new JSONObject().put("id", "bad").put("name", "Bad").put("url", "javascript:alert(1)"))
                .put(new SiteShortcut("good", "Bom", "https://example.test", null).toJson());
        context.getSharedPreferences("account_manager", 0).edit()
                .putString(SiteShortcutRepository.preferenceKey("first"), values.toString()).commit();
        assertEquals(1, first.getAll().size());
        assertEquals("good", first.getAll().get(0).id);
        for (String url : new String[]{"", "javascript:alert(1)", "file:/secret", "content://private/file", "data:text/html,hello",
                "https://user:password@example.test", "https://", "https://example.test:99999", "https://example.test/a b"})
            assertThrows("URL aceita: " + url, IllegalArgumentException.class, () -> first.add("Inválido", url, null));
    }

    @Test public void originsNormalizePortsAndUntrustedIconDataFallsBackSafely() {
        assertEquals("https://example.test", SiteShortcut.origin("https://EXAMPLE.test:443/path?q=x"));
        assertEquals("http://example.test:8080", SiteShortcut.origin("http://example.test:8080/a"));
        assertEquals("https://example.test:8080/a", SiteShortcut.normalizeUrl("example.test:8080/a"));
        assertEquals("https://example.test/", SiteShortcut.normalizeUrl("EXAMPLE.test"));
        assertEquals("https://example.test/a%2Fb?token=a%2Bb#part", SiteShortcut.normalizeUrl("https://example.test/a%2Fb?token=a%2Bb#part"));
        assertEquals("", SiteShortcut.origin("about:blank"));
        assertNull(ShortcutIcon.decode("invalid-base64"));
        assertNull(ShortcutIcon.decode("x".repeat(50001)));
        assertThrows(IllegalArgumentException.class, () -> first.add(" ", "example.test", null));
    }
}
