package org.jianxue.ime;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import org.jianxue.ime.engine.EngineHost;
import org.jianxue.ime.engine.NativeEngine;
import org.jianxue.ime.settings.SettingsActivity;
import org.json.JSONObject;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 在 Android 运行时中验证系统输入连接和密码/禁止学习的实际输入。 */
@RunWith(AndroidJUnit4.class)
public final class InputMethodTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Instrumentation getInstrumentation() { return instrumentation; }
    private Activity activity;
    private EditText editor;

    @Before public void setUp() throws Exception {
        Intent intent = new Intent(getInstrumentation().getTargetContext(), SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = getInstrumentation().startActivitySync(intent);
        getInstrumentation().runOnMainSync(() -> editor = findEditor(activity.getWindow().getDecorView()));
        assertNotNull(editor);
        totalCommits();
        prepare(android.text.InputType.TYPE_CLASS_TEXT, 0);
    }
    @After public void tearDown() throws Exception {
        if (activity != null) getInstrumentation().runOnMainSync(() -> activity.finish());
    }
    private EditText findEditor(View view) {
        if (view instanceof EditText) return (EditText) view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText found = findEditor(group.getChildAt(i)); if (found != null) return found;
        }
        return null;
    }
    private void prepare(int type, int options) throws Exception {
        getInstrumentation().runOnMainSync(() -> {
            editor.setText(""); editor.setInputType(type); editor.setImeOptions(options); editor.requestFocus();
            InputMethodManager manager = (InputMethodManager) activity.getSystemService(Activity.INPUT_METHOD_SERVICE);
            manager.restartInput(editor); manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
        Thread.sleep(1600);
        awaitEditorFocus();
    }
    private void awaitEditorFocus() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean focused = new java.util.concurrent.atomic.AtomicBoolean();
        for (int i = 0; i < 240; i++) {
            getInstrumentation().runOnMainSync(() -> focused.set(editor.hasWindowFocus() && editor.isFocused()));
            if (focused.get()) return;
            Thread.sleep(250);
        }
        fail("试输入框未取得窗口焦点，请检查设备锁屏或系统弹窗");
    }
    private String text() {
        AtomicReference<String> value = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> value.set(editor.getText().toString()));
        return value.get();
    }
    private void type(String input) throws Exception {
        awaitEditorFocus();
        getInstrumentation().sendStringSync(input);
        Thread.sleep(250);
    }
    private void awaitText(String expected) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (text().equals(expected)) return;
            Thread.sleep(250);
        }
        assertEquals(expected, text());
    }
    private long totalCommits() throws Exception {
        CountDownLatch done = new CountDownLatch(1); AtomicReference<JSONObject> value = new AtomicReference<>();
        EngineHost.get(activity).dispatch(EngineHost.object("kind", "stats"), result -> { value.set(result); done.countDown(); });
        assertTrue("本地资源加载超时", done.await(240, TimeUnit.SECONDS));
        assertFalse(value.get().toString(), value.get().has("error"));
        return value.get().getJSONObject("total").getLong("commits");
    }
    @Test public void testPinyinCommitsThroughSystemInputConnection() throws Exception {
        type("nihao");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE);
        awaitText("你好");
        assertEquals("你好", text());
    }
    @Test public void testPasswordIsDirectAndDoesNotIncreaseStats() throws Exception {
        prepare(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, 0);
        long before = totalCommits(); type("s3cret");
        awaitText("s3cret"); assertEquals(before, totalCommits());
    }
    @Test public void testNoPersonalizedLearningStillConvertsWithoutStats() throws Exception {
        prepare(android.text.InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        long before = totalCommits(); type("nihao");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE);
        awaitText("你好");
        assertEquals("你好", text()); assertEquals(before, totalCommits());
    }
    @Test public void testDeletingLastComposingLetterRemovesItFromEditor() throws Exception {
        type("a");
        awaitText("a");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL);
        awaitText("");
        assertEquals("", text());
        type("nihao");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE);
        awaitText("你好");
        assertEquals("你好", text());
    }
    @Test public void testAndroidCloudClientInitializesWithValidatedTls() throws Exception {
        String data = new java.io.File(activity.getFilesDir(), "data/data-v3-0.1.0").getAbsolutePath();
        String user = new java.io.File(activity.getCacheDir(), "tls-probe").getAbsolutePath();
        // 只创建客户端，不提交请求，不发送任何测试文字。
        long handle = NativeEngine.create(data, user, EngineHost.object("language", "off", "neural", false,
                "cloud", true, "endpoint", "https://example.invalid/v1", "model", "test", "api_key", "dummy").toString());
        try {
            JSONObject frame = new JSONObject(NativeEngine.dispatch(handle, "{\"kind\":\"frame\"}"));
            assertTrue(frame.toString(), frame.isNull("error"));
            assertTrue(frame.toString(), frame.isNull("warning"));
        } finally { NativeEngine.destroy(handle); }
    }

    private android.view.accessibility.AccessibilityNodeInfo findDescription(android.view.accessibility.AccessibilityNodeInfo root, String description) {
        if (root == null) return null;
        CharSequence value = root.getContentDescription();
        if (value != null && value.toString().startsWith(description)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = findDescription(root.getChild(i), description);
            if (found != null) return found;
        }
        return null;
    }
    private void tapDescription(String description) throws Exception {
        android.app.UiAutomation automation = getInstrumentation().getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info = automation.getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        android.view.accessibility.AccessibilityNodeInfo target = null;
        for (int i = 0; i < 120 && target == null; i++) {
            for (android.view.accessibility.AccessibilityWindowInfo window : automation.getWindows()) {
                target = findDescription(window.getRoot(), description); if (target != null) break;
            }
            if (target == null) Thread.sleep(250);
        }
        assertNotNull("未找到触摸键：" + description, target);
        android.graphics.Rect bounds = new android.graphics.Rect(); target.getBoundsInScreen(bounds);
        long now = android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try { assertTrue(automation.injectInputEvent(down, true)); assertTrue(automation.injectInputEvent(up, true)); }
        finally { down.recycle(); up.recycle(); }
        Thread.sleep(350);
    }
    @Test public void testNineKeyTouchAndExpandedCandidatesCommitThroughEditor() throws Exception {
        getInstrumentation().runOnMainSync(() -> org.jianxue.ime.settings.Preferences.store(activity).edit().putString("layout", "t9").putString("scheme", "pinyin").apply());
        prepare(android.text.InputType.TYPE_CLASS_TEXT, 0);
        for (char digit : "64426".toCharArray()) tapDescription("九键" + digit);
        awaitText("64426");
        tapDescription("展开候选"); tapDescription("候选 你好");
        awaitText("你好");
        tapDescription("常用工具"); tapDescription("9 / 26 键");
        assertEquals("qwerty", org.jianxue.ime.settings.Preferences.store(activity).getString("layout", ""));
        tapDescription("n"); tapDescription("i");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_SPACE);
        awaitText("你好你");
    }
    @Test public void testClipboardEmojiAndCursorToolsUseSystemEditor() throws Exception {
        android.content.ClipboardManager clipboard = (android.content.ClipboardManager) activity.getSystemService(Activity.CLIPBOARD_SERVICE);
        getInstrumentation().runOnMainSync(() -> clipboard.setPrimaryClip(android.content.ClipData.newPlainText("test", "工具测试")));
        tapDescription("剪贴板"); tapDescription("粘贴剪贴板文本"); awaitText("工具测试");
        tapDescription("返回键盘"); tapDescription("表情面板"); tapDescription("表情 😀"); awaitText("工具测试😀");
        tapDescription("返回键盘"); tapDescription("常用工具"); tapDescription("光标编辑"); tapDescription("全选"); tapDescription("复制");
        java.util.concurrent.atomic.AtomicReference<String> copied = new java.util.concurrent.atomic.AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> copied.set(clipboard.getPrimaryClip().getItemAt(0).getText().toString()));
        assertEquals("工具测试😀", copied.get());
        tapDescription("→"); tapDescription("←");
        java.util.concurrent.atomic.AtomicInteger position = new java.util.concurrent.atomic.AtomicInteger();
        getInstrumentation().runOnMainSync(() -> position.set(editor.getSelectionStart()));
        assertEquals(4, position.get());
    }
}
