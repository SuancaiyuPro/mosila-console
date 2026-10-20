package com.suancaiyu.mosilalight;

import android.bluetooth.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=GattProbeTest.ProbeGatt.class)
public class GattProbeTest {
    @Implements(BluetoothGatt.class)
    public static class ProbeGatt extends ShadowBluetoothGatt {
        final List<BluetoothGattCharacteristic> reads=new ArrayList<>();
        BluetoothGattDescriptor descriptor;
        byte[] descriptorValue;
        @Implementation protected boolean readCharacteristic(BluetoothGattCharacteristic c){reads.add(c);return true;}
        @Implementation(minSdk=33) protected int writeDescriptor(BluetoothGattDescriptor d,byte[] value){descriptor=d;descriptorValue=value.clone();return BluetoothStatusCodes.SUCCESS;}
    }
    BleMemoryTest fixture;
    BleController ble;
    BleController.Device device;
    ProbeGatt shadow;
    BluetoothGattService service;
    @Before public void setUp(){
        fixture=new BleMemoryTest();fixture.setUp();ble=fixture.ble;
        ble.startScan();fixture.emit(BleMemoryTest.A);device=fixture.find(BleMemoryTest.A);ble.connect(device);fixture.ready(device);
        shadow=(ProbeGatt)Shadows.shadowOf(device.gatt);service=device.gatt.getService(UUID.fromString(Protocol.SERVICE));
    }
    @After public void tearDown(){ble.close();}
    void idle(){ShadowLooper.idleMainLooper();}
    BluetoothGattCharacteristic add(int suffix,int properties){
        BluetoothGattCharacteristic c=new BluetoothGattCharacteristic(UUID.fromString(String.format(Locale.ROOT,"0000ee%02x-0000-1000-8000-00805f9b34fb",suffix)),properties,BluetoothGattCharacteristic.PERMISSION_READ);
        service.addCharacteristic(c);return c;
    }
    boolean logged(String value){return fixture.logs.stream().anyMatch(s->s.contains(value));}
    @Test public void writeOnlyLampReportsNoReadbackAndSendsNoCommands(){
        ble.probeState(device);assertFalse(ble.isProbing());assertTrue(shadow.reads.isEmpty());assertNull(shadow.getLatestWrittenBytes());assertTrue(logged("未提供可读特征"));
    }
    @Test public void readsAreSerializedControlsWaitAndRawDataIsLogged(){
        BluetoothGattCharacteristic first=add(2,BluetoothGattCharacteristic.PROPERTY_READ),second=add(3,BluetoothGattCharacteristic.PROPERTY_READ);
        ble.probeState(device);assertTrue(ble.isProbing());assertEquals(Arrays.asList(first),shadow.reads);
        assertFalse(ble.send(Protocol.mode(10,5,true,true),true,true));assertNull(shadow.getLatestWrittenBytes());
        shadow.getGattCallback().onCharacteristicRead(device.gatt,second,new byte[]{99},0);idle();assertEquals(1,shadow.reads.size());
        shadow.getGattCallback().onCharacteristicRead(device.gatt,first,new byte[]{0x69,(byte)0x96,3},0);idle();
        assertEquals(Arrays.asList(first,second),shadow.reads);assertTrue(logged("data=69 96 03"));
        shadow.getGattCallback().onCharacteristicRead(device.gatt,second,new byte[0],0);idle();assertFalse(ble.isProbing());assertTrue(device.ready());
        assertTrue(logged("data=(空)"));assertNull(shadow.getLatestWrittenBytes());
    }
    @Test public void failedReadCompletesWithoutInterpretingPayload(){
        BluetoothGattCharacteristic c=add(2,BluetoothGattCharacteristic.PROPERTY_READ);ble.probeState(device);
        shadow.getGattCallback().onCharacteristicRead(device.gatt,c,new byte[]{(byte)212},BluetoothGatt.GATT_READ_NOT_PERMITTED);idle();
        assertFalse(ble.isProbing());assertTrue(device.ready());assertTrue(logged("status=2"));
    }
    @Test public void subscribesUsingStandardCccdThenReadsAndLogsNotifications(){
        BluetoothGattCharacteristic c=add(2,BluetoothGattCharacteristic.PROPERTY_READ|BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        BluetoothGattDescriptor descriptor=new BluetoothGattDescriptor(GattProbe.CCCD,BluetoothGattDescriptor.PERMISSION_WRITE);c.addDescriptor(descriptor);shadow.allowCharacteristicNotification(c);
        ble.probeState(device);assertSame(descriptor,shadow.descriptor);assertArrayEquals(new byte[]{1,0},shadow.descriptorValue);assertTrue(shadow.reads.isEmpty());
        shadow.getGattCallback().onDescriptorWrite(device.gatt,descriptor,0);idle();assertEquals(Arrays.asList(c),shadow.reads);
        shadow.getGattCallback().onCharacteristicRead(device.gatt,c,new byte[]{4},0);idle();assertFalse(ble.isProbing());
        shadow.getGattCallback().onCharacteristicChanged(device.gatt,c,new byte[]{5,6});idle();assertTrue(logged("data=05 06"));assertNull(shadow.getLatestWrittenBytes());
    }
    @Test public void indicatesUseTwoAndAbsentCccdIsSkipped(){
        add(2,BluetoothGattCharacteristic.PROPERTY_NOTIFY);
        BluetoothGattCharacteristic c=add(3,BluetoothGattCharacteristic.PROPERTY_INDICATE);
        BluetoothGattDescriptor descriptor=new BluetoothGattDescriptor(GattProbe.CCCD,BluetoothGattDescriptor.PERMISSION_WRITE);c.addDescriptor(descriptor);shadow.allowCharacteristicNotification(c);
        ble.probeState(device);assertTrue(logged("没有 CCCD"));assertArrayEquals(new byte[]{2,0},shadow.descriptorValue);
        shadow.getGattCallback().onDescriptorWrite(device.gatt,descriptor,0);idle();assertFalse(ble.isProbing());
    }
    @Test public void timeoutClosesAmbiguousGattAndRetainsConnectionMemory(){
        add(2,BluetoothGattCharacteristic.PROPERTY_READ);ble.probeState(device);ShadowLooper.idleMainLooper(6,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(ble.isProbing());assertFalse(device.ready());assertTrue(shadow.isClosed());assertEquals(BleMemoryTest.A,ble.rememberedAddress());assertTrue(logged("探测超时"));
    }
    @Test public void disconnectCancelsReadAndIgnoresLateCallbacks(){
        BluetoothGattCharacteristic c=add(2,BluetoothGattCharacteristic.PROPERTY_READ);BluetoothGatt gatt=device.gatt;
        ble.probeState(device);ble.disconnect(device);shadow.getGattCallback().onCharacteristicRead(gatt,c,new byte[]{55},0);idle();
        assertFalse(ble.isProbing());assertFalse(logged("data=37"));ShadowLooper.idleMainLooper(6,java.util.concurrent.TimeUnit.SECONDS);assertFalse(logged("探测超时"));
    }
}
