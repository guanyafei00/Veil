package com.veil.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The owner's edits: dock order and the hidden list, kept in SharedPreferences over the values
 * from res/values/config.xml (or a pushed theme's config.json). The effective value is the saved
 * one once anything has been edited, otherwise a copy of the config value, so the owner always
 * starts from what the box already shows. Export writes a small backup to the app's external
 * files folder, import reads it back. Nothing here touches the network.
 */
final class PrefsStore {
    private static final String TAG = "TvHome.Prefs";
    private static final String PREFS = "tvhome_prefs";
    private static final String KEY_DOCK = "dock_apps";
    private static final String KEY_HIDDEN = "hidden_apps";
    /**
     * The owner's own icon order on the desktop (the grid below the dock), as a list of
     * package names. Empty = the alphabetical default. Only the apps the owner actually
     * moved appear in it; the rest keep their alphabetical place around them.
     */
    private static final String KEY_ICON_ORDER = "icon_order";
    private static final String KEY_FOLDERS = "folders";
    private static final String KEY_LOCK_ENABLED = "lock_enabled";
    private static final String KEY_LOCK_PASSWORD = "lock_password";
    /** Launcher guard (the invisible watchdog): keeps this desktop as THE desktop by pulling it back
     *  whenever another HOME/launcher app takes the foreground. Default ON — the owner's ask is that
     *  the parents' TV stays on this launcher. */
    private static final String KEY_GUARD_ENABLED = "guard_enabled";
    private static final String KEY_COLUMNS = "dock_columns";
    private static final String KEY_TOP_BAR_COLOR = "top_bar_color";
    private static final String KEY_TOP_TEXT_COLOR = "top_text_color";
    private static final String KEY_DOCK_COLOR = "dock_color";
    private static final String KEY_WEATHER_ON = "weather_on";
    private static final String KEY_WEATHER_LOC = "weather_loc";
    private static final String KEY_WEATHER_CACHE = "weather_cache";
    private static final String KEY_WEATHER_TIME = "weather_time";
    private static final String KEY_FORCE_24H = "force_24h";
    private static final String KEY_WEATHER_F = "weather_fahrenheit";
    private static final String KEY_ICON_RADIUS = "icon_radius";
    private static final String KEY_BING_WALLPAPER = "bing_wallpaper";
    private static final String KEY_BING_DATE = "bing_wallpaper_date";
    private static final String KEY_BING_STOCK_DATE = "bing_stock_date";
    private static final String KEY_AERIAL_ROTATE = "aerial_rotate";
    private static final String KEY_AERIAL_INDEX = "aerial_wallpaper_index";
    private static final String KEY_AERIAL_INTERVAL = "aerial_interval_min";
    private static final String KEY_AERIAL_LAST = "aerial_last_at";
    private static final String KEY_BING_ROTATE = "bing_rotate";
    private static final String KEY_BING_INTERVAL = "bing_interval_min";
    private static final String KEY_BING_LAST = "bing_last_at";
    private static final String KEY_BING_INDEX = "bing_index";
    // The 360 gallery (Wall360): category pick + rotation state, mirroring the Bing keys.
    private static final String KEY_W360_CAT = "w360_cat";
    private static final String KEY_W360_ROTATE = "w360_rotate";
    private static final String KEY_W360_INTERVAL = "w360_interval_min";
    private static final String KEY_W360_LAST = "w360_last_at";
    private static final String KEY_W360_INDEX = "w360_index";
    /** Prefix for the per-category catalogue sizes ("w360_catalog_26" → 10000). */
    private static final String KEY_W360_CATALOG_PREFIX = "w360_catalog_";
    /** Prefix for the per-category window cursor ("w360_cursor_26" → 245). */
    private static final String KEY_W360_CURSOR_PREFIX = "w360_cursor_";
    private static final String KEY_AERIAL_USED_URLS = "aerial_used_urls";
    private static final String KEY_AERIAL_STILL_RES = "aerial_still_res";
    /** Aerial expansion source: auto | apple | wallhaven | w360 | video (the owner's manual override). */
    private static final String KEY_AERIAL_SOURCE = "aerial_source";
    /** First page shows the dock row (off = pure wallpaper page, the dock apps join the grid). */
    private static final String KEY_DOCK_VISIBLE = "dock_visible";
    /** Dock focus swaps the wallpaper for the focused app's own banner art (Apple TV style). */
    private static final String KEY_HERO_ART = "hero_art";
    /**
     * App content previews: while an app is open its screen is mirrored, and the focused app's
     * hero art then shows that real content instead of a banner or an icon. On by default; the
     * system's one-time capture consent is asked on the first app launch.
     */
    private static final String KEY_PREVIEW_ON = "preview_on";
    /** The launcher's own screensaver: off by default, the owner turns it on in the panel. */
    private static final String KEY_SCREENSAVER_ON = "screensaver_on";
    /** Idle minutes before the screensaver starts (1…30); only read while it is on. */
    private static final String KEY_SCREENSAVER_DELAY = "screensaver_delay_min";
    /** The clock over the screensaver art; on by default, like the Apple/AT4K look. */
    private static final String KEY_SCREENSAVER_CLOCK = "screensaver_clock";
    /** One-shot migration flag: v2.9 resets the dock to the new 5-app default and the dock colour. */
    private static final String KEY_MIGRATED_29 = "migrated_v29b";
    /** One-shot migration flag: v3.0 force-disables hero art (see {@link #KEY_HERO_ART}). */
    private static final String KEY_MIGRATED_30 = "migrated_v30";
    /** Dock frosted-glass bar: on by default; the owner asked for a switch to turn it off. */
    private static final String KEY_DOCK_FROST = "dock_frost_on";
    /**
     * Whether the dock bar exists at all (the owner's ask): off = no dock row anywhere, the app
     * icons keep the exact same layout and behaviour, just without the pinned row. Independent of
     * {@link #KEY_DOCK_VISIBLE}, which only decides whether the first page shows the layout at
     * rest or waits pure-wallpaper for a Down press.
     */
    private static final String KEY_DOCK_BAR = "dock_bar_on";
    private static final String BACKUP_NAME = "tvhome-backup.json";

    private final SharedPreferences sp;

    PrefsStore(Context ctx) {
        this.sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The dock as the owner edited it, or a copy of the config value when nothing was saved yet. */
    List<String> loadDock(List<String> configDock) {
        List<String> saved = readList(sp.getString(KEY_DOCK, null));
        return saved != null ? saved : new ArrayList<>(configDock);
    }

    /** The hidden set as the owner edited it, or a copy of the config value. */
    Set<String> loadHidden(Set<String> configHidden) {
        List<String> saved = readList(sp.getString(KEY_HIDDEN, null));
        if (saved != null) return new LinkedHashSet<>(saved);
        return new LinkedHashSet<>(configHidden);
    }

    void saveDock(List<String> dock) {
        sp.edit().putString(KEY_DOCK, writeList(dock)).apply();
    }

    void saveHidden(Set<String> hidden) {
        sp.edit().putString(KEY_HIDDEN, writeList(new ArrayList<>(hidden))).apply();
    }

    /**
     * The owner's icon order for the desktop grid (empty = alphabetical default).
     * The list holds package names; the adapter sorts by the position of a package in it.
     */
    List<String> loadIconOrder() {
        List<String> saved = readList(sp.getString(KEY_ICON_ORDER, null));
        return saved != null ? saved : new ArrayList<>();
    }

    void saveIconOrder(List<String> order) {
        sp.edit().putString(KEY_ICON_ORDER, writeList(order)).apply();
    }

    /** Folders as the owner edited them (empty when none was ever created). */
    List<Folder> loadFolders() {
        List<Folder> out = new ArrayList<>();
        try {
            String raw = sp.getString(KEY_FOLDERS, null);
            if (raw == null) return out;
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                Folder f = Folder.fromJson(a.optJSONObject(i));
                if (f != null) out.add(f);
            }
        } catch (Exception e) {
            Log.w(TAG, "load folders failed: " + e.getMessage());
        }
        return out;
    }

    void saveFolders(List<Folder> folders) {
        try {
            JSONArray a = new JSONArray();
            for (Folder f : folders) a.put(f.toJson());
            sp.edit().putString(KEY_FOLDERS, a.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "save folders failed: " + e.getMessage());
        }
    }

    /** The desktop lock (LockService bounces blocked apps back home) and its 4-digit password. */
    boolean isLockEnabled() {
        return sp.getBoolean(KEY_LOCK_ENABLED, false);
    }

    void setLockEnabled(boolean on) {
        sp.edit().putBoolean(KEY_LOCK_ENABLED, on).apply();
    }

    /** The launcher guard is on by default — the desktop should stay as THE desktop. */
    boolean isGuardEnabled() {
        return sp.getBoolean(KEY_GUARD_ENABLED, true);
    }

    void setGuardEnabled(boolean on) {
        sp.edit().putBoolean(KEY_GUARD_ENABLED, on).apply();
    }

    String loadLockPassword() {
        return sp.getString(KEY_LOCK_PASSWORD, "");
    }

    void saveLockPassword(String password) {
        sp.edit().putString(KEY_LOCK_PASSWORD, password).apply();
    }

    /** The owner's dock-size pick (tiles per row, 4-6); fallback when nothing was picked yet. */
    int loadColumns(int fallback) {
        return sp.getInt(KEY_COLUMNS, fallback);
    }

    void saveColumns(int columns) {
        sp.edit().putInt(KEY_COLUMNS, columns).apply();
    }

    // ---- appearance (state bar / dock colours) --------------------------------------------------

    /** -1 = follow the built-in look. */
    int loadTopBarColor() {
        return sp.getInt(KEY_TOP_BAR_COLOR, -1);
    }

    void saveTopBarColor(int color) {
        sp.edit().putInt(KEY_TOP_BAR_COLOR, color).apply();
    }

    int loadTopTextColor() {
        return sp.getInt(KEY_TOP_TEXT_COLOR, -1);
    }

    void saveTopTextColor(int color) {
        sp.edit().putInt(KEY_TOP_TEXT_COLOR, color).apply();
    }

    int loadDockColor() {
        return sp.getInt(KEY_DOCK_COLOR, -1);
    }

    void saveDockColor(int color) {
        sp.edit().putInt(KEY_DOCK_COLOR, color).apply();
    }

    // ---- weather clock (the launcher's one networked feature) ------------------------------------

    boolean isWeatherOn() {
        return sp.getBoolean(KEY_WEATHER_ON, false);
    }

    void setWeatherOn(boolean on) {
        sp.edit().putBoolean(KEY_WEATHER_ON, on).apply();
    }

    /** The chosen city as a small JSON: {"city":…,"lat":…,"lon":…}, or null before the first pick. */
    JSONObject loadWeatherLoc() {
        try {
            String raw = sp.getString(KEY_WEATHER_LOC, null);
            return raw == null ? null : new JSONObject(raw);
        } catch (Exception e) {
            Log.w(TAG, "weather location unreadable: " + e.getMessage());
            return null;
        }
    }

    void saveWeatherLoc(String city, double lat, double lon) {
        try {
            sp.edit().putString(KEY_WEATHER_LOC, new JSONObject()
                    .put("city", city).put("lat", lat).put("lon", lon).toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "weather location not saved: " + e.getMessage());
        }
    }

    String loadWeatherCache() {
        return sp.getString(KEY_WEATHER_CACHE, null);
    }

    void saveWeatherCache(String json) {
        sp.edit().putString(KEY_WEATHER_CACHE, json).putLong(KEY_WEATHER_TIME, System.currentTimeMillis()).apply();
    }

    long loadWeatherTime() {
        return sp.getLong(KEY_WEATHER_TIME, 0);
    }

    // ---- quick extras (inspired by AT4Ks) --------------------------------------------------------------

    /** The owner's 24-hour clock pick; off = follow the system setting (the built-in behaviour). */
    boolean isForce24h() {
        return sp.getBoolean(KEY_FORCE_24H, false);
    }

    void setForce24h(boolean on) {
        sp.edit().putBoolean(KEY_FORCE_24H, on).apply();
    }

    /** Weather temperatures in Fahrenheit instead of Celsius. */
    boolean isWeatherFahrenheit() {
        return sp.getBoolean(KEY_WEATHER_F, false);
    }

    void setWeatherFahrenheit(boolean f) {
        sp.edit().putBoolean(KEY_WEATHER_F, f).apply();
    }

    /** Icon corner rounding as a percent (0 = square, 50 = circle) of the icon's shorter side. */
    int loadIconRadius() {
        return sp.getInt(KEY_ICON_RADIUS, 15);   // small rounded corners out of the box
    }

    void saveIconRadius(int percent) {
        sp.edit().putInt(KEY_ICON_RADIUS, Math.max(0, Math.min(50, percent))).apply();
    }

    /** Bing daily wallpaper: fetch today's picture once a day and show it as the wallpaper. */
    boolean isBingWallpaperOn() {
        return sp.getBoolean(KEY_BING_WALLPAPER, false);
    }

    void setBingWallpaperOn(boolean on) {
        sp.edit().putBoolean(KEY_BING_WALLPAPER, on).apply();
    }

    String loadBingDate() {
        return sp.getString(KEY_BING_DATE, "");
    }

    void saveBingDate(String date) {
        sp.edit().putString(KEY_BING_DATE, date).apply();
    }

    /** The last day the multi-market stock backfill ran (it repeats once a day). */
    String loadBingStockDate() {
        return sp.getString(KEY_BING_STOCK_DATE, "");
    }

    void saveBingStockDate(String date) {
        sp.edit().putString(KEY_BING_STOCK_DATE, date).apply();
    }

    /** The owner explicitly went back to plain black: never re-adopt the bundled wallpaper. */
    boolean isWallpaperReset() {
        return sp.getBoolean("wallpaper_reset", false);
    }

    void setWallpaperReset(boolean reset) {
        sp.edit().putBoolean("wallpaper_reset", reset).apply();
    }

    /** How many of the APK's bundled aerial stills have already been released into the gallery. */
    int loadBundledReleased() {
        return sp.getInt("aerial_bundled_released", 0);
    }

    void saveBundledReleased(int count) {
        sp.edit().putInt("aerial_bundled_released", count).apply();
    }

    /** Apple aerial wallpaper rotation: show the next cached still every time the desktop starts. */
    boolean isAerialRotateOn() {
        return sp.getBoolean(KEY_AERIAL_ROTATE, false);
    }

    void setAerialRotateOn(boolean on) {
        sp.edit().putBoolean(KEY_AERIAL_ROTATE, on).apply();
    }

    /** Default true: the dock row shows until the owner picks the pure-wallpaper first page. */
    boolean isDockVisible() {
        return sp.getBoolean(KEY_DOCK_VISIBLE, true);
    }

    void setDockVisible(boolean visible) {
        sp.edit().putBoolean(KEY_DOCK_VISIBLE, visible).apply();
    }

    /**
     * Hero art off by default since v3.0: the owner found app content behind the desktop ugly
     * ("别显示应用的内容大图了……现在只显示壁纸"). One-time migration v30 force-disables it once
     * for existing installs; the switch stays in the panel so it can be turned back on.
     */
    boolean isHeroArtOn() {
        return sp.getBoolean(KEY_HERO_ART, false);
    }

    void setHeroArtOn(boolean on) {
        sp.edit().putBoolean(KEY_HERO_ART, on).apply();
    }

    /** Default false: the focused app's big picture prefers its own captured screen. */
    boolean isPreviewOn() {
        return sp.getBoolean(KEY_PREVIEW_ON, false);
    }

    void setPreviewOn(boolean on) {
        sp.edit().putBoolean(KEY_PREVIEW_ON, on).apply();
    }

    /** The launcher's own screensaver (aerial stills + the Bing picture, clock on top). */
    boolean isScreensaverOn() {
        return sp.getBoolean(KEY_SCREENSAVER_ON, false);
    }

    void setScreensaverOn(boolean on) {
        sp.edit().putBoolean(KEY_SCREENSAVER_ON, on).apply();
    }

    /** Idle minutes before the screensaver shows; 5 by default. */
    int loadScreensaverDelayMin() {
        return sp.getInt(KEY_SCREENSAVER_DELAY, 5);
    }

    void saveScreensaverDelayMin(int minutes) {
        sp.edit().putInt(KEY_SCREENSAVER_DELAY, Math.max(1, Math.min(30, minutes))).apply();
    }

    boolean isScreensaverClockOn() {
        return sp.getBoolean(KEY_SCREENSAVER_CLOCK, true);
    }

    void setScreensaverClockOn(boolean on) {
        sp.edit().putBoolean(KEY_SCREENSAVER_CLOCK, on).apply();
    }

    int loadAerialIndex() {
        return sp.getInt(KEY_AERIAL_INDEX, 0);
    }

    void saveAerialIndex(int index) {
        sp.edit().putInt(KEY_AERIAL_INDEX, index).apply();
    }

    /** Aerial rotation cadence in minutes (minimum 5, default a day); 0 is never stored here. */
    int loadAerialIntervalMin() {
        return sp.getInt(KEY_AERIAL_INTERVAL, 1440);
    }

    void saveAerialIntervalMin(int minutes) {
        sp.edit().putInt(KEY_AERIAL_INTERVAL, Math.max(5, Math.min(1440, minutes))).apply();
    }

    /** Epoch ms of the last aerial rotation: the gate for "every N minutes" cadence. */
    long loadAerialLastAt() {
        return sp.getLong(KEY_AERIAL_LAST, 0L);
    }

    void saveAerialLastAt(long t) {
        sp.edit().putLong(KEY_AERIAL_LAST, t).apply();
    }

    /** Bing stock rotation (the owner's call: many cached Bing pictures, not just today's). */
    boolean isBingRotateOn() {
        return sp.getBoolean(KEY_BING_ROTATE, false);
    }

    void setBingRotateOn(boolean on) {
        sp.edit().putBoolean(KEY_BING_ROTATE, on).apply();
    }

    /** Bing rotation cadence in minutes (minimum 5, default a day); mirrors the aerial keys. */
    int loadBingIntervalMin() {
        return sp.getInt(KEY_BING_INTERVAL, 1440);
    }

    void saveBingIntervalMin(int minutes) {
        sp.edit().putInt(KEY_BING_INTERVAL, Math.max(5, minutes)).apply();
    }

    long loadBingLastAt() {
        return sp.getLong(KEY_BING_LAST, 0L);
    }

    void saveBingLastAt(long t) {
        sp.edit().putLong(KEY_BING_LAST, t).apply();
    }

    int loadBingIndex() {
        return sp.getInt(KEY_BING_INDEX, 0);
    }

    void saveBingIndex(int index) {
        sp.edit().putInt(KEY_BING_INDEX, index).apply();
    }

    // ---- 360 gallery (Wall360): a China-reachable stock with an anime category -------------------

    /** The chosen category id (defaults to anime, the owner's ask); see Wall360.CAT_IDS. */
    String loadW360Cat() {
        return sp.getString(KEY_W360_CAT, "26");
    }

    void saveW360Cat(String cid) {
        sp.edit().putString(KEY_W360_CAT, cid).apply();
    }

    /**
     * The catalogue size of a 360 category, remembered from the last successful list call.
     * The API's WAF only lets one request through at a time, so the app cannot ask "how big is
     * this category?" and then "give me a window" — that pair of calls in a row is what got the
     * box's IP flagged. Caching the size lets the next tap ask for a different window of the
     * SAME single call.
     */
    int loadW360Catalog(String cid) {
        return sp.getInt(KEY_W360_CATALOG_PREFIX + cid, 0);
    }

    void saveW360Catalog(String cid, int total) {
        sp.edit().putInt(KEY_W360_CATALOG_PREFIX + cid, total).apply();
    }

    /**
     * Where the next 360 list window should start. The API only serves a category's first
     * 1000 items, so the cursor walks that range and wraps around — a random start would
     * keep landing on the block that was downloaded last time.
     */
    int loadW360Cursor(String cid) {
        return sp.getInt(KEY_W360_CURSOR_PREFIX + cid, 0);
    }

    void saveW360Cursor(String cid, int cursor) {
        sp.edit().putInt(KEY_W360_CURSOR_PREFIX + cid, cursor).apply();
    }

    /** 360 stock rotation; mirrors the Bing keys and shares the same slot exclusivity. */
    boolean isW360RotateOn() {
        return sp.getBoolean(KEY_W360_ROTATE, false);
    }

    void setW360RotateOn(boolean on) {
        sp.edit().putBoolean(KEY_W360_ROTATE, on).apply();
    }

    int loadW360IntervalMin() {
        return sp.getInt(KEY_W360_INTERVAL, 1440);
    }

    void saveW360IntervalMin(int minutes) {
        sp.edit().putInt(KEY_W360_INTERVAL, Math.max(5, minutes)).apply();
    }

    long loadW360LastAt() {
        return sp.getLong(KEY_W360_LAST, 0L);
    }

    void saveW360LastAt(long t) {
        sp.edit().putLong(KEY_W360_LAST, t).apply();
    }

    int loadW360Index() {
        return sp.getInt(KEY_W360_INDEX, 0);
    }

    void saveW360Index(int index) {
        sp.edit().putInt(KEY_W360_INDEX, index).apply();
    }

    /**
     * Aerial sources already pulled into the stills folder (clip URL basenames), so the
     * one-tap expander never re-pulls the same clip. Seeded with the six original sources
     * on first read: the first 30 stills came from exactly those clips.
     */
    java.util.Set<String> loadAerialUsedUrls() {
        java.util.Set<String> seeded = new java.util.HashSet<>(java.util.Arrays.asList(
                "SE_A016_C009_SDR_20190717_SDR_2K_AVC.mov",
                "comp_A013_C012_0122D6_CC_v01_SDR_PS_FINAL_20180709_SDR_2K_AVC.mov",
                "comp_DB_D008_C010_PSNK_v21_SDR_PS_20180914_F0F16157_SDR_2K_AVC.mov",
                "BO_A014_C023_SDR_20190717_F240F3709_SDR_2K_AVC.mov",
                "comp_GMT060_117NC_363D_1034_AUSTRALIA_v35_SDR_PS_FINAL_20180731_SDR_2K_AVC.mov",
                "P005_C002_UHD_SDR_2K_AVC.mov"));
        java.util.Set<String> stored = sp.getStringSet(KEY_AERIAL_USED_URLS, seeded);
        return stored == null || stored.isEmpty() ? seeded : stored;
    }

    void addAerialUsedUrl(String basename) {
        java.util.Set<String> used = new java.util.HashSet<>(loadAerialUsedUrls());
        used.add(basename);
        sp.edit().putStringSet(KEY_AERIAL_USED_URLS, used).apply();
    }

    /** Aerial still resolution: 0 = follow the display, else force 1080 / 2160. */
    int loadAerialStillRes() {
        return sp.getInt(KEY_AERIAL_STILL_RES, 0);
    }

    void saveAerialStillRes(int res) {
        int v = res == 2160 ? 2160 : res == 1080 ? 1080 : 0;
        sp.edit().putInt(KEY_AERIAL_STILL_RES, v).apply();
    }

    /**
     * Aerial expansion source: "auto" runs the whole chain (bundled → Apple clips → Wallhaven →
     * 360), the other picks force ONE path — the owner asked for a manual Wallhaven choice.
     */
    String loadAerialSource() {
        return sp.getString(KEY_AERIAL_SOURCE, "auto");
    }

    void saveAerialSource(String source) {
        sp.edit().putString(KEY_AERIAL_SOURCE, source).apply();
    }

    /** Dock edits live over the config defaults; this drops back to them (settings row). */
    void clearDock() {
        sp.edit().remove(KEY_DOCK).apply();
    }

    /** Drops the dock-colour pick so the frosted-glass default shows again. */
    void clearDockColor() {
        sp.edit().remove(KEY_DOCK_COLOR).apply();
    }

    boolean isMigratedV29() {
        return sp.getBoolean(KEY_MIGRATED_29, false);
    }

    void setMigratedV29() {
        sp.edit().putBoolean(KEY_MIGRATED_29, true).apply();
    }

    boolean isMigratedV30() {
        return sp.getBoolean(KEY_MIGRATED_30, false);
    }

    void setMigratedV30() {
        sp.edit().putBoolean(KEY_MIGRATED_30, true).apply();
    }

    /** Dock frosted glass on by default; off falls back to the dark gradient bar. */
    boolean isDockFrostOn() {
        return sp.getBoolean(KEY_DOCK_FROST, true);
    }

    void setDockFrostOn(boolean on) {
        sp.edit().putBoolean(KEY_DOCK_FROST, on).apply();
    }

    /** The dock bar exists by default; off removes the pinned row (icons stay as they are). */
    boolean isDockBarOn() {
        return sp.getBoolean(KEY_DOCK_BAR, true);
    }

    void setDockBarOn(boolean on) {
        sp.edit().putBoolean(KEY_DOCK_BAR, on).apply();
    }

    /** Writes the current layout to the app's external files folder, or null when that is unavailable. */
    File backup(Context ctx, List<String> dock, Set<String> hidden, List<Folder> folders) {
        File dir = Config.dir(ctx);
        if (dir == null) return null;
        try {
            JSONObject json = new JSONObject();
            json.put("version", 2);
            json.put("dock_apps", new JSONArray(dock));
            json.put("hidden_apps", new JSONArray(new ArrayList<>(hidden)));
            JSONArray fa = new JSONArray();
            for (Folder f : folders) fa.put(f.toJson());
            json.put("folders", fa);
            json.put("lock_enabled", sp.getBoolean(KEY_LOCK_ENABLED, false));
            json.put("lock_password", sp.getString(KEY_LOCK_PASSWORD, ""));
            json.put("screensaver_on", sp.getBoolean(KEY_SCREENSAVER_ON, false));
            json.put("screensaver_delay_min", sp.getInt(KEY_SCREENSAVER_DELAY, 5));
            json.put("screensaver_clock", sp.getBoolean(KEY_SCREENSAVER_CLOCK, true));
            json.put("preview_on", sp.getBoolean(KEY_PREVIEW_ON, false));
            json.put("bing_rotate", sp.getBoolean(KEY_BING_ROTATE, false));
            json.put("bing_interval_min", sp.getInt(KEY_BING_INTERVAL, 1440));
            json.put("aerial_used_urls", new JSONArray(new ArrayList<>(loadAerialUsedUrls())));
            File out = new File(dir, BACKUP_NAME);
            try (FileOutputStream fo = new FileOutputStream(out)) {
                fo.write(json.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "backup failed: " + e.getMessage());
            return null;
        }
    }

    /** Reads the written backup back into the prefs; false when it is missing or unusable. */
    boolean restore(Context ctx) {
        File in = backupFile(ctx);
        if (in == null || !in.isFile()) return false;
        try (InputStream s = new FileInputStream(in)) {
            byte[] buf = new byte[(int) in.length()];
            int n = 0, r;
            while (n < buf.length && (r = s.read(buf, n, buf.length - n)) > 0) n += r;
            JSONObject json = new JSONObject(new String(buf, 0, n, StandardCharsets.UTF_8));
            List<String> dock = readList(json.optJSONArray("dock_apps"));
            if (dock == null) return false;
            Set<String> hidden = new LinkedHashSet<>(readList(json.optJSONArray("hidden_apps")));
            List<Folder> folders = new ArrayList<>();
            JSONArray fa = json.optJSONArray("folders");   // absent in v1 backups: no folders to restore
            if (fa != null) {
                for (int i = 0; i < fa.length(); i++) {
                    Folder f = Folder.fromJson(fa.optJSONObject(i));
                    if (f != null) folders.add(f);
                }
            }
            saveDock(dock);
            saveHidden(hidden);
            saveFolders(folders);
            if (json.has("lock_enabled")) {
                sp.edit().putBoolean(KEY_LOCK_ENABLED, json.optBoolean("lock_enabled", false))
                        .putString(KEY_LOCK_PASSWORD, json.optString("lock_password", ""))
                        .apply();
            }
            if (json.has("screensaver_on")) {
                sp.edit().putBoolean(KEY_SCREENSAVER_ON, json.optBoolean("screensaver_on", false))
                        .putInt(KEY_SCREENSAVER_DELAY, json.optInt("screensaver_delay_min", 5))
                        .putBoolean(KEY_SCREENSAVER_CLOCK, json.optBoolean("screensaver_clock", true))
                        .apply();
            }
            if (json.has("preview_on")) {
                sp.edit().putBoolean(KEY_PREVIEW_ON, json.optBoolean("preview_on", false)).apply();
            }
            if (json.has("bing_rotate")) {
                sp.edit().putBoolean(KEY_BING_ROTATE, json.optBoolean("bing_rotate", false))
                        .putInt(KEY_BING_INTERVAL, Math.max(5, json.optInt("bing_interval_min", 1440)))
                        .apply();
            }
            if (json.has("aerial_used_urls")) {
                List<String> used = readList(json.optJSONArray("aerial_used_urls"));
                if (used != null && !used.isEmpty()) {
                    sp.edit().putStringSet(KEY_AERIAL_USED_URLS, new HashSet<>(used)).apply();
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "restore failed: " + e.getMessage());
            return false;
        }
    }

    File backupFile(Context ctx) {
        File dir = Config.dir(ctx);
        return dir != null ? new File(dir, BACKUP_NAME) : null;
    }

    private static String writeList(List<String> values) {
        return new JSONArray(values).toString();
    }

    private static List<String> readList(String json) {
        try {
            return json == null ? null : readList(new JSONArray(json));
        } catch (Exception e) {
            Log.w(TAG, "list unreadable: " + e.getMessage());
            return null;
        }
    }

    private static List<String> readList(JSONArray a) {
        if (a == null) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            String s = a.optString(i);
            if (s != null && !s.trim().isEmpty()) out.add(s.trim());
        }
        return out;
    }
}
