package com.veil.launcher;

import android.content.Context;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.SurfaceHolder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
 * <p>
 * The clip list is never hard-written to a fixed size: we ship a bundled fallback playlist
 * (assets/videos.json — Apple's public Aerial CDN URLs), and on every launch we best-effort
 * fetch Apple's live manifest so the catalog auto-updates to whatever Apple currently publishes.
 * If the live fetch is unreachable (e.g. mainland-China CDN trouble) we quietly keep the bundled
 * list, so video never breaks.
 */
final class VideoEngine {

    static final VideoEngine INSTANCE = new VideoEngine();

    /** Give a clip this long to become playable before falling back. */
    private static final long PREPARE_TIMEOUT_MS = 12_000L;
    /** Wait this long after a failure before retrying the network. */
    private static final long RETRY_MS = 30_000L;
    /** How often we sample the playback position for diagnostics. */
    private static final long POSITION_TICK_MS = 5_000L;
    /** Network timeouts for the live catalog fetch. */
    private static final int FETCH_TIMEOUT_MS = 8_000;

    /**
     * Candidate Apple manifest URLs, tried in order. Apple has moved this endpoint at least twice
     * (V1 -> V2 -> itunes-assets), so we keep a list and take the first that answers. The legacy
     * /Aerials/2x/master.json is the historical one; add newer endpoints here as they appear.
     * Verified-on-device is required — see logcat tag "Veil" ("catalog auto-updated" vs
     * "using bundled playlist").
     */
    private static final String[] MANIFEST_URLS = {
            "https://sylvan.apple.com/Aerials/2x/master.json"
    };

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
    private final Object clipsLock = new Object();

    private Context ctx;
    private File catalogCache;
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

    /** Loads the playlist once, from the first context we see, then best-effort refreshes it. */
    void init(Context ctx) {
        if (this.ctx != null) return;
        this.ctx = ctx.getApplicationContext();
        loadPlaylist();   // bundled fallback (Apple's public Aerial CDN URLs)
        catalogCache = new File(this.ctx.getCacheDir(), "veil_catalog.json");
        loadCachedCatalog();   // if we fetched a live list before, reuse it offline
        refreshCatalogAsync(); // try to auto-update to Apple's current catalog right now
    }

    private void loadPlaylist() {
        if (ctx == null) return;
        try (InputStream in = ctx.getAssets().open("videos.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray a = new JSONArray(new String(bo.toByteArray(), StandardCharsets.UTF_8));
            synchronized (clipsLock) {
                for (int i = 0; i < a.length(); i++) {
                    JSONObject o = a.optJSONObject(i);
                    if (o == null) continue;
                    String url = o.optString("url", "");
                    if (!url.isEmpty()) clips.add(new Clip(o.optString("name", ""), url));
                }
            }
        } catch (Exception e) {
            // A missing or broken playlist just means no video (the still / clock stays).
            android.util.Log.w("Veil", "video playlist unreadable: " + e);
        }
    }

    // ---- live catalog refresh (auto-update) ----

    /** Fetches Apple's current manifest off the main thread; adopts it if it parses. */
    private void refreshCatalogAsync() {
        if (ctx == null) return;
        new Thread(() -> {
            for (String base : MANIFEST_URLS) {
                try {
                    String s = fetchText(base);
                    if (s == null) continue;
                    List<Clip> live = parseAppleManifest(s);
                    if (live != null && !live.isEmpty()) {
                        synchronized (clipsLock) {
                            clips.clear();
                            clips.addAll(live);
                        }
                        persistCache(live);
                        android.util.Log.i("Veil", "catalog auto-updated from " + base
                                + ": " + live.size() + " clips");
                        return;
                    }
                } catch (Exception e) {
                    android.util.Log.w("Veil", "catalog fetch failed for " + base + ": " + e);
                }
            }
            android.util.Log.i("Veil", "catalog: using bundled playlist (live fetch unavailable)");
        }).start();
    }

    /** Parses Apple's manifest into clips. Handles a bare array, or a resources->items tree. */
    private List<Clip> parseAppleManifest(String s) throws Exception {
        List<Clip> out = new ArrayList<>();
        Object root = new JSONTokener(s).nextValue();
        if (root instanceof JSONArray) {
            JSONArray arr = (JSONArray) root;
            for (int i = 0; i < arr.length(); i++) collectClip(arr.optJSONObject(i), out);
        } else if (root instanceof JSONObject) {
            JSONObject obj = (JSONObject) root;
            JSONArray res = obj.optJSONArray("resources");
            if (res != null) {
                int cap = Math.min(res.length(), 60);   // bound nested fetches
                for (int i = 0; i < cap; i++) {
                    JSONObject r = res.optJSONObject(i);
                    if (r == null) continue;
                    String ru = r.optString("url", "");
                    if (ru.isEmpty()) continue;
                    try {
                        String sub = fetchText(ru);
                        if (sub == null) continue;
                        Object subRoot = new JSONTokener(sub).nextValue();
                        if (subRoot instanceof JSONObject) {
                            collectItems(((JSONObject) subRoot).optJSONArray("items"), out);
                            collectAssets(((JSONObject) subRoot).optJSONObject("assets"), out);
                        }
                    } catch (Exception ignore) {
                        // one bad resource shouldn't sink the whole catalog
                    }
                }
            }
        }
        return out;
    }

    private void collectItems(JSONArray items, List<Clip> out) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) collectClip(items.optJSONObject(i), out);
    }

    private void collectAssets(JSONObject assets, List<Clip> out) {
        if (assets == null) return;
        for (int i = 0; i < assets.length(); i++) {
            Object v = assets.opt(assets.names().optString(i));
            if (v instanceof JSONObject) collectClip((JSONObject) v, out);
        }
    }

    /** Pulls name + url out of a clip-shaped object using the various keys Apple uses. */
    private void collectClip(JSONObject o, List<Clip> out) {
        if (o == null) return;
        String url = o.optString("url", "");
        if (url.isEmpty()) return;
        if (!url.endsWith(".mov") && !url.contains("Aerials") && !url.contains("itunes-assets")) return;
        String name = o.optString("name", o.optString("assetName",
                o.optString("location", o.optString("id", ""))));
        out.add(new Clip(name, url));
    }

    /** GETs text; tries HTTPS then falls back to HTTP (some boxes/CDN edges balk at the cert). */
    private String fetchText(String u) {
        try {
            String body = getUrl(new URL(u));
            if (body == null && u.startsWith("https://")) {
                body = getUrl(new URL("http://" + u.substring(8)));
            }
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private String getUrl(URL url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(FETCH_TIMEOUT_MS);
            c.setReadTimeout(FETCH_TIMEOUT_MS);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) try {
                c.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    private void persistCache(List<Clip> live) {
        if (catalogCache == null) return;
        try {
            JSONArray a = new JSONArray();
            for (Clip c : live) {
                JSONObject o = new JSONObject();
                o.put("name", c.name);
                o.put("url", c.url);
                a.put(o);
            }
            try (Writer w = new FileWriter(catalogCache)) {
                w.write(a.toString());
            }
        } catch (Exception ignore) {
            // Cache is a nice-to-have; never fatal.
        }
    }

    private void loadCachedCatalog() {
        if (catalogCache == null || !catalogCache.exists()) return;
        try {
            byte[] b = Files.readAllBytes(catalogCache.toPath());
            JSONArray a = new JSONArray(new String(b, StandardCharsets.UTF_8));
            List<Clip> cached = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String u = o.optString("url", "");
                if (u.isEmpty()) continue;
                cached.add(new Clip(o.optString("name", ""), u));
            }
            if (!cached.isEmpty()) {
                synchronized (clipsLock) {
                    clips.clear();
                    clips.addAll(cached);
                }
                android.util.Log.i("Veil", "catalog loaded from cache: " + cached.size() + " clips");
            }
        } catch (Exception ignore) {
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
        int size;
        synchronized (clipsLock) {
            size = clips.size();
        }
        if (size == 0 || !started) return;
        int pick;
        if (step == 0) {
            pick = size > 1 ? random.nextInt(size) : 0;
            if (pick >= lastPick && lastPick >= 0) pick = (pick + 1) % size;
        } else {
            pick = ((lastPick < 0 ? 0 : lastPick) + step + size) % size;
        }
        lastPick = pick;
        Clip clip;
        synchronized (clipsLock) {
            clip = clips.get(pick);
        }
        playClip(clip, 0L, false);
    }

    /**
     * Streams {@code clip} straight from its URL. Tries HTTPS first; if the decoder or transport
     * fails, retries the plain-HTTP variant once before giving up to the fallback. The same stream
     * is reused across desktop and screensaver, so there is never a second decode.
     */
    private void playClip(Clip clip, long seekMs, boolean viaHttp) {
        if (!started) return;
        lastPick = indexOf(clip);
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

    private int indexOf(Clip clip) {
        synchronized (clipsLock) {
            return clips.indexOf(clip);
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

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Clip)) return false;
            return url.equals(((Clip) o).url);
        }

        @Override
        public int hashCode() {
            return url.hashCode();
        }
    }
}
