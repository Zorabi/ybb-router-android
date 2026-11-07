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
    private LinearLayout root, body, bottom, header;
    private TextView screenStatus;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String page = "home", type = "ps5", query = "", deviceQuery = "";
    private JSONObject config = new JSONObject(), deviceData = new JSONObject();
    private JSONArray routers = new JSONArray();
    private long serverTime;
    private long serverTimeAtElapsed;
    private boolean resumed, deviceLoading, loginRequired, destroyed, freshDevices;
    private String homeSignature = "", errorText = "";
    private JSONObject selectedGame;
    private String regionBack = "games";
    private GameAdapter gameAdapter;
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
            if (device.optBoolean("acc_status") && info != null && info.optLong("start_time") > 0)
                label.setText("加速中 · "+duration(Math.max(0, now-info.optLong("start_time"))));
        }
    }
    interface Job<T> { T run() throws Exception; }
    interface Done<T> { void accept(T value); }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); ui = new NativeUi(this); app = (RouterApplication)getApplication(); repo = app.repository;
        config = Repository.object(repo.cached("config")); deviceData = Repository.object(repo.cached("devices"));
        routers = Repository.array(repo.cached("routers"));
        JSONObject old = repo.cached("devices"); serverTime = old == null ? 0 : old.optLong("server_time");
        if (state != null) {
            page = state.getString("page", "home"); type = state.getString("type", "ps5"); query = state.getString("query", "");
            deviceQuery = state.getString("deviceQuery", ""); regionBack = state.getString("regionBack", "games");
            try { if (state.containsKey("selectedGame")) selectedGame = new JSONObject(state.getString("selectedGame")); } catch(Exception ignored) {}
            if ("regions".equals(page) && selectedGame == null) page = "home";
        }
        showPage(); refreshHome();
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
        } else if (error instanceof java.io.IOException) errorText = "网络连接失败，已保留本地内容，请重试";
        else errorText = error.getMessage() == null ? "操作失败，请重试" : error.getMessage();
        if (screenStatus != null) screenStatus.setText(errorText);
        if ("home".equals(page)) renderHome(true);
        else toast(errorText);
    }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
    private void showPage() {
        gameRequest++; gameAdapter = null; homeSignature = "";
        root = ui.column(); root.setBackgroundColor(ui.bg);
        header = ui.row(); header.setPadding(ui.dp(20), ui.dp(10), ui.dp(12), ui.dp(10));
        boolean main = "home".equals(page) || "mine".equals(page);
        if (!main) header.addView(ui.button("返回", false, v -> back()));
        String title = title(); TextView heading = ui.label(title, main ? 27 : 21, ui.text, true);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1); if (!main) hp.leftMargin = ui.dp(12); header.addView(heading, hp);
        if (main) header.addView(ui.button("刷新", false, v -> { if ("home".equals(page)) refreshHome(); else loadMine(true); }));
        if ("games".equals(page)) header.addView(ui.button("更新", false, v -> refreshCatalog()));
        root.addView(header);
        screenStatus = ui.label("", 12, ui.muted, false); screenStatus.setPadding(ui.dp(22), 0, ui.dp(22), ui.dp(8)); root.addView(screenStatus);
        body = ui.column(); root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        if (main) {
            bottom = ui.row(); bottom.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8)); bottom.setBackgroundColor(ui.surface);
            Button home = ui.button("加速", false, v -> navigate("home")); home.setSelected("home".equals(page));
            Button mine = ui.button("我的", false, v -> navigate("mine")); mine.setSelected("mine".equals(page));
            bottom.addView(home, new LinearLayout.LayoutParams(0, ui.dp(50), 1)); bottom.addView(mine, new LinearLayout.LayoutParams(0, ui.dp(50), 1)); root.addView(bottom);
        }
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
    private void refreshHome() {
        screenStatus.setText("正在更新…"); errorText = "";
        task(() -> repo.fetch("routers", "router/bind?brand=xiaomi"), data -> {
            routers = Repository.array(data); loginRequired = false; if ("home".equals(page)) renderHome(true);
        });
        task(repo::fetchConfig, value -> { config = value; if ("home".equals(page)) renderHome(true); else if ("types".equals(page)) renderTypes(); prefetch(); });
        refreshDevices(true);
    }
    private void refreshDevices(boolean force) {
        if (deviceLoading) return; deviceLoading = true;
        int epoch = repo.epoch();
        app.network.execute(() -> {
            try {
                JSONObject data = repo.fetch("devices", "router/device");
                handler.post(() -> {
                    deviceLoading = false; if (destroyed || epoch != repo.epoch()) return;
                    freshDevices = true; deviceData = Repository.object(data); serverTime = data.optLong("server_time"); serverTimeAtElapsed = android.os.SystemClock.elapsedRealtime(); loginRequired = false; errorText = "";
                    checkPending();
                    if ("home".equals(page)) renderHome(force);
                    prefetch();
                });
            } catch (Exception e) { handler.post(() -> { deviceLoading = false; freshDevices = false; if (!destroyed && epoch == repo.epoch()) failure(e); }); }
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
        Collections.sort(out,GameRules.deviceOrder(config)); return out;
    }
    private String routerName() {
        JSONObject r=routers.optJSONObject(0); if(r==null)return "尚未绑定路由器";
        String model=r.optString("model"); return "RM1800".equalsIgnoreCase(model)?"小米 AX1800":r.optString("name",model);
    }
    private void renderHome(boolean force) {
        if (!"home".equals(page)) return;
        String signature=devices().toString()+deviceData.optString("router_sn")+deviceData.optString("version")+config.toString()+routers.toString()+errorText+app.pendingIp+app.workingIp+app.commandBusy.get()+deviceQuery;
        if (!force&&signature.equals(homeSignature)) {
            screenStatus.setText((freshDevices?"已同步 · ":"上次同步 · ")+clock(serverTime));
            updateElapsedLabels();
            return;
        } homeSignature=signature;
        screenStatus.setText(!errorText.isEmpty()?errorText:serverTime==0?"正在连接游帮帮服务…":(freshDevices?"已同步 · ":"上次同步 · ")+clock(serverTime));
        LinearLayout content=scrollBody();
        if(loginRequired) {
            LinearLayout card=ui.card();ui.add(card,ui.label("登录游帮帮账号",21,ui.text,true),0);
            ui.add(card,ui.label("使用已绑定路由器的百度账号继续",14,ui.muted,false),10);
            ui.add(card,ui.button("百度账号登录",true,v->login()),18);ui.add(content,card,0);return;
        }
        List<JSONObject> list=devices(); int count=0;for(JSONObject d:list)if(d.optBoolean("acc_status"))count++;
        LinearLayout router=ui.card(); ui.add(router,ui.label(routerName(),22,ui.text,true),0);
        boolean online=!deviceData.optString("router_sn").isEmpty();
        ui.add(router,ui.label((online?(freshDevices?"路由器在线":"上次在线"):"等待路由器连接")+"   ·   "+list.size()+" 台设备",13,online?ui.green:ui.muted,false),10);
        LinearLayout stats=ui.row();TextView stat=ui.label(count>0?count+" 台设备正在加速":"准备好，开始畅玩",19,ui.text,true); stats.addView(stat,new LinearLayout.LayoutParams(0,-2,1));
        Button settings=ui.button("游戏设置",false,v->navigate("types"));stats.addView(settings);ui.add(router,stats,16);ui.add(content,router,0);
        if(routers.length()==0)ui.add(content,ui.label("请先在小米 WiFi 的游帮帮插件中完成首次绑定，再刷新本页。",14,ui.muted,false),16);
        LinearLayout section=ui.row();section.addView(ui.label("加速设备",19,ui.text,true),new LinearLayout.LayoutParams(0,-2,1));
        section.addView(ui.label(list.size()+" 台",13,ui.muted,false));ui.add(content,section,26);
        EditText search=ui.search("搜索设备名称或 IP");search.setText(deviceQuery);ui.add(content,search,12);
        LinearLayout cards=ui.column();ui.add(content,cards,0);
        renderDeviceCards(cards,list);
        search.addTextChangedListener(watcher(value->{deviceQuery=value;renderDeviceCards(cards,list);}));
        if(!errorText.isEmpty())ui.add(content,ui.button("重新连接",false,v->refreshHome()),16);
    }
    private void renderDeviceCards(LinearLayout holder,List<JSONObject> list) {
        holder.removeAllViews();deviceStatuses.clear();int visible=0;String needle=deviceQuery.toLowerCase(Locale.ROOT);
        for(JSONObject device:list) {
            String name=device.optString("host");String t=GameRules.effectiveType(device,config);if(name.isEmpty())name=GameRules.typeName(t);
            if(!(name+" "+device.optString("ip")).toLowerCase(Locale.ROOT).contains(needle))continue;visible++;
            LinearLayout card=ui.card();boolean active=device.optBoolean("acc_status");
            LinearLayout top=ui.row();LinearLayout names=ui.column();ui.add(names,ui.label(name,17,ui.text,true),0);
            ui.add(names,ui.label(GameRules.typeName(t)+" · "+device.optString("ip"),12,ui.muted,false),6);top.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            Button more=ui.button("类型",false,v->chooseDeviceType(device));top.addView(more);ui.add(card,top,0);
            JSONObject choice=config.optJSONObject(t);String game=choice==null?"选择游戏":choice.optString("name","选择游戏");
            LinearLayout gameRow=ui.row(); Button gameButton=ui.button(game,false,v->openGames(t));gameButton.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);gameButton.setMaxLines(2);
            gameRow.addView(gameButton,new LinearLayout.LayoutParams(0,-2,1));
            Button region=ui.button(choice==null?"区服":GameRules.regionName(choice.optString("region")),false,v->openCurrentRegion(t));gameRow.addView(region);ui.add(card,gameRow,14);
            LinearLayout action=ui.row();String state=active?"加速中":"未加速";
            if(app.pendingIp.equals(device.optString("ip")))state=app.pendingStart?"正在启动…":"正在停止…";
            if(app.workingIp.equals(device.optString("ip")))state=app.workingStart?"正在启动…":"正在停止…";
            JSONObject info=device.optJSONObject("acc_info");if(active&&info!=null){long start=info.optLong("start_time");if(start>0)state+=" · "+duration(Math.max(0,estimatedServerTime()-start));}
            TextView status=ui.label(state,13,active?ui.green:ui.muted,false);deviceStatuses.put(device.optString("ip"),status);action.addView(status,new LinearLayout.LayoutParams(0,-2,1));
            String ip=device.optString("ip");
            boolean waiting=ip.equals(app.workingIp)||ip.equals(app.pendingIp);
            boolean starting=ip.equals(app.workingIp)?app.workingStart:app.pendingStart;
            Button start=ui.button(waiting?"":active?"停止加速":"开始加速",true,v->{if(active)stop(device);else start(device);});
            start.setTextColor(android.graphics.Color.WHITE);
            android.graphics.drawable.StateListDrawable actionBackground=new android.graphics.drawable.StateListDrawable();
            int normal=NativeUi.c(ui.dark?"#406EDB":"#2D5DE0");
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
                TextView label=ui.label(starting?"正在启动":"正在停止",14,color,false);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-2,-2);lp.leftMargin=ui.dp(8);loading.addView(label,lp);
                loading.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                control.addView(loading,new FrameLayout.LayoutParams(-1,-1));
                start.setContentDescription(starting?"正在启动加速，请稍候":"正在停止加速，请稍候");
            }
            action.addView(control,new LinearLayout.LayoutParams(ui.dp(136),ui.dp(48)));ui.add(card,action,12);ui.add(holder,card,12);
        }
        if(visible==0)ui.add(holder,ui.label(list.isEmpty()?"等待设备列表，点击右上角刷新":"没有匹配的设备",14,ui.muted,false),24);
    }
    private String clock(long seconds){return new SimpleDateFormat("HH:mm:ss",Locale.CHINA).format(new Date(seconds*1000));}
    private String duration(long s){return String.format(Locale.ROOT,"%02d:%02d:%02d",s/3600,s/60%60,s%60);}
    private TextWatcher watcher(Done<String> done){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){done.accept(s.toString());}public void afterTextChanged(Editable e){}};}
    private void login(){startActivityForResult(new Intent(this,LoginActivity.class),100);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==100&&result==RESULT_OK){repo.api.reloadCookies();loginRequired=false;refreshHome();}}
    private void renderTypes(){
        screenStatus.setText("按设备类型保存游戏与区服，已选内容可直接修改");LinearLayout content=scrollBody();
        for(String t:GameRules.TYPES){
            if(!GameRules.supported(t,deviceData.optString("version")))continue;
            LinearLayout card=ui.card();ui.add(card,ui.label(GameRules.typeName(t),18,ui.text,true),0);
            JSONObject choice=config.optJSONObject(t);ui.add(card,ui.label(choice==null?"尚未配置":choice.optString("name")+" · "+GameRules.regionName(choice.optString("region")),14,ui.muted,false),10);
            LinearLayout row=ui.row();row.addView(ui.button("选择游戏",false,v->openGames(t)),new LinearLayout.LayoutParams(0,-2,1));
            if(choice!=null)row.addView(ui.button("修改区服",false,v->openCurrentRegion(t)),new LinearLayout.LayoutParams(0,-2,1));ui.add(card,row,14);ui.add(content,card,12);
        }
    }
    private void openGames(String t){type=t;query="";page="games";showPage();}
    private void openCurrentRegion(String t){
        JSONObject c=config.optJSONObject(t);if(c==null||c.optString("game").isEmpty()){openGames(t);return;}
        try{selectedGame=GameRules.copy(c);selectedGame.put("id",c.optString("game"));type=t;regionBack="home";page="regions";showPage();}catch(Exception e){failure(e);}
    }
    private void renderGames(){
        body.removeAllViews();LinearLayout searchBox=ui.column();searchBox.setPadding(ui.dp(20),ui.dp(4),ui.dp(20),ui.dp(12));
        EditText search=ui.search("搜索中文、英文或拼音");search.setText(query);searchBox.addView(search);body.addView(searchBox);
        ListView list=new ListView(this);list.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));list.setDividerHeight(ui.dp(8));list.setClipToPadding(false);list.setPadding(ui.dp(20),0,ui.dp(20),ui.dp(16));list.setFastScrollEnabled(true);
        gameAdapter=new GameAdapter();list.setAdapter(gameAdapter);body.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        list.setOnItemClickListener((parent,view,position,id)->{selectedGame=gameAdapter.items.get(position);regionBack="games";page="regions";showPage();});
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
        app.network.execute(()->{
            List<JSONObject> items=GameRules.games(source,needle);
            boolean noMatches=items.isEmpty()&&!needle.trim().isEmpty();
            if(noMatches){JSONObject generic=genericGame(platform);if(generic!=null)items.add(generic);}
            if(needle.trim().isEmpty()){
                List<String> priority=downloadGames(platform);
                Collections.sort(items,(a,b)->{
                    int ap=priority.indexOf(a.optString("id")),bp=priority.indexOf(b.optString("id"));
                    if(ap>=0||bp>=0)return Integer.compare(ap<0?999:ap,bp<0?999:bp);
                    return a.optString("pname",a.optString("name")).compareToIgnoreCase(b.optString("pname",b.optString("name")));
                });
            }
            handler.post(()->{if(destroyed||gameAdapter!=adapter||adapter.revision.get()!=revision)return;
                adapter.items=items;adapter.noMatches=noMatches;adapter.notifyDataSetChanged();
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
        boolean noMatches;JSONArray source=new JSONArray();List<JSONObject> items=new ArrayList<>();final AtomicInteger revision=new AtomicInteger();
        @Override public int getCount(){return items.size();}
        @Override public JSONObject getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View convert,ViewGroup parent){
            LinearLayout row;TextView name,subtitle,badge;
            if(convert==null){
                row=ui.row();row.setPadding(ui.dp(14),ui.dp(12),ui.dp(14),ui.dp(12));row.setMinimumHeight(ui.dp(76));row.setBackground(ui.states(ui.surface,ui.tint));
                badge=ui.label("",15,ui.accent,true);badge.setGravity(Gravity.CENTER);badge.setBackground(ui.shape(ui.tint,12));row.addView(badge,new LinearLayout.LayoutParams(ui.dp(44),ui.dp(44)));
                LinearLayout labels=ui.column();labels.setPadding(ui.dp(14),0,ui.dp(8),0);name=ui.label("",16,ui.text,true);subtitle=ui.label("",12,ui.muted,false);ui.add(labels,name,0);ui.add(labels,subtitle,6);
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));row.setTag(new TextView[]{name,subtitle,badge});
            }else row=(LinearLayout)convert;
            TextView[] views=(TextView[])row.getTag();JSONObject game=items.get(position);String id=game.optString("id");String title=game.optString("name");
            views[0].setText(title);String tags=game.optString("tag");views[1].setText(noMatches?"通用加速":downloadGames(type).contains(id)?"下载专用":tags.isEmpty()?GameRules.typeName(type):tags);
            JSONObject c=config.optJSONObject(type);boolean chosen=c!=null&&id.equals(c.optString("game"));
            views[2].setText(chosen?"已选":title.isEmpty()?"游":title.substring(0,Math.min(title.length(),2)));row.setSelected(chosen);return row;
        }
        @Override public Object[] getSections(){return new String[]{"A","B","C","D","E","F","G","H","I","J","K","L","M","N","O","P","Q","R","S","T","U","V","W","X","Y","Z"};}
        @Override public int getPositionForSection(int section){String letter=(String)getSections()[Math.max(0,Math.min(25,section))];for(int i=0;i<items.size();i++)if(items.get(i).optString("pname").compareToIgnoreCase(letter)>=0)return i;return Math.max(0,items.size()-1);}
        @Override public int getSectionForPosition(int position){if(items.isEmpty())return 0;String p=items.get(Math.min(position,items.size()-1)).optString("pname").toUpperCase(Locale.ROOT);return p.isEmpty()?0:Math.max(0,Math.min(25,p.charAt(0)-'A'));}
    }
    private void renderRegions(){
        LinearLayout content=scrollBody();ui.add(content,ui.label(selectedGame.optString("name"),24,ui.text,true),4);
        ui.add(content,ui.label(GameRules.typeName(type)+" · 选择后保存",14,ui.muted,false),10);
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
        for(int i=0;i<regions.length();i++){
            JSONObject r=regions.optJSONObject(i);if(r==null)continue;String region=r.optString("region").toLowerCase(Locale.ROOT);
            boolean chosen=current!=null&&selectedGame.optString("id").equals(current.optString("game"))&&region.equalsIgnoreCase(current.optString("region"));
            Button b=ui.button(r.optString("name",GameRules.regionName(region))+(chosen?"  ·  当前":""),false,v->saveChoice(region,detail));
            b.setSelected(chosen);b.setEnabled(!app.commandBusy.get());ui.add(content,b,10);
        }
        String notice=detail.optString("notice");if(!notice.isEmpty())ui.add(content,ui.label(android.text.Html.fromHtml(notice).toString(),13,ui.muted,false),18);
    }
    private void saveChoice(String region,JSONObject detail){
        JSONObject game;try{game=GameRules.copy(selectedGame);if(detail.has("routeOption"))game.put("routeOption",detail.get("routeOption"));}catch(Exception e){failure(e);return;}
        boolean active=false;for(JSONObject d:devices())if(d.optBoolean("acc_status")&&type.equals(GameRules.effectiveType(d,config)))active=true;
        String platform=type;boolean consent=active;
        Runnable save=()->command(()->{
            JSONObject latest=repo.fetchConfig();JSONObject snapshot=Repository.object(repo.fetch("devices","router/device"));
            JSONArray list=snapshot.optJSONArray("device_list");
            if(list!=null)for(int i=0;i<list.length();i++){
                JSONObject d=list.getJSONObject(i);if(d.optBoolean("acc_status")&&platform.equals(GameRules.effectiveType(d,latest))){
                    if(!consent)throw new ApiClient.Failure(-1,"该设备类型刚开始加速，请返回刷新并确认停止后修改");
                    stopAndWait(d);
                }
            }
            return repo.saveGame(platform,game,region);
        },result->{config=result;toast("游戏与区服已保存");page="home";showPage();refreshDevices(true);prefetch();});
        if(active)confirm("修改游戏与区服","此设备类型正在加速。保存将先停止加速，可能使游戏断线。","停止并保存",save);else save.run();
    }
    private <T> void command(Job<T> job,Done<T> done){ command(job,done,null,false); }
    private <T> void command(Job<T> job,Done<T> done,JSONObject target,boolean starting){
        if(!app.pendingIp.isEmpty()){toast("请等待路由器完成当前操作");return;}
        if(!app.commandBusy.compareAndSet(false,true)){toast("操作正在进行，请稍候");return;}
        if(target!=null){app.workingIp=target.optString("ip");app.workingStart=starting;}
        screenStatus.setText("正在处理，请稍候…");if("home".equals(page))renderHome(true);
        app.network.execute(()->{
            try{T result=job.run();new Handler(Looper.getMainLooper()).post(()->{
                app.commandBusy.set(false);
                if(!destroyed)done.accept(result);
                app.workingIp="";
                if(!destroyed&&"home".equals(page))renderHome(true);
            });}
            catch(Exception e){new Handler(Looper.getMainLooper()).post(()->{
                app.commandBusy.set(false);app.workingIp="";
                if(!destroyed)failure(e);
            });}
        });
    }
    private void start(JSONObject device){
        String t=GameRules.effectiveType(device,config);
        if(!GameRules.supported(t,deviceData.optString("version"))){toast("当前路由插件暂不支持该设备类型");return;}
        JSONObject c=config.optJSONObject(t);if(c==null||c.optString("game").isEmpty()){openGames(t);return;}
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
                    if(!consent)throw new ApiClient.Failure(-1,"其他设备刚开始加速，请刷新后确认切换");stopAndWait(d);
                }
            }
            repo.message("start_speed",GameRules.startMessage(target,choice));return latest;
        },value->{config=value;pending(device,true);},device,true);
        if(other)confirm("切换加速设备","其他设备正在加速，切换会先停止其加速，可能使游戏断线。","停止并切换",start);else start.run();
    }
    private void stop(JSONObject device){command(()->{
        JSONArray latest=Repository.object(repo.fetch("devices","router/device")).optJSONArray("device_list");boolean found=false;
        if(latest!=null)for(int i=0;i<latest.length();i++){JSONObject d=latest.getJSONObject(i);if(d.optString("mac").equals(device.optString("mac"))&&d.optString("ip").equals(device.optString("ip")))found=true;}
        if(!found)throw new ApiClient.Failure(-1,"设备地址已变化，请刷新后重试");
        repo.message("stop_speed",new JSONObject().put("ip",device.getString("ip")));return true;},v->pending(device,false),device,false);}
    private void pending(JSONObject device,boolean start){app.pendingIp=device.optString("ip");app.pendingStart=start;app.pendingUntil=android.os.SystemClock.elapsedRealtime()+35000;toast("指令已发送，等待路由器确认");if("home".equals(page))renderHome(true);refreshDevices(false);handler.removeCallbacks(poll);if(resumed)handler.postDelayed(poll,1000);}
    private void checkPending(){
        if(app.pendingIp.isEmpty())return;
        JSONObject ack=deviceData.optJSONObject("acc_status");
        if(ack!=null&&ack.has("success")&&!ack.optBoolean("success")){
            String msg=ack.optString("msg_cn");if(!msg.isEmpty()){app.pendingIp="";toast(msg);return;}
        }
        for(JSONObject d:devices())if(app.pendingIp.equals(d.optString("ip"))&&d.optBoolean("acc_status")==app.pendingStart){toast(app.pendingStart?"加速已启动":"加速已停止");app.pendingIp="";return;}
        if(android.os.SystemClock.elapsedRealtime()>app.pendingUntil){app.pendingIp="";toast("路由器确认超时，请刷新查看实际状态");}
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
        if(!"mine".equals(page))return;screenStatus.setText("游帮帮路由助手 · 原生版 0.2.1");LinearLayout content=scrollBody();
        JSONObject user=Repository.object(repo.cached("user"));LinearLayout profile=ui.card();
        ui.add(profile,ui.label(user.optString("nick_name","游帮帮账号"),23,ui.text,true),0);
        JSONArray goods=Repository.array(repo.cached("goods"));String member="尚未开通全平台会员";
        long now=System.currentTimeMillis()/1000;for(int i=0;i<goods.length();i++){
            JSONObject item=goods.optJSONObject(i);if(item!=null&&item.optInt("spu")==3){long end=item.optLong("end_time");member=end>now?"全平台会员 · "+new SimpleDateFormat("yyyy.MM.dd",Locale.CHINA).format(new Date(end*1000))+" 到期":"全平台会员已到期";}
        }
        ui.add(profile,ui.label(member,14,ui.muted,false),12);
        ui.add(profile,ui.button("续费 / 开通",false,v->new AlertDialog.Builder(this).setTitle("全平台会员").setMessage("与官方插件一致，开通或续费需在游帮帮官方客户端完成。请选择支持路由器加速的全平台会员。").setNegativeButton("关闭",null).setPositiveButton("打开官方网站",(d,w)->external("https://ybb.baidu.com/")).show()),18);
        ui.add(content,profile,0);
        JSONArray offers=Repository.array(repo.cached("offers"));
        for(int i=0;i<offers.length();i++){
            JSONObject offer=offers.optJSONObject(i);if(offer==null||!offer.has("id"))continue;
            String id=offer.optString("id");ui.add(content,ui.button("领取账号可用的会员权益",false,v->confirm("领取会员权益","以官方接口返回的当前活动规则和领取结果为准。","确认领取",()->command(()->repo.api.request("PATCH","client/pop/"+Uri.encode(id),null),result->{toast("会员权益已领取");loadMine(true);}))),12);
        }
        LinearLayout settings=ui.card();ui.add(settings,ui.button("路由器管理",false,v->navigate("router")),0);
        ui.add(settings,ui.button("服务公告",false,v->navigate("notices")),8);
        ui.add(settings,ui.button("帮助与反馈",false,v->help()),8);
        ui.add(settings,ui.label("路由插件版本  "+deviceData.optString("version","未知"),13,ui.muted,false),20);ui.add(content,settings,18);
        ui.add(content,ui.button("退出登录",false,v->confirm("退出登录","退出此应用的账号。路由器绑定关系将保留。","退出登录",()->command(()->{
            repo.api.request("GET","user/logout",null);repo.clear();return true;
        },value->{CookieManagerCompat.clear();config=new JSONObject();deviceData=new JSONObject();routers=new JSONArray();loginRequired=true;page="home";showPage();}))),18);
        ui.add(content,ui.label("非官方客户端 · 管理功能由游帮帮服务提供\nCopyright (c) 2026 dongx · AGPL-3.0-only",12,ui.muted,false),24);
    }
    private void renderRouter(){
        screenStatus.setText("百度账号与路由器的绑定关系");LinearLayout content=scrollBody();LinearLayout card=ui.card();
        ui.add(card,ui.label(routerName(),23,ui.text,true),0);JSONObject router=routers.optJSONObject(0);
        ui.add(card,ui.label(router==null?"尚未绑定路由器":"型号  "+router.optString("model")+"\n名称  "+router.optString("name"),14,ui.muted,false),14);
        ui.add(card,ui.label("一个账号仅可绑定一台路由器。首次绑定或更换路由器，需要在小米 WiFi 的游帮帮插件中完成。",14,ui.muted,false),20);ui.add(content,card,0);
        if(router!=null)ui.add(content,ui.button("解绑路由器",false,v->confirm("解绑路由器","解绑后将无法继续管理此路由器的加速，需要重新通过小米 WiFi 绑定。","确认解绑",()->command(()->{
            repo.api.request("DELETE","router/unbind/"+Uri.encode(router.getString("id")),null);
            repo.api.request("GET","user/logout",null);repo.clear();return true;
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
    private void back(){switch(page){case "home":super.onBackPressed();return;case "mine":navigate("home");return;case "games":query="";navigate("types");return;case "regions":navigate(regionBack);return;case "router":case "notices":navigate("mine");return;default:navigate("home");}}
    @Override public void onBackPressed(){back();}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("page",page);state.putString("type",type);state.putString("query",query);state.putString("deviceQuery",deviceQuery);state.putString("regionBack",regionBack);if(selectedGame!=null)state.putString("selectedGame",selectedGame.toString());super.onSaveInstanceState(state);}
    @Override protected void onResume(){super.onResume();resumed=true;handler.removeCallbacks(poll);handler.postDelayed(poll,1500);handler.removeCallbacks(ticker);handler.post(ticker);}
    @Override protected void onPause(){resumed=false;handler.removeCallbacks(poll);handler.removeCallbacks(ticker);super.onPause();}
    @Override protected void onDestroy(){destroyed=true;handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private static final class CookieManagerCompat {
        static void clear(){android.webkit.CookieManager.getInstance().removeAllCookies(null);android.webkit.CookieManager.getInstance().flush();android.webkit.WebStorage.getInstance().deleteAllData();}
    }
}
