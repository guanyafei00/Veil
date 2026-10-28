package com.veil.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The launcher's settings, AT4K/Aerial style: a half-screen panel on the right that rides over
 * the live desktop (the window is translucent, the left half is a dimmed scrim), never covering
 * the whole screen. Level one is the category list (desktop / wallpaper / screensaver / clock /
 * system); picking one opens level two — that category's rows — and Back returns to the list.
 * Rows are built in code — one helper, no button IDs — and every row reuses the dialogs this
 * panel always had; the home screen adopts changes on resume.
 */
public class HomeSettingsActivity extends Activity {

    private PrefsStore prefs;
    /** The rows of the open category, inside the details scroller. */
    private LinearLayout list;
    private LinearLayout categories;
    private ScrollView details;
    private TextView title, sub;
    /** -1 = the category list is showing; otherwise the open category's index. */
    private int activeCategory = -1;

    private static final int PAGE_BG = 0xF60D0D10;
    private static final int ROW_BG = 0xFF1B1B20;
    private static final int CARD_BG = 0xFFF5F5F7;
    private static final int LABEL_COLOR = 0xFFF2F2F4;
    private static final int VALUE_COLOR = 0xFF98989F;
    private static final int HEADER_COLOR = 0xFF7C7C85;
    private static final int SCRIM = 0x8C000000;

    private static final int[] CATEGORIES = {
            R.string.section_desk, R.string.section_wallpaper, R.string.section_screensaver,
            R.string.section_clock, R.string.section_system,
    };

    // Values that refresh when a dialog changes a pref; rebuilt per category, so every access
    // is null-guarded (a row that was never built simply has no live TextView).
    private TextView lockValue, lockServiceValue, systemHomeValue, weatherValue, unitValue, h24Value, bingValue, aerialValue,
            bingRotateValue, aerialExpandValue, aerialResValue, aerialSourceValue,
            w360CatValue, w360ExpandValue, w360RotateValue,
            dockColorValue, radiusValue, columnsValue, dockVisibleValue, dockBarValue, dockFrostValue, heroArtValue,
            topBarColorValue, topTextColorValue,
            screensaverValue, screensaverDelayValue, screensaverClockValue, previewValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new PrefsStore(this);
        float d = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels;

        // The dimmed desktop behind: the window is translucent (Theme.TvHome.Settings), this
        // scrim keeps the left half readable without hiding the wallpaper.
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(SCRIM);

        // The panel: the right half of the screen, rounded on its left edge only.
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(screenW / 2,
                ViewGroup.LayoutParams.MATCH_PARENT);
        plp.gravity = Gravity.END;
        panel.setLayoutParams(plp);
        GradientDrawable panelBg = new GradientDrawable();
        panelBg.setColor(PAGE_BG);
        float r = 28 * d;
        panelBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        panel.setBackground(panelBg);
        panel.setPadding((int) (28 * d), (int) (26 * d), (int) (22 * d), (int) (22 * d));
        panel.setClipToPadding(false);

        title = new TextView(this);
        title.setText(R.string.panel_title);
        title.setTextSize(26);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(0xFFFFFFFF);
        title.setPadding(0, 0, 0, (int) (2 * d));
        panel.addView(title);

        sub = new TextView(this);
        sub.setText(getString(R.string.app_name) + " · tvOS");
        sub.setTextSize(12);
        sub.setTextColor(HEADER_COLOR);
        sub.setPadding(0, 0, 0, (int) (16 * d));
        panel.addView(sub);

        // Level one and level two share one slot: only one of them is ever visible.
        FrameLayout content = new FrameLayout(this);
        content.setClipToPadding(false);

        categories = new LinearLayout(this);
        categories.setOrientation(LinearLayout.VERTICAL);
        categories.setClipToPadding(false);
        content.addView(categories, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        details = new ScrollView(this);
        details.setFillViewport(true);
        details.setVerticalScrollBarEnabled(false);
        // Rows zoom to 1.03 on focus; that overhang lands in this padding, and the default
        // clipToPadding would slice the focused card's rounded ends into straight corners.
        details.setClipToPadding(false);
        details.setPadding((int) (4 * d), (int) (4 * d), (int) (4 * d), (int) (30 * d));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipToPadding(false);
        details.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        details.setVisibility(View.GONE);
        content.addView(details, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panel.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(panel);
        setContentView(root);

        buildCategories();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshValues();
        // First show (or a focus lost to a rebuild): land on the category list, not nowhere.
        if (getCurrentFocus() == null && categories.getChildCount() > 0) {
            categories.getChildAt(0).requestFocus();
        }
    }

    @Override
    public void onBackPressed() {
        if (inOptions) {
            showCategory(activeCategory);   // level three (option list) → back to the category rows
            return;
        }
        if (activeCategory >= 0) {
            showCategories();   // level two → level one, focus back on the open category
            return;
        }
        super.onBackPressed();
    }

    // ---- level one: the category list ------------------------------------------------------------

    private void buildCategories() {
        for (int i = 0; i < CATEGORIES.length; i++) {
            final int idx = i;
            categories.addView(categoryRow(getString(CATEGORIES[i]), v -> showCategory(idx)));
        }
    }

    /** One category entry: big label, chevron, white rounded card + slight zoom on focus. */
    private View categoryRow(String label, View.OnClickListener open) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding((int) (22 * d), 0, (int) (18 * d), 0);
        r.setMinimumHeight((int) (62 * d));
        r.setFocusable(true);
        r.setBackgroundResource(android.R.color.transparent);
        GradientDrawable idle = round(ROW_BG, 16 * d), focused = round(CARD_BG, 16 * d);
        r.setBackground(idle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * d);
        r.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(18);
        name.setTextColor(LABEL_COLOR);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(22);
        chevron.setTextColor(0xFF57575E);
        chevron.setPadding((int) (10 * d), 0, 0, 0);
        r.addView(chevron, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        r.setOnFocusChangeListener((v, has) -> {
            r.setBackground(has ? focused : idle);   // no zoom: a full-width row clips square
            name.setTextColor(has ? 0xFF1A1A1C : LABEL_COLOR);
            chevron.setTextColor(has ? 0xFF9A9AA0 : 0xFF57575E);
        });
        r.setOnClickListener(open);
        return r;
    }

    private void showCategories() {
        int back = activeCategory;   // focus returns to the category we came from
        activeCategory = -1;
        inOptions = false;
        details.setVisibility(View.GONE);
        categories.setVisibility(View.VISIBLE);
        title.setText(R.string.panel_title);
        sub.setText(getString(R.string.app_name) + " · tvOS");
        View focus = getCurrentFocus();
        if (focus == null || !isInside(focus, categories)) {
            int idx = back >= 0 && back < categories.getChildCount() ? back : 0;
            categories.getChildAt(idx).requestFocus();
        }
    }

    private void showCategory(int idx) {
        activeCategory = idx;
        inOptions = false;
        list.removeAllViews();
        switch (idx) {
            case 0: buildDeskRows(); break;
            case 1: buildWallpaperRows(); break;
            case 2: buildScreensaverRows(); break;
            case 3: buildClockRows(); break;
            default: buildSystemRows(); break;
        }
        refreshValues();   // the rows are rebuilt empty: fill the current-value column at once
        categories.setVisibility(View.GONE);
        details.setVisibility(View.VISIBLE);
        title.setText(CATEGORIES[idx]);
        sub.setText(R.string.settings_back_hint);
        if (list.getChildCount() > 0) {
            final View first = list.getChildAt(0);
            first.post(() -> {
                first.requestFocus();
                details.scrollTo(0, 0);
            });
        }
    }

    private static boolean isInside(View v, ViewGroup root) {
        if (v == root) return true;
        ViewParent p = v != null ? v.getParent() : null;
        while (p != null && p != root) p = p.getParent();
        return p == root;
    }

    // ---- level three: option lists rendered as panel rows ---------------------------------------

    /** True while an option list (level three) is on screen; Back then returns to the category. */
    private boolean inOptions;

    private interface OptionPick {
        void pick(int idx);
    }

    /**
     * A pick list inside the panel instead of a centre-screen AlertDialog — a dialog floating
     * over the half-panel read as out of place (the owner's ask: multi-level menus). The
     * current choice carries a ✓; picking one applies it and redraws the category rows.
     */
    private void showOptions(String titleText, String[] labels, int currentIdx, OptionPick pick) {
        inOptions = true;
        list.removeAllViews();
        title.setText(titleText);
        sub.setText(R.string.settings_back_hint);
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            row(labels[i] + (i == currentIdx ? "  ✓" : ""), null, v -> {
                pick.pick(idx);
                showCategory(activeCategory);
            });
        }
        int focusIdx = Math.max(0, Math.min(currentIdx, list.getChildCount() - 1));
        final View target = list.getChildAt(focusIdx);
        target.post(() -> {
            target.requestFocus();
            details.scrollTo(0, 0);
        });
    }

