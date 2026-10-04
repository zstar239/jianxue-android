package org.jianxue.ime;

import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;
import org.jianxue.ime.engine.EngineHost;
import org.jianxue.ime.engine.Frame;
import org.jianxue.ime.settings.Preferences;
import org.jianxue.ime.settings.SettingsActivity;
import org.jianxue.ime.ui.KeyboardView;
import org.json.JSONObject;

/** 系统输入连接、键盘界面与引擎事件之间的安卓平台壳。 */
public final class JianxueService extends InputMethodService implements KeyboardView.Listener {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SessionGuard guard = new SessionGuard();
    private EngineHost host;
    private KeyboardView keyboard;
    private Frame frame;
    private boolean english, numeric, password, privateInput, manualPrivate, active, ready;
    private int submitted;
    private int expectedSelection = -1;
    private String lastWarning = "";
    private String selectedForTranslation;
    private int selectionStart, selectionEnd, translatedStart, translatedEnd, pollsRemaining;
    private final Runnable idle = this::requestBackground;
    private final Runnable poll = this::pollBackground;
    private final Runnable flush = new Runnable() { public void run() { if (active) { host.flush(); handler.postDelayed(this, 60000); } } };

    @Override public void onCreate() { super.onCreate(); host = EngineHost.get(this); }
    @Override public View onCreateInputView() {
        keyboard = new KeyboardView(this, this);
        configureKeyboard();
        if (frame != null) keyboard.show(frame);
        return keyboard;
    }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting);
        long epoch = guard.next();
        handler.removeCallbacksAndMessages(null);
        active = true; ready = false; submitted = 0; expectedSelection = -1;
        password = EditorPolicy.password(info.inputType);
        numeric = EditorPolicy.numeric(info.inputType);
        privateInput = EditorPolicy.privateInput(info.inputType, info.imeOptions, manualPrivate);
        english = EditorPolicy.ascii(info.inputType) || numeric;
        frame = null;
        selectedForTranslation = null;
        selectionStart = info.initialSelStart; selectionEnd = info.initialSelEnd;
        configureKeyboard();
        if (keyboard != null) keyboard.status(password ? "安全输入" : "正在加载本地词库…");
        host.refreshConfiguration(result -> {
            if (!active || !guard.accepts(epoch)) return;
            if (result.has("error") && !result.isNull("error")) showError(result.optString("error"));
        });
        submit(EngineHost.object("kind", "start", "private", privateInput, "english", english), false);
        handler.postDelayed(flush, 60000);
    }
    @Override public void onStartInputView(EditorInfo info, boolean restarting) { super.onStartInputView(info, restarting); configureKeyboard(); if (frame != null && keyboard != null) keyboard.show(frame); }

    @Override public void onFinishInput() {
        active = false; guard.next();
        handler.removeCallbacksAndMessages(null);
        InputConnection connection = getCurrentInputConnection();
        if (connection != null) connection.finishComposingText();
        host.dispatch(EngineHost.object("kind", "finish"), null);
        frame = null;
        super.onFinishInput();
    }
    @Override public void onFinishInputView(boolean finishingInput) { handler.removeCallbacks(idle); handler.removeCallbacks(poll); host.flush(); super.onFinishInputView(finishingInput); }

    private void configureKeyboard() {
        if (keyboard == null) return;
        keyboard.configure(english, numeric, privateInput, password, Preferences.store(this).getBoolean("haptic", false), actionLabel());
    }
    private void refreshKeyboard() { configureKeyboard(); if (keyboard != null && frame != null) keyboard.show(frame); }
    private String actionLabel() {
        EditorInfo editor = getCurrentInputEditorInfo();
        if (editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "换行";
        return switch (editor.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEARCH -> "搜索"; case EditorInfo.IME_ACTION_SEND -> "发送";
            case EditorInfo.IME_ACTION_GO -> "前往"; case EditorInfo.IME_ACTION_NEXT -> "下一项";
            case EditorInfo.IME_ACTION_DONE -> "完成"; default -> "换行";
        };
    }

    @Override public void key(String value) {
        if (value.startsWith("pref:")) {
            String[] setting = value.split(":", 3);
            if (setting.length != 3) return;
            var preferences = Preferences.store(this);
            switch (setting[1]) {
                case "theme" -> { if (java.util.List.of("system", "light", "dark").contains(setting[2])) preferences.edit().putString("theme", setting[2]).apply(); }
                case "height" -> { int height; try { height = Integer.parseInt(setting[2]); } catch (NumberFormatException invalid) { return; } preferences.edit().putInt("key_height", Math.max(40, Math.min(64, height))).apply(); }
                case "haptic" -> preferences.edit().putBoolean("haptic", !preferences.getBoolean("haptic", false)).apply();
                default -> { return; }
            }
            refreshKeyboard(); return;
        }
        if (value.equals("layout")) {
            var preferences = Preferences.store(this);
            boolean toNine = !preferences.getString("layout", "t9").equals("t9") || !preferences.getString("scheme", "pinyin").equals("pinyin");
            finishComposition(() -> {
                var edit = preferences.edit().putString("layout", toNine ? "t9" : "qwerty");
                if (toNine) edit.putString("scheme", "pinyin"); edit.apply();
                host.refreshConfiguration(result -> { if (result.has("error") && !result.isNull("error")) showError(result.optString("error")); refreshKeyboard(); });
            }); return;
        }
        if (value.startsWith("edit:")) { finishComposition(() -> edit(value.substring(5))); return; }
        if (value.equals("settings")) { startActivity(new Intent(this, SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
        if (value.equals("globe")) {
            if (Build.VERSION.SDK_INT < 28 || !switchToNextInputMethod(false)) {
                ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker();
            }
            return;
        }
        if (value.equals("picker")) { ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker(); return; }
        if (value.equals("shift")) { if (keyboard != null) keyboard.toggleShift(); return; }
        if (value.equals("symbols")) { if (keyboard != null) keyboard.toggleSymbols(); return; }
        if (value.equals("mode")) { english = !english; configureKeyboard(); submit(EngineHost.object("kind", "mode", "english", english), true); return; }
        if (value.equals("private")) {
            manualPrivate = !manualPrivate;
            EditorInfo editor = getCurrentInputEditorInfo();
            if (editor != null) {
                InputConnection connection = getCurrentInputConnection();
                if (connection != null) connection.finishComposingText();
                onStartInput(editor, false);
            }
            return;
        }
        if (!active) return;
        if (value.startsWith("t9:")) {
            if (!password && !numeric && !english) submit(EngineHost.object("kind", "t9", "text", value.substring(3)), true);
            return;
        }
        if (value.equals("translate")) {
            if (privateInput) { showError("私密输入不联网翻译"); return; }
            InputConnection connection = getCurrentInputConnection();
            CharSequence selected = connection == null ? null : connection.getSelectedText(0);
            if (selected == null || selected.length() == 0) { showError("请先在输入框中选中文字"); return; }
            selectedForTranslation = selected.toString(); translatedStart = selectionStart; translatedEnd = selectionEnd;
            pollsRemaining = 100;
            submit(EngineHost.object("kind", "translate", "text", selectedForTranslation), false);
            handler.postDelayed(poll, 150);
            return;
        }
        if (password || numeric) { directKey(value); return; }
        JSONObject event;
        switch (value) {
            case "backspace", "left", "right" -> event = EngineHost.object("kind", value);
            case "space" -> event = EngineHost.object("kind", "space");
            case "completion" -> {
                if (frame != null && frame.translating) {
                    InputConnection connection = getCurrentInputConnection();
                    CharSequence selected = connection == null ? null : connection.getSelectedText(0);
                    if (selected == null || !selected.toString().equals(selectedForTranslation)
                            || selectionStart != translatedStart || selectionEnd != translatedEnd) {
                        showError("选区已变化，请重新翻译"); submit(EngineHost.object("kind", "clear"), false); return;
                    }
                }
                event = EngineHost.object("kind", "completion", "revision", frame == null ? 0 : frame.revision);
            }
            case "enter" -> {
                if (frame != null && !frame.raw.isEmpty() || submitted > 0) event = EngineHost.object("kind", "raw");
                else { enter(); return; }
            }
            default -> {
                if (value.length() != 1) return;
                event = EngineHost.object("kind", "key", "text", value);
                if (keyboard != null) keyboard.consumeShift();
            }
        }
        submit(event, true);
    }

    @Override public void spelling(String value, long revision) { if (active && !password) submit(EngineHost.object("kind", "spelling", "text", value, "revision", revision), true); }
    @Override public void insert(String value) {
        if (!active || value.isEmpty()) return;
        if (password || numeric) { InputConnection connection = getCurrentInputConnection(); if (connection != null) connection.commitText(value, 1); }
        else submit(EngineHost.object("kind", "literal", "text", value), true);
    }
    private void finishComposition(Runnable action) {
        if (!active) return;
        if (password || numeric) action.run();
        else submit(EngineHost.object("kind", "raw"), true, action);
    }
    private void edit(String command) {
        InputConnection connection = getCurrentInputConnection(); if (connection == null) return;
        if (password && (command.equals("copy") || command.equals("cut"))) { showError("密码不能复制或剪切"); return; }
        int menu = switch (command) { case "selectAll" -> android.R.id.selectAll; case "copy" -> android.R.id.copy; case "cut" -> android.R.id.cut; case "paste" -> android.R.id.paste; default -> 0; };
        if (menu != 0) { connection.performContextMenuAction(menu); return; }
        int code = switch (command) { case "left" -> KeyEvent.KEYCODE_DPAD_LEFT; case "right" -> KeyEvent.KEYCODE_DPAD_RIGHT; case "up" -> KeyEvent.KEYCODE_DPAD_UP; case "down" -> KeyEvent.KEYCODE_DPAD_DOWN; case "home" -> KeyEvent.KEYCODE_MOVE_HOME; case "end" -> KeyEvent.KEYCODE_MOVE_END; default -> 0; };
        if (code != 0) sendDownUpKeyEvents(code);
    }

    private void directKey(String value) {
        InputConnection connection = getCurrentInputConnection();
        if (connection == null) return;
        switch (value) {
            case "backspace" -> delete(connection);
            case "space" -> connection.commitText(" ", 1);
            case "enter" -> enter();
            case "left" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT);
            case "right" -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT);
            default -> { if (value.length() == 1) { connection.commitText(value, 1); if (keyboard != null) keyboard.consumeShift(); } }
        }
    }
    private void enter() {
        InputConnection connection = getCurrentInputConnection();
        EditorInfo editor = getCurrentInputEditorInfo();
        if (connection == null || editor == null) return;
        int action = editor.imeOptions & EditorInfo.IME_MASK_ACTION;
        if ((editor.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) connection.performEditorAction(action);
        else connection.commitText("\n", 1);
    }
    @Override public void choose(int index, long revision, boolean translation) { submit(EngineHost.object("kind", translation ? "translation" : "choose", "index", index, "revision", revision), true); }
    @Override public void page(int page) { submit(EngineHost.object("kind", "page", "page", page), false); }
    @Override public void forget(int index, long revision) { submit(EngineHost.object("kind", "forget", "index", index, "revision", revision), false); }

    private void submit(JSONObject event, boolean edit) {
        submit(event, edit, null);
    }
    private void submit(JSONObject event, boolean edit, Runnable after) {
        final long epoch = guard.current();
        if (edit) { submitted++; handler.removeCallbacks(idle); handler.removeCallbacks(poll); }
        host.dispatch(event, value -> {
            if (!active || !guard.accepts(epoch)) return;
            if (edit) submitted = Math.max(0, submitted - 1);
            Frame result = new Frame(value);
            if (!result.error.isEmpty()) { showError(result.error); return; }
            ready = true;
            InputConnection connection = getCurrentInputConnection();
            if (connection == null) return;
            if (edit) {
                connection.beginBatchEdit();
                if (!result.committed.isEmpty()) connection.commitText(result.committed, 1);
                if (result.delete) delete(connection);
                if (!result.raw.isEmpty()) connection.setComposingText(result.raw, 1);
                else {
                    if (result.committed.isEmpty() && !result.delete && frame != null && !frame.raw.isEmpty()) {
                        connection.setComposingText("", 1);
                    }
                    connection.finishComposingText();
                }
                connection.endBatchEdit();
            }
            frame = result;
            if (keyboard != null) keyboard.show(result);
            if (!result.warning.isEmpty() && !result.warning.equals(lastWarning)) {
                Toast.makeText(this, result.warning, Toast.LENGTH_LONG).show();
            }
            lastWarning = result.warning;
            if (!result.candidates.isEmpty()) annotate(result.revision, epoch);
            if (edit && submitted == 0 && !result.raw.isEmpty()) handler.postDelayed(idle, 250);
            if (after != null) after.run();
        });
    }

    private void annotate(long revision, long epoch) {
        host.dispatch(EngineHost.object("kind", "annotate", "revision", revision), value -> {
            if (!active || !guard.accepts(epoch) || frame == null || frame.revision != revision) return;
            Frame annotated = new Frame(value);
            if (annotated.revision != revision || !annotated.error.isEmpty()) return;
            frame = annotated;
            if (keyboard != null) keyboard.show(annotated);
        });
    }
    private void requestBackground() {
        if (!active || privateInput || frame == null || frame.raw.isEmpty() || submitted != 0) return;
        InputConnection connection = getCurrentInputConnection();
        String before = "", after = "";
        if (connection != null && Preferences.store(this).getBoolean("cloud", false)) {
            CharSequence previous = connection.getTextBeforeCursor(192, 0), following = connection.getTextAfterCursor(96, 0);
            before = previous == null ? "" : previous.toString();
            // 应用中的 marked text 不属于上文。
            if (before.endsWith(frame.raw)) before = before.substring(0, before.length() - frame.raw.length());
            after = following == null ? "" : following.toString();
        }
        host.dispatch(EngineHost.object("kind", "idle", "before", before, "after", after), null);
        pollsRemaining = 100;
        handler.postDelayed(poll, 100);
    }
    private void pollBackground() {
        if (!active || frame == null || (frame.raw.isEmpty() && !frame.translating) || privateInput || submitted > 0 || pollsRemaining-- <= 0) return;
        long epoch = guard.current();
        host.dispatch(EngineHost.object("kind", "poll"), value -> {
            if (!active || !guard.accepts(epoch) || submitted > 0) return;
            Frame updated = new Frame(value);
            if (updated.error.isEmpty() && frame != null && updated.revision != frame.revision) {
                frame = updated;
                if (keyboard != null) keyboard.show(updated);
                annotate(updated.revision, epoch);
            }
            if (active && frame != null && (!frame.raw.isEmpty() || (frame.translating && frame.completion.isEmpty()))) handler.postDelayed(poll, 150);
        });
    }
    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd, int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd);
        selectionStart = newStart; selectionEnd = newEnd;
        if (active && frame != null && frame.translating && (newStart != translatedStart || newEnd != translatedEnd)) {
            selectedForTranslation = null; guard.next(); handler.removeCallbacks(poll);
            submit(EngineHost.object("kind", "clear"), false);
            return;
        }
        if (submitted > 0 || frame == null || frame.raw.isEmpty() || !active) return;
        if (candidatesEnd >= 0 && newStart == newEnd && newEnd == candidatesEnd) { expectedSelection = newEnd; return; }
        if (newStart == oldStart && newEnd == oldEnd) return;
        if (newStart != expectedSelection || newEnd != expectedSelection) {
            InputConnection connection = getCurrentInputConnection();
            if (connection != null) connection.finishComposingText();
            guard.next(); handler.removeCallbacks(idle); handler.removeCallbacks(poll);
            submit(EngineHost.object("kind", "clear"), false);
        }
    }
    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && keyboard != null && keyboard.closePanel()) return true;
        if (active && keyCode == KeyEvent.KEYCODE_DEL) { key("backspace"); return true; }
        if (active && keyCode == KeyEvent.KEYCODE_SPACE) { key("space"); return true; }
        if (active && keyCode == KeyEvent.KEYCODE_ENTER) { key("enter"); return true; }
        if (active && !event.isCtrlPressed() && !event.isAltPressed()) {
            int value = event.getUnicodeChar();
            if (value > 0 && value < 128) { key(String.valueOf((char) value)); return true; }
        }
        return super.onKeyDown(keyCode, event);
    }
    private void delete(InputConnection connection) {
        if (connection.getSelectedText(0) != null) connection.commitText("", 1);
        else if (!connection.deleteSurroundingTextInCodePoints(1, 0)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
    }
    private void showError(String message) { if (keyboard != null) keyboard.status(message); Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
