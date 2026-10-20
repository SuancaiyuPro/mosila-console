package com.suancaiyu.mosilalight;

import android.bluetooth.*;
import android.bluetooth.le.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class BleNamesTest {
    BleMemoryTest f;
    @Before public void setUp(){f=new BleMemoryTest();f.setUp();f.ble.startScan();}
    @After public void tearDown(){f.ble.close();}
    byte[] field(int type,String name){byte[] text=name.getBytes(StandardCharsets.UTF_8);byte[] raw=new byte[text.length+2];raw[0]=(byte)(text.length+1);raw[1]=(byte)type;System.arraycopy(text,0,raw,2,text.length);return raw;}
    void emit(String address,String system,byte[] raw,boolean batch){
        BluetoothDevice device=f.ble.adapter.getRemoteDevice(address);Shadows.shadowOf(device).setName(system);
        ScanRecord record=raw==null?null:ReflectionHelpers.callStaticMethod(ScanRecord.class,"parseFromBytes",ReflectionHelpers.ClassParameter.from(byte[].class,raw));
        ScanResult result=new ScanResult(device,record,-40,1);
        for(ScanCallback callback:new HashSet<>(Shadows.shadowOf(f.ble.adapter.getBluetoothLeScanner()).getScanCallbacks())){
            if(batch)callback.onBatchScanResults(Collections.singletonList(result));else callback.onScanResult(0,result);
        }
        f.idle();
    }
    @Test public void parserHandlesUtf8NamesAndMalformedFields(){
        assertEquals("魔斯拉大灯",BleNames.advertised(field(9,"魔斯拉大灯"),true));
        assertEquals("MOS",BleNames.advertised(field(8,"MOS"),false));assertNull(BleNames.advertised(field(8,"MOS"),true));
        assertNull(BleNames.advertised(new byte[]{100,9,1},true));assertNull(BleNames.advertised(new byte[]{0},true));
        assertEquals("MOSILA",BleNames.clean(" MOSILA\u0000 "));assertNull(BleNames.clean("\u0000 "));
    }
    @Test public void lateSystemCacheNameUpdatesAnAlreadySeenDevice(){
        emit(BleMemoryTest.A,null,null,false);BleController.Device first=f.find(BleMemoryTest.A);assertTrue(first.label().contains("未命名"));
        emit(BleMemoryTest.A,"MOSILA",null,false);assertSame(first,f.find(BleMemoryTest.A));assertEquals("MOSILA",first.label());
    }
    @Test public void completeNameIsNotReplacedByShortOrEmptyAdvertisements(){
        emit(BleMemoryTest.A,null,field(9,"MOSILA FULL"),false);assertEquals("MOSILA FULL",f.find(BleMemoryTest.A).label());
        emit(BleMemoryTest.A,null,field(8,"MOS"),false);assertEquals("MOSILA FULL",f.find(BleMemoryTest.A).label());
        emit(BleMemoryTest.A,null,null,false);assertEquals("MOSILA FULL",f.find(BleMemoryTest.A).label());
    }
    @Test public void cachedScanNameSurvivesControllerRestartWithoutInventingParameterProfile(){
        emit(BleMemoryTest.A,null,field(9,"MOSILA CACHE"),false);assertFalse(f.ble.profiles.known(BleMemoryTest.A));
        f.ble.close();f.ble=new BleController(f.context,f.listener);f.ble.startScan();
        emit(BleMemoryTest.A,null,null,false);assertEquals("MOSILA CACHE",f.find(BleMemoryTest.A).label());assertFalse(f.ble.profiles.known(BleMemoryTest.A));
    }
    @Test public void savedProfileNameIsUsedWhenDeviceBroadcastsNoName(){
        f.ble.profiles.rememberName(BleMemoryTest.A,"我的大灯");emit(BleMemoryTest.A,null,null,false);assertEquals("我的大灯",f.find(BleMemoryTest.A).label());
    }
    @Test public void batchResultsResolveBroadcastNames(){
        emit(BleMemoryTest.A,null,field(9,"MOSILA BATCH"),true);assertEquals("MOSILA BATCH",f.find(BleMemoryTest.A).label());
    }
    @Test public void trulyNamelessDevicesAreDistinguishableByAddress(){
        emit(BleMemoryTest.A,null,null,false);emit(BleMemoryTest.B,null,null,false);
        assertNotEquals(f.find(BleMemoryTest.A).label(),f.find(BleMemoryTest.B).label());
        assertTrue(f.find(BleMemoryTest.A).label().contains("DD:EE:01"));
    }
}
