package org.jianxue.ime.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.jianxue.ime.settings.Preferences;
import org.jianxue.ime.settings.SettingsActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** 使用 Android 实际剪贴板和 Keystore 验证敏感过滤、收藏加密与清除。 */
@RunWith(AndroidJUnit4.class)
public final class ClipboardHistoryTest {
    @Test public void sensitiveClipsAreSkippedAndPinnedClipsAreEncrypted() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        Activity activity = instrumentation.startActivitySync(new Intent(context, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipboardHistory history = new ClipboardHistory(context); history.clear();
            ClipData sensitive = ClipData.newPlainText("test", "sensitive-test-only");
            PersistableBundle extras = new PersistableBundle(); extras.putBoolean("android.content.extra.IS_SENSITIVE", true); sensitive.getDescription().setExtras(extras);
            instrumentation.runOnMainSync(() -> manager.setPrimaryClip(sensitive));
            assertFalse(history.open(true).contains("sensitive-test-only"));
            instrumentation.runOnMainSync(() -> manager.setPrimaryClip(ClipData.newPlainText("test", "pinned-test-only")));
            assertTrue(history.open(true).contains("pinned-test-only")); history.togglePin("pinned-test-only");
            String saved = Preferences.store(context).getString("clipboard_pinned", "");
            assertFalse(saved.isEmpty()); assertFalse(saved.contains("pinned-test-only"));
            assertTrue(new ClipboardHistory(context).open(false).contains("pinned-test-only"));
            history.clear(); assertTrue(new ClipboardHistory(context).open(false).isEmpty());
            assertFalse(Preferences.store(context).contains("clipboard_pinned"));
        } finally { instrumentation.runOnMainSync(activity::finish); }
    }
}
