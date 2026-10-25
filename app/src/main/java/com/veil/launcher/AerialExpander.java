package com.veil.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-tap aerial-stills expander: pulls a never-used clip from the bundled playlist, downloads
 * the head of Apple's 2K stream with a plain HTTP range request, and grabs a few frames off the
 * local copy with the system MediaMetadataRetriever (no ffmpeg on the box). The frames are filed
 * into files/aerials/ next to the stills pushed from the PC.
 * <p>Download-then-decode replaced streaming the URL straight into the retriever: several box
 * ROMs (Android 7 especially) fail that path outright, and the error came back as a silent
 * "0 frames added". The download also gets real timeouts, so a dead network reports itself
 * instead of hanging. A clip is only remembered as used when it actually produced frames, so
 * failures can be retried instead of quietly burning the playlist.
 * <p>Head-only is not enough by itself: Apple's aerial .mov files keep their index (moov atom)
 * at the END of a 200-800 MB file, so a truncated head copy has no moov and every frame grab
 * came back empty ("downloaded fine, no pictures"). makeFastStart fetches the moov from the
 * tail, rebases its chunk offsets, and splices it in front of the downloaded media data —
 * the classic fast-start layout, minus the hundreds of megabytes we never needed.
 */
final class AerialExpander {
    private static final String TAG = "TvHome.AerialExpand";

    /** Frame grab points in microseconds, all inside the first 10 s of the head we download. */
    private static final long[] FRAME_US = {0L, 3_000_000L, 6_000_000L, 9_000_000L};
    private static final int STILL_W = 1920, STILL_H = 1080;
    private static final int STILL_W_4K = 3840, STILL_H_4K = 2160;
    /** Bytes pulled per clip: ~10 s of a 2K stream plus keyframe headroom; later grabs skip nulls. */
    private static final int MAX_CLIP_BYTES = 48 * 1024 * 1024;
    /** 4K HEVC runs ~25-40 Mbps: a longer head for the same 0-9 s window. */
    private static final int MAX_CLIP_BYTES_4K = 80 * 1024 * 1024;
    /** Head bytes parsed for the top-level atom layout (ftyp/wide/mdat…). */
    private static final int PROBE_BYTES = 256;
    /** Tail bytes fetched when hunting the moov atom (it sits at the end of these files). */
    private static final int TAIL_MAX = 8 * 1024 * 1024;
    /**
     * Apple's official tvOS 13+ catalog: the only aerials that exist as 4K masters (13 clips).
     * The bundled 114-clip playlist is the older tvOS 12 catalog, AVC 1080p only.
     */
    private static final String ENTRIES_URL = "https://sylvan.apple.com/Aerials/2x/entries.json";
    /** Clips tried per pass: one dead URL must not eat the whole run. */
    private static final int MAX_TRIES = 3;
    private static final Pattern NUM = Pattern.compile("aerial_(\\d+)\\.jpg");

    /** Error codes handed back with onDone; the settings panel maps them to its own strings. */
    static final String ERR_NET = "net";
    static final String ERR_TIMEOUT = "timeout";
    static final String ERR_DECODE = "decode";
    static final String ERR_EXHAUSTED = "exhausted";

    /** Result of one run, on the main thread: added &gt; 0 with error == null on success. */
    interface Callback {
        void onDone(int added, int total, String error, String detail);
    }

    private static volatile boolean running;

    private final Context app;
    private final PrefsStore prefs;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The lowest-level reason the last clip produced nothing — surfaced into the toast. */
    private volatile String detail;

    private AerialExpander(Context ctx, Callback cb) {
        this.app = ctx.getApplicationContext();
        this.prefs = new PrefsStore(this.app);
        this.cb = cb;
    }

    /** Starts one expansion pass. Extra taps while a pass runs are ignored. */
    static void expand(Context ctx, Callback cb) {
        if (running) {
            cb.onDone(0, 0, "busy", null);
            return;
        }
        running = true;
        new AerialExpander(ctx, cb).run();
    }

