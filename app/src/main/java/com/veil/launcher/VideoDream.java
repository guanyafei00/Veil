package com.veil.launcher;

import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;

import java.util.Random;

/**
 * A full-screen streaming video screensaver that shares the desktop's single decode: when the
 * dream begins it just takes over the shared player's output surface (setDisplay), so the very
 * same clip keeps playing — no re-buffer, no restart. A thin corner clock and the place caption
 * sit over the video; left/right keys hop between clips. When the network or codec fails it falls
 * back to the drifting clock look, so the screen is never dead.
 */
public class VideoDream extends DreamService implements SurfaceHolder.Callback {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final VideoEngine engine = VideoEngine.INSTANCE;

    private SurfaceView surface;
    private View overlay, fallback;
    private TextView caption;
    private boolean stopped;

    /** The engine drives our surface: show the video when it's live, the clock when it isn't. */
    private final VideoEngine.Listener videoListener = new VideoEngine.Listener() {
        @Override
        public void onPlayingChanged(boolean playing) {
            if (playing) {
                handler.removeCallbacks(driftClock);
                fallback.setVisibility(View.GONE);
                overlay.setVisibility(View.VISIBLE);
                surface.setVisibility(View.VISIBLE);
            } else {
                // No network / bad codec: show the drifting clock instead of a dead screen.
                fallback.setVisibility(View.VISIBLE);
                overlay.setVisibility(View.GONE);
                surface.setVisibility(View.GONE);
                handler.removeCallbacks(driftClock);
                driftClock.run();
            }
        }

        @Override
        public void onClipResolved(String name) {
            if (caption != null) caption.setText(name);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // Interactive so the D-pad can hop between clips; anything that isn't a hop wakes the TV.
        setInteractive(true);
        setFullscreen(true);
        setScreenBright(false);
        setContentView(R.layout.dream_video);
        surface = findViewById(R.id.videoSurface);
        overlay = findViewById(R.id.videoOverlay);
        fallback = findViewById(R.id.videoFallback);
        caption = findViewById(R.id.videoCaption);
        surface.getHolder().addCallback(this);
        engine.init(this);
        // Start on the clock; the engine swaps us to video the moment a clip is playable.
        fallback.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.GONE);
        surface.setVisibility(View.GONE);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        // Take over the shared player's output from the desktop — same clip, no restart.
        engine.useDream();
        if (!engine.isStarted()) engine.start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Left/right hop between clips; other keys fall through and wake the TV as usual.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                engine.hop(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onDreamingStopped() {
        stopped = true;
        // Hand the output back to the desktop so the video flows on without restarting.
        engine.useDesktop();
        engine.clearDreamHolder();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        stopped = true;
        engine.clearDreamHolder();
        super.onDetachedFromWindow();
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        engine.setDreamHolder(h, videoListener);
        engine.useDream();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        engine.clearDreamHolder();
    }

    /** Moves the fallback clock to a new spot every minute and a half (no burn-in). */
    private final Runnable driftClock = new Runnable() {
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
}
