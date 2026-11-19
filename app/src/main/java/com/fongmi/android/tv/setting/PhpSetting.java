package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

import java.io.File;

/**
 * PHP 本地服务相关配置。
 */
public class PhpSetting {

    public static final int DEFAULT_PORT = 8080;
    public static final String KEY_ENABLE = "php_enable";
    public static final String KEY_PORT = "php_port";
    public static final String KEY_ROOT = "php_root";

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLE, false);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLE, enabled);
    }

    public static int getPort() {
        int port = Prefers.getInt(KEY_PORT, DEFAULT_PORT);
        return port > 0 && port < 65536 ? port : DEFAULT_PORT;
    }

    public static void putPort(int port) {
        Prefers.put(KEY_PORT, port);
    }

    public static String getRoot() {
        String root = Prefers.getString(KEY_ROOT, "");
        // 空值或 "/" 这类根目录都视为未配置，回退到默认网站目录
        if (root == null || root.isEmpty() || root.equals("/")) return defaultRoot();
        return root;
    }

    public static void putRoot(String root) {
        Prefers.put(KEY_ROOT, root == null ? "" : root);
    }

    /** 默认网站根目录：外部存储下的 PHP 文件夹，方便用户直接放入网页文件。 */
    public static String defaultRoot() {
        return com.github.catvod.utils.Path.rootPath() + "/PHP";
    }

    public static File defaultRootFile() {
        return new File(defaultRoot());
    }

    /** 确保默认网站根目录存在（有存储权限时调用） */
    public static void ensureDefaultRoot() {
        try {
            File dir = new File(defaultRoot());
            if (!dir.exists()) dir.mkdirs();
            // 兼容旧版本存在文件而非目录的情况
            if (dir.exists() && !dir.isDirectory() && dir.delete()) dir.mkdirs();
        } catch (Exception ignored) {
        }
    }
}
