package com.rafael.groksessions;

import android.content.Context;
import android.content.SharedPreferences;
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
public final class AccountNameUniquenessTest {
    private AccountRepository repository;
    private AccountFolder firstFolder;
    private AccountFolder secondFolder;
    private Account original;

    @Before public void prepare() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences("account_manager", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        repository = new AccountRepository(context);
        firstFolder = repository.addFolder("Pessoal");
        secondFolder = repository.addFolder("Trabalho");
        original = repository.add("Grok Pessoal", firstFolder.id);
    }

    @Test public void duplicateNameInSameFolderIsRejectedWithoutCreatingAnotherProfile() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.add("Grok Pessoal", firstFolder.id));
        assertEquals("Já existe um perfil com esse nome.", error.getMessage());
        assertEquals(1, repository.getAll().size());
    }

    @Test public void duplicateNameInAnotherFolderIsRejectedRegardlessOfCaseAndEdgeSpaces() {
        for (String candidate : new String[]{"Grok Pessoal", "grok pessoal", "  GROK PESSOAL  "}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> repository.add(candidate, secondFolder.id));
            assertEquals("Já existe um perfil com esse nome.", error.getMessage());
        }
        assertEquals(1, repository.getAll().size());
        assertEquals(firstFolder.id, repository.getAll().get(0).folderId);
    }

    @Test public void renamingToAnotherProfilesNameIsRejectedWithoutChangingEitherAccount() {
        Account second = repository.add("Outra Conta", secondFolder.id);
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> repository.renameAccount(second.id, "  grok pessoal "));
        assertEquals("Já existe um perfil com esse nome.", error.getMessage());
        assertEquals(2, repository.getAll().size());
        assertEquals("Outra Conta", repository.getAll().stream()
                .filter(item -> item.id.equals(second.id)).findFirst().get().name);
    }

    @Test public void changingOnlyTheCurrentProfilesCaseIsAllowed() {
        repository.renameAccount(original.id, "GROK PESSOAL");
        assertEquals("GROK PESSOAL", repository.getAll().get(0).name);
        repository.renameAccount(original.id, "  GROK PESSOAL  ");
        assertEquals("GROK PESSOAL", repository.getAll().get(0).name);
        assertEquals(original.id, repository.getAll().get(0).id);
        assertEquals(original.privacySeed, repository.getAll().get(0).privacySeed);
    }

    @Test public void uniqueProfileNamesAndMovingBetweenFoldersContinueWorking() {
        Account second = repository.add("Grok Trabalho", secondFolder.id);
        repository.moveAccounts(Collections.singleton(original.id), secondFolder.id);
        assertEquals(2, repository.getForFolder(secondFolder.id).size());
        assertEquals(0, repository.getForFolder(firstFolder.id).size());
        assertEquals(2, repository.getAll().size());
        assertEquals(second.id, repository.getAll().stream()
                .filter(item -> item.name.equals("Grok Trabalho")).findFirst().get().id);
    }

    @Test public void deletingAProfileReleasesItsNameForReuse() {
        repository.remove(original.id);
        Account replacement = repository.add("  grok pessoal ", secondFolder.id);
        assertEquals("grok pessoal", replacement.name);
        assertNotEquals(original.id, replacement.id);
        assertEquals(1, repository.getAll().size());
    }

    @Test public void emptyNamesAreRejectedByTheRepositoryWithoutChangingStoredProfiles() {
        assertThrows(IllegalArgumentException.class, () -> repository.add("   ", secondFolder.id));
        assertThrows(IllegalArgumentException.class, () -> repository.renameAccount(original.id, ""));
        assertEquals("Grok Pessoal", repository.getAll().get(0).name);
    }
}
