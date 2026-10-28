package com.veil.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Random;

/**
 * Draws the tvOS style frosted band behind the first (dock) row and adds a gap below it.
 * <p>
 * The band is a vertical gradient plus a fine white grain at low alpha — the "frost" of
 * AT4K's blurred dock, done without a real blur pass a 1 GB box cannot afford. The grain bitmap
 * is generated once in code (no asset, no copyright question) and tiled with REPEAT.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    /** Corner rounding as a fraction of the band's shorter side — the owner's icon radius dial. */
    private final float radiusFrac;
    /**
     * -1 = the default frosted glass; otherwise a solid ARGB the owner picked in the settings panel.
     * The sentinel MUST be -1 (not 0): the "follow default" pref is stored as -1, and 0x00000000
     * (a real "invisible band" pick) must stay distinct from it. -1 as a paint colour is opaque
     * white, which once painted the whole band pure white — the "white dock" bug.
     */
    private final int tint;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    /** Rebuilt when the band's height changes, so the gradient always matches the drawn rect. */
    private LinearGradient gradient;
    private float gradTop, gradBottom;
    /** The wallpaper as shown (null = plain background): the band frost crops from it. */
    private Bitmap wallpaper;
    /**
     * The dock-region glass for the screen position it was rendered at (1/3 resolution).
     * REAL-TIME: every frame whose position moved re-renders the band's own wallpaper area.
     * Continuous movement produces continuous glass content — no settle-time jump, no stale
     * print, and it follows the wallpaper's breathing zoom where a frozen sheet could not.
     */
    private Bitmap frost;
    private int frostX = Integer.MAX_VALUE, frostY = Integer.MAX_VALUE;
    private int frostW = -1, frostH = -1;
    /** The mood the cached frost was rendered in: a clock flip must re-render (day ↔ night). */
    private boolean frostNight = GlassTheme.isNight();
    /** The dock frosted glass switch (owner's panel toggle): off falls back to the dark gradient. */
    private boolean frostEnabled = true;
    /** The last frost render failed (OOM): stop retrying per frame until the inputs change. */
    private boolean frostFailed;
    private final Paint bandBitmap = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    /** Called when the wallpaper is (re)decoded: the next draw re-renders the frost. */
    void setWallpaperBitmap(Bitmap bmp) {
        wallpaper = bmp;
        frost = null;
        frostFailed = false;
    }

    /** The dock-bar frosted glass on/off; off drops the cached crop so the gradient shows. */
    void setFrostEnabled(boolean on) {
        frostEnabled = on;
        if (!on) frost = null;
    }

    /**
     * Opacity of the whole band (0…1), driven by the row's fade into the top-bar glass: the band
     * is an ItemDecoration, so it does NOT follow the tiles' view alpha — without this the frosted
     * band stayed solid on the glass shelf while the icons on it had already dissolved (the
     * owner's "icon pressed on the frosted glass until the very top" report).
     */
    private float bandAlpha = 1f;

    void setBandAlpha(float alpha) {
        bandAlpha = Math.max(0f, Math.min(1f, alpha));
    }

    /** Pushes the band opacity into every paint used by onDraw (after any setColor/setShader). */
    private void applyBandAlpha() {
        int a = (int) (255 * bandAlpha);
        band.setAlpha(a);
        grain.setAlpha(a);
        bandBitmap.setAlpha(a);
    }

    /** Called by the desktop on scroll-state changes; the per-frame region render needs no hint. */
    void setScrolling(boolean on) {
        // Real-time region rendering tracks position by itself: nothing to do here.
    }

    DockDecoration(int dockSize, float density) {
        this(dockSize, density, 0, 0f);
    }

    DockDecoration(int dockSize, float density, int tint) {
        this(dockSize, density, tint, 0f);
    }

    DockDecoration(int dockSize, float density, int tint, float radiusFrac) {
        this.dockSize = dockSize;
        this.density = density;
        this.tint = tint;
        this.radiusFrac = radiusFrac;
        // A very fine white noise tile: the texture inside the "glass".
        int side = 48;
        Bitmap noise = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        int[] px = new int[side * side];
        Random rnd = new Random(20261019);
        for (int i = 0; i < px.length; i++) {
            int a = 8 + rnd.nextInt(18);
            px[i] = (a << 24) | 0x00FFFFFF;   // white grain, small random alpha
        }
        noise.setPixels(px, 0, side, 0, 0, side, side);
        grain.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        // No edge hairline any more: the 1 px bright stroke read as a white ring around the
        // whole dock (the owner's report). The frost's own brightness is enough of an edge.
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
            View card = cardOf(parent, child);
            if (card == null) continue;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        if (bandAlpha <= 0.01f) return;   // the row has dissolved into the glass: nothing to draw
        float pad = 14 * density;
        rect.inset(-pad, -pad);
        // The band's corners follow the owner's icon radius dial: same fraction of the shape's
        // shorter side, so the dock reads as one piece with the tiles sitting on it.
        float radius = radiusFrac * Math.min(rect.width(), rect.height());
        if (tint != -1) {
            // The owner's pick: a solid colour instead of the gradient; the grain and hairline stay.
            band.setColor(tint);
        } else if (frostEnabled && wallpaper != null) {
            // Frosted wallpaper (inspired by Tigris): the REAL wallpaper behind the band, blurred.
            // The band's own region is re-rendered whenever its screen position moved (a band
            // area at 1/3 resolution blurs in a couple of ms — cheap enough per frame), so the
            // glass always shows the wallpaper currently under the dock: real-time, continuous
            // while scrolling (no settle jump), and honest to the breathing wallpaper (no
            // frozen sheet). An idle dock re-renders nothing (position unchanged).
            if (!frostFailed) {
                int[] loc = new int[2];
                parent.getLocationOnScreen(loc);
                int w = Math.round(rect.width()), h = Math.round(rect.height());
                // 2-px quantisation: sub-pixel scroll jitter must not re-trigger the blur.
                int gx = (loc[0] + Math.round(rect.left)) & ~1;
                int gy = (loc[1] + Math.round(rect.top)) & ~1;
                if (frost == null || w != frostW || h != frostH || gx != frostX || gy != frostY
                        || GlassTheme.isNight() != frostNight) {
                    android.util.DisplayMetrics dm = parent.getResources().getDisplayMetrics();
                    Bitmap nf = FrostGlass.renderRegion(wallpaper,
                            dm.widthPixels, dm.heightPixels, gx, gy, w, h);
                    if (nf != null) {
                        if (frost != null) frost.recycle();
                        frost = nf;
                        frostX = gx; frostY = gy; frostW = w; frostH = h;
                        frostNight = GlassTheme.isNight();
                    } else {
                        // A failed render (OOM) used to retry every frame: frost ↔ dark gradient
                        // alternation read as flicker. Stick with the fallback until the
                        // wallpaper changes.
                        frostFailed = true;
                    }
                }
            }
            if (frost != null) {
                applyBandAlpha();
                android.graphics.BitmapShader sh = new android.graphics.BitmapShader(
                        frost, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.setScale(3f, 3f);   // the glass is 1/3 resolution: stretch it back over the band
                sh.setLocalMatrix(m);
                bandBitmap.setShader(sh);
                c.drawRoundRect(rect, radius, radius, bandBitmap);
                c.drawRoundRect(rect, radius, radius, grain);
                return;
            }
            // frost failed (huge wallpaper, OOM): fall through to the painted gradient
            band.setColor(0x8C1E1E20);
        } else {
            // Glass: a dark vertical gradient (brighter at the top, like lit plastic) plus the grain.
            // ~55% alpha, not the old ~90%: a wallpaper (v2.5+) must stay visible through the band.
            if (gradient == null || Math.abs(gradTop - rect.top) > 1 || Math.abs(gradBottom - rect.bottom) > 1) {
                gradTop = rect.top;
                gradBottom = rect.bottom;
                gradient = new LinearGradient(0, rect.top, 0, rect.bottom,
                        new int[]{0x8C1E1E20, 0x8C151517, 0x8C0D0D0F},
                        null, Shader.TileMode.CLAMP);
                band.setShader(gradient);
            }
        }
        applyBandAlpha();   // after any setColor/setShader: those reset the paint's alpha
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, grain);
    }

    /**
     * The tile's card view. Straight from the child's ViewHolder (multi-model review P1: the old
     * code did a findViewById per child per frame); the lookup is only the fallback for a child
     * that is not one of our tiles.
     */
    private static View cardOf(RecyclerView parent, View child) {
        RecyclerView.ViewHolder vh = parent.getChildViewHolder(child);
        if (vh instanceof AppAdapter.Holder) return ((AppAdapter.Holder) vh).card;
        return child.findViewById(R.id.card);
    }
}
