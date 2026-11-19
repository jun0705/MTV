package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.github.catvod.utils.Shell;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Formatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class Util {

    private static final Pattern EPISODE = Pattern.compile("(?i)(?:ep|第|e|[\\-\\.\\s])\\s?(\\d{1,4})");
    private static final Pattern BRACKET = Pattern.compile("\\[.*?\\]|\\(.*?\\)");
    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern RES_TAG = Pattern.compile("2160p|1080p|720p|480p|4k|h26[45]|x26[45]|mp4", Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_DIGIT = Pattern.compile("\\D+");

    private Util() {
        // 工具类禁止实例化
    }

    /* ------------------------------ UI / 窗口 ------------------------------ */

    public static void toggleFullscreen(Activity activity, boolean fullscreen) {
        if (activity == null) return;
        if (fullscreen) hideSystemUI(activity);
        else showSystemUI(activity);
    }

    public static void hideSystemUI(Activity activity) {
        if (activity == null) return;
        hideSystemUI(activity.getWindow());
    }

    public static void hideSystemUI(Window window) {
        if (window == null) return;
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(window, window.getDecorView());
        if (insets != null) {
            insets.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insets.hide(WindowInsetsCompat.Type.systemBars());
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    public static void showSystemUI(Activity activity) {
        if (activity == null) return;
        showSystemUI(activity.getWindow());
    }

    public static void showSystemUI(Window window) {
        if (window == null) return;
        WindowInsetsControllerCompat insets = WindowCompat.getInsetsController(window, window.getDecorView());
        if (insets != null) insets.show(WindowInsetsCompat.Type.systemBars());
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
    }

    public static boolean isFullscreen(Activity activity) {
        if (activity == null || activity.getWindow() == null) return false;
        return isLeanback() || (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0;
    }

    public static boolean isFullscreenLand(Activity activity) {
        return isFullscreen(activity) && !isLeanback() && ResUtil.isLand(activity);
    }

    /* ------------------------------ 键盘 ------------------------------ */

    public static void showKeyboard(View view) {
        if (view == null || !view.requestFocus()) return;
        InputMethodManager imm = getInputMethodManager();
        if (imm != null) view.postDelayed(() -> imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT), 250);
    }

    public static void hideKeyboard(View view) {
        if (view == null) return;
        InputMethodManager imm = getInputMethodManager();
        IBinder token = view.getWindowToken();
        if (imm == null || token == null) return;
        imm.hideSoftInputFromWindow(token, 0);
    }

    private static InputMethodManager getInputMethodManager() {
        return (InputMethodManager) App.get().getSystemService(Context.INPUT_METHOD_SERVICE);
    }

    /* ------------------------------ 剪贴板 ------------------------------ */

    public static CharSequence getClipText() {
        try {
            ClipboardManager manager = (ClipboardManager) App.get().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = manager == null ? null : manager.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            return clip.getItemAt(0).getText();
        } catch (Exception e) {
            return "";
        }
    }

    public static void copy(String text) {
        if (TextUtils.isEmpty(text)) return;
        try {
            ClipboardManager manager = (ClipboardManager) App.get().getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) return;
            manager.setPrimaryClip(ClipData.newPlainText("", text));
            Notify.show(R.string.copied);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /* ------------------------------ 亮度 ------------------------------ */

    public static float getBrightness(Activity activity) {
        try {
            if (activity == null) return 0.5f;
            float value = activity.getWindow().getAttributes().screenBrightness;
            if (WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL >= value
                    && value >= WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF) return value;
            return Settings.System.getFloat(activity.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS) / 255f;
        } catch (Exception e) {
            return 0.5f;
        }
    }

    /* ------------------------------ 文本解析 ------------------------------ */

    public static int getNumber(String text) {
        if (TextUtils.isEmpty(text)) return -1;
        try {
            text = BRACKET.matcher(text).replaceAll("");
            text = YEAR.matcher(text).replaceAll("");
            text = RES_TAG.matcher(text.toLowerCase()).replaceAll("");
            Matcher matcher = EPISODE.matcher(text);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
            String number = NON_DIGIT.matcher(text).replaceAll("");
            return number.isEmpty() ? -1 : Integer.parseInt(number);
        } catch (Exception e) {
            return -1;
        }
    }

    public static String clean(String text) {
        if (TextUtils.isEmpty(text) || !text.contains("<")) return text;
        String plain = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                .replace("\u00A0", " ").replace("\u3000", " ");
        StringBuilder sb = new StringBuilder();
        for (String line : plain.split("\\r?\\n")) sb.append(line.trim()).append('\n');
        return substring(sb.toString()).trim();
    }

    public static String substring(String text) {
        return substring(text, 1);
    }

    public static String substring(String text, int num) {
        if (text == null || text.length() <= num) return text;
        return text.substring(0, text.length() - num);
    }

    /* ------------------------------ 设备信息 ------------------------------ */

    public static String getAndroidId() {
        try {
            String id = Settings.Secure.getString(App.get().getContentResolver(), Settings.Secure.ANDROID_ID);
            return TextUtils.isEmpty(id) ? "0000000000000000" : id;
        } catch (Exception e) {
            return "0000000000000000";
        }
    }

    public static String getSerial() {
        try {
            return Shell.exec("getprop ro.serialno").replace("\n", "").trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static String getMac(String name) {
        try {
            NetworkInterface nif = NetworkInterface.getByName(name);
            if (nif == null || nif.getHardwareAddress() == null) return "";
            StringBuilder sb = new StringBuilder();
            for (byte b : nif.getHardwareAddress()) sb.append(String.format(Locale.US, "%02X:", b));
            return substring(sb.toString());
        } catch (Exception e) {
            return "";
        }
    }

    public static String getDeviceName() {
        String model = Build.MODEL == null ? "" : Build.MODEL.trim();
        String manufacturer = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER.trim();
        if (model.isEmpty()) return manufacturer;
        if (manufacturer.isEmpty()) return model;
        return model.toLowerCase(Locale.US).startsWith(manufacturer.toLowerCase(Locale.US))
                ? model : manufacturer + " " + model;
    }

    /** 获取本机首个可用的 IPv4 地址（跳过回环/虚拟网卡），失败返回 127.0.0.1 */
    public static String getIp() {
        try {
            Enumeration<NetworkInterface> nifs = NetworkInterface.getNetworkInterfaces();
            while (nifs != null && nifs.hasMoreElements()) {
                NetworkInterface nif = nifs.nextElement();
                if (!nif.isUp() || nif.isLoopback() || nif.isVirtual()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()
                            && !addr.isLinkLocalAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
            // 走 fallback
        }
        return "127.0.0.1";
    }

    /* ------------------------------ 播放相关 ------------------------------ */

    public static boolean isLeanback() {
        return "leanback".equals(BuildConfig.FLAVOR_mode);
    }

    public static boolean isMobile() {
        return "mobile".equals(BuildConfig.FLAVOR_mode);
    }

    public static String format(StringBuilder builder, Formatter formatter, long timeMs) {
        try {
            return androidx.media3.common.util.Util.getStringForTime(builder, formatter, timeMs);
        } catch (Exception e) {
            return "";
        }
    }

    public static String timeMs(long timeMs) {
        StringBuilder sb = new StringBuilder();
        return format(sb, new Formatter(sb, Locale.getDefault()), timeMs);
    }
}