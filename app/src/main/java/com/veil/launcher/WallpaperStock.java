package com.veil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * One wallpaper source's picture pool, seen from the settings panel and the preview gallery.
 * <p>
 * The desktop already rotates three picture sources (the Apple aerial stills, the Bing archive
 * and the 360 gallery), each with its own folder, its own index and its own copy-into-the-slot
 * step. The panel could not show them: the owner could not see what was in the pool, could not
 * pick a specific picture, and had no way to say "the next one, please".
 * <p>
 * This class is the shared view of those pools. It lists the pictures of a source, shows a
 * preview of any of them, and applies one to the wallpaper slot — the same three sources, the
 * same folders, the same indices the rotation already uses, so nothing can drift apart.
 * <p>
 * The index means ONE thing everywhere: <b>the pool slot the rotation will show next</b>.
 * Advancing it (next) applies that slot and moves on; going back (previous) applies the slot
 * before it and leaves the index pointing just after the picture now on screen, so the timed
 * rotation continues from where the owner left off instead of jumping.
 */
final class WallpaperStock {

    /** Which pool this is. The folder name and the index key are derived from it. */
    enum Source {
        AERIAL("aerials", "aerial"),
        BING("bing", "bing"),
        W360("w360", "w360");

        final String folder;
        final String label;

        Source(String folder, String label) {
            this.folder = folder;
            this.label = label;
        }
    }

    private WallpaperStock() {
    }

    /** The pictures of a source, sorted by name (the order the rotation walks them in). */
    static List<File> list(Context ctx, Source source) {
        File base = Config.dir(ctx);
        if (base == null) return new ArrayList<>();
        File dir = new File(base, source.folder);
        File[] files = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files);
        return new ArrayList<>(Arrays.asList(files));
    }

    /**
     * Loads a picture for the preview, scaled down to fit the panel. The full 4K bitmap is
     * 33 MB as ARGB_8888 — on this box that is an OutOfMemoryError, so the decode is sampled
     * down instead.
     */
    static Bitmap preview(Context ctx, File file, int maxW, int maxH) {
        if (file == null || !file.isFile()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), bounds);
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getPath(), opts);
        } catch (Exception | OutOfMemoryError bad) {
            android.util.Log.w("TvHome", "wallpaper preview failed: " + bad);
            return null;
        }
    }

    /**
     * Copies a picture over the wallpaper slot, so the desktop shows it right away.
     * Returns false when the copy failed (the slot keeps its old picture).
     */
    static boolean apply(Context ctx, File picture) {
        File base = Config.dir(ctx);
        if (base == null || picture == null || !picture.isFile()) return false;
        File out = new File(base, Config.WALLPAPER_NAME);
        try (FileInputStream in = new FileInputStream(picture);
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            return true;
        } catch (Exception e) {
            android.util.Log.w("TvHome", "wallpaper apply failed: " + e);
            return false;
        }
    }

    /**
     * Moves to the NEXT picture of the pool and applies it: the "next picture" button the
     * owner asked for. The index advances exactly like the timed rotation does, so the button
     * and the rotation never fight over the same counter.
     *
     * @return the file that was applied, or null when the pool is empty
     */
    static File next(Context ctx, Source source) {
        List<File> pool = list(ctx, source);
        if (pool.isEmpty()) return null;
        PrefsStore prefs = new PrefsStore(ctx);
        int index = advance(ctx, source, prefs);
        File pick = pool.get(index % pool.size());
        if (!apply(ctx, pick)) return null;
        // The rotation's "last shown" stamp moves too, so a timed rotation does not
        // immediately overwrite the picture the owner just chose.
        markShown(ctx, source, prefs);
        android.util.Log.d("TvHome", source.label + " next -> " + pick.getName());
        return pick;
    }

    /**
     * Goes back to the picture BEFORE the one the pool would show, and applies it. The index
     * is left pointing just after the picture now on screen, so "next" from here continues
     * forward again — the owner walks the pool back and forth without losing their place.
     *
     * @return the file that was applied, or null when the pool is empty
     */
    static File previous(Context ctx, Source source) {
        List<File> pool = list(ctx, source);
        if (pool.isEmpty()) return null;
        PrefsStore prefs = new PrefsStore(ctx);
        int index = peekIndex(ctx, source);
        int size = pool.size();
        int pick = ((index - 1) % size + size) % size;
        File file = pool.get(pick);
        if (!apply(ctx, file)) return null;
        // Point the index just after the picture now on screen: the next call shows pool[pick+1].
        setIndex(ctx, source, prefs, (pick + 1) % size);
        markShown(ctx, source, prefs);
        android.util.Log.d("TvHome", source.label + " previous -> " + file.getName());
        return file;
    }

    /**
     * Applies one specific picture of the pool — the owner picked it in the preview gallery.
     * The pool index is moved just after that picture and the rotation's "last shown" stamp
     * moves too, so the timed rotation neither jumps away nor overwrites the choice at once.
     *
     * @return true when the wallpaper slot now holds that picture
     */
    static boolean applyPicked(Context ctx, Source source, File picture) {
        List<File> pool = list(ctx, source);
        if (pool.isEmpty() || picture == null || !picture.isFile()) return false;
        int at = pool.indexOf(picture);
        if (at < 0) return false;
        if (!apply(ctx, picture)) return false;
        PrefsStore prefs = new PrefsStore(ctx);
        setIndex(ctx, source, prefs, (at + 1) % pool.size());
        markShown(ctx, source, prefs);
        android.util.Log.d("TvHome", source.label + " picked " + picture.getName());
        return true;
    }

    /**
     * The picture the pool would show next (without applying it) — the panel shows this as the
     * "current picture" of the pool.
     */
    static File current(Context ctx, Source source) {
        List<File> pool = list(ctx, source);
        if (pool.isEmpty()) return null;
        return pool.get(peekIndex(ctx, source) % pool.size());
    }

    /** The index of the next picture, and stores it (the rotation keeps its own counter). */
    private static int advance(Context ctx, Source source, PrefsStore prefs) {
        int i = peekIndex(ctx, source);
        setIndex(ctx, source, prefs, i + 1);
        return i;
    }

    /** The pool's next index, read without touching it. */
    private static int peekIndex(Context ctx, Source source) {
        PrefsStore prefs = new PrefsStore(ctx);
        switch (source) {
            case AERIAL: return prefs.loadAerialIndex();
            case BING: return prefs.loadBingIndex();
            default: return prefs.loadW360Index();
        }
    }

    /** Stores the pool's next index. */
    private static void setIndex(Context ctx, Source source, PrefsStore prefs, int value) {
        switch (source) {
            case AERIAL: prefs.saveAerialIndex(value); break;
            case BING: prefs.saveBingIndex(value); break;
            default: prefs.saveW360Index(value); break;
        }
    }

    /** Pushes the rotation's clock forward, so the timed rotation waits for the next tick. */
    private static void markShown(Context ctx, Source source, PrefsStore prefs) {
        long now = System.currentTimeMillis();
        switch (source) {
            case AERIAL: prefs.saveAerialLastAt(now); break;
            case BING: prefs.saveBingLastAt(now); break;
            default: prefs.saveW360LastAt(now); break;
        }
    }
}
