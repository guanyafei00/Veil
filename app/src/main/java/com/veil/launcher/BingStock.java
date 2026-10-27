package com.veil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Bing's picture-of-the-day archive as a REUSABLE stock source.
 * <p>
 * On the owner's box this was the only wallpaper source that actually worked: bing.com's root
 * certificate is old enough to be in Android 7's CA store (unlike Apple's CDN and 360's HTTPS
 * image host), and it needs no video decoder. The archive hands out eight days per market, and
 * every market picks different pictures — so one pass over a handful of markets yields dozens of
 * unique 4K-capable photographs.
 * <p>
 * It lives in its own class now because it does double duty: the daily wallpaper pulls from it,
 * and it is also the last-resort fallback for the aerial / 360 expansions, so a tap always ends
 * with real pictures instead of a "server unreachable" toast.
 */
final class BingStock {
    private static final String TAG = "TvHome.BingStock";

    /** Markets whose daily pictures differ; eight days each, deduped into one shared stock. */
    static final String[] MARKETS = {
            "zh-CN", "en-US", "ja-JP", "en-GB", "de-DE", "fr-FR", "zh-TW",
            "en-IN", "en-CA", "en-AU", "pt-BR", "it-IT", "ru-RU", "ko-KR", "es-ES",
    };

    /** The archive API: eight pictures per market, starting at index 0. */
    private static final String ARCHIVE =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=";

    /** A market answer with fewer bytes than this is an error page, not a picture. */
    private static final int MIN_JPEG = 20_000;

    /** Wallpaper target size: 1920x1080 is what the box can show and what fits a wallpaper slot. */
    private static final int TARGET_W = 1920, TARGET_H = 1080;

    private BingStock() {
    }

    /**
     * Fills {@code stock} with Bing pictures, skipping the ones already on disk.
     *
     * @param dir       the folder to fill (created when missing)
     * @param prefix    the file-name prefix, e.g. "bing_"
     * @param batch     how many pictures to fetch at most
     * @param markets   how many markets to walk through (the first ones carry the most traffic)
     * @return how many NEW pictures were written
     */
    static int pullInto(Context ctx, File dir, String prefix, int batch, int markets) {
        int added = 0;
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "mkdir failed: " + dir);
                return 0;
            }
            Set<String> names = new HashSet<>();
            File[] have = dir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".jpg"));
            if (have != null) for (File f : have) names.add(f.getName());
            int mktCount = Math.max(1, Math.min(markets, MARKETS.length));
            for (int m = 0; m < mktCount && added < batch; m++) {
                String mkt = MARKETS[m];
                String json;
                try {
                    json = Http.getString(ARCHIVE + mkt, 10_000, 15_000);
                } catch (Exception e) {
                    Log.w(TAG, "market " + mkt + " failed: " + e.getMessage());
                    continue;
                }
                JSONArray images;
                try {
                    images = new JSONObject(json).getJSONArray("images");
                } catch (Exception bad) {
                    continue;   // a market answering an error page contributes nothing
                }
                for (int i = 0; i < images.length() && added < batch; i++) {
                    JSONObject img = images.optJSONObject(i);
                    if (img == null) continue;
                    String urlbase = img.optString("urlbase", "");
                    String path = img.optString("url", "");
                    if (urlbase.isEmpty() || path.isEmpty()) continue;
                    String name = prefix
                            + urlbase.substring(urlbase.lastIndexOf('/') + 1).replace('?', '_')
                            + ".jpg";
                    if (names.contains(name)) continue;
                    byte[] jpg = fetchPicture(img, path);
                    if (jpg == null || jpg.length < MIN_JPEG) continue;
                    Bitmap bmp = decodeScaled(jpg);
                    if (bmp == null) continue;
                    try (FileOutputStream fo = new FileOutputStream(new File(dir, name))) {
                        bmp.compress(Bitmap.CompressFormat.JPEG, 88, fo);
                    } finally {
                        bmp.recycle();
                    }
                    names.add(name);
                    added++;
                }
            }
            if (added > 0) Log.d(TAG, "bing stock: +" + added + " into " + dir.getName());
            else Log.w(TAG, "bing stock: nothing usable");
        } catch (Exception e) {
            Log.w(TAG, "bing stock failed: " + e);
        }
        return added;
    }

    /**
     * Downloads one picture. The archive's default URL is 1920x1080, but the same picture
     * usually exists as a UHD (3840x2160) cut — try that first and fall back to the plain URL.
     */
    private static byte[] fetchPicture(JSONObject img, String plainUrl) {
        String base = img.optString("urlbase", "");
        if (!base.isEmpty()) {
            try {
                byte[] uhd = Http.getBytes("https://www.bing.com" + base + "_UHD.jpg",
                        10_000, 20_000, null, 24 * 1024 * 1024);
                if (uhd != null && uhd.length >= MIN_JPEG) return uhd;
            } catch (Exception e) {
                // No UHD cut for this picture (or the fetch failed): the plain one is fine.
            }
        }
        String url = plainUrl.startsWith("http") ? plainUrl : "https://www.bing.com" + plainUrl;
        try {
            return Http.getBytes(url, 10_000, 20_000, null, 24 * 1024 * 1024);
        } catch (Exception e) {
            Log.w(TAG, "picture failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Decodes the picture and scales it down to the wallpaper size in ONE pass.
     * <p>
     * The UHD cut is 3840x2160 = 8.3 M pixels = 33 MB as ARGB_8888 — on the box's small heap
     * that is another OutOfMemoryError waiting to happen, and the old code decoded the full
     * bitmap before resizing it. {@code inSampleSize} shrinks during the decode instead, so the
     * peak footprint stays at the scaled size.
     */
    private static Bitmap decodeScaled(byte[] jpg) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= TARGET_W && bounds.outHeight / (sample * 2) >= TARGET_H) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, opts);
        if (bmp == null) return null;
        if (bmp.getWidth() == TARGET_W && bmp.getHeight() == TARGET_H) return bmp;
        Bitmap scaled = Bitmap.createScaledBitmap(bmp, TARGET_W, TARGET_H, true);
        if (scaled != bmp) bmp.recycle();
        return scaled;
    }
}
