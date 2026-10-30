package com.veil.launcher;

/**
 * Process-local snapshot of what the desktop video background is showing right now.
 * The launcher (MainActivity) and the screensaver (VideoDream) run in the same process, so a
 * static holder is enough for the screensaver to pick up the exact clip — and roughly where —
 * the desktop left off, instead of starting a fresh random one.
 */
final class WallpaperState {

    private static String currentUrl;
    private static long currentPositionMs;
    private static long updatedAt;

    private WallpaperState() {
    }

    /** Called by the desktop player whenever a clip starts or its playback position advances. */
    static void publish(String url, long positionMs) {
        currentUrl = url;
        currentPositionMs = positionMs;
        updatedAt = System.currentTimeMillis();
    }

    /** Called when the desktop player stops or falls back to the still. */
    static void clear() {
        currentUrl = null;
        currentPositionMs = 0;
        updatedAt = 0;
    }

    static String currentUrl() {
        return currentUrl;
    }

    static long currentPositionMs() {
        return currentPositionMs;
    }

    /** How long ago (ms) the desktop last reported a playing clip; -1 when nothing is known. */
    static long ageMs() {
        return updatedAt == 0 ? -1 : System.currentTimeMillis() - updatedAt;
    }
}
