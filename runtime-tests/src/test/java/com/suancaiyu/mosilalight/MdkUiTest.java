package com.suancaiyu.mosilalight;
import android.view.*;
import android.widget.*;
import android.graphics.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MdkUiTest {
    ActivityController<MainActivity> c;MainActivity h;MdkUi ui;
    @Before public void setup(){RuntimeEnvironment.getApplication().setTheme(android.R.style.Theme_Material_NoActionBar);
        RuntimeEnvironment.getApplication().getSharedPreferences("console",0).edit().putBoolean("onboarding_complete",true).putBoolean("hide_free_notice",true).commit();c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();h=c.get();h.selectBrand(LampBrand.MODENGKE);ui=h.mdk;}
    @After public void close(){c.pause().stop().destroy();}
    void save(){MdkState s=new MdkState();s.lights[0].color=0xff123456;s.apply(s.light(0));h.ble.mdkProfiles.remember(BleMemoryTest.A,"JHDD30");h.ble.mdkProfiles.save(BleMemoryTest.A,s);ui.load(BleMemoryTest.A);}
    @Test public void previewChangesNeverOpenDraftOrOverwriteSavedDevice(){save();ui.state.lights[0].brightness=20;ui.submitLight();assertFalse(h.isOfflineDraft());assertEquals(View.GONE,h.draftBar.getVisibility());assertEquals(100,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].brightness);assertFalse(h.connection.getText().toString().contains("编辑中"));}
    @Test public void explicitDraftConfirmAndCancelHaveCompleteIsolation() throws Exception {
        save();ui.edit(BleMemoryTest.A);assertTrue(h.isOfflineDraft());assertEquals("编辑中",h.connection.getText().toString());ui.state.lights[0].brightness=26;ui.state.lights[0].flash=82;ui.state.lights[0].color=0xffabcdef;ui.submitLight();ui.state.boot=4;ui.submit("boot");ui.state.position=84;ui.submit("position");assertEquals(100,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].brightness);render("mdk-offline-edit.png");ui.finishDraft(false);assertEquals(100,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].brightness);assertFalse(h.isOfflineDraft());
        ui.edit(BleMemoryTest.A);ui.state.lights[0].effect=9;ui.state.lights[0].palette=new int[]{0xff110000,0xff002200};ui.state.lights[0].direction=4;ui.state.lights[0].width=88;ui.state.lights[0].speed=9;ui.state.lights[0].brightness=26;ui.submitLight();ui.state.boot=4;ui.submit("boot");ui.state.position=84;ui.submit("position");ui.finishDraft(true);
        MdkState loaded=h.ble.mdkProfiles.load(BleMemoryTest.A);assertEquals(26,loaded.lights[0].brightness);assertEquals(9,loaded.lights[0].effect);assertEquals(0xff123456,loaded.lights[0].color);assertArrayEquals(new int[]{0xff110000,0xff002200},loaded.lights[0].palette);assertEquals(4,loaded.lights[0].direction);assertEquals(88,loaded.lights[0].width);assertEquals(9,loaded.lights[0].speed);assertEquals(4,loaded.boot);assertEquals(84,loaded.position);assertEquals(3,loaded.restore().size());
    }
    @Test public void syncPreservesAllThreeModelSpecificCounts(){save();ui.edit(BleMemoryTest.A);ui.state.sync=true;ui.state.lights[0].color=0xff010203;ui.state.lights[0].brightness=67;ui.submitLight();ui.finishDraft(true);MdkState saved=h.ble.mdkProfiles.load(BleMemoryTest.A);for(int i=0;i<3;i++){assertEquals(67,saved.lights[i].brightness);assertEquals(MdkProtocol.COUNTS[i],saved.light(i)[21]&255);assertEquals(MdkProtocol.CHANNELS[i],saved.light(i)[13]&255);}assertEquals(3,saved.configured.size());}
    @Test public void chineseSearchAndScrollPickerKeepCorrectProtocolEffectId(){save();ui.edit(BleMemoryTest.A);h.showTab(1);ui.chooseEffect();ModePicker p=ui.picker;p.search.setText("渐变");assertEquals(java.util.Arrays.asList(3,4,8),p.matches);p.selectPosition(2);assertEquals(8,ui.state.lights[0].effect);assertTrue(p.isShowing());assertEquals(0,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].effect);p.search.setText("没有这个灯效");assertTrue(p.matches.isEmpty());p.selectPosition(0);assertEquals(8,ui.state.lights[0].effect);p.dismiss();ui.finishDraft(true);assertEquals(8,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].effect);}
    @Test public void brandSwitchAndBackRequireExplicitDraftResolution(){save();ui.edit(BleMemoryTest.A);ui.state.lights[0].brightness=12;ui.submitLight();h.selectBrand(LampBrand.MOSILA);assertEquals(LampBrand.MODENGKE,h.activeBrand);h.showBrandPicker();assertFalse(h.brandPickerOpen);h.onBackPressed();assertFalse(h.isOfflineDraft());assertEquals(100,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].brightness);h.selectBrand(LampBrand.MOSILA);assertEquals(LampBrand.MOSILA,h.activeBrand);}
    @Test public void allProductTabsRenderWithSeparateControls() throws Exception {for(int i=0;i<4;i++){h.showTab(i);render("mdk-tab-"+i+".png");assertTrue(h.body.getChildCount()>0);}h.showTab(2);assertNotNull(find(h.body,"大灯电调"));assertNotNull(find(h.body,"开机设置"));assertNull(find(h.body,"刹车灯"));}
    @Test public void connectionLogsAndPendingScanRequestsStayWithTheirBrand(){
        h.onLog("摩灯客专用日志");h.pendingScanBrand=LampBrand.MODENGKE;h.pendingRememberedScan=true;
        h.selectBrand(LampBrand.MOSILA);assertFalse(h.logText().contains("摩灯客专用日志"));assertNull(h.pendingScanBrand);assertFalse(h.pendingRememberedScan);
        h.onLog("MOSILA专用日志");h.selectBrand(LampBrand.MODENGKE);assertTrue(h.logText().contains("摩灯客专用日志"));assertFalse(h.logText().contains("MOSILA专用日志"));
    }
    TextView find(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),s);if(t!=null)return t;}return null;}
    void render(String name)throws Exception{View v=h.root;int w=786,height=1704;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,w,height);Bitmap b=Bitmap.createBitmap(w,height,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File dir=new File("target/screens");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
}
