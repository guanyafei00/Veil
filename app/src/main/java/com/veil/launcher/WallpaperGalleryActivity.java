package com.veil.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The wallpaper gallery: the owner opens a picture source and sees what is actually in the
 * pool — the big preview on top, the pool's pictures as a filmstrip below, and the controls
 * along the bottom.
 * <p>
 * Before this, a source was a number in a row ("every 30 minutes", "expand the library") with
 * no way to see the pictures behind it. The owner's ask was a preview and a "next picture"
 * button; this activity is both, plus the obvious third thing once the pictures are visible:
 * pick one and keep it.
 * <p>
 * The decoding is the whole difficulty. A 4K still is 33 MB as ARGB_8888 and this box has
 * little RAM — decoding a full-size grid of those is an instant OutOfMemoryError. So the
 * thumbnails are sampled down to a strip-sized bitmap and the big preview is sampled to the
 * screen, both off the main thread, and the cells are recycled as the strip scrolls.
 */
public class WallpaperGalleryActivity extends Activity {

    private static final String EXTRA_SOURCE = "source";

    /** Thumbnail size in the strip (dp). The strip cell is wider than tall (16:9). */
    private static final int THUMB_W_DP = 168;
    private static final int THUMB_H_DP = 94;

    private WallpaperStock.Source source;
    private final List<File> pool = new ArrayList<>();
    /** The pool slot on screen. -1 = nothing picked yet. */
    private int selected = -1;

    private ImageView preview;
    private TextView title, count, empty;
    private LinearLayout cells;
    private HorizontalScrollView strip;

