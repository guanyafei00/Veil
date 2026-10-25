package com.veil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The desktop lock (the owner's ask): while "桌面锁定" is on in the settings panel, any attempt to
 * reach the system Settings app or another home app bounces straight back to this home screen with
 * GLOBAL_ACTION_HOME. Those are the escape hatches that undo a launcher (change the default home,
 * return to the stock desktop, uninstall, clear defaults), so closing them pins this desktop as
 * THE desktop. Normal apps — video, games — are untouched, the TV stays usable.
 * <p>
 * The blocked home apps are not a hardcoded list: every package on the box that declares a HOME
 * launcher activity, except this app, is blocked — so the box's own stock desktop is caught
 * whatever it is called, and a second launcher installed later is caught too. The settings
 * packages are the fixed part: they are where the default home gets changed.
 * <p>
 * The toggle and the 4-digit password live in PrefsStore ("lock_enabled"/"lock_password"); without
 * the switch on this service does nothing. Enable the service in system Accessibility settings
 * (the settings panel has a row that opens that page and shows whether it runs).
 */
public class LockService extends AccessibilityService {

    /** Where the default home gets changed; fixed part of the blocklist. */
    private static final Set<String> SETTINGS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.android.settings",              // AOSP / phone-style settings
            "com.android.tv.settings",           // Android TV settings
            "com.google.android.gms.settings"    // Google settings entry (some ROMs)
    )));

    /** Cached full blocklist (settings + every other home); refreshed at most once a minute. */
    private Set<String> blocked = Collections.emptySet();
    private long blockedAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().contentEquals(pkg)) return;
        if (!new PrefsStore(this).isLockEnabled()) return;
        if (blockedNow().contains(pkg.toString())) {
            performGlobalAction(GLOBAL_ACTION_HOME);
        }
    }

    /** Settings packages plus every other home app on the box (Android 11 visibility included). */
    private Set<String> blockedNow() {
        long now = System.currentTimeMillis();
        if (!blocked.isEmpty() && now - blockedAt < 60_000L) return blocked;
        Set<String> out = new HashSet<>(SETTINGS);
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(probe, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (!getPackageName().equals(pkg)) out.add(pkg);
            }
        } catch (Exception e) {
            // A package query that fails still leaves the settings packages blocked.
            android.util.Log.w("TvHome", "lock: home list unavailable: " + e);
        }
        blocked = out;
        blockedAt = now;
        return out;
    }

    @Override
    public void onInterrupt() {
    }
}
