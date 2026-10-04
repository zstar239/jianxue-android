import org.jianxue.ime.engine.NativeEngine;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicReference;

/** 在真实 JVM 上加载 JNI 库并验证正式资源，避免只测 Rust 内部调用。 */
public final class JniSmoke {
    private static long revision(String frame) {
        Matcher matcher = Pattern.compile("\"revision\":(\\d+)").matcher(frame);
        if (!matcher.find()) throw new AssertionError(frame);
        return Long.parseLong(matcher.group(1));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        long handle = NativeEngine.create(args[0], args[1], "{\"language\":\"en\",\"neural\":true}");
        try {
            String frame = NativeEngine.dispatch(handle, "{\"kind\":\"input\",\"text\":\"kaifa\"}");
            require(frame.contains("开发"), "正式词库未给出开发：" + frame);
            require(frame.contains("\"neural\":true"), "正式模型未加载：" + frame);
            long old = revision(frame);
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"annotate\",\"revision\":" + old + "}");
            require(frame.contains("development") || frame.contains("develop"), "英译词未加载：" + frame);
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"choose\",\"revision\":" + old + ",\"index\":0}");
            require(frame.contains("\"committed\":\"开发\""), "JNI 上屏不匹配：" + frame);
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"start\",\"private\":true}");
            require(frame.contains("\"raw\":\"\""), "切换输入框未清理状态");
            NativeEngine.dispatch(handle, "{\"kind\":\"input\",\"text\":\"nihao\"}");
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"choose\",\"revision\":" + old + ",\"index\":0}");
            require(frame.contains("候选已更新"), "过期候选未被拒绝：" + frame);
            NativeEngine.dispatch(handle, "{\"kind\":\"clear\"}");
            long started = System.nanoTime();
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"t9\",\"text\":\"64426\"}");
            require(frame.contains("你好"), "正式九键未给出你好：" + frame);
            Matcher chosen = Pattern.compile("\"index\":(\\d+),\"text\":\"你好\"").matcher(frame);
            require(chosen.find(), "无法找到九键候选索引");
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"choose\",\"revision\":" + revision(frame) + ",\"index\":" + chosen.group(1) + "}");
            require(frame.contains("\"committed\":\"你好\"") && frame.contains("\"raw\":\"\""), "九键上屏或余码错误：" + frame);
            System.out.println("Formal T9 first conversion: " + (System.nanoTime() - started) / 1000000 + " ms (includes index construction).");
            NativeEngine.dispatch(handle, "{\"kind\":\"t9\",\"text\":\"969426498394\"}");
            frame = NativeEngine.dispatch(handle, "{\"kind\":\"frame\"}");
            require(frame.contains("我想学习"), "九键连续组句缺少我想学习：" + frame);
            NativeEngine.dispatch(handle, "{\"kind\":\"clear\"}");
            AtomicReference<Throwable> threadFailure = new AtomicReference<>();
            Thread other = new Thread(() -> {
                String rejected = NativeEngine.dispatch(handle, "{\"kind\":\"space\"}");
                require(rejected.contains("线程不匹配"), "跨线程句柄未被拒绝");
            });
            other.setUncaughtExceptionHandler((thread, failure) -> threadFailure.set(failure));
            other.start(); other.join();
            if (threadFailure.get() != null) throw new AssertionError(threadFailure.get());
            System.out.println("JNI smoke passed: real dictionary, glossary, neural model, commit, T9, continuous input, stale frame and thread ownership.");
        } finally { NativeEngine.destroy(handle); }
    }
}
