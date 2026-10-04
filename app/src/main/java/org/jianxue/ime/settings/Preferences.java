package org.jianxue.ime.settings;

import android.content.Context;
import android.content.SharedPreferences;
import org.jianxue.ime.engine.EngineHost;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Collections;

public final class Preferences {
    private Preferences() {}
    public static SharedPreferences store(Context context) { return context.getSharedPreferences("preferences", Context.MODE_PRIVATE); }
    public static JSONObject configuration(Context context) {
        SharedPreferences values = store(context);
        return EngineHost.object("language", values.getString("language", "en"), "scheme", values.getString("scheme", "pinyin"),
                "traditional", values.getBoolean("traditional", false), "learning", values.getBoolean("learning", true),
                "neural", values.getBoolean("neural", true), "fuzzy", values.getBoolean("fuzzy", false),
                "cloud", values.getBoolean("cloud", false), "endpoint", values.getString("endpoint", ""),
                "model", values.getString("model", ""), "api_key", values.getBoolean("cloud", false) ? Secrets.read(context) : "",
                "domains", new JSONArray(values.getStringSet("domains", Collections.emptySet())),
                "full_width", values.getBoolean("full_width", true), "auxiliary", values.getBoolean("auxiliary", false),
                "phrases", phrases(context));
    }
    public static JSONArray phrases(Context context) {
        try { return new JSONArray(store(context).getString("phrases", "[]")); }
        catch (org.json.JSONException invalid) { return new JSONArray(); }
    }
}
