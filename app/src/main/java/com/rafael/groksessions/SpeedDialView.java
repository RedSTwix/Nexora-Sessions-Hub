package com.rafael.groksessions;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native start page: shortcut data never enters a website's JavaScript context. */
final class SpeedDialView extends ScrollView {
    private static final int PAGE = Color.rgb(3, 11, 17), SURFACE = Color.rgb(10, 23, 33);
    private static final int TEXT = Color.rgb(242, 248, 252), MUTED = Color.rgb(142, 164, 179);
    private static final int CYAN = Color.rgb(34, 238, 226), VIOLET = Color.rgb(139, 92, 246);
    private final Activity activity;
    private final SiteShortcutRepository repository;
    private final String profileName;
    private final Consumer<String> open;
    private final LinearLayout content;
    private AlertDialog currentDialog;

    SpeedDialView(Activity activity, SiteShortcutRepository repository, String profileName, Consumer<String> open) {
        super(activity);
        this.activity = activity;
        this.repository = repository;
        this.profileName = profileName;
        this.open = open;
        setFillViewport(true);
        setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{Color.rgb(5, 23, 34), PAGE}));
        content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(26), dp(18), dp(30));
        addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        refresh();
    }

    void refresh() {
        int previousScroll = getScrollY();
        content.removeAllViews();
        TextView eyebrow = text("SEU PERFIL · SEUS ATALHOS", 10, CYAN);
        eyebrow.setLetterSpacing(0.12f);
        content.addView(eyebrow, full());
        TextView heading = text("Seus sites", 30, TEXT);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setPadding(0, dp(10), 0, dp(6));
        content.addView(heading, full());
        TextView subtitle = text("Um toque para continuar em " + profileName + ".", 13, MUTED);
        subtitle.setPadding(0, 0, 0, dp(24));
        content.addView(subtitle, full());
        List<SiteShortcut> sites = repository.getAll();
        if (sites.isEmpty()) {
            TextView empty = text("Seus próximos destinos ficam aqui.\nAdicione o primeiro site deste perfil.", 16, TEXT);
            empty.setLineSpacing(dp(6), 1);
            empty.setPadding(dp(18), dp(30), dp(18), dp(30));
            empty.setBackground(background(SURFACE, Color.rgb(23, 54, 70), 20));
            LinearLayout.LayoutParams params = full();
            params.setMargins(0, 0, 0, dp(16));
            content.addView(empty, params);
        }
        for (int i = 0; i < sites.size(); i += 2) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int column = 0; column < 2; column++) {
                int index = i + column;
                View tile = index < sites.size() ? tile(sites.get(index), index, sites.size()) : new View(activity);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                        index < sites.size() ? dp(170) : ViewGroup.LayoutParams.MATCH_PARENT, 1);
                params.setMargins(column == 0 ? 0 : dp(6), 0, column == 0 ? dp(6) : 0, 0);
                row.addView(tile, params);
            }
            LinearLayout.LayoutParams params = full();
            params.setMargins(0, 0, 0, dp(12));
            content.addView(row, params);
        }
        TextView add = text("+   Adicionar site", 16, PAGE);
        add.setTypeface(null, Typeface.BOLD);
        add.setGravity(Gravity.CENTER);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{CYAN, VIOLET});
        gradient.setCornerRadius(dp(16));
        add.setBackground(gradient);
        add.setContentDescription("Adicionar site neste perfil");
        add.setOnClickListener(view -> showAddSite("", "", null));
        content.addView(add, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)));
        post(() -> scrollTo(0, previousScroll));
    }

    private View tile(SiteShortcut site, int index, int count) {
        int accent = index % 2 == 0 ? CYAN : VIOLET;
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(14), dp(12), dp(12), dp(12));
        tile.setBackground(background(SURFACE, Color.argb(120, Color.red(accent), Color.green(accent), Color.blue(accent)), 18));
        tile.setContentDescription("Abrir " + site.name + ", " + site.url);
        tile.setOnClickListener(view -> open.accept(site.url));
        tile.setOnLongClickListener(view -> { showMenu(site, index, count); return true; });
        LinearLayout top = new LinearLayout(activity);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Bitmap bitmap = ShortcutIcon.decode(site.icon);
        View icon;
        if (bitmap != null) {
            ImageView image = new ImageView(activity);
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setPadding(dp(7), dp(7), dp(7), dp(7));
            icon = image;
        } else {
            String host = Uri.parse(site.url).getHost();
            String initial = host == null ? site.name : host.replaceFirst("^www\\.", "");
            if (initial.isEmpty()) initial = site.name;
            TextView letter = text(initial.substring(0, 1).toUpperCase(Locale.ROOT), 23, accent);
            letter.setGravity(Gravity.CENTER);
            letter.setTypeface(null, Typeface.BOLD);
            icon = letter;
        }
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        icon.setBackground(background(Color.rgb(13, 30, 42), Color.rgb(23, 54, 70), 13));
        top.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        TextView menu = text("⋮", 25, MUTED);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Opções do atalho " + site.name);
        menu.setOnClickListener(view -> showMenu(site, index, count));
        top.addView(menu, new LinearLayout.LayoutParams(dp(40), dp(48)));
        tile.addView(top, full());
        TextView name = text(site.name, 16, TEXT);
        name.setTypeface(null, Typeface.BOLD);
        name.setMaxLines(1);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(0, dp(12), 0, dp(5));
        tile.addView(name, full());
        TextView url = text(site.url, 11, MUTED);
        url.setMaxLines(2);
        url.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(url, full());
        return tile;
    }

    void showAddSite(String name, String url, Bitmap icon) { showEditor(null, name, url, icon); }

    private void showEditor(SiteShortcut existing, String initialName, String initialUrl, Bitmap icon) {
        LinearLayout fields = new LinearLayout(activity);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(22), dp(8), dp(22), dp(8));
        EditText name = input("Nome do site", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        name.setText(initialName == null ? "" : initialName);
        fields.addView(name, full());
        EditText url = input("Endereço (ex.: https://site.com)", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setText(initialUrl == null ? "" : initialUrl);
        fields.addView(url, full());
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(existing == null ? "Adicionar site" : "Editar atalho")
                .setView(fields).setPositiveButton("Salvar", null).setNegativeButton("Cancelar", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (name.getText().toString().trim().isEmpty()) { name.setError("Informe o nome do site"); return; }
            try {
                String normalized = SiteShortcut.normalizeUrl(url.getText().toString());
                Bitmap matchingIcon = icon != null && SiteShortcut.origin(normalized).equals(SiteShortcut.origin(initialUrl)) ? icon : null;
                if (existing == null) repository.add(name.getText().toString(), normalized, matchingIcon);
                else repository.edit(existing.id, name.getText().toString(), normalized, matchingIcon);
                dialog.dismiss();
                refresh();
            } catch (IllegalArgumentException exception) { url.setError(exception.getMessage()); }
        }));
        showDialog(dialog);
    }

    private void showMenu(SiteShortcut site, int index, int count) {
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        labels.add("Editar"); actions.add(() -> showEditor(site, site.name, site.url, null));
        if (index > 0) { labels.add("Mover antes"); actions.add(() -> { repository.move(site.id, -1); refresh(); }); }
        if (index + 1 < count) { labels.add("Mover depois"); actions.add(() -> { repository.move(site.id, 1); refresh(); }); }
        labels.add("Excluir"); actions.add(() -> showDialog(new AlertDialog.Builder(activity).setTitle("Remover atalho?")
                .setMessage("Remover “" + site.name + "” dos atalhos deste perfil?")
                .setPositiveButton("Remover", (dialog, which) -> { repository.remove(site.id); refresh(); })
                .setNegativeButton("Cancelar", null).create()));
        showDialog(new AlertDialog.Builder(activity).setTitle(site.name).setItems(labels.toArray(new String[0]),
                (dialog, which) -> actions.get(which).run()).create());
    }

    private void showDialog(AlertDialog dialog) {
        closeDialogs();
        currentDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (currentDialog == dialog) currentDialog = null; });
        dialog.show();
    }

    void closeDialogs() { if (currentDialog != null) { currentDialog.dismiss(); currentDialog = null; } }

    private EditText input(String hint, int type) {
        EditText input = new EditText(activity);
        input.setHint(hint);
        input.setContentDescription(hint);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        return input;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value); text.setTextSize(size); text.setTextColor(color);
        return text;
    }
    private GradientDrawable background(int color, int border, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color); background.setCornerRadius(dp(radius)); background.setStroke(dp(1), border);
        return background;
    }
    private LinearLayout.LayoutParams full() { return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
