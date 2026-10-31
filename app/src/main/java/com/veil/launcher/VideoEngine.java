package com.veil.launcher;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * One process-wide MediaPlayer that feeds BOTH the desktop video background and the video
 * screensaver. The two are just surfaces that take turns owning the player's output: switching
 * between them is a single setDisplay() call, so the stream never re-buffers or restarts — it
 * flows continuously from desktop to screensaver and back, at a single decode (one stream of
 * CDN traffic, not two). Only one surface is attached at a time; the idle side simply isn't
 * rendered while the player keeps decoding, which is what makes the hand-off instant.
 */
final class VideoEngine {

    static final VideoEngine INSTANCE = new VideoEngine();

    /** Give a clip this long to become playable before falling back. */
    private static final long PREPARE_TIMEOUT_MS = 12_000L;
    /** Wait this long after a failure before retrying the network. */
    private static final long RETRY_MS = 30_000L;
    /** How often we sample the playback position for diagnostics. */
    private static final long POSITION_TICK_MS = 5_000L;

    /**
     * The active surface's UI hook. {@code onPlayingChanged(true)} means a frame is on screen —
     * the surface should reveal itself and hide its fallback; {@code false} means show the
     * fallback (the static still on the desktop, the drifting clock on the screensaver).
     * {@code onClipResolved(name)} carries the playing clip's caption for the screensaver.
     */
    interface Listener {
        void onPlayingChanged(boolean playing);

        void onClipResolved(String name);
    }

    private enum Stage { DESKTOP, DREAM }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final List<Clip> clips = new ArrayList<>();

    private Context ctx;
    private MediaPlayer player;
    private SurfaceHolder desktopHolder, dreamHolder;
    private Listener desktopListener, dreamListener;
    private Stage stage = Stage.DESKTOP;
    private boolean dreamActive;
    private Clip current;
    private int lastPick = -1;
    private boolean playing;
    private boolean started;
    private final Runnable prepareTimeout = () -> failToStill("prepare timeout");

    private VideoEngine() {
    }

    // ---- setup ----

    /** Loads the playlist once, from the first context we see. */
    void init(Context ctx) {
        if (this.ctx != null) return;
        this.ctx = ctx.getApplicationContext();
        loadPlaylist();
    }

