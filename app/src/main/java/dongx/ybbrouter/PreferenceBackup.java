// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Versioned, local preferences only. Credentials and server configuration are never exported. */
public final class PreferenceBackup {
    private static final int MAX_BYTES = 1024 * 1024;
    private static final String TYPE = "(?:switch|windows|mac|oculus|ps|ps5|xbox|xbox_x|xbox_s|android|ios)";
    private static final Pattern DEVICE = Pattern.compile("device_(?:name_|seen_|alias_|type_)?[A-Za-z0-9:._-]{1,256}");
    private static final Pattern GAMES = Pattern.compile("games_" + TYPE);
    private static final Pattern HIDDEN = Pattern.compile("hidden_game_" + TYPE + "_[A-Za-z0-9_.-]{1,128}");
    private static final Pattern REGION = Pattern.compile("game_region_" + TYPE + "_[A-Za-z0-9_.-]{1,128}");
    private PreferenceBackup() {}
    private static boolean allowed(String key) {
        return DEVICE.matcher(key).matches() || GAMES.matcher(key).matches()
                || HIDDEN.matcher(key).matches() || REGION.matcher(key).matches();
    }
    private static boolean valid(String key, Object value) {
        if (GAMES.matcher(key).matches()) {
            if (!(value instanceof String)) return false;
            try {
                JSONArray ids = new JSONArray((String)value);
                if (ids.length() > 6) return false;
                for (int i = 0; i < ids.length(); i++)
                    if (!(ids.get(i) instanceof String) || !ids.getString(i).matches("[A-Za-z0-9_.-]{1,128}")) return false;
                return true;
            } catch (Exception e) { return false; }
        }
        if(key.startsWith("device_type_"))return value instanceof String&&((String)value).length()<=64;
        if (key.startsWith("device_seen_"))
            return (value instanceof Long || value instanceof Integer) && ((Number)value).longValue() >= 0;
        if (key.startsWith("device_name_") || key.startsWith("device_alias_") || REGION.matcher(key).matches())
            return value instanceof String && ((String)value).length() <= 256;
        return value instanceof Boolean;
    }
    public static JSONObject export(Map<String, ?> preferences) throws Exception {
        JSONObject entries = new JSONObject();
        for (Map.Entry<String, ?> entry : preferences.entrySet()) {
            if (!allowed(entry.getKey())) continue;
            if (!valid(entry.getKey(), entry.getValue())) throw new IOException("本地记录格式异常，请先检查常用记录");
            entries.put(entry.getKey(), entry.getValue());
        }
        JSONObject result = new JSONObject().put("format", "dongx.ybbrouter.preferences").put("version", 1)
                .put("createdAt", System.currentTimeMillis()).put("entries", entries);
        parse(result.toString());
        return result;
    }
    public static Map<String, Object> parse(String text) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new IOException("备份文件不能超过 1 MB");
        JSONObject document;
        try { document = new JSONObject(text); } catch (Exception e) { throw new IOException("无法识别备份文件"); }
        if (!"dongx.ybbrouter.preferences".equals(document.optString("format"))
                || !(document.opt("version") instanceof Number) || document.optDouble("version") != 1)
            throw new IOException("不支持此备份文件的格式或版本");
        JSONObject entries = document.optJSONObject("entries");
        if (entries == null || entries.length() > 5000) throw new IOException("备份记录数量或格式异常");
        Map<String, Object> result = new LinkedHashMap<>();
        java.util.Iterator<String> keys = entries.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object value = entries.get(key);
            if (!allowed(key) || !valid(key, value)) throw new IOException("备份包含不支持的记录，未恢复任何数据");
            if (key.startsWith("device_seen_")) value = ((Number)value).longValue();
            result.put(key, value);
        }
        return result;
    }
    public static final class RestorePlan {
        public final int devices,aliases,games,regions;
        public final java.util.List<String> conflicts;
        private final Map<String,Object> entries,baseline;
        RestorePlan(Map<String,Object> entries,Map<String,?> existing) throws Exception {
            this.entries=new LinkedHashMap<>(entries);this.baseline=new LinkedHashMap<>(existing);
            int deviceCount=0,aliasCount=0,gameCount=0,regionCount=0;
            java.util.Set<String> conflictGroups=new java.util.TreeSet<>();
            for(Map.Entry<String,Object> entry:entries.entrySet()){
                String key=entry.getKey();Object value=entry.getValue();
                if(!allowed(key)||!valid(key,value))throw new IOException("备份包含不支持的记录");
                if(key.startsWith("device_")&&Boolean.TRUE.equals(value))deviceCount++;
                if(key.startsWith("device_alias_")&&!((String)value).isEmpty())aliasCount++;
                if(GAMES.matcher(key).matches()){
                    JSONArray ids=new JSONArray((String)value);java.util.Set<String> distinct=new java.util.HashSet<>();
                    for(int i=0;i<ids.length();i++)distinct.add(ids.getString(i));gameCount+=distinct.size();
                }
                if(REGION.matcher(key).matches())regionCount++;
                if(userValue(key)&&existing.containsKey(key)&&!java.util.Objects.equals(value,existing.get(key)))conflictGroups.add(group(key));
            }
            devices=deviceCount;aliases=aliasCount;games=gameCount;regions=regionCount;
            conflicts=java.util.Collections.unmodifiableList(new java.util.ArrayList<>(conflictGroups));
        }
        public String conflictLabel(String group){
            if(group.startsWith("games:")){
                String platform=group.substring(6);
                return GameRules.typeName(platform)+" · 游戏记录与区服\n本机 "+gameIds(platform,false).size()+" 款，备份 "+gameIds(platform,true).size()+" 款最近游戏";
            }
            String suffix=group.substring(7),local=deviceLabel(baseline,suffix),backup=deviceLabel(entries,suffix);
            int separator=suffix.indexOf(':');String mac=separator<0?suffix:suffix.substring(separator+1);
            return (local.equals(backup)?backup:"本机："+local+"\n备份："+backup)+"\nMAC · "+mac;
        }
        public java.util.List<String> gameIds(String platform,boolean backup){
            Object value=(backup?entries:baseline).get("games_"+platform);java.util.List<String> result=new java.util.ArrayList<>();
            if(value instanceof String)try{JSONArray ids=new JSONArray((String)value);for(int i=0;i<ids.length();i++)result.add(ids.getString(i));}catch(Exception ignored){}
            return result;
        }
        private static String deviceLabel(Map<String,Object> values,String suffix){
            for(String key:new String[]{"device_alias_"+suffix,"device_name_"+suffix}){
                Object value=values.get(key);if(value instanceof String&&!((String)value).isEmpty())return (String)value;
            }
            return "未命名设备";
        }
        public Map<String,Object> merge(Map<String,?> current,java.util.Set<String> useBackup) throws IOException {
            Map<String,Object> result=new LinkedHashMap<>();
            for(Map.Entry<String,Object> entry:entries.entrySet()){
                String key=entry.getKey();String group=group(key);
                if(conflicts.contains(group)&&!useBackup.contains(group))continue;
                if(userValue(key)&&!java.util.Objects.equals(current.get(key),baseline.get(key)))
                    throw new IOException("本机记录已变化，请重新选择备份文件后恢复");
                Object value=entry.getValue(),local=current.get(key);
                if(key.startsWith("device_seen_")&&local instanceof Number)value=Math.max(((Number)local).longValue(),((Number)value).longValue());
                else if((key.startsWith("device_name_")||key.startsWith("device_type_"))&&local instanceof String&&!((String)local).isEmpty())value=local;
                result.put(key,value);
            }
            return result;
        }
    }
    public static RestorePlan preview(Map<String,Object> entries,Map<String,?> existing) throws Exception {return new RestorePlan(entries,existing);}
    private static boolean userValue(String key){return !key.startsWith("device_seen_")&&!key.startsWith("device_name_")&&!key.startsWith("device_type_");}
    private static String group(String key){
        if(key.startsWith("device_")){
            for(String prefix:new String[]{"device_alias_","device_name_","device_seen_","device_type_","device_"})
                if(key.startsWith(prefix))return "device:"+key.substring(prefix.length());
        }
        if(key.startsWith("games_"))return "games:"+key.substring(6);
        String[] types=GameRules.TYPES.clone();java.util.Arrays.sort(types,(a,b)->Integer.compare(b.length(),a.length()));
        for(String type:types)for(String prefix:new String[]{"hidden_game_","game_region_"})if(key.startsWith(prefix+type+"_"))return "games:"+type;
        throw new IllegalArgumentException("未知备份记录");
    }
    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("无法打开备份文件");
        ByteArrayOutputStream output = new ByteArrayOutputStream();byte[] buffer = new byte[8192];int n;
        while ((n = input.read(buffer)) != -1) {
            if (output.size() + n > MAX_BYTES) throw new IOException("备份文件不能超过 1 MB");
            output.write(buffer, 0, n);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