    private void run() {
        new Thread(() -> {
            int added = 0, total = 0;
            String error = null;
            detail = null;
            try {
                File dir = Config.dir(app);
                if (dir == null) throw new IllegalStateException("no files dir");
                File stills = new File(dir, "aerials");
                if (!stills.exists() && !stills.mkdirs()) throw new IllegalStateException("mkdir failed");
                File[] have = stills.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                total = have == null ? 0 : have.length;

                // Resolution: the owner's pick, or follow the display (4K panel → 4K stills).
                int res = prefs.loadAerialStillRes();
                android.util.DisplayMetrics dm = app.getResources().getDisplayMetrics();
                boolean want4k = res == 2160
                        || (res == 0 && Math.max(dm.widthPixels, dm.heightPixels) >= 3000);

                for (int tries = 0; tries < MAX_TRIES; tries++) {
                    boolean mode4k = want4k;
                    String url = null;
                    if (want4k) {
                        url = pick4kClip();
                        if (url == null) mode4k = false;   // no live 4K clip: the 1080p playlist takes over
                    }
                    if (url == null) url = pickClip();
                    if (url == null) {
                        if (error == null) error = ERR_EXHAUSTED;
                        break;
                    }
                    int got = grabFrames(url, stills, nextIndex(have) + added, mode4k);
                    if (got > 0) {
                        // Only a clip that actually produced stills counts as used.
                        prefs.addAerialUsedUrl(baseName(url));
                        added += got;
                        error = null;
                        break;
                    }
                    // This clip failed (dead URL, no frames): keep its playlist slot and try the next.
                    Log.w(TAG, "clip produced nothing, trying another: " + url);
                    error = ERR_DECODE;
                }
                // The video path is a lost cause on some ROMs (stagefright quirks no container
                // fix can reach): fall back to a plain still-photo source, which cannot fail to
                // decode — the expansion ends with real pictures instead of a dead message.
                if (added == 0 && !ERR_NET.equals(error) && !ERR_TIMEOUT.equals(error)
                        && !ERR_EXHAUSTED.equals(error)) {
                    int got = fetchWallhavenStills(stills, nextIndex(have) + added, want4k);
                    if (got > 0) {
                        added = got;
                        error = null;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "expand failed: " + e);
                detail = String.valueOf(e.getMessage());
                error = classify(e);
            } finally {
                running = false;
                final int fa = added, ft = total;
                final String fe = error, fd = detail;
                main.post(() -> cb.onDone(fa, ft, fe, fd));
            }
        }, "aerial-expand").start();
    }

    /**
     * Fallback still source when the video path yields nothing: Wallhaven's public search API
     * (no key, SFW only) for aerial photographs. Plain JPEG downloads — nothing for the box's
     * flaky video decoder to choke on — filed into the same aerials/ folder, so rotation picks
     * them up like any other still. A random page each run keeps repeats rare.
     */
    private int fetchWallhavenStills(File stills, int firstIndex, boolean want4k) {
        try {
            int page = 1 + (int) (Math.random() * 5);
            String api = "https://wallhaven.cc/api/v1/search?q=aerial&categories=100&purity=100"
                    + "&atleast=1920x1080&sorting=random&page=" + page;
            JSONObject root = new JSONObject(new String(fetchAll(api), "UTF-8"));
            JSONArray data = root.optJSONArray("data");
            if (data == null || data.length() == 0) {
                detail = "wallhaven: no results";
                return 0;
            }
            int saved = 0;
            for (int i = 0; i < data.length() && saved < 3; i++) {
                JSONObject o = data.getJSONObject(i);
                String path = o.optString("path", "");
                String res = o.optString("resolution", "");
                int cut = res.indexOf('x');
                if (cut > 0) {   // portrait shots would lose half the frame to the 16:9 crop
                    try {
                        if (Integer.parseInt(res.substring(0, cut)) < Integer.parseInt(res.substring(cut + 1)))
                            continue;
                    } catch (NumberFormatException bad) {
                        // unparseable resolution: keep the candidate and let the decode check decide
                    }
                }
                if (!path.toLowerCase(Locale.ROOT).endsWith(".jpg")) continue;
                byte[] jpg = fetchAll(path);
                if (jpg.length < 50_000) continue;
                android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
                Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length, opts);
                if (bmp == null || bmp.getWidth() < 1000) {
                    if (bmp != null) bmp.recycle();
                    continue;
                }
                Bitmap still = coverCrop(bmp, want4k ? STILL_W_4K : STILL_W, want4k ? STILL_H_4K : STILL_H);
                bmp.recycle();
                File out = new File(stills, String.format("aerial_%d.jpg", firstIndex + saved));
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    still.compress(Bitmap.CompressFormat.JPEG, 85, fo);
                } finally {
                    still.recycle();
                }
                saved++;
            }
            if (saved > 0) Log.d(TAG, "wallhaven fallback: " + saved + " stills");
            else detail = "wallhaven: nothing usable";
            return saved;
        } catch (Exception e) {
            Log.w(TAG, "wallhaven fallback failed: " + e);
            detail = "wallhaven: " + e.getMessage();
            return 0;
        }
    }

    /** Turns a thrown exception into one of the ERR_* codes the panel can explain. */
    private static String classify(Exception e) {
        if (e instanceof SocketTimeoutException) return ERR_TIMEOUT;
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("timeout") || m.contains("timed out")) return ERR_TIMEOUT;
        if (m.contains("failed to connect") || m.contains("unreachable")
                || m.contains("unknownhost") || m.startsWith("http 4") || m.startsWith("http 5")
                || m.contains("ssl") || m.contains("handshake") || m.contains("certificat")) {
            return ERR_NET;
        }
        return ERR_DECODE;
    }

    /** The first playlist clip whose source has not been pulled yet; null when all are used. */
    private String pickClip() throws Exception {
        try (InputStream in = app.getAssets().open("aerials.json")) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            JSONArray clips = new JSONArray(new String(bo.toByteArray(), "UTF-8"));
            java.util.Set<String> used = prefs.loadAerialUsedUrls();
            for (int i = 0; i < clips.length(); i++) {
                JSONObject o = clips.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (url.isEmpty() || used.contains(baseName(url))) continue;
                return url;
            }
        }
        return null;
    }

    /**
     * The first LIVE 4K clip of the official catalog not pulled yet; null when none is left.
     * Apple has taken most of this catalog offline (verified 2026-10: 12 of 13 URLs are 404),
     * so every candidate gets a cheap 16-byte range probe before it is trusted; a dead clip is
     * skipped without burning its "used" slot. Null → the caller falls back to the 1080p list.
     */
    private String pick4kClip() throws Exception {
        JSONArray urls = load4kCatalog();
        java.util.Set<String> used = prefs.loadAerialUsedUrls();
        for (int i = 0; i < urls.length(); i++) {
            String u = urls.optString(i, "");
            if (u.isEmpty() || used.contains(baseName(u))) continue;
            if (urlAlive(u)) return u;
        }
        return null;
    }

    /** 16-byte range probe: true when the clip is really on Apple's server. */
    private static boolean urlAlive(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setRequestProperty("Range", "bytes=0-15");
            c.setRequestProperty("User-Agent", "QuickTime/7.6");
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return false;
            try (InputStream in = c.getInputStream()) {
                return in.read(new byte[16]) > 0;
            }
        } catch (Exception dead) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Apple's official catalog, cached for a day in the app's files dir (a stale cache beats a dead network). */
    private JSONArray load4kCatalog() throws Exception {
        File base = Config.dir(app);
        if (base == null) throw new IllegalStateException("no files dir");
        File cache = new File(base, "aerials_4k.json");
        if (cache.isFile() && System.currentTimeMillis() - cache.lastModified() < 86_400_000L) {
            return new JSONArray(readFile(cache));
        }
        JSONObject root = new JSONObject(new String(fetchAll(ENTRIES_URL), "UTF-8"));
        JSONArray assets = root.optJSONArray("assets");
        if (assets == null || assets.length() == 0) throw new IOException("entries.json unusable");
        JSONArray urls = new JSONArray();
        for (int i = 0; i < assets.length(); i++) {
            String u = assets.getJSONObject(i).optString("url-4K-SDR", "");
            if (!u.isEmpty()) urls.put(u);
        }
        if (urls.length() == 0) throw new IOException("no 4K urls in entries.json");
        try (FileOutputStream fo = new FileOutputStream(cache)) {
            fo.write(urls.toString().getBytes("UTF-8"));
        }
        return urls;
    }

    private static String readFile(File f) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        }
        return bo.toString("UTF-8");
    }

    /** Plain GET with real timeouts; null-free — failures throw and land in classify(). */
    private static byte[] fetchAll(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("User-Agent", "QuickTime/7.6");
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < 20 * 1024 * 1024) bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Downloads the clip's head, reshapes it into a decodable file (makeFastStart), then grabs
     * the frame points off the local copy. Both temp files are always removed; the caller
     * decides whether the clip counts as used. 4K mode pulls the bigger HEVC head and targets
     * 3840x2160 stills.
     */
    private int grabFrames(String url, File stills, int firstIndex, boolean want4k) throws Exception {
        final int stillW = want4k ? STILL_W_4K : STILL_W;
        final int stillH = want4k ? STILL_H_4K : STILL_H;
        File raw = new File(stills, ".clip.raw");
        File tmp = new File(stills, ".clip.tmp");
        try {
            downloadHead(url, raw, want4k ? MAX_CLIP_BYTES_4K : MAX_CLIP_BYTES);
            makeFastStart(url, raw, tmp);
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            int saved = 0;
            try {
                try {
                    mmr.setDataSource(tmp.getAbsolutePath());
                } catch (Exception e) {
                    detail = "setDataSource: " + e;
                    throw e;
                }
                for (long us : FRAME_US) {
                    Bitmap frame = null;
                    try {
                        frame = mmr.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    } catch (Exception e) {
                        Log.w(TAG, "frame grab failed at " + us + ": " + e);
                        detail = "frame @" + (us / 1_000_000L) + "s: " + e;
                    }
                    if (frame == null || frame.getWidth() < 400 || frame.getHeight() < 400) {
                        if (detail == null && frame == null)
                            detail = "frame @" + (us / 1_000_000L) + "s: retriever returned null";
                        if (frame != null) frame.recycle();
                        continue;
                    }
                    if (tooDark(frame)) {
                        frame.recycle();
                        continue;
                    }
                    Bitmap still = coverCrop(frame, stillW, stillH);
                    frame.recycle();
                    File out = new File(stills, String.format("aerial_%d.jpg", firstIndex + saved));
                    try (FileOutputStream fo = new FileOutputStream(out)) {
                        still.compress(Bitmap.CompressFormat.JPEG, 85, fo);
                    } finally {
                        still.recycle();
                    }
                    saved++;
                }
            } finally {
                try {
                    mmr.release();
                } catch (Exception e) {
                    Log.w(TAG, "mmr release skipped: " + e);
                }
            }
            return saved;
        } finally {
            if (!raw.delete()) Log.w(TAG, "temp head left behind: " + raw);
            if (!tmp.delete()) Log.w(TAG, "temp clip left behind: " + tmp);
        }
    }

    /**
     * Reshapes the head-only download into a file the retriever can open. If a complete moov
     * already sits at the front, the head is used as-is (its offsets are absolute and valid for
     * the prefix we have). Otherwise the moov is fetched from the tail, every chunk offset is
     * rebased, and it is spliced in before the media data: [prefix][moov][mdat box]. The mdat
     * box header is RE-EMITTED — the raw payload cannot follow the moov headerless, because the
     * retriever walks top-level atoms strictly and stops at the first garbage box. With the
     * header back in place the data starts mdatStart + moovSize + 8 instead of
     * mdatStart + hdrLen, hence the delta = moovSize + 8 - hdrLen on each offset. (A previous
     * version spliced the payload headerless with delta = moovSize - 8: ffprobe tolerated it,
     * but stagefright on the box silently found no frames — "downloaded fine, no pictures".)
     */
    private void makeFastStart(String url, File raw, File out) throws Exception {
        byte[] probe = new byte[PROBE_BYTES];
        int got;
        try (InputStream in = new java.io.FileInputStream(raw)) {
            got = 0;
            while (got < probe.length) {
                int r = in.read(probe, got, probe.length - got);
                if (r < 0) break;
                got += r;
            }
        }
        long mdatStart = -1, mdatSize = 0, moovAt = -1, moovSize = 0;
        int mdatHdrLen = 8;
        int off = 0;
        while (off + 8 <= got) {
            long size = u32(probe, off) & 0xFFFFFFFFL;
            String typ = atomType(probe, off);
            if (size == 1 && off + 16 <= got) size = u64(probe, off + 8);
            if (size < 8) break;
            if ("moov".equals(typ)) { moovAt = off; moovSize = size; break; }
            if ("mdat".equals(typ)) {
                mdatStart = off;
                mdatSize = size;
                mdatHdrLen = (u32(probe, off) & 0xFFFFFFFFL) == 1 ? 16 : 8;
            }
            off += (int) size;
        }
        // Fast-start source (a complete moov inside the downloaded head): the raw file is a
        // valid prefix of the original, so its absolute offsets are exactly right for decoding.
        if (moovAt >= 0 && moovAt + moovSize <= raw.length()) {
            // No java.nio.file.Files here: it needs API 26 and the box runs Android 7.
            if (!raw.renameTo(out)) {
                copyFile(raw, out);
                if (!raw.delete()) Log.w(TAG, "raw head left behind: " + raw);
            }
            return;
        }
        if (mdatStart < 0) throw new IOException("no mdat in clip head");
        // moov at the tail: fetch it, patch it, splice it. Apple's aerials end with the moov.
        byte[] tail = fetchRange(url, mdatStart + mdatSize, TAIL_MAX);
        int mOff = -1;
        long mSize = 0;
        int toff = 0;
        while (toff + 8 <= tail.length) {
            long size = u32(tail, toff) & 0xFFFFFFFFL;
            String typ = atomType(tail, toff);
            if (size == 1) size = u64(tail, toff + 8);
            if (size < 8) break;
            if ("moov".equals(typ)) { mOff = toff; mSize = size; break; }
            toff += (int) size;
        }
        if (mOff < 0 || mSize > tail.length - mOff) throw new IOException("moov not found at tail");
        byte[] moov = new byte[(int) mSize];
        System.arraycopy(tail, mOff, moov, 0, (int) mSize);
        patchChunkOffsets(moov, 8, moov.length, mSize + 8 - mdatHdrLen);
        // Splice: prefix + patched moov + a fresh mdat box header + the media payload.
        long payload = raw.length() - mdatStart - mdatHdrLen;
        try (java.io.FileInputStream in = new java.io.FileInputStream(raw);
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] buf = new byte[65536];
            long remaining = mdatStart;
            while (remaining > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (r < 0) throw new IOException("head shorter than its own atom table");
                fo.write(buf, 0, r);
                remaining -= r;
            }
            fo.write(moov);
            long boxSize = payload + 8;
            fo.write(new byte[]{
                    (byte) (boxSize >> 24), (byte) (boxSize >> 16), (byte) (boxSize >> 8), (byte) boxSize,
                    'm', 'd', 'a', 't'});
            long skip = mdatHdrLen;
            while (skip > 0) skip -= in.skip(skip);
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }
        Log.d(TAG, "fast-start rebuilt: moov " + mSize + "B + mdat " + payload + "B at " + mdatStart);
    }

    /** Range-GETs up to {@code max} bytes from {@code start} (the moov hunt at the tail). */
    private static byte[] fetchRange(String url, long start, int max) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestProperty("Range", "bytes=" + start + "-" + (start + max - 1));
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0 && bo.size() < max) bo.write(buf, 0, n);
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** Atom boxes whose children are boxes again; everything else is a leaf. */
    private static final String[] CONTAINERS = {"moov", "trak", "mdia", "minf", "stbl"};

    /**
     * Adds {@code delta} to every chunk offset inside the moov (stco = 32-bit entries,
     * co64 = 64-bit ones). Without this the spliced moov would point at the ORIGINAL file's
     * byte positions and every frame read would land in the wrong place.
     */
    private static void patchChunkOffsets(byte[] b, int start, int end, long delta) {
        int off = start;
        while (off + 8 <= end) {
            long sizeL = u32(b, off) & 0xFFFFFFFFL;
            String typ = atomType(b, off);
            if (sizeL == 1) sizeL = u64(b, off + 8);
            if (sizeL < 8) return;
            int size = (int) Math.min(sizeL, end - off);
            if ("stco".equals(typ)) {
                int n = (int) (u32(b, off + 12) & 0xFFFFFFFFL);
                for (int i = 0; i < n && off + 16 + 4L * i + 4 <= end; i++) {
                    int p = off + 16 + 4 * i;
                    long v = (u32(b, p) & 0xFFFFFFFFL) + delta;
                    b[p] = (byte) (v >> 24);
                    b[p + 1] = (byte) (v >> 16);
                    b[p + 2] = (byte) (v >> 8);
                    b[p + 3] = (byte) v;
                }
            } else if ("co64".equals(typ)) {
                int n = (int) (u32(b, off + 12) & 0xFFFFFFFFL);
                for (int i = 0; i < n && off + 16 + 8L * i + 8 <= end; i++) {
                    int p = off + 16 + 8 * i;
                    long v = u64(b, p) + delta;
                    for (int k = 0; k < 8; k++) b[p + k] = (byte) (v >> (8 * (7 - k)));
                }
            } else {
                for (String ct : CONTAINERS) {
                    if (ct.equals(typ)) {
                        patchChunkOffsets(b, off + 8, off + size, delta);
                        break;
                    }
                }
            }
            off += size;
        }
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static long u64(byte[] b, int off) {
        return (u32(b, off) << 32) | (u32(b, off + 4) & 0xFFFFFFFFL);
    }

    private static String atomType(byte[] b, int off) {
        return new String(b, off + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** Plain byte copy (the API-26-free stand-in for Files.move when rename fails). */
    private static void copyFile(File src, File dst) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             FileOutputStream fo = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
        }
    }

    /**
     * Range-gets the first {@code maxBytes} of the clip into {@code out}. A real connection with
     * real timeouts, so the caller can tell "no network" from "no frames".
     */
    private static void downloadHead(String url, File out, int maxBytes) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10_000);
        c.setReadTimeout(15_000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Range", "bytes=0-" + (maxBytes - 1));
        try {
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IOException("HTTP " + code);
            }
            long total = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream fo = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    fo.write(buf, 0, n);
                    if (total >= maxBytes) break;
                }
            }
            if (total < 64 * 1024) throw new IOException("clip too small: " + total);
        } finally {
            c.disconnect();
        }
    }

    /** One past the highest aerial_NN.jpg number already in the folder. */
    private static int nextIndex(File[] have) {
        int max = 0;
        if (have != null) {
            for (File f : have) {
                Matcher m = NUM.matcher(f.getName());
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return max + 1;
    }

    /** Center-crop to exactly wantW x wantH (the stills are shown CENTER_CROP anyway). */
    private static Bitmap coverCrop(Bitmap src, int wantW, int wantH) {
        float scale = Math.max((float) wantW / src.getWidth(), (float) wantH / src.getHeight());
        int w = Math.max(1, Math.round(src.getWidth() * scale));
        int h = Math.max(1, Math.round(src.getHeight() * scale));
        Bitmap scaled = (w == src.getWidth() && h == src.getHeight())
                ? src : Bitmap.createScaledBitmap(src, w, h, true);
        int x = (scaled.getWidth() - wantW) / 2;
        int y = (scaled.getHeight() - wantH) / 2;
        Bitmap out = Bitmap.createBitmap(scaled, x, y, wantW, wantH);
        if (scaled != src && scaled != out) scaled.recycle();
        return out;
    }

    /** 16-point average below 8/255 counts as a black frame or a fade — skip it. */
    private static boolean tooDark(Bitmap bmp) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        long sum = 0;
        int n = 0;
        for (int i = 1; i <= 4; i++) {
            for (int j = 1; j <= 4; j++) {
                int c = bmp.getPixel(w * i / 5, h * j / 5);
                sum += (Color.red(c) + Color.green(c) + Color.blue(c)) / 3;
                n++;
            }
        }
        return n > 0 && sum / n < 8;
    }

    private static String baseName(String url) {
        int slash = url.lastIndexOf('/');
        return slash >= 0 ? url.substring(slash + 1) : url;
    }
}
