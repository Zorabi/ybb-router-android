// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Comparator;

/** Rules shared by native configuration screens and the protocol client. */
public final class GameRules {
    public static final String[] TYPES = {"switch", "windows", "mac", "oculus", "ps", "ps5", "xbox", "xbox_x", "xbox_s", "android", "ios"};
    private static final String[] NAMES = {"Switch 1 / 2", "Windows PC", "Mac", "Oculus", "PS4", "PS5", "Xbox", "Xbox X", "Xbox S", "Android", "iOS"};
    private GameRules() {}
    public static String typeName(String type) {
        for (int i = 0; i < TYPES.length; i++) if (TYPES[i].equals(type)) return NAMES[i];
        return "未识别设备";
    }
    public static boolean supported(String type, String version) {
        String base = version == null ? "" : version.split("-")[0];
        boolean modern = base.compareTo("20220428") > 0;
        if ("android".equals(type) || "ios".equals(type)) return modern;
        if ("oculus".equals(type)) return base.compareTo("20220325") > 0;
        for (String t : TYPES) if (t.equals(type)) return true;
        return false;
    }
    public static String platform(String type) {
        switch (type) {
            case "windows": return "win,win-web";
            case "android": return "router-android";
            case "ios": return "router-ios";
            case "ps5": return "ps";
            case "xbox_x": case "xbox_s": return "xbox";
            default: return type;
        }
    }
    public static Comparator<JSONObject> deviceOrder(JSONObject config) {
        return (a,b) -> {
            int active = Boolean.compare(b.optBoolean("acc_status"), a.optBoolean("acc_status"));
            if (active != 0) return active;
            String at = effectiveType(a,config), bt = effectiveType(b,config);
            int configured = Boolean.compare(config.optJSONObject(bt) != null, config.optJSONObject(at) != null);
            if (configured != 0) return configured;
            int platform = Integer.compare(typeIndex(at),typeIndex(bt));
            if (platform != 0) return platform;
            int ip = a.optString("ip").compareTo(b.optString("ip"));
            return ip != 0 ? ip : a.optString("mac").compareTo(b.optString("mac"));
        };
    }
    private static int typeIndex(String type) {
        for (int i=0;i<TYPES.length;i++) if(TYPES[i].equals(type)) return i;
        return TYPES.length;
    }
    public static String regionName(String region) {
        switch (region.toLowerCase(Locale.ROOT)) {
            case "auto": return "自动";
            case "jp": return "日本";
            case "hk": return "中国香港";
            case "kr": return "韩国";
            case "us": return "美国";
            case "eur": return "欧洲";
            case "tw": return "中国台湾";
            case "northcn": return "中国华北";
            case "southcn": return "中国华南";
            case "eastcn": return "中国华东";
            case "northwestcn": return "中国西北";
            default: return region.isEmpty() ? "未选择" : region;
        }
    }
    public static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
    public static JSONObject withGame(JSONObject latest, String type, JSONObject game, String region) throws JSONException {
        JSONObject out = copy(latest);
        // Retain unknown server settings and other platforms while replacing only this selection.
        JSONObject previous = latest.optJSONObject(type);
        JSONObject choice = previous == null ? new JSONObject() : copy(previous);
        choice.put("game", game.getString("id"));
        choice.put("name", game.getString("name"));
        choice.put("region", region.toLowerCase(Locale.ROOT));
        choice.remove("regionName"); choice.remove("pic");
        if (game.has("routeOption")) choice.put("routeOption", game.get("routeOption"));
        else choice.remove("routeOption");
        if ("android".equals(type) || "ios".equals(type)) choice.put("exeName", game.optString("exeName"));
        else choice.remove("exeName");
        out.put(type, choice);
        return out;
    }
    public static JSONObject withDeviceType(JSONObject latest, String mac, String type) throws JSONException {
        JSONObject out = copy(latest);
        JSONObject overrides = out.optJSONObject("device_type");
        if (overrides == null) overrides = new JSONObject();
        if (type.isEmpty()) overrides.remove(mac); else overrides.put(mac, type);
        out.put("device_type", overrides);
        return out;
    }
    public static String effectiveType(JSONObject device, JSONObject config) {
        JSONObject types = config.optJSONObject("device_type");
        return types == null ? device.optString("type") : types.optString(device.optString("mac"), device.optString("type"));
    }
    public static List<JSONObject> games(JSONArray source, String query) {
        String q = query.trim().toUpperCase(Locale.ROOT);
        ArrayList<JSONObject> out = new ArrayList<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject g = source.optJSONObject(i);
            if (g == null || g.optString("id").isEmpty()) continue;
            String text = g.optString("name")+" "+g.optString("ename")+" "+g.optString("pname")+" "+g.optString("tag");
            if (q.isEmpty() || text.toUpperCase(Locale.ROOT).contains(q)) out.add(g);
        }
        return out;
    }
    public static JSONObject startMessage(JSONObject device, JSONObject selection) throws JSONException {
        if (device.optString("ip").isEmpty() || selection.optString("game").isEmpty()) throw new JSONException("请先选择设备和游戏");
        JSONObject msg = new JSONObject().put("ip", device.getString("ip")).put("game_id", selection.getString("game"))
                .put("region", selection.optString("region", "auto").toLowerCase(Locale.ROOT));
        if (selection.has("routeOption")) msg.put("routeOption", selection.get("routeOption"));
        if (selection.has("exeName")) msg.put("package_name", selection.get("exeName"));
        return msg;
    }
}
