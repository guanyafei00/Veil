package com.veil.launcher;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * In-app update check + self-update.
 *
 * <p>Reads a hosted manifest (JSON) and, when its {@code versionCode} exceeds the installed one,
 * lets the owner download the APK and install it from inside the launcher — no PC / adb needed.
 *
 * <p>Manifest shape (host the JSON anywhere the box can reach):
 * <pre>
 * {
 *   "versionCode": 218,
 *   "versionName": "2.1.7",
 *   "apkUrl": "https://.../Veil-TV-full-debug.apk",        // used unless a flavor URL is given
 *   "apkUrlFull": "https://.../Veil-TV-full-debug.apk",     // optional, overrides apkUrl for full
 *   "apkUrlLite": "https://.../Veil-TV-lite-debug.apk",     // optional, overrides apkUrl for lite
 *   "notes": "Bug fixes and a new in-app updater.",
 *   "mandatory": false
 * }
 * </pre>
 *
 * The manifest URL is configured per-device in Settings (Update server address), falling back to
 * the {@link #DEFAULT_MANIFEST_URLS} list — the first reachable source wins, so a single dead CDN
 * mirror cannot break update checks.
 * The download lands in {@code getFilesDir()/updates} and is handed to the system installer through
 * a {@code FileProvider} on API 24+ (the {@code file://} path is used on older boxes like the T1).
 */
final class Updater {

    /** Build-time fallback manifest sources, tried in order. cdn.jsdelivr.net has been seen
     *  returning 403 for entire regions, so every mirror must have an independent fallback. */
    static final String[] DEFAULT_MANIFEST_URLS = {
            "https://cdn.jsdelivr.net/gh/guanyafei00/Veil@main/update.json",
            "https://fastly.jsdelivr.net/gh/guanyafei00/Veil@main/update.json",
            "https://gcore.jsdelivr.net/gh/guanyafei00/Veil@main/update.json",
            "https://raw.githubusercontent.com/guanyafei00/Veil/main/update.json",
    };

    /** Last-resort version code if PackageManager is unavailable (almost never used). */
    private static final int FALLBACK_VERSION_CODE = 220;

    private static final String TAG = "Veil.Update";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000; // at most once a day
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Updater() {
    }

    /** True when the manifest describes a newer version than the one installed. */
    static boolean isNewer(JSONObject m, int installedCode) {
        try {
            return m.optInt("versionCode", installedCode) > installedCode;
        } catch (Exception e) {
            return false;
        }
    }

    /** Best APK URL for this build's flavor out of the manifest. */
    static String apkUrlFor(JSONObject m, Context ctx) {
        String flavor = ctx.getString(R.string.flavor_name);
        if ("lite".equals(flavor) && !m.optString("apkUrlLite", "").isEmpty()) {
            return m.optString("apkUrlLite");
        }
        if ("full".equals(flavor) && !m.optString("apkUrlFull", "").isEmpty()) {
            return m.optString("apkUrlFull");
        }
        return m.optString("apkUrl", "");
    }

    /** Manifest URL candidates: per-device setting if set, else the build-time source list. */
    static String[] manifestUrls(Context ctx) {
        String url = new PrefsStore(ctx).loadUpdateUrl();
        if (url != null && !url.trim().isEmpty()) return new String[]{url.trim()};
        return DEFAULT_MANIFEST_URLS;
    }

    /** The primary manifest URL to use: per-device setting, else the first build-time default. */
    static String manifestUrl(Context ctx) {
        return manifestUrls(ctx)[0];
    }

    static int installedVersionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            if (Build.VERSION.SDK_INT >= 28) return (int) pi.getLongVersionCode();
            return pi.versionCode;
        } catch (Exception e) {
            return FALLBACK_VERSION_CODE;
        }
    }

    static String installedVersionName(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** Best-effort, silent auto-check on launch: rate-limited and respects the auto-check pref. */
    static void maybeAutoCheck(Context ctx) {
        PrefsStore prefs = new PrefsStore(ctx);
        if (!prefs.isUpdateAuto()) return;
        if (manifestUrl(ctx).isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - prefs.loadUpdateLastCheck() < AUTO_CHECK_INTERVAL_MS) return;
        prefs.saveUpdateLastCheck(now);
        check(ctx, false);
    }

    /** Check now. {@code manual=true} reports every outcome; {@code manual=false} only a new version. */
    static void check(final Context ctx, final boolean manual) {
        final String[] urls = manifestUrls(ctx);
        if (urls.length == 0 || urls[0].isEmpty()) {
            if (manual) toast(ctx, R.string.update_no_url);
            return;
        }
        if (manual) toast(ctx, R.string.update_checking);
        final int installed = installedVersionCode(ctx);
        new Thread(() -> {
            JSONObject m = null;
            for (String url : urls) {
                m = fetchManifest(url);
                if (m != null) break;
                Log.w(TAG, "manifest source failed, trying next: " + url);
            }
            if (m == null) {
                if (manual) {
                    MAIN.post(() -> toast(ctx,
                            ctx.getString(R.string.update_check_failed, "network unreachable or unreadable manifest")));
                }
                return;
            }
            final JSONObject manifest = m;
            if (isNewer(manifest, installed)) {
                MAIN.post(() -> showUpdateDialog(ctx, manifest, installed));
            } else if (manual) {
                MAIN.post(() -> toast(ctx, ctx.getString(R.string.update_latest, installedVersionName(ctx))));
            }
        }).start();
    }

    private static JSONObject fetchManifest(String url) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return new JSONObject(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.w(TAG, "manifest fetch failed: " + e);
            return null;
        }
    }

    private static void showUpdateDialog(final Context ctx, final JSONObject m, final int installed) {
        String newName = m.optString("versionName", "?");
        Log.i(TAG, "update available: v" + newName + " (installed v" + installed + ")");
        String notes = m.optString("notes", "");
        boolean mandatory = m.optBoolean("mandatory", false);
        String body = ctx.getString(R.string.update_available_msg, newName,
                installedVersionName(ctx), notes.isEmpty() ? "(no release notes)" : notes);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(ctx, R.style.VeilDialog)
                .setTitle(R.string.update_available)
                .setMessage(body)
                .setPositiveButton(R.string.update_now, (d, w) -> downloadAndInstall(ctx, m));
        if (!mandatory) b.setNegativeButton(R.string.update_later, null);
        b.setCancelable(!mandatory);
        Dialogs.show(b.create());
    }

    private static void downloadAndInstall(final Context ctx, final JSONObject m) {
        final String apkUrl = apkUrlFor(m, ctx);
        if (apkUrl.isEmpty()) {
            toast(ctx, ctx.getString(R.string.update_check_failed, "manifest missing apkUrl"));
            return;
        }
        Log.i(TAG, "downloading update APK from " + apkUrl);
        final File apk = new File(ctx.getFilesDir(), "updates/veil-update.apk");
        apk.getParentFile().mkdirs();
        final ProgressDialog pd = new ProgressDialog(ctx, R.style.VeilDialog);
        pd.setMessage(ctx.getString(R.string.update_downloading, 0));
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();
        Dialogs.style(pd);
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(apkUrl).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(15000);
                c.setInstanceFollowRedirects(true);
                if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                int total = c.getContentLength();
                InputStream in = c.getInputStream();
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[16384];
                long got = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) {
                        final int p = (int) (got * 100 / total);
                        MAIN.post(() -> pd.setProgress(p));
                    }
                }
                in.close();
                out.close();
                MAIN.post(() -> {
                    try {
                        pd.dismiss();
                    } catch (Exception ignore) {
                    }
                    installApk(ctx, apk);
                });
            } catch (Exception e) {
                Log.w(TAG, "download failed: " + e);
                final String why = e.getMessage() == null ? "download error" : e.getMessage();
                MAIN.post(() -> {
                    try {
                        pd.dismiss();
                    } catch (Exception ignore) {
                    }
                    toast(ctx, ctx.getString(R.string.update_download_failed, why));
                });
            }
        }).start();
    }

    private static void installApk(Context ctx, File apk) {
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Uri uri = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", apk);
                intent = new Intent(Intent.ACTION_INSTALL_PACKAGE, uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                apk.setReadable(true, false);
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive");
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "install intent failed: " + e);
            toast(ctx, ctx.getString(R.string.update_download_failed, "cannot start installer: " + e.getMessage()));
        }
    }

    private static void toast(Context ctx, int res) {
        toast(ctx, ctx.getString(res));
    }

    private static void toast(final Context ctx, final String s) {
        MAIN.post(() -> android.widget.Toast.makeText(ctx, s, android.widget.Toast.LENGTH_LONG).show());
    }
}
