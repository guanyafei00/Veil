package com.veil.launcher;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * The 360 wallpaper gallery (cdn.apc.360.cn): a China-reachable stock of curated categories —
 * anime, game, scenery, 4K, movie stills… — with thousands of ready-made JPEGs each. The owner
 * asked for an anime-capable source the box can actually reach: Apple's CDN and Wallhaven both
 * proved unreachable from the box's network, while this API answers plain HTTP from inside
 * China and its CDN (qhimg.com) serves finished pictures directly. One tap pulls a random
 * window of the chosen category into files/w360/ as rotation stock.
 */
final class Wall360 {
    private static final String TAG = "TvHome.Wall360";
    private static final String LIST_URL =
            "http://cdn.apc.360.cn/index.php?c=WallPaper&a=getAppsByCategory&from=360chrome";

    /** Curated categories in display order (ids verified live against getAllCategories). */
    static final String[] CAT_IDS = {
            "26", "5", "9", "36", "7", "12", "14", "22", "15", "10", "13", "35",
    };
    static final String[] CAT_NAMES = {
            "动漫卡通", "游戏壁纸", "风景大片", "4K专区", "影视剧照", "汽车天下",
            "萌宠动物", "军事天地", "小清新", "炫酷时尚", "节日美图", "文字控",
    };

    /** Human name for a stored category id; unknown ids read as the default anime pick. */
    static String catName(String cid) {
        for (int i = 0; i < CAT_IDS.length; i++) {
            if (CAT_IDS[i].equals(cid)) return CAT_NAMES[i];
        }
        return CAT_NAMES[0];
    }

    /** Error codes handed back with onDone; the settings panel maps them to its own wording. */
    static final String ERR_NET = "net";
    static final String ERR_TIMEOUT = "timeout";
    static final String ERR_DECODE = "decode";
    static final String ERR_EMPTY = "empty";

    /** Result of one run, on the main thread: added &gt; 0 with error == null on success. */
    interface Callback {
        void onDone(int added, int total, String error, String detail);
    }

    /** Pictures per tap; a slow network stops early, the rest waits for the next tap. */
    private static final int BATCH = 12;
    /** Hard cap on one pass's download so a huge category cannot stall the box. */
    private static final int MAX_BYTES = 30 * 1024 * 1024;

    private static volatile boolean running;
    /** The lowest-level reason the last pull produced nothing (read by AerialExpander). */
    private static volatile String lastPullDetail;

    private final Context app;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The lowest-level reason a picture was skipped — surfaced into the toast. */
    private volatile String detail;

    private Wall360(Context ctx, Callback cb) {
        this.app = ctx.getApplicationContext();
        this.cb = cb;
    }

    /** Starts one expansion pass. Extra taps while a pass runs are ignored. */
    static void expand(Context ctx, Callback cb) {
        if (running) {
            cb.onDone(0, 0, "busy", null);
            return;
        }
        running = true;
        new Wall360(ctx, cb).run();
    }

    /**
     * Synchronous pull on the CALLER's thread — the aerial expander's last-chance fallback
     * already runs on its own worker and folds the result into its own callback. Returns the
     * number of pictures added into {@code stock} (named {@code prefix<id>.jpg}); 0 with the
     * reason in {@link #lastPullDetail} when the network gave up.
     */
    static int pullInto(Context ctx, File stock, String prefix, String cid, int batch) {
        lastPullDetail = null;
        Wall360 w = new Wall360(ctx, null);
        try {
            return w.pullSync(stock, prefix, cid, batch);
        } catch (SocketTimeoutException e) {
            Log.w(TAG, "pull timed out: " + e.getMessage());
            lastPullDetail = String.valueOf(e.getMessage());
            return 0;
        } catch (Exception e) {
            Log.w(TAG, "pull failed: " + e);
            lastPullDetail = String.valueOf(e.getMessage());
            return 0;
        }
    }

    /** Why {@link #pullInto} last added nothing; null when nothing was even attempted. */
    static String lastPullDetail() {
        return lastPullDetail;
    }

    private void run() {
        new Thread(() -> {
            int added = 0, total = 0;
            String error = null;
            detail = null;
            try {
                File dir = Config.dir(app);
                if (dir == null) throw new IllegalStateException("no files dir");
                File stock = new File(dir, "w360");
                if (!stock.exists() && !stock.mkdirs()) throw new IllegalStateException("mkdir failed");
                File[] have = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                total = have == null ? 0 : have.length;
                added = pullSync(stock, "w360_", new PrefsStore(app).loadW360Cat(), BATCH);
                if (added == 0 && error == null) error = ERR_EMPTY;
                File[] after = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                total = after == null ? total : after.length;
            } catch (SocketTimeoutException e) {
                Log.w(TAG, "expand timed out: " + e.getMessage());
                detail = String.valueOf(e.getMessage());
                error = ERR_TIMEOUT;
            } catch (Exception e) {
                Log.w(TAG, "expand failed: " + e);
                detail = String.valueOf(e.getMessage());
                error = classify(e);
            } finally {
                running = false;
                final int fAdded = added, fTotal = total;
                final String fError = error, fDetail = detail;
                main.post(() -> cb.onDone(fAdded, fTotal, fError, fDetail));
            }
        }).start();
    }

