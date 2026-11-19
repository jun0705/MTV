package com.fongmi.android.tv.utils;

import android.content.Context;
import android.content.res.AssetManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.PhpSetting;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 首次使用时释放内置资源：
 * 1) php_runtime.zip —— arm64 的 php 解释器 + 依赖库，解压到应用私有目录 files/php/（无需存储权限）；
 * 2) php_bundle.zip —— 站点脚本，解压到默认网站根目录（需存储权限）。
 * 使用版本标记避免重复解压，且不覆盖用户已有文件。
 */
public class PhpAssets {

    private static final String BUNDLE = "php_bundle.zip";
    private static final String RUNTIME = "php_runtime.zip";
    private static final String RUNTIME_TAG = "php_runtime.ok3";

    /** runtime 目录：files/php/ */
    public static File runtimeDir() {
        return new File(Path.files(), "php");
    }

    /** 在用户开启 PHP 服务前调用，确保运行环境已就绪。 */
    public static void installRuntime() {
        File root = runtimeDir();
        File tag = new File(root, RUNTIME_TAG);
        File binary = new File(root, "php");
        if (tag.exists() && binary.exists()) return;
        try {
            root.mkdirs();
            unzip(App.get(), RUNTIME, root);
            binary.setExecutable(true, false);
            FileOutputStream fos = new FileOutputStream(tag);
            fos.write("ok".getBytes());
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /** 释放站点脚本到默认网站根目录（需存储权限；已存在则不覆盖）。 */
    public static void installSites() {
        try {
            File root = new File(PhpSetting.defaultRoot());
            if (!root.exists() && !root.mkdirs()) return;
            if (!root.canWrite()) return;
            if (new File(root, "config.php").exists()) return;
            unzip(App.get(), BUNDLE, root);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void unzip(Context context, String asset, File target) throws IOException {
        AssetManager am = context.getAssets();
        byte[] buf = new byte[8192];
        ZipInputStream zis = new ZipInputStream(am.open(asset));
        ZipEntry entry;
        String targetPath = target.getCanonicalPath();
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName();
            File out = new File(target, name);
            String outPath = out.getCanonicalPath();
            // 防目录穿越
            if (!outPath.startsWith(targetPath + File.separator) && !outPath.equals(targetPath)) continue;
            if (entry.isDirectory()) {
                out.mkdirs();
            } else {
                File parent = out.getParentFile();
                if (parent != null) parent.mkdirs();
                OutputStream os = new FileOutputStream(out);
                int len;
                while ((len = zis.read(buf)) > 0) os.write(buf, 0, len);
                os.flush();
                os.close();
            }
            zis.closeEntry();
        }
        zis.close();
    }
}
