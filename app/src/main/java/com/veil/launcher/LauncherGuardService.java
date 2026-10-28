package com.veil.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * The invisible launcher watchdog (the owner's ask, modelled on at4k's accessibility keep-alive):
 * whenever another HOME/launcher app crawls to the foreground, this service pulls OUR desktop back
 * to the front, so the parents' TV stays on this launcher and never silently drifts to the box's
 * stock home.
 * <p>
 * It is deliberately scoped to rival launchers only — opening Netflix, settings, or any normal app
 * is untouched, so the TV stays fully usable. The guard is on by default and can be switched off in
 * Settings; without the Accessibility permission enabled this service does nothing.
 */
public class LauncherGuardService extends AccessibilityService {

    /** Every other HOME app on the box (Android 11 visibility included); refreshed at most once a minute. */
    private Set<String> otherHomes = Collections.emptySet();
    private long cachedAt;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null || getPackageName().contentEquals(pkg)) return;
        if (!new PrefsStore(this).isGuardEnabled()) return;
        if (otherHomePackages().contains(pkg.toString())) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
    }

    /** Every HOME/launcher package on the box except this app; that is the set we must not yield to. */
    private Set<String> otherHomePackages() {
        long now = System.currentTimeMillis();
        if (!otherHomes.isEmpty() && now - cachedAt < 60_000L) return otherHomes;
        Set<String> out = new HashSet<>();
        try {
            Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            for (ResolveInfo ri : getPackageManager().queryIntentActivities(probe, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (!getPackageName().equals(pkg)) out.add(pkg);
            }
        } catch (Exception e) {
            android.util.Log.w("TvHome", "guard: home list unavailable: " + e);
        }
        otherHomes = out;
        cachedAt = now;
        return out;
    }

    @Override
    public void onInterrupt() {
    }
}
