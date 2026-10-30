package com.veil.launcher;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Desktop live video background: a full-screen moving video behind the launcher UI — the
 * "航拍视频真播" the owner asked for. The clip is streamed straight from the CDN; nothing is
 * written to the box's flash, so the tiny built-in storage is never touched. The same HTTPS link
 * the screensaver uses is tried first, with an HTTP variant as a fallback, and a quiet return to
 * the static still if the network or the codec fails — the desktop is never dead or black.
 * <p>
 * Continuity: while a clip plays we publish its URL and current position to {@link WallpaperState}
 * so the screensaver can pick up the exact same clip and seek near where the desktop left off.
 * <p>
 * Threading: MediaPlayer callbacks and view tweaks run on the main thread.
 */
final class WallpaperVideoPlayer implements SurfaceHolder.Callback {

    /** Give a clip this long to become playable before falling back to the still. */
    private static final long PREPARE_TIMEOUT_MS = 8_000L;
    /** How long to wait before retrying after a failure (stream or decode). */
    private static final long RETRY_MS = 30_000L;
    /** How often we sample the playback position to keep WallpaperState fresh. */
    private static final long POSITION_TICK_MS = 5_000L;

    /** Notified when a frame reaches the screen (hide the still) or we fall back (show the still). */
    interface StateListener {
        void onPlayingChanged(boolean playing);
    }

    private static final class Clip {
        final String name, url;

        Clip(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final android.content.Context ctx;
    private final SurfaceView surface;
    private final StateListener listener;
    private final List<Clip> clips = new ArrayList<>();

    private int lastPick = -1;
    private Clip current;                 // the clip playing (or last played)
    private SurfaceHolder holder;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean stopped = true;       // created stopped; start() flips it
    private boolean playing;
    private final Runnable prepareTimeout = () -> failToStill("prepare timeout");

    WallpaperVideoPlayer(android.content.Context ctx, SurfaceView surface, StateListener listener) {
        this.ctx = ctx;
        this.surface = surface;
        this.listener = listener;
        holder = surface.getHolder();
        holder.addCallback(this);
        loadPlaylist();
    }

    /** Reads the bundled playlist; a broken one just means no video (the still stays). */
    private void loadPlaylist() {
        try (InputStream in = ctx.getAssets().open("videos.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray a = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (!url.isEmpty()) clips.add(new Clip(o.optString("name", ""), url));
            }
        } catch (Exception e) {
            // Missing or broken playlist: the desktop keeps its static still instead of crashing.
            android.util.Log.w("Veil", "video playlist unreadable: " + e);
        }
    }

    /** Whether we are in active mode (attempting playback), so callers don't restart redundantly. */
    boolean isActive() {
        return !stopped;
    }

    /** Begin (or resume) playback. No-op when there are no clips or already active. */
    void start() {
        // INFO on purpose: some OEM builds (TCL) drop Debug-level app logs, and this is the
        // exact decision tree we need to see when the video "silently" stays black.
        android.util.Log.i("Veil", "video start(): clips=" + clips.size()
                + " surfaceReady=" + surfaceReady + " stopped=" + stopped + " playing=" + playing);
        if (clips.isEmpty()) return;
        boolean wasStopped = stopped;
        stopped = false;
        if (!surfaceReady) {
            // The surface is parked (GONE) while idle; showing it triggers surfaceCreated,
            // which calls back into playRandom().
            surface.setVisibility(android.view.View.VISIBLE);
            return;
        }
        if (!playing || wasStopped) playRandom();
    }

    /** Pause playback and reveal the static still (desktop away, or rotation turned off). */
    void pause() {
        stopped = true;
        releasePlayer();
        surface.setVisibility(android.view.View.GONE);   // park the hole with the window
        setPlaying(false);
        WallpaperState.clear();
    }

    /** Stop and reveal the still (same as pause for this headless player). */
    void stop() {
        pause();
    }

    /** Tear down completely (activity destroy). */
    void release() {
        stopped = true;
        if (holder != null) holder.removeCallback(this);
        releasePlayer();
        setPlaying(false);
        WallpaperState.clear();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        android.util.Log.i("Veil", "video surfaceCreated, stopped=" + stopped);
        surfaceReady = true;
        if (!stopped) playRandom();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        releasePlayer();
    }

    /** Picks a random clip (avoiding an immediate repeat) and streams it. */
    private void playRandom() {
        if (clips.isEmpty() || stopped) return;
        if (!surfaceReady) {
            // Parked surface: show it and wait for surfaceCreated to resume the attempt.
            surface.setVisibility(android.view.View.VISIBLE);
            return;
        }
        int pick = clips.size() > 1 ? random.nextInt(clips.size()) : 0;
        if (pick == lastPick && clips.size() > 1) pick = (pick + 1) % clips.size();
        lastPick = pick;
        playClip(clips.get(pick), 0L, false);
    }

    /**
     * Streams {@code clip} directly from its URL. Tries the link as-is (HTTPS); if the decoder
     * or the transport fails, retries the HTTP variant once before giving up to the still.
     */
    private void playClip(Clip clip, long seekMs, boolean viaHttp) {
        if (stopped || !surfaceReady) return;
        lastPick = clips.indexOf(clip);
        current = clip;
        releasePlayer();
        final String url = viaHttp ? toHttp(clip.url) : clip.url;
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            player.setDisplay(holder);
            player.setOnPreparedListener(p -> {
                main.removeCallbacks(prepareTimeout);
                setPlaying(true);          // a frame is coming: hide the still, show the video
                p.setLooping(false);
                if (seekMs > 0) {
                    try {
                        p.seekTo((int) seekMs);
                    } catch (Exception ignore) {
                        // Seek on a live stream is best-effort; playing from 0 is acceptable.
                    }
                }
                p.start();
                WallpaperState.publish(clip.url, seekMs);
                main.post(positionTick);
                android.util.Log.d("Veil", "video playing: " + clip.name
                        + (viaHttp ? " (http fallback)" : ""));
            });
            player.setOnCompletionListener(p -> playRandom());   // roll into the next landscape
            player.setOnErrorListener((p, what, extra) -> {
                if (!viaHttp) {
                    // One cheap retry over plain HTTP (some box TLS stacks balk at the CDN cert).
                    android.util.Log.w("Veil", "video https failed (" + what + "), try http");
                    playClip(clip, seekMs, true);
                } else {
                    failToStill("playback error " + what);
                }
                return true;
            });
            player.prepareAsync();
            main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            if (!viaHttp) {
                playClip(clip, seekMs, true);
            } else {
                failToStill(e.getMessage());
            }
        }
    }

