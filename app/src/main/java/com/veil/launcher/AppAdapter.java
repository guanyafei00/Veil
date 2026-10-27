package com.veil.launcher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.OvershootInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * App tiles. The first {@link MainActivity#COLUMNS} positions are the pinned dock (favourites, fixed order),
 * then the module cards the owner chose to show (by label), the rest is alphabetical.
 */
public class AppAdapter extends RecyclerView.Adapter<AppAdapter.Holder> {

    interface TileListener {
        void onTileFocused(int position, View card);

        /** Called for a D-pad direction key on a tile. Return true when handled (focus stays put). */
        boolean onTileVerticalKey(int position, int keyCode);

        /**
         * Called when OK opens an app tile: the desktop arms the content preview (screen capture)
         * first and only then starts the activity, so the hero art can show the app's own screen.
         */
        void onTileLaunch(Entry e);

        /** Called when OK is held on a tile: the owner's edit menu (pin, hide, app info). */
        void onTileLongPress(int position, View card);

        /** Called when OK opens a folder tile; the owner asks for the password when it is locked. */
        void onFolderOpen(Folder folder);
    }

    static final class Entry {
        static final int APP = 0, HOME_SETTINGS = 1, CARD = 2, FOLDER = 3;

        final int kind;
        final String label;
        final ComponentName component;
        final String pkg;
        final Drawable banner;
        final Drawable icon;
        /** Text under the tile when it is focused in the grid: the label, plus the caption for a card. */
        final String name;
        /** Ambient glow colour; 0 until sampled from the banner, or the card's accent from the start. */
        int ambient;
        /** The folder this tile stands for (FOLDER entries only). */
        final Folder folder;
        /**
         * The app's cached content preview — a real screen captured from inside the app — or null
         * when it has never been captured. The focused app's hero art shows it (the owner's ask:
         * the app's own content, never a blown-up icon).
         */
        File preview;

        Entry(String label, ComponentName component, Drawable banner, Drawable icon) {
            this(APP, label, component, banner, icon, null, 0, null);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent) {
            this(kind, label, component, banner, icon, caption, accent, null);
        }

        Entry(int kind, String label, ComponentName component, Drawable banner, Drawable icon, String caption, int accent, Folder folder) {
            this.kind = kind;
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
            this.name = caption == null ? label : label + " \u00B7 " + caption;
            this.ambient = accent;
            this.folder = folder;
        }

        /** Stable component key for a folder tile (never a real package on the device). */
        static ComponentName folderComponent(String folderId) {
            return new ComponentName("com.veil.launcher.folder", folderId);
        }
    }

    static final float FOCUS_SCALE = 1.10f;
    /** Design size of a tile (dp); tiles shrink from here when the columns don't fit, see tileWidthPx. */
    static final float DESIGN_W_DP = 180f, DESIGN_H_DP = 101f;

    /**
     * One background thread for the tile scan (multi-model review P0): the PackageManager query
     * and the label/banner/icon loads used to run on the main thread and janked the grid on every
     * package change and resume. Shared across adapters — a rebuild makes a new adapter, the work
     * is the same — and it lives for the process, which for a home screen never stops anyway.
     */
    private static final java.util.concurrent.ExecutorService IO = java.util.concurrent.Executors
            .newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "tile-scan");
                t.setPriority(Thread.MIN_PRIORITY + 1);   // stay out of the UI's way
                return t;
            });
    private static final android.os.Handler MAIN =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Tile width for a grid of this inner width: the cell minus room for the focus zoom and the glass
     * highlight, capped at the design size so 4 columns on a 1080p TV look exactly as before.
     */
    static int tileWidthPx(int innerWidthPx, int columns, float density) {
        int design = Math.round(DESIGN_W_DP * density);
        if (innerWidthPx <= 0 || columns <= 0) return design;
        int cell = innerWidthPx / columns;
        int fit = (int) ((cell - 12 * density) / FOCUS_SCALE);
        return Math.max(Math.round(80 * density), Math.min(design, fit));
    }

    static int tileHeightPx(int tileWidthPx) {
        return Math.round(tileWidthPx * DESIGN_H_DP / DESIGN_W_DP);
    }

    /** Height of one grid item: the tile, the name label under it and the item's own padding. */
    static int itemHeightPx(int tileHeightPx, float density) {
        return tileHeightPx + Math.round(45 * density);
    }

    private final Context ctx;
    private final TileListener listener;
    private final int dockSize;
    /** Icon corner rounding as a fraction of the icon's shorter side (0 = square, 0.5 = circle). */
    private final float iconRadiusFrac;
    /** False = the owner hid the dock row (pure-wallpaper first page): the first row is a normal
     *  grid row, and the dock apps merge into the alphabetical list. */
    private boolean dockRowShown = true;
    /** The tile's near-black plate, corners following the icon radius (shared: every tile is one size). */
    private android.graphics.drawable.GradientDrawable plate;
    /** Dock order (config.xml dock_apps, or the owner's edits over it). */
    private List<String> favourites;
    /** Never shown as tiles (res/values/config.xml hidden_apps, or the owner's edits). */
    private Set<String> hidden;
    /** Folders the owner created (AT4K-like); their apps are not shown as standalone tiles. */
    private List<Folder> folders;
    /**
     * The owner's own icon order for the free grid (the apps below the dock): package names in
     * the order the owner arranged them. Apps NOT in this list keep their alphabetical place
     * relative to each other and follow the owner's list — so a moved icon lands exactly where
     * it was dropped, and an app installed later does not jump in front of the arrangement.
     */
    private List<String> iconOrder = Collections.emptyList();
    /** The app the owner picked up (move mode), or null when nothing is being moved. */
    private String movingPkg;
    private final boolean showHomeSettings;
    private final List<Entry> items = new ArrayList<>();

    AppAdapter(Context ctx, TileListener listener, int dockSize, List<String> dockApps, Set<String> hiddenApps,
               List<Folder> folders, boolean showHomeSettings, float iconRadiusFrac) {
        this.ctx = ctx;
        this.listener = listener;
        this.dockSize = dockSize;
        this.favourites = dockApps;
        this.hidden = hiddenApps;
        this.folders = folders;
        this.showHomeSettings = showHomeSettings;
        this.iconRadiusFrac = iconRadiusFrac;
        // Tiles keep their identity across reload(), so a focused tile stays on its app instead of
        // its view being rebound to whatever app now sits in that slot.
        setHasStableIds(true);
    }

    /** The owner's dock order, hidden list or folders changed (long-press menu, restored backup): adopt them. */
    void setEdits(List<String> dockApps, Set<String> hiddenApps, List<Folder> folders) {
        this.favourites = dockApps;
        this.hidden = hiddenApps;
        this.folders = folders;
    }

    /**
     * The owner's icon order for the free grid (the apps below the dock). The adapter sorts
     * the free grid by this list; apps missing from it keep their alphabetical order and come
     * after the owner's list, so a newly installed app never jumps into an arrangement the
     * owner made on purpose.
     */
    void setIconOrder(List<String> order) {
        this.iconOrder = order == null ? Collections.<String>emptyList() : order;
    }

    /**
     * The app the owner picked up (move mode), or null. The picked-up tile is drawn faded so
     * it reads as "in hand" while the remote walks to the target slot.
     * <p>
     * No notifyItemChanged here: rebinding the focused tile would cost the focus, and the
     * desktop drives the fade on the view itself (see MainActivity.startMove).
     */
    void setMovingPkg(String pkg) {
        movingPkg = pkg;
    }

    /** The position of an app by package name, or -1. */
    int positionOfPkg(String pkg) {
        if (pkg == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            Entry e = items.get(i);
            if (e.pkg != null && e.pkg.equals(pkg)) return i;
        }
        return -1;
    }

    /**
     * The group a tile belongs to: 0 = the dock row, 1 = folders, 2 = module cards,
     * 3 = the free app grid. The owner's moves stay inside one group — an app cannot be
     * dropped into the dock row (that is what "pin to dock" is for) or into a folder.
     */
    int groupAt(int position) {
        if (position < 0 || position >= items.size()) return -1;
        return groupOf(items.get(position));
    }

    /** The group of one entry (the sort order's first key). */
    private int groupOf(Entry e) {
        if (e.kind == Entry.APP && favourites.contains(e.pkg)) return dockRowShown ? 0 : 3;
        if (e.kind == Entry.FOLDER) return 1;
        if (e.kind == Entry.CARD) return 2;
        return 3;
    }

    /** The app's place in the owner's icon order, or -1 when the owner never moved it. */
    private int indexInIconOrder(String pkg) {
        if (pkg == null || iconOrder.isEmpty()) return -1;
        return iconOrder.indexOf(pkg);
    }

    /** True when the tile is a plain app (not a card, not a folder, not the home tile). */
    boolean isPlainAppAt(int position) {
        if (position < 0 || position >= items.size()) return false;
        return items.get(position).kind == Entry.APP;
    }

    /**
     * The free grid's apps in display order — the list the owner's icon order is written
     * from. It is read off the sorted items, so it already reflects the current arrangement.
     */
    List<String> freeGridPackages() {
        List<String> out = new ArrayList<>();
        for (Entry e : items) {
            if (groupOf(e) == 3 && e.kind == Entry.APP && e.pkg != null) out.add(e.pkg);
        }
        return out;
    }

    /**
     * The first and last position of the free grid (the apps below the dock, the folders and
     * the module cards). The owner's move mode keeps the focus inside this range, so a stray
     * Right or Up cannot walk the remote out of the grid and into the top bar.
     */
    int freeGridStart() {
        for (int i = 0; i < items.size(); i++) {
            if (groupOf(items.get(i)) == 3) return i;
        }
        return -1;
    }

    int freeGridEnd() {
        for (int i = items.size() - 1; i >= 0; i--) {
            if (groupOf(items.get(i)) == 3) return i;
        }
        return -1;
    }

    /**
     * Rebuilds the tiles. A shown module card stands in for every tile of its package (same component,
     * so focus follows it); hidden_apps wins over a card, and a card's package in dock_apps takes that slot.
     * Folders (AT4K-like) come right after the dock, and the apps inside them are not shown again.
     * <p>The scan itself is background work (multi-model review P0): the entries are built on the
     * IO thread and swapped in on the main thread, and only then does {@code onDone} run. Reloads
     * queue in order on the one background thread, so the last one submitted lands last.
     */
    void reload(List<Cards.Card> cards) {
        reload(cards, null);
    }

    void reload(List<Cards.Card> cards, Runnable onDone) {
        // The application context is all the scan needs (PackageManager, resources, files dir):
        // it must not hold the activity alive while it runs (multi-model review P1).
        final Context appCtx = ctx.getApplicationContext();
        IO.execute(() -> {
            final List<Entry> fresh = buildEntries(appCtx, cards);
            MAIN.post(() -> {
                if (ctx instanceof android.app.Activity && ((android.app.Activity) ctx).isDestroyed()) return;
                items.clear();
                items.addAll(fresh);
                notifyDataSetChanged();
                if (onDone != null) onDone.run();
            });
        });
    }

    /**
     * The tile list build: package query, labels, banners, icons, custom icons and the sort.
     * Pure data — no views, no activity — so it is safe on the background thread (the drawables
     * it loads are only attached to views later, on the main thread). Reads the edit lists as
     * they are when it runs; every edit path submits its own reload right after, so the last
     * build to land is always the current one.
     */
    private List<Entry> buildEntries(Context ctx, List<Cards.Card> cards) {
        PackageManager pm = ctx.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(probe, 0);
        Map<String, Cards.Card> cardByPkg = new HashMap<>();
        for (Cards.Card c : cards) cardByPkg.put(c.pkg, c);
        Set<String> inFolder = new HashSet<>();
        for (Folder f : folders) inFolder.addAll(f.apps);
        List<Entry> fresh = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg) || inFolder.contains(pkg)) continue;
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Cards.Card card = cardByPkg.get(pkg);
            if (card != null && !card.component.equals(component)) continue;   // the card stands in for this tile
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            Drawable icon = ri.loadIcon(pm);
            Drawable custom = customIcon(ctx, pkg);
            if (custom != null) {
                // Icon management (inspired by AT4K): the owner's picture takes the icon slot, and the
                // banner hides so the custom icon is what the tile actually shows.
                icon = custom;
                banner = null;
            }
            // The owner's radius dial rounds the picture itself (RoundedIconDrawable), not only the
            // view frame: a padded, letterboxed or small icon can no longer keep square corners.
            icon = RoundedIconDrawable.wrap(icon, iconRadiusFrac, 512);
            banner = RoundedIconDrawable.wrap(banner, iconRadiusFrac, 512);
            Entry entry;
            if (card != null) {
                entry = new Entry(Entry.CARD, card.label, component, banner, icon, card.caption, card.accent);
            } else {
                entry = new Entry(String.valueOf(ri.loadLabel(pm)), component, banner, icon);
            }
            entry.preview = PreviewCapture.fileFor(ctx, pkg);
            fresh.add(entry);
        }
        for (Folder f : folders) {
            String caption = f.apps.isEmpty()
                    ? ctx.getString(R.string.folder_empty)
                    : ctx.getString(R.string.folder_count, f.apps.size());
            if (f.isLocked()) caption += " \u00B7 " + ctx.getString(R.string.folder_locked_short);
            fresh.add(new Entry(Entry.FOLDER, f.name, Entry.folderComponent(f.id), null,
                    ctx.getDrawable(R.drawable.ic_folder), caption, 0, f));
        }
        Collections.sort(fresh, (a, b) -> {
            // Group order: dock apps, folders, module cards, then the free app grid. With the
            // dock row hidden the pinned apps fall back into the free grid instead.
            int ga = groupOf(a);
            int gb = groupOf(b);
            if (ga != gb) return Integer.compare(ga, gb);
            if (ga == 0) {
                int fa = favourites.indexOf(a.pkg), fb = favourites.indexOf(b.pkg);
                if (fa >= 0 || fb >= 0) {
                    if (fa < 0) return 1;
                    if (fb < 0) return -1;
                    return Integer.compare(fa, fb);
                }
            }
            if (ga == 1) {   // folders keep the owner's creation order
                int fa = folders.indexOf(a.folder), fb = folders.indexOf(b.folder);
                if (fa >= 0 || fb >= 0) {
                    if (fa < 0) return 1;
                    if (fb < 0) return -1;
                    return Integer.compare(fa, fb);
                }
            }
            if (ga == 3) {
                // The owner's own arrangement wins over the alphabet. Apps the owner never
                // moved keep their alphabetical order and follow the arranged ones, so a new
                // app shows up at the end of the grid instead of inside the arrangement.
                int oa = indexInIconOrder(a.pkg), ob = indexInIconOrder(b.pkg);
                if (oa >= 0 || ob >= 0) {
                    if (oa < 0) return 1;
                    if (ob < 0) return -1;
                    return Integer.compare(oa, ob);
                }
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        // Optional "Default home" tile (config.xml), only when this TV actually has a home-screen picker.
        if (showHomeSettings
                && pm.resolveActivity(new Intent(Settings.ACTION_HOME_SETTINGS), 0) != null) {
            fresh.add(new Entry(Entry.HOME_SETTINGS, ctx.getString(R.string.home_settings),
                    new ComponentName(ctx, HomeSettingsActivity.class), null,
                    ctx.getDrawable(R.drawable.ic_home_settings), null, 0));
        }
        return fresh;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
        float density = parent.getResources().getDisplayMetrics().density;
        int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
        int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
        int w = tileWidthPx(inner, dockSize, density), h = tileHeightPx(w);
        View card = v.findViewById(R.id.card);
        ViewGroup.LayoutParams cardLp = card.getLayoutParams();
        cardLp.width = w;
        cardLp.height = h;
        card.setLayoutParams(cardLp);
        View name = v.findViewById(R.id.name);
        ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
        nameLp.width = w;
        nameLp.topMargin = h + Math.round(7 * density);
        name.setLayoutParams(nameLp);
        return new Holder(v, listener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder h, int position) {
        Entry e = items.get(position);
        // A recycled tile keeps whatever alpha the glass fade left on it (a row that dissolved
        // under the top bar is at 0): reset it here, or the tile stays invisible after a rebind
        // until some scroll happens to fade it back — the owner's "dock icons disappear".
        h.itemView.setAlpha(1f);
        h.name.setText(e.name);
        h.name.setVisibility(dockRowShown && position < dockSize ? View.INVISIBLE : View.VISIBLE);   // the dock has no labels
        boolean focused = h.card.isFocused();   // a rebind after reload() can hit the focused tile
        h.name.setAlpha(focused ? 1f : 0f);
        // Reload can rebind a tile mid-animation: restore the label's park/raise offset too.
        h.name.setTranslationY(h.itemView.getResources().getDisplayMetrics().density
                * (focused ? 4f : -6f));
        h.card.setScaleX(focused ? FOCUS_SCALE : 1f);
        h.card.setScaleY(focused ? FOCUS_SCALE : 1f);
        // A full-bleed banner paints its own rounded corners, so the card's near-black plate
        // would show through every clipped corner as black edges. Dock tiles sit on the light
        // frosted band, so they go transparent too; grid icon tiles keep the plate — the plate
        // is their design background and keeps the white label readable on bright wallpapers.
        // The plate's corners follow the same radius dial as the icon, so frame and icon match.
        boolean seeThrough = (dockRowShown && position < dockSize) || e.banner != null;
        if (seeThrough && (e.banner != null || e.icon != null)) {
            h.card.setBackground(null);
        } else {
            if (plate == null) {
                plate = new android.graphics.drawable.GradientDrawable();
                plate.setColor(0xFF1C1C1E);
                plate.setCornerRadius(iconRadiusFrac
                        * Math.min(h.card.getWidth(), h.card.getHeight()));
            }
            h.card.setBackground(plate);
        }
        // Move mode: the picked-up tile fades out (it is "in hand"). The drop target needs no
        // extra paint — the desktop's glass highlight already sits on the focused tile, which
        // IS the target.
        boolean isMoving = movingPkg != null && movingPkg.equals(e.pkg);
        h.card.setAlpha(isMoving ? 0.3f : 1f);
        if (e.banner != null) {
            h.banner.setImageDrawable(e.banner);
            h.banner.setVisibility(View.VISIBLE);
            applyIconRadius(h.banner, iconRadiusFrac);   // banner tiles get the same corner dial
            h.icon.setVisibility(View.GONE);
            h.label.setVisibility(View.GONE);
        } else {
            h.banner.setImageDrawable(null);
            h.banner.setVisibility(View.GONE);
            applyIconRadius(h.icon, iconRadiusFrac);
            h.icon.setImageDrawable(e.icon);
            h.icon.setVisibility(View.VISIBLE);
            h.label.setText(e.label);
            h.label.setVisibility(View.VISIBLE);
        }
        h.card.setOnClickListener(v -> launch(e));
        h.card.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (listener != null && pos != RecyclerView.NO_POSITION) listener.onTileLongPress(pos, v);
            return true;
        });
    }

    /** Opens the tile's activity by explicit component, with no extras: a card can name nothing else. */
    private void launch(Entry e) {
        if (e.kind == Entry.HOME_SETTINGS) {
            ctx.startActivity(new Intent(ctx, HomeSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return;
        }
        if (e.kind == Entry.FOLDER) {
            if (listener != null) listener.onFolderOpen(e.folder);
            return;
        }
        if (listener != null) {
            listener.onTileLaunch(e);   // the desktop arms the content preview, then starts it
            return;
        }
        startApp(e);
    }

    /** Starts the app tile's activity (also the path the desktop takes once the preview is armed). */
    void startApp(Entry e) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(e.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception ex) {
            android.util.Log.w("TvHome", "cannot open " + e.label + ": " + ex);
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, e.label), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @Override
    public long getItemId(int position) {
        return items.get(position).component.flattenToShortString().hashCode();
    }

    /** Stable key for the app at a position (its component), or null. */
    String keyAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).component.flattenToShortString() : null;
    }

    /** Package name of the app at a position, or null (quick tiles like Default home). */
    String pkgAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.kind == Entry.APP || e.kind == Entry.CARD ? e.pkg : null;
    }

    /** The folder the tile at this position stands for, or null when it is not a folder tile. */
    Folder folderAt(int position) {
        if (position < 0 || position >= items.size()) return null;
        Entry e = items.get(position);
        return e.kind == Entry.FOLDER ? e.folder : null;
    }

    /** Whether the app at a position sits in the dock (the first dockSize positions, pinned). */
    boolean isDockedAt(int position) {
        if (position < 0 || position >= items.size()) return false;
        return favourites.contains(items.get(position).pkg);
    }

    /** Position of the app with this key, or -1 when it is gone. */
    int positionOf(String key) {
        if (key == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).component.flattenToShortString().equals(key)) return i;
        }
        return -1;
    }

    String labelAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).label : "";
    }

    /** The app's own banner art (its manifest banner), or null — the dock focus hero shows it. */
    android.graphics.drawable.Drawable bannerAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).banner : null;
    }

    /** The app's cached content preview (a real screen from inside the app), or null. */
    File previewAt(int position) {
        return position >= 0 && position < items.size() ? items.get(position).preview : null;
    }

    /** True once the dock row is hidden: the first row is then a normal grid row. */
    void setDockRowShown(boolean shown) {
        this.dockRowShown = shown;
    }

    /** Corner radius for a tile of this size at the owner's radius fraction (same dial as the icon). */
    static float cornerRadius(int wPx, int hPx, float frac) {
        return frac * Math.min(wPx, hPx);
    }

    /** The owner's custom icon for a package (icon_&lt;pkg&gt;.png in the app's files dir), or null. */
    static Drawable customIcon(Context ctx, String pkg) {
        java.io.File dir = Config.dir(ctx);
        java.io.File f = dir != null ? new java.io.File(dir, "icon_" + pkg + ".png") : null;
        if (f == null || !f.isFile()) return null;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            int sample = 1;   // cap at ~256 px: an icon never needs more, even on 4K panels
            while (o.outWidth / (sample * 2) >= 256 && o.outHeight / (sample * 2) >= 256) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeFile(f.getPath(), o);
            return bmp == null ? null : new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
        } catch (Exception | OutOfMemoryError bad) {
            // A corrupt or oversized picture just means "no custom icon"; the app's own icon stays.
            android.util.Log.d("TvHome", "custom icon for " + pkg + " unusable: " + bad);
            return null;
        }
    }

    /**
     * Rounds the icon view (0 = square, 0.5 = circle); AT4K-like corner dial. The card behind the
     * picture is set transparent in onBindViewHolder, so the clipped corners show whatever is
     * behind the tile (the frosted dock band, the black grid floor) instead of the near-black card
     * background — no hard black edges at any radius.
     */
    private void applyIconRadius(ImageView iv, float frac) {
        if (frac <= 0f) {
            iv.setClipToOutline(false);
            iv.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
            return;
        }
        iv.setClipToOutline(true);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        frac * Math.min(view.getWidth(), view.getHeight()));
            }
        });
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final View card;
        final ImageView banner;
        final ImageView icon;
        final TextView label;
        final TextView name;

        Holder(View v, TileListener listener) {
            super(v);
            card = v.findViewById(R.id.card);
            banner = v.findViewById(R.id.banner);
            icon = v.findViewById(R.id.icon);
            label = v.findViewById(R.id.label);
            name = v.findViewById(R.id.name);

            final float density = v.getResources().getDisplayMetrics().density;
            // No card-level outline clip: the icon and the banner clip themselves at the owner's
            // radius, and the plate behind them is built with the same radius (see onBindViewHolder),
            // so frame and icon always match — and the bottom label is never cut by a corner arc.

            card.setOnKeyListener((view, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                        && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                int pos = getBindingAdapterPosition();
                return listener != null && pos != RecyclerView.NO_POSITION && listener.onTileVerticalKey(pos, keyCode);
            });

            card.setOnFocusChangeListener((view, focused) -> {
                float s = focused ? FOCUS_SCALE : 1.0f;
                view.animate().scaleX(s).scaleY(s)
                        .setDuration(focused ? 260 : 180)
                        .setInterpolator(new OvershootInterpolator(focused ? 1.6f : 0f))
                        .start();
                name.animate().alpha(focused ? 1f : 0f)
                        .translationY(focused ? density * 4f : -density * 6f)
                        .setDuration(220).start();
                int pos = getBindingAdapterPosition();
                if (focused && listener != null && pos != RecyclerView.NO_POSITION) {
                    listener.onTileFocused(pos, view);
                }
            });
        }
    }
}
