package org.jianxue.ime.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import org.jianxue.ime.engine.Frame;
import org.jianxue.ime.settings.Preferences;
import java.util.List;
import java.util.Locale;

/** 触摸键盘与工具面板；拼音解析、排序和文本变换仍由 Rust 处理。 */
@android.annotation.SuppressLint("ViewConstructor")
public final class KeyboardView extends LinearLayout {
    public interface Listener {
        void key(String value);
        void choose(int index, long revision, boolean translation);
        void page(int page);
        void forget(int index, long revision);
        void spelling(String value, long revision);
        void insert(String value);
    }
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ClipboardHistory clipboard;
    private LinearLayout candidates, keys, phonetics;
    private TextView preedit, badge, previous, next, completion, expand;
    private Frame frame;
    private int ink, muted, background, keyBackground, specialBackground, rowHeight;
    private final int accent = Color.rgb(7, 173, 99);
    private boolean english, numeric, symbols, numbers, shifted, privateInput, haptic, password, nineKey;
    private String actionLabel = "换行", panel = "", emojiCategory = "表情";
    private List<String> clips = java.util.Collections.emptyList();
    private static final String[] EMOJI_NAMES = {"最近", "表情", "手势", "生活", "符号"};
    private static final String[][] EMOJI = {
        {},
        {"😀", "😄", "😁", "😂", "😊", "😍", "🥰", "😘", "😎", "🤔", "🙃", "😅", "😭", "😢", "😤", "😴", "🤗", "🤩", "😋", "😇", "😏", "😳", "😔", "😷"},
        {"👍", "👎", "👌", "✌️", "🤞", "👏", "🙏", "🤝", "💪", "👋", "🙌", "☝️", "👀", "🤦", "🙋", "🫶"},
        {"❤️", "💚", "💛", "💙", "🌹", "🌸", "🌞", "🌙", "⭐", "🔥", "🎉", "🎂", "🎁", "☕", "🍵", "🍎", "🍔", "⚽", "🏀", "🏠", "🚗", "✈️", "📚", "💻"},
        {"✅", "❌", "❗", "❓", "💯", "✨", "💰", "🔔", "📍", "➡️", "⬅️", "⬆️", "⬇️", "♻️", "🎵", "⚠️"}
    };

    public KeyboardView(Context context, Listener listener) {
        super(context); this.listener = listener; clipboard = new ClipboardHistory(context);
        setOrientation(VERTICAL);
    }

