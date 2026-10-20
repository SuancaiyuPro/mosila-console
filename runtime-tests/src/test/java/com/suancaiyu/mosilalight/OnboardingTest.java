package com.suancaiyu.mosilalight;

import android.Manifest;
import android.app.Application;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class OnboardingTest {
    ActivityController<MainActivity> c;MainActivity h;
    @Before public void setup(){RuntimeEnvironment.getApplication().setTheme(android.R.style.Theme_Material_NoActionBar);}
    @After public void close(){if(c!=null)c.pause().stop().destroy();}
    void open(){c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();h=c.get();}
    void restart(){c.pause().stop().destroy();open();}
    TextView find(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),s);if(t!=null)return t;}return null;}
    void click(String label){TextView v=find(h.root,label);assertNotNull(label,v);v.performClick();}
    void complete(LampBrand brand){click("选择 "+brand.title+"  →");click("下一步：了解参数保存");click("进入设备页，开始使用");}
    @Test public void freshInstallMustChooseABrandBeforeControlsOrBluetooth() throws Exception {
        open();assertTrue(h.needsOnboarding());assertEquals(1,h.firstUse.step);assertNull(h.firstUse.choice);assertFalse(h.consolePreferences.contains("brand"));assertEquals(View.GONE,h.navigation.getVisibility());assertEquals(View.GONE,h.connection.getVisibility());assertNotNull(find(h.root,"先选择你的大灯品牌"));assertFalse(h.ble.isProtocolEnabled());
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT);Shadows.shadowOf(h.ble.adapter).setEnabled(true);
        h.getSharedPreferences("connection_memory",0).edit().putString("address",BleMemoryTest.A).commit();ShadowLooper.idleMainLooper();h.requestScan();h.beginScanRequest(true);assertFalse(h.ble.isScanning());assertFalse(h.send(Protocol.rgb(0xff123456,88),true,false));assertNull(Shadows.shadowOf(h).getLastRequestedPermission());
        h.showTab(3);assertTrue(h.needsOnboarding());assertNotNull(find(h.root,"先选择你的大灯品牌"));render("onboarding-brand.png");
    }
    @Test public void mosilaPathCompletesOnceAndManualBrandSwitchStillWorks(){
        open();complete(LampBrand.MOSILA);assertFalse(h.needsOnboarding());assertEquals("mosila",h.consolePreferences.getString("brand",""));assertEquals(3,h.tab);assertTrue(h.ble.isProtocolEnabled());assertEquals(View.VISIBLE,h.connection.getVisibility());assertNotNull(find(h.body,"连接，光就到位"));
        restart();assertFalse(h.needsOnboarding());assertEquals(LampBrand.MOSILA,h.activeBrand);h.brandEntry.performClick();assertTrue(h.brandPickerOpen);h.selectBrand(LampBrand.MODENGKE);assertEquals(LampBrand.MODENGKE,h.activeBrand);
    }
    @Test public void mdkPathUsesItsOwnModelAndProtocolOnlyAfterFinalStep() throws Exception {
        open();click("选择 魔灯客  →");assertEquals(2,h.firstUse.step);assertEquals(LampBrand.MODENGKE,h.firstUse.choice);assertFalse(h.ble.isProtocolEnabled());assertFalse(h.consolePreferences.contains("brand"));render("onboarding-connect.png");click("下一步：了解参数保存");render("onboarding-saving.png");click("进入设备页，开始使用");
        assertFalse(h.needsOnboarding());assertEquals(LampBrand.MODENGKE,h.activeBrand);assertEquals(LampBrand.MODENGKE,h.ble.brand);assertEquals(3,h.tab);assertTrue(h.ble.isProtocolEnabled());assertNotNull(find(h.body,"连接你的大灯"));restart();assertFalse(h.needsOnboarding());assertEquals(LampBrand.MODENGKE,h.activeBrand);
    }
    @Test public void interruptedGuideRestoresChoiceAndStepWithoutTreatingItAsAnUpgrade(){
        open();click("选择 魔灯客  →");click("下一步：了解参数保存");restart();assertTrue(h.needsOnboarding());assertEquals(3,h.firstUse.step);assertEquals(LampBrand.MODENGKE,h.firstUse.choice);assertFalse(h.ble.isProtocolEnabled());h.onBackPressed();assertEquals(2,h.firstUse.step);h.onBackPressed();assertEquals(1,h.firstUse.step);assertFalse(h.consolePreferences.getBoolean("onboarding_complete",true));
        click("选择 mosila  →");click("下一步：了解参数保存");click("进入设备页，开始使用");assertEquals(LampBrand.MOSILA,h.activeBrand);
    }
    @Test public void finishCannotSkipChoiceOrConnectionInstructions(){
        open();h.finishOnboarding();assertTrue(h.needsOnboarding());h.selectBrand(LampBrand.MODENGKE);h.finishOnboarding();assertTrue(h.needsOnboarding());assertEquals(2,h.firstUse.step);h.showBrandPicker();h.showTab(0);assertEquals(2,h.firstUse.step);assertFalse(h.ble.isProtocolEnabled());
    }
    @Test public void upgradingExistingInstallKeepsBrandParametersAndMemory(){
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("console",0).edit().putString("brand","modengke").putInt("mdkTab",2).commit();MdkProfiles profiles=new MdkProfiles(app.getSharedPreferences("modengke_p58_profiles",0));MdkState s=new MdkState();s.lights[0].brightness=67;s.apply(s.light(0));profiles.save(BleMemoryTest.A,s);app.getSharedPreferences("connection_memory_modengke_p58",0).edit().putString("address",BleMemoryTest.A).commit();
        open();assertFalse(h.needsOnboarding());assertEquals(LampBrand.MODENGKE,h.activeBrand);assertEquals(2,h.tab);assertEquals(67,h.ble.mdkProfiles.load(BleMemoryTest.A).lights[0].brightness);assertEquals(BleMemoryTest.A,h.ble.rememberedAddress());assertTrue(h.consolePreferences.getBoolean("onboarding_complete",false));
    }
    @Test public void originalMosilaInstallWithoutBrandPreferenceAlsoSkipsGuide(){
        Application app=RuntimeEnvironment.getApplication();app.getSharedPreferences("light",0).edit().putInt("color",0xff123456).commit();open();assertFalse(h.needsOnboarding());assertEquals(LampBrand.MOSILA,h.activeBrand);assertEquals(0xff123456,h.color);
    }
    void render(String name)throws Exception{View v=h.root;int w=786,height=1704;v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));v.layout(0,0,w,height);Bitmap b=Bitmap.createBitmap(w,height,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));File dir=new File("target/screens");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}}
}
