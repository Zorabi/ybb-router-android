// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 dongx
package dongx.ybbrouter;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class MainActivity extends Activity {
    private RouterApplication app;
    private Repository repo;
    private NativeUi ui;
    private android.content.SharedPreferences shortcuts;
    private LinearLayout root, body, bottom, header;
    private TextView screenStatus, routerConnection, routerHeading, routerSummary, homeBinding;
    private LinearLayout homeCards, homeQueue, homeErrors;
    private EditText homeSearch;
    private Button homeCommon, homeAll;
    private String homeQueueSignature="", homeErrorSignature="";
    private final Map<String,View> cardViews=new HashMap<>();
    private final Map<String,String> cardSignatures=new HashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String page = "home", type = "ps5", query = "", deviceQuery = "";
    private JSONObject config = new JSONObject(), deviceData = new JSONObject();
    private JSONArray routers = new JSONArray();
    private long serverTime;
    private long serverTimeAtElapsed;
    private boolean resumed, deviceLoading, loginRequired, destroyed, freshDevices;
    private String homeSignature = "", errorText = "";
    private JSONObject selectedGame, switchDevice;
    private boolean switchAfterSelection;
    private String switchRouter="";
    private int switchEpoch;
    private String regionBack = "games", gamesBack = "home";
    private boolean favoriteOnly = true;
    private GameAdapter gameAdapter;
    private PopupWindow deviceMenu;
    private int gameRequest;
    private final Set<String> prefetched = new HashSet<>();
    private final Map<String,TextView> deviceStatuses = new HashMap<>();
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if ("home".equals(page) && !loginRequired) refreshDevices(false);
            handler.postDelayed(this, app.pendingIp.isEmpty() ? 5000 : 1500);
        }
    };
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (!resumed) return;
            if ("home".equals(page)) updateElapsedLabels();
            handler.postDelayed(this, 1000);
        }
    };
    private long estimatedServerTime() {
        return serverTimeAtElapsed == 0 ? serverTime : serverTime + Math.max(0, android.os.SystemClock.elapsedRealtime()-serverTimeAtElapsed)/1000;
    }
    private void updateElapsedLabels() {
        long now = estimatedServerTime();
        for (JSONObject device : devices()) {
            String ip = device.optString("ip"); TextView label = deviceStatuses.get(ip);
            if (label == null || ip.equals(app.pendingIp) || ip.equals(app.workingIp)) continue;
            JSONObject info = device.optJSONObject("acc_info");
            if (device.optBoolean("acc_status")) {
                String state=freshDevices?"加速中":"上次加速中";
                if(freshDevices&&info!=null&&info.optLong("start_time")>0)state+=" · "+duration(Math.max(0,now-info.optLong("start_time")));
                label.setText(state);
            } else label.setText(freshDevices?"准备就绪":"上次未加速");
        }
    }
    interface Job<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); ui = new NativeUi(this); app = (RouterApplication)getApplication(); repo = app.repository;
        shortcuts = getSharedPreferences("shortcuts", MODE_PRIVATE);
        config = Repository.object(repo.cached("config")); deviceData = Repository.object(repo.cached("devices"));
        routers = Repository.array(repo.cached("routers"));
        JSONObject old = repo.cached("devices"); serverTime = old == null ? 0 : old.optLong("server_time");
        if (state != null) {
            page = state.getString("page", "home"); type = state.getString("type", "ps5"); query = state.getString("query", "");
            favoriteOnly = state.getBoolean("favoriteOnly", true); gamesBack = state.getString("gamesBack", "home");
            deviceQuery = state.getString("deviceQuery", ""); regionBack = state.getString("regionBack", "games");
            try { if (state.containsKey("selectedGame")) selectedGame = new JSONObject(state.getString("selectedGame")); } catch(Exception ignored) {}
            switchAfterSelection=state.getBoolean("switchAfterSelection");switchRouter=state.getString("switchRouter","");switchEpoch=state.getInt("switchEpoch",-1);
            try{if(state.containsKey("switchDevice"))switchDevice=new JSONObject(state.getString("switchDevice"));}catch(Exception ignored){}
            if ("regions".equals(page) && selectedGame == null) page = "home";
        }
        showPage(); refreshHome(false);
    }
    private <T> void task(Job<T> job, Done<T> done) {
        int epoch = repo.epoch();
        app.network.execute(() -> {
            try { T result = job.run(); handler.post(() -> { if (!destroyed && epoch == repo.epoch()) done.accept(result); }); }
            catch (Exception error) { handler.post(() -> { if (!destroyed && epoch == repo.epoch()) failure(error); }); }
        });
    }
    private void failure(Exception error) {
        if (error instanceof ApiClient.Failure && ((ApiClient.Failure)error).needsLogin()) {
            loginRequired = true; errorText = "登录已过期，请重新登录";
            if(app.switches.hasTask()){app.switches.abort();app.switchError="登录已过期，排队切换已取消";}
        } else if (error instanceof java.io.IOException) errorText = "网络连接失败，已保留本地内容，请重试";
        else if (error instanceof org.json.JSONException) errorText = "服务暂未返回完整数据，请稍后刷新";
        else errorText = error.getMessage() == null ? "操作失败，请重试" : error.getMessage();
        if (screenStatus != null) screenStatus.setText(errorText);
        if ("home".equals(page)) renderHome(true);
        else toast(errorText);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void showPage() {
        if(deviceMenu!=null)deviceMenu.dismiss();
        homeCards=null;homeSearch=null;homeQueue=null;homeErrors=null;routerHeading=null;routerSummary=null;routerConnection=null;homeBinding=null;homeCommon=null;homeAll=null;cardViews.clear();cardSignatures.clear();deviceStatuses.clear();
        homeQueueSignature="";homeErrorSignature="";
        gameRequest++; gameAdapter = null; homeSignature = "";
        root = ui.column(); root.setBackgroundColor(ui.bg);
        header = ui.row(); header.setPadding(ui.dp(20), ui.dp(10), ui.dp(12), ui.dp(10));
        boolean main = "home".equals(page) || "mine".equals(page);
        if (!main) header.addView(ui.iconButton(R.drawable.ic_back,"返回",v -> back()));
        String title = title(); TextView heading = ui.label(title, main ? 26 : 21, ui.text, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1); if (!main) hp.leftMargin = ui.dp(12); header.addView(heading, hp);
        if (main) header.addView(ui.iconButton(R.drawable.ic_refresh, "刷新", v -> { if ("home".equals(page)) refreshHome(); else loadMine(true); }));
        if ("games".equals(page)) header.addView(ui.button("更新", false, v -> refreshCatalog()));
        root.addView(header);
        screenStatus = ui.label("", 12, ui.muted, false); screenStatus.setPadding(ui.dp(22), 0, ui.dp(22), ui.dp(8)); root.addView(screenStatus);
        body = ui.column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (main) {
            bottom = ui.row(); bottom.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); bottom.setBackgroundColor(ui.surface);
            Button home = ui.navigation("游戏加速", R.drawable.ic_home, "home".equals(page), v -> navigate("home"));
            Button mine = ui.navigation("我的", R.drawable.ic_user, "mine".equals(page), v -> navigate("mine"));
            bottom.addView(home, new LinearLayout.LayoutParams(0, ui.dp(62), 1)); bottom.addView(mine, new LinearLayout.LayoutParams(0, ui.dp(62), 1)); root.addView(bottom);
        }
        getWindow().setStatusBarColor(ui.bg); getWindow().setNavigationBarColor(ui.surface);
        getWindow().getDecorView().setSystemUiVisibility(ui.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        setContentView(root);
        switch (page) {
            case "home": renderHome(true); break;
            case "mine": loadMine(false); break;
            case "types": renderTypes(); break;
            case "games": renderGames(); break;
            case "regions": renderRegions(); break;
            case "router": renderRouter(); break;
            case "notices": renderNotices(); break;
            default: navigate("home");
        }
    }
    private String title() {
        switch(page) { case "mine": return "我的"; case "types": return "游戏与线路"; case "games": return "选择游戏";
            case "regions": return "选择区服"; case "router": return "路由器管理"; case "notices": return "服务公告"; default:return "游戏加速"; }
    }
    private void navigate(String target) { if (page.equals(target)) return; page = target; showPage(); }
    private LinearLayout scrollBody() {
        body.removeAllViews(); ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        LinearLayout content = ui.column(); content.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(24)); scroll.addView(content);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, -1)); return content;
    }
    private void refreshHome() { refreshHome(true); }
    private void refreshHome(boolean userRequested) {
        if(userRequested||serverTime==0)screenStatus.setText("正在更新…");
        errorText = "";
        task(() -> repo.fetch("routers", "router/bind?brand=xiaomi"), data -> {
            routers = Repository.array(data); loginRequired = false; if ("home".equals(page)) renderHome(false);
        });
        task(repo::fetchConfig, value -> { config = value; if ("home".equals(page)) renderHome(false); else if ("types".equals(page)) renderTypes(); prefetch(); });
        refreshDevices(false);
    }
    private void refreshDevices(boolean force) {
        if (deviceLoading) return; deviceLoading = true;
        if("home".equals(page)){screenStatus.setText(homeStatusText());updateRouterConnection();}
        int epoch = repo.epoch();
        app.network.execute(() -> {
            try {
                JSONObject data = repo.fetch("devices", "router/device");
                handler.post(() -> {
                    deviceLoading = false; if (destroyed || epoch != repo.epoch()) return;
                    freshDevices = true; deviceData = Repository.object(data); serverTime = data.optLong("server_time"); serverTimeAtElapsed = android.os.SystemClock.elapsedRealtime(); loginRequired = false; errorText = "";
                    updateFavoriteDetails();
                    checkPending();
                    if ("home".equals(page)) renderHome(force);
                    prefetch();processQueuedSwitch();
                });
            } catch (Exception e) { handler.post(() -> { deviceLoading = false; freshDevices = false;
                if(app.switches.hasTask()&&!app.switches.isRunning()&&!app.pendingIp.isEmpty()){
                    app.switches.abort();app.switchError="无法确认前序指令状态，排队切换已取消，请刷新后重试";
                }
                if (!destroyed && epoch == repo.epoch()) failure(e); }); }
        });
    }
    private void prefetch() {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        JSONArray devices = deviceData.optJSONArray("device_list");
        if (devices != null) for (int i=0; i<devices.length(); i++) { JSONObject d=devices.optJSONObject(i); if(d!=null) keys.add(GameRules.effectiveType(d,config)); }
        for(String t:GameRules.TYPES) if(config.has(t)) keys.add(t);
        for (String t:keys) {
            if (!GameRules.supported(t, deviceData.optString("version")) || !prefetched.add(GameRules.platform(t))) continue;
            app.catalogs.execute(() -> { try { repo.games(t, false); } catch(Exception ignored) {} });
        }
        for(String t:GameRules.TYPES) {
            JSONObject c=config.optJSONObject(t); if(c==null || c.optString("game").isEmpty()) continue;
            String id=c.optString("game"); if(!prefetched.add("detail_"+id)) continue;
            app.catalogs.execute(() -> {try {repo.regions(id, false);} catch(Exception ignored) {}});
        }
    }
    private List<JSONObject> devices() {
        ArrayList<JSONObject> out=new ArrayList<>(); JSONArray source=deviceData.optJSONArray("device_list");
        if(source!=null) for(int i=0;i<source.length();i++) {JSONObject d=source.optJSONObject(i);if(d!=null&&!d.optString("host").contains("MiWiFi"))out.add(d);}
        Comparator<JSONObject> fallback = GameRules.deviceOrder(config);
        Collections.sort(out, (a, b) -> {
            int favorite = Boolean.compare(favoriteDevice(b), favoriteDevice(a));
            return favorite != 0 ? favorite : fallback.compare(a, b);
        }); return out;
    }
    private String deviceKey(JSONObject device) {
        String mac = device.optString("mac").toLowerCase(Locale.ROOT);
        return mac.isEmpty() ? "" : deviceData.optString("router_sn") + ":" + mac;
    }
    private boolean favoriteDevice(JSONObject device) {
        String key = deviceKey(device);
        return !key.isEmpty() && shortcuts.getBoolean("device_" + key, false);
    }
    private List<String> recentGames(String platform) {
        ArrayList<String> ids = new ArrayList<>();
        try {
            JSONArray values = new JSONArray(shortcuts.getString("games_" + platform, "[]"));
            for (int i = 0; i < values.length(); i++) {
                String id = values.optString(i);
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        } catch (Exception ignored) {}
        return ids;
    }
    private void rememberGame(String platform, String id) {
        List<String> ids = recentGames(platform);
        ids.remove(id); ids.add(0, id);
        if (ids.size() > 6) ids = new ArrayList<>(ids.subList(0, 6));
        shortcuts.edit().putString("games_" + platform, new JSONArray(ids).toString())
                .remove("hidden_game_" + platform + "_" + id).apply();
    }
    private void removeRecentGame(String platform, JSONObject game) {
        String id=game.optString("id");List<String> ids=recentGames(platform);ids.remove(id);
        shortcuts.edit().putString("games_"+platform,new JSONArray(ids).toString())
                .putBoolean("hidden_game_"+platform+"_"+id,true).apply();
        filterGames();toast("已从最近使用中移除「"+game.optString("name")+"」");
    }
    private void chooseGame(JSONObject game) {
        selectedGame=game;regionBack="games";page="regions";showPage();
    }
    private String routerName() {
        JSONObject r=routers.optJSONObject(0); if(r==null)return "尚未绑定路由器";
        String model=r.optString("model"); return "RM1800".equalsIgnoreCase(model)?"小米 AX1800":r.optString("name",model);
    }
    private void renderHome(boolean force) {
        if (!"home".equals(page)) return;
        if(deviceMenu!=null&&deviceMenu.isShowing()){updateElapsedLabels();return;}
        String signature=GameRules.deviceDisplaySignature(devices(),config)+deviceData.optString("router_sn")+deviceData.optString("version")+config.toString()+routers.toString()+errorText+app.pendingIp+app.workingIp+app.commandBusy.get()+app.workingStage+deviceQuery+favoriteOnly+loginRequired+app.switches.revision()+app.switchError;
        if (!force&&signature.equals(homeSignature)) {
            screenStatus.setText(homeStatusText());
            updateRouterConnection();updateElapsedLabels();
            return;
        } homeSignature=signature;
        screenStatus.setText(homeStatusText());
        if(homeCards!=null&&homeCards.isAttachedToWindow()&&!loginRequired){updateHomeViews(devices());return;}
        homeCards=null;homeSearch=null;cardViews.clear();cardSignatures.clear();deviceStatuses.clear();
        int scrollY=body.getChildCount()>0&&body.getChildAt(0) instanceof ScrollView?body.getChildAt(0).getScrollY():0;
        LinearLayout content=scrollBody();
        ScrollView scroll=(ScrollView)body.getChildAt(0);
        if(scrollY>0)scroll.post(()->scroll.scrollTo(0,scrollY));
        routerConnection=null;
        if(loginRequired) {
            LinearLayout card=ui.card();ui.add(card,ui.label("登录游帮帮账号",21,ui.text,true),0);
            ui.add(card,ui.label("使用已绑定路由器的百度账号继续",14,ui.muted,false),10);
            ui.add(card,ui.button("百度账号登录",true,v->login()),18);ui.add(content,card,0);return;
        }
        List<JSONObject> list=devices(); int count=0;for(JSONObject d:list)if(d.optBoolean("acc_status"))count++;
        LinearLayout router=ui.card(); router.setBackground(ui.shape(ui.hero,22));
        LinearLayout summary=ui.row();
        summary.addView(ui.symbol(R.drawable.ic_router,android.graphics.Color.WHITE,NativeUi.c("#395FCB")),new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        LinearLayout routerLabels=ui.column();routerLabels.setPadding(ui.dp(12),0,0,0);
        routerHeading=ui.label(routerName(),18,android.graphics.Color.WHITE,true);ui.add(routerLabels,routerHeading,0);
        routerConnection=ui.label("",12,NativeUi.c("#DBE6FF"),false);ui.add(routerLabels,routerConnection,6);updateRouterConnection();
        summary.addView(routerLabels,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=ui.iconButton(R.drawable.ic_arrow,"游戏与线路设置",v->navigate("types"));
        settings.setCompoundDrawables(ui.icon(R.drawable.ic_arrow,android.graphics.Color.WHITE,24),null,null,null);summary.addView(settings);
        ui.add(router,summary,0);
        routerSummary=ui.label("",14,android.graphics.Color.WHITE,false);ui.add(router,routerSummary,18);
        ui.add(content,router,0);
        homeQueue=ui.column();ui.add(content,homeQueue,0);
        homeBinding=ui.label("请先在小米 WiFi 的游帮帮插件中完成首次绑定。",14,ui.muted,false);ui.add(content,homeBinding,16);
        LinearLayout section=ui.row();section.addView(ui.label("我的设备",20,ui.text,true),new LinearLayout.LayoutParams(0,-2,1));
        section.addView(ui.button("清理常用记录",false,v->cleanMissingFavorites()));ui.add(content,section,24);
        LinearLayout filters=ui.row();filters.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));filters.setBackground(ui.shape(ui.soft,14));
        int favorites=0;for(JSONObject d:list)if(favoriteDevice(d))favorites++;
        Button all=ui.button("全部设备",false,v->{favoriteOnly=false;renderHome(true);});
        Button common=ui.button("常用设备 · "+favorites,false,v->{favoriteOnly=true;renderHome(true);});
        all.setBackground(ui.states(!favoriteOnly?ui.surface:android.graphics.Color.TRANSPARENT,ui.tint));
        common.setBackground(ui.states(favoriteOnly?ui.surface:android.graphics.Color.TRANSPARENT,ui.tint));
        all.setTextColor(!favoriteOnly?ui.text:ui.muted);common.setTextColor(favoriteOnly?ui.text:ui.muted);
        all.setSelected(!favoriteOnly);common.setSelected(favoriteOnly);
        filters.addView(common,new LinearLayout.LayoutParams(0,-2,1));filters.addView(all,new LinearLayout.LayoutParams(0,-2,1));ui.add(content,filters,14);
        homeAll=all;homeCommon=common;
        EditText search=ui.search("搜索设备名称、别名或 IP");search.setContentDescription("搜索设备名称、别名或 IP");search.setText(deviceQuery);homeSearch=search;ui.add(content,search,12);
        LinearLayout cards=ui.column();ui.add(content,cards,0);homeCards=cards;
        search.addTextChangedListener(watcher(value->{deviceQuery=value;renderDeviceCards(cards,devices());}));
        homeErrors=ui.column();ui.add(content,homeErrors,0);updateHomeViews(list);
    }
    private List<FavoriteRecords.Missing> missingFavorites(){
        return FavoriteRecords.missing(shortcuts.getAll(),deviceData.optString("router_sn"),devices());
    }
    private void updateHomeViews(List<JSONObject> list){
        routerHeading.setText(routerName());updateRouterConnection();
        int active=0,favorites=missingFavorites().size();for(JSONObject device:list){if(device.optBoolean("acc_status"))active++;if(favoriteDevice(device))favorites++;}
        routerSummary.setText(active>0?(freshDevices?"":"上次记录 · ")+active+" 台设备加速":"选择设备，开启你的游戏时间");
        homeBinding.setVisibility(routers.length()==0?View.VISIBLE:View.GONE);
        homeCommon.setText("常用设备 · "+favorites);homeCommon.setSelected(favoriteOnly);homeAll.setSelected(!favoriteOnly);
        homeCommon.setTextColor(favoriteOnly?ui.text:ui.muted);homeAll.setTextColor(!favoriteOnly?ui.text:ui.muted);
        homeCommon.setBackground(ui.states(favoriteOnly?ui.surface:android.graphics.Color.TRANSPARENT,ui.tint));
        homeAll.setBackground(ui.states(!favoriteOnly?ui.surface:android.graphics.Color.TRANSPARENT,ui.tint));
        String queue=app.switches.revision()+app.workingStage+app.pendingIp+app.switchError;
        if(!queue.equals(homeQueueSignature)){homeQueueSignature=queue;homeQueue.removeAllViews();renderSwitchQueue(homeQueue);}
        String error=errorText+app.switchError;
        if(!error.equals(homeErrorSignature)){
            homeErrorSignature=error;homeErrors.removeAllViews();
            if(!errorText.isEmpty()&&app.switchError.isEmpty())ui.add(homeErrors,ui.button("刷新状态后重试",false,v->refreshHome()),16);
        }
        renderDeviceCards(homeCards,list);updateElapsedLabels();
    }
    private String cardKey(JSONObject device){String key=deviceKey(device);return key.isEmpty()?"ip:"+device.optString("ip"):key;}
    private String cardSignature(JSONObject device){
        String platform=GameRules.effectiveType(device,config);
        return GameRules.deviceDisplaySignature(Collections.singletonList(device),config)+deviceName(device)+favoriteDevice(device)
                +String.valueOf(config.optJSONObject(platform))+app.pendingIp+app.pendingStart+app.workingIp+app.workingStage+app.commandBusy.get();
    }
    private String homeStatusText(){
        if(!errorText.isEmpty())return errorText;
        if(deviceLoading)return serverTime>0?"正在同步 · 显示上次设备记录":"正在获取设备列表…";
        return serverTime==0?"暂无设备记录，请刷新":(freshDevices?"已同步 · ":"本地记录 · ")+clock(serverTime);
    }
    private void updateRouterConnection(){
        if(routerConnection!=null)routerConnection.setText(deviceData.optString("router_sn").isEmpty()?"等待路由器连接":deviceLoading?"正在同步设备状态":freshDevices?"设备状态已同步":"本地记录 · 待同步");
    }
    private String deviceName(JSONObject device){
        String alias=shortcuts.getString("device_alias_"+deviceKey(device),"");
        if(!alias.isEmpty())return alias;
        String name=device.optString("host");return name.isEmpty()?GameRules.typeName(GameRules.effectiveType(device,config)):name;
    }
    private void renderDeviceCards(LinearLayout holder,List<JSONObject> list) {
        int visible=0;String needle=deviceQuery.toLowerCase(Locale.ROOT);
        List<View> wanted=new ArrayList<>();Set<String> kept=new HashSet<>(),visibleIps=new HashSet<>();
        for(JSONObject device:list) {
            String name=deviceName(device);String t=GameRules.effectiveType(device,config);
            if(favoriteOnly&&!favoriteDevice(device))continue;
            if(!(name+" "+device.optString("host")+" "+device.optString("ip")).toLowerCase(Locale.ROOT).contains(needle))continue;visible++;
            String key=cardKey(device),signature=cardSignature(device);kept.add(key);visibleIps.add(device.optString("ip"));
            View previous=cardViews.get(key);
            if(previous!=null&&signature.equals(cardSignatures.get(key))){wanted.add(previous);continue;}
            LinearLayout card=ui.card();boolean active=device.optBoolean("acc_status");
            LinearLayout top=ui.row();
            top.addView(ui.symbol(ui.deviceIcon(t),ui.accent,ui.tint),new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
            LinearLayout names=ui.column();names.setPadding(ui.dp(12),0,ui.dp(4),0);
            TextView deviceName=ui.label(name,16,ui.text,true);deviceName.setMaxLines(2);deviceName.setEllipsize(android.text.TextUtils.TruncateAt.END);ui.add(names,deviceName,0);
            ui.add(names,ui.label(GameRules.typeName(t)+(favoriteDevice(device)?" · 常用":"")+" · "+device.optString("ip"),11,ui.muted,false),6);
            top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            top.addView(ui.iconButton(R.drawable.ic_more,name+"，更多设置",v->deviceOptions(device,v)));
            ui.add(card,top,0);
            JSONObject choice=config.optJSONObject(t);String game=choice==null?"选择要玩的游戏":choice.optString("name","选择要玩的游戏");
            LinearLayout gameRow=ui.row();
            gameRow.addView(ui.selection("加速游戏",game,R.drawable.ic_game,v->openDeviceGames(device)),new LinearLayout.LayoutParams(0,-2,1));
            Button region=ui.button(choice==null?"区服":GameRules.regionName(choice.optString("region")),false,v->openDeviceRegion(device));
            ui.trailingIcon(region,R.drawable.ic_arrow,ui.accent,16);region.setTextSize(13);region.setPadding(ui.dp(10),ui.dp(8),0,ui.dp(8));region.setContentDescription("修改区服");
            gameRow.addView(region);ui.add(card,gameRow,16);
            LinearLayout action=ui.row();String state=freshDevices?(active?"加速中":"准备就绪"):(active?"上次加速中":"上次未加速");
            if(app.pendingIp.equals(device.optString("ip")))state="等待路由器确认";
            if(app.workingIp.equals(device.optString("ip")))state=app.workingStage;
            JSONObject info=device.optJSONObject("acc_info");if(freshDevices&&active&&info!=null&&!app.pendingIp.equals(device.optString("ip"))&&!app.workingIp.equals(device.optString("ip"))){long start=info.optLong("start_time");if(start>0)state+=" · "+duration(Math.max(0,estimatedServerTime()-start));}
            TextView status=ui.label(state,13,active?ui.green:ui.muted,false);deviceStatuses.put(device.optString("ip"),status);action.addView(status,new LinearLayout.LayoutParams(0,-2,1));
            String ip=device.optString("ip");
            boolean waiting=ip.equals(app.workingIp)||ip.equals(app.pendingIp);
            Button start=ui.button(waiting?"":active?"停止加速":"开始加速",true,v->{if(active)stop(device);else start(device);});
            start.setTextColor(android.graphics.Color.WHITE);
            android.graphics.drawable.StateListDrawable actionBackground=new android.graphics.drawable.StateListDrawable();
            int normal=ui.primary;
            int pressed=NativeUi.c(ui.dark?"#527CE0":"#204AB9");
            actionBackground.addState(new int[]{-android.R.attr.state_enabled},ui.shape(waiting?normal:NativeUi.c(ui.dark?"#3C5FB0":"#4967B4"),12));
            actionBackground.addState(new int[]{},ui.states(normal,pressed));
            start.setBackground(actionBackground);
            start.setEnabled(!waiting&&!app.commandBusy.get()&&app.pendingIp.isEmpty()&&!deviceData.optString("router_sn").isEmpty());
            FrameLayout control=new FrameLayout(this);
            control.addView(start,new FrameLayout.LayoutParams(-1,-1));
            if(waiting){
                LinearLayout loading=ui.row();loading.setGravity(Gravity.CENTER);
                int color=android.graphics.Color.WHITE;
                ProgressBar spinner=new ProgressBar(this,null,android.R.attr.progressBarStyleSmall);
                spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(color));
                loading.addView(spinner,new LinearLayout.LayoutParams(ui.dp(18),ui.dp(18)));
                TextView label=ui.label(ip.equals(app.pendingIp)?"等待确认":"处理中",14,color,false);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=ui.dp(8);loading.addView(label,lp);
                loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                control.addView(loading,new FrameLayout.LayoutParams(-1,-1));
                start.setContentDescription(ip.equals(app.pendingIp)?"等待路由器确认，请稍候":app.workingStage);
            }
            action.addView(control,new LinearLayout.LayoutParams(ui.dp(136),ui.dp(48)));ui.add(card,action,12);wanted.add(card);cardViews.put(key,card);cardSignatures.put(key,signature);
        }
        if(favoriteOnly)for(FavoriteRecords.Missing missing:missingFavorites()){
            if(!(missing.name+" "+missing.mac).toLowerCase(Locale.ROOT).contains(needle))continue;visible++;
            String key="missing:"+missing.key,signature=missing.name+missing.type+missing.lastSeen+freshDevices;kept.add(key);
            View cached=cardViews.get(key);
            if(cached==null||!signature.equals(cardSignatures.get(key))){cached=missingFavoriteCard(missing);cardViews.put(key,cached);cardSignatures.put(key,signature);}
            wanted.add(cached);
        }
        if(visible==0){
            LinearLayout empty=ui.card();
            ui.add(empty,ui.label(favoriteOnly?"把常用设备留在这里":"没有找到设备",18,ui.text,true),0);
            ui.add(empty,ui.label(favoriteOnly?"在全部设备中点击右上角的更多按钮，即可设为常用。":list.isEmpty()?"点击右上角刷新，获取路由器设备列表。":"试试其他设备名称或 IP。",14,ui.muted,false),10);
            wanted.add(empty);
        }
        cardViews.keySet().retainAll(kept);cardSignatures.keySet().retainAll(kept);deviceStatuses.keySet().retainAll(visibleIps);
        for(int i=holder.getChildCount()-1;i>=0;i--)if(!wanted.contains(holder.getChildAt(i)))holder.removeViewAt(i);
        for(int i=0;i<wanted.size();i++){
            View view=wanted.get(i);if(holder.getChildAt(i)==view)continue;
            if(view.getParent()==holder)holder.removeView(view);
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=ui.dp(12);holder.addView(view,i,params);
        }
    }
    private LinearLayout missingFavoriteCard(FavoriteRecords.Missing missing){
        LinearLayout card=ui.card(),row=ui.row();
        row.addView(ui.symbol(ui.deviceIcon(missing.type),ui.muted,ui.soft),new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
        LinearLayout labels=ui.column();labels.setPadding(ui.dp(12),0,ui.dp(8),0);
        ui.add(labels,ui.label(missing.name,16,ui.text,true),0);
        ui.add(labels,ui.label(freshDevices?"当前未发现 · 常用":"上次列表未发现 · 常用",12,ui.muted,false),6);
        row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        row.addView(ui.button("移除",false,v->{removeFavoriteRecord(missing.key);renderHome(true);toast("已移除常用记录");}));ui.add(card,row,0);
        String seen=missing.lastSeen>0?new SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.CHINA).format(new Date(missing.lastSeen)):"未知";
        ui.add(card,ui.label("最后发现 · "+seen+"\nMAC · "+missing.mac,12,ui.muted,false),14);
        ui.add(card,ui.label("可能暂时离线或已更换 MAC，可刷新状态确认。",12,ui.muted,false),10);return card;
    }
    private void removeFavoriteRecord(String key){
        shortcuts.edit().remove("device_"+key).remove("device_name_"+key).remove("device_seen_"+key).remove("device_type_"+key).remove("device_alias_"+key).apply();
    }
    private void updateFavoriteDetails(){
        android.content.SharedPreferences.Editor editor=shortcuts.edit();long now=System.currentTimeMillis();
        for(JSONObject device:devices()){
            if(!favoriteDevice(device))continue;
            String key=deviceKey(device);String type=GameRules.effectiveType(device,config);String name=device.optString("host",GameRules.typeName(type));
            if(!type.equals(shortcuts.getString("device_type_"+key,"")))editor.putString("device_type_"+key,type);
            if(!name.equals(shortcuts.getString("device_name_"+key,"")))editor.putString("device_name_"+key,name);
            if(now-shortcuts.getLong("device_seen_"+key,0)>=60000)editor.putLong("device_seen_"+key,now);
        }
        editor.apply();
    }
    private void cleanMissingFavorites(){
        if(!freshDevices){toast("请先刷新设备列表再清理");return;}
        String router=deviceData.optString("router_sn");
        if(router.isEmpty()||deviceData.optJSONArray("device_list")==null){toast("请先连接路由器再清理");return;}
        Set<String> present=new HashSet<>();for(JSONObject device:devices())present.add("device_"+deviceKey(device));
        String prefix="device_"+router+":";ArrayList<String> keys=new ArrayList<>();
        for(Map.Entry<String,?> entry:shortcuts.getAll().entrySet())
            if(entry.getKey().startsWith(prefix)&&Boolean.TRUE.equals(entry.getValue())&&!present.contains(entry.getKey()))keys.add(entry.getKey());
        Collections.sort(keys);
        if(keys.isEmpty()){toast("没有未出现在当前列表中的常用设备");return;}
        String[] labels=new String[keys.size()];boolean[] selected=new boolean[keys.size()];
        for(int i=0;i<keys.size();i++){
            String key=keys.get(i).substring("device_".length());
            String name=shortcuts.getString("device_alias_"+key,shortcuts.getString("device_name_"+key,"名称未知"));long seen=shortcuts.getLong("device_seen_"+key,0);
            String lastSeen=seen>0?"最后出现 · "+new SimpleDateFormat("yyyy.MM.dd HH:mm",Locale.CHINA).format(new Date(seen)):"最后出现时间未知（更新前记录）";
            labels[i]=name+"\nMAC · "+keys.get(i).substring(prefix.length())+"\n"+lastSeen;
        }
        LinearLayout heading=ui.column();heading.setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(12));
        ui.add(heading,ui.label("清理未发现的常用设备",19,ui.text,true),0);
        ui.add(heading,ui.label("以下设备未出现在当前列表中，可能暂时离线或已更换 MAC。请确认不再使用后再移除。",13,ui.muted,false),10);
        new AlertDialog.Builder(this).setCustomTitle(heading)
                .setMultiChoiceItems(labels,selected,(dialog,index,checked)->selected[index]=checked)
                .setNegativeButton("取消",null).setPositiveButton("移除所选",(dialog,which)->{
                    android.content.SharedPreferences.Editor editor=shortcuts.edit();int count=0;
                    for(int i=0;i<keys.size();i++)if(selected[i]){
                        String key=keys.get(i).substring("device_".length());
                        editor.remove(keys.get(i)).remove("device_name_"+key).remove("device_seen_"+key).remove("device_alias_"+key).remove("device_type_"+key);count++;
                    }
                    editor.apply();renderHome(true);toast(count==0?"未选择记录":"已清理 "+count+" 条常用记录");
                }).show();
    }
    private void deviceOptions(JSONObject device, View anchor) {
        LinearLayout content=ui.column();content.setPadding(ui.dp(8),ui.dp(8),ui.dp(8),ui.dp(8));
        int width=Math.min(ui.dp(216),getResources().getDisplayMetrics().widthPixels-ui.dp(32));
        PopupWindow menu=new PopupWindow(content,width,ViewGroup.LayoutParams.WRAP_CONTENT,true);
        deviceMenu=menu;
        menu.setOnDismissListener(()->{deviceMenu=null;if(!destroyed&&"home".equals(page))renderHome(true);});
        menu.setBackgroundDrawable(ui.shape(ui.surface,18));menu.setElevation(ui.dp(12));
        menu.setOutsideTouchable(true);menu.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);
        boolean favorite=favoriteDevice(device);
        Button pin=ui.button(favorite?"取消常用设备":"设为常用设备",false,v->{
            menu.dismiss();
            if(deviceKey(device).isEmpty()){toast("此设备缺少标识，暂时无法设为常用");return;}
            String key=deviceKey(device);
            android.content.SharedPreferences.Editor editor=shortcuts.edit().putBoolean("device_"+key,!favorite);
            if(favorite)editor.remove("device_name_"+key).remove("device_seen_"+key).remove("device_type_"+key);
            else {
                editor.putString("device_name_"+key,device.optString("host",GameRules.typeName(GameRules.effectiveType(device,config))));
                editor.putString("device_type_"+key,GameRules.effectiveType(device,config));
                if(freshDevices)editor.putLong("device_seen_"+key,System.currentTimeMillis());
            }
            editor.apply();renderHome(true);
        });
        pin.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);pin.setTextColor(ui.text);
        pin.setCompoundDrawables(ui.icon(R.drawable.ic_star,ui.accent,20),null,null,null);pin.setCompoundDrawablePadding(ui.dp(12));
        ui.add(content,pin,0);
        View divider=new View(this);divider.setBackgroundColor(ui.line);
        LinearLayout.LayoutParams line=new LinearLayout.LayoutParams(-1,ui.dp(1));line.setMargins(ui.dp(12),ui.dp(4),ui.dp(12),ui.dp(4));content.addView(divider,line);
        Button kind=ui.button("修改设备类型",false,v->{menu.dismiss();chooseDeviceType(device);});
        kind.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);kind.setTextColor(ui.text);
        kind.setCompoundDrawables(ui.icon(R.drawable.ic_device,ui.muted,20),null,null,null);kind.setCompoundDrawablePadding(ui.dp(12));
        ui.add(content,kind,0);
        Button rename=ui.button("设备别名",false,v->{menu.dismiss();renameDevice(device);});
        rename.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);rename.setTextColor(ui.text);
        rename.setCompoundDrawables(ui.icon(R.drawable.ic_edit,ui.muted,20),null,null,null);rename.setCompoundDrawablePadding(ui.dp(12));ui.add(content,rename,0);
        menu.showAsDropDown(anchor,anchor.getWidth()-width,ui.dp(6));
    }
    private void renameDevice(JSONObject device){
        if(deviceKey(device).isEmpty()){toast("此设备缺少标识，暂时无法设置别名");return;}
        EditText input=ui.search("例如：客厅 PS5");input.setText(shortcuts.getString("device_alias_"+deviceKey(device),""));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        LinearLayout fields=ui.column();fields.setPadding(ui.dp(24),ui.dp(8),ui.dp(24),0);ui.add(fields,input,0);
        ui.add(fields,ui.label("留空可恢复设备原名，仅在本机显示。",12,ui.muted,false),8);
        new AlertDialog.Builder(this).setTitle("设备别名").setView(fields).setNegativeButton("取消",null)
                .setPositiveButton("保存",(dialog,which)->{
                    String value=input.getText().toString().trim();String key="device_alias_"+deviceKey(device);
                    if(value.isEmpty())shortcuts.edit().remove(key).apply();else shortcuts.edit().putString(key,value).apply();
                    renderHome(true);
                }).show();
    }
    private void backupOptions(){
        new AlertDialog.Builder(this).setTitle("备份与恢复").setItems(new String[]{"导出常用记录","从文件恢复"},(dialog,which)->{
            Intent intent=new Intent(which==0?Intent.ACTION_CREATE_DOCUMENT:Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(which==0?"application/json":"*/*");
            if(which==0)intent.putExtra(Intent.EXTRA_TITLE,"ybb-preferences-"+new SimpleDateFormat("yyyyMMdd",Locale.CHINA).format(new Date())+".json");
            try{startActivityForResult(intent,which==0?101:102);}catch(android.content.ActivityNotFoundException e){toast("系统文件选择器不可用");}
        }).setNegativeButton("关闭",null).show();
    }
    private <T> void fileTask(Job<T> job, Done<T> done){
        int epoch=repo.epoch();
        app.network.execute(()->{
            try{T value=job.run();handler.post(()->{if(!destroyed&&epoch==repo.epoch())done.accept(value);});}
            catch(Exception error){handler.post(()->{if(!destroyed)toast(error.getMessage()==null?"文件操作失败，请重试":error.getMessage());});}
        });
    }
    private void backupResult(int request,Intent data){
        if(data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==101){
            final JSONObject document;
            try{document=PreferenceBackup.export(shortcuts.getAll());}catch(Exception e){toast(e.getMessage());return;}
            fileTask(()->{
                try(java.io.OutputStream out=getContentResolver().openOutputStream(uri,"wt")){
                    if(out==null)throw new java.io.IOException("无法创建备份文件");
                    out.write(document.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }return true;
            },value->toast("常用记录已导出，不含登录信息"));
        }else{
            fileTask(()->{
                Map<String,Object> entries;
                try(java.io.InputStream in=getContentResolver().openInputStream(uri)){entries=PreferenceBackup.parse(PreferenceBackup.read(in));}
                return PreferenceBackup.preview(entries,shortcuts.getAll());
            },this::showRestorePlan);
        }
    }
    private void showRestorePlan(PreferenceBackup.RestorePlan plan){
        String summary="备份包含 "+plan.devices+" 条常用设备记录、"+plan.games+" 款最近游戏，另有 "+plan.aliases+" 个别名、"+plan.regions+" 条区服偏好。";
        if(plan.conflicts.isEmpty()){
            confirm("恢复预览",summary+"\n将合并新记录，不更改账号、路由器绑定或加速配置。","合并恢复",()->applyRestorePlan(plan,Collections.emptySet()));return;
        }
        LinearLayout title=ui.column();title.setPadding(ui.dp(24),ui.dp(20),ui.dp(24),ui.dp(12));
        ui.add(title,ui.label("选择冲突记录",19,ui.text,true),0);ui.add(title,ui.label(summary,13,ui.muted,false),10);
        ui.add(title,ui.label("以下记录与本机不同。勾选采用备份，未勾选的设备或平台保留本机整组记录；其他新记录正常合并。",13,ui.muted,false),10);
        String[] labels=new String[plan.conflicts.size()];boolean[] checked=new boolean[labels.length];
        for(int i=0;i<labels.length;i++)labels[i]=plan.conflictLabel(plan.conflicts.get(i));
        new AlertDialog.Builder(this).setCustomTitle(title).setMultiChoiceItems(labels,checked,(dialog,index,value)->checked[index]=value)
                .setNegativeButton("取消",null).setPositiveButton("合并恢复",(dialog,which)->{
                    Set<String> groups=new HashSet<>();for(int i=0;i<checked.length;i++)if(checked[i])groups.add(plan.conflicts.get(i));applyRestorePlan(plan,groups);
                }).show();
    }
    private void applyRestorePlan(PreferenceBackup.RestorePlan plan,Set<String> useBackup){
        try{
            Map<String,Object> entries=plan.merge(shortcuts.getAll(),useBackup);
            android.content.SharedPreferences.Editor editor=shortcuts.edit();
            for(Map.Entry<String,Object> entry:entries.entrySet()){
                Object value=entry.getValue();
                if(value instanceof Boolean)editor.putBoolean(entry.getKey(),(Boolean)value);
                else if(value instanceof Long)editor.putLong(entry.getKey(),(Long)value);
                else editor.putString(entry.getKey(),(String)value);
            }
            editor.apply();homeSignature="";if("home".equals(page))renderHome(true);toast(entries.isEmpty()?"已保留本机记录":"常用记录已恢复");
        }catch(Exception e){toast(e.getMessage()==null?"恢复失败，未修改记录":e.getMessage());}
    }
    private String clock(long seconds){return new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(seconds*1000));}
    private String duration(long s){return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
    private TextWatcher watcher(Done<String> done){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){done.accept(s.toString());}public void afterTextChanged(Editable e){}};}
    private void login(){startActivityForResult(new Intent(this,LoginActivity.class),100);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if((request==101||request==102)&&result==RESULT_OK){backupResult(request,data);return;}if(request==100&&result==RESULT_OK){repo.api.reloadCookies();loginRequired=false;refreshHome();}}
    private void renderTypes(){
        screenStatus.setText("按设备类型保存游戏与区服，已选内容可直接修改");LinearLayout content=scrollBody();
        for(String t:GameRules.TYPES){
            if(!GameRules.supported(t,deviceData.optString("version")))continue;
            LinearLayout card=ui.card();LinearLayout top=ui.row();
            top.addView(ui.symbol(ui.deviceIcon(t),ui.accent,ui.tint),new LinearLayout.LayoutParams(ui.dp(40),ui.dp(40)));
            TextView name=ui.label(GameRules.typeName(t),17,ui.text,true);name.setPadding(ui.dp(12),0,0,0);top.addView(name);ui.add(card,top,0);
            JSONObject choice=config.optJSONObject(t);
            ui.add(card,ui.selection("加速游戏",choice==null?"选择游戏":choice.optString("name"),R.drawable.ic_game,v->openGames(t)),14);
            if(choice!=null){
                Button region=ui.button("区服 · "+GameRules.regionName(choice.optString("region")),false,v->openCurrentRegion(t));
                ui.trailingIcon(region,R.drawable.ic_arrow,ui.accent,16);ui.add(card,region,8);
            }
            ui.add(content,card,12);
        }
    }
    private void openGames(String t){switchDevice=null;switchAfterSelection=false;gamesBack=page;type=t;query="";page="games";showPage();}
    private void openCurrentRegion(String t){
        switchDevice=null;switchAfterSelection=false;
        JSONObject c=config.optJSONObject(t);if(c==null||c.optString("game").isEmpty()){openGames(t);return;}
        try{selectedGame=GameRules.copy(c);selectedGame.put("id",c.optString("game"));type=t;regionBack=page;page="regions";showPage();}catch(Exception e){failure(e);}
    }
    private boolean switchingDevice(JSONObject device){
        return device.optBoolean("acc_status")||(!app.pendingIp.isEmpty()&&!app.pendingStart)
                ||(!app.workingIp.isEmpty()&&!app.workingStart)||app.switches.hasTask();
    }
    private List<String> recentGameIds(String platform){
        List<String> ids=recentGames(platform);JSONObject current=config.optJSONObject(platform);
        if(current!=null&&!current.optString("game").isEmpty()&&!ids.contains(current.optString("game"))
                &&!shortcuts.getBoolean("hidden_game_"+platform+"_"+current.optString("game"),false))ids.add(current.optString("game"));
        return ids;
    }
    private void openDeviceGames(JSONObject device){openDeviceGames(device,false);}
    private void openDeviceGames(JSONObject device,boolean startAfter){
        String platform=GameRules.effectiveType(device,config);List<JSONObject> recent;
        try{recent=GameRules.quickGames(Repository.array(repo.cached("games_"+GameRules.platform(platform))),recentGameIds(platform),config.optJSONObject(platform),3);}
        catch(Exception e){openDeviceCatalog(device,startAfter);return;}
        if(recent.isEmpty()){openDeviceCatalog(device,startAfter);return;}
        String[] labels=new String[recent.size()];JSONObject current=config.optJSONObject(platform);
        for(int i=0;i<recent.size();i++){
            JSONObject game=recent.get(i);String id=game.optString("id");
            String region=shortcuts.getString("game_region_"+platform+"_"+id,"");
            boolean selected=current!=null&&id.equals(current.optString("game"));if(region.isEmpty()&&selected)region=current.optString("region");
            labels[i]=game.optString("name")+"\n"+(region.isEmpty()?"选择区服":GameRules.regionName(region))+(selected?" · 当前游戏":"");
        }
        new AlertDialog.Builder(this).setTitle("最近常玩 · "+deviceName(device)).setItems(labels,(dialog,index)->{
            try{
                selectedGame=GameRules.copy(recent.get(index));type=platform;switchDevice=GameRules.copy(device);switchAfterSelection=switchingDevice(device)||startAfter;
                switchRouter=deviceData.optString("router_sn");switchEpoch=repo.epoch();regionBack="home";page="regions";showPage();
            }catch(Exception e){failure(e);}
        }).setNeutralButton("完整目录",(dialog,which)->openDeviceCatalog(device,startAfter)).setNegativeButton("取消",null).show();
    }
    private void openDeviceCatalog(JSONObject device,boolean startAfter){
        boolean switching=switchingDevice(device)||startAfter;openGames(GameRules.effectiveType(device,config));
        try{switchDevice=GameRules.copy(device);switchAfterSelection=switching;switchRouter=deviceData.optString("router_sn");switchEpoch=repo.epoch();}catch(Exception e){failure(e);}
    }
    private void openDeviceRegion(JSONObject device){
        boolean switching=switchingDevice(device);openCurrentRegion(GameRules.effectiveType(device,config));
        try{switchDevice=GameRules.copy(device);switchAfterSelection=switching;switchRouter=deviceData.optString("router_sn");switchEpoch=repo.epoch();if("regions".equals(page))renderRegions();}catch(Exception e){failure(e);}
    }
    private void renderGames(){
        body.removeAllViews();LinearLayout searchBox=ui.column();searchBox.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(12));
        EditText search=ui.search("搜索中文、英文或拼音");search.setText(query);searchBox.addView(search);body.addView(searchBox);
        ListView list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));list.setDividerHeight(ui.dp(8));list.setClipToPadding(false);list.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(16));list.setFastScrollEnabled(true);list.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        gameAdapter=new GameAdapter();list.setAdapter(gameAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setItemsCanFocus(true);
        list.setOnItemClickListener((parent,view,position,id)->chooseGame(gameAdapter.items.get(position)));
        search.addTextChangedListener(watcher(value->{query=value;filterGames();}));
        int ticket=gameRequest;JSONObject local=repo.cached("games_"+GameRules.platform(type));
        if(local!=null){gameAdapter.source=Repository.array(local);filterGames();}
        else screenStatus.setText("首次获取 "+GameRules.typeName(type)+" 游戏目录…");
        String platformType=type;
        task(()->repo.games(platformType,false),data->{if(!"games".equals(page)||ticket!=gameRequest)return;gameAdapter.source=Repository.array(data);filterGames();});
    }
    private void filterGames(){
        if(gameAdapter==null)return;GameAdapter adapter=gameAdapter;String needle=query;
        int revision=adapter.revision.incrementAndGet();JSONArray source=adapter.source;String platform=type;
        List<String> recent = recentGameIds(platform);
        app.network.execute(()->{
            List<JSONObject> items=GameRules.games(source,needle);
            boolean noMatches=items.isEmpty()&&!needle.trim().isEmpty();
            if(noMatches){JSONObject generic=genericGame(platform);if(generic!=null)items.add(generic);}
            if(needle.trim().isEmpty()){
                List<String> priority=downloadGames(platform);
                Collections.sort(items,(a,b)->{
                    int ar=recent.indexOf(a.optString("id")),br=recent.indexOf(b.optString("id"));
                    if(ar>=0||br>=0)return Integer.compare(ar<0?999:ar,br<0?999:br);
                    int ap=priority.indexOf(a.optString("id")),bp=priority.indexOf(b.optString("id"));
                    if(ap>=0||bp>=0)return Integer.compare(ap<0?999:ap,bp<0?999:bp);
                    return a.optString("pname",a.optString("name")).compareToIgnoreCase(b.optString("pname",b.optString("name")));
                });
            }
            handler.post(()->{if(destroyed||gameAdapter!=adapter||adapter.revision.get()!=revision)return;
                adapter.recent=recent;adapter.items=items;adapter.noMatches=noMatches;adapter.notifyDataSetChanged();
                screenStatus.setText(noMatches?(items.isEmpty()?"未找到游戏，可点击右上角更新目录":"未找到游戏，可尝试下方通用加速"):GameRules.typeName(platform)+" · "+items.size()+" 款游戏");});
        });
    }
    private void refreshCatalog(){
        GameAdapter adapter=gameAdapter;if(adapter==null)return;
        screenStatus.setText("正在更新游戏目录…");String t=type;int ticket=gameRequest;
        task(()->repo.games(t,true),d->{if(gameAdapter==adapter&&ticket==gameRequest){adapter.source=Repository.array(d);filterGames();}});
    }
    private JSONObject genericGame(String type){
        String id,name;
        switch(type){case "android":id="C0000";name="安卓手游通用加速";break;case "ios":id="D0000";name="苹果手游通用加速";break;
            case "switch":id="10000";name="商城加速器";break;case "ps":case "ps5":id="30000";name="商城加速器";break;
            case "xbox":case "xbox_x":case "xbox_s":id="20000";name="商城加速器";break;default:return null;}
        try{return new JSONObject().put("id",id).put("name",name).put("pname","");}catch(Exception e){return null;}
    }
    private List<String> downloadGames(String type){
        if("windows".equals(type))return Arrays.asList("40001","40002","40003","40005","40007","40008","40013","40014","40016","40011");
        if("switch".equals(type))return Collections.singletonList("10000");
        if(type.startsWith("xbox"))return Collections.singletonList("20000");if(type.startsWith("ps"))return Collections.singletonList("30000");return Collections.emptyList();
    }
    private final class GameAdapter extends BaseAdapter implements SectionIndexer {
        List<String> recent=Collections.emptyList();
        boolean noMatches;JSONArray source=new JSONArray();List<JSONObject> items=new ArrayList<>();final AtomicInteger revision=new AtomicInteger();
        @Override public int getCount(){return items.size();}
        @Override public JSONObject getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return position;}
        private String group(JSONObject game) {
            if (!query.trim().isEmpty()) return noMatches ? "通用加速" : "搜索结果";
            String id=game.optString("id");
            return recent.contains(id)?"最近使用":downloadGames(type).contains(id)?"下载与商城":"全部游戏";
        }
        @Override public View getView(int position,View convert,ViewGroup parent){
            LinearLayout wrapper;
            if(convert==null){
                wrapper=ui.column();
                TextView section=ui.label("",14,ui.muted,true);section.setPadding(ui.dp(2),ui.dp(14),0,ui.dp(10));ui.add(wrapper,section,0);
                LinearLayout row=ui.row();row.setPadding(ui.dp(14),ui.dp(14),ui.dp(14),ui.dp(14));row.setMinimumHeight(ui.dp(80));row.setBackground(ui.states(ui.surface,ui.tint));row.setDuplicateParentStateEnabled(true);
                LinearLayout choice=ui.row();choice.setMinimumHeight(ui.dp(52));choice.setClickable(true);choice.setFocusable(true);
                choice.setBackground(ui.states(android.graphics.Color.TRANSPARENT,ui.tint));
                row.addView(choice,new LinearLayout.LayoutParams(0,-2,1));
                choice.addView(ui.symbol(R.drawable.ic_game,ui.accent,ui.tint),new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
                LinearLayout labels=ui.column();labels.setPadding(ui.dp(14),0,ui.dp(8),0);
                TextView name=ui.label("",16,ui.text,true),subtitle=ui.label("",12,ui.muted,false);
                ui.add(labels,name,0);ui.add(labels,subtitle,6);choice.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                TextView badge=ui.label("",12,ui.accent,true);choice.addView(badge);
                Button remove=ui.button("移除",false,null);remove.setTextSize(13);
                LinearLayout.LayoutParams removeParams=new LinearLayout.LayoutParams(-2,-2);removeParams.leftMargin=ui.dp(8);row.addView(remove,removeParams);
                ui.add(wrapper,row,0);wrapper.setTag(new TextView[]{name,subtitle,badge,section,remove});
            }else wrapper=(LinearLayout)convert;
            TextView[] views=(TextView[])wrapper.getTag();JSONObject game=items.get(position);String id=game.optString("id");
            String group=group(game);views[3].setText(group);
            views[3].setVisibility(position==0||!group.equals(group(items.get(position-1)))?View.VISIBLE:View.GONE);
            JSONObject c=config.optJSONObject(type);boolean chosen=c!=null&&id.equals(c.optString("game"));
            views[0].setText(game.optString("name"));String tags=game.optString("tag");
            views[1].setText(chosen?"当前选择 · "+GameRules.regionName(c.optString("region")):tags.isEmpty()?GameRules.typeName(type):tags);
            boolean removable=recent.contains(id);
            views[2].setText(chosen?"已选":"选择");ui.trailingIcon(views[2],chosen?0:R.drawable.ic_arrow,ui.accent,16);views[2].setVisibility(removable&&!chosen?View.GONE:View.VISIBLE);
            Button remove=(Button)views[4];remove.setVisibility(removable?View.VISIBLE:View.GONE);
            remove.setContentDescription("从最近使用中移除"+game.optString("name"));
            String platform=type;remove.setOnClickListener(v->removeRecentGame(platform,game));
            View choice=((LinearLayout)wrapper.getChildAt(1)).getChildAt(0);
            choice.setContentDescription("选择游戏，"+game.optString("name")+(chosen?"，当前已选":""));
            choice.setOnClickListener(v->chooseGame(game));wrapper.setSelected(chosen);
            return wrapper;
        }
        @Override public Object[] getSections(){return new String[]{"A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z"};}
        @Override public int getPositionForSection(int section){String letter=(String)getSections()[Math.max(0,Math.min(25,section))];for(int i=0;i<items.size();i++)if(!recent.contains(items.get(i).optString("id"))&&!downloadGames(type).contains(items.get(i).optString("id"))&&items.get(i).optString("pname").compareToIgnoreCase(letter)>=0)return i;return Math.max(0,items.size()-1);}
        @Override public int getSectionForPosition(int position){if(items.isEmpty())return 0;String p=items.get(Math.min(position,items.size()-1)).optString("pname").toUpperCase(Locale.ROOT);return p.isEmpty()?0:Math.max(0,Math.min(25,p.charAt(0)-'A'));}
    }
    private void renderRegions(){
        LinearLayout content=scrollBody();ui.add(content,ui.label(selectedGame.optString("name"),24,ui.text,true),4);
        ui.add(content,ui.label(GameRules.typeName(type)+(switchAfterSelection?" · 选择后排队切换并加速":" · 选择后保存"),14,ui.muted,false),10);
        LinearLayout choices=ui.column();ui.add(content,choices,20);int ticket=gameRequest;String id=selectedGame.optString("id");
        JSONObject cached=repo.cached("regions_"+id);
        if(cached!=null)regionChoices(choices,Repository.object(cached));else ui.add(choices,ui.label("首次加载区服…",14,ui.muted,false),8);
        task(()->repo.regions(id,false),d->{if("regions".equals(page)&&ticket==gameRequest)regionChoices(choices,Repository.object(d));});
    }
    private void regionChoices(LinearLayout content,JSONObject detail){
        content.removeAllViews();JSONArray regions=detail.optJSONArray("region_list");
        if(regions==null||regions.length()==0){ui.add(content,ui.label("当前游戏没有可用区服，请返回选择其他游戏",14,ui.muted,false),8);return;}
        screenStatus.setText("选择区服后生效，同一设备类型共用配置");
        JSONObject current=config.optJSONObject(type);
        String remembered=shortcuts.getString("game_region_"+type+"_"+selectedGame.optString("id"),"");
        List<JSONObject> available=GameRules.regionsWithPreferred(regions,remembered);boolean found=false;
        for(JSONObject region:available)if(remembered.equalsIgnoreCase(region.optString("region")))found=true;
        if(!remembered.isEmpty()){
            screenStatus.setText(found?"已标出上次区服，点击确认保存":"上次区服当前不可用，请重新选择");
        }
        for(int i=0;i<available.size();i++){
            JSONObject r=available.get(i);String region=r.optString("region");
            boolean chosen=current!=null&&selectedGame.optString("id").equals(current.optString("game"))&&region.equalsIgnoreCase(current.optString("region"));
            boolean previous=region.equalsIgnoreCase(remembered);
            Button b=ui.button(r.optString("name",GameRules.regionName(region))+(chosen?"  ·  当前":previous?"  ·  上次使用":""),false,v->saveChoice(region,detail));
            b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(ui.dp(18),ui.dp(16),ui.dp(18),ui.dp(16));
            ui.trailingIcon(b,chosen?R.drawable.ic_check:R.drawable.ic_arrow,chosen?ui.accent:ui.muted,20);
            b.setBackground(ui.states(chosen||previous?ui.tint:ui.surface,ui.tint));
            b.setSelected(chosen);b.setEnabled(!app.commandBusy.get()||switchAfterSelection);ui.add(content,b,10);
        }
        if(switchAfterSelection)screenStatus.setText("选择区服后排队切换，确认停止后自动开始加速");
        String notice=detail.optString("notice");if(!notice.isEmpty())ui.add(content,ui.label(android.text.Html.fromHtml(notice).toString(),13,ui.muted,false),18);
    }
    private void saveChoice(String region,JSONObject detail){
        JSONObject game;try{game=GameRules.copy(selectedGame);if(detail.has("routeOption"))game.put("routeOption",detail.get("routeOption"));}catch(Exception e){failure(e);return;}
        boolean unchanged=GameRules.sameSelection(config.optJSONObject(type),game,region);
        boolean liveActive=false;
        if(switchDevice!=null&&freshDevices)for(JSONObject device:devices())if(switchDevice.optString("mac").equalsIgnoreCase(device.optString("mac"))&&device.optBoolean("acc_status"))liveActive=true;
        boolean transitioning=app.commandBusy.get()||!app.pendingIp.isEmpty()||app.switches.hasTask();
        if(unchanged&&(!switchAfterSelection||(liveActive&&!transitioning))){
            shortcuts.edit().putString("game_region_"+type+"_"+game.optString("id"),region).apply();
            page="home";showPage();toast("游戏与区服未改变");return;
        }
        if(switchDevice!=null&&switchAfterSelection){enqueueSwitch(game,region);return;}
        boolean active=false;for(JSONObject d:devices())if(d.optBoolean("acc_status")&&type.equals(GameRules.effectiveType(d,config)))active=true;
        String platform=type;boolean consent=active;
        Runnable save=()->command(()->{
            JSONObject latest=repo.fetchConfig();if(GameRules.sameSelection(latest.optJSONObject(platform),game,region))return latest;
            JSONObject snapshot=Repository.object(repo.fetch("devices","router/device"));
            JSONArray list=snapshot.optJSONArray("device_list");
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject d=list.getJSONObject(i);if(d.optBoolean("acc_status")&&platform.equals(GameRules.effectiveType(d,latest))){
                    if(!consent)throw new ApiClient.Failure(-1,"该设备类型刚开始加速，请返回刷新并确认停止后修改");
                    stopAndWait(d);
                }
            }
            return repo.saveGame(platform,game,region);
        },result->{rememberGame(platform,game.optString("id"));shortcuts.edit().putString("game_region_"+platform+"_"+game.optString("id"),region).apply();config=result;toast("游戏与区服已保存");page="home";showPage();refreshDevices(true);prefetch();});
        if(active)confirm("修改游戏与区服","此设备类型正在加速。保存将先停止加速，可能使游戏断线。","停止并保存",save);else save.run();
    }
    private <T> void command(Job<T> job,Done<T> done){ command(job,done,null,false); }
    private <T> void command(Job<T> job,Done<T> done,JSONObject target,boolean starting){
        if(!app.pendingIp.isEmpty()){toast("请等待路由器完成当前操作");return;}
        if(!app.commandBusy.compareAndSet(false,true)){toast("操作正在进行，请稍候");return;}
        if(target!=null){app.workingIp=target.optString("ip");app.workingStart=starting;app.workingStage="正在核对设备状态";}
        screenStatus.setText("正在处理，请稍候…");if("home".equals(page))renderHome(true);
        app.network.execute(()->{
            try{T result=job.run();new Handler(Looper.getMainLooper()).post(()->{
                app.commandBusy.set(false);
                if(!destroyed)done.accept(result);
                app.workingIp="";app.workingStage="";
                if(!destroyed&&"home".equals(page))renderHome(true);
                if(!destroyed)processQueuedSwitch();
            });}
            catch(Exception e){new Handler(Looper.getMainLooper()).post(()->{
                app.commandBusy.set(false);app.workingIp="";app.workingStage="";
                if(app.switches.hasTask()){app.switches.abort();app.switchError="前序操作失败，排队切换已取消";}
                if(!destroyed)failure(e);
            });}
        });
    }
    private void enqueueSwitch(JSONObject game,String region){
        final JSONObject device=switchDevice;final String platform=type;final String router=switchRouter;final int epoch=switchEpoch;
        if(!router.equals(deviceData.optString("router_sn"))||epoch!=repo.epoch()){
            toast("路由器或登录状态已变化，请返回设备列表重新选择");return;
        }
        AccelerationQueue.Request previous=app.switches.peek();
        boolean sameQueuedDevice=previous!=null&&previous.router.equals(deviceData.optString("router_sn"))
                &&previous.mac.equalsIgnoreCase(device.optString("mac"))&&previous.ip.equals(device.optString("ip"));
        boolean activeNow=false;for(JSONObject current:devices())if(device.optString("mac").equalsIgnoreCase(current.optString("mac"))&&current.optBoolean("acc_status"))activeNow=true;
        final boolean targetActive=activeNow;
        final boolean mayStop=targetActive||sameQueuedDevice
                ||(app.pendingIp.equals(device.optString("ip"))&&!app.pendingStart)
                ||(app.workingIp.equals(device.optString("ip"))&&!app.workingStart);
        boolean alreadyStopping=(!app.pendingIp.isEmpty()&&!app.pendingStart)||(!app.workingIp.isEmpty()&&!app.workingStart)||app.switches.hasTask();
        Runnable enqueue=()->{
            try{
                AccelerationQueue.Request request=new AccelerationQueue.Request(router,device,platform,game,region,
                        deviceName(device),epoch,mayStop);
                app.switchError="";app.switches.offer(request);switchDevice=null;switchAfterSelection=false;
                page="home";showPage();toast("已安排切换为「"+game.optString("name")+"」");processQueuedSwitch();
            }catch(Exception e){failure(e);}
        };
        if(targetActive&&!alreadyStopping)
            confirm("切换游戏并加速","将停止当前设备的加速，保存新的游戏与区服，然后自动开始加速。可能使当前游戏断线。","停止并切换",enqueue);
        else enqueue.run();
    }
    private void renderSwitchQueue(LinearLayout content){
        AccelerationQueue.Request request=app.switches.peek();
        if(request!=null){
            LinearLayout card=ui.card();LinearLayout row=ui.row();LinearLayout labels=ui.column();
            ui.add(labels,ui.label("待切换 · "+request.game.optString("name"),16,ui.text,true),0);
            ui.add(labels,ui.label(request.deviceName+" · "+GameRules.regionName(request.region),12,ui.muted,false),6);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            Button cancel=ui.button("取消排队",false,v->{
                if(app.switches.cancel()){toast("已取消后续启动，已发送的指令不会撤回");renderHome(true);}
                else toast("启动指令已发出，请等待确认后操作");
            });cancel.setEnabled(app.switches.canCancel());row.addView(cancel);ui.add(card,row,0);
            String status=!app.switches.hasDesired()?"已取消后续加速，等待当前操作结束":!app.pendingIp.isEmpty()?"等待前序指令确认":app.switches.isRunning()?app.workingStage:"等待当前操作完成";
            ui.add(card,ui.label(status,13,ui.accent,false),12);ui.add(content,card,12);
        }
        if(!app.switchError.isEmpty()){
            LinearLayout card=ui.card();ui.add(card,ui.label(app.switchError,14,ui.danger,false),0);
            ui.add(card,ui.button("刷新设备状态",false,v->{app.switchError="";refreshHome();renderHome(true);}),10);ui.add(content,card,12);
        }
    }
    private void processQueuedSwitch(){
        if(destroyed||!resumed||loginRequired||app.commandBusy.get()||!app.pendingIp.isEmpty())return;
        AccelerationQueue.Request request=app.switches.begin();if(request==null)return;
        if(!app.commandBusy.compareAndSet(false,true)){app.switches.defer(request);return;}
        app.workingIp=request.ip;app.workingStart=true;app.workingStage="正在核对切换设备";
        if("home".equals(page))renderHome(true);
        app.network.execute(()->{
            GameSwitchOperation.Result result=null;Exception failure=null;
            try{
                result=GameSwitchOperation.execute(app.switches,request,new GameSwitchOperation.Backend(){
                    public JSONObject config()throws Exception{return repo.fetchConfig();}
                    public JSONObject devices()throws Exception{return Repository.object(repo.fetch("devices","router/device"));}
                    public JSONObject save(String platform,JSONObject game,String region)throws Exception{return repo.saveGame(platform,game,region);}
                    public void send(String type,JSONObject message)throws Exception{repo.message(type,message);}
                    public void pause(long millis)throws Exception{Thread.sleep(millis);}
                    public long now(){return android.os.SystemClock.elapsedRealtime();}
                    public int epoch(){return repo.epoch();}
                },this::commandStage);
            }catch(GameSwitchOperation.Superseded ignored){}catch(Exception e){failure=e;}
            final GameSwitchOperation.Result completed=result;final Exception error=failure;
            new Handler(Looper.getMainLooper()).post(()->{
                if(error!=null){app.switches.abort();app.switchError=error.getMessage()==null?"切换失败，请刷新设备状态":error.getMessage();}
                app.switches.finish(request);app.commandBusy.set(false);app.workingIp="";app.workingStage="";
                if(completed!=null&&request.epoch==repo.epoch()){
                    rememberGame(request.platform,request.game.optString("id"));
                    shortcuts.edit().putString("game_region_"+request.platform+"_"+request.game.optString("id"),request.region).apply();
                    if(completed.sent){
                        app.pendingIp=completed.device.optString("ip");app.pendingMac=request.mac;app.pendingRouter=request.router;
                        app.pendingStart=true;app.pendingUntil=android.os.SystemClock.elapsedRealtime()+35000;
                    }
                    if(!destroyed){config=completed.config;toast(completed.sent?"启动指令已发送，等待路由器确认":"当前游戏与区服未改变，已保留加速");}
                }
                if(!destroyed){
                    if(error!=null)failure(error);
                    if("home".equals(page)){renderHome(true);refreshDevices(false);}
                    processQueuedSwitch();
                }
            });
        });
    }
    private void commandStage(String stage){
        app.workingStage=stage;handler.post(()->{if(!destroyed&&"home".equals(page))renderHome(false);});
    }
    private void start(JSONObject device){
        String t=GameRules.effectiveType(device,config);
        if(!GameRules.supported(t,deviceData.optString("version"))){toast("当前路由插件暂不支持该设备类型");return;}
        JSONObject c=config.optJSONObject(t);if(c==null||c.optString("game").isEmpty()){openDeviceGames(device,true);return;}
        boolean other=false;for(JSONObject d:devices())if(d.optBoolean("acc_status")&&!d.optString("ip").equals(device.optString("ip")))other=true;
        boolean consent=other;
        Runnable start=()->command(()->{
            JSONObject latest=repo.fetchConfig();JSONObject snapshot=Repository.object(repo.fetch("devices","router/device"));JSONArray list=snapshot.optJSONArray("device_list");
            JSONObject target=null;
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject d=list.getJSONObject(i);
                if(d.optString("ip").equals(device.optString("ip"))&&d.optString("mac").equals(device.optString("mac")))target=d;
            }
            if(target==null)throw new ApiClient.Failure(-1,"设备地址已变化，请刷新设备列表后重试");
            if(target.optBoolean("acc_status"))throw new ApiClient.Failure(-1,"该设备已经在加速，请刷新状态");
            String kind=GameRules.effectiveType(target,latest);JSONObject choice=latest.optJSONObject(kind);
            if(!GameRules.supported(kind,snapshot.optString("version")))throw new ApiClient.Failure(-1,"当前插件不支持此设备类型");
            if(choice==null)throw new ApiClient.Failure(-1,"尚未选择游戏，请先配置游戏");
            if(!choice.optString("game").equals(c.optString("game"))||!choice.optString("region").equalsIgnoreCase(c.optString("region")))
                throw new ApiClient.Failure(-1,"游戏配置已在其他客户端更新，请刷新后重试");
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject d=list.getJSONObject(i);if(d.optString("ip").equals(device.optString("ip"))&&d.optString("mac").equals(device.optString("mac")))target=d;
                if(d.optBoolean("acc_status")&&!d.optString("ip").equals(device.optString("ip"))){
                    if(!consent)throw new ApiClient.Failure(-1,"其他设备刚开始加速，请刷新后确认切换");commandStage("正在停止其他设备");stopAndWait(d);
                }
            }
            commandStage("正在发送启动指令");repo.message("start_speed",GameRules.startMessage(target,choice));return latest;
        },value->{config=value;pending(device,true);},device,true);
        if(other)confirm("切换加速设备","其他设备正在加速，切换会先停止其加速，可能使游戏断线。","停止并切换",start);else start.run();
    }
    private void stop(JSONObject device){command(()->{
        JSONArray latest=Repository.object(repo.fetch("devices","router/device")).optJSONArray("device_list");boolean found=false;
        if(latest!=null)for(int i=0;i<latest.length();i++){JSONObject d=latest.getJSONObject(i);if(d.optString("mac").equals(device.optString("mac"))&&d.optString("ip").equals(device.optString("ip")))found=true;}
        if(!found)throw new ApiClient.Failure(-1,"设备地址已变化，请刷新后重试");
        commandStage("正在发送停止指令");repo.message("stop_speed",new JSONObject().put("ip",device.getString("ip")));return true;},v->pending(device,false),device,false);}
    private void pending(JSONObject device,boolean start){app.pendingIp=device.optString("ip");app.pendingMac=device.optString("mac");app.pendingRouter=deviceData.optString("router_sn");app.pendingStart=start;app.pendingUntil=android.os.SystemClock.elapsedRealtime()+35000;toast("指令已发送，等待路由器确认");if("home".equals(page))renderHome(true);refreshDevices(false);handler.removeCallbacks(poll);if(resumed)handler.postDelayed(poll,1000);}
    private void checkPending(){
        if(app.pendingIp.isEmpty())return;
        if(!app.pendingRouter.isEmpty()&&!app.pendingRouter.equals(deviceData.optString("router_sn"))){
            app.pendingIp="";app.switches.abort();app.switchError="路由器已变化，排队切换已取消";return;
        }
        JSONObject ack=deviceData.optJSONObject("acc_status");
        if(ack!=null&&ack.has("success")&&!ack.optBoolean("success")){
            String msg=ack.optString("msg_cn");if(!msg.isEmpty()){app.pendingIp="";errorText=msg;if(app.switches.hasTask()){app.switches.abort();app.switchError="前序指令失败："+msg;}toast(msg);return;}
        }
        for(JSONObject d:devices())if(app.pendingIp.equals(d.optString("ip"))&&(app.pendingMac.isEmpty()||app.pendingMac.equalsIgnoreCase(d.optString("mac")))&&d.optBoolean("acc_status")==app.pendingStart){toast(app.pendingStart?"加速已启动":"加速已停止");app.pendingIp="";return;}
        if(android.os.SystemClock.elapsedRealtime()>app.pendingUntil){app.pendingIp="";errorText="路由器确认超时，请刷新状态后再操作";if(app.switches.hasTask()){app.switches.abort();app.switchError="前序指令确认超时，未启动后续游戏";}toast(errorText);}
    }
    private void stopAndWait(JSONObject device)throws Exception{
        repo.message("stop_speed",new JSONObject().put("ip",device.getString("ip")));
        long end=android.os.SystemClock.elapsedRealtime()+20000;
        while(android.os.SystemClock.elapsedRealtime()<end){
            Thread.sleep(700);JSONObject data=Repository.object(repo.fetch("devices","router/device"));JSONArray list=data.optJSONArray("device_list");boolean running=false;
            if(list==null)throw new ApiClient.Failure(-1,"设备状态暂不可用，已取消后续操作");
            for(int i=0;i<list.length();i++){JSONObject d=list.getJSONObject(i);if(device.optString("ip").equals(d.optString("ip"))&&d.optBoolean("acc_status"))running=true;}
            if(!running)return;
        }
        throw new ApiClient.Failure(-1,"停止加速尚未确认，请刷新状态后重试");
    }
    private void confirm(String title,String message,String action,Runnable run){new AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton(action,(d,w)->run.run()).show();}
    private void chooseDeviceType(JSONObject device){
        ArrayList<String> names=new ArrayList<>(),types=new ArrayList<>();names.add("自动识别（"+GameRules.typeName(device.optString("type"))+"）");types.add("");
        for(String t:GameRules.TYPES)if(GameRules.supported(t,deviceData.optString("version"))){names.add(GameRules.typeName(t));types.add(t);}
        new AlertDialog.Builder(this).setTitle("设备类型").setItems(names.toArray(new String[0]),(dialog,index)->{
            String t=types.get(index);confirm("修改设备类型","错误的设备类型可能影响上网体验。确认修改？","确认修改",()->{
                for(JSONObject d:devices())if(d.optBoolean("acc_status")&&d.optString("mac").equals(device.optString("mac"))){toast("请先停止此设备的加速再修改类型");return;}
                command(()->{
                    JSONArray latest=Repository.object(repo.fetch("devices","router/device")).optJSONArray("device_list");
                    if(latest!=null)for(int i=0;i<latest.length();i++){JSONObject d=latest.getJSONObject(i);if(d.optString("mac").equals(device.optString("mac"))&&d.optBoolean("acc_status"))throw new ApiClient.Failure(-1,"该设备正在加速，请先停止后修改类型");}
                    return repo.saveDeviceType(device.getString("mac"),t);
                },value->{config=value;toast("设备类型已更新");renderHome(true);prefetch();});
            });
        }).setNegativeButton("取消",null).show();
    }
    private void loadMine(boolean force){
        renderMine();String[] keys={"user","goods","offers","routers"};String[] paths={"user/info","goods","client/pop","router/bind?brand=xiaomi"};
        for(int i=0;i<keys.length;i++){
            String key=keys[i],path=paths[i];
            if(!force&&repo.cached(key)!=null)continue;
            task(()->repo.fetch(key,path),data->{if("routers".equals(key))routers=Repository.array(data);if("mine".equals(page))renderMine();});
        }
    }
    private void renderMine(){
        if(!"mine".equals(page))return;
        screenStatus.setVisibility(View.GONE);LinearLayout content=scrollBody();
        JSONObject user=Repository.object(repo.cached("user"));
        String nickname=user.optString("nick_name");if(nickname.isEmpty())nickname="游帮帮账号";
        LinearLayout profile=ui.row();profile.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
        ImageView avatar=ui.symbol(R.drawable.ic_user,ui.accent,ui.tint);avatar.setPadding(ui.dp(12),ui.dp(12),ui.dp(12),ui.dp(12));
        avatar.setBackground(ui.shape(ui.tint,30));profile.addView(avatar,new LinearLayout.LayoutParams(ui.dp(48),ui.dp(48)));
        LinearLayout account=ui.column();account.setPadding(ui.dp(16),0,0,0);
        TextView name=ui.label(nickname,23,ui.text,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);ui.add(account,name,0);
        ui.add(account,ui.label("百度账号 · 游帮帮服务",12,ui.muted,false),8);
        profile.addView(account,new LinearLayout.LayoutParams(0,-2,1));ui.add(content,profile,0);

        JSONArray goods=Repository.array(repo.cached("goods"));
        String memberState=repo.cached("goods")==null?"正在获取会员状态":"尚未开通";
        String memberDetail="需使用支持路由器的全平台会员";boolean active=false;
        long now=System.currentTimeMillis()/1000;
        for(int i=0;i<goods.length();i++){
            JSONObject item=goods.optJSONObject(i);if(item==null||item.optInt("spu")!=3)continue;
            long end=item.optLong("end_time");active=end>now;memberState=active?"会员有效":"会员已到期";
            memberDetail=end>0?new SimpleDateFormat("yyyy.MM.dd",Locale.CHINA).format(new Date(end*1000))+ (active?" 到期":" 已到期"):"请在官方客户端查看会员信息";
        }
        LinearLayout member=ui.card();
        android.graphics.drawable.GradientDrawable membershipBackground=new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{ui.hero,NativeUi.c(ui.dark?"#1D2E50":"#183C9D")});
        membershipBackground.setCornerRadius(ui.dp(20));member.setBackground(membershipBackground);
        LinearLayout membershipTitle=ui.row();
        ImageView star=ui.symbol(R.drawable.ic_star,NativeUi.c("#D5E3FF"),android.graphics.Color.TRANSPARENT);star.setPadding(0,0,0,0);
        membershipTitle.addView(star,new LinearLayout.LayoutParams(ui.dp(20),ui.dp(20)));
        TextView title=ui.label("全平台会员",20,android.graphics.Color.WHITE,true);title.setPadding(ui.dp(8),0,ui.dp(8),0);
        membershipTitle.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        String badge=active?"有效":memberState.equals("会员已到期")?"已到期":memberState.equals("尚未开通")?"未开通":"获取中";
        TextView status=ui.label(badge,12,NativeUi.c("#E2EAFF"),true);status.setPadding(ui.dp(10),ui.dp(5),ui.dp(10),ui.dp(5));
        status.setBackground(ui.shape(NativeUi.c(ui.dark?"#354B72":"#3C5DB9"),8));membershipTitle.addView(status);
        ui.add(member,membershipTitle,0);
        LinearLayout membershipAction=ui.row();
        TextView official=ui.label(memberDetail,12,NativeUi.c("#D5E3FF"),false);
        membershipAction.addView(official,new LinearLayout.LayoutParams(0,-2,1));
        Button renew=ui.button(active?"续费会员":"了解会员",false,v->membershipHelp());
        renew.setTextSize(13);renew.setLineSpacing(0,1);
        renew.setIncludeFontPadding(false);renew.setGravity(Gravity.CENTER);
        renew.setTextColor(NativeUi.c("#183C9D"));
        renew.setBackground(new android.graphics.drawable.InsetDrawable(ui.states(NativeUi.c("#ECF2FF"),NativeUi.c("#CDDEFF")),0,ui.dp(9),0,ui.dp(9)));
        renew.setPadding(ui.dp(20),0,ui.dp(20),0);
        membershipAction.addView(renew);ui.add(member,membershipAction,10);ui.add(content,member,16);

        JSONArray offers=Repository.array(repo.cached("offers"));
        for(int i=0;i<offers.length();i++){
            JSONObject offer=offers.optJSONObject(i);if(offer==null||!offer.has("id"))continue;
            String id=offer.optString("id");
            LinearLayout benefit=ui.card();benefit.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
            ui.add(benefit,ui.settingsRow("领取可用会员权益",R.drawable.ic_star,v->confirm("领取会员权益","以官方接口返回的当前活动规则和领取结果为准。","确认领取",()->command(()->repo.api.request("PATCH","client/pop/"+Uri.encode(id),null),result->{toast("会员权益已领取");loadMine(true);}))),0);
            ui.add(content,benefit,12);
        }
        ui.add(content,ui.label("设备与支持",13,ui.muted,true),16);
        LinearLayout settings=ui.card();settings.setPadding(ui.dp(4),ui.dp(4),ui.dp(4),ui.dp(4));
        ui.add(settings,ui.settingsRow("路由器管理",R.drawable.ic_router,v->navigate("router")),0);ui.divider(settings,52);
        ui.add(settings,ui.settingsRow("服务公告",R.drawable.ic_notice,v->navigate("notices")),0);ui.divider(settings,52);
        ui.add(settings,ui.settingsRow("帮助与反馈",R.drawable.ic_help,v->help()),0);ui.divider(settings,52);
        ui.add(settings,ui.settingsRow("备份与恢复",R.drawable.ic_backup,v->backupOptions()),0);ui.add(content,settings,10);

        Button logout=ui.button("退出登录",false,v->confirm("退出登录","退出此应用的账号。路由器绑定关系将保留。","退出登录",()->command(()->{
            repo.api.request("GET","user/logout",null);repo.clear();app.switches.abort();app.switchError="";return true;
        },value->{CookieManagerCompat.clear();config=new JSONObject();deviceData=new JSONObject();routers=new JSONArray();loginRequired=true;page="home";showPage();})));
        logout.setTextColor(ui.danger);logout.setBackground(ui.states(ui.surface,ui.soft));ui.add(content,logout,16);
        TextView footer=ui.label("游帮帮路由助手 0.3.0 · 非官方客户端\n路由插件 "+deviceData.optString("version","未知")+"\nCopyright (c) 2026 dongx · AGPL-3.0-only",11,ui.muted,false);footer.setGravity(Gravity.CENTER);ui.add(content,footer,12);
    }
    private void membershipHelp(){
        new AlertDialog.Builder(this).setTitle("全平台会员").setMessage("开通或续费需在游帮帮官方客户端完成。请选择支持路由器加速的全平台会员。")
                .setNegativeButton("关闭",null).setPositiveButton("打开官方网站",(d,w)->external("https://ybb.baidu.com/")).show();
    }
    private void renderRouter(){
        screenStatus.setText("百度账号与路由器的绑定关系");LinearLayout content=scrollBody();LinearLayout card=ui.card();
        ui.add(card,ui.label(routerName(),23,ui.text,true),0);JSONObject router=routers.optJSONObject(0);
        ui.add(card,ui.label(router==null?"尚未绑定路由器":"型号  "+router.optString("model")+"\n名称  "+router.optString("name"),14,ui.muted,false),14);
        ui.add(card,ui.label("一个账号仅可绑定一台路由器。首次绑定或更换路由器，需要在小米 WiFi 的游帮帮插件中完成。",14,ui.muted,false),20);ui.add(content,card,0);
        if(router!=null)ui.add(content,ui.button("解绑路由器",false,v->confirm("解绑路由器","解绑后将无法继续管理此路由器的加速，需要重新通过小米 WiFi 绑定。","确认解绑",()->command(()->{
            repo.api.request("DELETE","router/unbind/"+Uri.encode(router.getString("id")),null);
            repo.api.request("GET","user/logout",null);repo.clear();app.switches.abort();app.switchError="";return true;
        },value->{CookieManagerCompat.clear();config=new JSONObject();deviceData=new JSONObject();routers=new JSONArray();loginRequired=true;page="home";showPage();}))),20);
    }
    private void renderNotices(){
        LinearLayout content=scrollBody();JSONObject cache=repo.cached("notices");
        if(cache!=null)noticeRows(content,Repository.array(cache));else ui.add(content,ui.label("正在获取官方公告…",14,ui.muted,false),8);
        task(()->repo.fetch("notices","notice/list"),data->{if("notices".equals(page))noticeRows(content,Repository.array(data));});
    }
    private void noticeRows(LinearLayout content,JSONArray notices){
        content.removeAllViews();screenStatus.setText("游帮帮官方服务公告");
        for(int i=0;i<notices.length();i++){
            JSONObject n=notices.optJSONObject(i);if(n==null)continue;LinearLayout card=ui.card();ui.add(card,ui.label(n.optString("title"),18,ui.text,true),0);
            ui.add(card,ui.label(n.optString("description",n.optString("content")),14,ui.muted,false),12);
            String link=n.optString("link");if(!link.isEmpty())ui.add(card,ui.button("阅读公告",false,v->readNotice(n.optString("title"),link)),14);ui.add(content,card,12);
        }
        if(notices.length()==0)ui.add(content,ui.label("暂无公告",14,ui.muted,false),12);
    }
    private void readNotice(String title,String link){
        Uri uri=Uri.parse(link);if(!"https".equals(uri.getScheme())||!"ybb.baidu.com".equals(uri.getHost())){external(link);return;}
        task(()->{
            java.net.HttpURLConnection c=(java.net.HttpURLConnection)new java.net.URL(link).openConnection();c.setConnectTimeout(8000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
            try(java.io.InputStream in=c.getInputStream()){
                String html=new String(ApiClient.read(in),java.nio.charset.StandardCharsets.UTF_8);
                html=html.replaceAll("(?is)<script[^>]*>.*?</script>","").replaceAll("(?is)<style[^>]*>.*?</style>","").replaceAll("(?is)<head[^>]*>.*?</head>","");
                return android.text.Html.fromHtml(html).toString().trim();
            }finally{c.disconnect();}
        },text->{TextView view=ui.label(text,16,ui.text,false);view.setTextIsSelectable(true);view.setPadding(ui.dp(20),ui.dp(12),ui.dp(20),ui.dp(20));ScrollView scroll=new ScrollView(this);scroll.addView(view);new AlertDialog.Builder(this).setTitle(title).setView(scroll).setPositiveButton("关闭",null).show();});
    }
    private void help(){new AlertDialog.Builder(this).setTitle("帮助与反馈").setMessage("首次绑定：在小米 WiFi 中打开游帮帮插件并绑定百度账号。\n\n启动较慢：路由器需要等待官方服务器下发指令，请以设备的实际加速状态为准。\n\n找不到游戏：在游戏列表中搜索中文、英文或拼音，点击右上角“更新”可更新目录。\n\n会员：使用支持路由器的游帮帮全平台会员。\n\n账号、会员和线路问题可通过官方渠道反馈。").setNegativeButton("关闭",null).setPositiveButton("官方反馈",(d,w)->external("https://ufosdk.baidu.com/ufosdk/postview/J0V34jPulTHw48naTy7Nqw%3D%3D/256969")).show();}
    private void external(String link){
        Uri uri=Uri.parse(link);if(!"https".equals(uri.getScheme())){toast("暂不支持此链接");return;}
        try{startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE));}catch(android.content.ActivityNotFoundException e){toast("未找到可打开链接的浏览器");}
    }
    private void back(){switch(page){case "home":super.onBackPressed();return;case "mine":navigate("home");return;case "games":query="";navigate(gamesBack);return;case "regions":navigate(regionBack);return;case "router":case "notices":navigate("mine");return;default:navigate("home");}}
    @Override public void onBackPressed(){back();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("switchRouter",switchRouter);state.putInt("switchEpoch",switchEpoch);state.putBoolean("switchAfterSelection",switchAfterSelection);if(switchDevice!=null)state.putString("switchDevice",switchDevice.toString());state.putBoolean("favoriteOnly",favoriteOnly);state.putString("gamesBack",gamesBack);state.putString("page",page);state.putString("type",type);state.putString("query",query);state.putString("deviceQuery",deviceQuery);state.putString("regionBack",regionBack);if(selectedGame!=null)state.putString("selectedGame",selectedGame.toString());super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();resumed=true;freshDevices=false;if("home".equals(page))renderHome(false);processQueuedSwitch();handler.removeCallbacks(poll);handler.postDelayed(poll,1500);handler.removeCallbacks(ticker);handler.post(ticker);}
    @Override protected void onPause(){resumed=false;freshDevices=false;handler.removeCallbacks(poll);handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;if(deviceMenu!=null)deviceMenu.dismiss();handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private static final class CookieManagerCompat {
        static void clear(){android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.CookieManager.getInstance().flush();android.webkit.WebStorage.getInstance().deleteAllData();}
    }
}