    public void configure(boolean english, boolean numeric, boolean privateInput, boolean password, boolean haptic, String actionLabel) {
        this.english = english; this.numeric = numeric; this.privateInput = privateInput; this.password = password;
        this.haptic = haptic; this.actionLabel = actionLabel;
        SharedPreferences prefs = Preferences.store(getContext());
        nineKey = prefs.getString("layout", "t9").equals("t9") && prefs.getString("scheme", "pinyin").equals("pinyin") && !english && !numeric && !password;
        String theme = prefs.getString("theme", "system");
        boolean dark = theme.equals("dark") || (theme.equals("system") && (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        ink = dark ? Color.rgb(238, 238, 238) : Color.rgb(35, 35, 35);
        muted = dark ? Color.rgb(170, 173, 177) : Color.rgb(125, 130, 136);
        background = dark ? Color.rgb(33, 35, 39) : Color.rgb(237, 239, 242);
        keyBackground = dark ? Color.rgb(64, 67, 73) : Color.WHITE;
        specialBackground = dark ? Color.rgb(47, 50, 56) : Color.rgb(222, 225, 230);
        rowHeight = Math.max(40, Math.min(64, prefs.getInt("key_height", 50)));
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) rowHeight = Math.min(38, rowHeight);
        panel = ""; symbols = false; numbers = false; shifted = false;
        frame = null; if (privateInput) clipboard.clearTransient();
        buildChrome(); rebuild();
    }

    private void buildChrome() {
        handler.removeCallbacksAndMessages(null); removeAllViews(); setPadding(dp(4), dp(3), dp(4), dp(6)); setBackgroundColor(background);
        LinearLayout toolbar = row();
        toolbar.addView(tool("☰", "常用工具", () -> openPanel("tools")), new LayoutParams(dp(40), dp(36)));
        preedit = label("简学 · 好好输入", 13); preedit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); preedit.setEllipsize(TextUtils.TruncateAt.START);
        toolbar.addView(preedit, new LayoutParams(0, dp(36), 1));
        toolbar.addView(tool("☺", "表情面板", () -> openPanel("emoji")), new LayoutParams(dp(36), dp(36)));
        toolbar.addView(tool("▣", "剪贴板", () -> openPanel("clipboard")), new LayoutParams(dp(36), dp(36)));
        badge = tool("中", "切换私密输入", () -> listener.key("private")); badge.setTextSize(12); badge.setTextColor(accent);
        toolbar.addView(badge, new LayoutParams(dp(42), dp(36))); addView(toolbar);
        LinearLayout strip = row();
        previous = tool("‹", "上一页候选", () -> { if (frame != null && frame.page > 0) listener.page(frame.page - 1); });
        strip.addView(previous, new LayoutParams(dp(22), dp(68)));
        candidates = row(); strip.addView(candidates, new LayoutParams(0, dp(68), 1));
        next = tool("›", "下一页候选", () -> { if (frame != null && frame.page + 1 < frame.pages) listener.page(frame.page + 1); });
        strip.addView(next, new LayoutParams(dp(22), dp(68)));
        expand = tool("⌄", "展开候选", () -> openPanel("candidates")); strip.addView(expand, new LayoutParams(dp(30), dp(68))); addView(strip);
        completion = tool("", "接受补全或翻译", () -> listener.key("completion")); completion.setTextSize(13); completion.setTextColor(accent); completion.setVisibility(GONE); completion.setEllipsize(TextUtils.TruncateAt.END);
        addView(completion, new LayoutParams(-1, dp(30)));
        keys = column(); addView(keys, new LayoutParams(-1, dp(bodyHeight()))); updateBadge();
    }

    public void show(Frame value) {
        frame = value;
        preedit.setText(password ? "安全输入" : value.preedit.isEmpty() ? (privateInput ? "私密 · 不记录、不联网" : "简学 · 好好输入") : value.preedit);
        candidates.removeAllViews();
        if (!password) for (Frame.Candidate candidate : value.candidates) candidates.addView(candidateView(candidate, value.revision), new LayoutParams(0, -1, 1));
        previous.setEnabled(value.page > 0); next.setEnabled(value.page + 1 < value.pages);
        previous.setAlpha(previous.isEnabled() ? 1 : .25f); next.setAlpha(next.isEnabled() ? 1 : .25f);
        expand.setEnabled(!password && !value.raw.isEmpty()); expand.setAlpha(expand.isEnabled() ? 1 : .3f);
        completion.setText(value.translating ? value.completion.isEmpty() ? "正在翻译选中文字…" : "点击替换选区 · " + value.completion : value.completion);
        completion.setEnabled(!value.completion.isEmpty()); completion.setVisibility(!privateInput && (!value.completion.isEmpty() || value.translating) ? VISIBLE : GONE);
        if (panel.equals("candidates")) { if (value.raw.isEmpty()) { panel = ""; rebuild(); } else expandedCandidates(); }
        else if (phonetics != null) populatePhonetics();
        updateBadge();
    }

