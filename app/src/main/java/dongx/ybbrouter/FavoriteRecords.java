// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Records missing from the current router snapshot; absence does not prove a device is offline. */
public final class FavoriteRecords {
    public static final class Missing {
        public final String key,mac,name,type;
        public final long lastSeen;
        Missing(String key,String mac,String name,String type,long lastSeen){this.key=key;this.mac=mac;this.name=name;this.type=type;this.lastSeen=lastSeen;}
    }
    private FavoriteRecords(){}
    public static List<Missing> missing(Map<String,?> preferences,String router,List<JSONObject> current){
        ArrayList<Missing> result=new ArrayList<>();if(router.isEmpty())return result;
        Set<String> present=new HashSet<>();for(JSONObject device:current)present.add(device.optString("mac").toLowerCase(java.util.Locale.ROOT));
        String prefix="device_"+router+":";
        for(Map.Entry<String,?> entry:preferences.entrySet()){
            if(!entry.getKey().startsWith(prefix)||!Boolean.TRUE.equals(entry.getValue()))continue;
            String mac=entry.getKey().substring(prefix.length());if(present.contains(mac.toLowerCase(java.util.Locale.ROOT)))continue;
            String key=router+":"+mac;
            String name=text(preferences,"device_alias_"+key,text(preferences,"device_name_"+key,"未命名设备"));
            Object seen=preferences.get("device_seen_"+key);
            result.add(new Missing(key,mac,name,text(preferences,"device_type_"+key,""),seen instanceof Number?((Number)seen).longValue():0));
        }
        java.util.Collections.sort(result,(a,b)->a.name.compareToIgnoreCase(b.name));return result;
    }
    private static String text(Map<String,?> values,String key,String fallback){Object value=values.get(key);return value instanceof String&&!((String)value).isEmpty()?(String)value:fallback;}
}
