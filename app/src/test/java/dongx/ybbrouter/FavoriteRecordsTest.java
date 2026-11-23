package dongx.ybbrouter;
import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class FavoriteRecordsTest {
    @Test public void onlyMissingFavoritesForCurrentRouterAreListed() throws Exception {
        Map<String,Object> prefs=new HashMap<>();prefs.put("device_router:aa:bb",true);prefs.put("device_router:cc:dd",true);
        prefs.put("device_router:ee:ff",false);prefs.put("device_other:xx",true);prefs.put("device_seen_router:aa:bb",100L);
        List<JSONObject> current=Collections.singletonList(new JSONObject().put("mac","AA:BB"));
        List<FavoriteRecords.Missing> missing=FavoriteRecords.missing(prefs,"router",current);
        assertEquals(1,missing.size());assertEquals("cc:dd",missing.get(0).mac);
    }
    @Test public void absentDeviceRetainsAliasTypeAndLastSeen() {
        Map<String,Object> prefs=new HashMap<>();prefs.put("device_router:aa",true);prefs.put("device_alias_router:aa","客厅 PS5");
        prefs.put("device_name_router:aa","PlayStation");prefs.put("device_type_router:aa","ps5");prefs.put("device_seen_router:aa",100L);
        FavoriteRecords.Missing record=FavoriteRecords.missing(prefs,"router",Collections.emptyList()).get(0);
        assertEquals("客厅 PS5",record.name);assertEquals("ps5",record.type);assertEquals(100L,record.lastSeen);
    }
    @Test public void oldRecordDoesNotInventNameOrTimestamp() {
        Map<String,Object> prefs=new HashMap<>();prefs.put("device_router:aa",true);
        FavoriteRecords.Missing record=FavoriteRecords.missing(prefs,"router",Collections.emptyList()).get(0);
        assertEquals("未命名设备",record.name);assertEquals(0,record.lastSeen);
        assertTrue(FavoriteRecords.missing(prefs,"",Collections.emptyList()).isEmpty());
    }
}
