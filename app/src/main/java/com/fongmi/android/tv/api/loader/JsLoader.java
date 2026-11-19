package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private static final long RETRY_DELAY = 3000L;
    private final ConcurrentHashMap<String, Spider> spiders;
    private final ConcurrentHashMap<String, Long> failures;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        failures = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        failures.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    /**
     * 初始化失败（模块拉取失败、JS 语法错误等）不能把 SpiderNull 永久缓存，
     * 否则一次网络抖动就让该站点在本次运行里彻底失效，只能重启 App 才会恢复。
     * 这里失败不落缓存，只做 3 秒冷却，之后请求会自动重试初始化。
     */
    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider exist = spiders.get(key);
        if (exist != null) return exist;
        synchronized (this) {
            exist = spiders.get(key);
            if (exist != null) return exist;
            Long last = failures.get(key);
            long now = System.currentTimeMillis();
            if (last != null && now - last < RETRY_DELAY) return empty(key);
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
                spiders.put(key, spider);
                failures.remove(key);
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                failures.put(key, now);
                return empty(key);
            }
        }
    }

    private Spider empty(String key) {
        Spider spider = new SpiderNull();
        spider.siteKey = key;
        return spider;
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}
