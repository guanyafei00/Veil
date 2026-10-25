package com.veil.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Enumeration;

/**
 * A tiny embedded HTTP server behind the "scan to upload wallpaper" feature. The TV shows a
 * QR code pointing at http://&lt;tv-ip&gt;:8790/?t=&lt;token&gt;; the phone opens the page, picks a
 * photo, the page downscales it on a canvas and POSTs the raw JPEG back to /upload?t=token.
 * The token guards against anyone else on the LAN sneaking an upload while the page is up.
 * Single client at a time, five-minute lifetime, writes Config.WALLPAPER_NAME and calls back
 * on the main thread — MainActivity re-reads the wallpaper on resume, so no extra plumbing.
 */
final class WallpaperServer {

    interface Listener {
        /** Called on the main thread after wallpaper.jpg was written. */
        void onUploaded();
    }

    private static final int PORT = 8790;
    private static final int MAX_BODY = 25 * 1024 * 1024;
    private static final long LIFETIME_MS = 5 * 60 * 1000;

    private final Context context;
    private final String token;
    private final Listener listener;
    private final Runnable timeout = this::stop;
    private ServerSocket server;
    private Thread thread;
    private volatile boolean running;

    WallpaperServer(Context context, String token, Listener listener) {
        this.context = context;
        this.token = token;
        this.listener = listener;
    }

    /** Starts accepting; returns the URL to encode into the QR code, or null on failure. */
    String start() {
        if (running) return null;
        String ip = localIp();
        if (ip == null) return null;
        try {
            server = new ServerSocket(PORT, 2, InetAddress.getByName("0.0.0.0"));
        } catch (IOException e) {
            android.util.Log.w("TvHome", "wallpaper server bind failed: " + e);
            return null;
        }
        running = true;
        thread = new Thread(this::acceptLoop, "wallpaper-server");
        thread.start();
        new android.os.Handler(android.os.Looper.getMainLooper())
                .postDelayed(timeout, LIFETIME_MS);
        return "http://" + ip + ":" + PORT + "/?t=" + token;
    }

    synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        server = null;
    }

    private void acceptLoop() {
        while (running) {
            try (Socket sock = server.accept()) {
                sock.setSoTimeout(10_000);
                handle(sock);
            } catch (SocketTimeoutException dropped) {
                // accept() timed out; loop re-checks `running` and exits if stopped.
            } catch (Exception e) {
                if (running) android.util.Log.d("TvHome", "wallpaper request failed: " + e);
            }
        }
    }

    private void handle(Socket sock) throws IOException {
        InputStream in = sock.getInputStream();
        String head = readHead(in);
        if (head == null) return;
        String line = head.substring(0, head.indexOf("\r\n"));
        String[] parts = line.split(" ");
        if (parts.length < 2) return;
        String path = parts[1];

        if (parts[0].equals("POST") && path.startsWith("/upload")) {
            int len = 0;
            for (String h : head.split("\r\n")) {
                if (h.toLowerCase().startsWith("content-length:"))
                    len = parseCl(h.substring(15).trim());
            }
            if (len <= 0 || len > MAX_BODY) {
                respond(sock, "413 Payload Too Large", "too large");
                return;
            }
            byte[] body = readBody(in, len);
            if (body == null || body.length < 4
                    || (body[0] & 0xFF) != 0xFF || (body[1] & 0xFF) != 0xD8) {
                respond(sock, "400 Bad Request", "not a jpeg");
                return;
            }
            File dir = Config.dir(context);
            if (dir == null) {
                respond(sock, "500 Server Error", "no storage");
                return;
            }
            File target = new File(dir, Config.WALLPAPER_NAME);
            File tmp = new File(dir, Config.WALLPAPER_NAME + ".up");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(body);
            }
            if (target.isFile() && !target.delete() || !tmp.renameTo(target)) {
                tmp.delete();
                respond(sock, "500 Server Error", "save failed");
                return;
            }
            respond(sock, "200 OK", "ok");
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (listener != null) listener.onUploaded();
            });
            return;
        }
        if (parts[0].equals("GET") && (path.startsWith("/?t=") || path.equals("/"))) {
            if (!path.contains("t=" + token)) {
                respond(sock, "403 Forbidden", "bad token");
                return;
            }
            byte[] page = page().getBytes("UTF-8");
            sock.getOutputStream().write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: text/html; charset=utf-8\r\n"
                    + "Content-Length: " + page.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes("UTF-8"));
            sock.getOutputStream().write(page);
        } else {
            respond(sock, "404 Not Found", "nope");
        }
    }

    private static int parseCl(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Reads headers up to the blank line (8 KB cap), so the body read starts clean. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            buf.write(c);
            int n = buf.size();
            if (n >= 4) {
                byte[] b = buf.toByteArray();
                if (b[n - 4] == '\r' && b[n - 3] == '\n' && b[n - 2] == '\r' && b[n - 1] == '\n')
                    return new String(b, "UTF-8");
            }
            if (n > 8192) return null;
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        byte[] body = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(body, off, len - off);
            if (n <= 0) return null;
            off += n;
        }
        return body;
    }

    private void respond(Socket sock, String status, String text) throws IOException {
        byte[] body = text.getBytes("UTF-8");
        OutputStream out = sock.getOutputStream();
        out.write(("HTTP/1.1 " + status + "\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n").getBytes("UTF-8"));
        out.write(body);
    }

    /** The phone-facing upload page; canvas downscales to 4K max before the POST. */
    private String page() {
        return "<!doctype html><html><head><meta charset=utf-8>"
                + "<meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>Upload</title></head>"
                + "<body style=\"font-family:sans-serif;background:#111;color:#eee;"
                + "text-align:center;padding:2em\">"
                + "<h3>Upload wallpaper to the TV</h3>"
                + "<input type=file accept=\"image/*\" style=\"font-size:1.1em;margin:1em 0\">"
                + "<div id=st style=\"margin-top:1em;color:#8f8\">Pick a picture</div>"
                + "<script>"
                + "var inp=document.querySelector('input'),st=document.getElementById('st');"
                + "inp.onchange=function(){var f=inp.files[0];if(!f)return;"
                + "st.textContent='Processing...';var img=new Image();"
                + "img.onload=function(){var max=3840,w=img.width,h=img.height;"
                + "var s=Math.min(1,max/Math.max(w,h));w=Math.round(w*s);h=Math.round(h*s);"
                + "var c=document.createElement('canvas');c.width=w;c.height=h;"
                + "c.getContext('2d').drawImage(img,0,0,w,h);"
                + "c.toBlob(function(blob){"
                + "fetch('/upload?t=" + token + "',{method:'POST',body:blob})"
                + ".then(function(r){st.textContent=r.ok?'\\u2713 Done, check the TV':'\\u2717 Failed ('+r.status+')';})"
                + ".catch(function(){st.textContent='\\u2717 Network error, try again';});"
                + "},'image/jpeg',0.9);};"
                + "img.onerror=function(){st.textContent='\\u2717 Cannot read the image';};"
                + "img.src=URL.createObjectURL(f);};"
                + "</script></body></html>";
    }

    /** First site-local IPv4 across the interfaces — good enough for a LAN QR link. */
    private static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isSiteLocalAddress() || !(a.getAddress().length == 4)) continue;
                    String host = a.getHostAddress();
                    if (host.startsWith("192.168.") || host.startsWith("10.")) return host;
                    if (fallback == null) fallback = host;
                }
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }
}
