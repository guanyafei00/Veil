package com.veil.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * Frosted glass over the wallpaper (inspired by Tigris): instead of painting a fake gradient, crop the
 * wallpaper behind the dock band, blur it (two box-blur passes on a quarter-size copy — no GPU,
 * no RenderScript, safe on a 1 GB box) and hand it back. The band is drawn once per wallpaper,
 * cached, and just pasted afterwards, so scrolling costs nothing.
 */
final class FrostGlass {

    private FrostGlass() {
    }

    /**
     * Renders the frosted band for {@code band} (screen coordinates). The wallpaper was shown with
     * CENTER_CROP on a {@code viewW x viewH} screen, so the crop maps the band through the same
     * transform. Returns a bitmap the size of the band; draw it scaled to the band rect.
     */
    static Bitmap render(Bitmap wallpaper, android.graphics.RectF band, int viewW, int viewH) {
        if (wallpaper == null || band == null || band.isEmpty() || viewW <= 0 || viewH <= 0) return null;
        try {
            int wallW = wallpaper.getWidth(), wallH = wallpaper.getHeight();
            // The wallpaper fills the screen with CENTER_CROP: one scale, centered overflow.
            float scale = Math.max(viewW / (float) wallW, viewH / (float) wallH);
            float drawW = wallW * scale, drawH = wallH * scale;
            float offX = (drawW - viewW) / 2f, offY = (drawH - viewH) / 2f;

            // Crop the band's region, at half resolution to keep the blur cheap.
            int srcL = Math.max(0, Math.round((band.left + offX) / scale));
            int srcT = Math.max(0, Math.round((band.top + offY) / scale));
            int srcR = Math.min(wallW, Math.round((band.right + offX) / scale));
            int srcB = Math.min(wallH, Math.round((band.bottom + offY) / scale));
            int cw = srcR - srcL, ch = srcB - srcT;
            if (cw < 4 || ch < 4) return null;

            int halfW = Math.max(2, cw / 2), halfH = Math.max(2, ch / 2);
            Bitmap out = finishGlass(cropScaled(wallpaper, srcL, srcT, srcR, srcB, halfW, halfH), 6, 4);
            return out;
        } catch (Exception | OutOfMemoryError e) {
            // A wallpaper too big to crop (or OOM): the caller falls back to the painted gradient.
            android.util.Log.w("TvHome", "frost render failed: " + e);
            return null;
        }
    }

    /**
     * Renders the frosted glass for a dock REGION at screen position (x, y), size w x h —
     * REAL-TIME per frame (the owner's ask: the glass must always show the wallpaper right
     * under the dock, in every position). The region is small, so this is cheap: a 1/3-scale
     * crop of just the band's wallpaper area, blurred in two passes. Continuous movement then
     * produces continuous content — no settle-time brightness jump (v1's flicker), no stale
     * print (v2's), no frozen sheet ignoring the wallpaper's own breathing zoom (v3's).
     */
    static Bitmap renderRegion(Bitmap wallpaper, int viewW, int viewH, int x, int y, int w, int h) {
        if (wallpaper == null || w <= 0 || h <= 0 || viewW <= 0 || viewH <= 0) return null;
        try {
            int wallW = wallpaper.getWidth(), wallH = wallpaper.getHeight();
            float scale = Math.max(viewW / (float) wallW, viewH / (float) wallH);
            float offX = (wallW * scale - viewW) / 2f, offY = (wallH * scale - viewH) / 2f;
            int srcL = Math.max(0, Math.round((x + offX) / scale));
            int srcT = Math.max(0, Math.round((y + offY) / scale));
            int srcR = Math.min(wallW, Math.round((x + w + offX) / scale));
            int srcB = Math.min(wallH, Math.round((y + h + offY) / scale));
            int cw = srcR - srcL, ch = srcB - srcT;
            if (cw < 4 || ch < 4) return null;
            int outW = Math.max(2, w / 3), outH = Math.max(2, h / 3);
            Bitmap cropped = cropScaled(wallpaper, srcL, srcT, srcR, srcB, outW, outH);
            // Blur radii scaled for the 1/3 resolution (5+3 here reads like 6+4 at half res).
            return finishGlass(cropped, 5, 3);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("TvHome", "frost region render failed: " + e);
            return null;
        }
    }

