// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import org.json.JSONObject;

/** One desired switch per app process. A newer selection replaces work not yet dispatched. */
public final class AccelerationQueue {
    public static final class Request {
        public final String router, mac, ip, platform, region, deviceName;
        public final JSONObject game;
        public final int epoch;
        public final boolean mayStop;
        public Request(String router, JSONObject device, String platform, JSONObject game, String region,
                       String deviceName, int epoch, boolean mayStop) throws Exception {
            this.router=router;this.mac=device.optString("mac");this.ip=device.optString("ip");
            this.platform=platform;this.game=new JSONObject(game.toString());this.region=region;
            this.deviceName=deviceName;this.epoch=epoch;this.mayStop=mayStop;
            if(router.isEmpty()||mac.isEmpty()||ip.isEmpty()||region.isEmpty()||game.optString("id").isEmpty()
                    ||!java.util.Arrays.asList(GameRules.TYPES).contains(platform))
                throw new IllegalArgumentException("设备、游戏或区服信息不完整，请刷新后重试");
        }
    }
    private Request desired, running;
    private boolean dispatched;
    private long revision;
    public synchronized void offer(Request request) { desired=request;revision++; }
    public synchronized Request peek() { return desired!=null?desired:running; }
    public synchronized boolean hasDesired() { return desired!=null; }
    public synchronized boolean hasTask() { return desired!=null||running!=null; }
    public synchronized boolean isRunning() { return running!=null; }
    public synchronized long revision() { return revision; }
    public synchronized Request begin() {
        if(running!=null||desired==null)return null;
        running=desired;dispatched=false;revision++;return running;
    }
    public synchronized boolean isCurrent(Request request) { return desired==request&&running==request; }
    public synchronized boolean claimStart(Request request) {
        if(!isCurrent(request))return false;
        dispatched=true;revision++;return true;
    }
    public synchronized boolean canCancel() { return desired!=null&&!(desired==running&&dispatched); }
    public synchronized boolean cancel() {
        if(!canCancel())return false;
        desired=null;revision++;return true;
    }
    public synchronized void defer(Request request) {
        if(running==request&&!dispatched){running=null;revision++;}
    }
    public synchronized void finish(Request request) {
        if(running!=request)return;
        if(desired==request)desired=null;
        running=null;dispatched=false;revision++;
    }
    public synchronized void abort() { desired=null;revision++; }
}
