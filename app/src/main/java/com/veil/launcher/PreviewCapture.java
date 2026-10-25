package com.veil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.DisplayMetrics;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * App-content previews (the owner's ask: the focused app's big picture must be the app's real
 * content, not its icon). While another app is in front, the screen is mirrored at half
 * resolution and the freshest frame is kept; on return to the desktop it is written as
 * {@code previews/preview_<pkg>.jpg}, and the hero art shows it for that app.
 * <p>
 * No service, no notification, no permissions: the projection lives in the launcher process,
 * which for a home screen never stops. The system's one-time capture consent is asked on the
 * first app launch after the feature is on; if it is refused, the banner art stays as it was.
 * Frames are gated to ~2.5 a second, capped at 30 files, and a black (or secure) screen is
 * never written — a 1 GB box never notices this running.
 */
final class PreviewCapture {

    private static final String TAG = "TvHome.Preview";
    private static final int MAX_PREVIEWS = 30;
    /** Frames younger than this are kept only as a fallback: they may be the hand-off frame. */
    private static final long FRAME_GATE_MS = 400;
    private static final long TRANSITION_MS = 250;
    private static final int SAVE_MAX_W = 720;

    private static PreviewCapture instance;

    private final Context appCtx;
    private final MediaProjection projection;
    private final HandlerThread thread = new HandlerThread("preview-capture");
    private final Handler handler;
    private final int capW, capH, capDpi;

    private ImageReader reader;
    private android.hardware.display.VirtualDisplay display;
    private String pkg;
    private Bitmap latest, prev;
    private long latestAt, prevAt;
    private boolean closed;

