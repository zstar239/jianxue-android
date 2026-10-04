package org.jianxue.ime.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;
import org.jianxue.ime.settings.Secrets;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** 仅在用户打开面板时读取纯文本；手动收藏才加密落盘。 */
final class ClipboardHistory {
    private final Context context;
    private final List<String> recent = new ArrayList<>(), pinned = new ArrayList<>();
    ClipboardHistory(Context context) { this.context = context; }
    List<String> open(boolean readCurrent) {
        pinned.clear();
        try {
            JSONArray data = new JSONArray(Secrets.readLocal(context, "clipboard_pinned"));
            for (int i = 0; i < Math.min(20, data.length()); i++) pinned.add(data.getString(i));
        } catch (org.json.JSONException empty) { /* 没有收藏。 */ }
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        try {
            ClipData clip = readCurrent ? manager.getPrimaryClip() : null;
            if (clip != null && clip.getItemCount() > 0 && (clip.getDescription().getExtras() == null
                    || !clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false))) {
                CharSequence text = clip.getItemAt(0).getText();
                if (text != null && text.length() > 0 && text.length() <= 4000) {
                    recent.remove(text.toString()); recent.add(0, text.toString());
                    while (recent.size() > 20) recent.remove(recent.size() - 1);
                }
            }
        } catch (SecurityException unavailable) { /* 当前窗口没有剪贴板读取权限。 */ }
        List<String> all = new ArrayList<>(pinned);
        for (String text : recent) if (!all.contains(text)) all.add(text);
        return all;
    }
    boolean pinned(String text) { return pinned.contains(text); }
    void togglePin(String text) {
        if (!pinned.remove(text)) { pinned.add(0, text); while (pinned.size() > 20) pinned.remove(pinned.size() - 1); }
        save();
    }
    void remove(String text) { recent.remove(text); pinned.remove(text); save(); }
    void clear() { recent.clear(); pinned.clear(); save(); }
    void clearTransient() { recent.clear(); pinned.clear(); }
    private void save() {
        try { Secrets.saveLocal(context, "clipboard_pinned", pinned.isEmpty() ? "" : new JSONArray(pinned).toString()); }
        catch (Exception unavailable) { Toast.makeText(context, "无法加密保存收藏", Toast.LENGTH_SHORT).show(); }
    }
}