    /** Best-effort HTTP variant of a URL (scheme only; host + path untouched). */
    private static String toHttp(String url) {
        if (url.startsWith("https://")) return "http://" + url.substring(8);
        return url;
    }

    /** Samples the playback position so the screensaver can take over mid-clip. */
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            if (stopped || player == null || !playing) return;
            try {
                int pos = player.getCurrentPosition();
                if (current != null) WallpaperState.publish(current.url, pos);
            } catch (Exception ignore) {
                // Position is advisory; a transient error just means the next tick retries.
            }
            main.postDelayed(this, POSITION_TICK_MS);
        }
    };

    /** No network / bad codec / too slow: back off to the static still (never a black screen). */
    private void failToStill(String why) {
        if (stopped) return;
        android.util.Log.w("Veil", "fallback to still: " + why);
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(positionTick);
        releasePlayer();
        setPlaying(false);     // reveal the static still
        WallpaperState.clear();
        // Park the surface too: an idle SurfaceView composites as opaque black on some boxes
        // (Phicomm T1), which would cover the still even with the wallpaper layered correctly.
        surface.setVisibility(android.view.View.GONE);
        // Stay in active mode but wait before retrying — the network may come back.
        main.postDelayed(() -> {
            if (!stopped) playRandom();
        }, RETRY_MS);
    }

    private void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        if (listener != null) listener.onPlayingChanged(on);
    }

    private void releasePlayer() {
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(positionTick);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignore) {
                android.util.Log.d("Veil", "video player stop skipped: " + ignore);
            }
            try {
                player.release();
            } catch (Exception ignore) {
                android.util.Log.d("Veil", "video player release skipped: " + ignore);
            }
            player = null;
        }
    }
}
