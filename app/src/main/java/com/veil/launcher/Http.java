package com.veil.launcher;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * One HTTP client for the whole app.
 * <p>
 * The box runs Android 7.1.2, whose bundled CA store predates the roots today's CDNs chain to
 * ("Trust anchor for certification path not found" — Apple's aerial CDN and the 360 image CDN
 * both died in the TLS handshake, while bing.com kept working because its root is old enough to
 * be in that store). So: try the SYSTEM trust store first, and only when the handshake fails,
 * retry once without certificate validation. Plain HTTP is unaffected; a normal, valid
 * certificate still gets the strict treatment.
 */
final class Http {
    private static final String TAG = "TvHome.Http";
    /** The browser-ish UA the picture CDNs answer to. */
    static final String UA = "Mozilla/5.0 (Linux; Android 7.1.2) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private Http() {
    }

    /**
     * GETs {@code url} into memory. {@code range} is an optional "bytes=start-end" header,
     * {@code maxBytes} caps the read. Throws on any non-2xx answer or I/O failure.
     */
    static byte[] getBytes(String url, int connectMs, int readMs, String range, int maxBytes)
            throws Exception {
        try {
            return fetch(url, connectMs, readMs, range, maxBytes, false);
        } catch (SSLException bad) {
            Log.w(TAG, "TLS handshake failed (" + bad.getMessage() + "), retrying leniently: " + url);
            return fetch(url, connectMs, readMs, range, maxBytes, true);
        }
    }

    /** GETs {@code url} as text (UTF-8). */
    static String getString(String url, int connectMs, int readMs) throws Exception {
        return new String(getBytes(url, connectMs, readMs, null, 4 * 1024 * 1024), "UTF-8");
    }

    /**
     * Streams {@code url} straight to {@code out} on disk instead of buffering it in memory.
     * <p>
     * The aerial clips are 48-80 MB heads of HEVC video: reading one into a byte[] on a box with
     * a few hundred MB of RAM died with an OutOfMemoryError — which is an Error, so it slipped
     * past every {@code catch (Exception)} and the expansion silently reported "0 added". Writing
     * the stream to the file as it arrives keeps the footprint at one 16 KB buffer.
     *
     * @return how many bytes landed in the file
     */
    static long downloadToFile(String url, int connectMs, int readMs, String range,
                               long maxBytes, java.io.File out) throws Exception {
        try {
            return stream(url, connectMs, readMs, range, maxBytes, out, false);
        } catch (SSLException bad) {
            Log.w(TAG, "TLS handshake failed (" + bad.getMessage() + "), retrying leniently: " + url);
            return stream(url, connectMs, readMs, range, maxBytes, out, true);
        }
    }

    private static long stream(String url, int connectMs, int readMs, String range,
                                long maxBytes, java.io.File out, boolean trustAll) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (range != null) c.setRequestProperty("Range", range);
            if (trustAll && c instanceof HttpsURLConnection) {
                HttpsURLConnection h = (HttpsURLConnection) c;
                SSLSocketFactory f = lenientFactory();
                if (f != null) h.setSSLSocketFactory(f);
                h.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new java.io.IOException("HTTP " + code + " @ " + url);
            }
            long written = 0;
            try (InputStream in = c.getInputStream();
                 java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[32768];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fo.write(buf, 0, n);
                    written += n;
                    if (maxBytes > 0 && written >= maxBytes) break;
                }
                fo.flush();
            }
            return written;
        } finally {
            c.disconnect();
        }
    }

    /** GETs {@code url} as text, or null when the network gave up (no exception thrown). */
    static String getStringOrNull(String url, int connectMs, int readMs) {
        try {
            return getString(url, connectMs, readMs);
        } catch (Exception e) {
            Log.w(TAG, "GET failed: " + url + " -> " + e);
            return null;
        }
    }

    private static byte[] fetch(String url, int connectMs, int readMs, String range,
                                int maxBytes, boolean trustAll) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(connectMs);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            if (range != null) c.setRequestProperty("Range", range);
            if (trustAll && c instanceof HttpsURLConnection) {
                HttpsURLConnection h = (HttpsURLConnection) c;
                SSLSocketFactory f = lenientFactory();
                if (f != null) h.setSSLSocketFactory(f);
                h.setHostnameVerifier(new HostnameVerifier() {
                    @Override
                    public boolean verify(String hostname, SSLSession session) {
                        return true;
                    }
                });
            }
            int code = c.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new java.io.IOException("HTTP " + code + " @ " + url);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (maxBytes > 0 && bo.size() >= maxBytes) break;
                }
            }
            return bo.toByteArray();
        } finally {
            c.disconnect();
        }
    }

    /** A socket factory that accepts any certificate — only used after the strict one failed. */
    private static SSLSocketFactory lenientFactory() {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            Log.w(TAG, "lenient SSL factory failed: " + e);
            return null;
        }
    }
}
