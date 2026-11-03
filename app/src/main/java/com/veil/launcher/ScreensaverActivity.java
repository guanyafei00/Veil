package com.veil.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.ScaleAnimation;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.Date;

/**
 * The launcher's own screensaver. After the chosen idle minutes the home screen hands the display
 * to this, and it mirrors whatever the DESKTOP is showing:
 * <ul>
 *   <li>Desktop wallpaper source = 航拍视频 → play the SAME aerial video (borrowing the desktop's
 *       single shared ExoPlayer so the very same clip keeps flowing, no re-buffer, no restart).</li>
 *   <li>Desktop wallpaper source = anything else (a photo, Bing, 360, the bundled default) → show
 *       that SAME static wallpaper picture, with a gentle breathe, so the screensaver is a still
 *       image exactly like the desktop.</li>
 * </ul>
 * A big clock sits over either, like Apple's; any key or touch ends it.
 * <p>
 * The decision is made from the persisted wallpaper source ({@link PrefsStore#loadVideoSource()}),
 * NOT from the live engine state: when this activity launches, the home screen goes through
 * onStop() which calls VideoEngine.pause(), so the engine reports "not started" even when the
 * desktop was genuinely playing video. The source preference is the truthful "is the desktop
 * video" signal.
 * <p>
 * This is deliberately NOT a DreamService: the system screensaver picker and its timeout live in
 * Settings.Secure, which an app cannot write — the owner's own switch and minutes have to run
 * inside the launcher. The system's own DreamService saver (VideoDream) already shares the same
 * engine the same way.
 */
public class ScreensaverActivity extends Activity implements SurfaceHolder.Callback {

    /** True while the screensaver is on screen; the launcher checks it before starting another. */
    static boolean showing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final VideoEngine engine = VideoEngine.INSTANCE;

    private SurfaceView surface;
    private ImageView stillView;
    /** Black curtain above the content; fades away once the still or the first video frame lands. */
    private View fade;
    private LinearLayout clockBox;
    private TextView timeView, dateView;
    private boolean clockOn = true;
    private boolean stopped;
    /** True when the desktop wallpaper source is the aerial video — we mirror it with video. */
    private boolean videoMode;

