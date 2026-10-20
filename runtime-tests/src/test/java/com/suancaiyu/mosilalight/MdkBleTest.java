package com.suancaiyu.mosilalight;
import android.bluetooth.*;
import android.os.SystemClock;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=MdkBleTest.MdkGatt.class)
public class MdkBleTest {
    @Implements(BluetoothGatt.class) public static class MdkGatt extends ShadowBluetoothGatt {
        final List<byte[]> writes=new ArrayList<>();final List<Long> times=new ArrayList<>();final List<Integer> types=new ArrayList<>();
        BluetoothGattDescriptor descriptor;int requested;
        @Implementation protected boolean requestMtu(int n){requested=n;return true;}
        @Implementation(minSdk=33) protected int writeDescriptor(BluetoothGattDescriptor d,byte[] v){descriptor=d;return 0;}
        @Implementation(minSdk=33) protected int writeCharacteristic(BluetoothGattCharacteristic c,byte[] value,int type){writes.add(value.clone());times.add(SystemClock.uptimeMillis());types.add(type);return 0;}
    }
    BleMemoryTest f;BleController ble;BleController.Device d;MdkGatt g;BluetoothGattCharacteristic notify,write;BluetoothGattCallback cb;
    @Before public void setup(){f=new BleMemoryTest();f.setUp();ble=f.ble;ble.setBrand(LampBrand.MODENGKE);}
    @After public void close(){ble.close();}
    void idle(){ShadowLooper.idleMainLooper();}void advance(long n){ShadowLooper.idleMainLooper(n,TimeUnit.MILLISECONDS);}
    void discover(){
        ble.startScan();f.emit(BleMemoryTest.A,"JHDD30");d=f.find(BleMemoryTest.A);ble.connect(d);g=(MdkGatt)Shadows.shadowOf(d.gatt);cb=g.getGattCallback();
        BluetoothGattService service=new BluetoothGattService(UUID.fromString(MdkProtocol.SERVICE),0);write=new BluetoothGattCharacteristic(UUID.fromString(MdkProtocol.WRITE),BluetoothGattCharacteristic.PROPERTY_WRITE,16);notify=new BluetoothGattCharacteristic(UUID.fromString(MdkProtocol.NOTIFY),BluetoothGattCharacteristic.PROPERTY_NOTIFY,1);notify.addDescriptor(new BluetoothGattDescriptor(GattProbe.CCCD,16));service.addCharacteristic(write);service.addCharacteristic(notify);g.addDiscoverableService(service);g.allowCharacteristicNotification(notify);
        cb.onConnectionStateChange(d.gatt,0,BluetoothProfile.STATE_CONNECTED);idle();cb.onServicesDiscovered(d.gatt,0);idle();
    }
    void query(){discover();cb.onMtuChanged(d.gatt,256,0);idle();assertNotNull(g.descriptor);cb.onDescriptorWrite(d.gatt,g.descriptor,0);idle();assertEquals(1,g.writes.size());assertArrayEquals(MdkProtocol.query(),g.writes.get(0));}
    void reply(int product){cb.onCharacteristicChanged(d.gatt,notify,MdkProtocol.frame(0xb9,new byte[]{1,0,0,1,0,(byte)product}));idle();}
    void written(){cb.onCharacteristicWrite(d.gatt,d.pendingWrite!=null?d.pendingWrite:d.write,0);idle();}
    @Test public void onlyOriginalQueryBeforeValidProductAndWriteCallback(){
        query();assertFalse(d.ready());assertEquals("",ble.rememberedAddress());assertFalse(ble.send(new MdkState().light(0),true,true));reply(58);assertFalse(d.ready());written();assertTrue(d.ready());assertEquals(d.address,ble.rememberedAddress());advance(1200);assertEquals(1,g.writes.size());assertTrue(ble.mdkProfiles.load(d.address).restore().isEmpty());
    }
    @Test public void wrongModelDisconnectsWithoutParameterWritesOrRemembering(){query();written();reply(48);assertFalse(d.ready());assertNull(d.gatt);assertEquals("",ble.rememberedAddress());advance(2000);assertEquals(1,g.writes.size());}
    @Test public void insufficientMtuNeverSendsEvenTheQuery(){discover();assertEquals(256,g.requested);cb.onMtuChanged(d.gatt,23,0);idle();assertNull(d.gatt);assertTrue(g.writes.isEmpty());}
    @Test public void noIdentityResponseTimesOutWithoutResendingOrRestoring(){MdkState s=new MdkState();s.apply(s.light(0));ble.mdkProfiles.save(BleMemoryTest.A,s);query();written();advance(5100);assertNull(d.gatt);assertEquals(1,g.writes.size());assertEquals(1,ble.mdkProfiles.load(d.address).restore().size());}
    @Test public void restoreIsSerializedAtFiveHundredMillisecondsAndManualChangesCoalesce(){
        MdkState saved=new MdkState();saved.apply(saved.packet("boot"));saved.apply(saved.light(0));ble.mdkProfiles.save(BleMemoryTest.A,saved);query();written();reply(58);assertTrue(d.ready());assertTrue(d.restoring);advance(699);assertEquals(1,g.writes.size());advance(1);assertEquals(2,g.writes.size());advance(800);assertEquals(2,g.writes.size());written();advance(499);assertEquals(2,g.writes.size());advance(1);assertEquals(3,g.writes.size());
        saved.lights[0].brightness=20;assertTrue(ble.send(saved.light(0),true,true));saved.lights[0].brightness=60;assertTrue(ble.send(saved.light(0),true,true));assertEquals(1,d.queue.size());assertEquals(60,ble.mdkProfiles.load(d.address).lights[0].brightness);written();advance(499);assertEquals(3,g.writes.size());advance(1);assertEquals(4,g.writes.size());assertEquals(60,g.writes.get(3)[22]);assertEquals((Integer)BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,g.types.get(3));
        assertFalse(ble.send(Protocol.rgb(0xff000000,255),true,true));
    }
    @Test public void switchingBrandSeparatesMemoryAndCancelsOldWrites(){query();written();reply(58);MdkState s=new MdkState();ble.send(s.light(0),true,true);ble.setBrand(LampBrand.MOSILA);advance(2000);assertFalse(d.ready());assertEquals(1,g.writes.size());assertEquals("",ble.rememberedAddress());ble.setBrand(LampBrand.MODENGKE);assertEquals(BleMemoryTest.A,ble.rememberedAddress());assertEquals(1,ble.mdkProfiles.load(BleMemoryTest.A).restore().size());}
}