    // ---- level two: one category's rows ----------------------------------------------------------

    private void buildDeskRows() {
        row(getString(R.string.panel_columns), columnsValue = new TextView(this), v -> showColumnsDialog());
        row(getString(R.string.panel_dock_color), dockColorValue = new TextView(this), v -> pickColor(R.string.panel_dock_color, prefs.loadDockColor(), c -> prefs.saveDockColor(c)));
        row(getString(R.string.panel_icon_radius), radiusValue = new TextView(this), v -> showIconRadiusDialog());
        row(getString(R.string.panel_dock_visible), dockVisibleValue = new TextView(this), v -> {
            boolean on = !prefs.isDockVisible();
            prefs.setDockVisible(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.dock_visible_on : R.string.dock_visible_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.panel_dock_bar), dockBarValue = new TextView(this), v -> {
            boolean on = !prefs.isDockBarOn();
            prefs.setDockBarOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.dock_bar_on : R.string.dock_bar_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.panel_dock_frost), dockFrostValue = new TextView(this), v -> {
            boolean on = !prefs.isDockFrostOn();
            prefs.setDockFrostOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.dock_frost_on : R.string.dock_frost_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.panel_hero_art), heroArtValue = new TextView(this), v -> {
            boolean on = !prefs.isHeroArtOn();
            prefs.setHeroArtOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.hero_art_on : R.string.hero_art_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.panel_preview), previewValue = new TextView(this), v -> {
            boolean on = !prefs.isPreviewOn();
            prefs.setPreviewOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.preview_on : R.string.preview_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.manage_hidden), null, v -> showHidden());
        row(getString(R.string.dock_reset), null, v -> {
            prefs.clearDock();
            Toast.makeText(this, R.string.dock_reset_done, Toast.LENGTH_LONG).show();
        });
    }

    /**
     * The wallpaper panel, grouped by source and ordered inside each group: pick → tune →
     * fill. Three picture sources live here (aerial, Bing, the 360 gallery) and their rows used
     * to be interleaved, which read as noise on the TV (the owner's report).
     */
    private void buildWallpaperRows() {
        row(getString(R.string.panel_wallpaper), null, v -> showWallpaperDialog());
        row(getString(R.string.scan_upload), null, v -> showQrUpload());

        section(getString(R.string.sec_aerial));
        row(getString(R.string.gallery_open), null, v -> openGallery(WallpaperStock.Source.AERIAL));
        row(getString(R.string.wallpaper_aerial), aerialValue = new TextView(this), v -> showAerialIntervalDialog());
        row(getString(R.string.aerial_source), aerialSourceValue = new TextView(this), v -> showAerialSourceDialog());
        row(getString(R.string.aerial_res), aerialResValue = new TextView(this), v -> showAerialResDialog());
        row(getString(R.string.aerial_expand), aerialExpandValue = new TextView(this), v -> showAerialExpandDialog());

        section(getString(R.string.sec_bing));
        row(getString(R.string.gallery_open), null, v -> openGallery(WallpaperStock.Source.BING));
        row(getString(R.string.wallpaper_bing), bingValue = new TextView(this), v -> {
            boolean on = prefs.isBingWallpaperOn();
            prefs.setBingWallpaperOn(!on);
            if (!on) prefs.setAerialRotateOn(false);
            refreshValues();
            Toast.makeText(this, on ? R.string.wallpaper_bing_off : R.string.wallpaper_bing_on, Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.bing_rotate), bingRotateValue = new TextView(this), v -> showBingIntervalDialog());

        section(getString(R.string.sec_w360));
        row(getString(R.string.gallery_open), null, v -> openGallery(WallpaperStock.Source.W360));
        row(getString(R.string.w360_gallery), w360CatValue = new TextView(this), v -> showW360CatDialog());
        row(getString(R.string.w360_expand), w360ExpandValue = new TextView(this), v -> showW360ExpandDialog());
        row(getString(R.string.w360_rotate), w360RotateValue = new TextView(this), v -> showW360IntervalDialog());

        section(getString(R.string.sec_diag));
        row(getString(R.string.net_check), null, v -> runNetCheck());
    }

    /**
     * Opens the picture gallery of one wallpaper source (the owner's preview request): the
     * pool's pictures, a big preview and the "previous / next / set as wallpaper" controls.
     * The gallery is a full-screen activity — a picture needs room to be judged.
     */
    private void openGallery(WallpaperStock.Source source) {
        startActivity(new Intent(this, WallpaperGalleryActivity.class)
                .putExtra("source", source.folder.equals("bing") ? "bing"
                        : source.folder.equals("w360") ? "w360" : "aerial"));
    }

    /**
     * The wallpaper-source sweep: DNS, TCP and HTTP for every picture source, with the head of
     * each answer. The report lands in a dialog (and in logcat, tag TvHome.NetCheck).
     */
    private void runNetCheck() {
        Toast.makeText(this, R.string.net_check_running, Toast.LENGTH_SHORT).show();
        NetCheck.run(this, report -> {
            android.util.Log.d("TvHome.NetCheck", "===== panel report =====\n" + report);
            Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                    .setTitle(R.string.net_check)
                    .setMessage(report)
                    .setPositiveButton(android.R.string.ok, null)
                    .create());
        });
    }

    /**
     * A group label between rows. Not focusable on purpose: the remote skips straight over it
     * to the next real row instead of stopping on a heading.
     */
    private void section(String label) {
        float d = getResources().getDisplayMetrics().density;
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(0xFF8E8E93);
        t.setPadding((int) (22 * d), (int) (14 * d), 0, (int) (4 * d));
        t.setFocusable(false);
        t.setFocusableInTouchMode(false);
        list.addView(t);
    }

    private void buildScreensaverRows() {
        row(getString(R.string.panel_screensaver), screensaverValue = new TextView(this), v -> {
            boolean on = !prefs.isScreensaverOn();
            prefs.setScreensaverOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.screensaver_on : R.string.screensaver_off,
                    Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.screensaver_delay), screensaverDelayValue = new TextView(this),
                v -> showScreensaverDelayDialog());
        row(getString(R.string.screensaver_clock), screensaverClockValue = new TextView(this), v -> {
            boolean on = !prefs.isScreensaverClockOn();
            prefs.setScreensaverClockOn(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.screensaver_clock_on : R.string.screensaver_clock_off,
                    Toast.LENGTH_SHORT).show();
        });
    }

    private void buildClockRows() {
        row(getString(R.string.panel_weather), weatherValue = new TextView(this), v -> showWeatherDialog());
        row(getString(R.string.weather_unit), unitValue = new TextView(this), v -> {
            prefs.setWeatherFahrenheit(!prefs.isWeatherFahrenheit());
            refreshValues();
        });
        row(getString(R.string.panel_24h), h24Value = new TextView(this), v -> {
            boolean on = !prefs.isForce24h();
            prefs.setForce24h(on);
            refreshValues();
            Toast.makeText(this, on ? R.string.done_24h_on : R.string.done_24h_off, Toast.LENGTH_SHORT).show();
        });
        row(getString(R.string.panel_top_bar), topBarColorValue = new TextView(this), v -> pickColor(R.string.panel_top_bar, prefs.loadTopBarColor(), c -> prefs.saveTopBarColor(c)));
        row(getString(R.string.panel_top_text), topTextColorValue = new TextView(this), v -> pickColor(R.string.panel_top_text, prefs.loadTopTextColor(), c -> prefs.saveTopTextColor(c)));
    }

    private void buildSystemRows() {
        row(getString(R.string.lock_button), lockValue = new TextView(this), v -> toggleLock());
        // The lock is an accessibility service the owner must allow once; this row shows whether it
        // runs and opens the system page where it is allowed — no more hunting through the ROM.
        row(getString(R.string.lock_service), lockServiceValue = new TextView(this),
                v -> openAccessibilitySettings());
        row(getString(R.string.backup), null, v -> backup());
        row(getString(R.string.restore), null, v -> restore());
        row(getString(R.string.panel_control), null, v -> showControlCenter());
        row(getString(R.string.panel_system_settings), null, v -> systemSettings());
        // The row's value shows whether this desktop IS the default home right now.
        row(getString(R.string.system_home), systemHomeValue = new TextView(this), v -> systemHome());
        row(getString(R.string.panel_about), null, v -> showStorageInfo());
    }

    /** One tvOS row: label left, value right, chevron; white rounded card + slight zoom on focus. */
    private void row(String label, TextView value, RowAction action) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding((int) (22 * d), 0, (int) (18 * d), 0);
        r.setMinimumHeight((int) (58 * d));
        r.setFocusable(true);
        r.setBackgroundResource(android.R.color.transparent);
        GradientDrawable idle = round(ROW_BG, 16 * d), focused = round(CARD_BG, 16 * d);
        r.setBackground(idle);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (10 * d);
        r.setLayoutParams(lp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(16);
        name.setTextColor(LABEL_COLOR);
        r.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (value != null) {
            value.setTextSize(13);
            value.setTextColor(VALUE_COLOR);
            value.setPadding((int) (10 * d), 0, 0, 0);
            r.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        TextView chevron = new TextView(this);
        chevron.setText("›");
        chevron.setTextSize(20);
        chevron.setTextColor(0xFF57575E);
        chevron.setPadding((int) (10 * d), 0, 0, 0);
        r.addView(chevron, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        r.setOnFocusChangeListener((v, has) -> {
            // No zoom on focus: the row already spans the panel's full width, and scaling it
            // pushed the rounded corners past the ScrollView's clip edge — the white focus bar
            // read as a SQUARE-cornered slab (the owner's report).
            r.setBackground(has ? focused : idle);
            name.setTextColor(has ? 0xFF1A1A1C : LABEL_COLOR);
            if (value != null) value.setTextColor(has ? 0xFF6E6E73 : VALUE_COLOR);
            chevron.setTextColor(has ? 0xFF9A9AA0 : 0xFF57575E);
            if (has) v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), false);
        });
        r.setOnClickListener(v -> action.run(v));
        list.addView(r);
    }

    private static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private interface RowAction {
        void run(View v);
    }

    /** Fills the right-hand "current value" text of every dynamic row from the prefs. */
    private void refreshValues() {
        set(columnsValue, getString(R.string.columns_fmt, prefs.loadColumns(Config.load(this).columns)));
        set(dockColorValue, colorName(prefs.loadDockColor()));
        set(topBarColorValue, colorName(prefs.loadTopBarColor()));
        set(topTextColorValue, colorName(prefs.loadTopTextColor()));
        set(radiusValue, radiusName());
        set(dockVisibleValue, prefs.isDockVisible() ? "开" : "关");
        set(dockBarValue, prefs.isDockBarOn() ? "开" : "关");
        set(dockFrostValue, prefs.isDockFrostOn() ? "开" : "关");
        set(heroArtValue, prefs.isHeroArtOn() ? "开" : "关");
        set(bingValue, prefs.isBingWallpaperOn() ? getString(R.string.enabled_suffix).replace("（", "").replace("）", "") : "关");
        File base = Config.dir(this);
        File[] stills = base == null ? null : new File(base, "aerials").listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
        set(aerialValue, prefs.isAerialRotateOn()
                ? intervalName(prefs.loadAerialIntervalMin()) + " · " + (stills == null ? 0 : stills.length) + " 张" : "关");
        set(aerialExpandValue, (stills == null ? 0 : stills.length) + " 张");
        set(aerialResValue, aerialResName());
        set(aerialSourceValue, aerialSourceName(prefs.loadAerialSource()));
        File[] stock = base == null ? null : new File(base, "bing").listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
        int stockN = stock == null ? 0 : stock.length;
        set(bingRotateValue, prefs.isBingRotateOn()
                ? intervalName(prefs.loadBingIntervalMin()) + " · " + stockN + " 张" : "关");
        File[] w360 = base == null ? null : new File(base, "w360").listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
        int w360N = w360 == null ? 0 : w360.length;
        set(w360CatValue, Wall360.catName(prefs.loadW360Cat()));
        set(w360ExpandValue, w360N + " 张");
        set(w360RotateValue, prefs.isW360RotateOn()
                ? intervalName(prefs.loadW360IntervalMin()) + " · " + w360N + " 张" : "关");
        if (prefs.isWeatherOn()) {
            org.json.JSONObject loc = prefs.loadWeatherLoc();
            String city = loc == null ? "" : loc.optString("city", "");
            set(weatherValue, "开" + (city.isEmpty() ? "" : " · " + city));
        } else {
            set(weatherValue, "关");
        }
        set(unitValue, prefs.isWeatherFahrenheit() ? "℉" : "℃");
        set(h24Value, prefs.isForce24h() ? "开" : "关");
        set(lockValue, prefs.isLockEnabled() ? "开" : "关");
        set(lockServiceValue, lockServiceEnabled()
                ? getString(R.string.lock_service_on) : getString(R.string.lock_service_off));
        set(systemHomeValue, isDefaultHome()
                ? getString(R.string.system_home_is_default) : getString(R.string.system_home_not_default));
        set(screensaverValue, prefs.isScreensaverOn() ? "开" : "关");
        set(screensaverDelayValue, getString(R.string.screensaver_delay_fmt,
                prefs.loadScreensaverDelayMin()));
        set(screensaverClockValue, prefs.isScreensaverClockOn() ? "开" : "关");
        set(previewValue, prefs.isPreviewOn() ? "开" : "关");
    }

    /** Null-safe setText: a row of another category was never built, or its view was recycled. */
    private static void set(TextView v, String s) {
        if (v != null) v.setText(s);
    }

    private String radiusName() {
        switch (prefs.loadIconRadius()) {
            case 15: return getString(R.string.radius_small);
            case 35: return getString(R.string.radius_large);
            case 50: return getString(R.string.radius_circle);
            default: return getString(R.string.radius_0);
        }
    }

    /** The aerial still resolution: the pick, plus what "auto" currently resolves to. */
    private String aerialResName() {
        int res = prefs.loadAerialStillRes();
        if (res == 1080) return "1080p";
        if (res == 2160) return "4K";
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean screen4k = Math.max(dm.widthPixels, dm.heightPixels) >= 3000;
        return getString(R.string.aerial_res_auto) + " · " + (screen4k ? "4K" : "1080p");
    }

    /** Still resolution pick: auto / 1080p / 4K (4K pulls Apple's official HEVC catalog). */
    private void showAerialResDialog() {
        String[] labels = {getString(R.string.aerial_res_auto), "1080p", "4K"};
        int cur;
        switch (prefs.loadAerialStillRes()) {
            case 1080: cur = 1; break;
            case 2160: cur = 2; break;
            default: cur = 0;
        }
        showOptions(getString(R.string.aerial_res), labels, cur, idx -> {
            prefs.saveAerialStillRes(idx == 2 ? 2160 : idx == 1 ? 1080 : 0);
            Toast.makeText(this, R.string.aerial_res_done, Toast.LENGTH_SHORT).show();
        });
    }

    private String colorName(int color) {
        if (color == -1) return getString(R.string.color_default);
        String[] names = {"黑色", "深灰", "半透明白", "蓝色", "绿色", "红色", "紫色", "橙色"};
        int[] all = {0xFF000000, 0xE61E1E20, 0x80FFFFFF, 0xE61565C0, 0xE62E7D32, 0xE6B71C1C, 0xE66A1B9A, 0xE6EF6C00};
        for (int i = 0; i < all.length; i++) {
            if (all[i] == color) return names[i];
        }
        return String.format("#%08X", color);
    }

    // ---- the dialogs (unchanged behaviour, reused by the rows) -----------------------------------

    private void backup() {
        Config cfg = Config.load(this);
        List<String> dock = prefs.loadDock(cfg.dockApps);
        Set<String> hidden = prefs.loadHidden(new LinkedHashSet<>(cfg.hiddenApps));
        List<Folder> folders = prefs.loadFolders();
        File out = prefs.backup(this, dock, hidden, folders);
        Toast.makeText(this,
                out != null ? getString(R.string.backup_done, out.getPath()) : getString(R.string.backup_failed),
                Toast.LENGTH_LONG).show();
    }

    private void restore() {
        boolean ok = prefs.restore(this);
        Toast.makeText(this, ok ? R.string.restore_done : R.string.restore_failed, Toast.LENGTH_LONG).show();
    }

    private void showHidden() {
        Config cfg = Config.load(this);
        Set<String> hidden = prefs.loadHidden(new LinkedHashSet<>(cfg.hiddenApps));
        if (hidden.isEmpty()) {
            Toast.makeText(this, R.string.nothing_hidden, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = hidden.toArray(new String[0]);
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.hidden_apps_title)
                .setItems(names, (d, which) -> {
                    Set<String> copy = new LinkedHashSet<>(hidden);
                    copy.remove(names[which]);
                    prefs.saveHidden(copy);
                })
                .create());
    }

    private void systemHome() {
        try {
            startActivity(new Intent(Settings.ACTION_HOME_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    private void systemSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    // ---- dock size -----------------------------------------------------------------------------

    private void showColumnsDialog() {
        final int current = prefs.loadColumns(Config.load(this).columns);
        final int[] options = {4, 5, 6};
        int curIdx = 0;
        String[] labels = new String[options.length];
        for (int i = 0; i < options.length; i++) {
            labels[i] = getString(R.string.columns_fmt, options[i]);
            if (options[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.panel_columns), labels, curIdx, idx -> {
            prefs.saveColumns(options[idx]);
            Toast.makeText(this, getString(R.string.columns_done, options[idx]),
                    Toast.LENGTH_LONG).show();
        });
    }

    // ---- wallpaper -----------------------------------------------------------------------------

    private void showWallpaperDialog() {
        boolean hasWallpaper = Config.load(this).wallpaper != null;
        List<CharSequence> options = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        options.add(getString(R.string.wallpaper_pick));
        actions.add(this::pickWallpaper);
        boolean bing = prefs.isBingWallpaperOn();
        options.add(getString(R.string.wallpaper_bing) + (bing ? getString(R.string.enabled_suffix) : ""));
        actions.add(() -> {
            prefs.setBingWallpaperOn(!bing);
            if (!bing) prefs.setAerialRotateOn(false);
            if (!bing) Toast.makeText(this, R.string.wallpaper_bing_on, Toast.LENGTH_LONG).show();
            else Toast.makeText(this, R.string.wallpaper_bing_off, Toast.LENGTH_LONG).show();
        });
        boolean aerial = prefs.isAerialRotateOn();
        options.add(getString(R.string.wallpaper_aerial)
                + (aerial ? " · " + intervalName(prefs.loadAerialIntervalMin()) : ""));
        actions.add(this::showAerialIntervalDialog);
        if (hasWallpaper) {
            options.add(getString(R.string.wallpaper_reset));
            actions.add(() -> {
                prefs.setBingWallpaperOn(false);
                prefs.setAerialRotateOn(false);
                resetWallpaper();
            });
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.panel_wallpaper)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private static final int REQUEST_WALLPAPER = 42;

    private void pickWallpaper() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*");
        try {
            startActivityForResult(Intent.createChooser(i, getString(R.string.wallpaper_pick)), REQUEST_WALLPAPER);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_WALLPAPER || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        File dir = Config.dir(this);
        if (dir == null) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try (InputStream in = getContentResolver().openInputStream(data.getData());
             FileOutputStream out = new FileOutputStream(new File(dir, Config.WALLPAPER_NAME))) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            Toast.makeText(this, R.string.wallpaper_done, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            android.util.Log.w("TvHome", "wallpaper save failed: " + e);
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void resetWallpaper() {
        File dir = Config.dir(this);
        File file = dir != null ? new File(dir, Config.WALLPAPER_NAME) : null;
        if (file != null && file.isFile() && !file.delete()) {
            Toast.makeText(this, R.string.wallpaper_failed, Toast.LENGTH_LONG).show();
            return;
        }
        prefs.setWallpaperReset(true);   // keep plain black: the bundled wallpaper must not return
        Toast.makeText(this, R.string.wallpaper_removed, Toast.LENGTH_LONG).show();
    }

    /** QR-code wallpaper upload: the phone scans, opens the page, and POSTs a JPEG. */
    private void showQrUpload() {
        String token = String.format(java.util.Locale.US, "%08d",
                new java.util.Random().nextInt(100000000));
        WallpaperServer server = new WallpaperServer(this, token, () ->
                Toast.makeText(this, R.string.scan_upload_done, Toast.LENGTH_LONG).show());
        String url = server.start();
        if (url == null) {
            Toast.makeText(this, R.string.scan_upload_fail, Toast.LENGTH_LONG).show();
            return;
        }
        Bitmap qr;
        try {
            qr = QrCode.encode(url, 8);
        } catch (IllegalArgumentException tooLong) {
            server.stop();
            Toast.makeText(this, R.string.scan_upload_fail, Toast.LENGTH_LONG).show();
            return;
        }
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        ImageView image = new ImageView(this);
        image.setImageBitmap(qr);
        image.setPadding(pad, pad, pad, pad / 2);
        TextView hint = new TextView(this);
        hint.setText(getString(R.string.scan_upload_hint, url));
        hint.setTextColor(0xFFB9B9C2);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(pad, 0, pad, pad);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.addView(image, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(hint, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.scan_upload)
                .setView(box)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> server.stop())
                .create());
    }

    // ---- status bar / dock colours (inspired by AT4K) -------------------------------------------

    private static final String[] COLOR_NAMES = {"黑色", "深灰", "半透明白", "蓝色", "绿色", "红色", "紫色", "橙色"};
    private static final int[] BG_COLORS = {0xFF000000, 0xE61E1E20, 0x80FFFFFF, 0xE61565C0, 0xE62E7D32, 0xE6B71C1C, 0xE66A1B9A, 0xE6EF6C00};
    private static final int[] DOCK_COLORS = {0xFF000000, 0xE61E1E20, 0x40FFFFFF, 0xE61565C0, 0xE62E7D32, 0xE6B71C1C, 0xE66A1B9A, 0xE6EF6C00};
    private static final int[] TEXT_COLORS = {0xFFFFFFFF, 0xFFFFD54F, 0xFF4FC3F7, 0xFF81C784, 0xFFF06292, 0xFFFFB74D, 0xFFBDBDBD, 0xFFFFF3E0};

    private interface ColorPicked {
        void save(int color);
    }

    private void pickColor(int titleRes, int current, ColorPicked saver) {
        int[] palette = titleRes == R.string.panel_top_text ? TEXT_COLORS
                : titleRes == R.string.panel_dock_color ? DOCK_COLORS : BG_COLORS;
        // -1 ("follow the built-in look") leads the list, so a picked colour can always be undone.
        int[] values = new int[palette.length + 1];
        values[0] = -1;
        System.arraycopy(palette, 0, values, 1, palette.length);
        String[] labels = new String[values.length];
        int curIdx = 0;
        labels[0] = getString(R.string.color_default);
        for (int i = 1; i < values.length; i++) {
            labels[i] = COLOR_NAMES[i - 1] + String.format("  #%08X", values[i]);
            if (values[i] == current) curIdx = i;
        }
        showOptions(getString(titleRes), labels, curIdx, idx -> {
            saver.save(values[idx]);
            Toast.makeText(this, R.string.appearance_done, Toast.LENGTH_SHORT).show();
        });
    }

    // ---- weather clock (Open-Meteo, no key) ----------------------------------------------------

    private void showWeatherDialog() {
        boolean on = prefs.isWeatherOn();
        org.json.JSONObject loc = prefs.loadWeatherLoc();
        String city = loc == null ? "" : loc.optString("city", "");
        List<CharSequence> options = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        options.add(getString(on ? R.string.weather_disable : R.string.weather_enable));
        actions.add(() -> {
            if (!on && prefs.loadWeatherLoc() == null) {
                Toast.makeText(this, R.string.weather_need_city, Toast.LENGTH_LONG).show();
                pickWeatherCity();
                return;
            }
            prefs.setWeatherOn(!on);
            Toast.makeText(this, on ? R.string.weather_disabled : R.string.weather_enabled, Toast.LENGTH_LONG).show();
        });
        options.add(getString(R.string.weather_city) + (city.isEmpty() ? "" : "：" + city));
        actions.add(this::pickWeatherCity);
        boolean f = prefs.isWeatherFahrenheit();
        options.add(getString(R.string.weather_unit) + (f ? "：℉" : "：℃"));
        actions.add(() -> {
            prefs.setWeatherFahrenheit(!f);
            Toast.makeText(this, R.string.weather_unit_done, Toast.LENGTH_SHORT).show();
        });
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.panel_weather)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** City search against Open-Meteo's free geocoding API; picking a result turns the clock on. */
    private void pickWeatherCity() {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setHint(R.string.weather_city_hint);
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.weather_city)
                .setView(input)
                .setPositiveButton(R.string.weather_search, (d, which) -> {
                    final String q = input.getText().toString().trim();
                    if (q.isEmpty()) return;
                    Toast.makeText(this, R.string.weather_searching, Toast.LENGTH_SHORT).show();
                    new Thread(() -> {
                        String res = httpGet("https://geocoding-api.open-meteo.com/v1/search?name="
                                + urlEncode(q) + "&count=6&language=zh&format=json");
                        final String[][] hits = parseGeo(res);   // [i] = name|lat|lon
                        runOnUiThread(() -> {
                            if (hits.length == 0) {
                                Toast.makeText(this, R.string.weather_none, Toast.LENGTH_LONG).show();
                                return;
                            }
                            String[] names = new String[hits.length];
                            for (int i = 0; i < hits.length; i++) names[i] = hits[i][0];
                            Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                                    .setTitle(R.string.weather_city)
                                    .setItems(names, (dd, w) -> {
                                        try {
                                            prefs.saveWeatherLoc(hits[w][0],
                                                    Double.parseDouble(hits[w][1]),
                                                    Double.parseDouble(hits[w][2]));
                                            prefs.setWeatherOn(true);
                                            Toast.makeText(this, R.string.weather_saved, Toast.LENGTH_LONG).show();
                                        } catch (NumberFormatException ignored) {
                                        }
                                    })
                                    .setNegativeButton(android.R.string.cancel, null)
                                    .create());
                        });
                    }, "geo").start();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            android.util.Log.w("TvHome", "url encode failed: " + e);
            return s;
        }
    }

    /** Parses the geocoding response into [i][0]=display name, [1]=lat, [2]=lon; empty on failure. */
    private static String[][] parseGeo(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONObject(json).optJSONArray("results");
            if (a == null) return new String[0][];
            String[][] out = new String[a.length()][];
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject r = a.getJSONObject(i);
                String name = r.optString("name", "");
                String admin = r.optString("admin1", "");
                String country = r.optString("country", "");
                String display = name + (admin.isEmpty() || admin.equals(name) ? "" : "·" + admin)
                        + (country.isEmpty() ? "" : "·" + country);
                out[i] = new String[]{display, String.valueOf(r.optDouble("latitude", 0)),
                        String.valueOf(r.optDouble("longitude", 0))};
            }
            return out;
        } catch (Exception e) {
            android.util.Log.w("TvHome", "geocoding payload unusable: " + e);
            return new String[0][];
        }
    }

    private static String httpGet(String url) {
        String body = Http.getStringOrNull(url, 8000, 8000);
        if (body == null) {
            // Expected when the box is offline; debug level so a real problem is still traceable.
            android.util.Log.d("TvHome", "GET failed for " + url);
        }
        return body;
    }

    // ---- desktop lock ---------------------------------------------------------------------------

    private void toggleLock() {
        if (prefs.isLockEnabled()) {
            askPassword(getString(R.string.lock_disable_ask), code -> {
                if (prefs.loadLockPassword().equals(code)) {
                    prefs.setLockEnabled(false);
                    Toast.makeText(this, R.string.lock_off, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
                }
            });
            return;
        }
        askPassword(getString(R.string.lock_set_password), code -> {
            prefs.saveLockPassword(code);
            prefs.setLockEnabled(true);
            Toast.makeText(this, R.string.lock_on, Toast.LENGTH_LONG).show();
            showLockGuide();
        });
    }

    /** The lock only bites once the LockService accessibility service is enabled in system settings. */
    private void showLockGuide() {
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.lock_guide_title)
                .setMessage(R.string.lock_guide_message)
                .setPositiveButton(R.string.lock_go_accessibility,
                        (d, w) -> {
                            try {
                                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                            } catch (ActivityNotFoundException unavailable) {
                                Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Idle minutes before the screensaver (the owner's ask: 1…30 minutes). */
    private void showScreensaverDelayDialog() {
        final int[] mins = {1, 2, 5, 10, 15, 30};
        int current = prefs.loadScreensaverDelayMin();
        String[] labels = new String[mins.length];
        int curIdx = 0;
        for (int i = 0; i < mins.length; i++) {
            labels[i] = getString(R.string.screensaver_delay_fmt, mins[i]);
            if (mins[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.screensaver_delay), labels, curIdx,
                idx -> prefs.saveScreensaverDelayMin(mins[idx]));
    }

    /**
     * Whether the desktop-lock accessibility service is actually allowed to run.
     * <p>The stored component names come in two shapes: the full form
     * "package/full.class.Name" and the short form "package/.ClassName" (some ROMs and some
     * settings paths store the short one). Matching only the full form read "off" while the
     * system page showed the service enabled — the owner's report. Also: the old gathin build
     * is still installed under the same service label, so an enabled entry for THAT package
     * must not count here.
     */
    private boolean lockServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null || enabled.isEmpty()) return false;
        String pkg = getPackageName();
        String cls = LockService.class.getName();
        String full = pkg + "/" + cls;          // com.veil.launcher/com.veil.launcher.LockService
        String shortForm = pkg + "/." + cls.substring(cls.lastIndexOf('.') + 1); // com.veil.launcher/.LockService
        for (String s : enabled.split(":")) {
            String e = s.trim();
            if (e.equalsIgnoreCase(full) || e.equalsIgnoreCase(shortForm)) return true;
            // Any same-package entry whose class is our lock service, however it was flattened.
            int slash = e.indexOf('/');
            if (slash > 0 && e.substring(0, slash).equalsIgnoreCase(pkg)
                    && e.substring(slash + 1).endsWith("LockService")) return true;
        }
        return false;
    }

    /** Whether THIS desktop is the box's current default home (the resolve of the HOME intent). */
    private boolean isDefaultHome() {
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(probe,
                    android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
            return ri != null && ri.activityInfo != null && getPackageName().equals(ri.activityInfo.packageName);
        } catch (Exception unavailable) {
            return false;
        }
    }

    /** Opens the system page where the lock service is allowed (the row's value shows the state). */
    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, R.string.lock_service_hint, Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }

    /** A 4-digit numeric password prompt; cancel or a wrong-length code does nothing. */
    private void askPassword(String title, PasswordDone done) {
        final EditText input = new EditText(this);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint(R.string.enter_password_hint);
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String code = input.getText().toString().trim();
                    if (code.length() == 4) done.run(code);
                    else Toast.makeText(this, R.string.password_wrong, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private interface PasswordDone {
        void run(String code);
    }

    // ---- control centre (inspired by Tigris): system commands over the box's own debug port ------

    private void showControlCenter() {
        List<CharSequence> options = new java.util.ArrayList<>();
        List<Runnable> actions = new java.util.ArrayList<>();
        options.add(getString(R.string.control_info));
        actions.add(() -> adbRun("getprop ro.product.model; getprop ro.build.version.release"));
        options.add(getString(R.string.control_reboot));
        actions.add(() -> adbRun("reboot"));
        options.add(getString(R.string.control_poweroff));
        actions.add(() -> adbRun("reboot -p"));
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.panel_control)
                .setItems(options.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    /** Runs one command on a worker thread and reports back; first run guides the owner to Allow. */
    private void adbRun(String command) {
        Toast.makeText(this, R.string.control_grant_hint, Toast.LENGTH_LONG).show();
        final android.app.ProgressDialog wait = new android.app.ProgressDialog(this, R.style.TvHomeDialog);
        wait.setMessage(getString(R.string.control_waiting));
        wait.show();
        Dialogs.style(wait);
        AdbShell.run(this, command, (ok, output) -> runOnUiThread(() -> {
            try {
                wait.dismiss();
            } catch (Exception e) {
                android.util.Log.d("TvHome", "progress dialog already gone: " + e);
            }
            if (!ok) {
                String msg = "AUTH_WAIT".equals(output)
                        ? getString(R.string.control_grant_hint)
                        : getString(R.string.control_fail);
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                return;
            }
            String trimmed = output == null ? "" : output.trim();
            if (trimmed.isEmpty()) {
                Toast.makeText(this, R.string.control_done, Toast.LENGTH_LONG).show();
            } else {
                Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                        .setTitle(R.string.panel_control)
                        .setMessage(trimmed)
                        .setPositiveButton(android.R.string.ok, null)
                        .create());
            }
        }));
    }

    // ---- quick extras: icon radius / cadences / expander -----------------------------------------

    /** The aerial rotation cadence: off, or every 5 minutes up to once a day (the default). */
    private void showAerialIntervalDialog() {
        final int[] mins = {0, 5, 15, 30, 60, 180, 720, 1440};
        String[] names = {getString(R.string.aerial_off), getString(R.string.interval_5m),
                getString(R.string.interval_15m), getString(R.string.interval_30m),
                getString(R.string.interval_1h), getString(R.string.interval_3h),
                getString(R.string.interval_12h), getString(R.string.interval_1d)};
        int current = prefs.isAerialRotateOn() ? prefs.loadAerialIntervalMin() : 0;
        int curIdx = 0;
        for (int i = 0; i < mins.length; i++) {
            if (mins[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.wallpaper_aerial), names, curIdx, idx -> {
            if (mins[idx] == 0) {
                prefs.setAerialRotateOn(false);
            } else {
                boolean wasOff = !prefs.isAerialRotateOn();
                prefs.setAerialRotateOn(true);
                prefs.setBingRotateOn(false);   // the two rotations are mutually exclusive
                prefs.setW360RotateOn(false);
                prefs.saveAerialIntervalMin(mins[idx]);
                prefs.saveAerialLastAt(0L);   // rotate right away: the pick shows at once
                if (wasOff) Toast.makeText(this, R.string.aerial_on, Toast.LENGTH_SHORT).show();
            }
        });
    }

    /** Bing stock rotation: the same cadence picks as the aerial rotation, opposite exclusivity. */
    private void showBingIntervalDialog() {
        final int[] mins = {0, 5, 15, 30, 60, 180, 720, 1440};
        String[] names = {getString(R.string.aerial_off), getString(R.string.interval_5m),
                getString(R.string.interval_15m), getString(R.string.interval_30m),
                getString(R.string.interval_1h), getString(R.string.interval_3h),
                getString(R.string.interval_12h), getString(R.string.interval_1d)};
        int current = prefs.isBingRotateOn() ? prefs.loadBingIntervalMin() : 0;
        int curIdx = 0;
        for (int i = 0; i < mins.length; i++) {
            if (mins[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.bing_rotate), names, curIdx, idx -> {
            if (mins[idx] == 0) {
                prefs.setBingRotateOn(false);
            } else {
                boolean wasOff = !prefs.isBingRotateOn();
                prefs.setBingRotateOn(true);
                prefs.setAerialRotateOn(false);   // the aerial rotation yields the slot
                prefs.setW360RotateOn(false);     // so does the 360 rotation
                prefs.saveBingIntervalMin(mins[idx]);
                prefs.saveBingLastAt(0L);   // rotate right away: the pick shows at once
                if (wasOff) Toast.makeText(this, R.string.bing_rotate_on, Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---- 360 gallery (Wall360): category pick / one-tap expansion / rotation ---------------------

    /** Aerial expansion source: auto chain, or force ONE of the paths (the owner's ask). */
    private void showAerialSourceDialog() {
        final String[] ids = {"auto", "apple", "wallhaven", "w360", "video"};
        final String[] names = {
                "自动（内置 → Apple 视频 → Wallhaven → 360）",
                "仅 Apple 视频抽帧（需能连苹果 CDN）",
                "仅 Wallhaven（国际源，盒子常被墙）",
                "仅 360 图库（国内直连，最稳）",
                "航拍视频（动态背景，在线真播）",
        };
        String cur = prefs.loadAerialSource();
        int curIdx = 0;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i].equals(cur)) curIdx = i;
        }
        showOptions(getString(R.string.aerial_source), names, curIdx, idx -> {
            prefs.saveAerialSource(ids[idx]);
            refreshValues();
            Toast.makeText(this, "航拍图源：" + names[idx], Toast.LENGTH_SHORT).show();
        });
    }

    /** Human name of an aerial source id for the row's value column. */
    private String aerialSourceName(String source) {
        switch (source) {
            case "apple": return "仅 Apple 视频";
            case "wallhaven": return "仅 Wallhaven";
            case "w360": return "仅 360 图库";
            case "video": return "航拍视频（动态）";
            default: return "自动";
        }
    }

    /** The 360 gallery category: which of the curated lists the next expansion pulls from. */
    private void showW360CatDialog() {
        String cid = prefs.loadW360Cat();
        int curIdx = 0;
        for (int i = 0; i < Wall360.CAT_IDS.length; i++) {
            if (Wall360.CAT_IDS[i].equals(cid)) curIdx = i;
        }
        showOptions(getString(R.string.w360_gallery), Wall360.CAT_NAMES, curIdx, idx -> {
            prefs.saveW360Cat(Wall360.CAT_IDS[idx]);
            refreshValues();
            Toast.makeText(this, "已选「" + Wall360.CAT_NAMES[idx] + "」", Toast.LENGTH_SHORT).show();
        });
    }

    /** One-tap 360 expansion: a random window of the chosen category, about 12 pictures a pass. */
    private void showW360ExpandDialog() {
        if (w360ExpandBusy) {
            Toast.makeText(this, R.string.w360_expanding, Toast.LENGTH_SHORT).show();
            return;
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.w360_expand)
                .setMessage("从「" + Wall360.catName(prefs.loadW360Cat())
                        + "」随机拉取一批壁纸（约 12 张），需要网络。继续？")
                .setPositiveButton(android.R.string.ok, (d, w) -> expandW360Stock())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private boolean w360ExpandBusy;

    private void expandW360Stock() {
        w360ExpandBusy = true;
        set(w360ExpandValue, getString(R.string.w360_expanding));
        Wall360.expand(this, (added, total, error, detail) -> {
            w360ExpandBusy = false;
            refreshValues();
            String msg;
            if (error != null) {
                String why;
                switch (error) {
                    case Wall360.ERR_TIMEOUT: why = "网络超时"; break;
                    case Wall360.ERR_DECODE: why = "图片解码失败"; break;
                    case Wall360.ERR_EMPTY: why = "没有拉到新图（可能都已拉取过）"; break;
                    case "busy": why = "上一次拉取还在进行"; break;
                    default: why = "网络连不上";
                }
                // The low-level reason rides along: a screenshot of this toast pins the failure.
                msg = "360 图库拉取失败：" + why
                        + (detail == null || detail.isEmpty() ? "" : "\n[" + detail + "]");
            } else if (added > 0) {
                msg = "新增 " + added + " 张，图库共 " + total + " 张";
            } else {
                msg = "没有拉到新图，稍后再试";
            }
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    /** 360 rotation cadence: the same picks as the aerial/Bing rotations, shared exclusivity. */
    private void showW360IntervalDialog() {
        final int[] mins = {0, 5, 15, 30, 60, 180, 720, 1440};
        String[] names = {getString(R.string.aerial_off), getString(R.string.interval_5m),
                getString(R.string.interval_15m), getString(R.string.interval_30m),
                getString(R.string.interval_1h), getString(R.string.interval_3h),
                getString(R.string.interval_12h), getString(R.string.interval_1d)};
        int current = prefs.isW360RotateOn() ? prefs.loadW360IntervalMin() : 0;
        int curIdx = 0;
        for (int i = 0; i < mins.length; i++) {
            if (mins[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.w360_rotate), names, curIdx, idx -> {
            if (mins[idx] == 0) {
                prefs.setW360RotateOn(false);
            } else {
                boolean wasOff = !prefs.isW360RotateOn();
                prefs.setW360RotateOn(true);
                prefs.setAerialRotateOn(false);   // one rotation owns the wallpaper slot
                prefs.setBingRotateOn(false);
                prefs.saveW360IntervalMin(mins[idx]);
                prefs.saveW360LastAt(0L);   // rotate right away: the pick shows at once
                if (wasOff) Toast.makeText(this, "360 图库轮换已开启", Toast.LENGTH_SHORT).show();
            }
            refreshValues();
        });
    }

    /**
     * The one-tap aerial-stills expander. Confirms the ~10-30 MB download first, then pulls a
     * fresh clip and files a few frames; the row stays disabled and shows the progress while it
     * runs. Failures come back as error codes and are explained in the owner's language.
     */
    private void showAerialExpandDialog() {
        if (aerialExpandBusy) {
            Toast.makeText(this, R.string.aerial_expanding, Toast.LENGTH_SHORT).show();
            return;
        }
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.aerial_expand)
                .setMessage(R.string.aerial_expand_msg)
                .setPositiveButton(R.string.aerial_expand_go, (d, w) -> expandAerialStock())
                .setNegativeButton(android.R.string.cancel, null)
                .create());
    }

    private boolean aerialExpandBusy;

    private void expandAerialStock() {
        aerialExpandBusy = true;
        set(aerialExpandValue, getString(R.string.aerial_expanding));
        Toast.makeText(this, R.string.aerial_expanding, Toast.LENGTH_SHORT).show();
        AerialExpander.expand(this, (added, total, error, detail) -> {
            aerialExpandBusy = false;
            refreshValues();
            if (error != null) {
                int msgRes;
                switch (error) {
                    case AerialExpander.ERR_NET: msgRes = R.string.aerial_err_net; break;
                    case AerialExpander.ERR_TIMEOUT: msgRes = R.string.aerial_err_timeout; break;
                    case AerialExpander.ERR_EXHAUSTED: msgRes = R.string.aerial_expand_used_up; break;
                    default: msgRes = R.string.aerial_err_decode; break;
                }
                // The low-level reason rides along: a screenshot of this toast pins the failure.
                String msg = detail == null || detail.isEmpty()
                        ? getString(msgRes) : getString(msgRes) + "\n[" + detail + "]";
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            } else if (added > 0) {
                Toast.makeText(this, getString(R.string.aerial_expand_done, added, total),
                        Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, R.string.aerial_expand_none, Toast.LENGTH_LONG).show();
            }
        });
    }

    /** Human name for a cadence in minutes (5…1440); anything else reads as the daily default. */
    private String intervalName(int m) {
        switch (m) {
            case 5: return getString(R.string.interval_5m);
            case 15: return getString(R.string.interval_15m);
            case 30: return getString(R.string.interval_30m);
            case 60: return getString(R.string.interval_1h);
            case 180: return getString(R.string.interval_3h);
            case 720: return getString(R.string.interval_12h);
            default: return getString(R.string.interval_1d);
        }
    }

    private void showIconRadiusDialog() {
        final int[] values = {0, 15, 35, 50};
        final int current = prefs.loadIconRadius();
        String[] names = {getString(R.string.radius_0), getString(R.string.radius_small),
                getString(R.string.radius_large), getString(R.string.radius_circle)};
        int curIdx = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) curIdx = i;
        }
        showOptions(getString(R.string.panel_icon_radius), names, curIdx, idx -> {
            prefs.saveIconRadius(values[idx]);
            Toast.makeText(this, R.string.radius_done, Toast.LENGTH_SHORT).show();
        });
    }

    /** The device's RAM and data-storage usage, read straight from the system (no permissions). */
    private void showStorageInfo() {
        String msg;
        try {
            android.app.ActivityManager am = getSystemService(android.app.ActivityManager.class);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            if (am != null) am.getMemoryInfo(mi);
            android.os.StatFs stat = new android.os.StatFs(
                    android.os.Environment.getDataDirectory().getPath());
            long total = stat.getTotalBytes(), avail = stat.getAvailableBytes();
            msg = getString(R.string.about_ram, gb(mi.totalMem - mi.availMem), gb(mi.totalMem)) + "\n"
                    + getString(R.string.about_storage, gb(total - avail), gb(total));
        } catch (Exception e) {
            msg = getString(R.string.about_failed);
        }
        // The version goes in here too: the owner needs to be able to tell at a glance which
        // build is on the box (a release is only useful if the installed one can be named).
        msg = versionLine() + "\n" + msg;
        Dialogs.show(new AlertDialog.Builder(this, R.style.TvHomeDialog)
                .setTitle(R.string.panel_about)
                .setMessage(msg)
                .setPositiveButton(android.R.string.ok, null)
                .create());
    }

    /** "Veil v2.0.1 (201)" — read from the installed package, so it can never drift. */
    private String versionLine() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            long code = android.os.Build.VERSION.SDK_INT >= 28
                    ? pi.getLongVersionCode() : pi.versionCode;
            return "Veil v" + pi.versionName + " (" + code + ")";
        } catch (Exception e) {
            return "Veil";
        }
    }

    private static String gb(long bytes) {
        return String.format(java.util.Locale.getDefault(), "%.1f GB", bytes / 1073741824.0);
    }
}
