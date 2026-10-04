package org.jianxue.ime.engine;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

/** 首次使用时复制只读资源；内容校验后才写版本标记。 */
final class AssetInstaller {
    private static final String VERSION = "data-v3-0.1.0";

    static File install(Context context) throws Exception {
        File data = new File(context.getFilesDir(), "data/" + VERSION);
        File marker = new File(data, ".ready");
        if (marker.isFile()) return data;
        if (!data.isDirectory() && !data.mkdirs()) throw new IOException("无法创建词库目录");
        AssetManager assets = context.getAssets();
        Map<String, String> checksums = new HashMap<>();
        try (InputStream input = assets.open("SHA256SUMS")) {
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            for (String line : new String(bytes.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                String[] fields = line.split("  ", 2);
                if (fields.length != 2 || !fields[0].matches("[0-9a-f]{64}")
                        || fields[1].isEmpty() || fields[1].startsWith("/")
                        || fields[1].contains("\\") || java.util.Arrays.asList(fields[1].split("/")).contains("..")) {
                    throw new IOException("资源校验清单格式错误");
                }
                checksums.put(fields[1], fields[0]);
            }
        }
        if (!checksums.containsKey("dict.qj") || !checksums.containsKey("lm.qj")) {
            throw new IOException("资源校验清单缺少正式词库");
        }
        // AssetManager.list 会合并系统 APK 的 assets；只安装本 APK 校验清单中的资源。
        for (Map.Entry<String, String> resource : checksums.entrySet()) {
            String name = resource.getKey();
            if (name.startsWith("licenses/")) continue;
            copy(assets, name, new File(data, name), resource.getValue());
        }
        Files.write(marker.toPath(), VERSION.getBytes(StandardCharsets.UTF_8));
        return data;
    }

    private static void copy(AssetManager assets, String name, File output, String expected)
            throws IOException, NoSuchAlgorithmException {
        File parent = output.getParentFile();
        if (parent == null || !parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建资源目录");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        File temporary = new File(output.getPath() + ".next");
        try (InputStream input = assets.open(name); FileOutputStream stream = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[65536];
            int size;
            while ((size = input.read(buffer)) != -1) {
                digest.update(buffer, 0, size);
                stream.write(buffer, 0, size);
            }
            stream.getFD().sync();
        }
        StringBuilder actual = new StringBuilder();
        for (byte value : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!expected.equals(actual.toString())) throw new IOException("资源校验失败：" + name);
        Files.move(temporary.toPath(), output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
