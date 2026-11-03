package com.veil.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceHolder;

import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * One process-wide ExoPlayer that feeds BOTH the desktop video background and the video
 * screensaver. The two are just surfaces that take turns owning the player's output: switching
 * between them is a single setVideoSurfaceHolder() call, so the stream never re-buffers or
 * restarts — it flows continuously from desktop to screensaver and back, at a single decode (one
 * stream of CDN traffic, not two). Only one surface is attached at a time; the idle side simply
 * isn't rendered while the player keeps decoding, which is what makes the hand-off instant.
 * <p>
 * ExoPlayer (not the platform MediaPlayer) is what makes this work across TV boxes: some vendor
 * ROMs (TCL mt5879) ship a MediaPlayer whose HTTP source signals EOS right after fetching the
 * tail-side moov atom, so non-fast-start MP4s — every Apple aerial — "prepare" and then never
 * render a frame. ExoPlayer re-opens the tail range for the moov and reads the mdat from
 * offset 0; its HTTP stack also runs in-process (Java TLS), sidestepping the native media TLS
 * hang seen on the T1.
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
    private static final long PREPARE_TIMEOUT_MS = 30_000L;
    /** Wait this long after a failure before retrying the network. */
    private static final long RETRY_MS = 30_000L;
    /** Consecutive playback errors with no successful prepare in between → whole-network outage. */
    private static final int MAX_ERROR_STREAK = 5;
    /** How often we sample the playback position for diagnostics. */
    private static final long POSITION_TICK_MS = 5_000L;
    /** Network timeouts for the live catalog fetch. */
    private static final int FETCH_TIMEOUT_MS = 8_000;

    /**
     * Apple's public Aerial manifest, tried in order. The plain JSON feed (Aerials/2x/entries.json)
     * is the only simple, parseable endpoint Apple still serves; it returns ~13 clips (a rotating
     * "initial" subset). The fuller catalogs live inside .tar/.plist bundles that are too heavy to
     * unpack on a TV box, so we MERGE whatever this returns on top of the bundled 114-clip list
     * (dedupe by URL) rather than replace it — auto-update adds new clips without ever shrinking.
     * Add newer endpoints here as Apple shifts them. Verified-on-device via logcat tag "Veil"
     * ("catalog live-fetch from ... +N new" vs "using bundled playlist" when unreachable).
     */
    private static final String[] MANIFEST_URLS = {
            "https://sylvan.apple.com/Aerials/2x/entries.json"
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
    /** The clips adopted from the live manifest (or its cache) — the auto-updated part. */
    private final List<Clip> cachedClips = new ArrayList<>();
    private final Object clipsLock = new Object();
    /** Local video files (sdcard/Veil/videos) — scanned from the settings panel. */
    private final List<Clip> localClips = new ArrayList<>();
    /** The owner's own video URLs (settings panel) — merged into the playlist. */
    private final List<Clip> urlClips = new ArrayList<>();

    private Context ctx;
    private File catalogCache;
    private ExoPlayer player;
    private SurfaceHolder desktopHolder, dreamHolder;
    private Listener desktopListener, dreamListener;
    private Stage stage = Stage.DESKTOP;
    private boolean dreamActive;
    private Clip current;
    private int lastPick = -1;
    private boolean playing;
    private boolean started;
    /**
     * The player's shuffled playlist. Clips live IN the player as a list with REPEAT_ALL, so the
     * transition from one aerial to the next is a decoder-level hand-off: ExoPlayer preloads the
     * next item while the current one plays and the last frame never leaves the surface — no
     * black gap between clips (the old setMediaItem-per-clip flow cleared the output and left the
     * screensaver black for the whole prepare of the next clip, ~10 s on a cold CDN edge).
     */
    private final List<Clip> queue = new ArrayList<>();
    /** Clips whose HTTPS form was already tried after an HTTP error — one retry each, then skip. */
    private final Set<String> httpsTried = new HashSet<>();
    /** The clip + transport options currently being prepared/played — the shared listener reads these. */
    private boolean pendingViaHttp;
    private long pendingSeekMs;
    private long prepareBegan;
    /** Built once and reused across every clip so a rotation never re-creates the decoder. */
    private final Player.Listener engineListener = new Player.Listener() {
        @Override
        public void onPlaybackStateChanged(int state) {
            if (player == null) return;   // released mid-flight; stale callback
            if (state == Player.STATE_READY) {
                main.removeCallbacks(prepareTimeout);
                timeoutHops = 0;
                errorStreak = 0;
                android.util.Log.i("Veil", "video prepared in "
                        + (SystemClock.elapsedRealtime() - prepareBegan) / 1000
                        + "s: " + (current != null ? current.name : "?"));
                setPlaying(true);
                if (current != null) {
                    WallpaperState.publish(current.url, pendingSeekMs);
                    notifyClip(current.name);
                }
                main.post(positionTick);
            } else if (state == Player.STATE_ENDED) {
                buildQueue();   // repeat mode should prevent this; a reshuffle is a safe recovery
            }
        }

        @Override
        public void onMediaItemTransition(androidx.media3.common.MediaItem mediaItem, int reason) {
            // The player moved to the next playlist item (seamless hand-off): update what "current"
            // means so the position tick and the caption follow the new aerial.
            int idx = player != null ? player.getCurrentMediaItemIndex() : -1;
            if (idx >= 0 && idx < queue.size()) {
                current = queue.get(idx);
                prepareBegan = SystemClock.elapsedRealtime();
                pendingSeekMs = 0L;
                WallpaperState.publish(current.url, 0L);
                notifyClip(current.name);
                android.util.Log.i("Veil", "seamless transition to: " + current.name);
            }
        }

        @Override
        public void onPlayerError(PlaybackException e) {
            if (player == null) return;
            errorStreak++;
            if (errorStreak >= MAX_ERROR_STREAK) {
                // The whole network is down (not one bad clip): stop churning through the
                // playlist one drop at a time and fall back until the retry timer.
                failToStill(e.getErrorCodeName() + " x" + errorStreak);
                return;
            }
            android.util.Log.w("Veil", "video error " + e.getErrorCodeName()
                    + " after " + (SystemClock.elapsedRealtime() - prepareBegan) / 1000 + "s"
                    + " (streak " + errorStreak + ")");
            int idx = player.getCurrentMediaItemIndex();
            Clip bad = idx >= 0 && idx < queue.size() ? queue.get(idx) : current;
            if (bad == null) return;
            // One cheap retry over the other transport (TLS cert failures on some CDN edges,
            // networks that block plain HTTP): swap the item in place via replaceMediaItem and
            // re-prepare. NOTE: setMediaItem here would WIPE the whole playlist down to one
            // item, and the next failure would then read count==1 and tear the engine down
            // over a single network blip.
            if (!httpsTried.contains(bad.url) && bad.url.startsWith("http")) {
                httpsTried.add(bad.url);
                String alt = bad.url.startsWith("https://")
                        ? toHttp(bad.url)
                        : bad.url.replaceFirst("^http://", "https://");
                android.util.Log.w("Veil", "video transport failed, retrying over "
                        + (alt.startsWith("https") ? "https" : "http") + ": " + bad.name);
                player.replaceMediaItem(idx, androidx.media3.common.MediaItem.fromUri(
                        playable(alt)));
                player.setPlayWhenReady(true);
                player.prepare();
                prepareBegan = SystemClock.elapsedRealtime();
                main.removeCallbacks(prepareTimeout);
                main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
            } else {
                // Already retried on the other transport: drop the dead clip and move on.
                android.util.Log.w("Veil", "video retry also failed, dropping clip: " + bad.name);
                int count = player.getMediaItemCount();
                if (count > 1) {
                    player.removeMediaItem(idx);
                    queue.remove(idx);
                    prepareBegan = SystemClock.elapsedRealtime();
                    main.removeCallbacks(prepareTimeout);
                    main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
                } else {
                    failToStill("playback error " + e.getErrorCodeName());
                }
            }
        }
    };
    /** Consecutive prepare-timeout hops without a success; caps the skip-ahead ladder. */
    private int timeoutHops;
    /** Consecutive playback errors since the last successful prepare — network-outage detector. */
    private int errorStreak;
    private final Runnable prepareTimeout = new Runnable() {
        @Override
        public void run() {
            // A hanging clip (dead CDN file, throttled edge) is SKIPPED, not retried: the
            // catalog is large, the next clip usually prepares in seconds. Two skips in a row
            // mean the network itself is gone — fall back to the still.
            if (timeoutHops < 2) {
                timeoutHops++;
                android.util.Log.w("Veil", "video prepare timeout, hop to the next clip");
                playAnother(1);
            } else {
                failToStill("prepare timeout");
            }
        }
    };

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
        scanLocalVideos();   // pick up /sdcard/Veil/videos files right away (needs storage grant)
        setCustomUrls(new PrefsStore(ctx).loadCustomVideoUrls());
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

    /**
     * Fetches Apple's current manifest off the main thread; adopts it if it parses. Every
     * candidate clip is probed for existence first — Apple's live feed lists files the CDN
     * no longer serves (verified 2026-10: 13/13 entries.json videos answer 404), and a dead
     * clip in the rotation means a 30 s hang whenever it is picked.
     */
    private void refreshCatalogAsync() {
        if (ctx == null) return;
        new Thread(() -> {
            HashMap<String, Boolean> memo = new HashMap<>();
            List<Clip> adopted = null;
            for (String base : MANIFEST_URLS) {
                try {
                    String s = fetchText(base);
                    if (s == null) continue;
                    List<Clip> live = parseAppleManifest(s);
                    if (live != null && !live.isEmpty()) {
                        adopted = filterAlive(live, memo);
                        android.util.Log.i("Veil", "catalog live-fetch from " + base + ": "
                                + live.size() + " seen, " + (live.size() - adopted.size())
                                + " dead, " + adopted.size() + " usable");
                        break;
                    }
                } catch (Exception e) {
                    android.util.Log.w("Veil", "catalog fetch failed for " + base + ": " + e);
                }
            }
            // Re-validate the previously adopted clips too (a feed that was healthy when stored
            // can rot), drop the dead ones from the rotation and rewrite the cache clean.
            List<Clip> cached;
            synchronized (clipsLock) {
                cached = new ArrayList<>(cachedClips);
            }
            List<Clip> aliveCached = filterAlive(cached, memo);
            boolean cacheChanged = aliveCached.size() != cached.size();
            int added = 0;
            synchronized (clipsLock) {
                if (adopted != null) {
                    for (Clip c : adopted) if (!clips.contains(c)) { clips.add(c); added++; }
                }
                for (Clip c : cached) {
                    if (!aliveCached.contains(c) && clips.contains(c)) clips.remove(c);
                }
            }
            if (added > 0 || cacheChanged) {
                List<Clip> persist = new ArrayList<>(aliveCached);
                if (adopted != null) {
                    for (Clip c : adopted) if (!persist.contains(c)) persist.add(c);
                }
                persistCache(persist);
            }
            if (added == 0 && adopted == null && !cacheChanged) {
                android.util.Log.i("Veil", "catalog: using bundled playlist (live fetch unavailable)");
            }
        }).start();
    }

    /** Keeps only clips whose URL still answers; network trouble counts as alive (no proof, no drop). */
    private List<Clip> filterAlive(List<Clip> in, HashMap<String, Boolean> memo) {
        List<Clip> out = new ArrayList<>();
        for (Clip c : in) if (alive(c.url, memo)) out.add(c);
        return out;
    }

    private boolean alive(String url, HashMap<String, Boolean> memo) {
        Boolean m = memo.get(url);
        if (m != null) return m;
        boolean a = probeAlive(url);
        memo.put(url, a);
        return a;
    }

    /** 500-byte range probe; 200/206 = exists, anything else = drop. */
    private boolean probeAlive(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Range", "bytes=0-500");
            int code = c.getResponseCode();
            if (code != 200 && code != 206) {
                android.util.Log.w("Veil", "catalog: clip answers HTTP " + code + ", dropping: " + url);
            }
            return code == 200 || code == 206;
        } catch (Exception e) {
            return true;   // offline / blocked probe proves nothing about the file
        } finally {
            if (c != null) try {
                c.disconnect();
            } catch (Exception ignore) {
            }
        }
    }

    /** Parses Apple's manifest into clips. Handles a bare array, the modern assets[] feed,
     *  or a legacy resources->items/assets tree. */
    private List<Clip> parseAppleManifest(String s) throws Exception {
        List<Clip> out = new ArrayList<>();
        Object root = new JSONTokener(s).nextValue();
        if (root instanceof JSONArray) {
            JSONArray arr = (JSONArray) root;
            for (int i = 0; i < arr.length(); i++) collectClip(arr.optJSONObject(i), out);
        } else if (root instanceof JSONObject) {
            JSONObject obj = (JSONObject) root;
            // Modern feed: "assets": [ { id, accessibilityLabel, url-1080-SDR, url-4K-SDR, ... } ]
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) collectAsset(assets.optJSONObject(i), out);
            }
            // Legacy feed: "resources": [ { url }, ... ] each pointing to a sub-manifest.
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

    /** Pulls name + best URL variant out of an asset-shaped object (url-1080-SDR, etc.). */
    private void collectAsset(JSONObject a, List<Clip> out) {
        if (a == null) return;
        String url = bestUrl(a);
        if (url == null) return;
        String name = a.optString("accessibilityLabel",
                a.optString("name", a.optString("id", "")));
        out.add(new Clip(name, url));
    }

    /** Picks the most broadly-decodable variant: 1080p SDR HEVC first (matches the bundled
     *  /Aerials/2x/Videos/*.mov we already know these boxes decode), then 4K, then any url-*.mov. */
    private String bestUrl(JSONObject a) {
        String[] prefs = {"url-1080-SDR", "url-1080-H264", "url-4K-SDR", "url-4K-H264"};
        for (String k : prefs) {
            String u = a.optString(k, "");
            if (u.endsWith(".mov")) return u;
        }
        JSONArray names = a.names();
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                String k = names.optString(i);
                if (k.startsWith("url")) {
                    String u = a.optString(k, "");
                    if (u.endsWith(".mov")) return u;
                }
            }
        }
        String u = a.optString("url", "");
        return u.isEmpty() ? null : u;
    }

    private void collectItems(JSONArray items, List<Clip> out) {
        if (items == null) return;
        for (int i = 0; i < items.length(); i++) collectAsset(items.optJSONObject(i), out);
    }

    private void collectAssets(JSONObject assets, List<Clip> out) {
        if (assets == null) return;
        for (int i = 0; i < assets.length(); i++) {
            Object v = assets.opt(assets.names().optString(i));
            if (v instanceof JSONObject) collectAsset((JSONObject) v, out);
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

    /** Reads a file's bytes with the legacy stream API (File.toPath()/Files needs API 26+, which
     *  older TV boxes we still support don't have). */
    private static byte[] readAllBytes(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    private void loadCachedCatalog() {
        if (catalogCache == null || !catalogCache.exists()) return;
        try {
            byte[] b = readAllBytes(catalogCache);
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
                cachedClips.addAll(cached);
                int added;
                synchronized (clipsLock) {
                    int before = clips.size();
                    for (Clip c : cached) if (!clips.contains(c)) clips.add(c);
                    added = clips.size() - before;
                }
                if (added > 0)
                    android.util.Log.i("Veil", "catalog restored from cache: +" + added
                            + " live clips, total " + clips.size());
            }
        } catch (Exception ignore) {
        }
    }

    // ---- custom video sources (local files + the owner's own URLs) -------------------------------

    /** Where the owner drops video files: /sdcard/Veil/videos (push via adb, USB manager, LocalSend). */
    static File localVideoDir() {
        return new File(android.os.Environment.getExternalStorageDirectory(), "Veil/videos");
    }

    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".mkv", ".webm", ".mov", ".m4v"};

    /**
     * Rescans the local video folder and returns how many playable files were found, or -1 when
     * the folder cannot be read (no storage permission / no storage). Local clips keep the
     * playlist position of the catalogue: they are merged in alongside the aerials.
     */
    int scanLocalVideos() {
        File dir = localVideoDir();
        List<Clip> found = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files == null) return -1;
        java.util.Arrays.sort(files);
        for (File f : files) {
            if (!f.isFile()) continue;
            String n = f.getName().toLowerCase(java.util.Locale.US);
            for (String ext : VIDEO_EXTENSIONS) {
                if (n.endsWith(ext)) {
                    found.add(new Clip(f.getName(), f.getAbsolutePath()));
                    break;
                }
            }
        }
        synchronized (clipsLock) {
            localClips.clear();
            localClips.addAll(found);
        }
        android.util.Log.i("Veil", "local video scan: " + found.size() + " files in " + dir);
        syncCustomClips();
        return found.size();
    }

    int localVideoCount() {
        synchronized (clipsLock) {
            return localClips.size();
        }
    }

    int customUrlCount() {
        synchronized (clipsLock) {
            return urlClips.size();
        }
    }

    /** Replaces the custom-URL list with {@code urls} and merges the result into the playlist. */
    void setCustomUrls(java.util.Set<String> urls) {
        List<Clip> found = new ArrayList<>();
        if (urls != null) {
            for (String u : urls) {
                String t = u == null ? "" : u.trim();
                if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("/")) {
                    found.add(new Clip(t.substring(Math.max(0, t.lastIndexOf('/') + 1)), t));
                }
            }
        }
        synchronized (clipsLock) {
            urlClips.clear();
            urlClips.addAll(found);
        }
        syncCustomClips();
    }

    /** The previous union of custom clips, so a rescan knows what to drop from the playlist. */
    private final List<Clip> customs = new ArrayList<>();

    /** Reconciles the playlist with the current custom clips: adds new, removes the gone. */
    private void syncCustomClips() {
        List<Clip> now = new ArrayList<>();
        synchronized (clipsLock) {
            now.addAll(localClips);
            now.addAll(urlClips);
        }
        List<Clip> added = new ArrayList<>();
        List<Clip> removed = new ArrayList<>();
        synchronized (clipsLock) {
            for (Clip old : customs) if (!now.contains(old) && clips.contains(old)) removed.add(old);
            for (Clip c : now) if (!clips.contains(c)) added.add(c);
            for (Clip c : removed) clips.remove(c);
            clips.addAll(added);
            customs.clear();
            customs.addAll(now);
        }
        // Live update of a playing playlist: append the new items, drop the gone ones in place —
        // the current clip keeps playing, the queue just grows/shrinks around it.
        if (player != null && started) {
            try {
                if (!removed.isEmpty()) {
                    java.util.HashSet<String> gone = new java.util.HashSet<>();
                    for (Clip c : removed) gone.add(c.url);
                    for (int i = queue.size() - 1; i >= 0; i--) {
                        if (gone.contains(queue.get(i).url) && i != player.getCurrentMediaItemIndex()) {
                            player.removeMediaItem(i);
                            queue.remove(i);
                        }
                    }
                }
                if (!added.isEmpty()) {
                    List<MediaItem> items = new ArrayList<>();
                    synchronized (clipsLock) {
                        for (Clip c : added) items.add(MediaItem.fromUri(playable(c.url)));
                        player.addMediaItems(items);
                        queue.addAll(added);
                    }
                }
            } catch (Exception e) {
                android.util.Log.w("Veil", "playlist live-update failed: " + e);
            }
        }
        android.util.Log.i("Veil", "custom clips synced: +" + added.size() + " -" + removed.size()
                + ", total " + (clips.size()));
    }

    /** Turns a clip URL into a playable form: bare file paths become file:// URIs. */
    private static String playable(String url) {
        if (url.startsWith("/") ) return "file://" + url;
        // Apple's China CDN edge (sylvan.apple.com → ksyun) serves a TLS chain Android TV
        // cannot validate ("Trust anchor for certification path not found"; desktop curl fails
        // the same check). Its plain-HTTP endpoint serves the same files without TLS, so the
        // aerials always ride HTTP. User-added custom URLs are left untouched.
        if (url.startsWith("https://sylvan.apple.com/")) return toHttp(url);
        return url;
    }

    // ---- surface ownership ----

    void setDesktopHolder(SurfaceHolder h, Listener l) {
        desktopHolder = h;
        desktopListener = l;
        if (!dreamActive) {
            applyStage();
            if (started && playing) notifyActive();
            else if (started && !playing && player == null) playRandom();
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
            else if (started && !playing && player == null) playRandom();
        }
    }

    void clearDreamHolder() {
        dreamHolder = null;
        if (dreamActive) applyStage();
    }

    /** Switch the rendered output to the desktop surface (dream ends / app resumes). */
    void useDesktop() {
        // While the launcher's own screensaver is mirroring the video, it owns the engine — a stray
        // MainActivity.onResume() (lifecycle races when the saver is launched from outside, or a brief
        // resume while it is up) must NOT yank the output back to the desktop surface, which would
        // leave the saver showing only its black surface while the clip decodes unseen behind it.
        if (ScreensaverActivity.showing) return;
        dreamActive = false;
        stage = Stage.DESKTOP;
        applyStage();
        // Back from a background hold (another app was in front): the player is still warm and
        // merely paused — resume it. The current frame lands on the re-attached surface within
        // milliseconds, so the desktop never falls back to the still wallpaper.
        if (player != null && started) {
            try {
                player.setPlayWhenReady(true);
            } catch (Exception ignore) {
            }
        }
        if (started && playing) notifyActive();
        else if (started && !playing && player == null) playRandom();
    }

    /** Switch the rendered output to the screensaver surface (dream begins). */
    void useDream() {
        dreamActive = true;
        stage = Stage.DREAM;
        applyStage();
        if (started && playing) notifyActive();
        else if (started && !playing && player == null) playRandom();
    }

    /** Point the player at whichever surface currently owns it. */
    private void applyStage() {
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        if (player != null) {
            try {
                // h may be null mid hand-off: keep decoding, attach on the next setXHolder().
                player.setVideoSurfaceHolder(h);
            } catch (Exception ignore) {
            }
        }
    }

    /** Tell the active listener its playing state — only when its surface is actually ready. */
    private void notifyActive() {
        Listener l = dreamActive ? dreamListener : desktopListener;
        // Note: notify even when the holder is momentarily null. Some ROMs (TCL mt5879) reap a
        // GONE SurfaceView's surface a second or two after it is created, so by STATE_READY the
        // holder can already be cleared — and the listener's whole job is to flip the video view
        // VISIBLE, which re-creates the surface and hands it back to the player via applyStage().
        // Gating on h != null here swallowed the "playing" signal and left the still on screen
        // forever while the video decoded into a detached surface.
        if (l != null) l.onPlayingChanged(playing);
    }

    // ---- lifecycle ----

    /** Begin (or resume) playback; a no-op until a surface is attached. */
    void start() {
        started = true;
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        // player != null covers the preparing state too: two start() calls in quick succession
        // (onResume + a wallpaper refresh) used to race here, the second one releasing the
        // first player mid-prepare and — on a flaky CDN — throwing away the working clip.
        if (h != null && !playing && player == null) playRandom();
    }

    /** Stop and release the decoder (desktop hidden, or video wallpaper turned off). */
    void pause() {
        started = false;
        releasePlayer();
        setPlaying(false);
        WallpaperState.clear();
    }

    /**
     * Desktop hidden behind an opaque app: pause playback but KEEP the player warm. The old
     * flow called {@link #pause()} here, which tore the whole player down — every return to the
     * desktop then spent seconds re-preparing and cross-fading from the still wallpaper. With a
     * hold, coming home re-attaches the surface and resumes inside the same clip: instant.
     * The decoder's memory is reclaimed by {@link #pause()} when the video wallpaper is actually
     * switched off, and by {@link #release()} on process teardown.
     */
    void backgroundHold() {
        if (player == null) return;
        try {
            player.setPlayWhenReady(false);   // stop decode + network, keep playlist & position
            player.setVideoSurfaceHolder(null);   // the desktop surface is about to be torn down
        } catch (Exception ignore) {
        }
        // started and playing stay true on purpose: the UI keeps the video view shown, and
        // useDesktop() resumes playback and re-notifies without a state flip.
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
        buildQueue();
    }

    /**
     * Shuffles every clip into the player as ONE playlist with REPEAT_ALL, then starts at a random
     * item. From here on the player walks the list itself and preloads each next clip in advance,
     * so clip-to-clip transitions are seamless (see the {@code queue} field note).
     */
    private void buildQueue() {
        List<Clip> snapshot;
        synchronized (clipsLock) {
            snapshot = new ArrayList<>(clips);
        }
        if (snapshot.isEmpty() || !started) return;
        Collections.shuffle(snapshot, random);
        queue.clear();
        queue.addAll(snapshot);
        httpsTried.clear();
        errorStreak = 0;
        current = snapshot.get(0);
        lastPick = 0;
        pendingViaHttp = true;
        pendingSeekMs = 0L;
        prepareBegan = SystemClock.elapsedRealtime();
        android.util.Log.i("Veil", "video queue built: " + queue.size() + " clips");
        try {
            if (player == null) buildPlayer();
            List<MediaItem> items = new ArrayList<>();
            for (Clip c : queue) items.add(MediaItem.fromUri(playable(c.url)));
            player.setMediaItems(items, /* startPositionIndex= */ 0, /* startPositionMs= */ 0L);
            player.setRepeatMode(Player.REPEAT_MODE_ALL);
            player.setPlayWhenReady(true);
            player.prepare();
            main.removeCallbacks(prepareTimeout);
            main.postDelayed(prepareTimeout, PREPARE_TIMEOUT_MS);
        } catch (Exception e) {
            android.util.Log.w("Veil", "video queue start failed: " + e);
            failToStill(e.getMessage());
        }
    }

    /**
     * Steps to the neighbouring playlist item. Because the player preloads it while the current
     * clip finishes, this is a decode-level hand-off — the frame never leaves the surface.
     */
    private void playAnother(int step) {
        if (player == null || queue.isEmpty() || !started) {
            buildQueue();
            return;
        }
        prepareBegan = SystemClock.elapsedRealtime();
        if (step >= 0) player.seekToNextMediaItem();
        else player.seekToPreviousMediaItem();
    }

    /** Builds the single shared ExoPlayer exactly once and wires the listener. */
    private void buildPlayer() {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(5_000)
                .setReadTimeoutMs(5_000);
        player = new ExoPlayer.Builder(ctx, new DefaultMediaSourceFactory(
                new DefaultDataSource.Factory(ctx, http))).build();
        player.setVideoSurfaceHolder(dreamActive ? dreamHolder : desktopHolder);
        player.addListener(engineListener);
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
                long pos = player.getCurrentPosition();
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
        timeoutHops = 0;
        errorStreak = 0;
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
        SurfaceHolder h = dreamActive ? dreamHolder : desktopHolder;
        Listener l = dreamActive ? dreamListener : desktopListener;
        android.util.Log.i("Veil", "setPlaying(" + on + ") notify h=" + (h != null)
                + " l=" + (l != null) + " dream=" + dreamActive);
        if (l != null) l.onPlayingChanged(on);
    }

    private void releasePlayer() {
        main.removeCallbacks(prepareTimeout);
        main.removeCallbacks(positionTick);
        if (player != null) {
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
