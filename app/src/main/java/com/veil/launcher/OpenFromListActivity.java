package com.veil.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * The app-list entry (the owner's N1 at Android 7.1.2 has a phone-style launcher that hides any
 * app declaring CATEGORY_HOME, so the desktop itself never shows an icon there). This thin activity
 * carries only CATEGORY_LAUNCHER: it shows up in every app list and simply forwards into the
 * desktop, then disappears.
 */
public class OpenFromListActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        finish();
    }
}