    private PreviewCapture(Context ctx, MediaProjection projection) {
        this.appCtx = ctx.getApplicationContext();
        this.projection = projection;
        thread.start();
        handler = new Handler(thread.getLooper());
        DisplayMetrics dm = appCtx.getResources().getDisplayMetrics();
        capW = Math.max(2, dm.widthPixels / 2);
        capH = Math.max(2, dm.heightPixels / 2);
        capDpi = Math.max(1, dm.densityDpi / 2);
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                projection.registerCallback(new MediaProjection.Callback() {
                    @Override
                    public void onStop() {
                        android.util.Log.d(TAG, "capture revoked by the system");
                        instance = null;   // the next launch asks for consent again
                    }
                }, handler);
            } catch (Exception e) {
                android.util.Log.w(TAG, "projection callback: " + e);
            }
        }
    }

    /** Keeps the projection for the whole process lifetime; replaces an older one. */
    static void init(Context ctx, MediaProjection projection) {
        if (instance != null) instance.shutdown();
        try {
            instance = new PreviewCapture(ctx, projection);
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w(TAG, "capture unavailable: " + e);
            instance = null;
        }
    }

    static boolean ready() {
        return instance != null;
    }

    /** Starts mirroring for the app the owner just opened (no-op before consent). */
    static void start(String pkg) {
        PreviewCapture pc = instance;
        if (pc != null) pc.begin(pkg);
    }

    /**
     * The desktop is back: stop mirroring at once (so the desktop itself is never captured),
     * then write the freshest frame that is old enough to be the app.
     */
    static void stopAndSave() {
        PreviewCapture pc = instance;
        if (pc == null) return;
        pc.stopMirror();
        pc.handler.post(pc::saveLatest);
    }

    /** The app's cached preview, or null when it has never been captured. */
    static File fileFor(Context ctx, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        File dir = previewsDir(ctx);
        File f = dir == null ? null : new File(dir, "preview_" + pkg + ".jpg");
        return f != null && f.isFile() ? f : null;
    }

    private void begin(String pkg) {
        if (closed) return;
        this.pkg = pkg;
        latest = null;
        prev = null;
        latestAt = prevAt = 0;
        if (display != null) return;   // already mirroring (the owner relaunched quickly)
        try {
            reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(onImage, handler);
            display = projection.createVirtualDisplay("veil-preview", capW, capH, capDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, handler);
            android.util.Log.d(TAG, "mirroring for " + pkg);
        } catch (Exception e) {
            android.util.Log.w(TAG, "capture start failed: " + e);
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // nothing else to free
                }
            }
            reader = null;
        }
    }

    private final ImageReader.OnImageAvailableListener onImage = r -> {
        Image image = r.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (latest != null && now - latestAt < FRAME_GATE_MS) {   // drain: the newest frame wins
            image.close();
            return;
        }
        try {
            Bitmap frame = toBitmap(image);
            if (frame != null) {
                if (prev != null && prev != latest) prev.recycle();
                prev = latest;          // keep one older frame in case the newest is the hand-off
                prevAt = latestAt;
                latest = frame;
                latestAt = now;
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "frame copy failed: " + e);
        } finally {
            image.close();
        }
    };

    /** One RGBA frame, row-stride aware (some pads the rows). */
    private static Bitmap toBitmap(Image image) {
        final int w = image.getWidth(), h = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer src = plane.getBuffer();
        int rowStride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        if (rowStride == w * 4 && pixelStride == 4) {
            src.rewind();
            bmp.copyPixelsFromBuffer(src);
            return bmp;
        }
        byte[] row = new byte[w * 4];
        ByteBuffer dst = ByteBuffer.allocate(w * h * 4);
        for (int y = 0; y < h; y++) {
            src.position(y * rowStride);
            src.limit(Math.min(src.capacity(), y * rowStride + w * 4));
            src.get(row);
            dst.put(row);
        }
        dst.flip();
        bmp.copyPixelsFromBuffer(dst);
        return bmp;
    }

    private void stopMirror() {
        if (display != null) {
            try {
                display.release();
            } catch (Exception e) {
                android.util.Log.w(TAG, "display release: " + e);
            }
            display = null;
        }
    }

    /** Capture thread: drop the reader, then write the picked frame. */
    private void saveLatest() {
        stopMirror();
        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null);
                reader.close();
            } catch (Exception e) {
                android.util.Log.w(TAG, "reader close: " + e);
            }
            reader = null;
        }
        Bitmap a = latest, b = prev;
        latest = prev = null;
        Bitmap frame;
        if (a == null) {
            frame = b;
        } else if (System.currentTimeMillis() - latestAt >= TRANSITION_MS) {
            frame = a;
            if (b != null) b.recycle();
        } else {
            frame = b != null ? b : a;
            if (b != null && a != b) a.recycle();
        }
        String target = pkg;
        pkg = null;
        if (frame == null || target == null) {
            if (frame != null) frame.recycle();
            return;
        }
        try {
            File dir = previewsDir(appCtx);
            if (dir == null) {
                frame.recycle();
                return;
            }
            Bitmap out = frame;
            if (frame.getWidth() > SAVE_MAX_W) {
                float s = SAVE_MAX_W / (float) frame.getWidth();
                out = Bitmap.createScaledBitmap(frame, SAVE_MAX_W,
                        Math.max(1, Math.round(frame.getHeight() * s)), true);
            }
            if (tooDark(out)) {
                android.util.Log.d(TAG, "blank screen, no preview for " + target);
            } else {
                File f = new File(dir, "preview_" + target + ".jpg");
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    out.compress(Bitmap.CompressFormat.JPEG, 78, fo);
                }
                android.util.Log.d(TAG, "preview saved for " + target);
                trim(dir);
            }
            if (out != frame) out.recycle();
        } catch (Exception | OutOfMemoryError e) {
            android.util.Log.w(TAG, "preview save failed: " + e);
        } finally {
            frame.recycle();
        }
    }

    /** A black or secure screen is worthless as a preview: sample a dozen points. */
    private static boolean tooDark(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        long sum = 0;
        int n = 0;
        for (int i = 0; i < 12; i++) {
            int x = Math.min(w - 1, (int) ((i + 0.5f) / 12f * w));
            int y = Math.min(h - 1, (int) ((i + 0.5f) / 12f * h));
            int p = bmp.getPixel(x, y);
            sum += (p >> 16 & 0xFF) + (p >> 8 & 0xFF) + (p & 0xFF);
            n += 3;
        }
        return n > 0 && sum / n < 8;
    }

    /** Keeps the newest MAX_PREVIEWS files; the rest are older captures nobody looks at. */
    private static void trim(File dir) {
        File[] all = dir.listFiles((d, name) -> name.startsWith("preview_") && name.endsWith(".jpg"));
        if (all == null || all.length <= MAX_PREVIEWS) return;
        Arrays.sort(all, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
        for (int i = MAX_PREVIEWS; i < all.length; i++) {
            if (!all[i].delete()) android.util.Log.d(TAG, "old preview kept: " + all[i].getName());
        }
    }

    private void shutdown() {
        closed = true;
        stopMirror();
        handler.post(() -> {
            if (latest != null) latest.recycle();
            if (prev != null) prev.recycle();
            latest = prev = null;
            thread.quitSafely();
        });
    }

    private static File previewsDir(Context ctx) {
        File base = Config.dir(ctx);
        File dir = base == null ? null : new File(base, "previews");
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            android.util.Log.w(TAG, "previews folder unavailable");
            return null;
        }
        return dir;
    }
}
