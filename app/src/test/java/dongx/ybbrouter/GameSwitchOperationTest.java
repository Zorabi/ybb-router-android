package dongx.ybbrouter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class GameSwitchOperationTest {
    private static class Fake implements GameSwitchOperation.Backend {
        JSONObject config=new JSONObject();
        final List<String> events=new ArrayList<>();
        boolean active=true,stopping,confirmStop=true,persist=true,otherActive,offlineAfterStop;
        String router="router",ip="192.168.1.2";
        int epoch,polls;long clock;
        Runnable afterPause,afterSave;
        Fake() throws Exception {config.put("ps5",new JSONObject().put("game","A").put("region","JP"));}
        public JSONObject config() throws Exception {return new JSONObject(config.toString());}
        public JSONObject devices() throws Exception {
            if(stopping&&confirmStop&&++polls>=2)active=false;
            JSONObject device=new JSONObject().put("mac","aa").put("ip",ip).put("type","ps5").put("acc_status",active);
            JSONArray list=new JSONArray();if(!(offlineAfterStop&&stopping))list.put(device);
            if(otherActive)list.put(new JSONObject().put("mac","bb").put("ip","192.168.1.3").put("type","ps5").put("acc_status",true));
            return new JSONObject().put("router_sn",router).put("version","20221103").put("device_list",list);
        }
        public JSONObject save(String type,JSONObject game,String region) throws Exception {
            assertFalse("Must confirm stop before saving",active);
            events.add("save:"+game.getString("id"));if(persist)config=GameRules.withGame(config,type,game,region);
            if(afterSave!=null){Runnable callback=afterSave;afterSave=null;callback.run();}return config();
        }
        public void send(String type,JSONObject message) {
            if(type.equals("stop_speed")){events.add("stop");stopping=true;}
            else {assertFalse("Must not start while previous game is active",active);events.add("start:"+message.optString("game_id"));}
        }
        public void pause(long millis){clock+=millis;if(afterPause!=null){Runnable callback=afterPause;afterPause=null;callback.run();}}
        public long now(){return clock;}
        public int epoch(){return epoch;}
    }
    private AccelerationQueue queue(AccelerationQueue.Request request){AccelerationQueue queue=new AccelerationQueue();queue.offer(request);queue.begin();return queue;}
    @Test public void confirmsStopAndSavedConfigurationBeforeStartingNextGame() throws Exception {
        Fake backend=new Fake();AccelerationQueue.Request request=AccelerationQueueTest.request("B");AccelerationQueue queue=queue(request);
        GameSwitchOperation.Result result=GameSwitchOperation.execute(queue,request,backend,stage->{});
        assertTrue(result.sent);assertEquals(Arrays.asList("stop","save:B","start:B"),backend.events);
    }
    @Test public void sameGameAndRegionKeepsExistingAcceleration() throws Exception {
        Fake backend=new Fake();AccelerationQueue.Request request=AccelerationQueueTest.request("A");
        GameSwitchOperation.Result result=GameSwitchOperation.execute(queue(request),request,backend,stage->{});
        assertFalse(result.sent);assertTrue(backend.events.isEmpty());assertTrue(backend.active);
    }
    @Test public void stopTimeoutNeverSavesOrStarts() throws Exception {
        Fake backend=new Fake();backend.confirmStop=false;AccelerationQueue.Request request=AccelerationQueueTest.request("B");
        try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("超时"));}
        assertEquals(Arrays.asList("stop"),backend.events);
    }
    @Test public void deviceDisappearingDoesNotCountAsStopConfirmation() throws Exception {
        Fake backend=new Fake();backend.offlineAfterStop=true;AccelerationQueue.Request request=AccelerationQueueTest.request("B");
        try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("离线"));}
        assertEquals(Arrays.asList("stop"),backend.events);
    }
    @Test public void serverIgnoringSaveNeverStartsWrongGame() throws Exception {
        Fake backend=new Fake();backend.persist=false;AccelerationQueue.Request request=AccelerationQueueTest.request("B");
        try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("未保存"));}
        assertEquals(Arrays.asList("stop","save:B"),backend.events);
    }
    @Test public void routerOrAddressChangesNeverSendCommands() throws Exception {
        for(boolean routerChanged:new boolean[]{true,false}){
            Fake backend=new Fake();if(routerChanged)backend.router="other";else backend.ip="192.168.1.9";
            AccelerationQueue.Request request=AccelerationQueueTest.request("B");
            try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){}
            assertTrue(backend.events.isEmpty());
        }
    }
    @Test public void doesNotStopAnotherDeviceWithoutConfirmation() throws Exception {
        Fake backend=new Fake();backend.otherActive=true;AccelerationQueue.Request request=AccelerationQueueTest.request("B");
        try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("其他设备"));}
        assertTrue(backend.events.isEmpty());
    }
    @Test public void latestChoiceWhileStoppingSkipsIntermediateGame() throws Exception {
        Fake backend=new Fake();AccelerationQueue.Request b=AccelerationQueueTest.request("B"),c=AccelerationQueueTest.request("C");AccelerationQueue queue=queue(b);
        backend.afterPause=()->queue.offer(c);
        try{GameSwitchOperation.execute(queue,b,backend,stage->{});fail();}catch(GameSwitchOperation.Superseded expected){}
        queue.finish(b);assertSame(c,queue.begin());GameSwitchOperation.execute(queue,c,backend,stage->{});
        assertEquals(Arrays.asList("stop","save:C","start:C"),backend.events);
    }
    @Test public void latestChoiceWhileSavingPreventsIntermediateStart() throws Exception {
        Fake backend=new Fake();AccelerationQueue.Request b=AccelerationQueueTest.request("B"),c=AccelerationQueueTest.request("C");AccelerationQueue queue=queue(b);
        backend.afterSave=()->queue.offer(c);
        try{GameSwitchOperation.execute(queue,b,backend,stage->{});fail();}catch(GameSwitchOperation.Superseded expected){}
        assertEquals(Arrays.asList("stop","save:B"),backend.events);
    }
    @Test public void cancelOrSessionChangeWhileStoppingNeverStarts() throws Exception {
        for(boolean cancel:new boolean[]{true,false}){
            Fake backend=new Fake();AccelerationQueue.Request request=AccelerationQueueTest.request("B");AccelerationQueue queue=queue(request);
            backend.afterPause=()->{if(cancel)queue.cancel();else backend.epoch++;};
            try{GameSwitchOperation.execute(queue,request,backend,stage->{});fail();}catch(GameSwitchOperation.Superseded expected){assertTrue(cancel);}catch(IllegalStateException expected){assertFalse(cancel);}
            assertEquals(Arrays.asList("stop"),backend.events);
        }
    }
    @Test public void deviceThatJustStartedRequiresNewConsent() throws Exception {
        Fake backend=new Fake();AccelerationQueue.Request request=new AccelerationQueue.Request("router",new JSONObject().put("mac","aa").put("ip","192.168.1.2"),"ps5",new JSONObject().put("id","B").put("name","B"),"JP","PS5",0,false);
        try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("重新确认"));}
        assertTrue(backend.events.isEmpty());
    }
    @Test public void identityOrSessionChangeAfterSaveNeverStarts() throws Exception {
        for(boolean address:new boolean[]{true,false}){
            Fake backend=new Fake();AccelerationQueue.Request request=AccelerationQueueTest.request("B");
            backend.afterSave=()->{if(address)backend.ip="192.168.1.9";else backend.epoch++;};
            try{GameSwitchOperation.execute(queue(request),request,backend,stage->{});fail();}catch(IllegalStateException expected){}
            assertEquals(Arrays.asList("stop","save:B"),backend.events);
        }
    }
    @Test public void configuredIdleGameStartsWithoutRewritingConfiguration() throws Exception {
        Fake backend=new Fake();backend.active=false;AccelerationQueue.Request request=AccelerationQueueTest.request("A");
        GameSwitchOperation.Result result=GameSwitchOperation.execute(queue(request),request,backend,stage->{});
        assertTrue(result.sent);assertEquals(Arrays.asList("start:A"),backend.events);
    }
}
