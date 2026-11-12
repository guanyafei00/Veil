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

    /** The white label's own contrast, so the glass behind it can stay genuinely translucent. */
    private float shadowRadius;

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
        shadowRadius = 2.4f * getResources().getDisplayMetrics().density;
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
        applyTextShadow();
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        applyTextShadow();
    }

    /**
     * A soft dark halo under the white label is what keeps it readable on snow / bright sky
     * frames. Doing it on the text (instead of darkening the whole pill) preserves the liquid
     * glass: the wallpaper still shows through the material. Focused pills flip to dark text on
     * white and must not carry a dark halo.
     */
    private void applyTextShadow() {
        if (isFocused()) {
            setShadowLayer(0f, 0f, 0f, 0);
        } else {
            setShadowLayer(shadowRadius, 0f, shadowRadius * 0.35f, 0xC0000000);
        }
    }
}
