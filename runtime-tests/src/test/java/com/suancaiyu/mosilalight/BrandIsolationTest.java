package com.suancaiyu.mosilalight;
import android.bluetooth.*;
import android.bluetooth.le.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class BrandIsolationTest {
    BleMemoryTest f;BleController ble;
    @Before public void setup(){f=new BleMemoryTest();f.setUp();ble=f.ble;}
    @After public void close(){ble.close();}
    ScanCallback callback(){return Shadows.shadowOf(ble.adapter.getBluetoothLeScanner()).getScanCallbacks().iterator().next();}
    ScanResult result(String address,String name,byte[] bytes){BluetoothDevice d=ble.adapter.getRemoteDevice(address);Shadows.shadowOf(d).setName(name);ScanRecord r=bytes==null?null:ReflectionHelpers.callStaticMethod(ScanRecord.class,"parseFromBytes",ReflectionHelpers.ClassParameter.from(byte[].class,bytes));return new ScanResult(d,r,-40,1);}
    boolean listed(String address){for(BleController.Device d:ble.devices())if(d.address.equals(address))return true;return false;}
    @Test public void rememberedMosilaNeverAppearsInMdkEvenWithoutNameOrService(){
        f.remember(BleMemoryTest.A);ble.profiles.rememberName(BleMemoryTest.A,"我的大灯");ble.profiles.record(BleMemoryTest.A,Protocol.rgb(0xff123456,88));
        ble.setBrand(LampBrand.MODENGKE);assertEquals("",ble.rememberedAddress());ble.startScan();f.emit(BleMemoryTest.A,null);assertFalse(listed(BleMemoryTest.A));f.emit(BleMemoryTest.B,"JHDD30");assertTrue(listed(BleMemoryTest.B));assertEquals("",ble.rememberedAddress());
        ble.setBrand(LampBrand.MOSILA);assertEquals(BleMemoryTest.A,ble.rememberedAddress());assertEquals(88,ble.profiles.load(BleMemoryTest.A).get("color")[7]&255);
    }
    @Test public void mdkRecordsDoNotAppearInMosilaAndClearingMemoryIsScoped(){
        ble.mdkProfiles.remember(BleMemoryTest.B,"我的九号");MdkState s=new MdkState();s.apply(s.light(0));ble.mdkProfiles.save(BleMemoryTest.B,s);
        f.context.getSharedPreferences("connection_memory_modengke_p58",0).edit().putString("address",BleMemoryTest.B).putString("name","JHDD30").commit();f.remember(BleMemoryTest.A);
        ble.startScan();f.emit(BleMemoryTest.B,null);assertFalse(listed(BleMemoryTest.B));ble.clearMemory();ble.setBrand(LampBrand.MODENGKE);assertEquals(BleMemoryTest.B,ble.rememberedAddress());assertTrue(ble.mdkProfiles.known(BleMemoryTest.B));
        ble.clearMemory();ble.setBrand(LampBrand.MOSILA);assertEquals("",ble.rememberedAddress());
    }
    @Test public void foreignBroadcastNamesAndServicesAreFilteredBothWays(){
        ble.startScan();f.emit(BleMemoryTest.A,"JHDD30");assertFalse(listed(BleMemoryTest.A));
        callback().onScanResult(0,result(BleMemoryTest.B,null,new byte[]{3,3,(byte)0xf0,(byte)0xff}));f.idle();assertFalse(listed(BleMemoryTest.B));
        ble.setBrand(LampBrand.MODENGKE);ble.startScan();f.emit(BleMemoryTest.A,"MOSILA");assertFalse(listed(BleMemoryTest.A));
        callback().onScanResult(0,result(BleMemoryTest.B,null,new byte[]{3,3,(byte)0xa0,(byte)0xee}));f.idle();assertFalse(listed(BleMemoryTest.B));
        callback().onScanResult(0,result(BleMemoryTest.B,null,new byte[]{3,3,(byte)0xf0,(byte)0xff}));f.idle();assertTrue(listed(BleMemoryTest.B));
    }
    @Test public void oldSessionCallbacksCannotPolluteOrStopNewBrandScan(){
        ble.startScan();ScanCallback old=callback();ScanResult a=result(BleMemoryTest.A,null,null);old.onScanResult(0,a); // queued before switching
        ble.setBrand(LampBrand.MODENGKE);ble.startScan();f.idle();assertTrue(ble.devices().isEmpty());
        old.onScanResult(0,a);old.onBatchScanResults(Collections.singletonList(a));old.onScanFailed(2);f.idle();assertTrue(ble.devices().isEmpty());assertTrue(ble.isScanning());
        f.emit(BleMemoryTest.B,"JHDD30");assertTrue(listed(BleMemoryTest.B));
    }
    @Test public void oldDeviceObjectCannotConnectAfterBrandSwitch(){
        ble.startScan();f.emit(BleMemoryTest.A);BleController.Device old=f.find(BleMemoryTest.A);ble.setBrand(LampBrand.MODENGKE);ble.connect(old);assertNull(old.gatt);assertEquals(0,ble.readyCount());
    }
    @Test public void namesAndLegacySharedCacheAreNotImportedAcrossBrands(){
        f.context.getSharedPreferences("bluetooth_names",0).edit().putString(BleMemoryTest.A,"旧版共享名字").commit();ble.startScan();f.emit(BleMemoryTest.A,"MOSILA CACHE");ble.setBrand(LampBrand.MODENGKE);ble.startScan();f.emit(BleMemoryTest.A,null);
        assertTrue(f.find(BleMemoryTest.A).label().contains("未命名"));
        ble.setBrand(LampBrand.MOSILA);ble.startScan();f.emit(BleMemoryTest.A,null);assertEquals("MOSILA CACHE",f.find(BleMemoryTest.A).label());
    }
}