    private View candidateView(Frame.Candidate candidate, long revision) {
        LinearLayout item = column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(3), dp(2), dp(3), dp(2));
        TextView text = label(candidate.text, 20); text.setTextColor(candidate.index == 0 ? accent : ink); text.setEllipsize(TextUtils.TruncateAt.END);
        String second = candidate.reading.isEmpty() ? candidate.code : candidate.reading;
        TextView gloss = label(candidate.gloss + (second.isEmpty() ? "" : "\n" + second), 10);
        gloss.setTextColor(candidate.fresh ? Color.rgb(189, 116, 42) : muted); gloss.setSingleLine(false); gloss.setMaxLines(2); gloss.setEllipsize(TextUtils.TruncateAt.END);
        item.addView(text, new LayoutParams(-1, dp(28))); item.addView(gloss, new LayoutParams(-1, dp(32)));
        item.setContentDescription("候选 " + candidate.text + (candidate.gloss.isEmpty() ? "" : "，" + candidate.gloss));
        item.setOnClickListener(v -> listener.choose(candidate.index, revision, false));
        item.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(getContext(), v);
            if (!candidate.gloss.isEmpty()) menu.getMenu().add(0, 1, 0, "输入译词 · " + candidate.gloss);
            if (!privateInput) menu.getMenu().add(0, 2, 1, "清除此词的个人学习");
            menu.setOnMenuItemClickListener(option -> { if (option.getItemId() == 1) listener.choose(candidate.index, revision, true); else listener.forget(candidate.index, revision); return true; });
            if (menu.getMenu().size() == 0) return false; menu.show(); return true;
        });
        if (candidate.text.isEmpty()) item.setVisibility(INVISIBLE); return item;
    }

    public void status(String value) { if (preedit != null) preedit.setText(value); }
    public void consumeShift() { if (shifted) { shifted = false; rebuild(); } }
    public void toggleShift() { shifted = !shifted; rebuild(); }
    public void toggleSymbols() { symbols = !symbols; numbers = false; panel = ""; rebuild(); }
    public boolean closePanel() { if (!panel.isEmpty() || symbols || numbers) { panel = ""; symbols = false; numbers = false; rebuild(); return true; } return false; }
    private void updateBadge() { badge.setText(privateInput ? "私密" : english ? "EN" : "中"); }
    private int bodyHeight() { return (rowHeight + 8) * 4; }

    private void rebuild() {
        handler.removeCallbacksAndMessages(null); keys.removeAllViews(); phonetics = null; expand.setText(panel.equals("candidates") ? "⌃" : "⌄");
        if (!panel.isEmpty()) { renderPanel(); return; }
        if ((numeric && !symbols) || numbers) {
            addKeys(keys, new String[]{"1", "2", "3", "backspace"}, null);
            addKeys(keys, new String[]{"4", "5", "6", "-"}, null);
            addKeys(keys, new String[]{"7", "8", "9", "."}, null);
            addKeys(keys, new String[]{"letters", "0", "symbols", "enter"}, null);
        } else if (symbols) {
            addKeys(keys, new String[]{"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"}, null);
            addKeys(keys, new String[]{"@", "+", "=", "%", "&", "*", "(", ")", "'", "\""}, null);
            addKeys(keys, new String[]{"letters", "-", "_", ":", ";", "/", "?", "!", "backspace"}, null);
            addKeys(keys, new String[]{"punctuation", "numbers", "mode", ",", "space", ".", "enter"}, new float[]{1.3f, 1, 1, .7f, 3.3f, .7f, 1.5f});
        } else if (nineKey) {
            LinearLayout body = row(); phonetics = column(); ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(true); scroll.addView(phonetics);
            body.addView(scroll, new LayoutParams(dp(52), -1)); LinearLayout pad = column(); body.addView(pad, new LayoutParams(0, -1, 1)); keys.addView(body, new LayoutParams(-1, -1));
            addKeys(pad, new String[]{"t9:1", "t9:2", "t9:3", "backspace"}, new float[]{1, 1, 1, .75f});
            addKeys(pad, new String[]{"t9:4", "t9:5", "t9:6", "numbers"}, new float[]{1, 1, 1, .75f});
            addKeys(pad, new String[]{"t9:7", "t9:8", "t9:9", "enter"}, new float[]{1, 1, 1, .75f});
            addKeys(pad, new String[]{"symbols", "mode", "space", "."}, new float[]{1, .9f, 2.1f, .75f}); populatePhonetics();
        } else {
            addLetters("qwertyuiop"); addLetters("asdfghjkl");
            addKeys(keys, new String[]{"shift", letter('z'), letter('x'), letter('c'), letter('v'), letter('b'), letter('n'), letter('m'), "backspace"}, new float[]{1.4f, 1, 1, 1, 1, 1, 1, 1, 1.4f});
            addKeys(keys, new String[]{"symbols", "layout", "mode", ",", "space", ".", "enter"}, new float[]{1.4f, 1, 1, .7f, 3.6f, .7f, 1.6f});
        }
    }
    private void populatePhonetics() {
        if (phonetics == null) return; phonetics.removeAllViews();
        TextView reset = tool(frame != null && !frame.locked.isEmpty() ? "重选" : "拼音", "重选九键拼音", () -> { if (frame != null) listener.spelling("", frame.revision); });
        reset.setTextSize(12); reset.setTextColor(muted); phonetics.addView(reset, new LayoutParams(-1, dp(32)));
        if (frame != null && !frame.digits.isEmpty()) for (String spelling : frame.spellings) {
            long revision = frame.revision;
            TextView item = tool(spelling, "拼音 " + spelling, () -> listener.spelling(spelling, revision)); item.setTextSize(15);
            if (spelling.equals(frame.locked)) { item.setTextColor(accent); item.setBackground(rounded(specialBackground, 6)); }
            phonetics.addView(item, new LayoutParams(-1, dp(40)));
        } else {
            for (String value : new String[]{"，", "。", "？", "！"}) phonetics.addView(tool(value, value, () -> listener.key(value)), new LayoutParams(-1, dp(40)));
        }
    }
    private String letter(char value) { return shifted ? String.valueOf(value).toUpperCase(Locale.ROOT) : String.valueOf(value); }
    private void addLetters(String letters) { String[] values = new String[letters.length()]; for (int i = 0; i < values.length; i++) values[i] = letter(letters.charAt(i)); addKeys(keys, values, null); }

    private void addKeys(LinearLayout parent, String[] values, float[] weights) {
        LinearLayout row = row();
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            String title = switch (value) {
                case "backspace" -> "⌫"; case "enter" -> actionLabel; case "space" -> english ? "space" : "空格";
                case "mode" -> english ? "EN / 中" : "中 / EN"; case "symbols" -> "符"; case "letters" -> "返回";
                case "layout" -> "9/26"; case "numbers" -> "123"; case "punctuation" -> "更多";
                case "shift" -> shifted ? "⬆" : "⇧";
                default -> value.startsWith("t9:") ? switch (value.charAt(3)) {
                    case '1' -> "标点\n1"; case '2' -> "ABC\n2"; case '3' -> "DEF\n3"; case '4' -> "GHI\n4"; case '5' -> "JKL\n5";
                    case '6' -> "MNO\n6"; case '7' -> "PQRS\n7"; case '8' -> "TUV\n8"; default -> "WXYZ\n9";
                } : value;
            };
            TextView key = label(title, value.startsWith("t9:") ? 20 : value.length() > 1 ? 13 : 23);
            if (value.startsWith("t9:")) { key.setSingleLine(false); SpannableString styled = new SpannableString(title); styled.setSpan(new RelativeSizeSpan(.55f), title.indexOf('\n') + 1, title.length(), 0); key.setText(styled); }
            boolean action = value.equals("enter"); boolean special = value.length() > 1 && !value.startsWith("t9:") && !value.equals("space");
            key.setTextColor(action ? Color.WHITE : ink); key.setBackground(rounded(action ? accent : special ? specialBackground : keyBackground, 7)); key.setElevation(dp(1));
            key.setContentDescription(switch (value) { case "backspace" -> "删除"; case "space" -> "空格，滑动移动光标"; case "layout" -> "切换九键与二十六键"; default -> value.startsWith("t9:") ? "九键" + value.charAt(3) : title; });
            key.setOnClickListener(v -> { feedback(v); localKey(value); });
            if (value.equals("backspace")) installRepeat(key, value);
            if (value.equals("space")) installCursorDrag(key);
            if (value.equals("mode")) key.setOnLongClickListener(v -> { listener.key("picker"); return true; });
            LayoutParams params = new LayoutParams(0, dp(rowHeight), weights == null ? 1 : weights[i]); params.setMargins(dp(3), dp(4), dp(3), dp(4)); row.addView(key, params);
        }
        parent.addView(row, new LayoutParams(-1, dp(rowHeight + 8)));
    }
    private void localKey(String value) {
        switch (value) {
            case "letters" -> { symbols = false; numbers = false; rebuild(); }
            case "numbers" -> { numbers = !numbers; symbols = false; panel = ""; rebuild(); }
            case "punctuation", "t9:1" -> openPanel("punctuation");
            default -> listener.key(value);
        }
    }
    private void openPanel(String name) {
        panel = panel.equals(name) ? "" : name;
        if (panel.equals("clipboard") && !privateInput) clips = clipboard.open(true);
        rebuild();
    }
    private void renderPanel() {
        switch (panel) {
            case "candidates" -> expandedCandidates(); case "tools" -> toolsPanel(); case "clipboard" -> clipboardPanel(); case "emoji" -> emojiPanel();
            case "edit" -> editorPanel(); case "punctuation" -> punctuationPanel(); default -> { panel = ""; rebuild(); }
        }
    }
    private LinearLayout panelContent(String title) {
        keys.removeAllViews(); LinearLayout heading = row(); TextView name = label(title, 14); name.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); name.setPadding(dp(10), 0, 0, 0);
        heading.addView(name, new LayoutParams(0, dp(34), 1)); heading.addView(tool("返回键盘", "返回键盘", () -> { panel = ""; rebuild(); }), new LayoutParams(dp(80), dp(34))); keys.addView(heading);
        ScrollView scroll = new ScrollView(getContext()); LinearLayout content = column(); scroll.addView(content); keys.addView(scroll, new LayoutParams(-1, 0, 1)); return content;
    }
    private void expandedCandidates() {
        LinearLayout content = panelContent("全部候选 · 长按输入译词"); expand.setText("⌃");
        if (frame == null) return; LinearLayout row = null;
        for (int i = 0; i < frame.allCandidates.size(); i++) {
            if (i % 3 == 0) { row = row(); content.addView(row, new LayoutParams(-1, dp(70))); }
            row.addView(candidateView(frame.allCandidates.get(i), frame.revision), new LayoutParams(0, -1, 1));
        }
        int start = frame.page / 32 * 32;
        if (start > 0) content.addView(tool("上一组候选", "上一组候选", () -> listener.page(start - 32)), new LayoutParams(-1, dp(38)));
        if (start + 32 < frame.pages) content.addView(tool("下一组候选", "下一组候选", () -> listener.page(start + 32)), new LayoutParams(-1, dp(38)));
    }
    private void toolsPanel() {
        LinearLayout content = panelContent("常用工具");
        toolRow(content, new String[]{"9 / 26 键", "数字键盘", "表情", "符号"}, new String[]{"layout", "numbers", "panel:emoji", "panel:punctuation"});
        toolRow(content, new String[]{"剪贴板", "光标编辑", "私密输入", "翻译选区"}, new String[]{"panel:clipboard", "panel:edit", "private", "translate"});
        toolRow(content, new String[]{"主题与高度", "输入法设置", "切换输入法"}, new String[]{"panel:appearance", "settings", "picker"});
    }
    private void toolRow(LinearLayout content, String[] titles, String[] actions) {
        LinearLayout row = row();
        for (int i = 0; i < titles.length; i++) {
            String action = actions[i]; TextView item = tool(titles[i], titles[i], () -> {
                if (action.equals("panel:appearance")) appearancePanel();
                else if (action.startsWith("panel:")) openPanel(action.substring(6));
                else { if (action.equals("numbers")) { panel = ""; numbers = true; rebuild(); } else listener.key(action); }
            }); item.setTextSize(13); item.setBackground(rounded(keyBackground, 7));
            LayoutParams params = new LayoutParams(0, dp(48), 1); params.setMargins(dp(4), dp(5), dp(4), dp(5)); row.addView(item, params);
        } content.addView(row);
    }
    private void appearancePanel() {
        panel = "appearance"; LinearLayout content = panelContent("主题、键盘高度与触感");
        toolRow(content, new String[]{"跟随系统", "浅色", "深色"}, new String[]{"pref:theme:system", "pref:theme:light", "pref:theme:dark"});
        toolRow(content, new String[]{"紧凑", "标准", "加高"}, new String[]{"pref:height:42", "pref:height:50", "pref:height:60"});
        toolRow(content, new String[]{haptic ? "触感：开" : "触感：关"}, new String[]{"pref:haptic:toggle"});
    }
    private void editorPanel() {
        LinearLayout content = panelContent("光标与选区");
        toolRow(content, new String[]{"行首", "↑", "行尾"}, new String[]{"edit:home", "edit:up", "edit:end"});
        toolRow(content, new String[]{"←", "↓", "→"}, new String[]{"edit:left", "edit:down", "edit:right"});
        toolRow(content, new String[]{"全选", "复制", "剪切", "粘贴"}, new String[]{"edit:selectAll", "edit:copy", "edit:cut", "edit:paste"});
    }
    private void clipboardPanel() {
        LinearLayout content = panelContent("剪贴板 · 长按收藏或删除");
        if (privateInput) { content.addView(label("私密输入不显示剪贴板历史", 14)); toolRow(content, new String[]{"粘贴当前内容"}, new String[]{"edit:paste"}); return; }
        if (clips.isEmpty()) { content.addView(label("暂无文本；复制后打开此面板", 14)); return; }
        for (String text : clips) {
            TextView item = tool((clipboard.pinned(text) ? "★ " : "") + text, "粘贴剪贴板文本", () -> listener.insert(text)); item.setTextSize(15); item.setSingleLine(false); item.setMaxLines(3); item.setEllipsize(TextUtils.TruncateAt.END);
            item.setPadding(dp(12), dp(8), dp(12), dp(8)); item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); item.setBackground(rounded(keyBackground, 7));
            item.setOnLongClickListener(v -> {
                PopupMenu menu = new PopupMenu(getContext(), v); menu.getMenu().add(clipboard.pinned(text) ? "取消收藏" : "收藏到本机").setOnMenuItemClickListener(option -> { clipboard.togglePin(text); clips = clipboard.open(false); clipboardPanel(); return true; });
                menu.getMenu().add("删除").setOnMenuItemClickListener(option -> { clipboard.remove(text); clips = clipboard.open(false); clipboardPanel(); return true; }); menu.show(); return true;
            }); LayoutParams params = new LayoutParams(-1, dp(66)); params.setMargins(dp(6), dp(4), dp(6), dp(4)); content.addView(item, params);
        }
        content.addView(tool("清空历史与收藏", "清空剪贴板历史与收藏", () -> { clipboard.clear(); clips = java.util.Collections.emptyList(); clipboardPanel(); }), new LayoutParams(-1, dp(38)));
    }
    private void emojiPanel() {
        LinearLayout content = panelContent("表情"); HorizontalScrollView tabs = new HorizontalScrollView(getContext()); LinearLayout categories = row(); tabs.addView(categories); content.addView(tabs);
        for (String name : EMOJI_NAMES) { TextView tab = tool(name, "表情分类 " + name, () -> { emojiCategory = name; emojiPanel(); }); if (name.equals(emojiCategory)) tab.setTextColor(accent); tab.setTextSize(13); categories.addView(tab, new LayoutParams(dp(66), dp(34))); }
        int category = java.util.Arrays.asList(EMOJI_NAMES).indexOf(emojiCategory);
        String[] values = category == 0 ? Preferences.store(getContext()).getString("emoji_recent", "").split("\\|", -1) : EMOJI[Math.max(1, category)];
        emojiGrid(content, values, true);
    }
    private void punctuationPanel() {
        LinearLayout content = panelContent("常用符号");
        emojiGrid(content, new String[]{"，", "。", "？", "！", "、", "：", "；", "…", "—", "～", "（", "）", "【", "】", "《", "》", "“", "”", "‘", "’", "·", "￥", "€", "$", "+", "−", "×", "÷", "=", "≠", "≤", "≥", "℃", "°", "㎡", "%", "@", "#", "&", "_", "\\", "/"}, false);
    }
    private void emojiGrid(LinearLayout content, String[] values, boolean emoji) {
        LinearLayout row = null; int count = 0;
        for (String value : values) {
            if (value.isEmpty()) continue; if (count % 6 == 0) { row = row(); content.addView(row, new LayoutParams(-1, dp(46))); }
            TextView item = tool(value, (emoji ? "表情 " : "符号 ") + value, () -> { listener.insert(value); if (emoji && !privateInput) rememberEmoji(value); }); item.setTextSize(25); row.addView(item, new LayoutParams(0, -1, 1)); count++;
        }
        if (row != null) while (count++ % 6 != 0) row.addView(new View(getContext()), new LayoutParams(0, -1, 1));
        if (values.length == 1 && values[0].isEmpty()) content.addView(label("使用过的表情会显示在这里", 14));
    }
    private void rememberEmoji(String value) {
        SharedPreferences prefs = Preferences.store(getContext()); java.util.ArrayList<String> recent = new java.util.ArrayList<>(java.util.Arrays.asList(prefs.getString("emoji_recent", "").split("\\|")));
        recent.remove(""); recent.remove(value); recent.add(0, value); while (recent.size() > 24) recent.remove(recent.size() - 1); prefs.edit().putString("emoji_recent", String.join("|", recent)).apply();
    }
    private void feedback(View view) { if (haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void installRepeat(TextView view, String key) {
        final boolean[] repeated = {false}; Runnable repeat = new Runnable() { public void run() { if (!view.isPressed()) return; repeated[0] = true; view.performClick(); handler.postDelayed(this, 65); } };
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { repeated[0] = false; v.setPressed(true); handler.postDelayed(repeat, 400); }
                case MotionEvent.ACTION_UP -> { handler.removeCallbacks(repeat); if (v.isPressed() && !repeated[0]) v.performClick(); v.setPressed(false); }
                case MotionEvent.ACTION_CANCEL -> { handler.removeCallbacks(repeat); v.setPressed(false); }
                case MotionEvent.ACTION_MOVE -> { if (event.getX() < 0 || event.getY() < 0 || event.getX() > v.getWidth() || event.getY() > v.getHeight()) { handler.removeCallbacks(repeat); v.setPressed(false); } }
                default -> { }
            } return true;
        });
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private void installCursorDrag(TextView view) {
        final float[] last = {0}; final boolean[] dragged = {false};
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { last[0] = event.getX(); dragged[0] = false; v.setPressed(true); }
                case MotionEvent.ACTION_MOVE -> { float distance = event.getX() - last[0]; if (Math.abs(distance) >= dp(18)) { dragged[0] = true; listener.key(distance > 0 ? "edit:right" : "edit:left"); last[0] = event.getX(); } }
                case MotionEvent.ACTION_UP -> { if (!dragged[0]) v.performClick(); v.setPressed(false); }
                case MotionEvent.ACTION_CANCEL -> v.setPressed(false);
                default -> { }
            } return true;
        });
    }
    @Override protected void onDetachedFromWindow() { handler.removeCallbacksAndMessages(null); super.onDetachedFromWindow(); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(HORIZONTAL); view.setGravity(Gravity.CENTER); return view; }
    private TextView tool(String title, String description, Runnable action) { TextView view = label(title, 18); view.setContentDescription(description); view.setOnClickListener(v -> { feedback(v); action.run(); }); return view; }
    private TextView label(String value, int size) { TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(ink); view.setGravity(Gravity.CENTER); view.setSingleLine(); return view; }
    private GradientDrawable rounded(int color, int radius) { GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
