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
@Config(sdk=35,manifest=Config.NONE,shadows=ProfileRestoreTest.RecordingGatt.class)
public class ProfileRestoreTest {
    @Implements(BluetoothGatt.class)
    public static class RecordingGatt extends ShadowBluetoothGatt {
        final List<byte[]> writes=new ArrayList<>();
        final List<Long> times=new ArrayList<>();
        int result=0;
        @Implementation(minSdk=33) protected int writeCharacteristic(BluetoothGattCharacteristic c,byte[] value,int type){
            writes.add(value.clone());times.add(SystemClock.uptimeMillis());return result;
        }
    }
    BleMemoryTest fixture;
    BleController ble;
    @Before public void setUp(){fixture=new BleMemoryTest();fixture.setUp();ble=fixture.ble;}
    @After public void tearDown(){ble.close();}
    BleController.Device connect(String address){ble.startScan();fixture.emit(address);BleController.Device d=fixture.find(address);ble.connect(d);fixture.ready(d);return d;}
    RecordingGatt shadow(BleController.Device d){return (RecordingGatt)Shadows.shadowOf(d.gatt);}
    void advance(long ms){ShadowLooper.idleMainLooper(ms,TimeUnit.MILLISECONDS);}
    void callback(BleController.Device d,int status){shadow(d).getGattCallback().onCharacteristicWrite(d.gatt,d.write,status);fixture.idle();}
    @Test public void noProfileMeansNoAutomaticWrites(){
        BleController.Device a=connect(BleMemoryTest.A);advance(1000);assertTrue(shadow(a).writes.isEmpty());assertFalse(a.restoring);
    }
    @Test public void restoreWaitsForEveryCallbackAndFourHundredMilliseconds(){
        ble.profiles.record(BleMemoryTest.A,Protocol.frame(5,1,0));ble.profiles.record(BleMemoryTest.A,Protocol.mode(17,9,false,true));
        BleController.Device a=connect(BleMemoryTest.A);RecordingGatt g=shadow(a);assertTrue(a.restoring);
        advance(699);assertEquals(0,g.writes.size());advance(1);assertEquals(1,g.writes.size());assertArrayEquals(Protocol.frame(5,1,0),g.writes.get(0));
        advance(900);assertEquals(1,g.writes.size());callback(a,0);
        advance(399);assertEquals(1,g.writes.size());advance(1);assertEquals(2,g.writes.size());assertArrayEquals(Protocol.mode(17,9,false,true),g.writes.get(1));
        assertTrue(g.times.get(1)-g.times.get(0)>=400);callback(a,0);assertFalse(a.restoring);assertTrue(fixture.logs.stream().anyMatch(s->s.contains("无设备执行确认")));
    }
    @Test public void twoDevicesRestoreOwnPacketsOnly(){
        ble.profiles.record(BleMemoryTest.A,Protocol.rgb(0xffff0000,180));ble.profiles.record(BleMemoryTest.B,Protocol.mode(212,2,true,false));
        BleController.Device a=connect(BleMemoryTest.A),b=connect(BleMemoryTest.B);advance(700);
        assertArrayEquals(Protocol.rgb(0xffff0000,180),shadow(a).writes.get(0));assertArrayEquals(Protocol.mode(212,2,true,false),shadow(b).writes.get(0));
        callback(a,0);callback(b,0);assertFalse(a.restoring);assertFalse(b.restoring);
    }
    @Test public void editsDuringRestoreWaitAndSaveOnlySelectedDevice(){
        ble.profiles.record(BleMemoryTest.A,Protocol.mode(1,5,true,true));
        BleController.Device a=connect(BleMemoryTest.A),b=connect(BleMemoryTest.B);b.selected=false;
        assertTrue(ble.send(Protocol.rgb(0xff001122,200),true,true));assertArrayEquals(Protocol.rgb(0xff001122,200),ble.profile(a).get("color"));assertNull(ble.profile(b).get("color"));
        advance(700);assertEquals(1,shadow(a).writes.size());assertArrayEquals(Protocol.mode(1,5,true,true),shadow(a).writes.get(0));
        callback(a,0);advance(400);assertEquals(2,shadow(a).writes.size());assertArrayEquals(Protocol.rgb(0xff001122,200),shadow(a).writes.get(1));callback(a,0);
        assertEquals("color",ble.profile(a).active);assertTrue(shadow(b).writes.isEmpty());
    }
    @Test public void coalescedControlsSaveLastValueAndKeepDistinctChannels(){
        BleController.Device a=connect(BleMemoryTest.A);
        ble.send(Protocol.rgb(0xff000001,255),true,false);
        ble.send(Protocol.rgb(0xff000002,255),true,false);ble.send(Protocol.rgb(0xff000003,255),true,false);
        ble.send(Protocol.frame(9,0,0),true,false);ble.send(Protocol.frame(9,1,1),true,false);
        assertEquals(3,a.queue.size());assertArrayEquals(Protocol.rgb(0xff000003,255),ble.profile(a).get("color"));
        callback(a,0);advance(150);assertArrayEquals(Protocol.rgb(0xff000003,255),shadow(a).writes.get(1));
    }
    @Test public void failedRestoreStopsQueueWithoutDiscardingSavedParameters(){
        ble.profiles.record(BleMemoryTest.A,Protocol.frame(8,0));ble.profiles.record(BleMemoryTest.A,Protocol.mode(5,4,true,true));
        BleController.Device a=connect(BleMemoryTest.A);advance(700);callback(a,133);advance(1000);
        assertEquals(1,shadow(a).writes.size());assertFalse(a.restoring);assertTrue(a.restoreQueue.isEmpty());assertEquals(2,ble.profile(a).savedCount());
        assertTrue(fixture.logs.stream().anyMatch(s->s.contains("恢复中断")));
    }
    @Test public void missingCallbackDisconnectsAndDoesNotBlindlyResend(){
        ble.profiles.record(BleMemoryTest.A,Protocol.mode(5,4,true,true));BleController.Device a=connect(BleMemoryTest.A);RecordingGatt g=shadow(a);
        advance(3300);assertEquals(1,g.writes.size());assertFalse(a.ready());assertFalse(a.restoring);assertEquals(1,ble.profile(a).savedCount());
    }
    @Test public void reconnectReloadsAllSavedSettingsAndDisconnectCancelsOldQueue(){
        ble.profiles.record(BleMemoryTest.A,Protocol.frame(5,0,1));ble.profiles.record(BleMemoryTest.A,Protocol.mode(8,5,true,false));
        BleController.Device a=connect(BleMemoryTest.A);RecordingGatt old=shadow(a);advance(700);ble.disconnect(a);advance(1000);assertEquals(1,old.writes.size());
        ble.connect(a);fixture.ready(a);advance(700);RecordingGatt again=shadow(a);assertNotSame(old,again);assertEquals(1,again.writes.size());assertArrayEquals(Protocol.frame(5,0,1),again.writes.get(0));
    }
    @Test public void immediateWriteRejectionStopsRestore(){
        ble.profiles.record(BleMemoryTest.A,Protocol.mode(8,5,true,false));BleController.Device a=connect(BleMemoryTest.A);RecordingGatt g=shadow(a);g.result=1;
        advance(700);assertFalse(a.restoring);advance(800);assertEquals(1,g.writes.size());assertEquals(1,ble.profile(a).savedCount());
    }
    @Test public void rapidPersistentSwitchesCoalesceEvenWithoutPreviewFlag(){
        BleController.Device a=connect(BleMemoryTest.A);ble.send(Protocol.rgb(0xff112233,255),true,false);
        for(int i=0;i<50;i++)assertTrue(ble.send(Protocol.frame(9,0,i%2),false,false));
        assertEquals(1,a.queue.size());assertArrayEquals(Protocol.frame(9,0,1),ble.profile(a).get("routeA"));
        callback(a,0);advance(150);assertEquals(2,shadow(a).writes.size());assertArrayEquals(Protocol.frame(9,0,1),shadow(a).writes.get(1));
    }

    @Test public void disablingProtocolCancelsPendingRestoreAndBlocksEveryTransportEntry(){
        ble.profiles.record(BleMemoryTest.A,Protocol.mode(8,5,true,true));BleController.Device a=connect(BleMemoryTest.A);RecordingGatt old=shadow(a);
        ble.setProtocolEnabled(false);advance(1200);assertFalse(a.ready());assertTrue(old.writes.isEmpty());
        ble.startScan();ble.startRememberedScan();ble.connect(a);assertFalse(ble.isScanning());assertNull(a.gatt);
        assertFalse(ble.send(Protocol.rgb(0xff112233,255),true,true));assertNull(ble.profile(a).get("color"));
        ble.setProtocolEnabled(true);ble.connect(a);fixture.ready(a);advance(700);assertArrayEquals(Protocol.mode(8,5,true,true),shadow(a).writes.get(0));
    }

}
