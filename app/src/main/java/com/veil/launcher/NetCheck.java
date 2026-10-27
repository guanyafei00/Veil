package com.veil.launcher;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * One-tap diagnosis of the wallpaper sources: for each one it resolves the host, opens a socket,
 * does the HTTP request and reports what came back (with the first bytes of the body).
 * <p>
 * The owner's box sits behind a home router whose DNS answers but whose picture CDNs behaved
 * differently one morning ("only Bing works"). A report like
 * "360 API: DNS ok, TCP ok, HTTP 200, body starts with …" pins the failing layer instead of
 * guessing. Also reachable over adb: {@code am broadcast -a com.veil.launcher.NETCHECK}.
 */
final class NetCheck {
    private static final String TAG = "TvHome.NetCheck";

    /**
     * name | url — the sources the wallpaper panel actually uses.
     * <p>
     * The 360 API entry is deliberately probed with a single picture and the sweep waits
     * between the probes: the API's WAF refuses a second request that arrives too soon after
     * the first, and a diagnostic sweep that trips the very WAF it is diagnosing would send
     * the owner chasing a phantom problem.
     */
    private static final String[] TARGETS = {
            "360 图库 API|http://cdn.apc.360.cn/index.php?c=WallPaper&a=getAppsByCategory&cid=26&start=0&count=3&from=360chrome",
            "360 图床|https://p3.qhimg.com/bdr/__85/t01e2fa62f505895db7.jpg",
            "Wallhaven|https://wallhaven.cc/api/v1/search?categories=010&purity=100",
            "Apple 航拍列表|https://sylvan.apple.com/Aerials/2x/entries.json",
            "必应壁纸|https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1",
            "必应图库|https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US",
            "天气 Open-Meteo|https://api.open-meteo.com/v1/forecast?latitude=39.9&longitude=116.4&current=temperature_2m",
    };

    interface Callback {
        void onDone(String report);
    }

    private final Context app;
    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    private NetCheck(Context ctx, Callback cb) {
        this.app = ctx.getApplicationContext();
        this.cb = cb;
    }

    /** Runs the whole sweep on a worker thread; the report arrives on the main thread. */
    static void run(Context ctx, Callback cb) {
        new NetCheck(ctx, cb).go();
    }

    private void go() {
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            out.append("网络自检 ").append(android.os.Build.MODEL)
                    .append(" / Android ").append(android.os.Build.VERSION.RELEASE)
                    .append("\n");
            for (String t : TARGETS) {
                int bar = t.indexOf('|');
                String name = t.substring(0, bar);
                String url = t.substring(bar + 1);
                out.append(probe(name, url)).append('\n');
                Log.d(TAG, name + " → " + out.substring(out.length() - out.length()).trim());
                // The probes are spaced out so the sweep never fires a burst of requests.
                try {
                    Thread.sleep(1_000L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            String report = out.toString();
            Log.d(TAG, "===== report =====\n" + report);
            main.post(() -> cb.onDone(report));
        }).start();
    }

    /** One source: DNS → TCP → HTTP, with timings and the head of the body. */
    private String probe(String name, String url) {
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(name).append("】");
        String host;
        try {
            host = new java.net.URL(url).getHost();
        } catch (Exception e) {
            return sb.append(" URL 非法: ").append(e.getMessage()).toString();
        }
        // 1. DNS
        long t0 = System.currentTimeMillis();
        try {
            InetAddress[] addrs = InetAddress.getAllByName(host);
            sb.append(" DNS ").append(addrs[0].getHostAddress());
        } catch (Exception e) {
            return sb.append(" ✗ DNS 失败: ").append(e.getClass().getSimpleName())
                    .append(' ').append(e.getMessage()).toString();
        }
        sb.append(" (").append(System.currentTimeMillis() - t0).append("ms)");
        // 2. TCP
        int port = url.startsWith("https") ? 443 : 80;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 6000);
            sb.append(" · TCP ").append(port).append(" 通");
        } catch (SocketTimeoutException e) {
            return sb.append(" ✗ TCP 超时").toString();
        } catch (Exception e) {
            return sb.append(" ✗ TCP 失败: ").append(e.getClass().getSimpleName())
                    .append(' ').append(e.getMessage()).toString();
        }
        // 3. HTTP (with the app's own lenient-TLS retry)
        long t1 = System.currentTimeMillis();
        try {
            byte[] body = Http.getBytes(url, 8000, 12000, null, 256 * 1024);
            sb.append(" · HTTP 200 · ").append(body.length).append("B")
                    .append(" (").append(System.currentTimeMillis() - t1).append("ms)");
            sb.append(" · ").append(head(body));
            // The probe was a real request to the 360 API: it counts against that host's
            // request budget, so the gallery expander must wait before its next call.
            if (url.contains("cdn.apc.360.cn")) {
                Wall360.noteApiUsed();
                // The API answers an out-of-range window with HTTP 200 and errno 1105 in the
                // body — a plain "200 OK" would hide the real state of the source.
                String text = new String(body, "UTF-8");
                if (text.contains("IP Forbidden") || text.contains("\"errno\":\"1105\"")) {
                    sb.append(" · ⚠ 窗口越界（errno 1105，不是网络封锁）");
                }
            }
        } catch (Exception e) {
            sb.append(" ✗ HTTP 失败: ").append(e.getClass().getSimpleName())
                    .append(' ').append(e.getMessage());
        }
        return sb.toString();
    }

    /** The first readable bytes of a response (text only; binary becomes a note). */
    private static String head(byte[] body) {
        try {
            String s = new String(body, "UTF-8");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length() && sb.length() < 90; i++) {
                char c = s.charAt(i);
                sb.append(c >= 32 && c < 127 ? c : ' ');
            }
            String t = sb.toString().trim();
            return t.isEmpty() ? "(binary)" : "\"" + t + "\"";
        } catch (Exception e) {
            return "(binary)";
        }
    }
}
