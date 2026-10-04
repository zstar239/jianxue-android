package org.jianxue.ime.engine;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import org.jianxue.ime.settings.Preferences;
import org.json.JSONObject;
import java.io.File;
import java.util.function.Consumer;

/** 服务与设置共享的线程所有者，所有输入事件按顺序处理。 */
public final class EngineHost {
    // 唯一持有的是 getApplicationContext()，生命周期与进程相同。
    @android.annotation.SuppressLint("StaticFieldLeak")
    private static EngineHost instance;
    private final Handler worker, main = new Handler(Looper.getMainLooper());
    private final Context context;
    private long handle;
    private String activeConfig, initializationError;
    private File dataDirectory;

    public static synchronized EngineHost get(Context context) {
        if (instance == null) instance = new EngineHost(context.getApplicationContext());
        return instance;
    }

    private EngineHost(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("jianxue-engine", android.os.Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        worker = new Handler(thread.getLooper());
        worker.post(() -> {
            try {
                dataDirectory = AssetInstaller.install(context);
                activeConfig = Preferences.configuration(context).toString();
                handle = NativeEngine.create(dataDirectory.getAbsolutePath(), userDirectory().getAbsolutePath(), activeConfig);
            } catch (Exception | LinkageError failure) {
                initializationError = "输入引擎无法启动：" + failure.getMessage();
            }
        });
    }

    public File userDirectory() { return new File(context.getFilesDir(), "user"); }

    public void dispatch(JSONObject event, Consumer<JSONObject> callback) {
        final String payload = event.toString();
        worker.post(() -> {
            JSONObject result;
            try {
                if (handle == 0) throw new IllegalStateException(initializationError);
                result = new JSONObject(NativeEngine.dispatch(handle, payload));
            } catch (Exception | LinkageError failure) {
                result = object("error", failure.getMessage() == null ? "输入引擎暂不可用" : failure.getMessage());
            }
            JSONObject reply = result;
            if (callback != null) main.post(() -> callback.accept(reply));
        });
    }

    public void refreshConfiguration(Consumer<JSONObject> callback) {
        final String config = Preferences.configuration(context).toString();
        worker.post(() -> {
            JSONObject result;
            try {
                if (handle == 0) throw new IllegalStateException(initializationError);
                if (!config.equals(activeConfig)) {
                    result = new JSONObject(NativeEngine.dispatch(handle, object("kind", "configure", "text", config).toString()));
                    if (result.isNull("error")) activeConfig = config;
                } else result = new JSONObject(NativeEngine.dispatch(handle, object("kind", "frame").toString()));
            } catch (Exception | LinkageError failure) { result = object("error", failure.getMessage()); }
            JSONObject reply = result;
            if (callback != null) main.post(() -> callback.accept(reply));
        });
    }

    public void flush() { dispatch(object("kind", "flush"), null); }

    public void importDictionary(android.net.Uri source, Consumer<JSONObject> callback) {
        worker.post(() -> {
            JSONObject result;
            File pending = new File(userDirectory(), "dicts/.pending.tsv");
            try {
                File directory = pending.getParentFile();
                if (directory == null || !directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建个人词库目录");
                try (java.io.InputStream input = context.getContentResolver().openInputStream(source); java.io.FileOutputStream output = new java.io.FileOutputStream(pending)) {
                    if (input == null) throw new java.io.IOException("无法读取文件");
                    byte[] buffer = new byte[65536]; int count; long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        total += count; if (total > 20L * 1024 * 1024) throw new java.io.IOException("词库超过 20 MB");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (handle == 0) throw new IllegalStateException(initializationError);
                result = new JSONObject(NativeEngine.dispatch(handle, object("kind", "import").toString()));
            } catch (Exception failure) { result = object("error", failure.getMessage()); }
            finally { if (pending.exists()) pending.delete(); }
            JSONObject reply = result; main.post(() -> callback.accept(reply));
        });
    }

    public void exportText(android.net.Uri destination, String value, Consumer<JSONObject> callback) {
        worker.post(() -> {
            JSONObject result;
            try (java.io.OutputStream output = context.getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new java.io.IOException("无法写入文件");
                output.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                result = object("saved", true);
            } catch (Exception failure) { result = object("error", failure.getMessage()); }
            JSONObject reply = result; main.post(() -> callback.accept(reply));
        });
    }

    public static JSONObject object(Object... values) {
        JSONObject result = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]); }
        catch (org.json.JSONException impossible) { throw new IllegalArgumentException(impossible); }
        return result;
    }
}
