package org.jianxue.ime.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.view.WindowInsets;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.jianxue.ime.engine.EngineHost;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** 原生设置、启用引导、统计与生词本。 */
public final class SettingsActivity extends Activity {
    private static final int IMPORT = 1, EXPORT = 2;
    private final String[] tabs = {"开始", "输入", "学习", "云联想", "关于"};
    private LinearLayout content;
    private EngineHost host;
    private SharedPreferences preferences;
    private int selectedTab;
    private String exportText = "";

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        host = EngineHost.get(this); preferences = Preferences.store(this);
        if (savedInstanceState != null) selectedTab = savedInstanceState.getInt("tab");
        build();
    }
    @Override protected void onSaveInstanceState(Bundle outState) { outState.putInt("tab", selectedTab); super.onSaveInstanceState(outState); }
    private void build() {
        LinearLayout root = column(); root.setBackgroundColor(Color.rgb(245, 246, 248));
        root.setPadding(dp(22), dp(20), dp(22), dp(12));
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(dp(22) + bars.left, dp(20) + bars.top, dp(22) + bars.right, dp(12) + bars.bottom);
            return insets;
        });
        root.addView(text("简学", 32, Color.rgb(7, 173, 99), true));
        root.addView(text("好好输入，顺便多认识一个词", 14, Color.rgb(107, 127, 116), false));
        LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER);
        for (int i = 0; i < tabs.length; i++) {
            final int index = i;
            TextView tab = text(tabs[i], 14, selectedTab == i ? Color.rgb(7, 173, 99) : Color.rgb(107, 127, 116), selectedTab == i);
            tab.setGravity(Gravity.CENTER); tab.setPadding(0, dp(20), 0, dp(20));
            tab.setOnClickListener(v -> { selectedTab = index; build(); });
            navigation.addView(tab, new LinearLayout.LayoutParams(0, dp(60), 1));
        }
        root.addView(navigation);
        ScrollView scroll = new ScrollView(this);
        content = column(); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        switch (selectedTab) { case 0 -> home(); case 1 -> input(); case 2 -> learning(); case 3 -> cloud(); default -> about(); }
    }
    private void home() {
        title("从一次输入开始");
        paragraph("安装后，先在系统中启用简学，再切换到简学键盘。拼音输入照常进行，候选下方会多一行译词。");
        button("1 · 启用简学输入法", () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        button("2 · 选择简学键盘", () -> ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker());
        title("在这里试一试");
        EditText trial = field("九键按 64426，或二十六键输入 nihao", false);
        trial.setMinLines(3); trial.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        content.addView(trial);
        paragraph("九键 64426 对应 nihao；空格选择首个候选；⌄ 展开全部候选，‹ › 翻页；长按候选可选择输入译词或清除个人学习。回车在组句中输入原始字母，组句结束后执行输入框的操作。");
        paragraph("密码框使用安全直输；点击键盘右上角可启用私密输入。词频、输入量和词汇记录保存在本机。");
    }
    private void input() {
        title("键盘外观与布局");
        choice("中文布局", "layout", new String[]{"九键拼音", "二十六键"}, new String[]{"t9", "qwerty"}, "t9");
        paragraph("九键用于全拼，按 2–9 输入；左侧选择拼音消歧。双拼、五笔和注音自动使用二十六键。键盘工具栏可随时切换；切到九键时会选择全拼。");
        choice("键盘主题", "theme", new String[]{"跟随系统", "浅色", "深色"}, new String[]{"system", "light", "dark"}, "system");
        int height = Math.max(40, Math.min(64, preferences.getInt("key_height", 50)));
        TextView heightLabel = text(getString(org.jianxue.ime.R.string.key_height_label, height), 16, Color.DKGRAY, false); content.addView(heightLabel);
        SeekBar heightControl = new SeekBar(this); heightControl.setMax(24); heightControl.setProgress(height - 40); heightControl.setContentDescription("键盘按键高度");
        heightControl.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { heightLabel.setText(getString(org.jianxue.ime.R.string.key_height_label, progress + 40)); }
            public void onStartTrackingTouch(SeekBar bar) { }
            public void onStopTrackingTouch(SeekBar bar) { preferences.edit().putInt("key_height", bar.getProgress() + 40).apply(); }
        }); content.addView(heightControl);
        toggle("按键触感（遵循系统触感设置）", "haptic", false);
        paragraph("空格键左右滑动可移动光标；长按中 / EN 键选择系统输入法。工具面板提供表情、符号、剪贴板与光标编辑。");
        title("输入方案");
        choice("键盘方案", "scheme", new String[]{"全拼", "小鹤双拼", "自然码", "微软双拼", "搜狗双拼", "智能 ABC", "小浪双拼", "首道双拼", "五笔 86", "五笔与拼音混输", "大千注音"},
                new String[]{"pinyin", "xiaohe", "ziranma", "microsoft", "sogou", "abc", "xiaolang", "shoudao", "wubi", "wubi_mixed", "zhuyin"}, "pinyin");
        toggle("输出繁体字", "traditional", false);
        toggle("中文全角标点", "full_width", true);
        toggle("学习个人用词习惯", "learning", true);
        toggle("本地整句模型", "neural", true);
        paragraph("模型在停顿后调整整句候选，首次开启需要加载本地权重。");
        toggle("模糊音：平翘舌、前后鼻音", "fuzzy", false);
        toggle("笔画辅助码（分号进入）", "auxiliary", false);
        paragraph("拼音输入完整后，敲分号进入辅助码；h 横、s 竖、p 撇、n 点捺、z 折。微软和搜狗双拼优先使用分号韵母。");
        button("编辑常用短语", this::phrases);
        paragraph("可为输入码指定固定候选位置，保存地址、邮箱或常用回复。中文模式按完整输入码匹配，内容按原样上屏。");
        paragraph("快捷输入：v 后接算式，如 v1+2；u 后接 Unicode 码点或拼音问字。双拼和五笔使用大写 V、U 进入。问字需要云联想。");
        title("领域词库");
        button("选择领域", this::domains);
        button("导入个人词库", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, IMPORT);
        });
        paragraph("支持 UTF-8 TSV、Rime .dict.yaml 和青简 QJ，最大 20 MB；使用上游导入器转换为本地 QJ。TSV 以 Tab 分隔词语、无声调拼音、词频，例如：\n简学\tjian xue\t10000\nRime 导入不展开 import_tables 或自定义列顺序。导入后保存在应用私有目录。");
    }
    private void learning() {
        title("一次只学一种语言");
        choice("候选译词", "language", new String[]{"英语", "日语", "西班牙语", "关闭译词"}, new String[]{"en", "ja", "es", "off"}, "en");
        paragraph("译词来自上游随包表，有些由机器生成。生词用暖色标记；词汇记录统计你见过和使用过的译词。");
        title("我的输入量");
        TextView stats = text("正在读取本地统计…", 15, Color.DKGRAY, false); content.addView(stats);
        host.dispatch(EngineHost.object("kind", "stats"), value -> {
            if (isDestroyed() || selectedTab != 2) return;
            if (value.has("error")) { stats.setText(value.optString("error")); return; }
            StringBuilder display = new StringBuilder();
            String[] names = {"今天", "最近 7 天", "累计"}, keys = {"today", "week", "total"};
            for (int i = 0; i < keys.length; i++) {
                JSONObject usage = value.optJSONObject(keys[i]);
                if (usage != null) display.append(names[i]).append("  ").append(usage.optLong("hanzi")).append(" 字 · ").append(usage.optLong("commits")).append(" 次上屏\n");
            }
            JSONObject vocabulary = value.optJSONObject("vocabulary");
            if (vocabulary != null) display.append("\n见过 ").append(vocabulary.optLong("seen")).append(" 个译词，熟悉 ").append(vocabulary.optLong("familiar")).append(" 个\n本周新见 ").append(vocabulary.optLong("new_this_week")).append(" 个");
            stats.setText(display.toString());
        });
        title("生词本");
        LinearLayout words = column(); content.addView(words);
        host.dispatch(EngineHost.object("kind", "vocabulary"), value -> {
            if (isDestroyed() || selectedTab != 2) return;
            JSONArray entries = value.optJSONArray("entries");
            if (entries == null || entries.length() == 0) { words.addView(text("输入时读一读候选译词，它们会逐渐出现在这里。", 14, Color.GRAY, false)); return; }
            for (int i = 0; i < Math.min(100, entries.length()); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry != null) {
                    TextView word = text(entry.optString("word") + "  ·  见过 " + entry.optLong("seen") + " 次", 16, Color.rgb(65, 91, 79), false);
                    word.setPadding(0, dp(8), 0, dp(8)); words.addView(word);
                }
            }
        });
        button("导出生词本 TSV", () -> host.dispatch(EngineHost.object("kind", "vocabulary"), value -> {
            exportText = value.optString("tsv");
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/tab-separated-values").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "jianxue-vocabulary.tsv"), EXPORT);
        }));
        button("重置本地学习与统计", () -> new AlertDialog.Builder(this).setTitle("重置本地记录？")
                .setMessage("删除个人词频、输入统计和生词记录。导入词库与设置保留。")
                .setNegativeButton("取消", null).setPositiveButton("重置", (dialog, which) -> host.dispatch(EngineHost.object("kind", "reset"), result -> {
                    toast(result.has("error") ? result.optString("error") : "本地记录已重置"); build();
                })).show());
    }
    private void cloud() {
        title("可选的云联想");
        paragraph("默认关闭。开启后，当前拼音和光标附近最多 64 个前文字符、32 个后文字符会直接发送到你填写的服务商。私密输入和密码框不发送。网络失败不影响本地输入。");
        toggle("启用云联想", "cloud", false);
        EditText endpoint = field("HTTPS 接口地址，例如 https://服务地址/v1", false);
        endpoint.setText(preferences.getString("endpoint", ""));
        EditText model = field("模型名称", false); model.setText(preferences.getString("model", ""));
        EditText secret = field("API Key（留空保留已保存密钥）", true);
        content.addView(endpoint); content.addView(model); content.addView(secret);
        button("保存接口设置", () -> {
            Uri uri = Uri.parse(endpoint.getText().toString().trim());
            if (!"https".equals(uri.getScheme()) || TextUtils.isEmpty(uri.getHost()) || model.getText().toString().trim().isEmpty()) { toast("请填写 HTTPS 地址与模型名称"); return; }
            try {
                if (!secret.getText().toString().isEmpty()) Secrets.save(this, secret.getText().toString().trim());
                preferences.edit().putString("endpoint", endpoint.getText().toString().trim()).putString("model", model.getText().toString().trim()).apply();
                secret.setText(""); changed(); toast("接口设置已保存");
            } catch (Exception failure) { toast("密钥保存失败：" + failure.getMessage()); }
        });
        button("清除 API Key 并关闭云联想", () -> {
            try { Secrets.save(this, ""); preferences.edit().putBoolean("cloud", false).apply(); changed(); build(); }
            catch (Exception failure) { toast("无法清除密钥"); }
        });
        paragraph("API Key 由 Android Keystore 加密保存，应用不提供账号或代理服务器。接口使用兼容的聊天补全协议。");
        paragraph("先在应用中选中文字，再点键盘上的「译」。译文显示后，点击译文替换原选区；移动选区会取消本次翻译。所选文字最多 2000 字，会发送给已配置的服务商。");
    }
    private void about() {
        title("简学输入法 0.2.0-dev");
        paragraph("独立的 Android 开源移植，复用 Qingjian 的 Rust 输入引擎、词库与译词数据。此应用不代表青简官方，不使用青简名称或 logo 作为品牌。");
        button("查看上游开源项目", () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/qingjian-team/qingjian"))));
        title("开源许可");
        paragraph("Android 壳与适配代码：Copyright © 2026 简学输入法贡献者，GPL-3.0-or-later。你可以按许可使用、修改和分发；软件不提供担保。分发 APK 时应同时提供对应源码与构建说明。上游版权声明与数据各自的许可随应用保留。");
        button("查看 GPL v3 全文", () -> {
            try (java.io.InputStream stream = getAssets().open("licenses/LICENSE")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) >= 0) bytes.write(buffer, 0, count);
                ScrollView scroll = new ScrollView(this); TextView license = text(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 12, Color.DKGRAY, false); scroll.addView(license);
                new AlertDialog.Builder(this).setTitle("GNU GPL v3").setView(scroll).setPositiveButton("关闭", null).show();
            } catch (Exception failure) { toast("许可文本无法读取"); }
        });
        title("数据与隐私");
        paragraph("默认离线，不上传输入。输入日志未启用；使用统计和词汇记录互相独立。密码、系统要求禁止学习的输入以及手动私密输入不写入学习、统计或词汇记录。应用禁用系统备份。");
        paragraph("词库：上游基础字词库与领域词库。译词：上游英语、日语、西班牙语表。整句：上游 data-v3 的含章·通变 small 模型。机器译词可能有错误。");
        paragraph("英语等级署名：The CEFR-J Wordlist Version 1.5, Yukio Tono (Tokyo University of Foreign Studies)；Octanove Vocabulary Profile C1/C2 1.0（CC BY-SA 4.0）。日语等级：Jonathan Waller / Tanos（CC BY）及 elzup 整理（MIT）。");
        button("数据来源与完整署名", () -> {
            try (java.io.InputStream stream = getAssets().open("licenses/NOTICE.md")) {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
                while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                ScrollView scroll = new ScrollView(this); TextView notice = text(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8), 13, Color.DKGRAY, false);
                android.text.util.Linkify.addLinks(notice, android.text.util.Linkify.WEB_URLS); scroll.addView(notice);
                new AlertDialog.Builder(this).setTitle("来源与署名").setView(scroll).setPositiveButton("关闭", null).show();
            } catch (Exception failure) { toast("来源说明无法读取"); }
        });
    }
    private void domains() {
        String[] names = {"IT 与计算机", "财经", "地名", "历史人物", "医学", "饮食", "法律", "汽车", "动物", "成语", "诗词"};
        String[] codes = {"it_computing", "finance", "places", "historical_figures", "medicine", "food", "law", "automotive", "animals", "idioms", "poetry_lines"};
        Set<String> selected = new HashSet<>(preferences.getStringSet("domains", java.util.Collections.emptySet()));
        boolean[] checked = new boolean[codes.length]; for (int i = 0; i < codes.length; i++) checked[i] = selected.contains(codes[i]);
        new AlertDialog.Builder(this).setTitle("领域词库").setMultiChoiceItems(names, checked, (dialog, index, enabled) -> { if (enabled) selected.add(codes[index]); else selected.remove(codes[index]); })
                .setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> { preferences.edit().putStringSet("domains", selected).apply(); changed(); }).show();
    }
    private void phrases() {
        JSONArray entries = Preferences.phrases(this);
        String[] labels = new String[entries.length()];
        for (int i = 0; i < entries.length(); i++) {
            JSONObject phrase = entries.optJSONObject(i);
            labels[i] = phrase == null ? "无效短语" : phrase.optString("code") + " · 第 " + phrase.optInt("position") + " 位 · "
                    + (phrase.optBoolean("enabled", true) ? "" : "已停用 · ") + phrase.optString("text").replace('\n', ' ');
        }
        new AlertDialog.Builder(this).setTitle("常用短语").setItems(labels, (dialog, index) -> editPhrase(entries, index))
                .setPositiveButton("新增", (dialog, which) -> editPhrase(entries, -1)).setNegativeButton("关闭", null).show();
    }
    private void editPhrase(JSONArray entries, int index) {
        JSONObject existing = index < 0 ? new JSONObject() : entries.optJSONObject(index);
        if (existing == null) existing = new JSONObject();
        LinearLayout form = column(); form.setPadding(dp(20), dp(6), dp(20), dp(6));
        EditText code = field("输入码：1–32 个小写字母", false); code.setText(existing.optString("code"));
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        EditText position = field("候选位置：1–9", false); position.setInputType(InputType.TYPE_CLASS_NUMBER); position.setText(String.valueOf(existing.optInt("position", 1)));
        EditText phrase = field("内容（可换行）", false); phrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); phrase.setMinLines(3); phrase.setText(existing.optString("text"));
        Switch enabled = new Switch(this); enabled.setText("启用短语"); enabled.setChecked(existing.optBoolean("enabled", true));
        form.addView(code); form.addView(position); form.addView(phrase); form.addView(enabled);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(index < 0 ? "新增短语" : "编辑短语").setView(form)
                .setPositiveButton("保存", null).setNegativeButton("取消", null)
                .setNeutralButton(index < 0 ? "" : "删除", null).create();
        dialog.setOnShowListener(visible -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                int slot;
                try { slot = Integer.parseInt(position.getText().toString()); }
                catch (NumberFormatException invalid) { toast("候选位置须为 1–9"); return; }
                JSONObject value = EngineHost.object("code", code.getText().toString().trim(), "position", slot,
                        "text", phrase.getText().toString(), "enabled", enabled.isChecked());
                JSONArray proposed = new JSONArray();
                for (int i = 0; i < entries.length(); i++) proposed.put(i == index ? value : entries.optJSONObject(i));
                if (index < 0) proposed.put(value);
                savePhrases(proposed, dialog);
            });
            if (index < 0) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(View.GONE);
            else dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                JSONArray proposed = new JSONArray();
                for (int i = 0; i < entries.length(); i++) if (i != index) proposed.put(entries.optJSONObject(i));
                savePhrases(proposed, dialog);
            });
        });
        dialog.show();
    }
    private void savePhrases(JSONArray entries, AlertDialog dialog) {
        host.dispatch(EngineHost.object("kind", "validate_phrases", "text", entries.toString()), result -> {
            if (result.has("error")) { toast(result.optString("error")); return; }
            preferences.edit().putString("phrases", entries.toString()).apply();
            changed(); dialog.dismiss(); toast("短语已保存");
        });
    }
    private void choice(String label, String key, String[] labels, String[] values, String fallback) {
        String current = preferences.getString(key, fallback); int found = Arrays.asList(values).indexOf(current); int selected = Math.max(0, found);
        button(label + " · " + labels[selected], () -> new AlertDialog.Builder(this).setTitle(label).setSingleChoiceItems(labels, selected, (dialog, which) -> {
            preferences.edit().putString(key, values[which]).apply(); dialog.dismiss(); changed(); build();
        }).setNegativeButton("取消", null).show());
    }
    private void toggle(String label, String key, boolean fallback) {
        Switch toggle = new Switch(this); toggle.setText(label); toggle.setTextSize(16);
        toggle.setPadding(0, dp(12), 0, dp(12)); toggle.setChecked(preferences.getBoolean(key, fallback));
        toggle.setOnCheckedChangeListener((view, enabled) -> {
            if (key.equals("cloud") && enabled && (Secrets.read(this).isEmpty() || preferences.getString("endpoint", "").isEmpty())) { view.setChecked(false); toast("先保存接口地址、模型和 API Key"); return; }
            preferences.edit().putBoolean(key, enabled).apply(); changed();
        }); content.addView(toggle);
    }
    private void changed() { host.refreshConfiguration(value -> {
        if (value.has("error") && !value.isNull("error")) toast(value.optString("error"));
        else if (value.has("warning") && !value.isNull("warning")) toast(value.optString("warning"));
    }); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == IMPORT) host.importDictionary(data.getData(), value -> toast(value.has("error") ? value.optString("error") : "个人词库已导入"));
        if (request == EXPORT) host.exportText(data.getData(), exportText, value -> toast(value.has("error") ? value.optString("error") : "生词本已导出"));
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, int size, int color, boolean bold) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); if (bold) text.setTypeface(null, Typeface.BOLD); return text; }
    private void title(String value) { TextView title = text(value, 21, Color.rgb(33, 57, 46), true); title.setPadding(0, dp(16), 0, dp(12)); content.addView(title); }
    private void paragraph(String value) { TextView paragraph = text(value, 15, Color.rgb(83, 104, 93), false); paragraph.setLineSpacing(dp(5), 1); paragraph.setPadding(0, dp(5), 0, dp(15)); content.addView(paragraph); }
    private void button(String value, Runnable action) { Button button = new Button(this); button.setText(value); button.setAllCaps(false); button.setTextColor(Color.rgb(7, 173, 99)); button.setOnClickListener(v -> action.run()); content.addView(button, new LinearLayout.LayoutParams(-1, -2)); }
    private EditText field(String hint, boolean secret) { EditText field = new EditText(this); field.setHint(hint); field.setTextSize(15); if (secret) field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); return field; }
    private void toast(String value) { Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
