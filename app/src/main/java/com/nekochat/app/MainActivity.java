package com.nekochat.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A7chat — 通用 AstrBot 客户端
 * 页面不直接发网络请求，也不靠 JS 注入：App 内起一个只监听 127.0.0.1 的小 HTTP 服务，
 * 页面访问 http://127.0.0.1:port/ ，/p?u=目标地址 由服务端代为转发（自动附带登录 token）。
 * 这样绕开 CORS、绕开 addJavascriptInterface 的各种坑。
 */
public class MainActivity extends Activity {

    private WebView web;
    private Bridge bridge;
    private MiniServer server;
    private ValueCallback<Uri[]> filePathCallback;
    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        sp = getSharedPreferences("nekochat", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#F7F9F7"));
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(true);
        ws.setAllowContentAccess(true);
        ws.setAllowFileAccessFromFileURLs(true);
        ws.setAllowUniversalAccessFromFileURLs(true);
        ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        ws.setSupportZoom(false);
        ws.setBuiltInZoomControls(false);
        ws.setTextZoom(100);
        ws.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = cb;
                try {
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    startActivityForResult(Intent.createChooser(i, "选择文件"), 1001);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient());
        bridge = new Bridge(this);
        web.addJavascriptInterface(bridge, "AstrBot");

        setContentView(web);

        String startUrl = "file:///android_asset/index.html";
        try {
            server = new MiniServer(this);
            server.loadPrefs();
            server.start();
            startUrl = "http://127.0.0.1:" + server.port + "/";
        } catch (Exception ignored) {
        }
        web.loadUrl(startUrl);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == 1001) {
            Uri[] out = null;
            if (res == RESULT_OK && data != null) {
                if (data.getData() != null) {
                    out = new Uri[]{data.getData()};
                } else if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    out = new Uri[n];
                    for (int i = 0; i < n; i++) out[i] = data.getClipData().getItemAt(i).getUri();
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(out);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    protected void onDestroy() {
        if (server != null) server.stop();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web == null) {
            super.onBackPressed();
            return;
        }
        web.evaluateJavascript("(function(){try{return !!(window.__back && window.__back());}catch(e){return false;}})()",
                value -> {
                    if (!"true".equals(value)) finish();
                });
    }

    private void uiToast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
    }

    /* ================= 本机转发服务 ================= */

    static class MiniServer {
        private final MainActivity act;
        private ServerSocket ss;
        volatile int port;
        volatile String token = "";
        volatile String base = "";
        volatile String sid = "";
        // 面板协议档位：2=/api/v1（新版） 1=/api/chat/session 命名 0=4.0 的 conversation 命名
        int apiTier = 2;
        volatile String botName = "AstrBot";
        volatile String userName = "";

        MiniServer(MainActivity a) {
            this.act = a;
        }

        private File stickerDir() {
            File d = new File(act.getFilesDir(), "stickers");
            if (!d.exists()) d.mkdirs();
            return d;
        }

        private JSONArray stickerList() {
            JSONArray arr = new JSONArray();
            File[] fs = stickerDir().listFiles();
            if (fs == null) return arr;
            java.util.Arrays.sort(fs, (a, b) -> b.lastModified() > a.lastModified() ? 1 : (b.lastModified() == a.lastModified() ? 0 : -1));
            for (File f : fs) {
                if (!f.isFile()) continue;
                String n = f.getName();
                int dot = n.lastIndexOf('.');
                String id = dot > 0 ? n.substring(0, dot) : n;
                try {
                    JSONObject o = new JSONObject();
                    o.put("id", id);
                    o.put("url", "/stickers/" + n);
                    arr.put(o);
                } catch (Exception ignored) {
                }
            }
            return arr;
        }

        private android.content.SharedPreferences sp() {
            return act.getSharedPreferences("nekochat", android.content.Context.MODE_PRIVATE);
        }

        void loadPrefs() {
            android.content.SharedPreferences p = sp();
            base = p.getString("base", "");
            token = p.getString("token", "");
            sid = p.getString("sid", "");
            botName = p.getString("botName", "AstrBot");
            userName = p.getString("userName", "");
            apiTier = p.getInt("apiTier", 2);
        }

        void savePrefs() {
            sp().edit().putString("base", base).putString("token", token)
                    .putString("sid", sid).putString("botName", botName)
                    .putString("userName", userName).putInt("apiTier", apiTier).apply();
        }

