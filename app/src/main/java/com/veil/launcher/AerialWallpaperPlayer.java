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
 * Desktop aerial video background: streams Apple's 2K aerials — the very same playlist the Aerial
 * screensaver (AerialDream) uses — behind the launcher UI, so the home screen shows the moving
 * Apple-TV-style video instead of a frozen still. This is the "航拍视频真播" the owner asked for.
 * <p>
 * It deliberately has no clock or caption overlay (unlike the screensaver): it is a clean
 * background. When the network or the codec fails, or a clip is too slow to start, it quietly
 * stops and the static aerial still (wallpaperView) shows through — the desktop is never dead or
 * black. It retries on a gentle cadence in case the TV reconnects.
 * <p>
 * Threading: the MediaPlayer callbacks fire on the main thread, so the StateListener (which touches
 * wallpaperView) is safe to run directly.
 */
final class AerialWallpaperPlayer implements SurfaceHolder.Callback {

    /** Give a clip this long to become playable before falling back to the still. */
    private static final long PREPARE_TIMEOUT_MS = 12_000L;
    /** How long to wait before retrying after a hard failure (network down, etc.). */
    private static final long RETRY_MS = 60_000L;

    /** Notified when a frame reaches the screen (hide the still) or we fall back (show the still). */
    interface StateListener {
        void onPlayingChanged(boolean playing);
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final android.content.Context ctx;
    private final SurfaceView surface;
    private final StateListener listener;

    private final List<Clip> clips = new ArrayList<>();
    private int lastPick = -1;
    private SurfaceHolder holder;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean stopped = true;   // created stopped; start() flips it
    private boolean playing;
    private final Runnable prepareTimeout = () -> failToStill("prepare timeout");

    private static final class Clip {
        final String name, url;
        Clip(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    AerialWallpaperPlayer(android.content.Context ctx, SurfaceView surface, StateListener listener) {
        this.ctx = ctx;
        this.surface = surface;
        this.listener = listener;
        holder = surface.getHolder();
        holder.addCallback(this);
        loadPlaylist();
    }

    /** Reads the bundled 16 KB playlist; a broken one just means no video (the still stays). */
    private void loadPlaylist() {
        try (InputStream in = ctx.getAssets().open("aerials.json")) {
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
            android.util.Log.w("TvHome", "aerial desktop playlist unreadable: " + e);
        }
    }

    /** Whether we are in active mode (attempting playback), so callers don't restart redundantly. */
    boolean isActive() {
        return !stopped;
    }

    /** Begin (or resume) playback. No-op when there are no clips or already active. */
    void start() {
        if (clips.isEmpty()) return;
        boolean wasStopped = stopped;
        stopped = false;
        if (surfaceReady && (!playing || wasStopped)) playRandom();
    }

    /** Pause playback and reveal the static still (desktop away, or aerial rotation turned off). */
    void pause() {
        stopped = true;
        releasePlayer();
        setPlaying(false);
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
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
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

    private void playRandom() {
        playAnother(0);
    }

    /** Plays the clip `step` away from the current one (wraps around); 0 = a fresh random pick. */
    private void playAnother(int step) {
        if (clips.isEmpty() || stopped || !surfaceReady) return;
        int pick;
        if (step == 0) {
            pick = clips.size() > 1 ? random.nextInt(clips.size() - 1) : 0;
            if (pick >= lastPick && lastPick >= 0) pick++;   // never the same clip twice in a row
        } else {
            pick = ((lastPick < 0 ? 0 : lastPick) + step + clips.size()) % clips.size();
        }
        lastPick = pick;
        final Clip clip = clips.get(pick);
        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setDataSource(clip.url);
            player.setDisplay(holder);
            player.setOnPreparedListener(p -> {
                handler.removeCallbacks(prepareTimeout);
                setPlaying(true);          // a frame is coming: hide the still, show the video
                p.setLooping(false);
                p.start();
            });
            player.setOnCompletionListener(p -> playAnother(1));   // roll into the next landscape
            player.setOnErrorListener((p, what, extra) -> {
                failToStill("playback error " + what);
                return true;
            });
            player.prepareAsync();
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            failToStill(e.getMessage());
        }
    }

    /** No network / bad codec / too slow: back off to the static still (never a black screen). */
    private void failToStill(String why) {
        if (stopped) return;
        android.util.Log.w("TvHome.AerialBg", "fallback to still: " + why);
        handler.removeCallbacks(prepareTimeout);
        releasePlayer();
        setPlaying(false);     // reveal the static still
        // Stay in active mode but wait before retrying — the TV may reconnect overnight.
        handler.postDelayed(() -> {
            if (!stopped && surfaceReady) playRandom();
        }, RETRY_MS);
    }

    private void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        if (listener != null) listener.onPlayingChanged(on);
    }

    private void releasePlayer() {
        handler.removeCallbacks(prepareTimeout);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception ignore) {
                android.util.Log.d("TvHome", "aerial bg player stop skipped: " + ignore);
            }
            try {
                player.release();
            } catch (Exception ignore) {
                android.util.Log.d("TvHome", "aerial bg player release skipped: " + ignore);
            }
            player = null;
        }
    }
}
