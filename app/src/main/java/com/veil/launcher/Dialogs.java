package com.veil.launcher;

import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.view.WindowManager;

/**
 * Every dialog in the launcher flows through here, so this is the one place that decides what a
 * popup looks like. The N1's ROM restyles the framework's dialog themes and ignores our style
 * items, which left every menu see-through on a bright wallpaper — so the finished dialog's window
 * is styled directly, which no theme override can take back. The window now wears the launcher's
 * liquid-glass material (the same GlassSurface preset as the long-press menu), so third/fourth
 * level popups match the desktop instead of reading as a stock dark rectangle.
 */
final class Dialogs {

    private Dialogs() {
    }

    /** Styles the dialog window with the glass material, then shows it. */
    static void show(AlertDialog d) {
        style(d);
        d.show();
    }

    /** Styles a shown-or-not dialog window: glass panel + a soft dim behind. */
    static void style(android.app.Dialog d) {
        try {
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.dimAmount = 0.45f;
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            d.getWindow().setAttributes(lp);
            float density = d.getContext().getResources().getDisplayMetrics().density;
            Drawable glass = GlassSurface.background(GlassSurface.Style.MENU, density, false);
            // A little padding so the rounded glass edges never clip the content.
            int pad = (int) (2 * density);
            d.getWindow().getDecorView().setPadding(pad, pad, pad, pad);
            d.getWindow().setBackgroundDrawable(glass);
        } catch (Exception e) {
            // A dialog with no window (already dismissed) simply keeps the theme default.
            android.util.Log.d("Veil", "dialog window styling skipped: " + e);
        }
    }
}
