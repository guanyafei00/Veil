package com.veil.launcher;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Desktop aerial video background: Apple-TV-style moving video behind the launcher UI — the
 * "航拍视频真播" the owner asked for. Plays the same HEVC 2K playlist the Aerial screensaver
 * uses, but the way the Aerial app does it: DOWNLOAD then play locally. Direct HTTPS streaming
 * through MediaPlayer stalls on Chinese boxes (Phicomm T1: MediaPlayer's native TLS stack dies
 * while java HttpsURLConnection reaches the same CDN fine), so we never stream — we fetch the
 * clip to the cache (resumable .part), then hand MediaPlayer a plain local file.
 * <p>
 * Cache policy: prefer an already-cached clip for instant playback; keep one download running
 * to grow the pool; evict oldest when over {@link #MAX_CACHE_BYTES}/{@link #MAX_CACHE_FILES}.
 * While nothing is playable (or a download/decode fails) the static still shows and we retry
 * on a gentle cadence — the desktop is never dead or black.
 * <p>
 * Threading: MediaPlayer callbacks and view tweaks run on the main thread; downloads run on a
 * private HandlerThread and hop back with {@link #main}.
 */
final class AerialWallpaperPlayer implements SurfaceHolder.Callback {

    /** Give a LOCAL clip this long to become playable before falling back to the still. */
    private static final long PREPARE_TIMEOUT_MS = 8_000L;
    /** How long to wait before retrying after a failure (download or decode). */
    private static final long RETRY_MS = 30_000L;
    /** Download attempt budget per kick (each attempt resumes from the .part). */
    private static final int DOWNLOAD_ATTEMPTS = 8;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    /** Cache ceiling: ~600 MB / 4 clips, LRU eviction (T1-class boxes have small flash). */
    private static final long MAX_CACHE_BYTES = 600L * 1024 * 1024;
    private static final int MAX_CACHE_FILES = 4;

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
    private final HandlerThread ioThread;
    private final Handler io;
    private final Random random = new Random();
    private final android.content.Context ctx;
    private final SurfaceView surface;
    private final StateListener listener;
    private final List<Clip> clips = new ArrayList<>();
    private final File cacheDir;

    private int lastPick = -1;
    private Clip current;                 // the clip playing (or last played)
    private SurfaceHolder holder;
    private MediaPlayer player;
    private boolean surfaceReady;
    private boolean stopped = true;       // created stopped; start() flips it
    private boolean playing;
    private volatile boolean downloading;
    private final Runnable prepareTimeout = () -> failToStill("prepare timeout");

    AerialWallpaperPlayer(android.content.Context ctx, SurfaceView surface, StateListener listener) {
        this.ctx = ctx;
        this.surface = surface;
        this.listener = listener;
        holder = surface.getHolder();
        holder.addCallback(this);
        File ext = ctx.getExternalFilesDir(null);
        cacheDir = new File(ext != null ? ext : ctx.getFilesDir(), "aerial_videos");
        // noinspection ResultOfMethodCallIgnored
        cacheDir.mkdirs();
        ioThread = new HandlerThread("AerialFetcher");
        ioThread.start();
        io = new Handler(ioThread.getLooper());
        loadPlaylist();
    }

    /** Reads the bundled playlist; a broken one just means no video (the still stays). */
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

    /** Local cache file for a clip's URL (stable across restarts). */
    private File cacheFile(String url) {
        return new File(cacheDir, Integer.toHexString(url.hashCode()) + ".mov");
    }

    private boolean isCached(Clip c) {
        File f = cacheFile(c.url);
        return f.isFile() && f.length() > 0;
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
        if (!surfaceReady) {
            // The surface is parked (GONE) while idle; showing it triggers surfaceCreated,
            // which calls back into playRandom().
            surface.setVisibility(android.view.View.VISIBLE);
            return;
        }
        if (!playing || wasStopped) playRandom();
    }

    /** Pause playback and reveal the static still (desktop away, or aerial rotation turned off). */
    void pause() {
        stopped = true;
        releasePlayer();
        surface.setVisibility(android.view.View.GONE);   // park the hole with the window
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
        ioThread.quitSafely();
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
        if (clips.isEmpty() || stopped) return;
        if (!surfaceReady) {
            // Parked surface: show it and wait for surfaceCreated to resume the attempt.
            surface.setVisibility(android.view.View.VISIBLE);
            return;
        }
        // Prefer a cached clip: instant playback, zero network.
        List<Clip> cached = new ArrayList<>();
        for (Clip c : clips) if (isCached(c)) cached.add(c);
        if (!cached.isEmpty()) {
            Clip pick = cached.size() > 1 ? cached.get(random.nextInt(cached.size())) : cached.get(0);
            if (cached.size() > 1 && pick == current) {
                pick = cached.get((cached.indexOf(pick) + 1) % cached.size());
            }
            playLocal(pick);
            prefetch();   // keep growing the pool in the background
            return;
        }
        // Nothing cached yet: download one (the still shows until it lands).
        Clip pick = clips.get(random.nextInt(clips.size()));
        android.util.Log.d("TvHome", "aerial video: nothing cached, fetching " + pick.name);
        kickDownload(pick);
    }

    /** Plays a fully-cached clip off the disk. */
    private void playLocal(Clip clip) {
        lastPick = clips.indexOf(clip);
        current = clip;
        releasePlayer();
        File f = cacheFile(clip.url);
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setDisplay(holder);
            player.setOnPreparedListener(p -> {
                main.removeCallbacks(prepareTimeout);
                setPlaying(true);          // a frame is coming: hide the still, show the video
                p.setLooping(false);
                p.start();
                android.util.Log.d("TvHome", "aerial video playing local: " + clip.name
                        + " (" + (f.length() / 1048576) + " MB)");
            });
            player.setOnCompletionListener(p -> playRandom());   // roll into the next landscape
            player.setOnErrorListener((p, what, extra) -> {
                failToStill("playback error " + what);
                return true;
            });
            player.prepareAsync();
            main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            failToStill(e.getMessage());
        }
    }

    /** Kick a background download unless one is already running. */
    private void kickDownload(Clip clip) {
        if (downloading) return;
        downloading = true;
        io.post(() -> downloadWithResume(clip));
    }

    /** After a clip lands, pull one more uncached one so rotation variety keeps growing. */
    private void prefetch() {
        List<Clip> uncached = new ArrayList<>();
        for (Clip c : clips) if (!isCached(c)) uncached.add(c);
        if (!uncached.isEmpty()) kickDownload(uncached.get(random.nextInt(uncached.size())));
    }

    /** Resumable download: .part + Range, up to DOWNLOAD_ATTEMPTS tries, then rename into place. */
    private void downloadWithResume(Clip clip) {
        File dst = cacheFile(clip.url);
        if (dst.isFile() && dst.length() > 0) {
            downloading = false;
            onDownloaded(clip);
            return;
        }
        File part = new File(cacheDir, dst.getName() + ".part");
        for (int attempt = 1; attempt <= DOWNLOAD_ATTEMPTS && !stopped; attempt++) {
            try {
                long have = part.isFile() ? part.length() : 0;
                HttpURLConnection conn = (HttpURLConnection) new URL(clip.url).openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                if (conn instanceof HttpsURLConnection) {
                    // Chinese CDN edges for sylvan.apple.com serve real content but a cert chain
                    // Android 7's 2016 trust store can't validate (NetCheck's lenient check gets
                    // HTTP 200, strict clients die on "trust anchor not found"). This fetch is
                    // public, non-sensitive video content, so pin nothing and trust-all — scoped
                    // strictly to this downloader, the rest of the app keeps default TLS.
                    javax.net.ssl.SSLSocketFactory lenient = trustAllFactory();
                    if (lenient != null) {
                        ((HttpsURLConnection) conn).setSSLSocketFactory(lenient);
                        ((HttpsURLConnection) conn).setHostnameVerifier((h, s) -> true);
                    }
                }
                if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
                int code = conn.getResponseCode();
                if (code != 200 && code != 206) {
                    conn.disconnect();
                    throw new java.io.IOException("HTTP " + code);
                }
                long total = have + conn.getContentLength();
                try (InputStream in = conn.getInputStream();
                     OutputStream out = new java.io.FileOutputStream(part, have > 0)) {
                    byte[] buf = new byte[16384];
                    long lastLog = have;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (stopped) { conn.disconnect(); downloading = false; return; }
                        out.write(buf, 0, n);
                        long now = have + part.length();
                        if (now - lastLog > 10_485_760L) {
                            lastLog = now;
                            android.util.Log.d("TvHome", "aerial video download: " + clip.name
                                    + " " + (now / 1048576) + (total > 0 ? "/" + (total / 1048576) : "") + " MB");
                        }
                    }
                }
                conn.disconnect();
                if (!part.renameTo(dst)) throw new java.io.IOException("rename failed");
                android.util.Log.d("TvHome", "aerial video downloaded: " + clip.name
                        + " (" + (dst.length() / 1048576) + " MB)");
                evict();
                downloading = false;
                main.post(() -> onDownloaded(clip));
                return;
            } catch (Exception e) {
                android.util.Log.w("TvHome", "aerial video download attempt " + attempt
                        + " failed (" + (part.length() / 1048576) + " MB kept): " + e);
                if (attempt < DOWNLOAD_ATTEMPTS && !stopped) {
                    try { Thread.sleep(5_000L); } catch (InterruptedException ie) { break; }
                }
            }
        }
        downloading = false;
        if (!stopped) main.post(() -> failToStill("download failed: " + clip.name));
    }

    /** A clip is on disk: if we're active but nothing is playing, roll it onto the screen. */
    private void onDownloaded(Clip clip) {
        if (stopped || playing || !surfaceReady) return;
        playLocal(clip);
        prefetch();
    }

    /** Trust-all SSL factory for the aerial CDN fetch (see downloadWithResume); null = default. */
    private static javax.net.ssl.SSLSocketFactory trustAllFactory() {
        try {
            javax.net.ssl.TrustManager[] tms = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        @Override public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                        @Override public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) { }
                        @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, tms, new java.security.SecureRandom());
            return sc.getSocketFactory();
        } catch (Exception e) {
            android.util.Log.w("TvHome", "lenient SSL unavailable: " + e);
            return null;
        }
    }

    /** Keep the cache under the ceiling: drop the oldest .mov files (never the playing one). */
    private void evict() {
        File[] movs = cacheDir.listFiles((d, name) -> name.endsWith(".mov"));
        if (movs == null) return;
        long total = 0;
        for (File f : movs) total += f.length();
        Arrays.sort(movs, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        int i = 0;
        while ((total > MAX_CACHE_BYTES || movs.length - i > MAX_CACHE_FILES) && i < movs.length) {
            File victim = movs[i++];
            if (current != null && victim.equals(cacheFile(current.url))) continue;
            total -= victim.length();
            // noinspection ResultOfMethodCallIgnored
            victim.delete();
            android.util.Log.d("TvHome", "aerial video cache evicted: " + victim.getName());
        }
    }

    /** No network / bad codec / too slow: back off to the static still (never a black screen). */
    private void failToStill(String why) {
        if (stopped) return;
        android.util.Log.w("TvHome.AerialBg", "fallback to still: " + why);
        main.removeCallbacks(prepareTimeout);
        releasePlayer();
        setPlaying(false);     // reveal the static still
        // Park the surface too: an idle SurfaceView composites as opaque black on some boxes
        // (Phicomm T1), which would cover the still even with the wallpaper layered correctly.
        surface.setVisibility(android.view.View.GONE);
        // Stay in active mode but wait before retrying — the cache may have grown meanwhile.
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