    /**
     * The core, synchronous: probe the category's size, land on a RANDOM page window (repeated
     * taps keep bringing fresh pictures instead of the same first page), then download until
     * {@code batch} new pictures are saved. Skips already-pulled ids (deduped by file name) and
     * portrait pictures; validates every download (JPEG bounds) before it counts. Throws on a
     * dead network; skips with a note in {@link #detail}.
     */
    private int pullSync(File stock, String prefix, String cid, int batch) throws Exception {
        detail = null;
        File[] have = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
        java.util.Set<String> names = new java.util.HashSet<>();
        if (have != null) for (File f : have) names.add(f.getName());
        int added = 0;
        long bytes = 0;
        JSONObject probe = fetchPage(cid, 0, 1);
        int catalog = 3000;
        if (probe != null) {
            try { catalog = Integer.parseInt(probe.optString("total", "3000")); }
            catch (NumberFormatException bad) { catalog = 3000; }
        }
        if (catalog < 1) catalog = 1;
        if (catalog > 50_000) catalog = 50_000;
        int start = catalog <= batch ? 0 : (int) (Math.random() * (catalog - batch));
        JSONObject page = fetchPage(cid, start, batch + 30);
        JSONArray data = page == null ? null : page.optJSONArray("data");
        if (data == null || data.length() == 0) {
            throw new IllegalStateException("empty list for category " + cid);
        }
        for (int i = 0; i < data.length() && added < batch && bytes < MAX_BYTES; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String url = item.optString("url", "");
            if (id.isEmpty() || url.isEmpty()) continue;
            String name = prefix + id + ".jpg";
            if (names.contains(name)) continue;
            if (isPortrait(item.optString("resolution", ""))) continue;
            byte[] jpg = httpGet(url, 20_000);
            if (jpg == null || jpg.length < 20_000) {   // a real wallpaper is never tiny
                detail = "tiny or unreadable: " + url;
                continue;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bounds);
            if (bounds.outWidth <= 0) {                 // the CDN handed back an error page
                detail = "not an image: " + url;
                continue;
            }
            try (FileOutputStream fo = new FileOutputStream(new File(stock, name))) {
                fo.write(jpg);
            }
            names.add(name);
            added++;
            bytes += jpg.length;
        }
        return added;
    }

    /**
     * One list call: cid + window. Returns the whole envelope (errno/total/data) or null when
     * the network gave up; the reason lands in {@link #detail}.
     */
    private JSONObject fetchPage(String cid, int start, int count) throws Exception {
        String api = LIST_URL + "&cid=" + cid + "&start=" + start + "&count=" + count;
        byte[] raw = httpGet(api, 15_000);
        if (raw == null || raw.length == 0) return null;
        try {
            return new JSONObject(new String(raw, "UTF-8"));
        } catch (Exception bad) {
            Log.w(TAG, "list not JSON: " + bad.getMessage());
            return null;
        }
    }

    /** "2560x1440" → false; anything taller than wide (or unreadable) → true. */
    private static boolean isPortrait(String resolution) {
        int x = resolution.indexOf('x');
        if (x <= 0 || x == resolution.length() - 1) return false;   // unknown: give it a chance
        try {
            return Integer.parseInt(resolution.substring(0, x).trim())
                    < Integer.parseInt(resolution.substring(x + 1).trim());
        } catch (NumberFormatException bad) {
            return false;
        }
    }

    private static String classify(Exception e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();
        if (m.contains("timeout")) return ERR_TIMEOUT;
        if (m.contains("ssl") || m.contains("handshake") || m.contains("certificat")
                || m.contains("unknownhost") || m.contains("econnrefused")
                || m.contains("unreachable") || m.contains("connect")) return ERR_NET;
        return ERR_NET;
    }

    /** Plain HTTP GET (the API is http-only); null with the reason in {@link #detail} on failure. */
    private byte[] httpGet(String url, int readTimeout) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(readTimeout);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 7) VeilLauncher");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toByteArray();
        } catch (SocketTimeoutException e) {
            detail = "timeout: " + url;
            return null;
        } catch (Exception e) {
            detail = e.getClass().getSimpleName() + ": " + e.getMessage() + " @ " + url;
            return null;
        }
    }
}
