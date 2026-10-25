package com.veil.launcher;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A folder of apps on the home grid (inspired by AT4K's folders): a tile that opens a small list of
 * the apps inside it, optionally behind a numeric password so the owner can keep private apps out
 * of sight. The password is a plain 4-digit string kept in SharedPreferences — enough to stop a
 * curious family member, not a security boundary; nothing here touches the network.
 */
final class Folder {
    /** Stable id used for the tile's component; survives reloads. */
    final String id;
    String name;
    /** 4 digits, or empty when the folder is not locked. */
    String password = "";
    final List<String> apps = new ArrayList<>();

    Folder(String id, String name) {
        this.id = id;
        this.name = name;
    }

    boolean isLocked() {
        return password != null && password.length() > 0;
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        o.put("password", password == null ? "" : password);
        o.put("apps", new JSONArray(apps));
        return o;
    }

    static Folder fromJson(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("id", "").trim();
        if (id.isEmpty()) return null;
        Folder f = new Folder(id, o.optString("name", "").trim());
        f.password = o.optString("password", "").trim();
        JSONArray apps = o.optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length(); i++) {
                String p = apps.optString(i);
                if (p != null && !p.trim().isEmpty()) f.apps.add(p.trim());
            }
        }
        return f;
    }
}
