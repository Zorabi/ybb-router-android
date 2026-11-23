package dongx.ybbrouter;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AccelerationQueueTest {
    static AccelerationQueue.Request request(String game) throws Exception {
        return new AccelerationQueue.Request("router",new JSONObject().put("mac","aa").put("ip","192.168.1.2"),"ps5",
                new JSONObject().put("id",game).put("name",game),"JP","PS5",0,true);
    }
    @Test public void latestWaitingSelectionReplacesPreviousOne() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();queue.offer(request("B"));AccelerationQueue.Request c=request("C");queue.offer(c);
        assertSame(c,queue.begin());assertNull(queue.begin());
    }
    @Test public void replacingRunningTaskKeepsNewRequestAfterOldFinishes() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request b=request("B"),c=request("C");
        queue.offer(b);queue.begin();queue.offer(c);assertFalse(queue.isCurrent(b));assertFalse(queue.claimStart(b));
        queue.finish(b);assertSame(c,queue.begin());assertTrue(queue.isCurrent(c));
    }
    @Test public void cancelBeforeDispatchPreventsStart() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request b=request("B");queue.offer(b);queue.begin();
        assertTrue(queue.cancel());assertFalse(queue.claimStart(b));queue.finish(b);assertFalse(queue.hasTask());
    }
    @Test public void alreadySentStartCannotBePresentedAsCancelled() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request b=request("B");queue.offer(b);queue.begin();
        assertTrue(queue.claimStart(b));assertFalse(queue.canCancel());assertFalse(queue.cancel());queue.finish(b);assertFalse(queue.hasTask());
    }
    @Test public void cancellingNextTaskDoesNotClaimToRetractPreviousStart() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request b=request("B");queue.offer(b);queue.begin();queue.claimStart(b);
        queue.offer(request("C"));assertTrue(queue.cancel());queue.finish(b);assertNull(queue.begin());
    }
    @Test public void abortClearsNewerRequestsAfterFailure() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request b=request("B");queue.offer(b);queue.begin();queue.offer(request("C"));
        queue.abort();queue.finish(b);assertFalse(queue.hasTask());assertNull(queue.begin());
    }
    @Test public void gameSnapshotDoesNotChangeWhenCallerEditsSelection() throws Exception {
        JSONObject game=new JSONObject().put("id","B").put("name","B");
        AccelerationQueue.Request request=new AccelerationQueue.Request("router",new JSONObject().put("mac","aa").put("ip","192.168.1.2"),"ps5",game,"JP","PS5",0,true);
        game.put("id","C");assertEquals("B",request.game.getString("id"));
    }
    @Test(expected=IllegalArgumentException.class) public void cannotQueueWithoutStableDeviceIdentity() throws Exception {
        new AccelerationQueue.Request("router",new JSONObject().put("ip","192.168.1.2"),"ps5",new JSONObject().put("id","B"),"JP","PS5",0,true);
    }
    @Test public void deferringBusyWorkerKeepsDesiredRequest() throws Exception {
        AccelerationQueue queue=new AccelerationQueue();AccelerationQueue.Request request=request("B");queue.offer(request);queue.begin();
        queue.defer(request);assertSame(request,queue.begin());
    }
}
