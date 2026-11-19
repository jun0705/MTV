package com.fongmi.android.tv.server;

import android.content.Context;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.PhpSetting;
import com.fongmi.android.tv.utils.PhpAssets;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本地 PHP 服务：用 NanoHTTPD 提供 HTTP，静态文件直接返回，.php 以 CGI 方式调用 PHP 解释器。
 *
 * <p>关键点是 PHP 的启动方式：Android 10+ 禁止 App execve 私有目录下的二进制，
 * 因此借助系统自带的 /system/bin/linker64（永恒可执行）加载 PHP 解释器，
 * php 与依赖库只需 read 权限即可，从而彻底绕过执行限制。php 及其依赖库来自
 * assets/php_runtime.zip，首次开启时解压到 files/php/（RUNPATH=$ORIGIN + LD_LIBRARY_PATH 双保险）。
 * stdout 保持纯净 CGI 输出，linker/PHP 警告重定向到日志，避免污染响应。</p>
 */
public class PhpServer {

    private volatile NanoPHP nano;
    private volatile int port;

    private static class Loader {
        static final PhpServer INSTANCE = new PhpServer();
    }

    public static PhpServer get() {
        return Loader.INSTANCE;
    }

    private File getRoot() {
        String path = PhpSetting.getRoot();
        File root = (path == null || path.isEmpty()) ? PhpSetting.defaultRootFile() : new File(path);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    public boolean isRunning() {
        return nano != null;
    }

    public boolean isPreparing() {
        return false;
    }

    /** 局域网访问地址 */
    public String getAddress() {
        return "http://" + Util.getIp() + ":" + currentPort() + "/";
    }

    /** 本机访问地址 */
    public String getLocalAddress() {
        return "http://127.0.0.1:" + currentPort() + "/";
    }

    private int currentPort() {
        return isRunning() ? port : PhpSetting.getPort();
    }

    /** 启动服务（先确保运行环境已解压）。固定使用配置端口，避免地址与配置不一致。 */
    public synchronized void start(Context context) {
        if (isRunning()) return;
        PhpAssets.installRuntime();
        int port = PhpSetting.getPort();
        try {
            NanoPHP server = new NanoPHP(port);
            server.start(500);
            if (!server.isAlive()) {
                try { server.stop(); } catch (Exception ignored) { }
                App.post(() -> Notify.show(R.string.php_start_fail));
                return;
            }
            nano = server;
            this.port = port;
            App.post(() -> Notify.show(R.string.php_started));
        } catch (Throwable e) {
            App.post(() -> Notify.show(R.string.php_start_fail));
        }
    }

    public synchronized void stop() {
        if (nano != null) {
            try { nano.stop(); } catch (Exception ignored) { }
            nano = null;
            App.post(() -> Notify.show(R.string.php_stopped));
        }
    }

    /** 按当前开关状态重启服务，统一状态入口 */
    public synchronized void restart(Context context) {
        stop();
        if (PhpSetting.isEnabled()) start(context);
    }

    private File findBinary() {
        File builtin = new File(PhpAssets.runtimeDir(), "php");
        if (builtin.exists()) {
            builtin.setExecutable(true, false);
            return builtin;
        }
        return null;
    }

    private class NanoPHP extends NanoHTTPD {

        NanoPHP(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            File file = resolve(session.getUri().trim());
            if (file == null || file.isDirectory()) return Nano.error(Response.Status.NOT_FOUND, "Not Found");
            if (file.getName().endsWith(".php")) return cgi(session, file);
            return static_(file);
        }

        private File resolve(String uri) {
            try {
                File root = getRoot().getCanonicalFile();
                String path = uri.replaceFirst("^/", "");
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                if (path.isEmpty()) path = "index.php";
                File file = new File(root, path).getCanonicalFile();
                if (!file.getPath().startsWith(root.getPath())) return null;
                if (file.isDirectory() && new File(file, "index.php").exists()) return new File(file, "index.php");
                if (file.isDirectory() && new File(file, "index.html").exists()) return new File(file, "index.html");
                return file.exists() ? file : null;
            } catch (Exception e) {
                return null;
            }
        }

        private Response static_(File file) {
            try {
                InputStream is = new java.io.FileInputStream(file);
                return newChunkedResponse(Response.Status.OK, getMimeTypeForFile(file.getName()), is);
            } catch (Exception e) {
                return Nano.error(Response.Status.INTERNAL_ERROR, e.toString());
            }
        }

        private Response cgi(IHTTPSession session, File file) {
            File binary = findBinary();
            if (binary == null) return Nano.error(Response.Status.INTERNAL_ERROR, "PHP 解释器未找到");
            Map<String, String> files = new HashMap<>();
            try {
                session.parseBody(files);
            } catch (Exception ignored) {
            }
            String body = files.get("postData");
            if (body == null) body = "";
            try {
                String libDir = binary.getParentFile().getAbsolutePath();
                File boot = new File(libDir, "_cgi_boot.php");
                String phpPath = binary.getAbsolutePath();
                // 动态生成 php.ini：错误写日志、用 auto_prepend_file 注入 CGI 引导脚本
                try {
                    File ini = new File(libDir, "php.ini");
                    StringBuilder sb = new StringBuilder();
                    sb.append("[PHP]\n");
                    sb.append("display_errors = Off\n");
                    sb.append("log_errors = On\n");
                    sb.append("error_log = \"").append(new File(libDir, "php_error.log").getAbsolutePath()).append("\"\n");
                    sb.append("error_reporting = E_ALL\n");
                    sb.append("memory_limit = 128M\n");
                    sb.append("max_execution_time = 30\n");
                    sb.append("default_charset = UTF-8\n");
                    if (boot.exists()) sb.append("auto_prepend_file = \"").append(boot.getAbsolutePath()).append("\"\n");
                    FileWriter fw = new FileWriter(ini, false);
                    fw.write(sb.toString());
                    fw.close();
                } catch (Exception ignored) {
                }
                String[] argv = new String[]{phpPath, file.getAbsolutePath()};
                File linker = new File("/system/bin/linker64");
                ProcessBuilder pb;
                if (linker.exists()) {
                    String[] cmd = new String[argv.length + 1];
                    cmd[0] = linker.getAbsolutePath();
                    System.arraycopy(argv, 0, cmd, 1, argv.length);
                    pb = new ProcessBuilder(cmd);
                } else {
                    pb = new ProcessBuilder(argv);
                }
                pb.directory(getRoot());
                // stderr 写日志，不合并进 stdout，避免污染 CGI 输出
                try {
                    pb.redirectError(ProcessBuilder.Redirect.to(new File(libDir, "php_stderr.log")));
                } catch (Exception ignored) {
                }
                String oldLib = pb.environment().get("LD_LIBRARY_PATH");
                pb.environment().put("LD_LIBRARY_PATH", oldLib == null || oldLib.isEmpty() ? libDir : libDir + ":" + oldLib);
                pb.environment().put("PHPRC", libDir);
                pb.environment().put("GATEWAY_INTERFACE", "CGI/1.1");
                pb.environment().put("REDIRECT_STATUS", "200");
                pb.environment().put("REQUEST_METHOD", session.getMethod().name());
                pb.environment().put("SCRIPT_FILENAME", file.getAbsolutePath());
                pb.environment().put("SCRIPT_NAME", "/" + file.getName());
                pb.environment().put("DOCUMENT_ROOT", getRoot().getAbsolutePath());
                String qs = session.getQueryParameterString() == null ? "" : session.getQueryParameterString();
                pb.environment().put("REQUEST_URI", session.getUri() + (qs.isEmpty() ? "" : "?" + qs));
                pb.environment().put("QUERY_STRING", qs);
                pb.environment().put("SERVER_PROTOCOL", "HTTP/1.1");
                String host = session.getHeaders().get("host");
                if (host == null || host.isEmpty()) host = Util.getIp() + ":" + currentPort();
                pb.environment().put("HTTP_HOST", host);
                pb.environment().put("PHP_HTTP_HOST", host);
                pb.environment().put("PHP_SCRIPT_NAME", session.getUri());
                pb.environment().put("SERVER_NAME", Util.getIp());
                pb.environment().put("REMOTE_ADDR", session.getRemoteIpAddress());
                String length = session.getHeaders().get("content-length");
                if (length != null) pb.environment().put("CONTENT_LENGTH", length);
                String type = session.getHeaders().get("content-type");
                if (type != null) pb.environment().put("CONTENT_TYPE", type);
                String ua = session.getHeaders().get("user-agent");
                if (ua != null) pb.environment().put("HTTP_USER_AGENT", ua);
                Process process = pb.start();
                OutputStream os = process.getOutputStream();
                if (!body.isEmpty()) os.write(body.getBytes());
                os.flush();
                os.close();
                byte[] out = read(process.getInputStream());
                process.waitFor();
                return response(out);
            } catch (Exception e) {
                return Nano.error(Response.Status.INTERNAL_ERROR, e.toString());
            }
        }

        private byte[] read(InputStream is) throws Exception {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) out.write(buffer, 0, len);
            is.close();
            return out.toByteArray();
        }

        private Response response(byte[] cgi) {
            String text = new String(cgi, java.nio.charset.StandardCharsets.UTF_8);
            // 容错：linker/PHP 偶尔把警告写到 stdout，截首个 JSON 起止符拿纯净内容
            int first = -1;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '{' || c == '[') { first = i; break; }
            }
            if (first >= 0) {
                int last = -1;
                for (int i = text.length() - 1; i >= first; i--) {
                    char c = text.charAt(i);
                    if (c == '}' || c == ']') { last = i; break; }
                }
                if (last > first) {
                    String pure = text.substring(first, last + 1);
                    return newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", pure);
                }
            }
            int split = text.indexOf("\r\n\r\n");
            int size = 4;
            if (split < 0) { split = text.indexOf("\n\n"); size = 2; }
            String headers = split < 0 ? "" : text.substring(0, split);
            byte[] body = split < 0 ? cgi : trim(cgi, split + size);
            Response.Status status = Response.Status.OK;
            String type = "text/html";
            for (String line : headers.split("\r?\n")) {
                String[] part = line.split(":", 2);
                if (part.length != 2) continue;
                String key = part[0].trim().toLowerCase();
                String value = part[1].trim();
                if (key.equals("status")) {
                    try { status = Response.Status.lookup(Integer.parseInt(value.split(" ")[0])); } catch (Exception ignored) { }
                } else if (key.equals("content-type")) {
                    type = value;
                }
            }
            return newFixedLengthResponse(status, type, new String(body, java.nio.charset.StandardCharsets.UTF_8));
        }

        private byte[] trim(byte[] data, int offset) {
            byte[] result = new byte[data.length - offset];
            System.arraycopy(data, offset, result, 0, result.length);
            return result;
        }
    }
}
