package com.suancaiyu.mosilalight;

import android.Manifest;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class BleMemoryTest {
    static final String A="AA:BB:CC:DD:EE:01", B="AA:BB:CC:DD:EE:02";
    Context context;
    BleController ble;
    ArrayList<String> logs=new ArrayList<>();
    BleController.Listener listener=new BleController.Listener(){public void onChanged(){}public void onLog(String s){logs.add(s);}};
    @Before public void setUp(){
        context=RuntimeEnvironment.getApplication();
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT);
        context.getSharedPreferences("connection_memory",0).edit().clear().commit();
        ble=new BleController(context,listener);Shadows.shadowOf(ble.adapter).setEnabled(true);
    }
    @After public void tearDown(){ble.close();}
    void idle(){ShadowLooper.idleMainLooper();}
    void remember(String address){context.getSharedPreferences("connection_memory",0).edit().putString("address",address).putString("name","MOSILA").commit();}
    BluetoothDevice emit(String address){return emit(address,"MOSILA");}
    BluetoothDevice emit(String address,String name){
        BluetoothDevice device=ble.adapter.getRemoteDevice(address);Shadows.shadowOf(device).setName(name);
        ScanResult result=new ScanResult(device,null,-50,1);
        Set<ScanCallback> callbacks=new HashSet<>(Shadows.shadowOf(ble.adapter.getBluetoothLeScanner()).getScanCallbacks());
        for(ScanCallback callback:callbacks)callback.onScanResult(0,result);idle();return device;
    }
    BleController.Device find(String address){for(BleController.Device d:ble.devices())if(d.address.equals(address))return d;throw new AssertionError(address);}
    void ready(BleController.Device d){
        ShadowBluetoothGatt shadow=Shadows.shadowOf(d.gatt);
        BluetoothGattService service=new BluetoothGattService(UUID.fromString(Protocol.SERVICE),BluetoothGattService.SERVICE_TYPE_PRIMARY);
        service.addCharacteristic(new BluetoothGattCharacteristic(UUID.fromString(Protocol.WRITE),BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,BluetoothGattCharacteristic.PERMISSION_WRITE));
        shadow.addDiscoverableService(service);
        shadow.getGattCallback().onConnectionStateChange(d.gatt,0,BluetoothProfile.STATE_CONNECTED);idle();
        shadow.getGattCallback().onServicesDiscovered(d.gatt,0);idle();
        assertTrue(d.ready());assertNull(shadow.getLatestWrittenBytes());
    }
    @Test public void onlyValidatedSuccessfulConnectionIsRememberedAndPersists(){
        ble.startScan();emit(A);BleController.Device a=find(A);ble.connect(a);
        assertEquals("",ble.rememberedAddress());ready(a);assertEquals(A,ble.rememberedAddress());
        ble.close();ble=new BleController(context,listener);assertEquals(A,ble.rememberedAddress());
        ble.startScan();emit(B);BleController.Device b=find(B);ble.connect(b);
        Shadows.shadowOf(b.gatt).getGattCallback().onServicesDiscovered(b.gatt,0);idle();
        assertFalse(b.ready());assertEquals(A,ble.rememberedAddress());
    }
    @Test public void rememberedScanIgnoresSameNameAndConnectsOnlyMatchingAddress(){
        remember(A);ble.startRememberedScan();assertTrue(ble.isScanning());
        emit(B);assertNull(find(B).gatt);assertTrue(ble.isScanning());
        emit(A);assertNotNull(find(A).gatt);assertFalse(ble.isScanning());
        ready(find(A));assertEquals(1,ble.readyCount());
    }
    @Test public void ordinaryScanDoesNotAutoConnectAndClearDisablesMemory(){
        remember(A);ble.startScan();emit(A);assertNull(find(A).gatt);
        ble.stopScan();ble.startRememberedScan();ble.clearMemory();assertFalse(ble.isScanning());
        assertEquals("",ble.rememberedAddress());ble.startRememberedScan();assertFalse(ble.isScanning());
    }
    @Test public void timeoutStopsAndRetainsMemoryWithoutRetry(){
        remember(A);ble.startRememberedScan();ShadowLooper.idleMainLooper(16,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(ble.isScanning());assertEquals(A,ble.rememberedAddress());
        assertTrue(logs.stream().anyMatch(s->s.contains("未找到上次")));
        ShadowLooper.idleMainLooper(60,java.util.concurrent.TimeUnit.SECONDS);assertFalse(ble.isScanning());
    }
    @Test public void stoppedScanIgnoresLateAdvertisements(){
        remember(A);ble.startRememberedScan();
        ScanCallback callback=Shadows.shadowOf(ble.adapter.getBluetoothLeScanner()).getScanCallbacks().iterator().next();
        ble.stopScan();callback.onScanResult(0,new ScanResult(ble.adapter.getRemoteDevice(A),null,-40,1));idle();
        assertTrue(ble.devices().isEmpty());assertEquals(A,ble.rememberedAddress());
    }
}
