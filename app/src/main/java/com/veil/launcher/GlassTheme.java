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
        // Called from the dock's per-frame draw (the breathing wallpaper invalidates the
        // desktop continuously). Calendar.getInstance() allocates on EVERY call — constant
        // GC churn read as jank on 1–2 GB boxes — so the hour is cached for 30 s instead.
        long now = System.currentTimeMillis();
        if (now - checkedAt > 30_000) {
            checkedAt = now;
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            cachedNight = h >= 18 || h < 7;
        }
        return cachedNight;
    }

    private static long checkedAt;
    private static boolean cachedNight = true;
}
