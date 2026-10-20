package com.suancaiyu.mosilalight;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,qualifiers="zh-rCN-w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppTest {
    ActivityController<MainActivity> controller;
    MainActivity activity;
    @Before public void setUp(){
        RuntimeEnvironment.getApplication().setTheme(android.R.style.Theme_Material_NoActionBar);
        RuntimeEnvironment.getApplication().getSharedPreferences("console",0).edit().putBoolean("onboarding_complete",true).putBoolean("hide_free_notice",true).commit();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
    }
    @After public void tearDown(){controller.pause().stop().destroy();}
    @Test public void allTabsRenderAndStartOffline() throws Exception {
        assertEquals(0,activity.ble.readyCount());
        // Assets are checked directly in the APK; standalone test has no Gradle resource merger.
        if(activity.modes.size()==1)for(int i=1;i<213;i++)activity.modes.add("灯效 "+(i+1));
        for(int tab=0;tab<4;tab++){
            activity.showTab(tab);render("tab-"+tab+".png");
            assertEquals(tab,activity.tab);assertTrue(activity.body.getChildCount()>0);
        }
        assertTrue(activity.connection.getText().toString().contains("连接设备"));
    }
    @Test public void dialogsOpen() {
        activity.enterColor();assertTrue(org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().isShowing());
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
        activity.chooseMode();assertTrue(activity.modePicker.isShowing());
        activity.modePicker.dismiss();
    }
    @Test public void leavingPageAndBackgroundStopsContinuousCommands(){
        activity.heldDirection=1;activity.showTab(2);assertEquals(-1,activity.heldDirection);
        activity.heldDirection=0;activity.onPause();assertEquals(-1,activity.heldDirection);assertTrue(activity.paused);
    }
    @Test public void freshColorUsesOriginalNonzeroDefault(){
        assertEquals(255,activity.intensity);
        activity.color=0xffff0000;
        assertEquals("69 96 05 02 FF 00 00 FF",Protocol.hex(activity.colorPacket()));
    }
    @Test public void legacyZeroCacheIsRepairedWhenUpgrading(){
        controller.pause().stop().destroy();
        android.content.SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("light",0);
        p.edit().clear().putInt("white",0).putInt("color",0xff12ab34).putInt("mode",10).commit();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        assertEquals(255,activity.intensity);assertEquals(10,activity.mode);
        assertEquals("69 96 05 02 12 AB 34 FF",Protocol.hex(activity.colorPacket()));
        assertFalse(p.contains("white"));assertEquals(255,p.getInt("colorIntensity",-1));
    }
    @Test public void nonzeroLegacySettingIsPreservedAcrossRestart(){
        controller.pause().stop().destroy();
        android.content.SharedPreferences p=RuntimeEnvironment.getApplication().getSharedPreferences("light",0);
        p.edit().clear().putInt("white",96).commit();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        assertEquals(96,activity.intensity);
        activity.save();controller.pause().stop().destroy();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        assertEquals(96,activity.intensity);
    }
    @Test public void intensitySliderDoesNotSendZero(){
        android.widget.SeekBar bar=findSeekBar(activity.body);
        assertNotNull(bar);assertEquals(254,bar.getMax());assertEquals(254,bar.getProgress());
        activity.intensity=1;activity.color=0xff00ff00;
        assertEquals("69 96 05 02 00 FF 00 01",Protocol.hex(activity.colorPacket()));
        assertTrue(activity.lamp.getContentDescription().toString().contains("三透镜"));
    }
    void realModes() throws Exception {
        org.json.JSONArray data=new org.json.JSONArray(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("../app/src/main/assets/modes.json")),"UTF-8"));
        activity.modes.clear();for(int i=0;i<data.length();i++)activity.modes.add(data.getString(i));
    }
    @Test public void chineseSearchMatchesMiddleAndRetainsOriginalIds() throws Exception {
        realModes();java.util.List<Integer> ids=ModeSearch.find(activity.modes,"流水");
        assertTrue(ids.size()>1);
        for(int id:ids)assertTrue(activity.modes.get(id).contains("流水"));
        assertEquals(ModeSearch.find(activity.modes,"流水"),ModeSearch.find(activity.modes," 流 水 "));
        assertEquals(java.util.Arrays.asList(212),ModeSearch.find(activity.modes,"２１３"));
        assertTrue(ModeSearch.find(activity.modes,"不存在的灯效").isEmpty());
        assertEquals(213,ModeSearch.find(activity.modes,"").size());
    }
    @Test public void pickerSearchAndTapApplyWithoutClosingOrAutoSelecting() throws Exception {
        realModes();activity.mode=17;activity.chooseMode();ModePicker picker=activity.modePicker;
        assertEquals(17,activity.mode);
        picker.search.setText("流水");assertEquals(17,activity.mode);
        int original=picker.matches.get(picker.matches.size()-1);
        picker.list.performItemClick(null,picker.matches.size(),original);
        assertEquals(original,activity.mode);assertTrue(picker.isShowing());
        assertEquals(original,activity.preferences.getInt("mode",-1));
        picker.search.setText("不存在的灯效");picker.selectPosition(0);assertEquals(original,activity.mode);
        assertEquals(View.VISIBLE,picker.empty.getVisibility());
        picker.search.setText("");assertEquals(213,picker.matches.size());assertEquals(original,activity.mode);
        assertTrue(picker.status.getText().toString().contains("未连接"));
        renderPicker("mode-picker.png");
        controller.pause();assertFalse(picker.isShowing());picker.selectPosition(0);assertEquals(original,activity.mode);
        controller.resume();
    }
    @Test public void wheelIdleAppliesCenteredModeAndKeepsLastModeReachable() throws Exception {
        realModes();activity.chooseMode();ModePicker picker=activity.modePicker;
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100,java.util.concurrent.TimeUnit.MILLISECONDS);
        renderPicker("mode-picker-initial.png");
        picker.center(212);org.robolectric.shadows.ShadowLooper.idleMainLooper(100,java.util.concurrent.TimeUnit.MILLISECONDS);renderPicker("mode-picker-last.png");
        assertEquals(212,picker.centerPosition());assertEquals(0,activity.mode);
        android.widget.AbsListView.OnScrollListener listener=org.robolectric.Shadows.shadowOf(picker.list).getOnScrollListener();
        listener.onScrollStateChanged(picker.list,android.widget.AbsListView.OnScrollListener.SCROLL_STATE_TOUCH_SCROLL);
        listener.onScrollStateChanged(picker.list,android.widget.AbsListView.OnScrollListener.SCROLL_STATE_IDLE);
        assertEquals(212,activity.mode);assertTrue(picker.isShowing());
        assertEquals("69 96 06 03 00 D4 06 01 01",Protocol.hex(Protocol.mode(activity.mode,activity.speed,activity.modeA,activity.modeB)));
        picker.center(0);org.robolectric.shadows.ShadowLooper.idleMainLooper(100,java.util.concurrent.TimeUnit.MILLISECONDS);renderPicker("mode-picker-first.png");assertEquals(0,picker.centerPosition());
    }
    void renderPicker(String name) throws Exception {
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100,java.util.concurrent.TimeUnit.MILLISECONDS);
        View v=activity.modePicker.content;int w=738;
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1500,View.MeasureSpec.AT_MOST));v.layout(0,0,w,v.getMeasuredHeight());
        Bitmap b=Bitmap.createBitmap(w,v.getMeasuredHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));
        File dir=new File("target/screens");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
    @Test public void openingAppStartsRememberedScanAndBackgroundStopsIt(){
        controller.pause().stop().destroy();
        RuntimeEnvironment.getApplication().getSharedPreferences("connection_memory",0).edit().putString("address","AA:BB:CC:DD:EE:01").putString("name","MOSILA").commit();
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
        org.robolectric.Shadows.shadowOf(android.bluetooth.BluetoothAdapter.getDefaultAdapter()).setEnabled(true);
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertTrue(activity.ble.isScanning());
        controller.pause();assertFalse(activity.ble.isScanning());controller.resume();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertFalse(activity.ble.isScanning());
    }
    BleController.Device connectForUi(){
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
        org.robolectric.Shadows.shadowOf(activity.ble.adapter).setEnabled(true);
        BleMemoryTest f=new BleMemoryTest();f.ble=activity.ble;activity.ble.startScan();f.emit(BleMemoryTest.A);
        BleController.Device d=f.find(BleMemoryTest.A);activity.ble.connect(d);f.ready(d);return d;
    }
    @Test public void powerToggleTracksSavedStateWithoutDoubleToggle(){
        BleController.Device d=connectForUi();activity.showTab(0);
        TextView toggle=findText(activity.body,"关闭灯光");assertNotNull(toggle);toggle.performClick();
        assertFalse(activity.power);assertEquals(0,activity.ble.profile(d).get("power")[4]);
        toggle.performClick();assertTrue(activity.power);assertEquals(1,activity.ble.profile(d).get("power")[4]);
    }
    @Test public void loadingEachDeviceRestoresItsOwnCompletePanel(){
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
        BleController.Device a=new BleController.Device(activity.ble.adapter.getRemoteDevice(BleMemoryTest.A));
        BleController.Device b=new BleController.Device(activity.ble.adapter.getRemoteDevice(BleMemoryTest.B));
        byte[][] data={Protocol.rgb(0xff123456,87),Protocol.mode(200,3,false,true),Protocol.startup(6,90,0xffaabbcc),Protocol.frame(5,0,1),Protocol.frame(6,1,0),Protocol.frame(8,0),Protocol.frame(9,0,0),Protocol.frame(9,1,1),Protocol.frame(10,18),Protocol.lineOrder(6),Protocol.power(false,0xff123456,87)};
        for(byte[] packet:data)activity.ble.profiles.record(a.address,packet);
        activity.loadDeviceProfile(a);
        assertEquals(0xff123456,activity.color);assertEquals(87,activity.intensity);assertEquals(200,activity.mode);assertEquals(3,activity.speed);
        assertFalse(activity.modeA);assertTrue(activity.modeB);assertFalse(activity.routeA);assertTrue(activity.routeB);assertFalse(activity.power);
        assertEquals(6,activity.startupSetting);assertEquals(90,activity.pointsSetting);assertEquals(0xffaabbcc,activity.startupColorSetting);
        assertEquals(0,activity.brakeSetting);assertEquals(1,activity.turnSetting);assertEquals(0,activity.selfCheckSetting);assertEquals(18,activity.level);assertEquals(6,activity.lineOrderSetting);assertEquals("mode",activity.activeOutput);
        activity.loadDeviceProfile(b);assertEquals(0xff70d8ef,activity.color);assertEquals(0,activity.mode);assertEquals(-1,activity.brakeSetting);assertEquals(-1,activity.lineOrderSetting);
        activity.loadDeviceProfile(a);assertEquals(200,activity.mode);assertEquals(6,activity.startupSetting);
    }
    @Test public void startupPickerChangesAutoSaveWithoutAnApplyClick(){
        BleController.Device d=connectForUi();activity.color=0xff123456;activity.showTab(2);
        java.util.ArrayList<android.widget.NumberPicker> pickers=new java.util.ArrayList<>();findPickers(activity.body,pickers);assertEquals(2,pickers.size());
        pickers.get(0).setValue(4);org.robolectric.Shadows.shadowOf(pickers.get(0)).getOnValueChangeListener().onValueChange(pickers.get(0),0,4);
        pickers.get(1).setValue(77);org.robolectric.Shadows.shadowOf(pickers.get(1)).getOnValueChangeListener().onValueChange(pickers.get(1),50,77);
        assertArrayEquals(Protocol.startup(4,77,0xff123456),activity.ble.profile(d).get("startup"));
    }
    void findPickers(View v,java.util.List<android.widget.NumberPicker> result){
        if(v instanceof android.widget.NumberPicker){result.add((android.widget.NumberPicker)v);return;}
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)findPickers(((ViewGroup)v).getChildAt(i),result);
    }
    TextView findText(View v,String value){
        if(v instanceof TextView&&((TextView)v).getText().toString().equals(value))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=findText(((ViewGroup)v).getChildAt(i),value);if(found!=null)return found;}
        return null;
    }
    @Test public void disconnectedChangesSaveToDeviceAndSurviveAppRestart(){
        BleController.Device d=connectForUi();activity.ble.disconnect(d);activity.editOfflineProfile(d.address);
        assertTrue(activity.send(Protocol.rgb(0xffaa1122,91),true,true));assertFalse(activity.lastSubmissionQueued);
        assertNull(activity.ble.profiles.load(d.address).get("color"));activity.confirmOfflineDraft();
        assertArrayEquals(Protocol.rgb(0xffaa1122,91),activity.ble.profiles.load(d.address).get("color"));
        activity.showTab(3);assertNotNull(findText(activity.body,"离线编辑已保存参数"));
        controller.pause().stop().destroy();
        controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        assertEquals(d.address,activity.displayedAddress);assertEquals(0xffaa1122,activity.color);assertEquals(91,activity.intensity);activity.showTab(0);
        assertTrue(activity.subtitle.getText().toString().contains("离线编辑"));
    }
    @Test public void editingOfflineDeviceDoesNotWriteToOtherConnectedDevice(){
        BleController.Device a=connectForUi();BleMemoryTest f=new BleMemoryTest();f.ble=activity.ble;
        activity.ble.startScan();f.emit(BleMemoryTest.B);BleController.Device b=f.find(BleMemoryTest.B);activity.ble.connect(b);f.ready(b);
        activity.ble.disconnect(a);activity.editOfflineProfile(a.address);assertEquals(0,activity.ble.readyCount());
        assertTrue(b.ready());assertEquals("编辑中",activity.connection.getText().toString());
        assertTrue(activity.send(Protocol.mode(112,7,false,true),true,true));assertFalse(activity.lastSubmissionQueued);activity.confirmOfflineDraft();
        assertArrayEquals(Protocol.mode(112,7,false,true),activity.ble.profile(a).get("mode"));assertNull(activity.ble.profile(b).get("mode"));
        assertNull(org.robolectric.Shadows.shadowOf(b.gatt).getLatestWrittenBytes());
    }
    @Test public void offlineModePickerReportsSavedRatherThanSent(){
        BleController.Device d=connectForUi();activity.ble.disconnect(d);activity.editOfflineProfile(d.address);activity.chooseMode();activity.modePicker.selectPosition(5);
        assertTrue(activity.modePicker.status.getText().toString().contains("待确认"));
        assertNull(activity.ble.profile(d).get("mode"));activity.confirmOfflineDraft();assertEquals("mode",activity.ble.profile(d).active);
    }
    @Test public void offlineConfirmAndCancelCoverAllPages() throws Exception {
        BleController.Device d=connectForUi();activity.send(Protocol.rgb(0xff123456,88),true,true);activity.ble.disconnect(d);
        String original=activity.ble.profiles.storage.getString(d.address,"");
        activity.editOfflineProfile(d.address);assertTrue(activity.isOfflineDraft());assertEquals("编辑中",activity.connection.getText().toString());
        assertNotNull(findText(activity.draftBar,"确认"));assertNotNull(findText(activity.draftBar,"取消"));
        activity.send(Protocol.rgb(0xffabcdef,120),true,true);activity.showTab(1);activity.send(Protocol.mode(17,3,false,true),true,true);
        activity.showTab(2);activity.send(Protocol.startup(4,66,0xff778899),true,true);activity.send(Protocol.frame(8,1),true,true);
        assertEquals(original,activity.ble.profiles.storage.getString(d.address,""));
        render("offline-draft.png");findText(activity.draftBar,"取消").performClick();assertFalse(activity.isOfflineDraft());assertNotEquals("编辑中",activity.connection.getText().toString());
        assertEquals(original,activity.ble.profiles.storage.getString(d.address,""));assertEquals(0xff123456,activity.color);
        activity.editOfflineProfile(d.address);activity.send(Protocol.rgb(0xffabcdef,120),true,true);activity.send(Protocol.mode(17,3,false,true),true,true);
        findText(activity.draftBar,"确认").performClick();assertFalse(activity.isOfflineDraft());
        assertArrayEquals(Protocol.rgb(0xffabcdef,120),activity.ble.profile(d).get("color"));assertArrayEquals(Protocol.mode(17,3,false,true),activity.ble.profile(d).get("mode"));
    }
    @Test public void backAndBackgroundDoNotCommitDraft(){
        BleController.Device d=connectForUi();activity.ble.disconnect(d);activity.editOfflineProfile(d.address);
        activity.send(Protocol.mode(42,4,true,true),true,true);controller.pause();assertNull(activity.ble.profile(d).get("mode"));controller.resume();
        activity.onBackPressed();assertFalse(activity.isOfflineDraft());assertNull(activity.ble.profile(d).get("mode"));
        activity.editOfflineProfile(d.address);activity.send(Protocol.mode(50,4,true,true),true,true);
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        assertFalse(activity.isOfflineDraft());assertNull(activity.ble.profiles.load(d.address).get("mode"));
    }
    @Test public void reconnectDoesNotWriteOrReplaceDraft(){
        BleController.Device d=connectForUi();activity.ble.disconnect(d);activity.editOfflineProfile(d.address);
        activity.send(Protocol.mode(15,5,true,true),true,true);activity.ble.connect(d);
        BleMemoryTest f=new BleMemoryTest();f.ble=activity.ble;f.ready(d);
        assertTrue(activity.isOfflineDraft());assertEquals(15,activity.mode);assertFalse(d.selected);assertNull(activity.ble.profile(d).get("mode"));
        activity.confirmOfflineDraft();assertArrayEquals(Protocol.mode(15,5,true,true),activity.ble.profile(d).get("mode"));
        assertNull(org.robolectric.Shadows.shadowOf(d.gatt).getLatestWrittenBytes());
    }
    @Test public void knownDeviceAndColdStartDoNotAutoEnterDraftWhenParametersAreTouched(){
        BleController.Device d=connectForUi();activity.send(Protocol.rgb(0xff123456,88),true,true);activity.ble.disconnect(d);
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        String before=activity.ble.profiles.storage.getString(d.address,"");
        assertFalse(activity.isOfflineDraft());assertEquals(View.GONE,activity.draftBar.getVisibility());
        for(byte[] p:new byte[][]{Protocol.rgb(0xffabcdef,99),Protocol.mode(20,5,true,false),Protocol.startup(3,55,0xff112233),Protocol.frame(10,11),Protocol.frame(5,0,1)}){
            assertFalse(activity.send(p,true,true));assertFalse(activity.isOfflineDraft());
        }
        activity.showTab(1);activity.chooseMode();activity.modePicker.selectPosition(8);assertFalse(activity.isOfflineDraft());
        assertTrue(activity.modePicker.status.getText().toString().contains("预览"));activity.modePicker.dismiss();
        assertEquals(before,activity.ble.profiles.storage.getString(d.address,""));assertNotEquals("编辑中",activity.connection.getText().toString());
        activity.showTab(3);TextView edit=findText(activity.body,"离线编辑已保存参数");assertNotNull(edit);edit.performClick();assertTrue(activity.isOfflineDraft());
        assertEquals("编辑中",activity.connection.getText().toString());
    }
    @Test public void consoleOpensBrandPickerAndBothBrandEntriesRender() throws Exception {
        assertNotNull(findText(activity.header,"console"));activity.brandEntry.performClick();assertTrue(activity.brandPickerOpen);
        assertNotNull(findText(activity.body,"mosila"));assertNotNull(findText(activity.body,"魔灯客"));assertEquals(View.GONE,activity.navigation.getVisibility());
        render("brand-picker.png");findText(activity.body,"进入 魔灯客  →").performClick();assertEquals(LampBrand.MODENGKE,activity.activeBrand);
        assertFalse(activity.brandPickerOpen);assertNotNull(findText(activity.body,"九号 N/F/M 大灯 3.0"));assertNotNull(findSeekBar(activity.body));assertEquals(View.VISIBLE,activity.navigation.getVisibility());
        render("modengke-control.png");activity.selectBrand(LampBrand.MOSILA);assertEquals(LampBrand.MOSILA,activity.activeBrand);assertNotNull(activity.wheel);
    }
    @Test public void changingBrandStopsBluetoothAndPreservesMosilaProfiles(){
        BleController.Device d=connectForUi();activity.send(Protocol.rgb(0xff123456,88),true,true);
        String profile=activity.ble.profiles.storage.getString(d.address,"");activity.showTab(2);
        activity.selectBrand(LampBrand.MODENGKE);assertFalse(d.ready());assertFalse(activity.ble.isScanning());assertTrue(activity.ble.isProtocolEnabled());
        assertFalse(activity.send(Protocol.mode(14,5,true,true),true,true));activity.requestScan();assertTrue(activity.ble.isScanning());activity.ble.stopScan();
        assertEquals(profile,activity.ble.profiles.storage.getString(d.address,""));assertEquals("modengke",activity.consolePreferences.getString("brand",""));
        activity.selectBrand(LampBrand.MOSILA);assertTrue(activity.ble.isProtocolEnabled());assertEquals(2,activity.tab);
        assertArrayEquals(Protocol.rgb(0xff123456,88),activity.ble.profile(d).get("color"));
    }
    @Test public void restartingInOtherBrandDoesNotScanRememberedMosila(){
        BleController.Device d=connectForUi();activity.selectBrand(LampBrand.MODENGKE);
        controller.pause().stop().destroy();controller=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();activity=controller.get();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();assertEquals(LampBrand.MODENGKE,activity.activeBrand);
        assertFalse(activity.ble.isScanning());assertTrue(activity.ble.isProtocolEnabled());assertEquals("",activity.ble.rememberedAddress());
        activity.showBrandPicker();assertTrue(activity.brandPickerOpen);activity.onBackPressed();assertFalse(activity.brandPickerOpen);
    }
    @Test public void draftMustFinishBeforeBrandSwitchAndSelectingSameBrandKeepsConnection(){
        BleController.Device d=connectForUi();activity.showBrandPicker();activity.selectBrand(LampBrand.MOSILA);assertTrue(d.ready());
        activity.ble.disconnect(d);activity.editOfflineProfile(d.address);activity.send(Protocol.mode(10,5,true,true),true,true);
        activity.showBrandPicker();assertFalse(activity.brandPickerOpen);activity.selectBrand(LampBrand.MODENGKE);assertEquals(LampBrand.MOSILA,activity.activeBrand);assertTrue(activity.isOfflineDraft());
        activity.cancelOfflineDraft();activity.showBrandPicker();assertTrue(activity.brandPickerOpen);
    }
    @Test public void lateMosilaConnectionDoesNotReplaceBrandPicker(){
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.BLUETOOTH_SCAN,android.Manifest.permission.BLUETOOTH_CONNECT);
        org.robolectric.Shadows.shadowOf(activity.ble.adapter).setEnabled(true);
        BleMemoryTest f=new BleMemoryTest();f.ble=activity.ble;activity.ble.startScan();f.emit(BleMemoryTest.A);BleController.Device d=f.find(BleMemoryTest.A);activity.ble.connect(d);
        activity.showBrandPicker();f.ready(d);assertTrue(activity.brandPickerOpen);assertNotNull(findText(activity.body,"魔灯客"));
    }
    android.widget.SeekBar findSeekBar(View v){
        if(v instanceof android.widget.SeekBar)return (android.widget.SeekBar)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){
            android.widget.SeekBar b=findSeekBar(((ViewGroup)v).getChildAt(i));if(b!=null)return b;
        }
        return null;
    }
    void render(String name) throws Exception {
        View v=activity.root;int w=786,h=1704;
        v.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));v.layout(0,0,w,h);
        Bitmap b=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);v.draw(new Canvas(b));
        File dir=new File("target/screens");dir.mkdirs();try(FileOutputStream out=new FileOutputStream(new File(dir,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}
    }
}
