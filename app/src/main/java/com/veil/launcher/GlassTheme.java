package com.veil.launcher;

import java.util.Calendar;

/**
 * Day/night mood for the liquid-glass chrome. The dock frost ships in two looks:
 * <ul>
 *   <li><b>Night (default)</b> — smoked glass: the blurred wallpaper darkened so the
 *       white labels and icons stay readable on a darkened room screen.</li>
 *   <li><b>Day</b> — milky bright frost: a white veil instead of the dark scrim, so the
 *       band reads as sunlit glass rather than a black slab.</li>
 * </ul>
 * Panels and dialogs keep the dark material in BOTH moods — their titles are white and
 * would vanish on a light panel. The day/night cut is a simple clock rule (7:00–18:00 =
 * day), predictable and dependency-free; sunrise/sunset precision is not worth a location
 * permission for wallpaper chrome.
 */
final class GlassTheme {

    private GlassTheme() { }

    /** 7:00–18:00 counts as day; anything else is night glass. */
    static boolean isNight() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return h >= 18 || h < 7;
    }
}
