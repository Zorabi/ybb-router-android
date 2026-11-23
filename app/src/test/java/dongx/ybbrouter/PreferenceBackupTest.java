package dongx.ybbrouter;

import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class PreferenceBackupTest {
    @Test public void roundTripKeepsPreferencesButNeverCredentials() throws Exception {
        Map<String,Object> preferences=new HashMap<>();
        preferences.put("device_router:aa:bb",true);
        preferences.put("device_alias_router:aa:bb","客厅 PS5");
        preferences.put("device_seen_router:aa:bb",123L);
        preferences.put("games_ps5","[\"P1\",\"P2\"]");
        preferences.put("game_region_ps5_P1","JP");
        preferences.put("hidden_game_ps5_P2",true);
        preferences.put("token","secret-token");preferences.put("session","private-session");
        String backup=PreferenceBackup.export(preferences).toString();
        Map<String,Object> restored=PreferenceBackup.parse(backup);
        assertEquals(6,restored.size());assertEquals("客厅 PS5",restored.get("device_alias_router:aa:bb"));
        assertEquals(123L,restored.get("device_seen_router:aa:bb"));assertEquals("JP",restored.get("game_region_ps5_P1"));
        assertFalse(backup.contains("secret-token"));assertFalse(backup.contains("private-session"));
    }
    private String file(JSONObject entries) throws Exception {
        return new JSONObject().put("format","dongx.ybbrouter.preferences").put("version",1).put("entries",entries).toString();
    }
    @Test(expected=IOException.class) public void importingCredentialKeysRejectsEntireFile() throws Exception {
        PreferenceBackup.parse(file(new JSONObject().put("device_router:aa",true).put("token","secret")));
    }
    @Test(expected=IOException.class) public void rejectsWrongPreferenceType() throws Exception {
        PreferenceBackup.parse(file(new JSONObject().put("device_router:aa","true")));
    }
    @Test(expected=IOException.class) public void rejectsOversizedRecentList() throws Exception {
        PreferenceBackup.parse(file(new JSONObject().put("games_ps5","[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\",\"7\"]")));
    }
    @Test(expected=IOException.class) public void rejectsUnknownVersion() throws Exception {
        JSONObject backup=PreferenceBackup.export(new HashMap<>());backup.put("version",2);PreferenceBackup.parse(backup.toString());
    }
    @Test(expected=IOException.class) public void rejectsUnsupportedPlatform() throws Exception {
        PreferenceBackup.parse(file(new JSONObject().put("games_other","[]")));
    }
    @Test(expected=IOException.class) public void rejectsOversizedFileBeforeImport() throws Exception {
        PreferenceBackup.read(new ByteArrayInputStream(new byte[1024*1024+1]));
    }
    @Test public void timestampsAreNormalizedToLongAndEmptyBackupIsValid() throws Exception {
        assertEquals(10L,PreferenceBackup.parse(file(new JSONObject().put("device_seen_router:aa",10))).get("device_seen_router:aa"));
        assertTrue(PreferenceBackup.parse(file(new JSONObject())).isEmpty());
    }
    @Test public void restorePreviewCountsUserRecordsInsteadOfRawKeys() throws Exception {
        Map<String,Object> values=new HashMap<>();values.put("device_router:aa",true);values.put("device_alias_router:aa","PS5");
        values.put("device_seen_router:aa",100L);values.put("games_ps5","[\"P1\",\"P2\"]");values.put("game_region_ps5_P1","JP");
        PreferenceBackup.RestorePlan plan=PreferenceBackup.preview(values,new HashMap<>());
        assertEquals(1,plan.devices);assertEquals(1,plan.aliases);assertEquals(2,plan.games);assertEquals(1,plan.regions);assertTrue(plan.conflicts.isEmpty());
    }
    @Test public void conflictsDefaultToKeepingEntireLocalDeviceGroup() throws Exception {
        Map<String,Object> local=new HashMap<>();local.put("device_router:aa",false);local.put("device_alias_router:aa","本机名称");
        Map<String,Object> backup=new HashMap<>();backup.put("device_router:aa",true);backup.put("device_alias_router:aa","备份名称");backup.put("device_name_router:aa","原名");backup.put("games_ps5","[\"P1\"]");
        PreferenceBackup.RestorePlan plan=PreferenceBackup.preview(backup,local);
        assertEquals(1,plan.conflicts.size());Map<String,Object> keep=plan.merge(local,java.util.Collections.emptySet());
        assertFalse(keep.containsKey("device_router:aa"));assertFalse(keep.containsKey("device_name_router:aa"));assertEquals("[\"P1\"]",keep.get("games_ps5"));
        Map<String,Object> replace=plan.merge(local,java.util.Collections.singleton(plan.conflicts.get(0)));
        assertEquals(true,replace.get("device_router:aa"));assertEquals("备份名称",replace.get("device_alias_router:aa"));
    }
    @Test public void gameConflictsAreGroupedByCorrectPlatformWithUnderscore() throws Exception {
        Map<String,Object> local=new HashMap<>();local.put("game_region_xbox_x_X1","JP");
        Map<String,Object> backup=new HashMap<>();backup.put("game_region_xbox_x_X1","HK");backup.put("games_xbox_x","[\"X1\"]");
        PreferenceBackup.RestorePlan plan=PreferenceBackup.preview(backup,local);
        assertEquals(java.util.Collections.singletonList("games:xbox_x"),plan.conflicts);
        assertTrue(plan.merge(local,java.util.Collections.emptySet()).isEmpty());
        assertEquals("HK",plan.merge(local,java.util.Collections.singleton("games:xbox_x")).get("game_region_xbox_x_X1"));
    }
    @Test public void lastSeenNeverRegressesAndMetadataIsNotAConflict() throws Exception {
        Map<String,Object> local=new HashMap<>();local.put("device_seen_router:aa",200L);local.put("device_type_router:aa","ps5");
        Map<String,Object> backup=new HashMap<>();backup.put("device_seen_router:aa",100L);backup.put("device_type_router:aa","windows");
        PreferenceBackup.RestorePlan plan=PreferenceBackup.preview(backup,local);
        assertTrue(plan.conflicts.isEmpty());Map<String,Object> restored=plan.merge(local,java.util.Collections.emptySet());
        assertEquals(200L,restored.get("device_seen_router:aa"));assertEquals("ps5",restored.get("device_type_router:aa"));
    }
    @Test(expected=IOException.class) public void changesAfterPreviewRejectRestoreBeforeWriting() throws Exception {
        Map<String,Object> local=new HashMap<>();Map<String,Object> backup=new HashMap<>();backup.put("games_ps5","[\"P1\"]");
        PreferenceBackup.RestorePlan plan=PreferenceBackup.preview(backup,local);local.put("games_ps5","[\"P2\"]");
        plan.merge(local,java.util.Collections.emptySet());
    }
}
