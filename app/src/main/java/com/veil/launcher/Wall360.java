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
    /** The API answered, but its WAF flagged the source IP: "IP Forbidden". */
    static final String ERR_BLOCKED = "blocked";

    /**
     * Minimum gap between two LIST calls. A courtesy pause: the API tolerates a steady rhythm
     * of requests, but a burst of them is not worth risking over.
     */
    private static final long API_GAP_MS = 1_500L;
    /** Same idea for the picture CDN. */
    private static final long IMAGE_GAP_MS = 300L;

    /** The moment the last list call went out (shared by every instance). */
    private static long lastApiAt;
    /** The moment the last picture download went out. */
    private static long lastImageAt;

    /** Result of one run, on the main thread: added &gt; 0 with error == null on success. */
    interface Callback {
        void onDone(int added, int total, String error, String detail);
    }

    /**
     * The API's hard limits, measured on the box (2026-10-27):
     * <ul>
     *   <li>{@code start + count} must stay at or below 1000 — the "first page" of a category.
     *       A window past it answers with errno 1105 / "Proxy Warning - IP Forbidden", which
     *       reads like a network block but is really an out-of-range request.
     *   <li>{@code count} must not exceed 99; above that the API answers errno 2000
     *       ("参数不正确" — the parameter is wrong).
     * </ul>
     * The old code asked for a random window of a 10 000-item catalogue, so nearly every tap
     * landed outside the allowed window and came back "blocked". Every request now stays
     * inside it.
     */
    private static final int MAX_WINDOW = 1000;
    private static final int MAX_COUNT = 99;
    /** How many windows one pass may walk through when looking for pictures that are new. */
    private static final int MAX_PAGES = 4;

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
    /** The error code of the last failed pull, kept so the Bing fallback can report it. */
    private volatile String lastError;

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
        } catch (Throwable e) {
            Log.w(TAG, "pull failed: " + e);
            lastPullDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
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
            File stock = null;
            try {
                File dir = Config.dir(app);
                if (dir == null) throw new IllegalStateException("no files dir");
                stock = new File(dir, "w360");
                if (!stock.exists() && !stock.mkdirs()) throw new IllegalStateException("mkdir failed");
                File[] have = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                total = have == null ? 0 : have.length;
            } catch (Exception e) {
                Log.w(TAG, "expand failed: " + e);
                detail = String.valueOf(e.getMessage());
                error = classify(e);
            }
            // The 360 pull is isolated in its own try: a WAF refusal must not skip the Bing
            // fallback below, or the tap ends with nothing at all.
            if (stock != null) {
                try {
                    added = pullSync(stock, "w360_", new PrefsStore(app).loadW360Cat(), BATCH);
                    if (added == 0) error = lastError;
                } catch (Throwable e) {
                    Log.w(TAG, "360 pull failed: " + e);
                    detail = e.getClass().getSimpleName() + ": " + e.getMessage();
                    error = classify(e);
                }
                if (added == 0) {
                    // The 360 gallery is the anime/game/scenery source, but its WAF blocks a
                    // source IP that asks too often. Bing's archive is the one host that always
                    // answers on this box, so the expansion still ends with real pictures.
                    int got = BingStock.pullInto(app, stock, "bing_", BATCH, 4);
                    if (got > 0) {
                        added = got;
                        error = null;
                        detail = "360 图库被风控拦截，已改用必应图库补足";
                    }
                }
            }
            try {
                if (stock != null) {
                    File[] after = stock.listFiles((d, n) -> n.toLowerCase().endsWith(".jpg"));
                    total = after == null ? total : after.length;
                }
                if (added == 0 && error == null) error = ERR_EMPTY;
            } catch (Exception e) {
                Log.w(TAG, "expand failed: " + e);
            }
            running = false;
            final int fAdded = added, fTotal = total;
            final String fError = error, fDetail = detail;
            main.post(() -> cb.onDone(fAdded, fTotal, fError, fDetail));
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
        // ONE list call, not two. The old code probed the catalogue with count=1 and then asked
        // for the window — two calls in a row, and the second one got flagged. The envelope
        // carries "total", so a single window request already tells us the catalogue size.
        //
        // The window itself has to stay inside the API's limits (start + count <= 1000,
        // count <= 99) — anything beyond that comes back as a bogus "IP Forbidden".
        //
        // The start is a RUNNING CURSOR, not a random jump: the category serves its first
        // 1000 items, and a random start would keep landing on the same block that was
        // already downloaded. The cursor walks the whole range and wraps around, so repeated
        // taps keep bringing genuinely new pictures.
        int count = MAX_COUNT;
        int span = MAX_WINDOW - count;          // how far the cursor may travel
        int cursor = new PrefsStore(app).loadW360Cursor(cid) % (span + 1);
        if (cursor < 0) cursor = 0;
        int start = cursor;
        // The window may hold nothing new (the cursor came back around to a block that was
        // already taken). Then it walks on to the next window until it finds fresh pictures.
        JSONArray data = null;
        JSONObject page = null;
        int pages = 0;
        while (pages < MAX_PAGES) {
            page = fetchPage(cid, start, count);
            if (page == null) {
                lastError = ERR_NET;
                throw new IllegalStateException("no list for category " + cid
                        + (detail == null ? "" : " (" + detail + ")"));
            }
            data = page.optJSONArray("data");
            if (data != null && data.length() > 0 && hasNewPicture(data, names, prefix)) break;
            // Nothing new here: step the window on and look again.
            start = (start + count) % (span + 1);
            pages++;
        }
        if (data == null || data.length() == 0) {
            lastError = ERR_EMPTY;
            throw new IllegalStateException("empty list for category " + cid
                    + (detail == null ? "" : " (" + detail + ")"));
        }
        // Remember the catalogue size so the NEXT tap can jump to a fresh window.
        int total = 3000;
        try { total = Integer.parseInt(page.optString("total", "3000")); }
        catch (NumberFormatException bad) { total = 3000; }
        if (total < 1) total = 1;
        if (total > 50_000) total = 50_000;
        prefsSaveCatalog(cid, total);
        // Advance the cursor past the window we just read, so the next tap starts where this
        // one stopped instead of asking for the same block again.
        prefsSaveCursor(cid, (start + count) % (span + 1));
        for (int i = 0; i < data.length() && added < batch && bytes < MAX_BYTES; i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            String url = item.optString("url", "");
            if (id.isEmpty() || url.isEmpty()) continue;
            String name = prefix + id + ".jpg";
            if (names.contains(name)) continue;
            if (isPortrait(item.optString("resolution", ""))) continue;
            waitImageGap();
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
     * True when the window holds at least one picture that is not on disk yet. Portrait
     * pictures do not count: they are skipped later, so a window of nothing but portraits
     * would still be a wasted call.
     */
    private static boolean hasNewPicture(JSONArray data, java.util.Set<String> names, String prefix) {
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            String id = item.optString("id", "");
            if (id.isEmpty()) continue;
            if (isPortrait(item.optString("resolution", ""))) continue;
            if (!names.contains(prefix + id + ".jpg")) return true;
        }
        return false;
    }

    /** Remembers a category's catalogue size so the next tap can pick a different window. */
    private void prefsSaveCatalog(String cid, int catalog) {
        try {
            new PrefsStore(app).saveW360Catalog(cid, catalog);
        } catch (Exception e) {
            Log.w(TAG, "catalog size not saved: " + e);
        }
    }

    /** Remembers where the next window should start, so repeated taps bring new pictures. */
    private void prefsSaveCursor(String cid, int cursor) {
        try {
            new PrefsStore(app).saveW360Cursor(cid, cursor);
        } catch (Exception e) {
            Log.w(TAG, "cursor not saved: " + e);
        }
    }

    /**
     * One list call: cid + window. Returns the whole envelope (errno/total/data) or null when
     * the network gave up; the reason lands in {@link #detail}.
     * <p>
     * The API answers an out-of-range window with errno 1105 / "IP Forbidden" — a message that
     * reads like a network block but is not one. When that happens the call is repeated once
     * from the very beginning of the category, which always works.
     */
    private JSONObject fetchPage(String cid, int start, int count) throws Exception {
        String api = LIST_URL + "&cid=" + cid + "&start=" + start + "&count=" + count;
        waitApiGap();
        byte[] raw = httpGet(api, 15_000);
        if (raw == null || raw.length == 0) {
            detail = "empty body from " + api;
            Log.w(TAG, detail);
            return null;
        }
        JSONObject o;
        try {
            o = new JSONObject(new String(raw, "UTF-8"));
        } catch (Exception bad) {
            detail = "not JSON (" + bad.getMessage() + ") body=" + snippet(raw);
            Log.w(TAG, "list not JSON: " + detail);
            return null;
        }
        JSONArray data = o.optJSONArray("data");
        if (data != null && data.length() > 0) return o;
        String errmsg = o.optString("errmsg", "");
        // errno 1105 / "IP Forbidden" is NOT a network block: the API answers an out-of-range
        // window (start + count > 1000) with exactly this text.
        if (errmsg.contains("IP Forbidden") || "1105".equals(o.optString("errno"))) {
            if (start > 0) {
                Log.w(TAG, "window " + start + "+" + count + " out of range — retrying from the start");
                return fetchPage(cid, 0, count);
            }
            detail = "360 图库返回「IP Forbidden」：请求窗口越界";
            Log.w(TAG, detail);
            return o;
        }
        // The API answered but with no pictures: keep the raw body, it says why.
        detail = "errno=" + o.optString("errno") + " errmsg=" + errmsg
                + " body=" + snippet(raw);
        Log.w(TAG, "list empty: " + detail);
        return o;
    }

    /** Sleeps so that two list calls are never closer together than {@link #API_GAP_MS}. */
    private static synchronized void waitApiGap() {
        long now = System.currentTimeMillis();
        long due = lastApiAt + API_GAP_MS;
        if (due > now) {
            try {
                Thread.sleep(due - now);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lastApiAt = System.currentTimeMillis();
    }

    /** Records that a request to the 360 API just went out, so the next one waits its turn. */
    static synchronized void noteApiUsed() {
        lastApiAt = System.currentTimeMillis();
    }

    /** The same spacing for the picture CDN (a different host, the same source IP). */
    private static synchronized void waitImageGap() {
        long now = System.currentTimeMillis();
        long due = lastImageAt + IMAGE_GAP_MS;
        if (due > now) {
            try {
                Thread.sleep(due - now);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        lastImageAt = System.currentTimeMillis();
    }

    /** The first bytes of a response, printable in a toast. */
    private static String snippet(byte[] raw) {
        try {
            String s = new String(raw, "UTF-8");
            s = s.replaceAll("\\s+", " ").trim();
            return s.length() > 160 ? s.substring(0, 160) + "…" : s;
        } catch (Exception e) {
            return raw.length + " bytes";
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

    private static String classify(Throwable e) {
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
            return Http.getBytes(url, 10_000, readTimeout, null, 20 * 1024 * 1024);
        } catch (java.net.SocketTimeoutException e) {
            detail = "timeout: " + url;
            return null;
        } catch (Exception e) {
            detail = e.getClass().getSimpleName() + ": " + e.getMessage() + " @ " + url;
            return null;
        }
    }
}
