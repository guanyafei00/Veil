package com.veil.launcher;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.Drawable;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

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
            lp.dimAmount = 0.55f;
            lp.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            d.getWindow().setAttributes(lp);
            float density = d.getContext().getResources().getDisplayMetrics().density;
            Drawable glass = GlassSurface.background(GlassSurface.Style.MENU, density, false);
            // A little padding so the rounded glass edges never clip the content.
            int pad = (int) (2 * density);
            d.getWindow().getDecorView().setPadding(pad, pad, pad, pad);
            d.getWindow().setBackgroundDrawable(glass);
            // Some box ROMs re-style the framework dialog theme to a light/grey text colour,
            // so a long Chinese release note can land on the glass as barely-readable grey.
            // Force the message/title colours after the decor view is laid out.
            d.getWindow().getDecorView().post(() -> {
                try {
                    TextView message = (TextView) d.findViewById(android.R.id.message);
                    if (message != null) {
                        message.setTextColor(0xFFF2F2F4);
                        message.setLineSpacing(0, 1.18f);
                    }
                    int titleId = d.getContext().getResources()
                            .getIdentifier("alertTitle", "id", "android");
                    if (titleId != 0) {
                        TextView title = (TextView) d.findViewById(titleId);
                        if (title != null) title.setTextColor(0xFFFFFFFF);
                    }
                    // Box ROMs also grey out the button-bar labels: "Cancel" / "Update now"
                    // were almost invisible on the near-black glass. Force them back to white.
                    if (d instanceof AlertDialog) {
                        AlertDialog ad = (AlertDialog) d;
                        int[] which = {DialogInterface.BUTTON_NEGATIVE,
                                DialogInterface.BUTTON_NEUTRAL,
                                DialogInterface.BUTTON_POSITIVE};
                        for (int w : which) {
                            Button b = ad.getButton(w);
                            if (b != null) b.setTextColor(0xFFF2F2F4);
                        }
                    }
                } catch (Exception ignored) {
                    // A dialog dismissed before the post runs simply keeps the theme colour.
                }
            });
        } catch (Exception e) {
            // A dialog with no window (already dismissed) simply keeps the theme default.
            android.util.Log.d("Veil", "dialog window styling skipped: " + e);
        }
    }
}
