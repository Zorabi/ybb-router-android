// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

final class Repository {
    final ApiClient api;
    private final File directory;
    private final ConcurrentHashMap<String, JSONObject> memory = new ConcurrentHashMap<>();
    private volatile int generation;
    int epoch() { return generation; }
    private final Object configLock = new Object();
    Repository(Context context) { api = new ApiClient(context); directory = new File(context.getNoBackupFilesDir(), "native-cache"); directory.mkdirs(); }
    String fileKey(String key) { return key.replaceAll("[^a-zA-Z0-9_-]", "_"); }
    synchronized JSONObject cached(String key) {
        JSONObject value = memory.get(key);
        if (value != null) return value;
        try (FileInputStream in = new AtomicFile(new File(directory, fileKey(key)+".json")).openRead()) {
            value = new JSONObject(new String(ApiClient.read(in), StandardCharsets.UTF_8)); memory.put(key, value); return value;
        } catch (Exception ignored) { return null; }
    }
    synchronized void save(String key, JSONObject data) {
        memory.put(key, data);
        AtomicFile file = new AtomicFile(new File(directory, fileKey(key)+".json")); FileOutputStream out = null;
        try { out = file.startWrite(); out.write(data.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(out); }
        catch (Exception e) { if (out != null) file.failWrite(out); }
    }
    JSONObject fetch(String key, String path) throws Exception {
        int epoch = generation;
        JSONObject data = api.request("GET", path, null);
        if (epoch != generation) throw new ApiClient.Failure(10001, "登录状态已变化");
        // User info includes unrelated server credentials. Cache only display fields.
        if ("user".equals(key)) {
            JSONObject user = data.getJSONObject("result");
            data.put("result", new JSONObject().put("nick_name", user.optString("nick_name")).put("avatar", user.optString("avatar")));
        }
        if (key.startsWith("games_")) {
            JSONArray source = data.getJSONArray("result"), compact = new JSONArray();
            String[] fields = {"id", "name", "ename", "pname", "tag", "routeOption", "exeName", "sort"};
            for (int i = 0; i < source.length(); i++) {
                JSONObject game = source.getJSONObject(i), item = new JSONObject();
                for (String field : fields) if (game.has(field)) item.put(field, game.get(field));
                compact.put(item);
            }
            data.put("result", compact);
        }
        if (key.startsWith("regions_")) {
            JSONObject source = data.getJSONObject("result"), compact = new JSONObject();
            for (String field : new String[]{"region_list", "notice", "name", "routeOption"}) if (source.has(field)) compact.put(field, source.get(field));
            data.put("result", compact);
        }
        synchronized (this) {
            if (epoch != generation) throw new ApiClient.Failure(10001, "登录状态已变化");
            save(key, data);
        }
        return data;
    }
    JSONObject fetchConfig() throws Exception { return fetch("config", "router/config/game").getJSONObject("result"); }
    JSONObject saveGame(String type, JSONObject game, String region) throws Exception {
        synchronized (configLock) {
            JSONObject latest = fetchConfig(); JSONObject merged = GameRules.withGame(latest, type, game, region);
            api.request("POST", "router/config/game", merged);
            save("config", new JSONObject().put("result", merged)); return merged;
        }
    }
    JSONObject saveDeviceType(String mac, String type) throws Exception {
        synchronized (configLock) {
            JSONObject merged = GameRules.withDeviceType(fetchConfig(), mac, type);
            api.request("POST", "router/config/game", merged);
            save("config", new JSONObject().put("result", merged)); return merged;
        }
    }
    JSONObject games(String type, boolean force) throws Exception {
        String platform = GameRules.platform(type), key = "games_"+platform;
        JSONObject cache = cached(key);
        if (!force && cache != null && System.currentTimeMillis()/1000-cache.optLong("server_time") < 86400) return cache;
        return fetch(key, "game/list?type="+java.net.URLEncoder.encode(platform, "UTF-8"));
    }
    JSONObject regions(String game, boolean force) throws Exception {
        String key = "regions_"+game; JSONObject cache = cached(key);
        if (!force && cache != null && System.currentTimeMillis()/1000-cache.optLong("server_time") < 86400) return cache;
        return fetch(key, "gamelist/detail?game_id="+java.net.URLEncoder.encode(game, "UTF-8"));
    }
    void message(String type, JSONObject msg) throws Exception {
        api.request("POST", "router/message", new JSONObject().put("type", type).put("msg", msg));
    }
    synchronized void clear() {
        generation++; memory.clear(); File[] files = directory.listFiles(); if (files != null) for (File file : files) file.delete(); api.clear();
    }
    static JSONObject object(JSONObject data) { return data == null || data.optJSONObject("result") == null ? new JSONObject() : data.optJSONObject("result"); }
    static JSONArray array(JSONObject data) { return data == null || data.optJSONArray("result") == null ? new JSONArray() : data.optJSONArray("result"); }
}
