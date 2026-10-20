package com.suancaiyu.mosilalight;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import static com.suancaiyu.mosilalight.MainActivity.*;

/** Product-specific UI for 九号 N/F/M 大灯 3.0; no controls for unrelated products. */
final class MdkUi {
    final MainActivity h;
    MdkState state=new MdkState();
    int channel;
    String address="",draft="",returnAddress="";
    MdkState beforeDraft;
    final HashMap<String,Boolean> selections=new HashMap<>();
    final LinkedHashSet<String> edited=new LinkedHashSet<>();
    ModePicker picker;
    Wheel wheel;Preview preview;TextView colorLabel;
    long lastColor;
    MdkUi(MainActivity h){this.h=h;}
    void enter(){String last=h.consolePreferences.getString("mdkAddress","");if(h.ble.mdkProfiles.known(last))load(last);else{state=new MdkState();address="";}channel=0;}
    void load(String target){address=target;state=h.ble.mdkProfiles.load(target);if(draft.isEmpty())h.consolePreferences.edit().putString("mdkAddress",target).apply();if(!h.brandPickerOpen&&h.body!=null&&!h.paused)h.showTab(h.tab);}
    void ready(BleController.Device d){if(!draft.isEmpty()){d.selected=false;return;}if(h.ble.readyCount()==1&&d.selected)load(d.address);}
    String hint(){return !draft.isEmpty()?"离线草稿 · 确认后保存":h.ble.readyCount()>0?"按设备自动保存 · 重连后逐条恢复":"未连接，仅预览 · 在设备页明确开启离线编辑";}
    void build(int tab){
        wheel=null;preview=null;colorLabel=null;
        if(tab==3){devices();return;}
        h.pageTitle(tab==2?"大灯，按你的习惯":"九号 N/F/M 大灯 3.0",hint());
        if(tab!=2){
            LinearLayout hero=h.card();hero.addView(h.text("三路独立控制",12,MUTED,true));preview=new Preview();hero.addView(preview,new LinearLayout.LayoutParams(-1,h.dp(140)));
            LinearLayout tabs=h.row();for(int i=0;i<3;i++){final int slot=i;TextView b=h.button(MdkProtocol.NAMES[i],channel==i,v->{channel=slot;h.showTab(h.tab);});b.setTextSize(11);b.setPadding(h.dp(5),h.dp(10),h.dp(5),h.dp(10));h.addWeight(tabs,b);}hero.addView(tabs);h.addCard(hero);
            if(tab==0)color();else effects();
        }else settings();
        if(tab!=2)h.foot(hint()+"\n仅适配产品 ID 58 · 参数来自本地记录，不代表设备状态回读。");
        if(tab==2)h.addAboutEntry();
    }
    boolean submit(String key){
        h.lastSubmissionQueued=false;if(h.needsOnboarding()||h.activeBrand!=LampBrand.MODENGKE)return false;byte[] packet=state.packet(key);
        if(!draft.isEmpty()){state.configured.add(key);edited.add(key);renderDraft();return true;}
        boolean ok=h.ble.send(packet,true,true);h.lastSubmissionQueued=ok;
        if(ok){state.configured.add(key);for(BleController.Device d:h.ble.devices())if(d.ready()&&d.selected){MdkState saved=h.ble.mdkProfiles.load(d.address);saved.sync=state.sync;h.ble.mdkProfiles.save(d.address,saved);}}
        else h.toast("请先连接大灯，或在设备页开启离线编辑");
        return ok;
    }
    void submitLight(){
        if(state.sync){MdkState.Light source=state.lights[channel];for(int i=0;i<3;i++)if(i!=channel){MdkState.Light dest=state.lights[i];dest.effect=source.effect;dest.speed=source.speed;dest.flash=source.flash;dest.direction=source.direction;dest.width=source.width;dest.brightness=source.brightness;dest.color=source.color;dest.on=source.on;dest.palette=source.palette.clone();}for(int i=0;i<3;i++)submit("light"+i);}
        else submit("light"+channel);
        if(preview!=null)preview.invalidate();
    }
    void color(){
        MdkState.Light l=state.lights[channel];LinearLayout card=h.card();LinearLayout top=h.row();top.setGravity(Gravity.CENTER_VERTICAL);top.addView(h.text(MdkProtocol.NAMES[channel],18,TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        top.addView(h.button(l.on?"关闭此路":"开启此路",false,v->{l.on=!l.on;submitLight();h.showTab(0);}));card.addView(top);
        if(l.effect!=0)card.addView(h.sectionNote("当前为“"+MdkProtocol.EFFECTS[l.effect]+"”；选择颜色后切换为单色。"));
        colorLabel=h.text(hex(l.color),13,MUTED,true);colorLabel.setGravity(Gravity.RIGHT);colorLabel.setOnClickListener(v->editColor(l.color,c->{l.color=c;l.effect=0;submitLight();h.showTab(0);}));card.addView(colorLabel);
        wheel=new Wheel();card.addView(wheel,new LinearLayout.LayoutParams(-1,h.dp(220)));
        LinearLayout colors=h.row();for(int c:new int[]{0xffff425c,0xffffb74b,0xffc7f86f,0xff48fefe,0xff6d85ff,0xffeb61fa,0xffffffff}){TextView sw=h.button("",false,v->{l.color=c;l.effect=0;submitLight();h.showTab(0);});sw.setBackground(h.shape(c,30,0));sw.setMinHeight(h.dp(30));sw.setPadding(0,0,0,0);h.addWeight(colors,sw);}card.addView(colors);
        h.addSlider(card,"亮度",l.brightness,100,n->l.brightness=n,this::submitLight,n->n+"%");
        h.addSlider(card,"单色闪烁频率",l.flash,100,n->l.flash=n,()->{l.effect=0;submitLight();});
        card.addView(h.button("应用当前颜色",true,v->{l.effect=0;submitLight();}));h.addCard(card);
    }
    void effects(){
        MdkState.Light l=state.lights[channel];LinearLayout card=h.card();card.addView(h.text("当前灯效 · "+MdkProtocol.NAMES[channel],12,MUTED,true));TextView current=h.text(MdkProtocol.EFFECTS[l.effect],25,TEXT,true);current.setPadding(0,h.dp(12),0,h.dp(16));card.addView(current);card.addView(h.button("浏览全部 10 种灯效  →",true,v->chooseEffect()));
        if(l.effect!=0)h.addSlider(card,"切换速度",l.speed,9,n->l.speed=n,this::submitLight,n->"第 "+(n+1)+" 档");
        if(l.effect>=6){
            card.addView(h.sectionNote("灯效方向"));String[] names={"向右","向左","向外","向内"};LinearLayout directions=h.row();for(int i=1;i<=4;i++){final int value=i;h.addWeight(directions,h.button(names[i-1],l.direction==i,v->{l.direction=value;submitLight();h.showTab(1);}));}card.addView(directions);
            h.addSlider(card,"颜色宽度",l.width,100,n->l.width=n,this::submitLight);
        }
        if(l.effect!=0&&l.effect!=8){card.addView(h.sectionNote("灯效颜色 · 支持 1–7 色，按显示顺序发送"));LinearLayout swatches=h.row();for(int i=0;i<l.palette.length;i++){final int index=i;TextView sw=h.button(""+(i+1),false,v->editColor(l.palette[index],c->{l.palette[index]=c;submitLight();h.showTab(1);}));sw.setTextColor(Color.luminance(l.palette[i])>.5?BG:TEXT);sw.setBackground(h.shape(l.palette[i],12,0));sw.setPadding(0,h.dp(12),0,h.dp(12));h.addWeight(swatches,sw);}card.addView(swatches);LinearLayout count=h.row();h.addWeight(count,h.button("减少颜色",false,v->{if(l.palette.length>1){l.palette=Arrays.copyOf(l.palette,l.palette.length-1);submitLight();h.showTab(1);}}));h.addWeight(count,h.button("增加颜色",false,v->{if(l.palette.length<7){int n=l.palette.length;l.palette=Arrays.copyOf(l.palette,n+1);l.palette[n]=0xffffffff;submitLight();h.showTab(1);}}));card.addView(count);}
        h.addSlider(card,"灯效亮度",l.brightness,100,n->l.brightness=n,this::submitLight,n->n+"%");h.addCard(card);
    }
    void chooseEffect(){
        if(picker!=null&&picker.isShowing())return;
        picker=new ModePicker(h,Arrays.asList(MdkProtocol.EFFECTS),()->state.lights[channel].effect,id->{state.lights[channel].effect=id;submitLight();return h.lastSubmissionQueued;});
        picker.setOnDismissListener(d->{if(!h.paused&&!h.brandPickerOpen&&h.activeBrand==LampBrand.MODENGKE)h.showTab(h.tab);});picker.show();
    }
    void settings(){
        LinearLayout sync=h.card();sync.addView(h.text("三路同步",17,TEXT,true));sync.addView(h.sectionNote("开启后，调色和灯效修改会同步到三路，各路保留原定灯珠数量。"));sync.addView(h.button(state.sync?"三路同步：开启":"三路同步：关闭",state.sync,v->{state.sync=!state.sync;saveSync();h.showTab(2);}));h.addCard(sync);
        LinearLayout boot=h.card();boot.addView(h.text("开机设置",18,TEXT,true));boot.addView(h.button("颜色自检："+(state.selfCheck?"开启":"关闭"),state.selfCheck,v->{state.selfCheck=!state.selfCheck;submit("selfCheck");h.showTab(2);}));
        boot.addView(h.button("开机颜色  "+hex(state.bootColor),false,v->editColor(state.bootColor,c->{state.bootColor=c;submit("bootColor");h.showTab(2);})));LinearLayout effects=h.row();for(int i=2;i<=4;i++){final int code=i;h.addWeight(effects,h.button("效果 "+(i-1),state.boot==i,v->{state.boot=code;submit("boot");h.showTab(2);}));}boot.addView(h.sectionNote("开机效果"));boot.addView(effects);h.addCard(boot);
        LinearLayout angle=h.card();angle.addView(h.text("大灯电调",18,TEXT,true));angle.addView(h.button("电调自检："+(state.orientationCheck?"开启":"关闭"),state.orientationCheck,v->{state.orientationCheck=!state.orientationCheck;submit("orientation");h.showTab(2);}));
        LinearLayout fx=h.row();for(int i=1;i<=2;i++){final int value=i;h.addWeight(fx,h.button("从天而降 "+i,state.orientationEffect==i,v->{state.orientationEffect=value;submit("orientation");h.showTab(2);}));}angle.addView(fx);
        h.addSlider(angle,"位置",state.position/5,20,n->state.position=n*5,()->submit("position"),n->Integer.toString(n*5));
        LinearLayout steps=h.row();h.addWeight(steps,h.button("降低 5",false,v->{state.position=Math.max(0,state.position-5);submit("position");h.showTab(2);}));h.addWeight(steps,h.button("升高 5",false,v->{state.position=Math.min(100,state.position+5);submit("position");h.showTab(2);}));angle.addView(steps);
        angle.addView(h.sectionNote("位置使用原版 A3 绝对位置指令，松手后发送；已保存的位置会在重连时恢复。"));h.addCard(angle);
    }
    void saveSync(){
        if(!draft.isEmpty()){edited.add("三路同步");renderDraft();return;}
        for(BleController.Device d:h.ble.devices())if(d.ready()&&d.selected){MdkState s=h.ble.mdkProfiles.load(d.address);s.sync=state.sync;h.ble.mdkProfiles.save(d.address,s);}
    }
    void devices(){
        h.pageTitle("连接你的大灯","魔灯客 · 九号 N/F/M 大灯 3.0 专用");
        if(!draft.isEmpty()){h.body.addView(h.sectionNote("请先确认或取消离线修改，再切换设备。"));return;}
        LinearLayout scan=h.card();scan.addView(h.text(h.ble.readyCount()>0?"已选择 "+h.ble.readyCount()+" 台设备":"尚未连接大灯",16,TEXT,true));scan.addView(h.sectionNote("连接后先查询型号；只有产品 ID 58 才开放控制。已保存的参数逐条发送，间隔至少 500 ms。"));scan.addView(h.button(h.ble.isScanning()?"停止扫描":"扫描附近设备",true,v->h.requestScan()));
        if(!h.ble.rememberedAddress().isEmpty()){scan.addView(h.sectionNote("上次连接："+h.ble.rememberedLabel()+"\n"+h.ble.rememberedAddress()));scan.addView(h.button("查找并重连上次设备",false,v->h.beginScanRequest(true)));scan.addView(h.button("清除连接记忆",false,v->{h.ble.clearMemory();refreshDevices();}));}h.addCard(scan);
        HashSet<String> listed=new HashSet<>();for(BleController.Device d:h.ble.devices()){
            listed.add(d.address);LinearLayout card=h.card();card.addView(h.text(d.label(),17,TEXT,true));card.addView(h.sectionNote(d.address+" · "+d.rssi+" dBm\n"+(d.restoring?"正在恢复参数 "+d.restored+"/"+d.restoreTotal:d.state)));card.addView(h.button(d.gatt!=null?"断开":"连接",d.ready(),v->{if(d.gatt!=null)h.ble.disconnect(d);else if(h.hasBlePermissions())h.ble.connect(d);else h.requestScan();}));
            if(d.ready()){
                CheckBox check=new CheckBox(h);check.setText("控制此设备");check.setTextColor(TEXT);check.setChecked(d.selected);check.setOnCheckedChangeListener((v,on)->{d.selected=on;if(h.ble.readyCount()==1)for(BleController.Device one:h.ble.devices())if(one.ready()&&one.selected)load(one.address);h.updateHeader();});card.addView(check);
                card.addView(h.button("单独设置此设备",false,v->{for(BleController.Device other:h.ble.devices())other.selected=other==d;load(d.address);h.showTab(0);}));
            }else if(h.ble.mdkProfiles.known(d.address))card.addView(h.button("离线编辑已保存参数",false,v->edit(d.address)));
            if(h.ble.mdkProfiles.known(d.address))card.addView(h.button("删除已保存参数",false,v->h.confirmDeleteProfile(d.address)));
            h.addCard(card);
        }
        for(String target:h.ble.mdkProfiles.addresses())if(!listed.contains(target)){LinearLayout card=h.card();card.addView(h.text(h.ble.mdkProfiles.name(target),16,TEXT,true));card.addView(h.sectionNote(target+" · 已保存 "+h.ble.mdkProfiles.load(target).configured.size()+" 项参数"));card.addView(h.button("离线编辑已保存参数",false,v->edit(target)));card.addView(h.button("删除已保存参数",false,v->h.confirmDeleteProfile(target)));h.addCard(card);}
        LinearLayout logs=h.card();logs.addView(h.text("连接记录",17,TEXT,true));logs.addView(h.button("复制连接记录",false,v->{android.content.ClipboardManager cb=(android.content.ClipboardManager)h.getSystemService(Context.CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText("MDK BLE",h.logText()));h.toast("记录已复制");}));h.logView=h.text(h.logText(),11,MUTED,false);h.logView.setTypeface(Typeface.MONOSPACE);h.logView.setTextIsSelectable(true);logs.addView(h.logView);h.addCard(logs);
        h.foot("请先断开原版应用的连接，再扫描。首次使用会申请附近设备权限。");
    }
    void refreshDevices(){if(h.tab==3&&!h.brandPickerOpen&&draft.isEmpty()){int y=h.scroll.getScrollY();h.body.removeAllViews();devices();h.scroll.post(()->h.scroll.scrollTo(0,y));}}
    void edit(String target){
        if(!draft.isEmpty()||!h.ble.mdkProfiles.known(target))return;
        returnAddress=address;beforeDraft=state.copy();draft=target;edited.clear();selections.clear();for(BleController.Device d:h.ble.devices()){selections.put(d.address,d.selected);d.selected=false;}
        load(target);renderDraft();h.showTab(0);h.updateHeader();
    }
    void renderDraft(){
        h.draftBar.removeAllViews();h.draftBar.setVisibility(draft.isEmpty()?View.GONE:View.VISIBLE);if(draft.isEmpty())return;
        h.draftBar.addView(h.text("编辑中 · "+h.ble.mdkProfiles.name(draft),14,ACCENT,true));h.draftBar.addView(h.text(draft,10,MUTED,false));h.draftBar.addView(h.text("已修改 "+edited.size()+" 项 · 确认后保存",11,MUTED,false));LinearLayout actions=h.row();h.addWeight(actions,h.button("取消",false,v->finishDraft(false)));h.addWeight(actions,h.button("确认",true,v->finishDraft(true)));h.draftBar.addView(actions);
    }
    void finishDraft(boolean confirm){
        if(draft.isEmpty())return;if(confirm){h.ble.mdkProfiles.save(draft,state);address=draft;h.consolePreferences.edit().putString("mdkAddress",address).apply();}else{state=beforeDraft;address=returnAddress;}
        for(BleController.Device d:h.ble.devices())if(selections.containsKey(d.address))d.selected=selections.get(d.address);
        draft="";beforeDraft=null;edited.clear();selections.clear();renderDraft();h.showTab(3);h.updateHeader();h.toast(confirm?"已确认保存，重连后自动恢复":"已取消本次修改");
    }
    interface ColorChange{void set(int color);}
    void editColor(int color,ColorChange changed){
        EditText edit=new EditText(h);edit.setSingleLine(true);edit.setText(hex(color));edit.setSelectAllOnFocus(true);LinearLayout container=h.column();container.setPadding(h.dp(24),h.dp(10),h.dp(24),0);container.addView(edit);
        AlertDialog dialog=new AlertDialog.Builder(h).setTitle("输入颜色 #RRGGBB").setView(container).setNegativeButton("取消",null).setPositiveButton("应用",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{String s=edit.getText().toString().trim();if(!s.startsWith("#"))s="#"+s;if(s.length()!=7)throw new IllegalArgumentException();changed.set(Color.parseColor(s));dialog.dismiss();}catch(Exception e){edit.setError("请输入6位颜色值");}}));dialog.show();
    }
    static String hex(int c){return String.format(Locale.ROOT,"#%06X",c&0xffffff);}
    void pause(){if(picker!=null)picker.dismiss();}
    final class Wheel extends View {
        Paint p=new Paint(3);float cx,cy,r;
        Wheel(){super(h);setContentDescription("选择当前通道的颜色");}
        protected void onDraw(Canvas c){cx=getWidth()/2f;cy=getHeight()/2f;r=Math.min(getWidth(),getHeight())*.43f;p.setShader(new SweepGradient(cx,cy,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED},null));c.drawCircle(cx,cy,r,p);p.setShader(new RadialGradient(cx,cy,r,Color.WHITE,0x00ffffff,Shader.TileMode.CLAMP));c.drawCircle(cx,cy,r,p);p.setShader(null);float[] hsv=new float[3];Color.colorToHSV(state.lights[channel].color,hsv);double a=Math.toRadians(hsv[0]);float x=cx+(float)Math.cos(a)*r*hsv[1],y=cy+(float)Math.sin(a)*r*hsv[1];p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(h.dp(5));p.setColor(BG);c.drawCircle(x,y,h.dp(10),p);p.setStrokeWidth(h.dp(2));p.setColor(TEXT);c.drawCircle(x,y,h.dp(10),p);p.setStyle(Paint.Style.FILL);}
        public boolean onTouchEvent(MotionEvent e){int action=e.getActionMasked();if(action==MotionEvent.ACTION_DOWN&&Math.hypot(e.getX()-cx,e.getY()-cy)>r+h.dp(12))return false;if(action==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);return true;}getParent().requestDisallowInterceptTouchEvent(true);float dx=e.getX()-cx,dy=e.getY()-cy;state.lights[channel].color=Color.HSVToColor(new float[]{(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360),Math.min(1,(float)Math.hypot(dx,dy)/r),1});state.lights[channel].effect=0;invalidate();if(colorLabel!=null)colorLabel.setText(hex(state.lights[channel].color));if(preview!=null)preview.invalidate();long now=SystemClock.uptimeMillis();if(action==MotionEvent.ACTION_UP||now-lastColor>120){if(!draft.isEmpty()||h.ble.readyCount()>0)submitLight();lastColor=now;}if(action==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(false);performClick();}return true;}
        public boolean performClick(){super.performClick();return true;}
    }
    final class Preview extends View {
        Paint p=new Paint(3);Preview(){super(h);setContentDescription("三路大灯预览，无螺丝孔位");}
        void glow(Canvas c,Path path,int color,int width){p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setColor((color&0xffffff)|0x25000000);p.setStrokeWidth(width*3);c.drawPath(path,p);p.setColor(color);p.setStrokeWidth(width);c.drawPath(path,p);p.setStyle(Paint.Style.FILL);}
        protected void onDraw(Canvas c){c.save();float s=Math.min(getWidth()/600f,getHeight()/250f);c.translate((getWidth()-600*s)/2,(getHeight()-250*s)/2);c.scale(s,s);p.setColor(0xff0b121a);c.drawRoundRect(20,42,580,220,55,55,p);p.setStyle(Paint.Style.STROKE);p.setColor(BORDER);p.setStrokeWidth(2);c.drawRoundRect(20,42,580,220,55,55,p);p.setStyle(Paint.Style.FILL);
            int[] tint=new int[3];for(int i=0;i<3;i++)tint[i]=state.lights[i].on?state.lights[i].color:0xff35414b;
            Path strip=new Path();strip.moveTo(52,72);strip.quadTo(300,39,548,72);glow(c,strip,tint[0],10);
            for(int i=0;i<3;i++){int x=175+i*125;p.setColor(0xff283542);c.drawRoundRect(x-51,99,x+51,199,12,12,p);p.setShader(new RadialGradient(x-10,130,60,new int[]{0xff8e9fab,0xff1b2936,0xff0b121b},null,Shader.TileMode.CLAMP));c.drawCircle(x,149,39,p);p.setShader(null);p.setColor(0x60e0edf4);c.drawRoundRect(x-20,123,x+1,131,3,3,p);p.setStyle(Paint.Style.STROKE);p.setColor(tint[2]);p.setStrokeWidth(3);c.drawCircle(x,149,40,p);p.setStyle(Paint.Style.FILL);}
            for(int side=0;side<2;side++){c.save();if(side==1){c.translate(600,0);c.scale(-1,1);}for(int i=0;i<7;i++){Path fin=new Path();fin.moveTo(47+i*2,103+i*12);fin.lineTo(92+i*3,106+i*12);glow(c,fin,tint[1],5);}c.restore();}c.restore();}
    }
}
