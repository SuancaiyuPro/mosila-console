package com.suancaiyu.mosilalight;
import android.app.AlertDialog;
import android.bluetooth.*;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE,shadows=ProfileRestoreTest.RecordingGatt.class)
public class DeleteProfilesTest {
    AppTest fixture;MainActivity h;
    @Before public void setup(){fixture=new AppTest();fixture.setUp();h=fixture.activity;}
    @After public void close(){fixture.tearDown();}
    void seed(){
        h.ble.profiles.rememberName(BleMemoryTest.A,"MOSILA A");h.ble.profiles.record(BleMemoryTest.A,Protocol.rgb(0xff123456,88));h.ble.profiles.record(BleMemoryTest.B,Protocol.mode(3,4,true,false));
        MdkState s=new MdkState();s.apply(s.light(0));h.ble.mdkProfiles.remember(BleMemoryTest.A,"JHDD30 A");h.ble.mdkProfiles.save(BleMemoryTest.A,s);
        h.getSharedPreferences("connection_memory",0).edit().putString("address",BleMemoryTest.A).commit();h.getSharedPreferences("connection_memory_modengke_p58",0).edit().putString("address",BleMemoryTest.A).commit();
    }
    AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    void click(int which){dialog().getButton(which).performClick();ShadowLooper.idleMainLooper();}
    @Test public void cancelPreservesAllDataAndConfirmDeletesOnlyTargetBrandAndAddress(){
        seed();h.loadProfileAddress(BleMemoryTest.A);h.showTab(3);assertNotNull(find(h.body,"删除已保存参数"));
        h.confirmDeleteProfile(BleMemoryTest.A);click(AlertDialog.BUTTON_NEGATIVE);assertTrue(h.ble.profiles.known(BleMemoryTest.A));assertTrue(h.ble.mdkProfiles.known(BleMemoryTest.A));
        h.confirmDeleteProfile(BleMemoryTest.A);click(AlertDialog.BUTTON_POSITIVE);assertFalse(h.ble.profiles.known(BleMemoryTest.A));assertTrue(h.ble.profiles.known(BleMemoryTest.B));assertTrue(h.ble.mdkProfiles.known(BleMemoryTest.A));assertEquals(BleMemoryTest.A,h.ble.rememberedAddress());assertEquals("",h.displayedAddress);assertFalse(h.logText().isEmpty());
    }
    @Test public void mdkDeletionClearsEntireStateAndUiCacheButKeepsMemoryAndOtherBrand(){
        seed();h.selectBrand(LampBrand.MODENGKE);h.mdk.load(BleMemoryTest.A);h.showTab(3);assertNotNull(find(h.body,"删除已保存参数"));h.confirmDeleteProfile(BleMemoryTest.A);click(AlertDialog.BUTTON_POSITIVE);
        assertFalse(h.ble.mdkProfiles.known(BleMemoryTest.A));assertTrue(h.ble.mdkProfiles.load(BleMemoryTest.A).restore().isEmpty());assertEquals("JHDD30 A",h.ble.mdkProfiles.name(BleMemoryTest.A));assertEquals(BleMemoryTest.A,h.ble.rememberedAddress());assertTrue(h.ble.profiles.known(BleMemoryTest.A));assertEquals("",h.mdk.address);assertFalse(h.consolePreferences.contains("mdkAddress"));assertNull(find(h.body,"删除已保存参数"));
    }
    @Test public void pendingRestoreStopsAndDeletedSettingsDoNotReturnOnReconnect(){
        h.ble.profiles.record(BleMemoryTest.A,Protocol.mode(8,5,true,true));BleController.Device d=fixture.connectForUi();BluetoothGatt old=d.gatt;ProfileRestoreTest.RecordingGatt shadow=(ProfileRestoreTest.RecordingGatt)Shadows.shadowOf(old);
        assertTrue(d.restoring);h.confirmDeleteProfile(d.address);click(AlertDialog.BUTTON_POSITIVE);ShadowLooper.idleMainLooper(2,TimeUnit.SECONDS);assertNull(d.gatt);assertTrue(shadow.writes.isEmpty());assertFalse(h.ble.profiles.known(d.address));assertEquals(d.address,h.ble.rememberedAddress());
        h.ble.connect(d);BleMemoryTest bleFixture=new BleMemoryTest();bleFixture.ble=h.ble;bleFixture.ready(d);ShadowLooper.idleMainLooper(1,TimeUnit.SECONDS);assertTrue(h.ble.profile(d).restorePackets().isEmpty());assertTrue(((ProfileRestoreTest.RecordingGatt)Shadows.shadowOf(d.gatt)).writes.isEmpty());
    }
    @Test public void inFlightAndQueuedWritesAreCancelledWithoutAResetCommand(){
        BleController.Device d=fixture.connectForUi();h.send(Protocol.rgb(0xff112233,88),true,true);h.send(Protocol.mode(4,5,true,true),true,true);BluetoothGatt old=d.gatt;ProfileRestoreTest.RecordingGatt shadow=(ProfileRestoreTest.RecordingGatt)Shadows.shadowOf(old);assertEquals(1,shadow.writes.size());assertFalse(d.queue.isEmpty());
        h.confirmDeleteProfile(d.address);click(AlertDialog.BUTTON_POSITIVE);shadow.getGattCallback().onCharacteristicWrite(old,old.getService(java.util.UUID.fromString(Protocol.SERVICE)).getCharacteristic(java.util.UUID.fromString(Protocol.WRITE)),0);ShadowLooper.idleMainLooper(4,TimeUnit.SECONDS);assertEquals(1,shadow.writes.size());assertNull(d.gatt);assertTrue(d.queue.isEmpty());assertFalse(h.ble.profiles.known(d.address));
    }
    @Test public void idleConnectionIsRetainedAndNewAdjustmentsCreateANewRecord(){
        BleController.Device d=fixture.connectForUi();h.ble.profiles.record(d.address,Protocol.rgb(0xff123456,88));h.confirmDeleteProfile(d.address);click(AlertDialog.BUTTON_POSITIVE);assertTrue(d.ready());assertFalse(h.ble.profiles.known(d.address));assertTrue(h.send(Protocol.rgb(0xffabcdef,77),true,true));assertEquals(77,h.ble.profile(d).get("color")[7]&255);assertEquals(1,h.ble.profile(d).savedCount());
    }
    @Test public void draftOrBrandSwitchCannotDeleteTheWrongProfile(){
        seed();h.editOfflineProfile(BleMemoryTest.A);h.confirmDeleteProfile(BleMemoryTest.A);assertTrue(h.ble.profiles.known(BleMemoryTest.A));h.cancelOfflineDraft();h.confirmDeleteProfile(BleMemoryTest.A);AlertDialog pending=dialog();h.selectBrand(LampBrand.MODENGKE);pending.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();assertTrue(h.ble.profiles.known(BleMemoryTest.A));assertTrue(h.ble.mdkProfiles.known(BleMemoryTest.A));
    }
    TextView find(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),s);if(t!=null)return t;}return null;}
}
