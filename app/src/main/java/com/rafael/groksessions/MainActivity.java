package com.rafael.groksessions;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Browser;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.ProfileStore;
import androidx.webkit.WebViewFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final String GROK_URL = "https://grok.com/";
    private static final int REQUEST_AUTH_BROWSER = 4001;
    private static final String EXTRA_LAUNCH_AUTH_TAB = "androidx.browser.auth.extra.LAUNCH_AUTH_TAB";
    private static final String EXTRA_REDIRECT_SCHEME = "androidx.browser.auth.extra.REDIRECT_SCHEME";
    private static final String EXTRA_HTTPS_REDIRECT_HOST = "androidx.browser.auth.extra.HTTPS_REDIRECT_HOST";
    private static final String EXTRA_HTTPS_REDIRECT_PATH = "androidx.browser.auth.extra.HTTPS_REDIRECT_PATH";

    private AccountRepository repository;
    private LinearLayout contentList;
    private ScrollView contentScroll;
    private Button primaryAction;
    private View dualSwitchButton;
    private Button organizeButton;
    private Button selectButton;
    private TextView sectionTitle;
    private TextView sectionSubtitle;
    private TextView automationStatus;
    private AccountFolder selectedFolder;
    private String pendingUrl;
    private String sourcePackage;
    private boolean returnResultToCaller;
    private String redirectScheme;
    private String redirectHost;
    private String redirectPath;
    private boolean externalIntentHandled;
    private boolean waitingForAccessibility;
    private boolean organizeMode;
    private boolean selectionMode;
    private final Set<String> selectedAccountIds = new HashSet<>();
    private final Set<String> organizeSelectedItemIds = new HashSet<>();

    private static final int COLOR_PAGE = Color.rgb(3, 11, 17);
    private static final int COLOR_SURFACE = Color.rgb(10, 23, 33);
    private static final int COLOR_SURFACE_ALT = Color.rgb(13, 30, 42);
    private static final int COLOR_LINE = Color.rgb(23, 54, 70);
    private static final int COLOR_TEXT = Color.rgb(242, 248, 252);
    private static final int COLOR_MUTED = Color.rgb(142, 164, 179);
    private static final int COLOR_CYAN = Color.rgb(34, 238, 226);
    private static final int COLOR_VIOLET = Color.rgb(139, 92, 246);
    private static final int COLOR_SUCCESS = Color.rgb(53, 232, 155);
    private static final int COLOR_DANGER = Color.rgb(255, 102, 133);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new AccountRepository(this);
        SecureSessionStore.clearLegacyCallbacks(this);
        cleanPendingProfiles();
        buildScreen();
        captureAuthContract(getIntent());
        captureIncomingUrl(getIntent());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAutomationStatus();
        if (waitingForAccessibility && DualAppAutomationService.isEnabled(this)) {
            waitingForAccessibility = false;
            startAccessibilitySwitch();
        }
        renderContent();
        if (pendingUrl != null && !externalIntentHandled) {
            externalIntentHandled = true;
            chooseAccountFor(pendingUrl);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        externalIntentHandled = false;
        captureAuthContract(intent);
        captureIncomingUrl(intent);
        if (pendingUrl != null) {
            externalIntentHandled = true;
            chooseAccountFor(pendingUrl);
        }
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(12));
        root.setBackground(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(3, 15, 23), COLOR_PAGE, Color.rgb(2, 8, 13)}
        ));

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setOrientation(LinearLayout.HORIZONTAL);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.nexora_logo);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setContentDescription("Nexora");
        applyGlow(logo, Color.rgb(0, 190, 235), 8);
        brandRow.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout brandCopy = new LinearLayout(this);
        brandCopy.setOrientation(LinearLayout.VERTICAL);
        brandCopy.setPadding(dp(11), 0, 0, 0);
        TextView brand = text("NEXORA", 17, COLOR_TEXT);
        brand.setTypeface(null, Typeface.BOLD);
        brand.setLetterSpacing(0.08f);
        applyTextGlow(brand, Color.rgb(55, 164, 220), 2);
        brandCopy.addView(brand, matchWrap());
        TextView product = text("SESSION HUB", 11, COLOR_CYAN);
        product.setTypeface(null, Typeface.BOLD);
        product.setLetterSpacing(0.16f);
        applyTextGlow(product, COLOR_CYAN, 3);
        brandCopy.addView(product, matchWrap());
        brandRow.addView(brandCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(10), dp(7), dp(10), dp(7));
        badge.setBackground(roundedBackground(Color.rgb(7, 35, 35), 18, Color.rgb(26, 110, 90), 1));
        applyGlow(badge, Color.rgb(0, 230, 170), 5);
        View badgeDot = new View(this);
        badgeDot.setBackground(roundedBackground(Color.rgb(0, 245, 190), 6, Color.TRANSPARENT, 0));
        LinearLayout.LayoutParams badgeDotParams = new LinearLayout.LayoutParams(dp(9), dp(9));
        badgeDotParams.setMargins(0, 0, dp(8), 0);
        badge.addView(badgeDot, badgeDotParams);
        TextView badgeText = text("LOCAL  •  SEGURO", 10, COLOR_SUCCESS);
        badgeText.setTypeface(null, Typeface.BOLD);
        badge.addView(badgeText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        brandRow.addView(badge);

        TextView settingsButton = text("⚙", 20, COLOR_CYAN);
        settingsButton.setGravity(Gravity.CENTER);
        settingsButton.setIncludeFontPadding(false);
        settingsButton.setContentDescription("Configurações de disponibilidade");
        settingsButton.setBackground(pressableBackground(
                COLOR_SURFACE_ALT,
                Color.rgb(17, 45, 59),
                Color.rgb(24, 88, 112),
                18
        ));
        settingsButton.setOnClickListener(view -> showAvailabilitySettings());
        applyTextGlow(settingsButton, COLOR_CYAN, 4);
        applyGlow(settingsButton, Color.rgb(0, 189, 240), 7);
        LinearLayout.LayoutParams headerSettingsParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        headerSettingsParams.setMargins(dp(7), 0, 0, 0);
        brandRow.addView(settingsButton, headerSettingsParams);
        root.addView(brandRow, matchWrap());

        TextView eyebrow = text("CONTAS ISOLADAS", 11, COLOR_CYAN);
        eyebrow.setTypeface(null, Typeface.BOLD);
        eyebrow.setLetterSpacing(0.18f);
        applyTextGlow(eyebrow, COLOR_CYAN, 3);
        LinearLayout.LayoutParams eyebrowParams = matchWrap();
        eyebrowParams.setMargins(dp(2), dp(18), 0, 0);
        root.addView(eyebrow, eyebrowParams);
        TextView intro = text("Navegação separada. Mais privacidade para você.", 11, COLOR_MUTED);
        LinearLayout.LayoutParams introParams = matchWrap();
        introParams.setMargins(dp(2), dp(2), 0, dp(14));
        root.addView(intro, introParams);

        if (!supportsProfiles()) {
            TextView warning = text(
                    "O Android System WebView deste aparelho não oferece perfis separados. Atualize o WebView pela Play Store.",
                    13,
                    COLOR_DANGER
            );
            warning.setPadding(dp(14), dp(14), dp(14), dp(14));
            warning.setBackground(roundedBackground(Color.rgb(39, 17, 26), 14, Color.rgb(98, 35, 55), 1));
            LinearLayout.LayoutParams warningParams = matchWrap();
            warningParams.setMargins(0, 0, 0, dp(12));
            root.addView(warning, warningParams);
        }

        LinearLayout quickActions = new LinearLayout(this);
        quickActions.setOrientation(LinearLayout.HORIZONTAL);
        quickActions.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout dualAction = new LinearLayout(this);
        dualAction.setGravity(Gravity.CENTER_VERTICAL);
        dualAction.setPadding(dp(14), 0, dp(12), 0);
        dualAction.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(7, 43, 57), Color.rgb(8, 33, 62), Color.rgb(34, 39, 105)},
                16,
                Color.rgb(16, 150, 220),
                1
        ));
        applyGlow(dualAction, Color.rgb(18, 116, 255), 10);
        TextView switchIcon = text("↻", 27, COLOR_CYAN);
        switchIcon.setGravity(Gravity.CENTER);
        applyTextGlow(switchIcon, COLOR_CYAN, 5);
        dualAction.addView(switchIcon, new LinearLayout.LayoutParams(dp(52), dp(58)));
        LinearLayout dualCopy = new LinearLayout(this);
        dualCopy.setOrientation(LinearLayout.VERTICAL);
        TextView dualTitle = text("Trocar Grok Dual", 14, COLOR_TEXT);
        dualTitle.setTypeface(null, Typeface.BOLD);
        applyTextGlow(dualTitle, Color.rgb(63, 135, 224), 2);
        dualCopy.addView(dualTitle, matchWrap());
        dualCopy.addView(text("Alternar ambiente de navegação", 10, COLOR_MUTED), matchWrap());
        dualAction.addView(dualCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dualArrow = text("›", 27, COLOR_MUTED);
        dualArrow.setGravity(Gravity.CENTER);
        dualAction.addView(dualArrow, new LinearLayout.LayoutParams(dp(24), dp(58)));
        dualSwitchButton = dualAction;
        dualSwitchButton.setOnClickListener(view -> confirmDualSwitch());
        quickActions.addView(dualSwitchButton, new LinearLayout.LayoutParams(
                0,
                dp(64),
                1f
        ));

        primaryAction = primaryButton("＋");
        primaryAction.setContentDescription("Criar pasta");
        primaryAction.setTextSize(25);
        applyGlow(primaryAction, Color.rgb(104, 76, 255), 12);
        primaryAction.setOnClickListener(view -> {
            if (selectedFolder == null) {
                showAddFolderDialog(null);
            } else {
                showAddAccountDialog(selectedFolder, null);
            }
        });
        LinearLayout.LayoutParams createParams = new LinearLayout.LayoutParams(dp(58), dp(64));
        createParams.setMargins(dp(8), 0, 0, 0);
        quickActions.addView(primaryAction, createParams);
        root.addView(quickActions, matchWrap());

        automationStatus = text("", 1, Color.TRANSPARENT);

        LinearLayout managerHeader = new LinearLayout(this);
        managerHeader.setOrientation(LinearLayout.HORIZONTAL);
        managerHeader.setGravity(Gravity.CENTER_VERTICAL);
        managerHeader.setPadding(dp(2), 0, 0, 0);

        LinearLayout sectionCopy = new LinearLayout(this);
        sectionCopy.setOrientation(LinearLayout.VERTICAL);
        sectionTitle = text("Pastas de contas", 20, COLOR_TEXT);
        sectionTitle.setTypeface(null, Typeface.BOLD);
        applyTextGlow(sectionTitle, Color.rgb(25, 102, 155), 2);
        sectionCopy.addView(sectionTitle, matchWrap());
        sectionSubtitle = text("Gerencie suas pastas de contas isoladas.", 11, COLOR_MUTED);
        sectionSubtitle.setPadding(0, dp(2), 0, 0);
        sectionCopy.addView(sectionSubtitle, matchWrap());
        LinearLayout.LayoutParams sectionParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        managerHeader.addView(sectionCopy, sectionParams);

        selectButton = miniButton("Selecionar");
        selectButton.setVisibility(View.GONE);
        selectButton.setOnClickListener(view -> toggleSelectionMode());
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        selectParams.setMargins(dp(6), 0, 0, 0);
        managerHeader.addView(selectButton, selectParams);

        organizeButton = miniButton("☰   Organizar");
        organizeButton.setTextColor(COLOR_TEXT);
        organizeButton.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(8, 30, 43), Color.rgb(8, 23, 38)},
                15,
                Color.rgb(13, 101, 135),
                1
        ));
        applyGlow(organizeButton, Color.rgb(0, 149, 207), 6);
        organizeButton.setOnClickListener(view -> toggleOrganizeMode());
        LinearLayout.LayoutParams organizeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        organizeParams.setMargins(dp(6), 0, 0, 0);
        managerHeader.addView(organizeButton, organizeParams);
        LinearLayout.LayoutParams managerHeaderParams = matchWrap();
        managerHeaderParams.setMargins(0, dp(20), 0, dp(12));
        root.addView(managerHeader, managerHeaderParams);

        contentScroll = new ScrollView(this);
        contentScroll.setFillViewport(true);
        contentScroll.setClipToPadding(false);
        contentScroll.setPadding(0, 0, 0, dp(8));
        contentList = new LinearLayout(this);
        contentList.setOrientation(LinearLayout.VERTICAL);
        contentScroll.addView(contentList, matchWrap());
        root.addView(contentScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        setContentView(root);
    }

    private void renderContent() {
        if (contentList == null) {
            return;
        }
        contentList.removeAllViews();
        organizeButton.setText(organizeMode ? "✓   Concluir" : "☰   Organizar");
        selectButton.setVisibility(selectedFolder == null || organizeMode ? View.GONE : View.VISIBLE);
        selectButton.setText(selectionMode ? "Cancelar" : "Selecionar");
        if (selectedFolder == null) {
            renderFolders();
        } else {
            renderAccountsInFolder();
        }
    }

    private void renderFolders() {
        primaryAction.setText("＋");
        primaryAction.setContentDescription("Criar pasta");
        sectionTitle.setText(R.string.account_folders_title);
        sectionSubtitle.setText(R.string.account_folders_description);
        List<AccountFolder> folders = repository.getFolders();
        if (organizeMode && !folders.isEmpty()) {
            addManagerHint("Toque para selecionar. As setas movem todos os cards verdes juntos.");
        }
        if (folders.isEmpty()) {
            contentList.addView(emptyState(
                    "◇",
                    "Nenhuma pasta ainda",
                    "Crie uma pasta para agrupar suas contas e manter cada sessão organizada."
            ), matchWrap());
            return;
        }
        for (AccountFolder folder : folders) {
            addCard(folderCard(folder));
        }
    }

    private void renderAccountsInFolder() {
        primaryAction.setText("＋");
        primaryAction.setContentDescription("Adicionar conta");
        sectionTitle.setText(selectedFolder.name);
        sectionSubtitle.setText(R.string.folder_accounts_description);

        Button backToFolders = compactButton("‹  Voltar às pastas");
        backToFolders.setOnClickListener(view -> leaveFolder());
        LinearLayout.LayoutParams backParams = matchWrap();
        backParams.setMargins(0, 0, 0, dp(10));
        contentList.addView(backToFolders, backParams);

        if (selectionMode) {
            addSelectionToolbar();
        } else if (organizeMode) {
            addManagerHint("Toque para selecionar. As setas movem todos os cards verdes juntos.");
        }

        List<Account> accounts = repository.getForFolder(selectedFolder.id);
        if (accounts.isEmpty()) {
            contentList.addView(emptyState(
                    "◎",
                    "Pasta vazia",
                    "Adicione uma conta para criar uma nova sessão isolada do Grok."
            ), matchWrap());
            return;
        }

        for (Account account : accounts) {
            addCard(accountCard(account));
        }
    }

    private LinearLayout folderCard(AccountFolder folder) {
        LinearLayout card = card();
        card.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(8, 30, 42), Color.rgb(6, 25, 39), Color.rgb(8, 30, 49)},
                17,
                Color.rgb(11, 105, 136),
                1
        ));
        applyGlow(card, Color.rgb(0, 119, 174), 5);
        if (organizeMode && organizeSelectedItemIds.contains(folder.id)) {
            highlightMovedCard(card);
        }
        int style = repository.getFolderStyle(folder.id);
        int accent = folderAccent(style);
        boolean pinned = repository.isFolderPinned(folder.id);
        int count = repository.getForFolder(folder.id).size();

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(0, dp(2), 0, dp(2));
        summary.setOnClickListener(view -> {
            if (organizeMode) {
                toggleOrganizeItemSelection(folder.id);
            } else {
                enterFolder(folder);
            }
        });

        TextView folderIcon = text(folderSymbol(style), 22, accent);
        folderIcon.setGravity(Gravity.CENTER);
        folderIcon.setBackground(gradientStrokeBackground(
                new int[]{withAlpha(accent, 65), Color.rgb(7, 44, 48)},
                12,
                withAlpha(accent, 185),
                1
        ));
        applyTextGlow(folderIcon, accent, 4);
        applyGlow(folderIcon, accent, 7);
        summary.addView(folderIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(folder.name, 17, COLOR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name, matchWrap());
        String details = count + (count == 1 ? " conta isolada" : " contas isoladas");
        if (pinned) details = "★  Fixada  ·  " + details;
        copy.addView(text(details, 12, pinned ? accent : COLOR_MUTED), matchWrap());
        summary.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (organizeMode) {
            Set<String> moveGroup = organizeMoveGroupFor(folder.id);
            summary.addView(createReorderControls(
                    repository.canMoveFolders(moveGroup, -1),
                    view -> {
                        repository.moveFolders(selectOrganizeMoveGroup(folder.id), -1);
                        renderContent();
                    },
                    repository.canMoveFolders(moveGroup, 1),
                    view -> {
                        repository.moveFolders(selectOrganizeMoveGroup(folder.id), 1);
                        renderContent();
                    }
            ), new LinearLayout.LayoutParams(dp(76), dp(48)));
        } else {
            TextView trailing = text("⋮", 25, COLOR_MUTED);
            trailing.setGravity(Gravity.CENTER);
            trailing.setOnClickListener(view -> showFolderMenu(trailing, folder));
            summary.addView(trailing, new LinearLayout.LayoutParams(dp(42), dp(48)));
        }
        card.addView(summary, matchWrap());
        return card;
    }

    private LinearLayout accountCard(Account account) {
        boolean selected = selectedAccountIds.contains(account.id);
        boolean pinned = repository.isAccountPinned(account.id);
        LinearLayout card = card();
        card.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(8, 30, 42), Color.rgb(6, 25, 39), Color.rgb(8, 30, 49)},
                17,
                Color.rgb(11, 105, 136),
                1
        ));
        applyGlow(card, Color.rgb(0, 119, 174), 5);
        if (organizeMode && organizeSelectedItemIds.contains(account.id)) {
            highlightMovedCard(card);
        }
        if (selected) {
            card.setBackground(roundedBackground(Color.rgb(8, 38, 46), 17, COLOR_CYAN, 1));
        }

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(0, dp(2), 0, dp(2));
        summary.setOnClickListener(view -> {
            if (selectionMode) {
                toggleAccountSelection(account.id);
            } else if (organizeMode) {
                toggleOrganizeItemSelection(account.id);
            } else if (!organizeMode) {
                openBrowser(account, GROK_URL);
            }
        });

        TextView avatar = text(initialFor(account.name), 16, COLOR_PAGE);
        avatar.setTypeface(null, Typeface.BOLD);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(gradientBackground(COLOR_CYAN, COLOR_VIOLET, 14));
        applyGlow(avatar, Color.rgb(87, 83, 255), 7);
        summary.addView(avatar, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(account.name, 16, COLOR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        copy.addView(name, matchWrap());
        copy.addView(text(
                pinned ? "★  Fixada  ·  sessão protegida" : "●  Sessão protegida e separada",
                12,
                pinned ? COLOR_CYAN : COLOR_SUCCESS
        ), matchWrap());
        summary.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (organizeMode) {
            Set<String> moveGroup = organizeMoveGroupFor(account.id);
            summary.addView(createReorderControls(
                    repository.canMoveAccounts(moveGroup, account.folderId, -1),
                    view -> {
                        repository.moveAccountsWithinFolder(
                                selectOrganizeMoveGroup(account.id),
                                account.folderId,
                                -1
                        );
                        renderContent();
                    },
                    repository.canMoveAccounts(moveGroup, account.folderId, 1),
                    view -> {
                        repository.moveAccountsWithinFolder(
                                selectOrganizeMoveGroup(account.id),
                                account.folderId,
                                1
                        );
                        renderContent();
                    }
            ), new LinearLayout.LayoutParams(dp(76), dp(48)));
        } else {
            String trailingLabel = selectionMode ? (selected ? "✓" : "○") : "⋮";
            TextView trailing = text(trailingLabel, 23, selected ? COLOR_CYAN : COLOR_MUTED);
            trailing.setGravity(Gravity.CENTER);
            if (!selectionMode) {
            trailing.setOnClickListener(view -> showAccountMenu(trailing, account));
            } else {
                trailing.setOnClickListener(view -> toggleAccountSelection(account.id));
            }
            summary.addView(trailing, new LinearLayout.LayoutParams(dp(42), dp(48)));
        }
        card.addView(summary, matchWrap());
        return card;
    }

    private void enterFolder(AccountFolder folder) {
        selectedFolder = folder;
        organizeMode = false;
        organizeSelectedItemIds.clear();
        selectionMode = false;
        selectedAccountIds.clear();
        renderContent();
        scrollContentToTop();
    }

    private void leaveFolder() {
        selectedFolder = null;
        organizeMode = false;
        organizeSelectedItemIds.clear();
        selectionMode = false;
        selectedAccountIds.clear();
        renderContent();
        scrollContentToTop();
    }

    private void toggleOrganizeMode() {
        organizeMode = !organizeMode;
        organizeSelectedItemIds.clear();
        selectionMode = false;
        selectedAccountIds.clear();
        renderContent();
        scrollContentToTop();
    }

    private void toggleSelectionMode() {
        selectionMode = !selectionMode;
        organizeMode = false;
        organizeSelectedItemIds.clear();
        selectedAccountIds.clear();
        renderContent();
        scrollContentToTop();
    }

    private void toggleAccountSelection(String accountId) {
        if (!selectedAccountIds.remove(accountId)) {
            selectedAccountIds.add(accountId);
        }
        renderContent();
    }

    private void toggleOrganizeItemSelection(String itemId) {
        if (!organizeSelectedItemIds.remove(itemId)) {
            organizeSelectedItemIds.add(itemId);
        }
        renderContent();
    }

    private Set<String> organizeMoveGroupFor(String itemId) {
        Set<String> group = new HashSet<>();
        if (organizeSelectedItemIds.contains(itemId)) {
            group.addAll(organizeSelectedItemIds);
        } else {
            group.add(itemId);
        }
        return group;
    }

    private Set<String> selectOrganizeMoveGroup(String itemId) {
        if (!organizeSelectedItemIds.contains(itemId)) {
            organizeSelectedItemIds.clear();
            organizeSelectedItemIds.add(itemId);
        }
        return new HashSet<>(organizeSelectedItemIds);
    }

    private void addManagerHint(String message) {
        TextView hint = text(message, 12, COLOR_CYAN);
        hint.setGravity(Gravity.CENTER_VERTICAL);
        hint.setPadding(dp(12), dp(10), dp(12), dp(10));
        hint.setBackground(roundedBackground(Color.rgb(7, 38, 44), 12, Color.rgb(20, 91, 94), 1));
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        contentList.addView(hint, params);
    }

    private LinearLayout createReorderControls(
            boolean canMoveUp,
            View.OnClickListener moveUp,
            boolean canMoveDown,
            View.OnClickListener moveDown
    ) {
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);

        TextView up = reorderArrow("↑", canMoveUp, moveUp);
        controls.addView(up, new LinearLayout.LayoutParams(dp(34), dp(38)));

        TextView down = reorderArrow("↓", canMoveDown, moveDown);
        LinearLayout.LayoutParams downParams = new LinearLayout.LayoutParams(dp(34), dp(38));
        downParams.setMargins(dp(4), 0, 0, 0);
        controls.addView(down, downParams);
        return controls;
    }

    private TextView reorderArrow(String arrow, boolean enabled, View.OnClickListener listener) {
        TextView control = text(arrow, 20, enabled ? COLOR_CYAN : COLOR_MUTED);
        control.setGravity(Gravity.CENTER);
        control.setIncludeFontPadding(false);
        control.setEnabled(enabled);
        control.setAlpha(enabled ? 1f : 0.32f);
        control.setBackground(roundedBackground(
                enabled ? Color.rgb(7, 43, 52) : COLOR_SURFACE,
                11,
                enabled ? Color.rgb(14, 128, 145) : COLOR_LINE,
                1
        ));
        if (enabled) {
            control.setOnClickListener(listener);
            applyTextGlow(control, COLOR_CYAN, 3);
        }
        return control;
    }

    private void highlightMovedCard(View card) {
        card.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(8, 40, 38), Color.rgb(6, 29, 35), Color.rgb(8, 36, 43)},
                17,
                Color.rgb(31, 224, 145),
                2
        ));
        applyGlow(card, Color.rgb(31, 224, 145), 8);
    }

    private void addSelectionToolbar() {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(8), dp(8), dp(8));
        toolbar.setBackground(roundedBackground(COLOR_SURFACE, 13, COLOR_LINE, 1));

        TextView count = text(
                selectedAccountIds.size() + (selectedAccountIds.size() == 1 ? " selecionada" : " selecionadas"),
                12,
                selectedAccountIds.isEmpty() ? COLOR_MUTED : COLOR_CYAN
        );
        count.setTypeface(null, Typeface.BOLD);
        toolbar.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button all = miniButton("Todas");
        all.setOnClickListener(view -> selectAllVisibleAccounts());
        toolbar.addView(all, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));

        Button move = miniButton("Mover");
        move.setEnabled(!selectedAccountIds.isEmpty());
        move.setAlpha(selectedAccountIds.isEmpty() ? 0.45f : 1f);
        move.setOnClickListener(view -> showMoveAccountsDialog(new HashSet<>(selectedAccountIds)));
        LinearLayout.LayoutParams moveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
        );
        moveParams.setMargins(dp(6), 0, 0, 0);
        toolbar.addView(move, moveParams);

        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        contentList.addView(toolbar, params);
    }

    private void selectAllVisibleAccounts() {
        if (selectedFolder == null) return;
        List<Account> visible = repository.getForFolder(selectedFolder.id);
        boolean everyVisibleSelected = !visible.isEmpty();
        for (Account account : visible) {
            if (!selectedAccountIds.contains(account.id)) {
                everyVisibleSelected = false;
                break;
            }
        }
        for (Account account : visible) {
            if (everyVisibleSelected) selectedAccountIds.remove(account.id);
            else selectedAccountIds.add(account.id);
        }
        renderContent();
    }

    private void showFolderMenu(View anchor, AccountFolder folder) {
        List<NexoraMenuAction> actions = new ArrayList<>();
        actions.add(new NexoraMenuAction(
                "⌖",
                repository.isFolderPinned(folder.id) ? "Desafixar" : "Fixar no topo",
                false,
                () -> {
                repository.toggleFolderPinned(folder.id);
                renderContent();
                }
        ));
        actions.add(new NexoraMenuAction("✎", "Renomear", false, () -> showRenameFolderDialog(folder)));
        actions.add(new NexoraMenuAction("✦", "Personalizar", false, () -> showFolderStyleDialog(folder)));
        actions.add(new NexoraMenuAction("⌫", "Excluir pasta", true, () -> confirmDeleteFolder(folder)));
        showNexoraActionMenu(anchor, actions);
    }

    private void showAccountMenu(View anchor, Account account) {
        List<NexoraMenuAction> actions = new ArrayList<>();
        actions.add(new NexoraMenuAction("↗", "Abrir sessão", false, () -> openBrowser(account, GROK_URL)));
        actions.add(new NexoraMenuAction(
                "⌖",
                repository.isAccountPinned(account.id) ? "Desafixar" : "Fixar no topo",
                false,
                () -> {
                repository.toggleAccountPinned(account.id);
                renderContent();
                }
        ));
        actions.add(new NexoraMenuAction("✎", "Renomear", false, () -> showRenameAccountDialog(account)));
        actions.add(new NexoraMenuAction("⇄", "Mover para outra pasta", false, () -> {
                Set<String> oneAccount = new HashSet<>();
                oneAccount.add(account.id);
                showMoveAccountsDialog(oneAccount);
        }));
        actions.add(new NexoraMenuAction("⌫", "Apagar conta", true, () -> confirmDelete(account)));
        showNexoraActionMenu(anchor, actions);
    }

    private void showNexoraActionMenu(View anchor, List<NexoraMenuAction> actions) {
        int menuWidth = dp(238);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8), dp(8), dp(8), dp(8));
        panel.setBackground(gradientStrokeBackground(
                new int[]{Color.rgb(8, 31, 43), Color.rgb(7, 23, 34), Color.rgb(10, 34, 48)},
                19,
                COLOR_CYAN,
                1
        ));
        applyGlow(panel, COLOR_CYAN, 12);

        PopupWindow popup = new PopupWindow(
                panel,
                menuWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                true
        );
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setClippingEnabled(true);
        popup.setElevation(dp(18));

        for (int index = 0; index < actions.size(); index++) {
            NexoraMenuAction action = actions.get(index);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), 0, dp(12), 0);
            row.setBackground(pressableBackground(
                    Color.TRANSPARENT,
                    Color.rgb(11, 53, 67),
                    Color.TRANSPARENT,
                    12
            ));

            int actionColor = action.destructive ? Color.rgb(255, 70, 93) : COLOR_CYAN;
            if (action.destructive) {
                ImageView icon = new ImageView(this);
                icon.setImageResource(R.drawable.ic_delete_nexora);
                icon.setColorFilter(actionColor);
                icon.setPadding(dp(8), dp(15), dp(8), dp(15));
                row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(54)));
            } else {
                TextView icon = text(action.icon, 24, actionColor);
                icon.setGravity(Gravity.CENTER);
                icon.setIncludeFontPadding(false);
                applyTextGlow(icon, actionColor, 4);
                row.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(54)));
            }

            TextView label = text(action.label, 15, action.destructive ? Color.rgb(255, 111, 127) : COLOR_TEXT);
            label.setTypeface(null, Typeface.BOLD);
            label.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(label, new LinearLayout.LayoutParams(0, dp(54), 1f));
            row.setContentDescription(action.label);
            row.setOnClickListener(view -> {
                popup.dismiss();
                view.post(action.action);
            });
            panel.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

            if (index < actions.size() - 1) {
                View divider = new View(this);
                divider.setBackgroundColor(withAlpha(COLOR_CYAN, 42));
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                );
                dividerParams.setMargins(dp(8), 0, dp(8), 0);
                panel.addView(divider, dividerParams);
            }
        }

        panel.measure(
                View.MeasureSpec.makeMeasureSpec(menuWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        );
        int menuHeight = panel.getMeasuredHeight();
        int[] anchorLocation = new int[2];
        anchor.getLocationOnScreen(anchorLocation);
        View rootView = anchor.getRootView();
        int screenWidth = rootView.getWidth();
        int screenHeight = rootView.getHeight();
        int x = Math.max(dp(10), Math.min(
                anchorLocation[0] + anchor.getWidth() - menuWidth,
                screenWidth - menuWidth - dp(10)
        ));
        int yBelow = anchorLocation[1] + anchor.getHeight() - dp(8);
        int y = yBelow + menuHeight <= screenHeight - dp(16)
                ? yBelow
                : Math.max(dp(16), anchorLocation[1] - menuHeight + dp(8));
        popup.showAtLocation(rootView, Gravity.TOP | Gravity.START, x, y);
    }

    private static final class NexoraMenuAction {
        final String icon;
        final String label;
        final boolean destructive;
        final Runnable action;

        NexoraMenuAction(String icon, String label, boolean destructive, Runnable action) {
            this.icon = icon;
            this.label = label;
            this.destructive = destructive;
            this.action = action;
        }
    }

    private void showMoveAccountsDialog(Set<String> accountIds) {
        if (selectedFolder == null || accountIds.isEmpty()) return;
        List<AccountFolder> destinations = new ArrayList<>();
        for (AccountFolder folder : repository.getFolders()) {
            if (!folder.id.equals(selectedFolder.id)) destinations.add(folder);
        }
        if (destinations.isEmpty()) {
            Toast.makeText(this, "Crie outra pasta antes de mover contas.", Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[destinations.size()];
        for (int i = 0; i < destinations.size(); i++) names[i] = destinations.get(i).name;
        new AlertDialog.Builder(this)
                .setTitle(accountIds.size() == 1 ? "Mover conta para" : "Mover " + accountIds.size() + " contas para")
                .setItems(names, (dialog, which) -> {
                    AccountFolder destination = destinations.get(which);
                    repository.moveAccounts(accountIds, destination.id);
                    int movedCount = accountIds.size();
                    selectedAccountIds.clear();
                    selectionMode = false;
                    renderContent();
                    Toast.makeText(
                            this,
                            movedCount == 1
                                    ? "Conta movida para “" + destination.name + "”."
                                    : movedCount + " contas movidas para “" + destination.name + "”.",
                            Toast.LENGTH_SHORT
                    ).show();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showFolderStyleDialog(AccountFolder folder) {
        String[] styles = new String[]{
                "◇  Ciano",
                "◆  Violeta",
                "◎  Verde",
                "✦  Rosa"
        };
        new AlertDialog.Builder(this)
                .setTitle("Estilo da pasta")
                .setSingleChoiceItems(styles, repository.getFolderStyle(folder.id), (dialog, which) -> {
                    repository.setFolderStyle(folder.id, which);
                    dialog.dismiss();
                    renderContent();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void confirmDeleteFolder(AccountFolder folder) {
        int count = repository.getForFolder(folder.id).size();
        if (count > 0) {
            Toast.makeText(
                    this,
                    "Mova ou apague as " + count + " contas antes de excluir esta pasta.",
                    Toast.LENGTH_LONG
            ).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Excluir “" + folder.name + "”?")
                .setMessage("A pasta está vazia e será removida.")
                .setPositiveButton("Excluir", (dialog, which) -> {
                    if (repository.removeFolder(folder.id)) {
                        renderContent();
                        Toast.makeText(this, "Pasta excluída.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private int folderAccent(int style) {
        if (style == 1) return COLOR_VIOLET;
        if (style == 2) return COLOR_SUCCESS;
        if (style == 3) return COLOR_DANGER;
        return COLOR_CYAN;
    }

    private String folderSymbol(int style) {
        if (style == 1) return "◆";
        if (style == 2) return "◎";
        if (style == 3) return "✦";
        return "◇";
    }

    private int withAlpha(int color, int alpha) {
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private void chooseAccountFor(String url) {
        if (!supportsProfiles()) {
            Toast.makeText(this, "Atualize o Android System WebView para usar perfis separados.", Toast.LENGTH_LONG).show();
            return;
        }
        List<AccountFolder> folders = repository.getFolders();
        Dialog dialog = createNexoraPickerDialog();
        LinearLayout panel = createPickerPanel();
        addPickerHeader(panel, "▱", "Escolha a pasta", "Selecione a pasta para continuar", null, null);

        ScrollView listScroll = createPickerScroll();
        LinearLayout list = createPickerList();
        for (AccountFolder folder : folders) {
            int count = repository.getForFolder(folder.id).size();
            list.addView(createFolderPickerRow(folder, count, view -> {
                dialog.dismiss();
                chooseAccountInFolder(folder, url);
            }), pickerRowParams());
        }
        if (folders.isEmpty()) {
            list.addView(createPickerEmpty("Nenhuma pasta criada", "Crie uma pasta para continuar."), matchWrap());
        }
        listScroll.addView(list, matchWrap());
        panel.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        addPickerFooter(panel, "NOVA PASTA", view -> {
            dialog.dismiss();
            showAddFolderDialog(url);
        }, "CANCELAR", view -> {
            dialog.dismiss();
            cancelAuthRequestIfNeeded();
        });
        showNexoraPickerDialog(dialog, panel);
    }

    private void showAvailabilitySettings() {
        Dialog dialog = createNexoraPickerDialog();
        LinearLayout panel = createPickerPanel();
        addPickerHeader(
                panel,
                "⚙",
                "Configurações",
                "Controle os indicadores de disponibilidade.",
                "×",
                view -> dialog.dismiss()
        );

        Set<String> enabledFolders = new HashSet<>(repository.getAvailabilityEnabledFolderIds());
        ScrollView scroll = createPickerScroll();
        LinearLayout content = createPickerList();

        TextView availabilityTitle = text("DISPONIBILIDADE", 11, COLOR_CYAN);
        availabilityTitle.setTypeface(null, Typeface.BOLD);
        availabilityTitle.setLetterSpacing(0.12f);
        availabilityTitle.setPadding(dp(2), dp(4), 0, dp(8));
        content.addView(availabilityTitle, matchWrap());

        LinearLayout durationCard = card();
        TextView durationLabel = text("Tempo de espera", 15, COLOR_TEXT);
        durationLabel.setTypeface(null, Typeface.BOLD);
        durationCard.addView(durationLabel, matchWrap());
        TextView durationHint = text("Após abrir uma conta, ela fica indisponível por este período.", 12, COLOR_MUTED);
        durationHint.setPadding(0, dp(3), 0, dp(10));
        durationCard.addView(durationHint, matchWrap());

        LinearLayout durationRow = new LinearLayout(this);
        durationRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText hoursInput = new EditText(this);
        hoursInput.setText(String.valueOf(repository.getAvailabilityDurationHours()));
        hoursInput.setSelectAllOnFocus(true);
        hoursInput.setSingleLine(true);
        hoursInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        hoursInput.setTextColor(COLOR_TEXT);
        hoursInput.setTextSize(17);
        hoursInput.setGravity(Gravity.CENTER);
        hoursInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        hoursInput.setBackground(roundedBackground(COLOR_SURFACE_ALT, 12, COLOR_LINE, 1));
        durationRow.addView(hoursInput, new LinearLayout.LayoutParams(dp(82), dp(48)));
        TextView hoursLabel = text("horas", 14, COLOR_MUTED);
        hoursLabel.setPadding(dp(10), 0, 0, 0);
        durationRow.addView(hoursLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        durationCard.addView(durationRow, matchWrap());

        TextView resetHint = text(
                "Libera todas as contas e pastas agora. Sessões, logins e cookies não serão alterados.",
                12,
                COLOR_MUTED
        );
        resetHint.setPadding(0, dp(12), 0, dp(8));
        durationCard.addView(resetHint, matchWrap());

        Button resetIndicators = compactButton("↻  Liberar todos agora");
        resetIndicators.setTextColor(COLOR_CYAN);
        resetIndicators.setContentDescription("Resetar somente os indicadores de disponibilidade");
        resetIndicators.setOnClickListener(view -> {
            repository.resetAvailabilityIndicators();
            dialog.dismiss();
            renderContent();
            Toast.makeText(
                    this,
                    "Indicadores liberados. Suas sessões foram preservadas.",
                    Toast.LENGTH_SHORT
            ).show();
        });
        durationCard.addView(resetIndicators, matchWrap());

        LinearLayout.LayoutParams durationParams = matchWrap();
        durationParams.setMargins(0, 0, 0, dp(14));
        content.addView(durationCard, durationParams);

        TextView foldersTitle = text("PASTAS CONTROLADAS", 11, COLOR_CYAN);
        foldersTitle.setTypeface(null, Typeface.BOLD);
        foldersTitle.setLetterSpacing(0.12f);
        foldersTitle.setPadding(dp(2), 0, 0, dp(8));
        content.addView(foldersTitle, matchWrap());

        for (AccountFolder folder : repository.getFolders()) {
            content.addView(createAvailabilityFolderSettingRow(folder, enabledFolders), pickerRowParams());
        }
        scroll.addView(content, matchWrap());
        panel.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        addPickerFooter(panel, "SALVAR", view -> {
            int hours;
            try {
                hours = Integer.parseInt(hoursInput.getText().toString().trim());
            } catch (NumberFormatException exception) {
                hoursInput.setError("Informe o tempo em horas");
                return;
            }
            if (hours < 1 || hours > 720) {
                hoursInput.setError("Use um valor entre 1 e 720 horas");
                return;
            }
            repository.saveAvailabilitySettings(enabledFolders, hours);
            dialog.dismiss();
            renderContent();
            Toast.makeText(this, "Configurações salvas.", Toast.LENGTH_SHORT).show();
        }, "CANCELAR", view -> dialog.dismiss());
        showNexoraPickerDialog(dialog, panel);
    }

    private LinearLayout createAvailabilityFolderSettingRow(AccountFolder folder, Set<String> enabledFolders) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(16, 49, 62), COLOR_LINE, 15));

        int style = repository.getFolderStyle(folder.id);
        int accent = folderAccent(style);
        TextView icon = text(folderSymbol(style), 22, accent);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedBackground(withAlpha(accent, 35), 13, withAlpha(accent, 130), 1));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(8), 0);
        TextView name = text(folder.name, 15, COLOR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name, matchWrap());
        int count = repository.getForFolder(folder.id).size();
        copy.addView(text(count + (count == 1 ? " conta" : " contas"), 12, COLOR_MUTED), matchWrap());
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggle = text("", 18, COLOR_PAGE);
        toggle.setTypeface(null, Typeface.BOLD);
        toggle.setGravity(Gravity.CENTER);
        styleAvailabilityToggle(toggle, enabledFolders.contains(folder.id));
        row.addView(toggle, new LinearLayout.LayoutParams(dp(42), dp(42)));
        row.setOnClickListener(view -> {
            boolean enabled;
            if (enabledFolders.remove(folder.id)) {
                enabled = false;
            } else {
                enabledFolders.add(folder.id);
                enabled = true;
            }
            styleAvailabilityToggle(toggle, enabled);
        });
        return row;
    }

    private void styleAvailabilityToggle(TextView toggle, boolean enabled) {
        toggle.setText(enabled ? "✓" : "");
        toggle.setContentDescription(enabled ? "Controle ativado" : "Controle desativado");
        toggle.setBackground(enabled
                ? gradientBackground(COLOR_CYAN, COLOR_VIOLET, 12)
                : roundedBackground(COLOR_SURFACE, 12, COLOR_LINE, 1));
    }

    private void chooseAccountInFolder(AccountFolder folder, String url) {
        List<Account> accounts = repository.getForFolder(folder.id);
        if (accounts.isEmpty()) {
            showAddAccountDialog(folder, url);
            return;
        }
        Dialog dialog = createNexoraPickerDialog();
        LinearLayout panel = createPickerPanel();
        addPickerHeader(
                panel,
                "♙",
                folder.name + ": escolha a conta",
                "Selecione uma conta para continuar.",
                "×",
                view -> {
                    dialog.dismiss();
                    chooseAccountFor(url);
                }
        );

        ScrollView listScroll = createPickerScroll();
        LinearLayout list = createPickerList();
        for (Account account : accounts) {
            list.addView(createAccountPickerRow(account, view -> {
                dialog.dismiss();
                openBrowser(account, url);
            }), pickerRowParams());
        }
        listScroll.addView(list, matchWrap());
        panel.addView(listScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ));
        addPickerFooter(panel, "NOVA CONTA", view -> {
            dialog.dismiss();
            showAddAccountDialog(folder, url);
        }, "VOLTAR", view -> {
            dialog.dismiss();
            chooseAccountFor(url);
        });
        showNexoraPickerDialog(dialog, panel);
    }

    private Dialog createNexoraPickerDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnCancelListener(ignored -> cancelAuthRequestIfNeeded());
        return dialog;
    }

    private LinearLayout createPickerPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(16), dp(8), dp(16), dp(12));
        panel.setBackground(roundedBackground(COLOR_SURFACE, 24, COLOR_CYAN, 1));
        return panel;
    }

    private void showNexoraPickerDialog(Dialog dialog, LinearLayout panel) {
        dialog.setContentView(panel);
        int dialogWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
        int dialogHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.80f);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setDimAmount(0.76f);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setLayout(dialogWidth, dialogHeight);
            window.setGravity(Gravity.CENTER);
        }
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dialogWidth, dialogHeight);
        }
    }

    private void addPickerHeader(
            LinearLayout panel,
            String icon,
            String title,
            String subtitle,
            String closeLabel,
            View.OnClickListener closeAction
    ) {
        TextView handle = text("━", 23, COLOR_MUTED);
        handle.setGravity(Gravity.CENTER);
        handle.setLetterSpacing(-0.18f);
        panel.addView(handle, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22)));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(4), dp(2), dp(12));

        TextView iconView = text(icon, 31, COLOR_CYAN);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(roundedBackground(Color.rgb(8, 45, 51), 16, Color.rgb(19, 139, 139), 1));
        header.addView(iconView, new LinearLayout.LayoutParams(dp(60), dp(60)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(14), 0, dp(4), 0);
        TextView titleView = text(title, 18, COLOR_TEXT);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setMaxLines(2);
        copy.addView(titleView, matchWrap());
        TextView subtitleView = text(subtitle, 13, COLOR_MUTED);
        subtitleView.setPadding(0, dp(3), 0, 0);
        copy.addView(subtitleView, matchWrap());
        header.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (closeLabel != null && closeAction != null) {
            TextView close = text(closeLabel, 31, COLOR_TEXT);
            close.setGravity(Gravity.CENTER);
            close.setIncludeFontPadding(false);
            close.setSingleLine(true);
            close.setPadding(0, 0, 0, 0);
            close.setBackground(roundedBackground(COLOR_SURFACE_ALT, 22, COLOR_LINE, 1));
            close.setOnClickListener(closeAction);
            header.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        panel.addView(header, matchWrap());
    }

    private ScrollView createPickerScroll() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(2));
        return scroll;
    }

    private LinearLayout createPickerList() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        return list;
    }

    private LinearLayout createFolderPickerRow(AccountFolder folder, int count, View.OnClickListener clickListener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        row.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(16, 49, 62), COLOR_LINE, 16));
        row.setOnClickListener(clickListener);

        int accent = folderAccent(repository.getFolderStyle(folder.id));
        TextView icon = text(folderSymbol(repository.getFolderStyle(folder.id)), 25, accent);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(roundedBackground(withAlpha(accent, 35), 14, withAlpha(accent, 130), 1));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setPadding(dp(12), 0, dp(6), 0);
        TextView name = text(folder.name, 16, COLOR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        copy.addView(name, matchWrap());
        copy.addView(text(count + (count == 1 ? " conta isolada" : " contas isoladas"), 12, COLOR_MUTED), matchWrap());
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (repository.isAvailabilityEnabledForFolder(folder.id)) {
            View availability = availabilityDot(repository.hasAvailableAccount(folder.id));
            LinearLayout.LayoutParams availabilityParams = new LinearLayout.LayoutParams(dp(12), dp(12));
            availabilityParams.setMargins(0, 0, dp(10), 0);
            row.addView(availability, availabilityParams);
        }

        TextView arrow = text("›", 34, COLOR_MUTED);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, new LinearLayout.LayoutParams(dp(30), dp(52)));
        return row;
    }

    private LinearLayout createAccountPickerRow(Account account, View.OnClickListener clickListener) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(7), dp(8), dp(7));
        row.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(16, 49, 62), COLOR_LINE, 15));
        row.setOnClickListener(clickListener);

        TextView icon = text(initialFor(account.name), 17, COLOR_PAGE);
        icon.setTypeface(null, Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(gradientBackground(COLOR_CYAN, COLOR_VIOLET, 13));
        row.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView name = text(account.name, 15, COLOR_TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setSingleLine(true);
        name.setPadding(dp(12), 0, dp(5), 0);
        row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (repository.isAvailabilityEnabledForFolder(account.folderId)) {
            View availability = availabilityDot(repository.isAccountAvailable(account.id));
            LinearLayout.LayoutParams availabilityParams = new LinearLayout.LayoutParams(dp(12), dp(12));
            availabilityParams.setMargins(dp(5), 0, dp(12), 0);
            row.addView(availability, availabilityParams);
        }
        return row;
    }

    private View availabilityDot(boolean available) {
        int color = available ? COLOR_SUCCESS : Color.rgb(255, 36, 77);
        View availability = new View(this);
        availability.setContentDescription(
                available
                        ? "Disponível"
                        : "Indisponível por " + repository.getAvailabilityDurationHours() + " horas"
        );
        availability.setBackground(roundedBackground(color, 8, withAlpha(Color.WHITE, 180), 1));
        return availability;
    }

    private TextView createPickerEmpty(String title, String description) {
        TextView empty = text(title + "\n" + description, 14, COLOR_MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setLineSpacing(dp(4), 1f);
        empty.setPadding(dp(16), dp(32), dp(16), dp(32));
        empty.setBackground(roundedBackground(COLOR_SURFACE_ALT, 16, COLOR_LINE, 1));
        return empty;
    }

    private LinearLayout.LayoutParams pickerRowParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(7));
        return params;
    }

    private void addPickerFooter(
            LinearLayout panel,
            String primaryLabel,
            View.OnClickListener primaryAction,
            String secondaryLabel,
            View.OnClickListener secondaryAction
    ) {
        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        footer.setPadding(dp(2), dp(12), dp(2), 0);

        Button primary = button(primaryLabel);
        primary.setTextColor(COLOR_CYAN);
        primary.setTextSize(12);
        primary.setBackgroundColor(Color.TRANSPARENT);
        primary.setOnClickListener(primaryAction);
        footer.addView(primary, new LinearLayout.LayoutParams(0, dp(46), 1f));

        TextView divider = new TextView(this);
        divider.setBackgroundColor(COLOR_LINE);
        footer.addView(divider, new LinearLayout.LayoutParams(dp(1), dp(28)));

        Button secondary = button(secondaryLabel);
        secondary.setTextColor(COLOR_CYAN);
        secondary.setTextSize(12);
        secondary.setBackgroundColor(Color.TRANSPARENT);
        secondary.setOnClickListener(secondaryAction);
        footer.addView(secondary, new LinearLayout.LayoutParams(0, dp(46), 1f));
        panel.addView(footer, matchWrap());
    }

    private void showAddAccountDialog(AccountFolder folder, String urlAfterCreation) {
        if (!supportsProfiles()) {
            Toast.makeText(this, "Perfis múltiplos não são suportados neste WebView.", Toast.LENGTH_LONG).show();
            return;
        }
        EditText input = new EditText(this);
        input.setHint("Ex.: Grok pessoal");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleDialogInput(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Nome da conta")
                .setView(input)
                .setPositiveButton("Criar", null)
                .setNegativeButton("Cancelar", (ignored, which) -> cancelAuthRequestIfNeeded())
                .setOnCancelListener(ignored -> cancelAuthRequestIfNeeded())
                .create();

        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("Informe um nome");
                return;
            }
            Account account = repository.add(name, folder.id);
            dialog.dismiss();
            renderContent();
            openBrowser(account, urlAfterCreation == null ? GROK_URL : urlAfterCreation);
        }));
        dialog.show();
    }

    private void showAddFolderDialog(String urlAfterCreation) {
        showNameDialog("Nome da pasta", "Ex.: ccntcache", "Criar", "", name -> {
            AccountFolder folder = repository.addFolder(name);
            if (urlAfterCreation != null) {
                showAddAccountDialog(folder, urlAfterCreation);
            } else {
                selectedFolder = folder;
                renderContent();
                scrollContentToTop();
            }
        });
    }

    private void showRenameFolderDialog(AccountFolder folder) {
        showNameDialog("Renomear pasta", "Nome da pasta", "Salvar", folder.name, name -> {
            repository.renameFolder(folder.id, name);
            if (selectedFolder != null && selectedFolder.id.equals(folder.id)) {
                selectedFolder = new AccountFolder(folder.id, name);
            }
            renderContent();
        });
    }

    private void showRenameAccountDialog(Account account) {
        showNameDialog("Renomear conta", "Nome da conta", "Salvar", account.name, name -> {
            repository.renameAccount(account.id, name);
            renderContent();
        });
    }

    private void showNameDialog(String title, String hint, String positiveLabel, String initialValue, NameReceiver receiver) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(initialValue);
        input.setSelection(initialValue.length());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleDialogInput(input);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(positiveLabel, null)
                .setNegativeButton("Cancelar", (ignored, which) -> cancelAuthRequestIfNeeded())
                .setOnCancelListener(ignored -> cancelAuthRequestIfNeeded())
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) {
                input.setError("Informe um nome");
                return;
            }
            dialog.dismiss();
            receiver.accept(name);
        }));
        dialog.show();
    }

    private void confirmDelete(Account account) {
        new AlertDialog.Builder(this)
                .setTitle("Apagar " + account.name + "?")
                .setMessage("A sessão, cookies e dados de navegação desse perfil serão apagados.")
                .setPositiveButton("Apagar", (dialog, which) -> deleteAccount(account))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    @SuppressLint("RequiresFeature")
    private void deleteAccount(Account account) {
        repository.remove(account.id);
        if (!supportsProfiles()) {
            repository.markForDeletion(account.id);
            Toast.makeText(this, "Conta removida; limpeza da sessão ficou pendente.", Toast.LENGTH_LONG).show();
            renderContent();
            return;
        }
        try {
            ProfileStore.getInstance().deleteProfile(account.id);
            repository.deletionCompleted(account.id);
            Toast.makeText(this, "Conta e sessão apagadas.", Toast.LENGTH_SHORT).show();
        } catch (IllegalStateException exception) {
            repository.markForDeletion(account.id);
            Toast.makeText(this, "Conta removida. A sessão será apagada ao reabrir o app.", Toast.LENGTH_LONG).show();
        } catch (RuntimeException exception) {
            repository.markForDeletion(account.id);
            Toast.makeText(this, "Conta removida; limpeza da sessão ficou pendente.", Toast.LENGTH_LONG).show();
        }
        renderContent();
    }

    private void cleanPendingProfiles() {
        if (!supportsProfiles()) {
            return;
        }
        Set<String> pending = repository.getPendingDeletions();
        for (String profileId : pending) {
            try {
                ProfileStore.getInstance().deleteProfile(profileId);
                repository.deletionCompleted(profileId);
            } catch (RuntimeException ignored) {
                // Uma WebView ainda pode estar usando o perfil. Tentaremos no próximo início.
            }
        }
    }

    private void openBrowser(Account account, String url) {
        repository.markAccountUnavailable(account);
        Intent intent = new Intent(this, BrowserActivity.class);
        intent.putExtra(BrowserActivity.EXTRA_PROFILE_ID, account.id);
        intent.putExtra(BrowserActivity.EXTRA_PROFILE_NAME, account.name);
        intent.putExtra(BrowserActivity.EXTRA_URL, url);
        if (sourcePackage != null) {
            intent.putExtra(BrowserActivity.EXTRA_SOURCE_PACKAGE, sourcePackage);
        }
        intent.putExtra(BrowserActivity.EXTRA_RETURN_RESULT, returnResultToCaller);
        intent.putExtra(BrowserActivity.EXTRA_REDIRECT_SCHEME, redirectScheme);
        intent.putExtra(BrowserActivity.EXTRA_REDIRECT_HOST, redirectHost);
        intent.putExtra(BrowserActivity.EXTRA_REDIRECT_PATH, redirectPath);
        if (returnResultToCaller) {
            startActivityForResult(intent, REQUEST_AUTH_BROWSER);
        } else {
            startActivity(intent);
        }
        pendingUrl = null;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_AUTH_BROWSER) {
            return;
        }
        Intent safeResult = null;
        if (data != null && data.getData() != null) {
            safeResult = new Intent();
            safeResult.setData(data.getData());
        }
        setResult(resultCode, safeResult);
        finish();
    }

    private void captureAuthContract(Intent intent) {
        boolean authTab = intent != null && intent.getBooleanExtra(EXTRA_LAUNCH_AUTH_TAB, false);
        returnResultToCaller = authTab || getCallingActivity() != null || getCallingPackage() != null;
        redirectScheme = intent == null ? null : intent.getStringExtra(EXTRA_REDIRECT_SCHEME);
        redirectHost = intent == null ? null : intent.getStringExtra(EXTRA_HTTPS_REDIRECT_HOST);
        redirectPath = intent == null ? null : intent.getStringExtra(EXTRA_HTTPS_REDIRECT_PATH);
    }

    private void cancelAuthRequestIfNeeded() {
        if (returnResultToCaller) {
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void captureIncomingUrl(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        String scheme = uri.getScheme();
        if ("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme)) {
            pendingUrl = uri.toString();
            sourcePackage = findSourcePackage(intent);
        }
    }

    private String findSourcePackage(Intent intent) {
        String candidate = getCallingPackage();
        if (candidate == null || candidate.isEmpty()) {
            candidate = intent.getStringExtra(Browser.EXTRA_APPLICATION_ID);
        }
        if (candidate == null || candidate.isEmpty()) {
            candidate = intent.getStringExtra(Intent.EXTRA_REFERRER_NAME);
            if (candidate != null && candidate.startsWith("android-app://")) {
                candidate = Uri.parse(candidate).getHost();
            }
        }
        if (candidate == null || candidate.isEmpty()) {
            Object referrer = intent.getParcelableExtra(Intent.EXTRA_REFERRER);
            if (referrer instanceof Uri) {
                Uri referrerUri = (Uri) referrer;
                if ("android-app".equalsIgnoreCase(referrerUri.getScheme())) {
                    candidate = referrerUri.getHost();
                }
            }
        }
        if (candidate == null || candidate.equals(getPackageName())) {
            return null;
        }
        return candidate;
    }

    private void confirmDualSwitch() {
        new AlertDialog.Builder(this)
                .setTitle("Trocar a conta do Grok Dual?")
                .setMessage(
                        "O Grok Dual será fechado e seus dados locais serão apagados para voltar à tela de login, "
                                + "sem usar o botão Encerrar sessão da xAI.\n\n"
                                + "Configurações, downloads e conteúdo ainda não sincronizado dentro do Grok Dual podem ser perdidos. "
                                + "A credencial Android local do Grok Dual também será removida. "
                                + "As pastas, perfis e cookies deste navegador não serão apagados."
                )
                .setPositiveButton("Preparar troca", (dialog, which) -> beginDualSwitch())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void beginDualSwitch() {
        if (!DualAppAutomationService.isEnabled(this)) {
            showAccessibilitySetup();
            return;
        }
        startAccessibilitySwitch();
    }

    private void startAccessibilitySwitch() {
        automationStatus.setText(R.string.dual_app_preparing);
        if (!DualAppAutomationService.requestSwitch(this)) {
            automationStatus.setText(R.string.dual_app_settings_error);
            Toast.makeText(this, "A tela de Apps duplos da Xiaomi não foi encontrada.", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshAutomationStatus() {
        if (automationStatus == null) {
            return;
        }
        String lastResult = DualAppAutomationService.consumeLastResult(this);
        if (lastResult != null) {
            automationStatus.setText(lastResult);
            return;
        }
        automationStatus.setText(
                DualAppAutomationService.isEnabled(this)
                        ? "Automação por Acessibilidade ativada; Shizuku não é necessário."
                        : "Ative a Acessibilidade uma vez para trocar sem Shizuku ou depuração."
        );
    }

    private void showAccessibilitySetup() {
        new AlertDialog.Builder(this)
                .setTitle("Ativar automação permanente")
                .setMessage(
                        "Ative o serviço “Automação do Grok Dual” nas configurações de Acessibilidade. "
                                + "Ele observa somente a tela de Apps duplos da Xiaomi e automatiza desligar, ligar e abrir o Grok Dual.\n\n"
                                + "Depois de ativado, não será necessário Shizuku, computador ou depuração."
                )
                .setPositiveButton("Abrir Acessibilidade", (dialog, which) -> openAccessibilitySettings())
                .setNegativeButton("Agora não", null)
                .show();
    }

    private void openAccessibilitySettings() {
        waitingForAccessibility = true;
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivity(details);
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        }
    }

    private boolean supportsProfiles() {
        return WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE);
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(COLOR_TEXT);
        button.setTextSize(12);
        button.setTypeface(null, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(pressableBackground(COLOR_SURFACE_ALT, Color.rgb(17, 45, 59), COLOR_LINE, 13));
        button.setStateListAnimator(null);
        return button;
    }

    private Button primaryButton(String label) {
        Button button = button(label);
        button.setTextColor(COLOR_PAGE);
        button.setTextSize(14);
        button.setMinHeight(dp(54));
        button.setMinimumHeight(dp(54));
        button.setBackground(gradientBackground(COLOR_CYAN, COLOR_VIOLET, 15));
        return button;
    }

    private Button compactButton(String label) {
        Button button = button(label);
        button.setTextSize(11);
        button.setMinHeight(dp(40));
        button.setMinimumHeight(dp(40));
        button.setPadding(dp(7), 0, dp(7), 0);
        return button;
    }

    private Button miniButton(String label) {
        Button button = button(label);
        button.setTextSize(10);
        button.setMinHeight(dp(36));
        button.setMinimumHeight(dp(36));
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(12));
        card.setBackground(roundedBackground(COLOR_SURFACE, 17, COLOR_LINE, 1));
        return card;
    }

    private LinearLayout actionRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, 0);
        return row;
    }

    private LinearLayout.LayoutParams weightedButtonParams() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void addCard(LinearLayout card) {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, 0, 0, dp(10));
        contentList.addView(card, params);
    }

    private void scrollContentToTop() {
        if (contentScroll != null) {
            contentScroll.post(() -> contentScroll.scrollTo(0, 0));
        }
    }

    private LinearLayout emptyState(String icon, String title, String description) {
        LinearLayout empty = card();
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(20), dp(26), dp(20), dp(26));

        TextView iconView = text(icon, 28, COLOR_CYAN);
        iconView.setGravity(Gravity.CENTER);
        iconView.setBackground(roundedBackground(Color.rgb(7, 40, 46), 16, Color.rgb(23, 94, 98), 1));
        empty.addView(iconView, new LinearLayout.LayoutParams(dp(56), dp(56)));

        TextView titleView = text(title, 17, COLOR_TEXT);
        titleView.setTypeface(null, Typeface.BOLD);
        titleView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.setMargins(0, dp(13), 0, dp(5));
        empty.addView(titleView, titleParams);

        TextView descriptionView = text(description, 13, COLOR_MUTED);
        descriptionView.setGravity(Gravity.CENTER);
        descriptionView.setLineSpacing(0, 1.15f);
        empty.addView(descriptionView, matchWrap());
        return empty;
    }

    private String initialFor(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.isEmpty() ? "G" : trimmed.substring(0, 1).toUpperCase(Locale.getDefault());
    }

    private void styleDialogInput(EditText input) {
        input.setTextColor(COLOR_TEXT);
        input.setHintTextColor(COLOR_MUTED);
        input.setTextSize(15);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(roundedBackground(COLOR_SURFACE_ALT, 12, COLOR_LINE, 1));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(dp(18), dp(6), dp(18), 0);
        input.setLayoutParams(params);
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

    private Drawable gradientBackground(int startColor, int endColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{startColor, endColor}
        );
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private Drawable gradientStrokeBackground(
            int[] colors,
            int radiusDp,
            int strokeColor,
            int strokeDp
    ) {
        GradientDrawable drawable = new GradientDrawable(GradientDrawable.Orientation.TL_BR, colors);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), strokeColor);
        }
        return drawable;
    }

    private void applyGlow(View view, int color, int elevationDp) {
        view.setElevation(dp(elevationDp));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            view.setOutlineAmbientShadowColor(withAlpha(color, 165));
            view.setOutlineSpotShadowColor(withAlpha(color, 225));
        }
    }

    private void applyTextGlow(TextView view, int color, int radiusDp) {
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        view.setShadowLayer(dp(radiusDp), 0, 0, withAlpha(color, 150));
    }

    private Drawable pressableBackground(int normalColor, int pressedColor, int strokeColor, int radiusDp) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                roundedBackground(pressedColor, radiusDp, COLOR_CYAN, 1)
        );
        states.addState(
                new int[]{},
                roundedBackground(normalColor, radiusDp, strokeColor, 1)
        );
        return states;
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(sizeSp);
        textView.setTextColor(color);
        return textView;
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

    private interface NameReceiver {
        void accept(String name);
    }
}
