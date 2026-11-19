package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

public class Module {

    private static final int MAX_SIZE = 50;
    private static final int RETRY = 3;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public String fetch(String name) {
        String content = cache.get(name);
        if (!TextUtils.isEmpty(content)) return content;
        if (name.startsWith("http")) {
            content = fetchRemote(name);
            if (!TextUtils.isEmpty(content)) cache.put(name, content);
            return content;
        }
        if (name.startsWith("assets")) cache.put(name, content = Asset.read(name));
        else if (name.startsWith("lib/")) cache.put(name, content = Asset.read("js/" + name));
        return content;
    }

    /**
     * 远程模块（drpy2.min.js、ext 规则等）拉取失败会直接导致站点初始化失败，
     * 而 JsLoader 对初始化失败的站点会缓存空实现，表现为「站点有时能识别、有时识别不了」。
     * 这里做三次重试，并过滤掉网关/限流返回的 HTML 错误页，避免把错误页当成 JS 缓存下来。
     */
    private String fetchRemote(String url) {
        for (int i = 0; i < RETRY; i++) {
            try {
                String content = OkHttp.string(url);
                if (!isBroken(url, content)) return content;
            } catch (Throwable e) {
                if (i + 1 >= RETRY) return "";
            }
            if (i + 1 < RETRY) sleep(300L * (i + 1));
        }
        return "";
    }

    /**
     * JS 模块却返回了 HTML 错误页（Gitee / 镜像限流时常见），视为失败
     */
    private boolean isBroken(String url, String content) {
        if (TextUtils.isEmpty(content)) return true;
        String name = url.toLowerCase();
        if (!name.endsWith(".js") && !name.contains(".js?")) return false;
        String head = content.substring(0, Math.min(content.length(), 256)).trim().toLowerCase();
        return head.startsWith("<!doctype") || head.startsWith("<html");
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}