    /**
     * Two pools: the filmstrip decodes many small thumbnails, the big preview decodes one big
     * picture. On one thread the preview would wait behind all 28 thumbnails — and this box
     * decodes a 4K still slowly. The preview pool is a single thread (only the newest preview
     * matters); the thumbnail pool has a few threads (they are independent).
     */
    private final ExecutorService thumbIo = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "gallery-thumb");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final ExecutorService previewIo = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "gallery-preview");
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    /**
     * Bumped per preview load so a slow decode cannot land after a newer one. The thumbnails
     * need no such guard: every one of them is wanted.
     */
    private int previewGen;
    /** The cell carrying the "this is the rotation's next picture" dot. */
    private FrameLayout dotCell;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_wallpaper_gallery);
        String key = getIntent().getStringExtra(EXTRA_SOURCE);
        source = "bing".equals(key) ? WallpaperStock.Source.BING
                : "w360".equals(key) ? WallpaperStock.Source.W360
                : WallpaperStock.Source.AERIAL;

        preview = findViewById(R.id.galleryPreview);
        title = findViewById(R.id.galleryTitle);
        count = findViewById(R.id.galleryCount);
        empty = findViewById(R.id.galleryEmpty);
        cells = findViewById(R.id.galleryCells);
        strip = findViewById(R.id.galleryStrip);

        title.setText(getString(labelFor(source)));
        pool.addAll(WallpaperStock.list(this, source));
        if (pool.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(R.string.gallery_empty);
            count.setText(getString(R.string.gallery_count, 0));
            strip.setVisibility(View.GONE);
        } else {
            count.setText(getString(R.string.gallery_count, pool.size()));
            buildStrip();
            // Open on the picture the pool would show next — the one the desktop is about to use.
            selected = indexOf(WallpaperStock.current(this, source));
            if (selected < 0) selected = 0;
            showSelected(false);
        }

        findViewById(R.id.galleryPrev).setOnClickListener(v -> step(-1));
        findViewById(R.id.galleryNext).setOnClickListener(v -> step(1));
        findViewById(R.id.galleryApply).setOnClickListener(v -> applySelected());
        findViewById(R.id.galleryClose).setOnClickListener(v -> finish());

        // The first focus goes to the strip so the remote walks the pictures first.
        if (pool.isEmpty()) {
            findViewById(R.id.galleryNext).requestFocus();
        } else {
            cells.post(() -> {
                View cell = cells.getChildAt(Math.min(selected, cells.getChildCount() - 1));
                if (cell != null) cell.requestFocus();
            });
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        thumbIo.shutdownNow();
        previewIo.shutdownNow();
    }

    /** The pool's own label for this source, so the header names the right library. */
    private static int labelFor(WallpaperStock.Source s) {
        switch (s) {
            case BING: return R.string.sec_bing;
            case W360: return R.string.sec_w360;
            default: return R.string.sec_aerial;
        }
    }

    // ---- the filmstrip --------------------------------------------------------------------------

    /**
     * One cell per picture: a sampled thumbnail, focusable, with the focused one marked.
     * The bitmap is decoded in the background — a 4K still decoded on the main thread would
     * stutter the whole screen.
     */
    private void buildStrip() {
        float d = getResources().getDisplayMetrics().density;
        int w = Math.round(THUMB_W_DP * d);
        int h = Math.round(THUMB_H_DP * d);
        for (int i = 0; i < pool.size(); i++) {
            final int idx = i;
            FrameLayout cell = new FrameLayout(this);
            cell.setFocusable(true);
            cell.setFocusableInTouchMode(true);

            ImageView img = new ImageView(this);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setContentDescription(null);
            cell.addView(img, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            // The cell's own layout params must be LinearLayout's — the strip is a horizontal
            // LinearLayout, and a plain MarginLayoutParams would lose the width on the way in.
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            lp.rightMargin = Math.round(10 * d);
            cell.setLayoutParams(lp);

            cell.setOnFocusChangeListener((v, has) -> {
                v.setScaleX(has ? 1.06f : 1f);
                v.setScaleY(has ? 1.06f : 1f);
                markBorder(cell, has);
                if (has) {
                    // Moving the remote over a picture shows it big — the preview follows the focus.
                    selected = idx;
                    showPreview(pool.get(idx));
                }
            });            cell.setOnClickListener(v -> {
                selected = idx;
                showPreview(pool.get(idx));
                applySelected();
            });

            cells.addView(cell);
            loadThumb(cell, img, pool.get(i), i);
        }
    }

    /** The focused cell gets a white ring; the others a thin translucent one. */
    private void markBorder(View cell, boolean focused) {
        float d = getResources().getDisplayMetrics().density;
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(6 * d);
        g.setStroke(Math.round((focused ? 3 : 1) * d),
                focused ? 0xFFFFFFFF : 0x33FFFFFF);
        cell.setBackground(g);
    }

    /** Decodes one thumbnail off the main thread and puts it in the cell. */
    private void loadThumb(View cell, ImageView img, File file, int idx) {
        thumbIo.execute(() -> {
            int w = Math.round(THUMB_W_DP * getResources().getDisplayMetrics().density);
            int h = Math.round(THUMB_H_DP * getResources().getDisplayMetrics().density);
            Bitmap bmp = WallpaperStock.preview(this, file, w, h);
            main.post(() -> {
                if (isFinishing() || cells.getChildCount() == 0) return;
                if (bmp != null) img.setImageBitmap(bmp);
            });
        });
    }

    /**
     * Marks the picture that is the desktop wallpaper right now with a small dot, so the
     * owner can see which one of the pool's pictures is on the screen.
     */
    private void markWallpaperDot(int idx) {
        if (dotCell != null) {
            android.view.ViewGroup parent = (android.view.ViewGroup) dotCell.getParent();
            if (parent != null) parent.removeView(dotCell);
            dotCell = null;
        }
        if (idx < 0 || idx >= cells.getChildCount()) return;
        FrameLayout cell = (FrameLayout) cells.getChildAt(idx);
        ImageView dot = new ImageView(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xFFFFFFFF);
        dot.setBackground(bg);
        float d = getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                Math.round(8 * d), Math.round(8 * d));
        dlp.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.END;
        dlp.bottomMargin = Math.round(6 * d);
        dlp.rightMargin = Math.round(6 * d);
        cell.addView(dot, dlp);
        dotCell = cell;
    }

    // ---- the picture being shown -----------------------------------------------------------------

    /** Shows one picture in the big preview (sampled to the screen, off the main thread). */
    private void showPreview(File file) {
        if (file == null) return;
        final int myGen = ++previewGen;
        previewIo.execute(() -> {
            int w = getResources().getDisplayMetrics().widthPixels;
            Bitmap bmp = WallpaperStock.preview(this, file, w, w * 9 / 16);
            main.post(() -> {
                if (myGen != previewGen || isFinishing()) return;
                if (bmp != null) preview.setImageBitmap(bmp);
            });
        });
    }

    /** Points the big preview at the selected picture, without applying it. */
    private void showSelected(boolean apply) {
        if (selected < 0 || selected >= pool.size()) return;
        showPreview(pool.get(selected));
        markWallpaperDot(wallpaperSlot());
        if (apply) applySelected();
    }

    /**
     * The pool slot whose picture is the desktop wallpaper right now, or -1 when it cannot be
     * told. The wallpaper slot holds a copy of a pool file, so the two are compared by size —
     * cheap (one stat per file) and good enough to point at the right thumbnail. When two
     * files share that size, nothing is marked rather than the wrong one.
     */
    private int wallpaperSlot() {
        File wall = Config.load(this).wallpaper;
        if (wall == null || !wall.isFile()) return -1;
        long size = wall.length();
        int found = -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).length() != size) continue;
            if (found >= 0) return -1;   // ambiguous: two pictures of that size
            found = i;
        }
        return found;
    }

    /**
     * Walks the pool by one picture and shows it. Both directions apply the picture: on a TV
     * the "next picture" button means "show me another one", and the desktop follows.
     */
    private void step(int delta) {
        if (pool.isEmpty()) return;
        int n = pool.size();
        selected = ((selected + delta) % n + n) % n;
        showPreview(pool.get(selected));
        scrollToSelected();
        applySelected();
    }

    /** Brings the selected cell into view in the strip. */
    private void scrollToSelected() {
        View cell = cells.getChildAt(selected);
        if (cell != null) cell.requestFocus();
    }

    /** Copies the shown picture over the wallpaper slot; the desktop picks it up on resume. */
    private void applySelected() {
        if (selected < 0 || selected >= pool.size()) return;
        File file = pool.get(selected);
        if (!WallpaperStock.applyPicked(this, source, file)) {
            Toast.makeText(this, R.string.gallery_apply_failed, Toast.LENGTH_LONG).show();
            return;
        }
        markWallpaperDot(indexOf(file));
        Toast.makeText(this, getString(R.string.gallery_applied, file.getName()),
                Toast.LENGTH_SHORT).show();
        // The desktop is behind this activity and watches the wallpaper file's stamp, so the
        // new picture shows the moment the owner goes back.
    }

    /** The pool slot this file sits in, or -1 when it is not in the pool. */
    private int indexOf(File file) {
        if (file == null) return -1;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).equals(file)) return i;
        }
        return -1;
    }
}
