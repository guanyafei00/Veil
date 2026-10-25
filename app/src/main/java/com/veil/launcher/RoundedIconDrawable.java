package com.veil.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

/**
 * An icon whose own pixels carry the corner rounding (the owner's radius dial). The old approach
 * clipped the ImageView's frame, which only shows when the picture actually reaches the corners —
 * a padded, letterboxed or small icon kept its square edges. Here the shader fills a round-rect
 * path, so the rounding is part of the picture and every icon shows it, at any radius.
 * <p>
 * Built once per tile on the background scan thread and drawn like any drawable afterwards;
 * a source that cannot be rasterised is returned unchanged, so a tile never loses its icon.
 */
final class RoundedIconDrawable extends Drawable {

    private final Bitmap bmp;
    private final float frac;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private BitmapShader shader;

    private RoundedIconDrawable(Bitmap bmp, float frac) {
        this.bmp = bmp;
        this.frac = frac;
    }

    /**
     * Wraps a drawable into a rounded copy, or returns it unchanged when the radius is off or the
     * picture cannot be rasterised. {@code capPx} bounds the raster size (the PM's own bitmaps
     * are reused as they are; only odd drawable types are redrawn).
     */
    static Drawable wrap(Drawable src, float frac, int capPx) {
        if (src == null || frac <= 0f) return src;
        try {
            Bitmap bmp = bitmapOf(src, capPx);
            return bmp == null ? src : new RoundedIconDrawable(bmp, frac);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("TvHome", "icon rounding skipped: " + e);
            return src;
        }
    }

    private static Bitmap bitmapOf(Drawable d, int cap) {
        if (d instanceof BitmapDrawable) {
            Bitmap b = ((BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled() && b.getWidth() <= cap && b.getHeight() <= cap) return b;
        }
        int w = Math.max(1, Math.min(cap, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : cap));
        int h = Math.max(1, Math.min(cap, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : cap));
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        return out;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        rect.set(bounds);
        // Fill the view the way centerCrop does: one scale, centred overflow.
        float scale = Math.max(bounds.width() / (float) bmp.getWidth(),
                bounds.height() / (float) bmp.getHeight());
        float dw = bmp.getWidth() * scale, dh = bmp.getHeight() * scale;
        if (shader == null) {
            shader = new BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        Matrix m = new Matrix();
        m.setScale(scale, scale);
        m.postTranslate(bounds.left + (bounds.width() - dw) / 2f,
                bounds.top + (bounds.height() - dh) / 2f);
        shader.setLocalMatrix(m);
        paint.setShader(shader);
        float r = frac * Math.min(rect.width(), rect.height());
        path.reset();
        path.addRoundRect(rect, r, r, Path.Direction.CW);
    }

    @Override
    public void draw(Canvas canvas) {
        canvas.drawPath(path, paint);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return paint.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        paint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