        void start() throws Exception {
            ss = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            port = ss.getLocalPort();
            Thread t = new Thread(() -> {
                while (true) {
                    try {
                        final Socket s = ss.accept();
                        s.setTcpNoDelay(true);
                        Thread w = new Thread(() -> handle(s));
                        w.setDaemon(true);
                        w.start();
                    } catch (Exception e) {
                        break;
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        }

        void stop() {
            try {
                if (ss != null) ss.close();
            } catch (Exception ignored) {
            }
        }

        /* ---------------- 基础工具 ---------------- */

        private static String readLine(InputStream in) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            int c;
            boolean any = false;
            while ((c = in.read()) != -1) {
                any = true;
                if (c == '\n') break;
                if (c != '\r') bos.write(c);
            }
            if (!any) return null;
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }

        private static byte[] readAll(InputStream in) throws Exception {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }

        private static String param(String target, String key) {
            int q = target.indexOf('?');
            if (q < 0) return null;
            String[] pairs = target.substring(q + 1).split("&");
            for (String pair : pairs) {
                int e = pair.indexOf('=');
                if (e <= 0) continue;
                if (key.equals(pair.substring(0, e))) {
                    try {
                        return URLDecoder.decode(pair.substring(e + 1), "UTF-8");
                    } catch (Exception ex) {
                        return pair.substring(e + 1);
                    }
                }
            }
            return null;
        }

        private static String normBase(String b) {
            b = b == null ? "" : b.trim();
            if (b.isEmpty()) return "";
            if (!b.startsWith("http://") && !b.startsWith("https://")) b = "http://" + b;
            while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
            return b;
        }

        private void writeRaw(OutputStream out, int code, String ct, byte[] data) throws Exception {
            out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: " + ct + "\r\n"
                    + "Content-Length: " + data.length + "\r\nCache-Control: no-store\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(data);
            out.flush();
        }

        private void writeJson(OutputStream out, String json) throws Exception {
            writeRaw(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
        }

        private void serveAsset(OutputStream out, String name) throws Exception {
            InputStream is;
            try {
                is = act.getAssets().open(name);
            } catch (Exception e) {
                is = act.getAssets().open("index.html");
            }
            byte[] data = readAll(is);
            is.close();
            String ct = name.endsWith(".png") ? "image/png"
                    : name.endsWith(".html") ? "text/html; charset=utf-8"
                    : name.endsWith(".json") ? "application/json; charset=utf-8"
                    : "application/octet-stream";
            writeRaw(out, 200, ct, data);
        }

        /* 向真实服务器发请求，返回 {状态码, 响应体} */

        /* ---------- 老版本面板兼容 ---------- */
        private String pickToken(String body) {
            try {
                JSONObject j = new JSONObject(body);
                JSONObject d = j.optJSONObject("data");
                String t = d != null ? d.optString("token", "") : "";
                if (t.isEmpty()) t = j.optString("token", "");
                if (t.isEmpty()) t = j.optString("access_token", "");
                return t;
            } catch (Exception e) {
                return "";
            }
        }

        private String firstNonEmpty(JSONObject o, String... keys) {
            for (String k : keys) {
                String v = o.optString(k, "");
                if (!v.isEmpty()) return v;
            }
            return "";
        }

        private boolean missing(String[] r) {
            String c = r[0];
            return "404".equals(c) || "405".equals(c) || "400".equals(c) || "000".equals(c);
        }

        private void detectTier() {
            String[] p = httpJson("GET", base + "/api/v1/chat/sessions", null, null);
            if (!missing(p)) { apiTier = 2; return; }
            p = httpJson("GET", base + "/api/chat/sessions", null, null);
            if (!missing(p)) { apiTier = 1; return; }
            apiTier = 0;
        }

        private String tierName() {
            return apiTier == 2 ? "新版" : (apiTier == 1 ? "兼容" : "老版本");
        }

        private void sendSessions(OutputStream out) throws Exception {
            String url = apiTier == 2 ? base + "/api/v1/chat/sessions"
                    : (apiTier == 1 ? base + "/api/chat/sessions"
                    : base + "/api/chat/conversations");
            String[] r = httpJson("GET", url, null, null);
            JSONArray arr = new JSONArray();
            try {
                JSONObject j = new JSONObject(r[1]);
                Object data = j.opt("data");
                JSONArray src = null;
                if (data instanceof JSONArray) src = (JSONArray) data;
                else if (data instanceof JSONObject) {
                    JSONObject dj = (JSONObject) data;
                    src = dj.optJSONArray("sessions");
                    if (src == null) src = dj.optJSONArray("conversations");
                }
                if (src == null) src = new JSONArray();
                for (int i = 0; i < src.length(); i++) {
                    JSONObject o0 = src.optJSONObject(i);
                    if (o0 == null) continue;
                    String id = firstNonEmpty(o0, "session_id", "conversation_id", "cid", "id");
                    if (id.isEmpty()) continue;
                    JSONObject o = new JSONObject();
                    o.put("session_id", id);
                    o.put("display_name", firstNonEmpty(o0, "display_name", "title", "name"));
                    o.put("updated_at", firstNonEmpty(o0, "updated_at", "created_at"));
                    arr.put(o);
                }
            } catch (Exception ignored) {
            }
            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("data", arr);
            writeJson(out, res.toString());
        }

        private void sendHistory(OutputStream out) throws Exception {
            String url = apiTier == 2 ? base + "/api/v1/chat/sessions/" + sid
                    : (apiTier == 1 ? base + "/api/chat/get_session?session_id=" + sid
                    : base + "/api/chat/get_conversation?conversation_id=" + sid);
            String[] r = httpJson("GET", url, null, null);
            JSONArray hist = new JSONArray();
            try {
                JSONObject j = new JSONObject(r[1]);
                Object data = j.opt("data");
                JSONArray src = null;
                if (data instanceof JSONObject) src = ((JSONObject) data).optJSONArray("history");
                else if (data instanceof JSONArray) src = (JSONArray) data;
                if (src == null) src = j.optJSONArray("history");
                if (src == null) src = new JSONArray();
                for (int i = 0; i < src.length(); i++) {
                    JSONObject h = src.optJSONObject(i);
                    if (h == null) continue;
                    Object c0 = h.opt("content");
                    JSONObject c;
                    if (c0 instanceof String) {
                        try { c = new JSONObject((String) c0); } catch (Exception e) { continue; }
                    } else if (c0 instanceof JSONObject) {
                        c = (JSONObject) c0;
                    } else {
                        continue;
                    }
                    Object m = c.opt("message");
                    JSONArray parts = c.optJSONArray("message");
                    if (m instanceof String) {
                        parts = new JSONArray();
                        JSONObject p0 = new JSONObject();
                        p0.put("type", "plain");
                        p0.put("text", (String) m);
                        parts.put(p0);
                    }
                    if (parts == null) parts = new JSONArray();
                    String img = c.optString("image_url", "");
                    if (img.isEmpty()) img = c.optString("audio_url", "");
                    if (!img.isEmpty()) {
                        JSONObject p1 = new JSONObject();
                        p1.put("type", "image");
                        p1.put("attachment_id", img);
                        p1.put("filename", img);
                        parts.put(p1);
                    }
                    c.put("message", parts);
                    JSONObject o = new JSONObject();
                    o.put("content", c);
                    o.put("created_at", h.optString("created_at", ""));
                    hist.put(o);
                }
            } catch (Exception ignored) {
            }
            JSONObject inner = new JSONObject();
            inner.put("history", hist);
            JSONObject res = new JSONObject();
            res.put("status", "ok");
            res.put("data", inner);
            writeJson(out, res.toString());
        }

        private String[] httpJson(String method, String url, String contentType, byte[] body) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                if ("PATCH".equals(method)) {
                    try {
                        java.lang.reflect.Field fm = java.net.URLConnection.class.getDeclaredField("method");
                        fm.setAccessible(true);
                        fm.set(c, "PATCH");
                    } catch (Exception e) {
                        c.setRequestMethod("POST");
                    }
                } else {
                    c.setRequestMethod(method);
                }
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                c.setRequestProperty("Accept", "application/json");
                if (!token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
                if (body != null && body.length > 0) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type",
                            contentType == null ? "application/json" : contentType);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.flush();
                    os.close();
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = is == null ? "" : new String(readAll(is), StandardCharsets.UTF_8);
                return new String[]{String.valueOf(code), text};
            } catch (Exception e) {
                return new String[]{"0", "{\"status\":\"error\",\"message\":\"" + e.toString().replace("\"", "'") + "\"}"};
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /* ---------------- 请求入口 ---------------- */

        private void handle(Socket sock) {
            try {
                InputStream in = new BufferedInputStream(sock.getInputStream(), 65536);
                OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 65536);

                String reqLine = readLine(in);
                if (reqLine == null) {
                    sock.close();
                    return;
                }
                String[] parts = reqLine.split(" ");
                String method = parts.length > 0 ? parts[0] : "GET";
                String target = parts.length > 1 ? parts[1] : "/";

                Map<String, String> hs = new HashMap<>();
                String h;
                while ((h = readLine(in)) != null && !h.isEmpty()) {
                    int i = h.indexOf(':');
                    if (i > 0) hs.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
                }
                int len = 0;
                try {
                    len = Integer.parseInt(hs.containsKey("content-length") ? hs.get("content-length") : "0");
                } catch (Exception ignored) {
                }
                byte[] body = new byte[len];
                int off = 0;
                while (off < len) {
                    int n = in.read(body, off, len - off);
                    if (n < 0) break;
                    off += n;
                }

                route(method, target, hs, body, out);
                out.flush();
                sock.close();
            } catch (Exception ignored) {
                try {
                    sock.close();
                } catch (Exception ignored2) {
                }
            }
        }

        private void route(String method, String target, Map<String, String> hs, byte[] body, OutputStream out) throws Exception {
            String path = target.split("\\?")[0];
            if (path.startsWith("/api/")) {
                handleApi(method, path, target, hs, body, out);
            } else if (path.equals("/") || path.equals("/index.html")) {
                serveAsset(out, "index.html");
            } else if (path.startsWith("/avatar/")) {
                String who = path.contains("user") ? "user" : "bot";
                File f = new File(act.getFilesDir(), "avatar_" + who + ".png");
                if (f.exists()) {
                    FileInputStream fis = new FileInputStream(f);
                    byte[] data = readAll(fis);
                    fis.close();
                    writeRaw(out, 200, "image/png", data);
                } else {
                    serveAsset(out, "icons/bot.png");
                }
            } else if (path.startsWith("/stickers/")) {
                File f = new File(stickerDir(), path.substring("/stickers/".length()));
                if (f.exists() && f.isFile()) {
                    FileInputStream fis = new FileInputStream(f);
                    byte[] data = readAll(fis);
                    fis.close();
                    String n = f.getName().toLowerCase(Locale.ROOT);
                    String ct = n.endsWith(".png") ? "image/png"
                            : (n.endsWith(".webp") ? "image/webp" : "image/jpeg");
                    writeRaw(out, 200, ct, data);
                } else {
                    writeRaw(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
                }
            } else if (path.startsWith("/icons/")) {
                serveAsset(out, path.substring(1));
            } else if (path.equals("/manifest.json")) {
                writeJson(out, "{\"name\":\"A7chat\",\"short_name\":\"A7chat\",\"start_url\":\"/\",\"display\":\"standalone\"}");
            } else {
                serveAsset(out, path.substring(1));
            }
        }

        private void handleApi(String method, String path, String target,
                               Map<String, String> hs, byte[] body, OutputStream out) throws Exception {

            if (path.equals("/api/login")) {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String b = normBase(in.optString("base", ""));
                if (!b.isEmpty()) base = b;
                userName = in.optString("username", "");
                JSONObject req = new JSONObject();
                req.put("username", userName);
                req.put("password", in.optString("password", ""));
                String[] r = httpJson("POST", base + "/api/v1/auth/login", null,
                        req.toString().getBytes(StandardCharsets.UTF_8));
                String got = pickToken(r[1]);
                if (got.isEmpty()) {
                    // 老版本面板只有 /api/auth/login
                    r = httpJson("POST", base + "/api/auth/login", null,
                            req.toString().getBytes(StandardCharsets.UTF_8));
                    got = pickToken(r[1]);
                }
                if (!got.isEmpty()) token = got;
                if (!token.isEmpty()) detectTier();
                savePrefs();
                writeRaw(out, 200, "application/json; charset=utf-8", r[1].getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/api/state")) {
                JSONObject j = new JSONObject();
                j.put("session_id", sid);
                j.put("server_mode", tierName());
                j.put("server_tier", apiTier);
                j.put("bot_name", botName);
                j.put("user_name", userName);
                File fb = new File(act.getFilesDir(), "avatar_bot.png");
                File fu = new File(act.getFilesDir(), "avatar_user.png");
                j.put("bot_avatar", fb.exists()
                        ? "/avatar/bot.png?v=" + fb.lastModified()
                        : "/icons/bot.png?v=20");
                j.put("user_avatar", fu.exists()
                        ? "/avatar/user.png?v=" + fu.lastModified() : "");
                j.put("stickers", stickerList());
                j.put("moments", new org.json.JSONArray());
                JSONObject pro = new JSONObject();
                pro.put("enable", false);
                pro.put("min_minutes", 90);
                pro.put("max_minutes", 240);
                j.put("proactive", pro);
                writeJson(out, j.toString());
                return;
            }

            if (path.equals("/api/session/set")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String s = in.optString("session_id", "");
                    if (!s.isEmpty()) { sid = s; savePrefs(); }
                } catch (Exception ignored) {
                }
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if (path.equals("/api/session/new")) {
                String url = apiTier == 2 ? base + "/api/v1/chat/sessions/new"
                        : (apiTier == 1 ? base + "/api/chat/new_session"
                        : base + "/api/chat/new_conversation");
                String[] r = httpJson("GET", url, null, null);
                String created = "";
                try {
                    JSONObject j = new JSONObject(r[1]);
                    JSONObject d = j.optJSONObject("data");
                    if (d != null) created = firstNonEmpty(d, "session_id", "conversation_id", "cid", "id");
                    if (created.isEmpty()) created = firstNonEmpty(j, "session_id", "conversation_id");
                } catch (Exception ignored) {
                }
                if (!created.isEmpty()) { sid = created; savePrefs(); }
                JSONObject res = new JSONObject();
                res.put("status", created.isEmpty() ? "error" : "ok");
                JSONObject dd = new JSONObject();
                dd.put("session_id", created);
                res.put("data", dd);
                writeJson(out, res.toString());
                return;
            }

            if (path.equals("/api/sessions")) {
                sendSessions(out);
                return;
            }

            if (path.equals("/api/session/rename")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String target3 = in.optString("session_id", "");
                    JSONObject req = new JSONObject();
                    req.put("session_id", target3);
                    req.put("display_name", in.optString("display_name", ""));
                    byte[] payload = req.toString().getBytes(StandardCharsets.UTF_8);
                    // 先走旧的 dashboard 接口（POST，兼容性最好），失败再试新版 PATCH
                    String[] r;
                    if (apiTier == 0) {
                        JSONObject req0 = new JSONObject();
                        req0.put("conversation_id", target3);
                        req0.put("title", in.optString("display_name", ""));
                        r = httpJson("POST", base + "/api/chat/rename_conversation", null,
                                req0.toString().getBytes(StandardCharsets.UTF_8));
                    } else {
                        r = httpJson("POST", base + "/api/chat/update_session_display_name", null, payload);
                        if (!"200".equals(r[0])) {
                            JSONObject req2 = new JSONObject();
                            req2.put("display_name", in.optString("display_name", ""));
                            r = httpJson("PATCH", base + "/api/v1/chat/sessions/" + target3, null,
                                    req2.toString().getBytes(StandardCharsets.UTF_8));
                        }
                    }
                    writeRaw(out, 200, "application/json; charset=utf-8", r[1].getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    writeJson(out, "{\"status\":\"error\"}");
                }
                return;
            }

            if (path.equals("/api/session/delete")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String target2 = in.optString("session_id", "");
                    String[] r;
                    if (apiTier == 2) {
                        r = httpJson("DELETE", base + "/api/v1/chat/sessions/" + target2, null, null);
                    } else if (apiTier == 1) {
                        r = httpJson("GET", base + "/api/chat/delete_session?session_id=" + target2, null, null);
                    } else {
                        r = httpJson("GET", base + "/api/chat/delete_conversation?conversation_id=" + target2, null, null);
                    }
                    if (target2.equals(sid)) { sid = ""; savePrefs(); }
                    writeRaw(out, 200, "application/json; charset=utf-8", r[1].getBytes(StandardCharsets.UTF_8));
                } catch (Exception e) {
                    writeJson(out, "{\"status\":\"error\"}");
                }
                return;
            }

            if (path.equals("/api/history")) {
                String q = param(target, "session_id");
                if (q != null && !q.isEmpty()) sid = q;
                sendHistory(out);
                return;
            }

            if (path.equals("/api/chat")) {
                if (apiTier == 2) {
                    proxy(method, base + "/api/v1/chat", hs, body, out);
                } else {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    Object msg = in.opt("message");
                    String sidv = in.optString("session_id", "");
                    JSONObject req = new JSONObject();
                    if (apiTier == 1) {
                        req.put("message", msg == null ? "" : msg);
                        req.put("session_id", sidv);
                    } else {
                        // 4.0 系列：message 只收字符串，图片另走 image_url
                        String textOnly = "";
                        String imgUrl = "";
                        if (msg instanceof JSONArray) {
                            JSONArray parts = (JSONArray) msg;
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < parts.length(); i++) {
                                JSONObject p0 = parts.optJSONObject(i);
                                if (p0 == null) continue;
                                if ("plain".equals(p0.optString("type")) ) sb.append(p0.optString("text", ""));
                                else if (imgUrl.isEmpty()) imgUrl = p0.optString("attachment_id", "");
                            }
                            textOnly = sb.toString();
                        } else if (msg != null) {
                            textOnly = String.valueOf(msg);
                        }
                        req.put("message", textOnly);
                        if (!imgUrl.isEmpty()) req.put("image_url", imgUrl);
                        req.put("conversation_id", sidv);
                    }
                    req.put("enable_streaming", true);
                    proxy("POST", base + "/api/chat/send", hs,
                            req.toString().getBytes(StandardCharsets.UTF_8), out);
                }
                return;
            }

            if (path.equals("/api/img")) {
                String aid = param(target, "attachment_id");
                if (aid == null) aid = "";
                if (apiTier == 2) {
                    proxy("GET", base + "/api/chat/get_attachment?attachment_id=" + aid, hs, new byte[0], out);
                } else {
                    // 老版本按文件名取
                    proxy("GET", base + "/api/chat/get_file?filename=" + java.net.URLEncoder.encode(aid, "UTF-8"),
                            hs, new byte[0], out);
                }
                return;
            }

            if (path.equals("/api/upload")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String kind = in.optString("kind", "");
                    if (kind.equals("sticker")) {
                        String data = in.optString("data", "");
                        String mime = "image/png";
                        int comma = data.indexOf(',');
                        if (data.startsWith("data:")){
                            int semi = data.indexOf(';');
                            if (semi > 5) mime = data.substring(5, semi);
                            if (comma > 0) data = data.substring(comma + 1);
                        }
                        String ext = mime.contains("jpeg") ? ".jpg" : (mime.contains("webp") ? ".webp" : ".png");
                        String id = "s" + System.currentTimeMillis();
                        File f = new File(stickerDir(), id + ext);
                        byte[] img = Base64.decode(data, Base64.DEFAULT);
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(img);
                        fos.flush();
                        fos.close();
                        JSONObject r = new JSONObject();
                        r.put("status", "ok");
                        r.put("id", id);
                        r.put("url", "/stickers/" + id + ext);
                        writeJson(out, r.toString());
                        return;
                    }
                    if (kind.equals("avatar")) {
                        String who = "bot".equals(in.optString("who", "")) ? "bot" : "user";
                        String data = in.optString("data", "");
                        int comma = data.indexOf(',');
                        if (data.startsWith("data:") && comma > 0) data = data.substring(comma + 1);
                        byte[] img = Base64.decode(data, Base64.DEFAULT);
                        File f = new File(act.getFilesDir(), "avatar_" + who + ".png");
                        FileOutputStream fos = new FileOutputStream(f);
                        fos.write(img);
                        fos.flush();
                        fos.close();
                        writeJson(out, "{\"status\":\"ok\",\"url\":\"/avatar/" + who + ".png?t=" + System.currentTimeMillis() + "\"}");
                        return;
                    }
                } catch (Exception ignored) {
                }
                writeJson(out, "{\"status\":\"ok\",\"url\":\"\"}");
                return;
            }

            if (path.equals("/api/upload_file")) {
                String filename = "file";
                try {
                    String hn = hs.get("x-filename");
                    if (hn != null) filename = URLDecoder.decode(hn, "UTF-8");
                } catch (Exception ignored) {
                }
                String mime = hs.containsKey("x-mime") ? hs.get("x-mime") : "application/octet-stream";
                String boundary = "----NekoChat" + System.currentTimeMillis();
                ByteArrayOutputStream mp = new ByteArrayOutputStream();
                mp.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                mp.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n")
                        .getBytes(StandardCharsets.UTF_8));
                mp.write(("Content-Type: " + mime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                mp.write(body);
                mp.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                String[] r = httpJson("POST", base + "/api/v1/file",
                        "multipart/form-data; boundary=" + boundary, mp.toByteArray());
                writeRaw(out, 200, "application/json; charset=utf-8", r[1].getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (path.equals("/api/settings")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    if (in.has("bot_name") && !in.optString("bot_name").isEmpty()) botName = in.optString("bot_name");
                    if (in.has("user_name") && !in.optString("user_name").isEmpty()) userName = in.optString("user_name");
                    savePrefs();
                } catch (Exception ignored) {
                }
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if (path.equals("/api/moment")) {
                writeJson(out, "{\"status\":\"ok\",\"data\":[]}");
                return;
            }

            if (path.equals("/api/avatar/reset")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String who = in.optString("who", "bot");
                    File f = new File(act.getFilesDir(), "bot".equals(who) ? "avatar_bot.png" : "avatar_user.png");
                    if (f.exists()) f.delete();
                } catch (Exception ignored) {
                }
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if (path.equals("/api/sticker/delete")) {
                try {
                    JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                    String id = in.optString("id", "");
                    File[] fs = stickerDir().listFiles();
                    if (fs != null && !id.isEmpty()) {
                        for (File f : fs) {
                            if (f.getName().startsWith(id)) f.delete();
                        }
                    }
                } catch (Exception ignored) {
                }
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            if (path.equals("/api/proactive")) {
                writeJson(out, "{\"status\":\"ok\"}");
                return;
            }

            writeJson(out, "{\"status\":\"error\",\"message\":\"不支持的接口\"}");
        }

        /* 原样转发（支持流式） */
        private void proxy(String method, String dest, Map<String, String> hs, byte[] body, OutputStream out) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(dest).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(15000);
                c.setReadTimeout(300000);
                c.setInstanceFollowRedirects(false);
                c.setUseCaches(false);
                for (Map.Entry<String, String> e : hs.entrySet()) {
                    String k = e.getKey();
                    if (k.equals("host") || k.equals("connection") || k.equals("content-length")
                            || k.equals("accept-encoding") || k.equals("origin") || k.equals("referer")
                            || k.startsWith("sec-") || k.equals("user-agent") || k.equals("cookie")) continue;
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
                if (!token.isEmpty() && c.getRequestProperty("Authorization") == null) {
                    c.setRequestProperty("Authorization", "Bearer " + token);
                }
                if (body.length > 0) {
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(body);
                    os.flush();
                    os.close();
                }
                int code = c.getResponseCode();
                String ct = c.getContentType();
                if (ct == null || ct.isEmpty()) ct = "application/json; charset=utf-8";
                out.write(("HTTP/1.1 " + code + " OK\r\nContent-Type: " + ct + "\r\n"
                        + "Cache-Control: no-store\r\nTransfer-Encoding: chunked\r\n"
                        + "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] crlf = "\r\n".getBytes(StandardCharsets.US_ASCII);
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(Integer.toHexString(n).getBytes(StandardCharsets.US_ASCII));
                        out.write(crlf);
                        out.write(buf, 0, n);
                        out.write(crlf);
                        out.flush();
                    }
                    is.close();
                }
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            } catch (Exception e) {
                try {
                    String msg = "转发失败: " + e;
                    byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                    out.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain; charset=utf-8\r\n"
                            + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    out.write(b);
                    out.flush();
                } catch (Exception ignored) {
                }
            } finally {
                if (c != null) c.disconnect();
            }
        }
    }

    /* ================= 备用 JS 桥（仅用于少量同步配置，页面不依赖它） ================= */

    public static class Bridge {

        private final MainActivity act;

        Bridge(MainActivity a) {
            this.act = a;
        }

        @JavascriptInterface
        public void toast(String msg) {
            act.uiToast(msg);
        }

        @JavascriptInterface
        public void exitApp() {
            act.runOnUiThread(act::finish);
        }
    }
}
