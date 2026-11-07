package dongx.ybbrouter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameRulesTest {
    @Test public void gameChangesKeepOtherPlatformsAndUnknownSettings() throws Exception {
        JSONObject original=new JSONObject("{android:{game:'C1',name:'旧游戏',exeName:'old.pkg'},ps5:{game:'P1',region:'jp'},device_type:{mac:'ps5'},future_flag:true}".replace('\'', '"'));
        JSONObject game=new JSONObject().put("id","C2").put("name","新游戏").put("exeName","new.pkg").put("routeOption","CN");
        JSONObject saved=GameRules.withGame(original,"android",game,"HK");
        assertEquals("P1",saved.getJSONObject("ps5").getString("game"));
        assertTrue(saved.getBoolean("future_flag"));
        assertEquals("ps5",saved.getJSONObject("device_type").getString("mac"));
        assertEquals("hk",saved.getJSONObject("android").getString("region"));
        assertEquals("new.pkg",saved.getJSONObject("android").getString("exeName"));
        assertEquals("C1",original.getJSONObject("android").getString("game"));
    }
    @Test public void obsoleteRouteIsRemovedWhenNewGameHasNone() throws Exception {
        JSONObject original=new JSONObject().put("ps5",new JSONObject().put("game","P1").put("routeOption","old").put("pic","old.jpg").put("regionName","日本"));
        JSONObject saved=GameRules.withGame(original,"ps5",new JSONObject().put("id","P2").put("name","新游戏"),"AUTO");
        assertFalse(saved.getJSONObject("ps5").has("routeOption"));assertFalse(saved.getJSONObject("ps5").has("pic"));assertFalse(saved.getJSONObject("ps5").has("regionName"));
    }
    @Test public void automaticDeviceTypeRemovesOnlyThatOverride() throws Exception {
        JSONObject original=new JSONObject().put("device_type",new JSONObject().put("a","ps5").put("b","android")).put("switch",new JSONObject().put("game","10000"));
        JSONObject saved=GameRules.withDeviceType(original,"a","");
        assertFalse(saved.getJSONObject("device_type").has("a"));assertEquals("android",saved.getJSONObject("device_type").getString("b"));assertEquals("10000",saved.getJSONObject("switch").getString("game"));
        assertTrue(original.getJSONObject("device_type").has("a"));
    }
    @Test public void searchFindsChineseEnglishPinyinAndTags() throws Exception {
        JSONArray games=new JSONArray().put(new JSONObject().put("id","1").put("name","战地").put("ename","Battlefield").put("pname","ZHANDI").put("tag","射击"));
        for(String q:new String[]{"战地","battle","zhandi","射击","  battlefield "})assertEquals(1,GameRules.games(games,q).size());
        assertEquals(0,GameRules.games(games,"赛车").size());
    }
    @Test public void mapsRouterPlatformsToOfficialCatalogTypes() {
        assertEquals("win,win-web",GameRules.platform("windows"));assertEquals("router-android",GameRules.platform("android"));assertEquals("ps",GameRules.platform("ps5"));assertEquals("xbox",GameRules.platform("xbox_s"));
    }
    @Test public void pluginVersionControlsDeviceSupport() {
        assertTrue(GameRules.supported("android","20221103-c99262d"));assertFalse(GameRules.supported("android","20220428-abc"));assertFalse(GameRules.supported("oculus","20220325"));assertTrue(GameRules.supported("ps5",""));assertFalse(GameRules.supported("unknown","20221103"));
    }
    @Test public void overrideDoesNotMutateDetectedDeviceType() throws Exception {
        JSONObject d=new JSONObject().put("mac","aa").put("type","windows");JSONObject c=new JSONObject().put("device_type",new JSONObject().put("aa","ps5"));
        assertEquals("ps5",GameRules.effectiveType(d,c));assertEquals("windows",d.getString("type"));
    }
    @Test public void startMessageTargetsOnlySelectedDeviceAndIncludesRouteAndPackage() throws Exception {
        JSONObject d=new JSONObject().put("ip","192.168.31.22");JSONObject choice=new JSONObject().put("game","C0023").put("region","JP").put("routeOption","CN").put("exeName","com.example.game");
        JSONObject msg=GameRules.startMessage(d,choice);
        assertEquals("192.168.31.22",msg.getString("ip"));assertEquals("C0023",msg.getString("game_id"));assertEquals("jp",msg.getString("region"));assertEquals("CN",msg.getString("routeOption"));assertEquals("com.example.game",msg.getString("package_name"));
    }
    @Test(expected=org.json.JSONException.class) public void missingGameCannotSendStartCommand() throws Exception {
        GameRules.startMessage(new JSONObject().put("ip","192.168.31.22"),new JSONObject());
    }
    @Test public void deviceOrderIsStableWhenServerOrderChanges() throws Exception {
        JSONObject c=new JSONObject().put("ps5",new JSONObject().put("game","P1"));
        JSONObject a=new JSONObject().put("type","ps5").put("ip","192.168.31.2").put("mac","a");
        JSONObject b=new JSONObject().put("type","ps5").put("ip","192.168.31.3").put("mac","b");
        JSONObject other=new JSONObject().put("type","windows").put("ip","192.168.31.4").put("mac","c");
        java.util.List<JSONObject> first=new java.util.ArrayList<>(java.util.Arrays.asList(a,other,b));
        java.util.List<JSONObject> second=new java.util.ArrayList<>(java.util.Arrays.asList(other,b,a));
        java.util.Collections.sort(first,GameRules.deviceOrder(c));java.util.Collections.sort(second,GameRules.deviceOrder(c));
        assertEquals(first.toString(),second.toString());assertSame(a,first.get(0));
        other.put("acc_status",true);java.util.Collections.sort(first,GameRules.deviceOrder(c));assertSame(other,first.get(0));
    }
}
