package com.suancaiyu.mosilalight;

import android.Manifest;
import android.app.*;
import android.bluetooth.BluetoothAdapter;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public final class MainActivity extends Activity implements BleController.Listener {
    static final int BG=0xff0b0f14, CARD=0xff151c24, BORDER=0xff27313c, TEXT=0xffeef2f5, MUTED=0xff8f9ba8, ACCENT=0xffc7f86f;
    final Handler main = new Handler(Looper.getMainLooper());
    BleController ble;
    MdkUi mdk;
    FirstUseGuide firstUse;
    AlertDialog freeNotice,infoDialog;
    boolean freeNoticeShown;
    static final String FREE_NOTICE_TEXT="此软件免费开源，如果你是通过付费获取的恭喜你被骗了";
    static final String PROJECT_URL="https://github.com/SuancaiyuPro/mosila-console";
    boolean needsOnboarding(){return firstUse!=null&&firstUse.required;}
    SharedPreferences preferences,consolePreferences;
    LampBrand activeBrand=LampBrand.MOSILA;
    boolean brandPickerOpen;
    TextView brandTag,brandArrow;
    LinearLayout brandEntry;
    LinearLayout root, header, body, navigation, deviceList, memoryPanel;
    ScrollView scroll;
    TextView connection, subtitle, colorValue, valueLabel, scanButton, deviceSummary, logView;
    LampView lamp;
    ColorWheel wheel;
    int startupColorSetting=0xff70d8ef,startupSetting=0,pointsSetting=50,brakeSetting=-1,turnSetting=-1,selfCheckSetting=-1,lineOrderSetting=-1;
    String activeOutput="",displayedAddress="";
    boolean lastSubmissionQueued;
    String draftAddress="",draftReturnAddress="";
    final ArrayList<byte[]> draftEdits=new ArrayList<>();
    final HashMap<String,Boolean> draftSelections=new HashMap<>();
    LinearLayout draftBar;
    TextView draftStatus;
    boolean isOfflineDraft(){return activeBrand==LampBrand.MODENGKE&&mdk!=null?!mdk.draft.isEmpty():!draftAddress.isEmpty();}
    int tab, color=0xff70d8ef, intensity=Protocol.DEFAULT_COLOR_INTENSITY, mode=0, speed=5, level=9;
    boolean power=true, routeA=true, routeB=true, modeA=true, modeB=true, paused;
    final ArrayList<String> modes = new ArrayList<>();
    ArrayDeque<String> logs = new ArrayDeque<>();
    final EnumMap<LampBrand,ArrayDeque<String>> brandLogs=new EnumMap<>(LampBrand.class);
    long lastPreviewSend;
    int heldDirection=-1;
    final Runnable holdTask = new Runnable() {
        public void run() {
            if (heldDirection < 0 || paused || ble.readyCount()==0) { stopHold(); return; }
            ble.send(Protocol.frame(7,heldDirection),true,false);
            main.postDelayed(this,125);
        }
    };
    final Runnable deviceRender = () -> { if(tab==3&&!brandPickerOpen&&activeBrand.supported) updateDevices(); };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        freeNoticeShown=saved!=null&&saved.getBoolean("freeNoticeShown",false)&&!saved.getBoolean("freeNoticeOpen",false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT>=29) getWindow().setNavigationBarContrastEnforced(false);
        firstUse=new FirstUseGuide(this);
        consolePreferences=getSharedPreferences("console",MODE_PRIVATE);
        activeBrand=LampBrand.fromId(consolePreferences.getString("brand","mosila"));
        brandLogs.put(activeBrand,logs);
        preferences=getSharedPreferences("light",MODE_PRIVATE);
        color=preferences.getInt("color",color); intensity=ColorSettings.loadIntensity(preferences);
        mode=preferences.getInt("mode",0); speed=preferences.getInt("speed",5);
        power=preferences.getBoolean("power",true);modeA=preferences.getBoolean("modeA",true);modeB=preferences.getBoolean("modeB",true);
        level=preferences.getInt("level",9);startupColorSetting=preferences.getInt("startupColorSetting",color);startupSetting=preferences.getInt("startupSetting",0);pointsSetting=preferences.getInt("points",50);
        brakeSetting=preferences.getInt("brakeSetting",-1);turnSetting=preferences.getInt("turnSetting",-1);selfCheckSetting=preferences.getInt("selfCheckSetting",-1);lineOrderSetting=preferences.getInt("lineOrderSetting",-1);activeOutput=preferences.getString("activeOutput","");
        routeA=preferences.getBoolean("routeA",true); routeB=preferences.getBoolean("routeB",true);
        try(InputStream in=getAssets().open("modes.json")) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[4096]; int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);
            JSONArray a=new JSONArray(out.toString("UTF-8")); for(int i=0;i<a.length();i++) modes.add(a.getString(i));
        } catch(Exception e) { modes.clear();for(int i=0;i<213;i++)modes.add(i==0?"自动循环":"灯效 "+(i+1)); }
        mode=Math.max(0,Math.min(mode,modes.size()-1));
        ble=new BleController(this,this);ble.setBrand(activeBrand);ble.setProtocolEnabled(activeBrand.supported&&!needsOnboarding());mdk=new MdkUi(this);if(activeBrand==LampBrand.MODENGKE)mdk.enter();
        String storedAddress=preferences.getString("displayedAddress","");
        if(!needsOnboarding()&&activeBrand==LampBrand.MOSILA&&ble.profiles.known(storedAddress))loadProfileAddress(storedAddress);
        root=column(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom()); return insets;
        });
        setContentView(root); root.requestApplyInsets();
        buildShell(); showTab(saved==null?consolePreferences.getInt(activeBrand==LampBrand.MOSILA?"mosilaTab":"mdkTab",0):saved.getInt("tab",0));
        if(saved!=null&&saved.getBoolean("brandPicker",false))showBrandPicker();
    }
    void buildShell() {
        header=row(); header.setPadding(dp(22),dp(16),dp(20),dp(14)); header.setGravity(Gravity.CENTER_VERTICAL);
        brandEntry=column();brandEntry.setContentDescription("选择大灯品牌");brandEntry.setFocusable(true);brandEntry.setOnClickListener(v->showBrandPicker());
        LinearLayout wordLine=row();wordLine.setGravity(Gravity.CENTER_VERTICAL);
        TextView word=text("console",25,TEXT,true);word.setLetterSpacing(.01f);wordLine.addView(word);
        TextView arrow=text("⌄",18,MUTED,false);brandArrow=arrow;arrow.setPadding(dp(9),0,0,0);wordLine.addView(arrow);brandEntry.addView(wordLine);
        brandTag=text(activeBrand.title+" · 灯光控制",10,MUTED,true);brandTag.setLetterSpacing(.08f);brandEntry.addView(brandTag);
        header.addView(brandEntry,new LinearLayout.LayoutParams(0,-2,1));
        connection=button("连接设备",false,v->{if(brandPickerOpen)showTab(tab);else if(activeBrand.supported)showTab(3);else showBrandPicker();}); connection.setTextSize(12);
        header.addView(connection,new LinearLayout.LayoutParams(-2,dp(42))); root.addView(header);
        draftBar=column();draftBar.setPadding(dp(18),dp(10),dp(18),dp(10));draftBar.setBackgroundColor(CARD);root.addView(draftBar);renderDraftBar();
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body=column(); body.setPadding(dp(20),dp(8),dp(20),dp(24)); scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        navigation=row(); navigation.setPadding(dp(10),dp(8),dp(10),dp(8)); navigation.setBackgroundColor(0xff10161d);
        root.addView(navigation,new LinearLayout.LayoutParams(-1,-2));
        updateHeader();
    }
    void showTab(int next) {
        if(needsOnboarding()){showOnboarding();return;}
        brandPickerOpen=false;
        stopHold(); tab=next; lamp=null; wheel=null; colorValue=null; deviceList=null; logView=null;
        body.removeAllViews(); scroll.scrollTo(0,0);
        if(!activeBrand.supported){tab=0;navigation.setVisibility(View.GONE);buildPendingBrand();updateHeader();return;}
        navigation.setVisibility(View.VISIBLE);consolePreferences.edit().putInt(activeBrand==LampBrand.MOSILA?"mosilaTab":"mdkTab",tab).apply();
        if(activeBrand==LampBrand.MODENGKE)mdk.build(tab);else switch(tab) { case 0: buildColor(); break; case 1: buildModes(); break; case 2: buildSettings(); break; default: if(isOfflineDraft())pageTitle("离线草稿待确认","请先确认保存或取消，再连接或选择其他设备。");else buildDevices(); }
        navigation.removeAllViews(); String[] names={"调色","灯效","设置","设备"};
        for(int i=0;i<4;i++) {
            final int index=i; LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setPadding(dp(4),dp(7),dp(4),dp(5));
            if(tab==i)item.setBackground(shape(0xff243022,16,0));
            IconView icon=new IconView(i,tab==i?ACCENT:MUTED); item.addView(icon,new LinearLayout.LayoutParams(dp(24),dp(24)));
            TextView title=text(names[i],11,tab==i?ACCENT:MUTED,tab==i); title.setPadding(0,dp(5),0,0); item.addView(title);
            item.setContentDescription(names[i]); item.setOnClickListener(v->showTab(index)); item.setFocusable(true);
            LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1); ip.setMargins(dp(3),0,dp(3),0); navigation.addView(item,ip);
        }
    }
    void showBrandPicker(){
        if(needsOnboarding()){showOnboarding();return;}
        if(isOfflineDraft()){toast("请先确认或取消离线修改");return;}
        stopHold();if(modePicker!=null&&modePicker.isShowing())modePicker.dismiss();
        brandPickerOpen=true;lamp=null;wheel=null;colorValue=null;deviceList=null;logView=null;
        body.removeAllViews();scroll.scrollTo(0,0);navigation.setVisibility(View.GONE);
        pageTitle("选择大灯品牌","为你的大灯，打开对应的控制台");
        for(LampBrand brand:LampBrand.values()){
            LinearLayout panel=card();panel.setContentDescription("品牌 "+brand.title);panel.setOnClickListener(v->selectBrand(brand));
            LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);
            TextView mark=text(brand==LampBrand.MOSILA?"M":"灯",25,BG,true);mark.setGravity(Gravity.CENTER);mark.setBackground(shape(brand==LampBrand.MOSILA?ACCENT:0xffffc180,16,0));
            line.addView(mark,new LinearLayout.LayoutParams(dp(54),dp(54)));
            LinearLayout description=column();description.setPadding(dp(15),0,0,0);
            description.addView(text(brand.title,23,TEXT,true));
            description.addView(text(brand==LampBrand.MOSILA?"调色 · 213 种灯效 · 灯光设置":"九号 N/F/M 大灯 3.0 · 三路独立控制",11,MUTED,false));
            line.addView(description,new LinearLayout.LayoutParams(0,-2,1));panel.addView(line);
            TextView status=text(activeBrand==brand?"当前品牌":brand.supported?"已适配":"待适配",11,brand.supported?ACCENT:0xffffc180,true);status.setPadding(0,dp(14),0,dp(12));panel.addView(status);
            panel.addView(button("进入 "+brand.title+"  →",activeBrand==brand,v->selectBrand(brand)));addCard(panel);
        }
        body.addView(button("返回当前品牌",false,v->showTab(tab)));
        foot("不同品牌使用各自的控制界面与设备设置。");updateHeader();
    }
    void selectBrand(LampBrand brand){
        if(needsOnboarding()){firstUse.select(brand);showOnboarding();return;}
        int mosilaTab=consolePreferences.getInt("mosilaTab",0);
        if(isOfflineDraft()){toast("请先确认或取消离线修改");return;}
        if(activeBrand!=brand){
            save();stopHold();if(mdk!=null)mdk.pause();main.removeCallbacks(startupScan);ble.setProtocolEnabled(false);
            pendingScanBrand=null;pendingRememberedScan=false;waitingLocation=false;
            logView=null;brandLogs.put(activeBrand,logs);logs=brandLogs.computeIfAbsent(brand,k->new ArrayDeque<>());
            activeBrand=brand;brandPickerOpen=true;consolePreferences.edit().putString("brand",brand.id).apply();
            ble.setBrand(brand);ble.setProtocolEnabled(brand.supported);startupScanChecked=false;
            if(brand==LampBrand.MODENGKE)mdk.enter();
            if(brand==LampBrand.MOSILA){String address=preferences.getString("displayedAddress","");if(ble.profiles.known(address))loadProfileAddress(address);}
        }
        showTab(brand==LampBrand.MOSILA?mosilaTab:consolePreferences.getInt("mdkTab",0));updateHeader();
        if(brand.supported&&!paused)main.post(startupScan);
    }
    void showOnboarding(){
        stopHold();brandPickerOpen=false;lamp=null;wheel=null;colorValue=null;deviceList=null;logView=null;
        body.removeAllViews();scroll.scrollTo(0,0);navigation.setVisibility(View.GONE);draftBar.setVisibility(View.GONE);
        LinearLayout progress=row();progress.setPadding(0,dp(8),0,dp(18));
        for(int i=1;i<=3;i++){View dot=new View(this);dot.setBackground(shape(i<=firstUse.step?ACCENT:BORDER,4,0));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(4),1);p.setMargins(0,0,dp(7),0);progress.addView(dot,p);}body.addView(progress);
        if(firstUse.step==1){
            pageTitle("先选择你的大灯品牌","欢迎使用 mosila console · 请选择实际使用的品牌");
            for(LampBrand brand:LampBrand.values()){
                boolean chosen=firstUse.choice==brand;
                LinearLayout panel=card();panel.setContentDescription("首次使用选择 "+brand.title);panel.setOnClickListener(v->selectBrand(brand));
                LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);
                TextView badge=text(brand==LampBrand.MOSILA?"M":"灯",26,BG,true);badge.setGravity(Gravity.CENTER);badge.setBackground(shape(brand==LampBrand.MOSILA?ACCENT:0xffffc180,16,0));title.addView(badge,new LinearLayout.LayoutParams(dp(50),dp(50)));
                LinearLayout description=column();description.setPadding(dp(14),0,0,0);description.addView(text(brand.title,24,TEXT,true));description.addView(text(brand==LampBrand.MOSILA?"MOSILA 大灯":"九号 N/F/M 大灯 3.0",12,MUTED,false));title.addView(description,new LinearLayout.LayoutParams(0,-2,1));panel.addView(title);
                panel.addView(sectionNote(brand==LampBrand.MOSILA?"独立调色 · 213 种灯效 · A/B 路控制":"一字日行灯 · 侧翼灯 · 恶魔眼\n当前仅适配九号 N/F/M 大灯 3.0"));
                panel.addView(button("选择 "+brand.title+"  →",chosen,v->selectBrand(brand)));addCard(panel);
            }
            foot("不同品牌的蓝牙协议与可调参数不同。完成引导后，仍可点击左上角 console 切换品牌。");
        }else if(firstUse.step==2){
            pageTitle("先连接你的大灯",firstUse.choice.title+" · "+(firstUse.choice==LampBrand.MODENGKE?"九号 N/F/M 大灯 3.0":"MOSILA 大灯"));
            LinearLayout tips=card();guideTip(tips,"1","打开大灯电源","让手机靠近灯具，保持蓝牙开启。");guideTip(tips,"2","断开原版应用","原版应用连接占用蓝牙时，请先断开，再回到这里扫描。");guideTip(tips,"3","扫描并允许权限","进入设备页后点击“扫描附近设备”，按系统提示允许“附近的设备”权限，再选择大灯连接。");addCard(tips);
            body.addView(button("下一步：了解参数保存",true,v->{firstUse.move(3);showOnboarding();}));
            TextView back=button("上一步：重新选择品牌",false,v->{firstUse.move(1);showOnboarding();});back.setPadding(dp(15),dp(14),dp(15),dp(14));LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);body.addView(back,bp);
            foot(firstUse.choice==LampBrand.MODENGKE?"连接后会核对灯具型号。此入口仅支持九号 N/F/M 大灯 3.0。":"连接后即可调色、选择灯效和设置灯光参数。");
        }else{
            pageTitle("设置会为你记住","最后一步 · 了解自动保存与离线编辑");
            LinearLayout tips=card();guideTip(tips,"1","连接时自动保存","调色、灯效和设置会按设备分别保存，下次连接自动逐条恢复。");guideTip(tips,"2","离线修改需要确认","先在设备页点击“离线编辑已保存参数”。顶部显示“编辑中”，确认才保存，取消会放弃本次修改。");guideTip(tips,"3","设备参数可以删除","在设备页点击“删除已保存参数”。连接记忆可通过“清除连接记忆”单独管理。");addCard(tips);
            body.addView(button("进入设备页，开始使用",true,v->finishOnboarding()));
            LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,-2);bp.topMargin=dp(10);body.addView(button("上一步",false,v->{firstUse.move(2);showOnboarding();}),bp);
            foot("当前品牌："+firstUse.choice.title+" · 后续可从左上角 console 切换。");
        }
        updateHeader();
    }
    void guideTip(LinearLayout panel,String number,String title,String note){
        LinearLayout line=row();line.setGravity(Gravity.TOP);line.setPadding(0,dp(6),0,dp(12));TextView badge=text(number,14,ACCENT,true);badge.setGravity(Gravity.CENTER);badge.setBackground(shape(0xff243022,12,0));line.addView(badge,new LinearLayout.LayoutParams(dp(32),dp(32)));
        LinearLayout description=column();description.setPadding(dp(12),0,0,0);description.addView(text(title,17,TEXT,true));TextView detail=sectionNote(note);detail.setPadding(0,dp(6),0,dp(8));description.addView(detail);line.addView(description,new LinearLayout.LayoutParams(0,-2,1));panel.addView(line);
    }
    void finishOnboarding(){
        if(!needsOnboarding()||!firstUse.finish())return;
        LampBrand chosen=firstUse.choice;selectBrand(chosen);ble.setProtocolEnabled(chosen.supported);showTab(3);updateHeader();showFreeNoticeIfNeeded();
    }
    void showFreeNoticeIfNeeded(){
        if(needsOnboarding()||paused||isFinishing()||isDestroyed()||freeNoticeShown||consolePreferences.getBoolean("hide_free_notice",false))return;
        freeNoticeShown=true;
        LinearLayout content=column();content.setPadding(dp(24),dp(24),dp(24),dp(8));
        content.addView(text("FREE & OPEN SOURCE",10,ACCENT,true));
        TextView title=text("免费开源说明",23,TEXT,true);title.setPadding(0,dp(10),0,dp(14));content.addView(title);
        TextView message=text(FREE_NOTICE_TEXT,16,TEXT,false);message.setLineSpacing(dp(6),1);content.addView(message);
        CheckBox remember=new CheckBox(this);remember.setText("不再提醒");remember.setTextColor(MUTED);remember.setTextSize(14);remember.setPadding(0,dp(16),0,dp(6));remember.setButtonTintList(ColorStateList.valueOf(ACCENT));
        remember.setOnCheckedChangeListener((view,checked)->consolePreferences.edit().putBoolean("hide_free_notice",checked).apply());content.addView(remember);
        freeNotice=new AlertDialog.Builder(this).setView(content).setPositiveButton("我知道了",null).create();
        AlertDialog shown=freeNotice;shown.setOnShowListener(dialog->{shown.getWindow().setBackgroundDrawable(shape(CARD,24,BORDER));shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);});
        freeNotice.setOnDismissListener(dialog->{freeNotice=null;if(!paused)main.post(startupScan);});freeNotice.show();
    }
    void buildPendingBrand(){
        pageTitle("品牌适配中","请选择已支持的品牌与型号");
        body.addView(button("选择其他品牌",true,v->showBrandPicker()));
    }
    void pageTitle(String title,String note) {
        TextView h=text(title,30,TEXT,true); h.setPadding(0,dp(5),0,dp(5)); body.addView(h);
        subtitle=text(note,12,MUTED,false); subtitle.setPadding(0,0,0,dp(21)); subtitle.setLineSpacing(dp(3),1); body.addView(subtitle);
    }
    void buildColor() {
        pageTitle("让光，有你的颜色",isOfflineDraft()?"离线草稿，确认后才保存":ble.readyCount()>0?"已连接 · 选择颜色即可控制大灯":"连接大灯，或前往设备页离线编辑");
        LinearLayout hero=card();
        LinearLayout top=row(); top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout info=column(); info.addView(text("LIVE COLOR",10,MUTED,true));
        info.addView(text("三透镜氛围灯",18,TEXT,true)); top.addView(info,new LinearLayout.LayoutParams(0,-2,1));
        TextView toggle=button(power?"关闭灯光":"开启灯光",false,v->{
            boolean next=!power;
            if(send(Protocol.power(next,color,intensity),false,true)) { power=next;save(); ((TextView)v).setText(power?"关闭灯光":"开启灯光"); lamp.invalidate(); }
        }); top.addView(toggle); hero.addView(top);
        lamp=new LampView(); hero.addView(lamp,new LinearLayout.LayoutParams(-1,dp(156)));
        LinearLayout routes=row();
        TextView a=routeButton(true), b=routeButton(false); addWeight(routes,a); addWeight(routes,b); hero.addView(routes); addCard(hero);
        LinearLayout colorCard=card();
        LinearLayout title=row(); title.setGravity(Gravity.CENTER_VERTICAL); title.addView(text("色彩",16,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        colorValue=text(hexColor(),12,MUTED,true); colorValue.setTypeface(Typeface.MONOSPACE); colorValue.setOnClickListener(v->enterColor()); title.addView(colorValue); colorCard.addView(title);
        wheel=new ColorWheel(); colorCard.addView(wheel,new LinearLayout.LayoutParams(-1,dp(225)));
        LinearLayout swatches=row(); int[] colors={0xfffe5f6b,0xffffb45b,0xffc7f86f,0xff63dfcc,0xff71b8ff,0xffbb8afa};
        for(int c:colors) {
            TextView sw=text("",12,TEXT,false); sw.setBackground(shape(c,30,0)); sw.setContentDescription(String.format(Locale.ROOT,"颜色 #%06X",c&0xffffff));
            sw.setOnClickListener(v->{color=c; updateColor(); applyColor(true);});
            LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,dp(34),1); sp.setMargins(dp(5),dp(2),dp(5),dp(10)); swatches.addView(sw,sp);
        }
        colorCard.addView(swatches);
        addSlider(colorCard,"氛围灯强度",intensity-1,254,n->{intensity=n+1; if(lamp!=null)lamp.invalidate(); if(SystemClock.uptimeMillis()-lastPreviewSend>85){applyColor(false);lastPreviewSend=SystemClock.uptimeMillis();}},()->applyColor(true),n->Integer.toString(n+1));
        colorCard.addView(button(ble.readyCount()>0?"应用当前颜色":isOfflineDraft()?"暂存当前颜色":"连接大灯",true,v->{if(ble.readyCount()==0&&!isOfflineDraft())showTab(3);else applyColor(true);})); addCard(colorCard);
        foot(profileNote());
    }
    TextView routeButton(boolean a) {
        boolean on=a?routeA:routeB;
        return button((a?"A 路":"B 路")+(on?"  ·  已选":"  ·  关闭"),on,v->{
            boolean n=!(a?routeA:routeB);
            if(send(Protocol.frame(9,a?0:1,n?1:0),false,true)) {
                if(a) routeA=n; else routeB=n;
                save(); ((TextView)v).setText((a?"A 路":"B 路")+(n?"  ·  已选":"  ·  关闭")); styleButton((TextView)v,n); lamp.invalidate();
            }
        });
    }
    void updateColor() { if(colorValue!=null)colorValue.setText(hexColor()); if(wheel!=null)wheel.invalidate(); if(lamp!=null)lamp.invalidate(); }
    String hexColor(){return String.format(Locale.ROOT,"#%06X",color&0xffffff);}
    byte[] colorPacket(){return Protocol.rgb(color,intensity);}
    void applyColor(boolean explicit){ if(explicit)save(); send(colorPacket(),true,explicit); }
    void enterColor() {
        EditText edit=new EditText(this); edit.setSingleLine(true); edit.setText(hexColor()); edit.setSelectAllOnFocus(true); edit.setHint("#RRGGBB");
        LinearLayout container=column(); container.setPadding(dp(24),dp(8),dp(24),0); container.addView(edit);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("输入颜色").setView(container).setNegativeButton("取消",null).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            try { String s=edit.getText().toString().trim(); if(!s.startsWith("#"))s="#"+s; if(s.length()!=7)throw new IllegalArgumentException(); color=Color.parseColor(s);updateColor();applyColor(true);dialog.dismiss(); }
            catch(Exception ex){edit.setError("请输入 6 位颜色值，例如 #70D8EF");}
        })); dialog.show();
    }
    void buildModes() {
        pageTitle("每一程，都有氛围",modes.size()+" 种灯效 · 自由切换节奏");
        LinearLayout current=card(); current.addView(text("当前选择",11,MUTED,true));
        TextView name=text(String.format(Locale.ROOT,"%03d  %s",mode+1,modes.get(mode)),22,TEXT,true); name.setPadding(0,dp(10),0,dp(14)); current.addView(name);
        current.addView(button("浏览全部灯效  →",true,v->chooseMode())); addCard(current);
        TextView label=text("快速选择",16,TEXT,true); label.setPadding(0,dp(3),0,dp(12)); body.addView(label);
        int[] favorites={0,1,2,3,4,10};
        for(int i=0;i<favorites.length;i+=2){
            LinearLayout r=row(); for(int j=i;j<Math.min(i+2,favorites.length);j++){
                final int id=favorites[j]; TextView t=button(String.format(Locale.ROOT,"%02d  %s",id+1,modes.get(id)),mode==id,v->{mode=id;save();sendMode();showTab(1);}); t.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); t.setTextSize(13); addWeight(r,t);
            } LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.bottomMargin=dp(8);body.addView(r,rp);
        }
        LinearLayout settings=card();
        addSlider(settings,"变化速度",speed-1,9,n->speed=n+1,()->{save();sendMode();},n->Integer.toString(n+1));
        LinearLayout routes=row(); TextView a=button("A 路灯效",modeA,v->{modeA=!modeA;styleButton((TextView)v,modeA);sendMode();});
        TextView b=button("B 路灯效",modeB,v->{modeB=!modeB;styleButton((TextView)v,modeB);sendMode();}); addWeight(routes,a);addWeight(routes,b);settings.addView(routes);
        settings.addView(button("应用灯效",true,v->{save();sendMode();}));addCard(settings);
        foot(profileNote());
    }
    void sendMode(){send(Protocol.mode(mode,speed,modeA,modeB),true,true);}
    ModePicker modePicker;
    void chooseMode() {
        if(modePicker!=null && modePicker.isShowing())return;
        modePicker=new ModePicker(this);
        modePicker.setOnDismissListener(d->{if(!paused && tab==1)showTab(1);});
        modePicker.show();
    }
    String startupHint(){return (isOfflineDraft()?"修改暂存草稿，确认后保存":"修改时自动保存，连接时下发")+"；使用调色页的当前颜色\n当前开机颜色："+String.format(Locale.ROOT,"#%06X",startupColorSetting&0xffffff);}
    void buildSettings() {
        pageTitle("把细节，也调好","日常设置与大灯调节");
        LinearLayout effects=card(); effects.addView(text("车辆灯光效果",17,TEXT,true));
        effects.addView(sectionNote("刹车效果"));
        LinearLayout brake=row();
        addWeight(brake,button("常亮",brakeSetting==1,v->{if(send(Protocol.frame(5,1,0),false,true)){toast(settingFeedback());showTab(2);}}));
        addWeight(brake,button("爆闪",brakeSetting==0,v->{if(send(Protocol.frame(5,0,1),false,true)){toast(settingFeedback());showTab(2);}}));effects.addView(brake);
        effects.addView(sectionNote("转向效果"));LinearLayout turn=row();
        addWeight(turn,button("流水",turnSetting==1,v->{if(send(Protocol.frame(6,1,0),false,true)){toast(settingFeedback());showTab(2);}}));
        addWeight(turn,button("频闪",turnSetting==0,v->{if(send(Protocol.frame(6,0,1),false,true)){toast(settingFeedback());showTab(2);}}));effects.addView(turn);addCard(effects);
        LinearLayout height=card();height.addView(text("光高调节",17,TEXT,true));height.addView(sectionNote("按住调节，松开停止"));
        LinearLayout directions=row(); TextView d0=button("方向 0",false,null),d1=button("方向 1",false,null);
        holdButton(d0,0);holdButton(d1,1);addWeight(directions,d0);addWeight(directions,d1);height.addView(directions);
        addSlider(height,"位置数值",level,18,n->level=n,()->send(Protocol.frame(10,level),true,true));
        height.addView(text("首次请短按确认方向与实物的对应关系。",11,MUTED,false));addCard(height);
        LinearLayout startup=card();startup.addView(text("开机效果",17,TEXT,true));TextView startupNote=sectionNote(startupHint());startup.addView(startupNote);
        LinearLayout sr=row(); NumberPicker startupMode=new NumberPicker(this);startupMode.setMinValue(0);startupMode.setMaxValue(6);startupMode.setWrapSelectorWheel(false);startupMode.setValue(startupSetting);
        NumberPicker points=new NumberPicker(this);points.setMinValue(10);points.setMaxValue(100);points.setValue(pointsSetting);points.setWrapSelectorWheel(false);
        LinearLayout ml=column();ml.addView(text("模式 0–6",12,MUTED,false));ml.addView(startupMode);addWeight(sr,ml);
        LinearLayout pl=column();pl.addView(text("点数 10–100",12,MUTED,false));pl.addView(points);addWeight(sr,pl);startup.addView(sr);
        startupMode.setOnValueChangedListener((picker,oldValue,newValue)->{
            startupSetting=newValue;save();send(Protocol.startup(startupSetting,pointsSetting,color),true,false);startupNote.setText(startupHint());
        });
        points.setOnValueChangedListener((picker,oldValue,newValue)->{
            pointsSetting=newValue;save();send(Protocol.startup(startupSetting,pointsSetting,color),true,false);startupNote.setText(startupHint());
        });
        startup.addView(button("应用开机设置",true,v->{if(send(Protocol.startup(startupMode.getValue(),points.getValue(),color),false,true)){startupNote.setText(startupHint());toast(settingFeedback());}}));addCard(startup);
        LinearLayout misc=card(); misc.addView(text("其他设置",17,TEXT,true));
        misc.addView(button("RGB 线序："+(lineOrderSetting<1?"未设置":new String[]{"RGB","RBG","GRB","GBR","BRG","BGR"}[lineOrderSetting-1])+"  →",false,v->new AlertDialog.Builder(this).setTitle("RGB 线序").setItems(new String[]{"RGB","RBG","GRB","GBR","BRG","BGR"},(d,i)->{if(send(Protocol.lineOrder(i+1),false,true)){toast(settingFeedback());showTab(2);}}).setNegativeButton("取消",null).show()));
        misc.addView(button("自检："+(selfCheckSetting<0?"未设置":selfCheckSetting==1?"启用":"关闭")+"  →",false,v->new AlertDialog.Builder(this).setTitle("自检设置").setMessage("原应用提示：近光关闭时进行开关灯操作。请选择自检是否启用。").setPositiveButton("启用",(d,w)->{if(send(Protocol.frame(8,1),false,true))showTab(2);}).setNeutralButton("关闭自检",(d,w)->{if(send(Protocol.frame(8,0),false,true))showTab(2);}).setNegativeButton("取消",null).show()));addCard(misc);
        addAboutEntry();
    }
    void addAboutEntry(){
        LinearLayout about=card();about.addView(button("关于软件  →",false,v->showAboutSoftware()));addCard(about);
    }
    String appVersion(){try{String value=getPackageManager().getPackageInfo(getPackageName(),0).versionName;return value==null?"0.3.4":value;}catch(Exception e){return "0.3.4";}}
    LinearLayout infoContent(String title){
        LinearLayout content=column();content.setPadding(dp(22),dp(22),dp(22),dp(10));TextView name=text(title,24,TEXT,true);name.setPadding(0,0,0,dp(14));content.addView(name);return content;
    }
    void showAboutSoftware(){
        LinearLayout content=infoContent("关于软件");content.addView(text("mosila console",25,TEXT,true));content.addView(sectionNote("版本 "+appVersion()));
        content.addView(text("作者",17,TEXT,true));content.addView(sectionNote("ChatGPT、酸菜鱼"));
        content.addView(text("免费开源",17,ACCENT,true));content.addView(sectionNote(FREE_NOTICE_TEXT));
        content.addView(text("支持的设备",17,TEXT,true));content.addView(sectionNote("mosila 大灯\n魔灯客 · 九号 N/F/M 大灯 3.0"));
        content.addView(text("项目地址",17,TEXT,true));
        TextView project=sectionNote(PROJECT_URL);project.setTextColor(ACCENT);project.setPadding(0,dp(10),0,dp(14));project.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(PROJECT_URL)));}catch(ActivityNotFoundException e){toast("未找到可打开网页的应用");}});content.addView(project);
        showInfoDialog(content,"查看使用引导",()->showUsageHelp());
    }
    void showUsageHelp(){
        LinearLayout content=infoContent("使用引导");
        guideTip(content,"1","选择品牌","点击左上角 console，按灯具选择 mosila 或魔灯客。魔灯客当前仅适配九号 N/F/M 大灯 3.0。");
        guideTip(content,"2","连接大灯","打开大灯电源，断开原版应用，在设备页扫描并允许系统蓝牙权限，再选择设备连接。");
        guideTip(content,"3","调节与保存","连接后调色、选择灯效或设置参数会按设备自动保存，下次连接会逐条恢复。");
        guideTip(content,"4","离线编辑","在设备页明确点击离线编辑，顶部显示编辑中；确认后保存，取消则丢弃本次修改。");
        guideTip(content,"5","管理设备记录","删除已保存参数只删除该设备的本地设置；连接记忆可单独清除。");
        showInfoDialog(content,null,null);
    }
    void showInfoDialog(LinearLayout content,String secondary,Runnable action){
        ScrollView view=new ScrollView(this);view.setFillViewport(false);view.addView(content);
        LinearLayout container=column();container.addView(view,new LinearLayout.LayoutParams(-1,Math.min(dp(470),(int)(getResources().getDisplayMetrics().heightPixels*.62f))));
        AlertDialog.Builder builder=new AlertDialog.Builder(this).setView(container).setPositiveButton("关闭",null);
        if(secondary!=null)builder.setNeutralButton(secondary,(dialog,which)->action.run());
        AlertDialog shown=builder.create();infoDialog=shown;shown.setOnShowListener(dialog->{shown.getWindow().setBackgroundDrawable(shape(CARD,24,BORDER));shown.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(ACCENT);if(secondary!=null)shown.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(ACCENT);});shown.show();
    }
    void holdButton(TextView button,int direction) {
        button.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                if(ble.readyCount()==0){toast("请先连接并选择大灯");return true;}
                stopHold();heldDirection=direction;v.setPressed(true);main.post(holdTask);v.getParent().requestDisallowInterceptTouchEvent(true);return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_UP || e.getActionMasked()==MotionEvent.ACTION_CANCEL){v.setPressed(false);stopHold();v.getParent().requestDisallowInterceptTouchEvent(false);if(e.getActionMasked()==MotionEvent.ACTION_UP)v.performClick();return true;}
            return true;
        }); button.setOnClickListener(v->{});
    }
    void stopHold(){heldDirection=-1;main.removeCallbacks(holdTask);if(ble!=null)ble.cancelContinuous();}
    void buildDevices() {
        pageTitle("连接，光就到位","打开大灯电源，在附近发现并连接设备");
        LinearLayout scanner=card();deviceSummary=text("",15,TEXT,true);scanner.addView(deviceSummary);scanner.addView(sectionNote("按蓝牙地址单独保存设置；连接后自动恢复该设备已有参数，每条间隔至少 400 ms。"));
        scanButton=button("扫描附近设备",true,v->requestScan());scanner.addView(scanButton);
        memoryPanel=column();scanner.addView(memoryPanel);
        addCard(scanner);
        deviceList=column();body.addView(deviceList);
        LinearLayout diagnostics=card();LinearLayout title=row();title.setGravity(Gravity.CENTER_VERTICAL);title.addView(text("连接记录",15,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        title.addView(button("复制",false,v->{android.content.ClipboardManager cb=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("MOSILA BLE",logText()));toast("记录已复制");}));diagnostics.addView(title);
        logView=text(logText(),11,MUTED,false);logView.setTypeface(Typeface.MONOSPACE);logView.setTextIsSelectable(true);logView.setPadding(0,dp(12),0,0);diagnostics.addView(logView);addCard(diagnostics);
        foot("首次使用请允许“附近的设备”权限。\n未发现设备时，请关闭原版应用的蓝牙连接后重新扫描。");updateDevices();
    }
    void updateDevices() {
        if(activeBrand==LampBrand.MODENGKE){mdk.refreshDevices();return;}
        if(deviceList==null || tab!=3||brandPickerOpen||!activeBrand.supported)return;
        deviceSummary.setText(ble.readyCount()>0?"已选择 "+ble.readyCount()+" 台已连接设备":"尚未连接大灯");
        scanButton.setText(ble.isScanning()?"停止扫描":"扫描附近设备");deviceList.removeAllViews();
        memoryPanel.removeAllViews();
        if(!ble.rememberedAddress().isEmpty()){
            memoryPanel.addView(sectionNote("上次连接："+ble.rememberedLabel()+"\n"+ble.rememberedAddress()+" · 打开应用自动查找"));
            memoryPanel.addView(button("查找并重连上次设备",false,v->beginScanRequest(true)));
            memoryPanel.addView(button("清除连接记忆",false,v->{ble.clearMemory();updateDevices();}));
        }
        java.util.List<BleController.Device> devices=ble.devices();
        if(devices.isEmpty()) {
            TextView empty=text(ble.isScanning()?"正在寻找附近的大灯…":"点击扫描，设备会显示在这里",14,MUTED,false);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(35),0,dp(40));deviceList.addView(empty);
        }
        for(BleController.Device device:devices) {
            LinearLayout card=card();LinearLayout line=row();line.setGravity(Gravity.CENTER_VERTICAL);LinearLayout desc=column();
            desc.addView(text(device.label(),15,TEXT,true));TextView address=text(device.address+" · "+device.rssi+" dBm",10,MUTED,false);address.setPadding(0,dp(5),0,dp(5));desc.addView(address);
            desc.addView(text(device.restoring?"正在恢复参数 "+device.restored+"/"+device.restoreTotal:device.state,12,device.ready()?ACCENT:MUTED,false));line.addView(desc,new LinearLayout.LayoutParams(0,-2,1));
            TextView action=button(device.gatt!=null?"断开":"连接",device.ready(),v->{if(device.gatt!=null)ble.disconnect(device);else if(hasBlePermissions())ble.connect(device);else requestScan();});line.addView(action);card.addView(line);
            if(device.ready()) {
                CheckBox selected=new CheckBox(this);selected.setText("控制此设备");selected.setTextSize(12);selected.setTextColor(MUTED);selected.setButtonTintList(ColorStateList.valueOf(ACCENT));selected.setChecked(device.selected);
                selected.setOnCheckedChangeListener((v,on)->{device.selected=on;if(ble.readyCount()==1)for(BleController.Device d:ble.devices())if(d.ready()&&d.selected)loadDeviceProfile(d);updateHeader();deviceSummary.setText("已选择 "+ble.readyCount()+" 台已连接设备");});card.addView(selected);
                card.addView(text("该设备已保存 "+ble.profile(device).savedCount()+" 项参数",11,MUTED,false));
                card.addView(button("单独设置此设备",false,v->{
                    for(BleController.Device d:ble.devices())d.selected=d==device;
                    loadDeviceProfile(device);updateHeader();showTab(0);
                }));
                card.addView(button(device.probe!=null?"正在检测回读…":"检测状态回读（实验）",false,v->ble.probeState(device)));
            }
            if(!device.ready()&&ble.profiles.known(device.address))card.addView(button("离线编辑已保存参数",false,v->editOfflineProfile(device.address)));
            if(ble.profiles.known(device.address))card.addView(button("删除已保存参数",false,v->confirmDeleteProfile(device.address)));
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(12);deviceList.addView(card,cp);
        }
        for(String address:ble.profiles.addresses()){
            boolean listed=false;for(BleController.Device d:devices)if(d.address.equals(address)){listed=true;break;}
            if(listed)continue;
            DeviceProfiles.Profile saved=ble.profiles.load(address);LinearLayout savedCard=card();
            savedCard.addView(text(saved.name,15,TEXT,true));savedCard.addView(sectionNote(address+" · 已保存 "+saved.savedCount()+" 项参数"));
            savedCard.addView(button("离线编辑已保存参数",false,v->editOfflineProfile(address)));savedCard.addView(button("删除已保存参数",false,v->confirmDeleteProfile(address)));addOfflineCard(savedCard);
        }
        if(logView!=null)logView.setText(logText());
    }
    void confirmDeleteProfile(String address){
        if(needsOnboarding())return;
        if(isOfflineDraft()){toast("请先确认或取消离线修改");return;}
        LampBrand targetBrand=activeBrand;
        String name=targetBrand==LampBrand.MOSILA?ble.profiles.load(address).name:ble.mdkProfiles.name(address);
        new AlertDialog.Builder(this).setTitle("删除已保存参数？")
            .setMessage(targetBrand.title+" · "+name+"\n"+address+"\n\n删除后重连将不再恢复这些参数，连接记忆会保留。再次调节后会重新保存。若该设备正在发送参数，将断开连接以停止剩余发送。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{
                if(activeBrand!=targetBrand||isOfflineDraft()){toast("设备页面已变化，请重新操作");return;}
                if(!ble.deleteSavedParameters(address))return;
                if(targetBrand==LampBrand.MOSILA){
                    if(address.equals(displayedAddress)){loadProfileAddress(address);displayedAddress="";save();}
                    if(address.equals(preferences.getString("displayedAddress","")))preferences.edit().remove("displayedAddress").apply();
                }else{
                    if(address.equals(mdk.address)){mdk.address="";mdk.state=new MdkState();}
                    if(address.equals(consolePreferences.getString("mdkAddress","")))consolePreferences.edit().remove("mdkAddress").apply();
                }
                showTab(3);toast("已删除该设备的已保存参数");
            }).show();
    }
    void addOfflineCard(LinearLayout card){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(12);deviceList.addView(card,p);}
    boolean hasBlePermissions(){
        if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    boolean startupScanChecked,pendingRememberedScan,waitingLocation;
    LampBrand pendingScanBrand;
    final Runnable startupScan=()->{
        if(needsOnboarding()||paused||startupScanChecked||!activeBrand.supported||(freeNotice!=null&&freeNotice.isShowing()))return;
        startupScanChecked=true;
        if(!ble.rememberedAddress().isEmpty())beginScanRequest(true);
    };
    void requestScan() {
        if(needsOnboarding())return;
        pendingRememberedScan=false;
        if(ble.isScanning()){ble.stopScan();return;}
        beginScanRequest(false);
    }
    void beginScanRequest(boolean remembered) {
        if(needsOnboarding()||!activeBrand.supported)return;
        pendingScanBrand=activeBrand;pendingRememberedScan=remembered;
        if(!hasBlePermissions()) {
            String[] needed=Build.VERSION.SDK_INT>=31?new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT}:new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
            requestPermissions(needed,42);return;
        }
        if(ble.adapter==null){toast("此设备不支持蓝牙");return;}
        try {
            if(!ble.adapter.isEnabled()){startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),43);return;}
            if(Build.VERSION.SDK_INT<31){android.location.LocationManager lm=(android.location.LocationManager)getSystemService(LOCATION_SERVICE);if(lm!=null&&!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)&&!lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)){new AlertDialog.Builder(this).setTitle("打开定位服务").setMessage("此 Android 版本需要开启系统定位服务才能扫描 BLE 设备。").setPositiveButton("去开启",(d,w)->{waitingLocation=true;startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));}).setNegativeButton("取消",null).show();return;}}
            if(remembered)ble.startRememberedScan();else ble.startScan();
        }catch(RuntimeException ex){toast("无法开始扫描，请检查蓝牙权限");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==42){if(pendingScanBrand!=activeBrand)return;if(hasBlePermissions())beginScanRequest(pendingRememberedScan);else new AlertDialog.Builder(this).setTitle("需要蓝牙权限").setMessage("请在应用权限中允许“附近的设备”，之后重新扫描。").setPositiveButton("应用设置",(d,w)->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName())))).setNegativeButton("取消",null).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==43&&result==RESULT_OK&&pendingScanBrand==activeBrand)beginScanRequest(pendingRememberedScan);}
    String settingFeedback(){return isOfflineDraft()?"草稿已修改，请确认保存":lastSubmissionQueued?"已保存并加入发送队列":"已保存，重连后自动恢复";}
    boolean send(byte[] packet,boolean coalesce,boolean explicit){
        lastSubmissionQueued=false;
        if(needsOnboarding()||activeBrand!=LampBrand.MOSILA)return false;
        String key=DeviceProfiles.key(packet);
        if(isOfflineDraft()&&key!=null&&!key.equals("lastMotion")){
            draftEdits.removeIf(p->key.equals(DeviceProfiles.key(p)));draftEdits.add(packet.clone());
            applyUiPacket(packet);if(draftStatus!=null)draftStatus.setText("已修改 "+draftEdits.size()+" 项 · 确认后保存");
            return true;
        }
        if(isOfflineDraft())return false;
        boolean ok=ble.send(packet,coalesce,explicit);lastSubmissionQueued=ok;
        if(ok){applyUiPacket(packet);save();}
        if(!ok&&explicit)toast(ble.isProbing()?"正在读取设备信息，请稍后再控制灯光":"请先连接并选择大灯");return ok;
    }
    @Override public void onChanged(){updateHeader();main.removeCallbacks(deviceRender);main.postDelayed(deviceRender,200);if(ble.readyCount()==0)stopHold();}
    void updateHeader(){
        if(connection==null)return;
        connection.setVisibility(needsOnboarding()?View.GONE:View.VISIBLE);brandEntry.setEnabled(!needsOnboarding());brandArrow.setVisibility(needsOnboarding()?View.GONE:View.VISIBLE);brandEntry.setContentDescription(needsOnboarding()?"mosila console":"选择大灯品牌");
        if(needsOnboarding()){brandTag.setText("首次使用 · 第 "+firstUse.step+" / 3 步");return;}
        brandTag.setText(activeBrand.title+(activeBrand.supported?" · 灯光控制":" · 品牌适配中"));
        if(brandPickerOpen){connection.setText("返回");connection.setTextColor(TEXT);return;}
        if(!activeBrand.supported){connection.setText("切换品牌");connection.setTextColor(TEXT);return;}
        int selected=ble.readyCount(),connected=0;for(BleController.Device d:ble.devices())if(d.ready())connected++;
        connection.setText(isOfflineDraft()?"编辑中":connected>0?"●  "+connected+" 台已连接":"＋  连接设备");connection.setTextColor(isOfflineDraft()||connected>0?ACCENT:TEXT);
        if(activeBrand==LampBrand.MOSILA&&tab==0&&subtitle!=null)subtitle.setText(isOfflineDraft()?"离线草稿，确认后才保存":selected>0?"已连接 · 选择颜色即可控制大灯":"连接大灯，或前往设备页离线编辑");
    }
    @Override public void onLog(String message){String time=new SimpleDateFormat("HH:mm:ss",Locale.ROOT).format(new Date());logs.addLast(time+"  "+message);while(logs.size()>160)logs.removeFirst();if(logView!=null)logView.setText(logText());}
    String logText(){if(logs.isEmpty())return "暂无记录。连接后可在这里查看通信记录。";StringBuilder b=new StringBuilder();for(String s:logs)b.append(s).append('\n');return b.toString().trim();}
    String profileNote(){
        if(isOfflineDraft())return "离线草稿："+draftAddress+"\n确认后保存；取消将放弃本次所有修改。";
        if(ble.readyCount()==0)return "未连接时仅作预览；需要保存到设备，请先在设备页点击离线编辑。";
        return (ble.readyCount()>1?"当前操作会分别保存到所选设备；可在设备页单独设置。":displayedAddress.isEmpty()?"当前为本地预览；连接后设置会按设备自动保存。":"当前设备："+displayedAddress+" · 参数自动保存，重连后恢复。")+"\n这些是本地设置，不是大灯状态回读。";
    }
    @Override public void onReady(BleController.Device device){
        if(needsOnboarding()||!activeBrand.supported)return;
        if(activeBrand==LampBrand.MODENGKE){mdk.ready(device);return;}
        if(isOfflineDraft()){device.selected=false;return;}
        if(ble.readyCount()==1&&device.selected)loadDeviceProfile(device);
    }
    void loadDeviceProfile(BleController.Device device){loadProfileAddress(device.address);}
    void editOfflineProfile(String address){
        if(needsOnboarding()||!activeBrand.supported||isOfflineDraft()||!ble.profiles.known(address))return;
        draftReturnAddress=displayedAddress;draftAddress=address;draftEdits.clear();draftSelections.clear();
        for(BleController.Device d:ble.devices()){draftSelections.put(d.address,d.selected);d.selected=false;}
        loadProfileAddress(address);
        renderDraftBar();updateHeader();showTab(0);
    }
    void renderDraftBar(){
        if(activeBrand==LampBrand.MODENGKE&&mdk!=null){mdk.renderDraft();return;}
        if(draftBar==null)return;draftBar.removeAllViews();draftBar.setVisibility(isOfflineDraft()?View.VISIBLE:View.GONE);
        if(!isOfflineDraft())return;
        draftBar.addView(text("编辑中 · "+ble.profiles.load(draftAddress).name,14,ACCENT,true));
        draftBar.addView(text(draftAddress,10,MUTED,false));
        draftStatus=text("已修改 "+draftEdits.size()+" 项 · 确认后保存",11,MUTED,false);draftBar.addView(draftStatus);
        LinearLayout actions=row();addWeight(actions,button("取消",false,v->cancelOfflineDraft()));addWeight(actions,button("确认",true,v->confirmOfflineDraft()));draftBar.addView(actions);
    }
    void confirmOfflineDraft(){
        if(activeBrand==LampBrand.MODENGKE){mdk.finishDraft(true);return;}
        if(!isOfflineDraft())return;String address=draftAddress;
        ble.profiles.recordAll(address,draftEdits);endOfflineDraft();loadProfileAddress(address);showTab(3);
        toast("已确认保存，重连后自动恢复");
    }
    void cancelOfflineDraft(){
        if(activeBrand==LampBrand.MODENGKE){mdk.finishDraft(false);return;}
        if(!isOfflineDraft())return;String address=ble.profiles.known(draftReturnAddress)?draftReturnAddress:draftAddress;
        for(BleController.Device d:ble.devices())if(draftSelections.containsKey(d.address))d.selected=draftSelections.get(d.address);
        endOfflineDraft();loadProfileAddress(address);updateHeader();showTab(3);toast("已取消本次修改");
    }
    void endOfflineDraft(){draftAddress="";draftEdits.clear();draftSelections.clear();draftStatus=null;renderDraftBar();updateHeader();}
    @Override public void onBackPressed(){if(needsOnboarding()){if(firstUse.step>1){firstUse.move(firstUse.step-1);showOnboarding();}else super.onBackPressed();return;}if(brandPickerOpen)showTab(tab);else if(!activeBrand.supported)showBrandPicker();else if(isOfflineDraft())cancelOfflineDraft();else super.onBackPressed();}
    void loadProfileAddress(String address){
        if(activeBrand!=LampBrand.MOSILA)return;
        displayedAddress=address;
        color=0xff70d8ef;intensity=255;mode=0;speed=5;level=9;power=true;routeA=true;routeB=true;modeA=true;modeB=true;
        startupColorSetting=color;startupSetting=0;pointsSetting=50;brakeSetting=-1;turnSetting=-1;selfCheckSetting=-1;lineOrderSetting=-1;
        DeviceProfiles.Profile p=ble.profiles.load(address);
        for(String key:new String[]{"power","color","mode","startup","brake","turn","selfCheck","lineOrder","level","routeA","routeB"}){
            byte[] packet=p.get(key);if(packet!=null)applyUiPacket(packet);
        }
        activeOutput=p.active;save();
        if(modePicker!=null&&modePicker.isShowing())modePicker.dismiss();
        if(!paused&&body!=null&&!brandPickerOpen)showTab(tab);
    }
    void applyUiPacket(byte[] p){
        String key=DeviceProfiles.key(p);if(key==null)return;
        if(key.equals("lineOrder")){lineOrderSetting=p[1]&255;return;}
        int cmd=p[3]&255;
        switch(cmd){
            case 1:power=p[4]!=0;if(power)activeOutput="color";break;
            case 2:color=0xff000000|((p[4]&255)<<16)|((p[5]&255)<<8)|(p[6]&255);intensity=p[7]&255;activeOutput="color";break;
            case 3:mode=p[5]&255;speed=11-(p[6]&255);modeA=p[7]!=0;modeB=p[8]!=0;activeOutput="mode";break;
            case 4:startupSetting=p[4]&255;pointsSetting=p[5]&255;startupColorSetting=0xff000000|((p[6]&255)<<16)|((p[7]&255)<<8)|(p[8]&255);break;
            case 5:brakeSetting=p[4]&255;break;
            case 6:turnSetting=p[4]&255;break;
            case 8:selfCheckSetting=p[4]&255;break;
            case 9:if(p[4]==0)routeA=p[5]!=0;else routeB=p[5]!=0;break;
            case 10:level=p[4]&255;break;
        }
    }
    void save(){if(needsOnboarding()||isOfflineDraft()||activeBrand!=LampBrand.MOSILA)return;preferences.edit().putInt("color",color).putInt("colorIntensity",intensity).putInt("mode",mode).putInt("speed",speed)
        .putBoolean("routeA",routeA).putBoolean("routeB",routeB).putBoolean("power",power).putBoolean("modeA",modeA).putBoolean("modeB",modeB)
        .putInt("level",level).putInt("startupColorSetting",startupColorSetting).putInt("startupSetting",startupSetting).putInt("points",pointsSetting).putInt("brakeSetting",brakeSetting).putInt("turnSetting",turnSetting)
        .putInt("selfCheckSetting",selfCheckSetting).putInt("lineOrderSetting",lineOrderSetting).putString("activeOutput",activeOutput).putString("displayedAddress",displayedAddress).apply();}
    @Override protected void onPause(){paused=true;main.removeCallbacks(startupScan);if(modePicker!=null)modePicker.dismiss();if(mdk!=null)mdk.pause();stopHold();ble.stopScan();save();super.onPause();}
    @Override protected void onResume(){super.onResume();paused=false;showFreeNoticeIfNeeded();if(waitingLocation){waitingLocation=false;beginScanRequest(pendingRememberedScan);}else main.post(startupScan);}
    @Override protected void onDestroy(){if(infoDialog!=null){infoDialog.dismiss();infoDialog=null;}if(freeNotice!=null){freeNotice.setOnDismissListener(null);freeNotice.dismiss();freeNotice=null;}stopHold();ble.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("tab",tab);out.putBoolean("brandPicker",brandPickerOpen);out.putBoolean("freeNoticeShown",freeNoticeShown);out.putBoolean("freeNoticeOpen",freeNotice!=null&&freeNotice.isShowing());super.onSaveInstanceState(out);}
    interface IntChange{void changed(int value);} interface ValueFormat{String format(int value);}
    void addSlider(LinearLayout target,String label,int value,int max,IntChange change,Runnable finished){addSlider(target,label,value,max,change,finished,n->Integer.toString(n));}
    void addSlider(LinearLayout target,String label,int value,int max,IntChange change,Runnable finished,ValueFormat format){
        LinearLayout row=row();row.setPadding(0,dp(15),0,dp(4));row.addView(text(label,13,TEXT,false),new LinearLayout.LayoutParams(0,-2,1));TextView number=text(format.format(value),12,MUTED,true);row.addView(number);target.addView(row);
        SeekBar seek=new SeekBar(this);seek.setMax(max);seek.setProgress(value);seek.setProgressTintList(ColorStateList.valueOf(ACCENT));seek.setThumbTintList(ColorStateList.valueOf(ACCENT));seek.setProgressBackgroundTintList(ColorStateList.valueOf(BORDER));seek.setContentDescription(label);
        target.addView(seek,new LinearLayout.LayoutParams(-1,dp(46)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar b){} public void onProgressChanged(SeekBar b,int n,boolean user){number.setText(format.format(n));if(user)change.changed(n);}public void onStopTrackingTouch(SeekBar b){finished.run();}});
    }
    LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);return l;}
    LinearLayout card(){LinearLayout l=column();l.setPadding(dp(18),dp(18),dp(18),dp(18));l.setBackground(shape(CARD,24,BORDER));return l;}
    void addCard(LinearLayout l){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(16);body.addView(l,p);}
    void addWeight(LinearLayout target,View view){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(3),dp(3),dp(3));target.addView(view,p);}
    TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");if(bold)t.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));return t;}
    TextView button(String label,boolean active,View.OnClickListener click){TextView t=text(label,13,TEXT,true);t.setGravity(Gravity.CENTER);t.setMinHeight(dp(46));t.setPadding(dp(15),dp(12),dp(15),dp(12));styleButton(t,active);t.setOnClickListener(click);t.setFocusable(true);return t;}
    void styleButton(TextView t,boolean active){t.setTextColor(active?BG:TEXT);t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff),shape(active?ACCENT:0xff222c37,14,active?0:BORDER),null));}
    GradientDrawable shape(int fill,int radius,int stroke){GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(radius));if(stroke!=0)d.setStroke(dp(1),stroke);return d;}
    TextView sectionNote(String value){TextView t=text(value,12,MUTED,false);t.setPadding(0,dp(14),0,dp(10));t.setLineSpacing(dp(3),1);return t;}
    void foot(String value){TextView t=text(value,11,MUTED,false);t.setLineSpacing(dp(5),1);t.setPadding(dp(4),dp(5),dp(4),dp(8));body.addView(t);}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_SHORT).show();}
    int dp(float value){return Math.round(getResources().getDisplayMetrics().density*value);}

    final class ColorWheel extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float cx,cy,radius;
        ColorWheel(){super(MainActivity.this);setContentDescription("拖动色盘选择颜色");setFocusable(true);}
        @Override protected void onDraw(Canvas canvas){
            cx=getWidth()/2f;cy=getHeight()/2f;radius=Math.min(getWidth(),getHeight())*.43f;
            int[] rainbow={Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED};
            paint.setShader(new SweepGradient(cx,cy,rainbow,null));canvas.drawCircle(cx,cy,radius,paint);
            paint.setShader(new RadialGradient(cx,cy,radius,new int[]{Color.WHITE,0x00ffffff},null,Shader.TileMode.CLAMP));canvas.drawCircle(cx,cy,radius,paint);paint.setShader(null);
            float[] hsv=new float[3];Color.colorToHSV(color,hsv);double a=Math.toRadians(hsv[0]);float x=cx+(float)Math.cos(a)*radius*hsv[1],y=cy+(float)Math.sin(a)*radius*hsv[1];
            paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(5));paint.setColor(0xff19222b);canvas.drawCircle(x,y,dp(10),paint);
            paint.setStrokeWidth(dp(2));paint.setColor(Color.WHITE);canvas.drawCircle(x,y,dp(10),paint);paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean onTouchEvent(android.view.MotionEvent e){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN && Math.hypot(e.getX()-cx,e.getY()-cy)>radius+dp(12))return false;
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE||e.getActionMasked()==MotionEvent.ACTION_UP){
                getParent().requestDisallowInterceptTouchEvent(true);float dx=e.getX()-cx,dy=e.getY()-cy;
                float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360);float sat=Math.min(1,(float)Math.hypot(dx,dy)/radius);color=Color.HSVToColor(new float[]{hue,sat,1});updateColor();
                long now=SystemClock.uptimeMillis();if(e.getActionMasked()==MotionEvent.ACTION_UP){applyColor(true);getParent().requestDisallowInterceptTouchEvent(false);performClick();}else if(now-lastPreviewSend>=85){applyColor(false);lastPreviewSend=now;}return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);save();return true;}return true;
        }
        @Override public boolean performClick(){super.performClick();return true;}
    }
    final class LampView extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        LampView(){super(MainActivity.this);setContentDescription("三透镜大灯预览：顶部连续灯带，两侧层叠灯条");}
        @Override protected void onDraw(Canvas c){
            c.save();
            float scale=Math.min(getWidth()/600f,getHeight()/282f);
            c.translate((getWidth()-600*scale)/2,(getHeight()-282*scale)/2);c.scale(scale,scale);
            boolean enabled=power&&(routeA||routeB);int glow=enabled?color:0xff43515f;
            p.setShader(new RadialGradient(300,134,280,new int[]{(glow&0xffffff)|(enabled?0x26000000:0x08000000),0x00151c24},null,Shader.TileMode.CLAMP));
            p.setStyle(Paint.Style.FILL);c.drawRect(0,0,600,282,p);p.setShader(null);
            // Smooth lamp housing: no mounting tabs, fixing screws or holes.
            Path shell=new Path();shell.moveTo(49,73);shell.cubicTo(93,38,496,38,551,73);shell.cubicTo(568,93,577,164,558,187);
            shell.cubicTo(518,240,85,240,43,187);shell.cubicTo(24,162,33,94,49,73);shell.close();
            p.setShader(new LinearGradient(0,50,0,234,new int[]{0xff43505d,0xff1b2530,0xff080d13,0xff34414c},null,Shader.TileMode.CLAMP));c.drawPath(shell,p);p.setShader(null);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0xff526170);c.drawPath(shell,p);p.setStyle(Paint.Style.FILL);
            Path chamber=new Path();chamber.moveTo(62,93);chamber.quadTo(300,67,538,93);chamber.lineTo(552,171);chamber.quadTo(300,230,48,171);chamber.close();
            p.setColor(0xff080e15);c.drawPath(chamber,p);
            // Three clear projector lenses; these do not change hue with the trim.
            for(float x:new float[]{181,300,419}){
                p.setShader(new LinearGradient(x-48,100,x+48,196,new int[]{0xff4d5a66,0xff17212d,0xff344351},null,Shader.TileMode.CLAMP));c.drawRoundRect(new RectF(x-52,103,x+52,200),13,13,p);p.setShader(null);
                p.setColor(0xff070c12);c.drawCircle(x,150,45,p);
                p.setShader(new RadialGradient(x-10,135,59,new int[]{0xff99aab6,0xff263646,0xff0c1520},new float[]{0,.42f,1},Shader.TileMode.CLAMP));c.drawCircle(x,150,40,p);p.setShader(null);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(0xff7a8e9e);c.drawCircle(x,150,40,p);
                p.setStrokeWidth(1);p.setColor(0xff9cb0bf);c.drawArc(new RectF(x-33,117,x+33,183),205,85,false,p);p.setStyle(Paint.Style.FILL);
                p.setColor(0x65e0edf4);c.drawRoundRect(new RectF(x-20,125,x+2,133),3,3,p);
                p.setColor(0x2dc1d7e5);c.drawOval(new RectF(x+7,153,x+23,174),p);
            }
            // One continuous curved upper strip, as on the supplied headlamp.
            Path strip=new Path();strip.moveTo(66,85);strip.cubicTo(143,66,455,66,534,85);
            p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setColor((glow&0xffffff)|(enabled?0x30000000:0x08000000));p.setStrokeWidth(35);c.drawPath(strip,p);
            p.setColor((glow&0xffffff)|(enabled?0x70000000:0x20000000));p.setStrokeWidth(23);c.drawPath(strip,p);
            p.setColor(glow);p.setStrokeWidth(13);c.drawPath(strip,p);
            p.setColor(enabled?mix(glow,0xfff5fbff,.63f):0xff34414c);p.setStrokeWidth(6);c.drawPath(strip,p);
            // Seven layered side fins, tapering inwards toward the lower edge.
            for(int side=0;side<2;side++){
                c.save();if(side==1){c.translate(600,0);c.scale(-1,1);}
                for(int i=0;i<7;i++){
                    float y=111+i*10,start=65+i*1.4f,end=102+i*2.5f;
                    p.setStrokeWidth(15);p.setColor((glow&0xffffff)|(enabled?0x35000000:0x08000000));c.drawLine(start,y,end,y+3,p);
                    p.setStrokeWidth(6);p.setColor(glow);c.drawLine(start,y,end,y+3,p);
                    p.setStrokeWidth(2);p.setColor(enabled?mix(glow,0xfff5fbff,.7f):0xff34414c);c.drawLine(start,y,end,y+3,p);
                }c.restore();
            }
            p.setStrokeCap(Paint.Cap.BUTT);p.setStrokeWidth(2);p.setColor(0xff536171);
            Path lower=new Path();lower.moveTo(69,195);lower.quadTo(300,250,531,195);c.drawPath(lower,p);
            p.setStyle(Paint.Style.FILL);c.restore();
        }
        int mix(int a,int b,float weight){return Color.rgb((int)(Color.red(a)*(1-weight)+Color.red(b)*weight),(int)(Color.green(a)*(1-weight)+Color.green(b)*weight),(int)(Color.blue(a)*(1-weight)+Color.blue(b)*weight));}
    }
    final class IconView extends View {
        final int type,tint;final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        IconView(int type,int tint){super(MainActivity.this);this.type=type;this.tint=tint;}
        @Override protected void onDraw(Canvas canvas){
            canvas.save();canvas.scale(getWidth()/24f,getHeight()/24f);p.setColor(tint);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            if(type==0){canvas.drawCircle(12,12,8.5f,p);canvas.drawCircle(12,12,3,p);canvas.drawLine(18,5,20,3,p);}
            if(type==1){Path path=new Path();path.moveTo(13,2);path.lineTo(5,13);path.lineTo(11,13);path.lineTo(10,22);path.lineTo(19,10);path.lineTo(13,10);path.close();canvas.drawPath(path,p);}
            if(type==2){for(int i=0;i<3;i++){int y=5+i*7;canvas.drawLine(3,y,21,y,p);p.setStyle(Paint.Style.FILL);p.setColor(0xff10161d);canvas.drawCircle(i==1?15:8,y,3,p);p.setColor(tint);p.setStyle(Paint.Style.STROKE);canvas.drawCircle(i==1?15:8,y,2.5f,p);}}
            if(type==3){Path path=new Path();path.moveTo(11,2);path.lineTo(18,8);path.lineTo(6,17);path.moveTo(6,7);path.lineTo(18,16);path.lineTo(11,22);path.lineTo(11,2);canvas.drawPath(path,p);}
            canvas.restore();
        }
    }
}
