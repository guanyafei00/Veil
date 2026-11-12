package com.veil.launcher;

import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * One place that builds the launcher's translucent "liquid-glass" panels so the settings panel and
 * the long-press tile menu read as the same material. Callers pick a preset (SETTINGS rides over the
 * live desktop at half brightness with a real backdrop blur; MENU is the foreground action sheet
 * over a wallpaper) and the helper returns the matching background drawable plus the window blur
 * radius. The two still differ in depth and corner treatment — that is intentional, not drift.
 */
final class GlassSurface {

    /** How a panel reads against its surroundings. */
    enum Style {
        /** The half-screen settings sheet: left-only corners, window blur on API 31+.
         *  Translucent glass, not a painted slab — the wallpaper's colour must still read through
         *  the sheet. 82% -> 72% keeps long Chinese rows legible while leaving the material
         *  visibly glassy; the 110px backdrop blur does the rest on API 31+. */
        SETTINGS(0xD1202228, 0xB814161B, 0x66FFFFFF, 28f, 22f, 110),
        /** Dialogs and the long-press action sheet: one step deeper, all corners. */
        MENU(0xE01E2026, 0xC8121419, 0x66FFFFFF, 22f, 22f, 0);

        final int topColor, bottomColor, strokeColor;
        final float cornerDp, blurRadius;

        Style(int topColor, int bottomColor, int strokeColor, float cornerDp, float unused, int blurRadius) {
            this.topColor = topColor;
            this.bottomColor = bottomColor;
            this.strokeColor = strokeColor;
            this.cornerDp = cornerDp;
            this.blurRadius = blurRadius;
        }
    }

    private GlassSurface() {
    }

    /**
     * Builds the panel background for {@code style}. {@code leftOnly} keeps the rounded corners on
     * the left edge and squares the right edge (the settings sheet hugs the screen's right side);
     * pass false for a fully rounded panel like the long-press menu. The result is a two-layer
     * drawable: the smoked gradient fill with a light rim, plus a top specular sheen so both panels
     * read as the same liquid-glass material.
     */
    static android.graphics.drawable.LayerDrawable background(Style style, float density, boolean leftOnly) {
        float r = style.cornerDp * density;
        float[] radii = leftOnly
                ? new float[]{r, r, 0, 0, 0, 0, r, r}
                : new float[]{r, r, r, r, r, r, r, r};

        GradientDrawable base = new GradientDrawable();
        base.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        base.setColors(new int[]{style.topColor, style.bottomColor});
        base.setStroke(1, style.strokeColor);
        base.setCornerRadii(radii);

        // Top specular sheen, inset from the bottom so it fades out before the lower edge.
        GradientDrawable sheen = new GradientDrawable();
        sheen.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
        // Specular highlight: modest enough not to wash out text, bright enough to read as glass.
        sheen.setColors(new int[]{0x1AFFFFFF, 0x00FFFFFF});
        // Round only the top corners of the sheen, or it would poke out of the panel's rounded top.
        float[] sheenRadii = leftOnly
                ? new float[]{r, r, 0, 0, 0, 0, 0, 0}
                : new float[]{r, r, r, r, r, r, r, r};
        sheen.setCornerRadii(sheenRadii);

        android.graphics.drawable.LayerDrawable layers =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{base, sheen});
        // Keep the sheen in the top portion of the panel.
        layers.setLayerInsetBottom(1, (int) (72 * density));
        return layers;
    }

    /** Applies the backdrop blur for {@code style} when the platform supports it. */
    static void applyWindowBlur(Window window, Style style) {
        if (style.blurRadius > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius((int) style.blurRadius);
        }
    }
}