    /** The engine drives our surface; the clock sits over it like Apple's. */
    private final VideoEngine.Listener videoListener = new VideoEngine.Listener() {
        @Override
        public void onPlayingChanged(boolean playing) {
            if (stopped || !videoMode) return;
            // Video back up: drop the cover still and ease the curtain away. Video down
            // (playback failure or a re-buffer window): show the bundled aerial still behind
            // the clock instead of the surface's black hole.
            if (playing) {
                stillView.clearAnimation();
                stillView.setVisibility(View.GONE);
                reveal(true);
            } else {
                showDesktopStill();
            }
            clockBox.setVisibility(clockOn ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onClipResolved(String name) {
            // The launcher's own screensaver shows no caption, unlike the system dream.
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (stopped || !clockOn) return;
            timeView.setText(DateFormat.getTimeFormat(ScreensaverActivity.this).format(new Date()));
            dateView.setText(DateFormat.getMediumDateFormat(ScreensaverActivity.this).format(new Date()));
            handler.postDelayed(this, 10_000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawableResource(android.R.color.black);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_screensaver);

        surface = findViewById(R.id.saverVideo);
        stillView = findViewById(R.id.saverStill);
        fade = findViewById(R.id.saverFade);
        fade.setAlpha(1f);   // start covered; the still or the first video frame fades it away
        clockBox = findViewById(R.id.saverClock);
        timeView = findViewById(R.id.saverTime);
        dateView = findViewById(R.id.saverDate);
        clockOn = new PrefsStore(this).isScreensaverClockOn();
        // Follow the desktop: video wallpaper source -> video screensaver; anything else -> the still.
        videoMode = "video".equals(new PrefsStore(this).loadVideoSource());

        // Start on the clock; the engine swaps us to video, or the still shows behind it.
        clockBox.setVisibility(clockOn ? View.VISIBLE : View.GONE);
        showing = true;

        if (videoMode) {
            stillView.setVisibility(View.GONE);
            // Visible from the start: a SurfaceView left GONE can skip surfaceCreated on some TV ROMs
            // (the home screen is then black with only the clock). It paints black until the first
            // frame arrives, which is indistinguishable from the black window background anyway.
            surface.setVisibility(View.VISIBLE);
            // The home screen paused the engine on its onStop(); re-bind and (re)start it into our
            // surface so the same aerial clip flows on — without this, the screensaver would never play.
            engine.init(this);
            surface.getHolder().addCallback(this);
        } else {
            surface.setVisibility(View.GONE);
            showDesktopStill();
        }
        clockTick.run();
    }

    /** Load the desktop's current static wallpaper (the very file the home screen shows) and breathe it.
     *  No wallpaper file (pure-video mode) falls back to the bundled aerial frame — never a void. */
    private void showDesktopStill() {
        File wall = Config.load(this).wallpaper;
        if (!(wall != null && wall.isFile() && wall.length() > 0)) {
            // Pure-video desktop: unpack the bundled aerial frame and use it as the still.
            try {
                wall = new File(getCacheDir(), "aerial_fallback.jpg");
                if (!(wall.isFile() && wall.length() > 0)) {
                    try (java.io.InputStream in = getAssets().open("aerial_fallback.jpg");
                         java.io.OutputStream out = new java.io.FileOutputStream(wall)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("Veil", "saver fallback still unavailable: " + e);
            }
        }
        if (wall != null && wall.isFile() && wall.length() > 0) {
            Bitmap bmp = decodeWallpaper(wall);
            if (bmp != null) {
                stillView.setImageBitmap(bmp);
                stillView.setVisibility(View.VISIBLE);
                ScaleAnimation breathe = new ScaleAnimation(1f, 1.06f, 1f, 1.06f,
                        Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
                breathe.setDuration(20_000L);
                breathe.setRepeatCount(Animation.INFINITE);
                breathe.setRepeatMode(Animation.REVERSE);
                stillView.startAnimation(breathe);
                reveal(true);   // the picture is decoded: ease the curtain away
                return;
            }
        }
        // No picture to show (e.g. wallpaper switched off): keep it black behind the clock.
        stillView.setVisibility(View.GONE);
    }

    /** Eases the black curtain: {@code reveal=false} fades it back in over the content. */
    private void reveal(boolean show) {
        if (fade == null || stopped) return;
        fade.animate().cancel();
        fade.setVisibility(View.VISIBLE);
        fade.animate().alpha(show ? 0f : 1f)
                .setDuration(show ? 900L : 500L)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .withEndAction(() -> {
                    if (!stopped && fade != null && fade.getAlpha() == 0f) {
                        fade.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    /** Downsampled decode so a 4K wallpaper never costs a giant bitmap on a 1 GB TV. */
    private Bitmap decodeWallpaper(File f) {
        try {
            BitmapFactory.Options head = new BitmapFactory.Options();
            head.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), head);
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int maxW = dm.widthPixels, maxH = dm.heightPixels;
            int scale = 1;
            while (head.outWidth / scale / 2 >= maxW && head.outHeight / scale / 2 >= maxH) scale *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = scale;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (!videoMode) return;
        // Take over the shared player's output from the desktop — same clip, no restart — and
        // (re)start playback, because onStop() paused the engine before we got here.
        engine.setDreamHolder(h, videoListener);
        engine.useDream();
        engine.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        if (videoMode) engine.clearDreamHolder();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        showing = false;
        handler.removeCallbacks(clockTick);
        if (videoMode) {
            // Hand the output back to the desktop so the video flows on without restarting.
            // (MainActivity.onResume also calls engine.useDesktop(), this just makes it immediate.)
            engine.useDesktop();
            engine.clearDreamHolder();
        }
        // Free the still bitmap we decoded.
        if (stillView != null) {
            stillView.clearAnimation();
            if (stillView.getDrawable() instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) stillView.getDrawable()).getBitmap();
                stillView.setImageDrawable(null);
                if (b != null) b.recycle();
            }
        }
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Any button ends the screensaver, the way every TV saver behaves.
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            finish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        finish();
        return true;
    }
}
