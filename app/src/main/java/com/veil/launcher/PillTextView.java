package com.veil.launcher;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.TextView;

/**
 * A frosted pill (top-bar buttons, gallery buttons, dialog actions) with one hard guarantee:
 * its outline is always a true capsule, radius = half the view's height.
 *
 * <p>The pill's XML background used to be a layer-list of two rounded shapes. Some TV ROMs
 * (TCL mt5879 in particular) ignore the corners of a layer-list item that carries a bottom
 * inset, so the second layer painted a square dark plate with straight corners behind the
 * rounded rim — visible in the owner's photos as a rectangular block around the pill. Fixing
 * the radius in XML never survived those ROMs. Clipping the whole view to a round-rect
 * outline is renderer-independent: every layer, stroke and sheen is cut to the same arc.
 */
public final class PillTextView extends TextView {

    public PillTextView(Context context) {
        super(context);
        init();
    }

    public PillTextView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public PillTextView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int h = view.getHeight();
                // Half the height gives a stadium shape, matching every pill background and
                // staying correct if a style changes the button's height later.
                outline.setRoundRect(0, 0, view.getWidth(), h, h / 2f);
            }
        });
        setClipToOutline(true);
    }
}
