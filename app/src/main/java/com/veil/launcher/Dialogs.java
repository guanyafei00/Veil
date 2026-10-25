package com.veil.launcher;

import android.app.AlertDialog;
import android.graphics.drawable.ColorDrawable;
import android.view.WindowManager;

/**
 * The N1's ROM restyles the framework's dialog themes and ignores our style items, which left
 * every menu see-through on a bright wallpaper. So the finished dialog's window is styled
 * directly — a dark panel plus a dim behind — which no theme override can take back.
 */
final class Dialogs {

    private Dialogs() {
    }

    /** Forces a dark opaque panel and a 50% dim onto the dialog window, then shows it. */
    static void show(AlertDialog d) {
        style(d);
        d.show();
    }

    /** Forces a dark opaque panel and a 50% dim onto a shown-or-not dialog window. */
    static void style(android.app.Dialog d) {
        try {
            WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.dimAmount = 0.5f;
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            d.getWindow().setAttributes(lp);
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0xF22A2A31));
        } catch (Exception e) {
            // A dialog with no window (already dismissed) simply keeps the theme default.
            android.util.Log.d("TvHome", "dialog window styling skipped: " + e);
        }
    }
}
