package com.rafael.groksessions;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class ProfileIdentityTest {
    @Test public void createsIndependentValidSeeds() {
        Set<String> seeds = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            String seed = ProfileIdentity.newSeed();
            assertTrue(seed.matches("[0-9a-f]{64}"));
            assertTrue(seeds.add(seed));
        }
    }

    @Test public void oldAccountsNeverReceiveAnIdentityWhenReadOrSaved() throws Exception {
        JSONObject old = new JSONObject("{\"id\":\"existing\",\"name\":\"Existing account\",\"folderId\":\"folder\"}");
        Account account = Account.fromJson(old, "legacy");
        assertNull(account.privacySeed);
        Account changed = account.renamed("New name").movedTo("other");
        assertNull(changed.privacySeed);
        assertFalse(changed.toJson().has("privacySeed"));
        assertNull(Account.fromJson(changed.toJson(), "legacy").privacySeed);
    }

    @Test public void preservesNewIdentityAcrossSavingRenamingAndMoving() throws Exception {
        String seed = ProfileIdentity.newSeed();
        Account account = new Account("new", "New account", "folder", seed);
        for (int i = 0; i < 10; i++) {
            account = Account.fromJson(account.toJson(), "legacy").renamed("Renamed").movedTo("other");
            assertEquals(seed, account.privacySeed);
        }
        assertEquals("new", account.id);
        assertEquals("Renamed", account.name);
        assertEquals("other", account.folderId);
    }

    @Test public void treatsMissingOrInvalidIdentityAsLegacyWithoutRegeneratingIt() throws Exception {
        Account legacy = Account.fromJson(new JSONObject("{\"id\":\"old\",\"name\":\"Old\"}"), "legacy");
        assertNull(legacy.privacySeed);
        assertEquals("legacy", legacy.folderId);
        assertNull(new Account("old", "Old", "folder", "invalid").privacySeed);
        assertNull(new Account("old", "Old", "folder").privacySeed);
    }
}