    /** CENTER_CROP-maps wallpaper region [srcL,T,R,B] into a halfW x halfH RGB_565 bitmap. */
    private static Bitmap cropScaled(Bitmap wallpaper, int srcL, int srcT, int srcR, int srcB,
                                     int halfW, int halfH) {
        Bitmap cropped = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.RGB_565);
        Canvas c = new Canvas(cropped);
        c.drawBitmap(wallpaper,
                new Rect(srcL, srcT, srcR, srcB),
                new Rect(0, 0, halfW, halfH), null);
        return cropped;
    }

    /** Two box-blur passes + the readability darkening; consumes {@code cropped}. */
    private static Bitmap finishGlass(Bitmap cropped, int radius1, int radius2) {
        int halfW = cropped.getWidth(), halfH = cropped.getHeight();
        int[] px = new int[halfW * halfH];
        cropped.getPixels(px, 0, halfW, 0, 0, halfW, halfH);
        px = boxBlur(px, halfW, halfH, radius1);
        px = boxBlur(px, halfW, halfH, radius2);
        cropped.setPixels(px, 0, halfW, 0, 0, halfW, halfH);

        // Darken a little so white text on top stays readable, whatever the wallpaper is.
        Bitmap out = Bitmap.createBitmap(halfW, halfH, Bitmap.Config.RGB_565);
        Canvas oc = new Canvas(out);
        oc.drawBitmap(cropped, 0, 0, null);
        oc.drawColor(0x3A000000);
        cropped.recycle();
        return out;
    }

    /** In-place separable box blur (sliding window, O(n) per pass). */
    private static int[] boxBlur(int[] px, int w, int h, int radius) {
        if (w < 3 || h < 3) return px;
        int[] tmp = new int[px.length];
        int win = radius * 2 + 1;
        // horizontal
        for (int y = 0; y < h; y++) {
            int row = y * w;
            long sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int x = Math.max(0, Math.min(w - 1, i));
                int p = px[row + x];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                tmp[row + x] = 0xFF000000 | ((int) (sr / win) << 16) | ((int) (sg / win) << 8) | (int) (sb / win);
                int addX = Math.min(w - 1, x + radius + 1);
                int subX = Math.max(0, x - radius);
                int pa = px[row + addX], ps = px[row + subX];
                sr += ((pa >> 16) & 0xFF) - ((ps >> 16) & 0xFF);
                sg += ((pa >> 8) & 0xFF) - ((ps >> 8) & 0xFF);
                sb += (pa & 0xFF) - (ps & 0xFF);
            }
        }
        // vertical
        for (int x = 0; x < w; x++) {
            long sr = 0, sg = 0, sb = 0;
            for (int i = -radius; i <= radius; i++) {
                int y = Math.max(0, Math.min(h - 1, i));
                int p = tmp[y * w + x];
                sr += (p >> 16) & 0xFF;
                sg += (p >> 8) & 0xFF;
                sb += p & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                px[y * w + x] = 0xFF000000 | ((int) (sr / win) << 16) | ((int) (sg / win) << 8) | (int) (sb / win);
                int addY = Math.min(h - 1, y + radius + 1);
                int subY = Math.max(0, y - radius);
                int pa = tmp[addY * w + x], ps = tmp[subY * w + x];
                sr += ((pa >> 16) & 0xFF) - ((ps >> 16) & 0xFF);
                sg += ((pa >> 8) & 0xFF) - ((ps >> 8) & 0xFF);
                sb += (pa & 0xFF) - (ps & 0xFF);
            }
        }
        return px;
    }
}
