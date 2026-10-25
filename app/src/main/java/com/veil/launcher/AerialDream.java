package com.veil.launcher;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The aerial screensaver (inspired by Apple's aerial screensaver): full-screen Apple TV aerials streamed straight from
 * Apple's servers — nothing in the APK but a 16 KB playlist. A thin corner clock and the place
 * caption sit over the video; left/right keys jump to the previous/next clip. When the network
 * or the codec fails it quietly falls back to the drifting clock look, so the screen is never
 * dead. Videos play at the server's 2K H.264 bitrate; the saver is opt-in in the system's
 * screensaver picker, so nobody pays the traffic by accident.
 */
public class AerialDream extends DreamService implements SurfaceHolder.Callback {

    /** Give a clip this long to become playable before falling back to the clock. */
    private static final long PREPARE_TIMEOUT_MS = 12_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private static final class Clip {
        final String name, url;

        Clip(String name, String url) {
            this.name = name;
            this.url = url;
        }
    }

    private final List<Clip> clips = new ArrayList<>();
    private int lastPick = -1;

    private SurfaceView surface;
    private SurfaceHolder holder;
    private View overlay, fallback;
    private TextView caption;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean stopped;
    private final Runnable prepareTimeout = new Runnable() {
        @Override
        public void run() {
            failToClock("prepare timeout");
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive so the D-pad can hop between clips; anything that isn't a hop wakes the TV.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_aerial);
        surface = findViewById(R.id.aerialSurface);
        overlay = findViewById(R.id.aerialOverlay);
        fallback = findViewById(R.id.aerialFallback);
        caption = findViewById(R.id.aerialCaption);
        holder = surface.getHolder();
        holder.addCallback(this);
        loadPlaylist();
        overlay.setVisibility(clips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        // surfaceCreated may already have fired, or may still be coming; playRandom() guards both.
        if (!clips.isEmpty() && surfaceReady && !stopped && player == null
                && fallback.getVisibility() != View.VISIBLE) {
            playRandom();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Left/right hop between clips; other keys fall through and wake the TV as usual.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                playAnother(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onDreamingStopped() {
        stopped = true;
        releasePlayer();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopped = true;
        releasePlayer();
        super.onDetachedFromWindow();
    }

    /** Reads the bundled 16 KB playlist; a broken one just means the clock fallback. */
    private void loadPlaylist() {
        try (InputStream in = getAssets().open("aerials.json")) {
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
            // A missing or broken clips.json: the screensaver stays dark instead of crashing.
            android.util.Log.w("TvHome", "aerial clips unreadable: " + e);
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (!clips.isEmpty() && !stopped) playRandom();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
    }

    private void playRandom() {
        playAnother(0);
    }

    /** Plays the clip `step` away from the current one (wraps around); 0 = a fresh random pick. */
    private void playAnother(int step) {
        if (clips.isEmpty() || stopped) return;
        int pick;
        if (step == 0) {
            pick = clips.size() > 1 ? random.nextInt(clips.size() - 1) : 0;
            if (pick >= lastPick && lastPick >= 0) pick++;   // never the same clip twice in a row
        } else {
            pick = ((lastPick < 0 ? 0 : lastPick) + step + clips.size()) % clips.size();
        }
        lastPick = pick;
        final Clip clip = clips.get(pick);
        caption.setText(clip.name);
        releasePlayer();
        if (!surfaceReady) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(clip.url);
            player.setDisplay(holder);
            player.setOnPreparedListener(p -> {
                handler.removeCallbacks(prepareTimeout);
                fallback.setVisibility(View.GONE);
                surface.setVisibility(View.VISIBLE);
                p.setLooping(false);
                p.start();
            });
            player.setOnCompletionListener(p -> playAnother(1));   // roll into the next landscape
            player.setOnErrorListener((p, what, extra) -> {
                failToClock("playback error " + what);
                return true;
            });
            player.prepareAsync();
            handler.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            failToClock(e.getMessage());
        }
    }

    /** No network / bad codec / too slow: show the drifting clock instead of a dead screen. */
    private void failToClock(String why) {
        if (stopped) return;
        android.util.Log.w("TvHome.Aerial", "fallback: " + why);
        handler.removeCallbacks(prepareTimeout);
        releasePlayer();
        surface.setVisibility(View.GONE);
        overlay.setVisibility(View.GONE);
        fallback.setVisibility(View.VISIBLE);
        // Slow drift, same idea as ClockDream: nothing stays still long enough to burn in.
        handler.removeCallbacks(fallbackTick);
        fallbackTick.run();
        // Try the network again in a few minutes — the TV may have reconnected overnight.
        handler.postDelayed(() -> {
            if (!stopped && !clips.isEmpty()) {
                handler.removeCallbacks(fallbackTick);
                fallback.setVisibility(View.GONE);
                overlay.setVisibility(View.VISIBLE);
                playRandom();
            }
        }, 3 * 60_000L);
    }

    /** Moves the fallback clock to a new spot every minute and a half. */
    private final Runnable fallbackTick = new Runnable() {
        @Override
        public void run() {
            if (stopped || fallback.getVisibility() != View.VISIBLE) return;
            View parent = (View) fallback.getParent();
            int maxX = Math.max(0, parent.getWidth() - fallback.getWidth());
            int maxY = Math.max(0, parent.getHeight() - fallback.getHeight());
            fallback.animate()
                    .x(maxX * (0.08f + 0.84f * random.nextFloat()))
                    .y(maxY * (0.08f + 0.84f * random.nextFloat()))
                    .setDuration(2500).start();
            handler.postDelayed(this, 90_000L);
        }
    };

    private void releasePlayer() {
        handler.removeCallbacks(prepareTimeout);
        if (player != null) {
            try {
                player.stop();
            } catch (Exception e) {
                android.util.Log.d("TvHome", "aerial player stop skipped: " + e);
            }
            try {
                player.release();
            } catch (Exception e) {
                android.util.Log.d("TvHome", "aerial player release skipped: " + e);
            }
            player = null;
        }
    }
}
