package com.fongmi.quickjs.utils;

import android.text.TextUtils;

import com.fongmi.quickjs.bean.Req;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.Call;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Connect {

    // 每次请求都 newBuilder().build() 会新建连接池，导致同站点多次请求反复握手（慢且易失败），
    // 按「是否跟随跳转 + 超时」缓存客户端，复用连接。
    private static final Map<String, OkHttpClient> CLIENTS = new ConcurrentHashMap<>();

    public static Call to(String url, Req req) {
        Headers headers = Headers.of(req.getHeader());
        String key = req.isRedirect() + "-" + req.getTimeout();
        OkHttpClient client = CLIENTS.computeIfAbsent(key, k -> OkHttp.client(req.isRedirect(), req.getTimeout()));
        return client.newCall(getRequest(fixUrl(url, headers), req, headers));
    }

    /**
     * 不少 JS 规则用「请求 A 域名 + Host 头伪装成 B 域名」访问接口
     * （例如 http://api.douban.com/api/v2 + Host: frodo.douban.com）。
     * OkHttp 会用 URL 里的域名覆盖 Host 头，请求实际打到 A，被 301/403 拒绝，
     * 表现为同一站点时好时坏。这里按 HTTP 语义把请求真正发到 Host 指向的域名，
     * 并把 http 升级为 https。目标是 IP 字面量时保持原样，不影响「指定节点 IP + Host」的用法。
     */
    private static String fixUrl(String url, Headers headers) {
        try {
            String host = headers.get("Host");
            if (TextUtils.isEmpty(host)) return url;
            String raw = host.contains(":") ? host.substring(0, host.indexOf(':')) : host;
            HttpUrl httpUrl = HttpUrl.parse(url);
            if (httpUrl == null || TextUtils.isEmpty(raw)) return url;
            if (raw.equalsIgnoreCase(httpUrl.host())) return url;
            if (isIp(httpUrl.host())) return url;
            HttpUrl.Builder builder = httpUrl.newBuilder().scheme("https").host(raw);
            if (httpUrl.port() == 80) builder.port(443);
            return builder.build().toString();
        } catch (Throwable e) {
            return url;
        }
    }

    private static boolean isIp(String host) {
        if (TextUtils.isEmpty(host)) return false;
        if (host.contains(":")) return true;
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    public static JSObject success(QuickJSContext ctx, Req req, Response res) {
        try (res) {
            JSObject jsObject = ctx.createNewJSObject();
            JSObject jsHeader = ctx.createNewJSObject();
            setHeader(ctx, res, jsHeader);
            jsObject.setProperty("code", res.code());
            jsObject.setProperty("headers", jsHeader);
            if (req.getBuffer() == 0) jsObject.setProperty("content", new String(res.body().bytes(), req.getCharset()));
            if (req.getBuffer() == 1) jsObject.setProperty("content", JSUtil.toArray(ctx, res.body().bytes()));
            if (req.getBuffer() == 2) jsObject.setProperty("content", Util.base64(res.body().bytes()));
            if (req.getBuffer() == 3) jsObject.setProperty("content", res.body().bytes());
            return jsObject;
        } catch (Exception e) {
            return error(ctx);
        }
    }

    public static JSObject error(QuickJSContext ctx) {
        JSObject jsObject = ctx.createNewJSObject();
        JSObject jsHeader = ctx.createNewJSObject();
        jsObject.setProperty("headers", jsHeader);
        jsObject.setProperty("content", "");
        jsObject.setProperty("code", "");
        return jsObject;
    }

    private static Request getRequest(String url, Req req, Headers headers) {
        if (req.getMethod().equalsIgnoreCase("post")) {
            return new Request.Builder().url(url).headers(headers).post(getPostBody(req, headers.get(HttpHeaders.CONTENT_TYPE))).build();
        } else if (req.getMethod().equalsIgnoreCase("header")) {
            return new Request.Builder().url(url).headers(headers).head().build();
        } else {
            return new Request.Builder().url(url).headers(headers).get().build();
        }
    }

    private static RequestBody getPostBody(Req req, String contentType) {
        if (req.getData() != null && "json".equals(req.getPostType())) return getJsonBody(req);
        if (req.getData() != null && "form".equals(req.getPostType())) return getFormBody(req);
        if (req.getData() != null && "form-data".equals(req.getPostType())) return getFormDataBody(req);
        if (req.getBody() != null && contentType != null) return RequestBody.create(req.getBody(), MediaType.get(contentType));
        return RequestBody.create(new byte[0]);
    }

    private static RequestBody getJsonBody(Req req) {
        return RequestBody.create(req.getData().toString(), MediaType.get("application/json; charset=utf-8"));
    }

    private static RequestBody getFormBody(Req req) {
        FormBody.Builder builder = new FormBody.Builder();
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.add(key, params.get(key));
        return builder.build();
    }

    private static RequestBody getFormDataBody(Req req) {
        String boundary = "--dio-boundary-" + new SecureRandom().nextInt(42949) + new SecureRandom().nextInt(67296);
        MultipartBody.Builder builder = new MultipartBody.Builder(boundary).setType(MultipartBody.FORM);
        Map<String, String> params = Json.toMap(req.getData());
        for (String key : params.keySet()) builder.addFormDataPart(key, params.get(key));
        return builder.build();
    }

    private static void setHeader(QuickJSContext ctx, Response res, JSObject object) {
        for (Map.Entry<String, List<String>> entry : res.headers().toMultimap().entrySet()) {
            if (entry.getValue().size() == 1) object.setProperty(entry.getKey(), entry.getValue().get(0));
            if (entry.getValue().size() >= 2) object.setProperty(entry.getKey(), JSUtil.toArray(ctx, entry.getValue()));
        }
    }
}