    private void loadPlaylist() {
        if (ctx == null) return;
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
            // A missing or broken playlist just means no video (the still / clock stays).
            android.util.Log.w("Veil", "video playlist unreadable: " + e);
        }
    }

    // ---- surface ownership ----

    void setDesktopHolder(SurfaceHolder h, Listener l) {
        desktopHolder = h;
        desktopListener = l;
        if (!dreamActive) {
            applyStage();
            if (started && playing) notifyActive();
            else if (started && !playing) playRandom();
        }
    }

    void clearDesktopHolder() {
        desktopHolder = null;
        if (!dreamActive) applyStage();
    }

    void setDreamHolder(SurfaceHolder h, Listener l) {
        dreamHolder = h;
        dreamListener = l;
        if (dreamActive) {
            applyStage();
            if (started && playing) notifyActive();
            else if (started && !playing) playRandom();
        }
    }

    void clearDreamHolder() {
        dreamHolder = null;
        if (dreamActive) applyStage();
    }

    /** Switch the rendered output to the desktop surface (dream ends / app resumes). */
    void useDesktop() {
        dreamActive = false;
        stage = Stage.DESKTOP;
        applyStage();
        if (started && playing) notifyActive();
        else if (started && !playing) playRandom();
    }

    /** Switch the rendered output to the screensaver surface (dream begins). */
    void useDream() {
        dreamActive = true;
        stage = Stage.DREAM;
        applyStage();
        if (started && playing) notifyActive();
        else if (started && !playing) playRandom();
    }

    /** Point the player at whichever surface currently owns it. */
    private void applyStage() {
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        if (player != null) {
            try {
                // h may be null mid hand-off: keep decoding, attach on the next setXHolder().
                player.setDisplay(h);
            } catch (Exception ignore) {
            }
        }
    }

    /** Tell the active listener its playing state — only when its surface is actually ready. */
    private void notifyActive() {
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        Listener l = dreamActive ? dreamListener : desktopListener;
        if (h != null && l != null) l.onPlayingChanged(playing);
    }

    // ---- lifecycle ----

    /** Begin (or resume) playback; a no-op until a surface is attached. */
    void start() {
        started = true;
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        if (h != null && !playing) playRandom();
    }

    /** Stop and release the decoder (desktop hidden, or video wallpaper turned off). */
    void pause() {
        started = false;
        releasePlayer();
        setPlaying(false);
        WallpaperState.clear();
    }

    /** Release the decoder without forgetting the started flag (process tear-down). */
    void release() {
        started = false;
        releasePlayer();
    }

    boolean isStarted() {
        return started;
    }

    /** Left/right hop between clips; only the screensaver may drive this. */
    void hop(int step) {
        if (!dreamActive) return;
        playAnother(step);
    }

    // ---- playback ----

    private void playRandom() {
        playAnother(0);
    }

    private void playAnother(int step) {
        if (clips.isEmpty() || !started) return;
        int pick;
        if (step == 0) {
            pick = clips.size() > 1 ? random.nextInt(clips.size()) : 0;
            if (pick >= lastPick && lastPick >= 0) pick = (pick + 1) % clips.size();
        } else {
            pick = ((lastPick < 0 ? 0 : lastPick) + step + clips.size()) % clips.size();
        }
        lastPick = pick;
        playClip(clips.get(pick), 0L, false);
    }

    /**
     * Streams {@code clip} straight from its URL. Tries HTTPS first; if the decoder or transport
     * fails, retries the plain-HTTP variant once before giving up to the fallback. The same stream
     * is reused across desktop and screensaver, so there is never a second decode.
     */
    private void playClip(Clip clip, long seekMs, boolean viaHttp) {
        if (!started) return;
        lastPick = clips.indexOf(clip);
        current = clip;
        releasePlayer();
        final String url = viaHttp ? toHttp(clip.url) : clip.url;
        try {
            player = new MediaPlayer();
            player.setDataSource(url);
            player.setDisplay(dreamActive ? dreamHolder : desktopHolder);
            player.setOnPreparedListener(p -> {
                main.removeCallbacks(prepareTimeout);
                setPlaying(true);
                p.setLooping(false);
                if (seekMs > 0) {
                    try {
                        p.seekTo((int) seekMs);
                    } catch (Exception ignore) {
                        // Resuming a live stream is best-effort; playing from 0 is fine.
                    }
                }
                p.start();
                if (current != null) {
                    WallpaperState.publish(current.url, seekMs);
                    notifyClip(current.name);
                }
                main.post(positionTick);
                android.util.Log.d("Veil", "video playing: " + clip.name + (viaHttp ? " (http)" : ""));
            });
            player.setOnCompletionListener(p -> playAnother(1));   // roll into the next landscape
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

    private static String toHttp(String url) {
        if (url.startsWith("https://")) return "http://" + url.substring(8);
        return url;
    }

    /** Samples the playback position so diagnostics know what is on screen. */
    private final Runnable positionTick = new Runnable() {
        @Override
        public void run() {
            if (!started || player == null || !playing) return;
            try {
                int pos = player.getCurrentPosition();
                if (current != null) WallpaperState.publish(current.url, pos);
            } catch (Exception ignore) {
                // Position is advisory; a transient error just means the next tick retries.
            }
            main.postDelayed(this, POSITION_TICK_MS);
        }
    };

    /** No network / bad codec / too slow: back off to the active surface's fallback. */
    private void failToStill(String why) {
        if (!started) return;
        android.util.Log.w("Veil", "video fallback: " + why);
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(positionTick);
        releasePlayer();
        setPlaying(false);
        WallpaperState.clear();
        // Stay in active mode but wait before retrying — the network may come back.
        main.postDelayed(() -> {
            if (started && !playing) playRandom();
        }, RETRY_MS);
    }

    private void notifyClip(String name) {
        Listener l = dreamActive ? dreamListener : desktopListener;
        if (l != null) l.onClipResolved(name);
    }

    private void setPlaying(boolean on) {
        if (playing == on) return;
        playing = on;
        Listener l = dreamActive ? dreamListener : desktopListener;
        if (l != null) l.onPlayingChanged(on);
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

    private static final class Clip {
        final String name, url;

        Clip(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }
}
