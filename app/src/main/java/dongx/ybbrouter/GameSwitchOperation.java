// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import org.json.JSONArray;
import org.json.JSONObject;

/** Stop -> confirm -> save -> verify -> start. No real router is needed to test this sequence. */
public final class GameSwitchOperation {
    public interface Backend {
        JSONObject config() throws Exception;
        JSONObject devices() throws Exception;
        JSONObject save(String platform, JSONObject game, String region) throws Exception;
        void send(String type, JSONObject message) throws Exception;
        void pause(long millis) throws Exception;
        long now();
        int epoch();
    }
    public interface Progress { void update(String text); }
    public static final class Superseded extends Exception {}
    public static final class Result {
        public final JSONObject config, device;
        public final boolean sent;
        Result(JSONObject config, JSONObject device, boolean sent) { this.config=config;this.device=device;this.sent=sent; }
    }
    private GameSwitchOperation() {}
    private static void checkEpoch(AccelerationQueue.Request request, Backend backend) {
        if(backend.epoch()!=request.epoch)throw new IllegalStateException("登录状态已变化，切换已取消");
    }
    private static void check(AccelerationQueue queue, AccelerationQueue.Request request, Backend backend) throws Exception {
        checkEpoch(request,backend);
        if(!queue.isCurrent(request))throw new Superseded();
    }
    private static JSONObject target(AccelerationQueue.Request request, JSONObject snapshot, JSONObject config, boolean stopped) throws Exception {
        if(!request.router.equals(snapshot.optString("router_sn")))throw new IllegalStateException("路由器已变化，切换已取消");
        JSONArray list=snapshot.optJSONArray("device_list");
        if(list==null)throw new IllegalStateException("设备列表不可用，切换已取消");
        JSONObject target=null;
        for(int i=0;i<list.length();i++){
            JSONObject device=list.optJSONObject(i);if(device==null)continue;
            boolean same=request.mac.equalsIgnoreCase(device.optString("mac"));
            if(same)target=device;
            else if(device.optBoolean("acc_status"))throw new IllegalStateException("其他设备正在加速，请刷新后重新确认切换");
        }
        if(target==null||!request.ip.equals(target.optString("ip")))throw new IllegalStateException("目标设备已离线或地址变化，请刷新后重试");
        if(!request.platform.equals(GameRules.effectiveType(target,config))||!GameRules.supported(request.platform,snapshot.optString("version")))
            throw new IllegalStateException("设备类型或插件支持已变化，切换已取消");
        if(stopped&&target.optBoolean("acc_status"))throw new IllegalStateException("设备重新开始加速，请刷新后重新确认切换");
        return target;
    }
    public static Result execute(AccelerationQueue queue, AccelerationQueue.Request request, Backend backend, Progress progress) throws Exception {
        check(queue,request,backend);progress.update("正在核对切换设备");
        JSONObject config=backend.config(),snapshot=backend.devices();check(queue,request,backend);
        JSONObject device=target(request,snapshot,config,false);
        JSONObject current=config.optJSONObject(request.platform);
        if(device.optBoolean("acc_status")&&GameRules.sameSelection(current,request.game,request.region))return new Result(config,device,false);
        if(device.optBoolean("acc_status")){
            if(!request.mayStop)throw new IllegalStateException("设备刚开始加速，请重新确认后切换");
            progress.update("正在停止当前游戏");check(queue,request,backend);
            backend.send("stop_speed",new JSONObject().put("ip",device.getString("ip")));
            long until=backend.now()+20000;boolean confirmed=false;
            while(backend.now()<until){
                checkEpoch(request,backend);backend.pause(700);checkEpoch(request,backend);
                snapshot=backend.devices();checkEpoch(request,backend);
                if(backend.now()>=until)break;
                device=target(request,snapshot,config,false);
                if(!device.optBoolean("acc_status")){confirmed=true;break;}
            }
            if(!confirmed)throw new IllegalStateException("停止加速确认超时，未启动后续游戏");
        }
        check(queue,request,backend);config=backend.config();snapshot=backend.devices();check(queue,request,backend);
        target(request,snapshot,config,true);
        progress.update("正在核对游戏配置");check(queue,request,backend);
        if(!GameRules.sameSelection(config.optJSONObject(request.platform),request.game,request.region)){
            progress.update("正在保存下一款游戏");check(queue,request,backend);
            backend.save(request.platform,request.game,request.region);
        }
        check(queue,request,backend);
        config=backend.config();snapshot=backend.devices();check(queue,request,backend);
        device=target(request,snapshot,config,true);
        JSONObject selection=config.optJSONObject(request.platform);
        if(selection==null||!request.game.optString("id").equals(selection.optString("game"))
                ||!request.region.equalsIgnoreCase(selection.optString("region")))
            throw new IllegalStateException("游戏配置未保存或已被更新，未启动加速");
        check(queue,request,backend);
        if(!queue.claimStart(request))throw new Superseded();
        progress.update("正在发送启动指令");
        backend.send("start_speed",GameRules.startMessage(device,selection));
        return new Result(config,device,true);
    }
}
