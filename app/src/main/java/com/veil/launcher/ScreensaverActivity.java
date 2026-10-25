package com.veil.launcher;

import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Random;

/**
 * The launcher's own screensaver (the owner's ask): after the chosen idle minutes the home screen
 * hands the screen over to this — the cached Apple aerial stills and the Bing picture of the day,
 * one full-screen picture at a time, crossfaded, with a slow zoom so nothing stays still long
 * enough to burn in, and a big clock over the art like Apple's. Any key or touch ends it.
 * <p>
 * The switch, the idle minutes and the clock live in the settings panel. Off by default: a
 * screensaver nobody asked for is a screensaver that gets a launcher uninstalled.
 * <p>
 * This is deliberately NOT a DreamService: the system screensaver picker and its timeout live in
 * Settings.Secure, which an app cannot write — the owner's own switch and minutes have to run
 * inside the launcher, which is exactly where the owner looks for them. (The two DreamService
 * savers — the drifting clock and the streaming Apple videos — stay as they are.)
 */
public class ScreensaverActivity extends Activity {

    /** One picture stays this long before the next fades in. */
    private static final long SLIDE_MS = 45_000L;
    private static final long FADE_MS = 900L;
    /** An unreadable file is skipped in a moment, not after a whole slide. */
    private static final long RETRY_MS = 2_000L;
    /** The zoom across one slide: barely there, but nothing is ever perfectly still. */
    private static final float ZOOM_TO = 1.07f;
    /**
     * Hard ceiling on the decoded bitmap's pixels (~1080p): a 4K panel would otherwise ask for
     * 33 MB per picture, and the saver holds two of them plus a decode on a 1 GB box — that is
     * how a screensaver turns black. Sources are 1080p stills, so nothing is lost here.
     */
    private static final int MAX_PIXELS = 2_200_000;

    /** True while the screensaver is on screen; the launcher checks it before starting another. */
    static boolean showing;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<File> pool = new ArrayList<>();
    private final Random rnd = new Random();

    private ImageView imgA, imgB, front;
    private LinearLayout clockBox;
    private TextView timeView, dateView;
    private boolean clockOn = true;
    private int next;
    /** The slow anti-burn-in zoom of the picture in front (its own animators, see applyPicture). */
    private android.animation.ObjectAnimator zoomX, zoomY;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            if (pool.isEmpty() || isDestroyed()) return;
            if (next >= pool.size()) next = 0;
            showNext();
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (!clockOn || isDestroyed()) return;
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

        imgA = findViewById(R.id.saverImgA);
        imgB = findViewById(R.id.saverImgB);
        front = imgA;
        clockBox = findViewById(R.id.saverClock);
        timeView = findViewById(R.id.saverTime);
        dateView = findViewById(R.id.saverDate);
        clockOn = new PrefsStore(this).isScreensaverClockOn();
        clockBox.setVisibility(clockOn ? View.VISIBLE : View.GONE);
        showing = true;

        // The pool is file IO (the stills folder can hold dozens of pictures): background.
        new Thread(() -> {
            collectPool();
            handler.post(() -> {
                if (isDestroyed()) return;
                clockTick.run();
                if (!pool.isEmpty()) {
                    next = rnd.nextInt(pool.size());
                    showNext();   // schedules its own follow-up once the decode lands
                }
            });
        }, "saver-pool").start();
    }

    /**
     * The current wallpaper slot first, then the cached Bing stock and the cached aerial
     * stills in a shuffled order (the stock grows on its own: one Bing picture a day).
     */
    private void collectPool() {
        File dir = Config.dir(this);
        File wall = dir != null ? new File(dir, Config.WALLPAPER_NAME) : null;
        if (wall != null && wall.isFile() && wall.length() > 0) pool.add(wall);
        File bing = dir != null ? new File(dir, "bing") : null;
        File[] stock = bing != null
                ? bing.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg")) : null;
        if (stock != null && stock.length > 0) {
            List<File> list = new ArrayList<>(Arrays.asList(stock));
            Collections.shuffle(list, rnd);
            pool.addAll(list);
        }
        File aerials = dir != null ? new File(dir, "aerials") : null;
        File[] stills = aerials != null
                ? aerials.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg")) : null;
        if (stills != null && stills.length > 0) {
            List<File> list = new ArrayList<>(Arrays.asList(stills));
            Collections.shuffle(list, rnd);
            pool.addAll(list);
        }
    }

    /**
     * Decodes the next picture off the main thread (a 4K still must never jank the screen it
     * decorates) and schedules the follow-up: a full slide after a good picture, a short retry
     * after an unreadable file so one bad JPEG cannot cost 45 seconds of black.
     */
    private void showNext() {
        if (pool.isEmpty() || isDestroyed()) return;
        final File file = pool.get(next);
        next++;
        new Thread(() -> {
            final Bitmap bmp = decode(file);
            handler.post(() -> {
                if (isDestroyed()) return;
                if (bmp != null) applyPicture(bmp);
                handler.postDelayed(rotate, bmp != null ? SLIDE_MS : RETRY_MS);
            });
        }, "saver-decode").start();
    }

    /**
     * Crossfades to the next picture (null never reaches here: showNext filters it).
     * <p>The fade and the slow zoom MUST ride separate animators: one ViewPropertyAnimator has a
     * single duration, so folding the 45 s zoom into the fade's animator stretched the crossfade
     * to 45 s as well — the old picture was gone in under a second and the new one crawled in
     * from black for the rest of the slide (the owner's "screensaver is often black").
     */
    private void applyPicture(Bitmap bmp) {
        if (bmp == null || isDestroyed()) return;
        ImageView back = front == imgA ? imgB : imgA;
        // The zoom from this view's last turn (two slides ago) may still be running.
        if (zoomX != null) { zoomX.cancel(); zoomX = null; }
        if (zoomY != null) { zoomY.cancel(); zoomY = null; }
        back.setImageBitmap(bmp);
        back.setScaleX(1f);
        back.setScaleY(1f);
        back.setAlpha(0f);
        back.animate().cancel();
        back.animate().alpha(1f).setDuration(FADE_MS).start();
        front.animate().cancel();
        front.animate().alpha(0f).setDuration(FADE_MS).start();
        front = back;
        zoomX = android.animation.ObjectAnimator.ofFloat(back, View.SCALE_X, 1f, ZOOM_TO);
        zoomY = android.animation.ObjectAnimator.ofFloat(back, View.SCALE_Y, 1f, ZOOM_TO);
        zoomX.setDuration(SLIDE_MS);
        zoomY.setDuration(SLIDE_MS);
        zoomX.start();
        zoomY.start();
    }

    /** Downsampled to the screen, ARGB_8888: the 565 half-memory saving showed as sky banding. */
    private Bitmap decode(File file) {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= dm.widthPixels
                    && o.outHeight / (sample * 2) >= dm.heightPixels) sample *= 2;
            // Hard pixel ceiling: never hold more than ~a 1080p frame per picture on this box.
            while (o.outWidth / sample * o.outHeight / sample > MAX_PIXELS) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            o.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;   // sky banding in 565
            return android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w("TvHome", "screensaver picture unreadable: " + e);
            return null;
        }
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

    @Override
    protected void onDestroy() {
        showing = false;
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(clockTick);
        if (zoomX != null) zoomX.cancel();
        if (zoomY != null) zoomY.cancel();
        super.onDestroy();
    }
}
