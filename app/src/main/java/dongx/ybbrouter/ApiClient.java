// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.content.Context;
import android.webkit.CookieManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ApiClient {
    static final String ORIGIN = "https://ybb.baidu.com";
    static final class Failure extends Exception {
        final int code;
        Failure(int code, String message) { super(message); this.code = code; }
        boolean needsLogin() { return code == 10001 || code == 10002; }
    }
    private final SessionStore store;
    private final String cuid;
    private JSONObject session;
    private String cookies;
    ApiClient(Context context) {
        store = new SessionStore(context); session = store.read();
        android.content.SharedPreferences prefs = context.getSharedPreferences("client", 0);
        String id = prefs.getString("cuid", "");
        if (id.isEmpty()) { id = UUID.randomUUID().toString().replace("-", ""); prefs.edit().putString("cuid", id).apply(); }
        cuid = id;
        cookies = CookieManager.getInstance().getCookie(ORIGIN);
    }
    synchronized void reloadCookies() { cookies = CookieManager.getInstance().getCookie(ORIGIN); }
    synchronized void clear() { session = new JSONObject(); cookies = null; store.clear(); }
    private synchronized void ensureToken(boolean force) throws Exception {
        long now = System.currentTimeMillis()/1000;
        if (!force && !session.optString("token").isEmpty() && session.optLong("expire") > now + 60) return;
        JSONObject data = raw("POST", "auth/token", new JSONObject().put("cuid", cuid), false);
        JSONObject fresh = data.getJSONObject("result");
        if (fresh.optString("token").isEmpty()) throw new Failure(10001, "请登录百度账号");
        store.write(fresh); session = fresh;
    }
    JSONObject request(String method, String path, JSONObject body) throws Exception {
        ensureToken(false);
        try { return raw(method, path, body, true); }
        catch (Failure e) {
            // Auth rejection is safe to retry: the rejected request was not executed.
            if (!e.needsLogin()) throw e;
            ensureToken(true);
            return raw(method, path, body, true);
        }
    }
    private JSONObject raw(String method, String path, JSONObject body, boolean bearer) throws Exception {
        if (path.startsWith("/") || path.contains("://")) throw new IllegalArgumentException("Invalid API path");
        HttpURLConnection connection = (HttpURLConnection) new URL(ORIGIN+"/api/v1/server/"+path).openConnection();
        connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod(method);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-Auth-Timestamp", String.valueOf(System.currentTimeMillis()));
        connection.setRequestProperty("X-Client-Version", "20211217");
        connection.setRequestProperty("X-Device-Name", "router");
        connection.setRequestProperty("X-Channel-Name", "mirouter");
        connection.setRequestProperty("X-System-Version", "");
        connection.setRequestProperty("X-Client-Cuid", cuid);
        connection.setRequestProperty("Referer", ORIGIN+"/m/pages/xiaomi/acc");
        synchronized (this) {
            if (cookies != null) connection.setRequestProperty("Cookie", cookies);
            if (bearer) connection.setRequestProperty("Authorization", "Bearer "+session.optString("token"));
        }
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status == 401 || status == 403) throw new Failure(10001, "登录已过期，请重新登录");
            if (status < 200 || status >= 300) throw new Failure(status, "服务暂时不可用（"+status+"），请稍后重试");
            JSONObject response;
            try (InputStream in = connection.getInputStream()) { response = new JSONObject(new String(read(in), StandardCharsets.UTF_8)); }
            if (!response.optBoolean("success")) {
                JSONObject error = response.optJSONObject("errors");
                throw new Failure(error == null ? -1 : error.optInt("code", -1), error == null ? "请求失败，请稍后重试" : error.optString("message_cn", "请求失败，请稍后重试"));
            }
            return response;
        } finally { connection.disconnect(); }
    }
    static byte[] read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n = in.read(buffer)) != -1) {
            if (out.size() + n > 16 * 1024 * 1024) throw new java.io.IOException("服务返回的数据过大");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
