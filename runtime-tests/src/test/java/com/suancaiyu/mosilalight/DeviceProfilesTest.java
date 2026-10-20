package com.suancaiyu.mosilalight;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class DeviceProfilesTest {
    DeviceProfiles store;
    @Before public void setUp(){store=new DeviceProfiles(RuntimeEnvironment.getApplication().getSharedPreferences("device_profiles",0));}
    List<String> hex(List<byte[]> packets){ArrayList<String> r=new ArrayList<>();for(byte[] p:packets)r.add(Protocol.hex(p));return r;}
    void record(byte[] b){store.record(BleMemoryTest.A,b);}
    @Test public void allSettingsSurviveRestartAndDoNotCrossAddresses(){
        byte[][] all={Protocol.power(true,0xff112233,255),Protocol.rgb(0xff445566,87),Protocol.mode(212,8,false,true),Protocol.startup(6,100,0xffabcdef),Protocol.frame(5,0,1),Protocol.frame(6,1,0),Protocol.frame(7,1),Protocol.frame(8,1),Protocol.frame(9,0,0),Protocol.frame(9,1,1),Protocol.frame(10,18),Protocol.lineOrder(6)};
        for(byte[] b:all)record(b);
        store.record(BleMemoryTest.B,Protocol.rgb(0xffff0000,200));
        DeviceProfiles again=new DeviceProfiles(store.storage);DeviceProfiles.Profile p=again.load(BleMemoryTest.A);
        assertEquals(12,p.savedCount());for(byte[] b:all)assertArrayEquals(b,p.get(DeviceProfiles.key(b)));
        assertEquals("mode",p.active);assertArrayEquals(Protocol.rgb(0xffff0000,200),again.load(BleMemoryTest.B).get("color"));
        assertNull(again.load(BleMemoryTest.B).get("mode"));assertEquals(0,again.load("AA:BB:CC:DD:EE:03").savedCount());
        List<String> restore=hex(p.restorePackets());assertEquals(10,restore.size());
        assertEquals(Protocol.hex(Protocol.lineOrder(6)),restore.get(0));
        assertFalse(restore.contains(Protocol.hex(Protocol.frame(7,1))));assertFalse(restore.contains(Protocol.hex(Protocol.rgb(0xff445566,87))));
        assertTrue(restore.contains(Protocol.hex(Protocol.mode(212,8,false,true))));
        assertTrue(restore.indexOf(Protocol.hex(Protocol.power(true,0xff112233,255)))<restore.indexOf(Protocol.hex(Protocol.mode(212,8,false,true))));
    }
    @Test public void colorAndModeHaveIndependentCachesAndOnlyLastOutputRestores(){
        record(Protocol.mode(17,3,true,false));record(Protocol.rgb(0xff001122,250));
        DeviceProfiles.Profile p=store.load(BleMemoryTest.A);assertEquals("color",p.active);assertEquals(2,p.savedCount());
        assertEquals(Arrays.asList(Protocol.hex(Protocol.rgb(0xff001122,250))),hex(p.restorePackets()));
        record(Protocol.mode(7,9,false,true));p=store.load(BleMemoryTest.A);assertEquals("mode",p.active);assertEquals(1,p.restorePackets().size());
        assertArrayEquals(Protocol.rgb(0xff001122,250),p.get("color"));
    }
    @Test public void powerOffIsRestoredLastAndMotionNeverRestores(){
        record(Protocol.rgb(0xff123456,100));record(Protocol.mode(12,5,true,true));record(Protocol.frame(7,0));record(Protocol.power(false,0xff123456,100));
        List<byte[]> packets=store.load(BleMemoryTest.A).restorePackets();assertEquals(2,packets.size());
        assertArrayEquals(Protocol.mode(12,5,true,true),packets.get(0));assertArrayEquals(Protocol.power(false,0xff123456,100),packets.get(1));
    }
    @Test public void channelSettingsRemainIndependentAndUnknownValuesAreNotInvented(){
        record(Protocol.frame(9,0,1));record(Protocol.frame(9,1,0));record(Protocol.frame(9,0,0));
        DeviceProfiles.Profile p=store.load(BleMemoryTest.A);assertEquals(2,p.restorePackets().size());
        assertArrayEquals(Protocol.frame(9,0,0),p.get("routeA"));assertArrayEquals(Protocol.frame(9,1,0),p.get("routeB"));assertNull(p.get("brake"));
    }
    @Test public void corruptAndUnknownPacketsAreNotReplayed(){
        store.storage.edit().putString(BleMemoryTest.A,"{\"active\":\"mode\",\"packets\":{\"mode\":[105,150,6,3,0,999,1,1,1],\"level\":[105,150,2,10,50]}}").commit();
        assertTrue(store.load(BleMemoryTest.A).restorePackets().isEmpty());
        record(new byte[]{0x69,(byte)0x96,2,99,1});record(Protocol.frame(2,0,0,0,0));
        assertEquals(0,store.load(BleMemoryTest.A).savedCount());
    }
    @Test public void namesPersistWithoutInventingCommandsAndMalformedMemberDoesNotHideValidSettings(){
        store.rememberName(BleMemoryTest.A,"我的大灯");assertTrue(store.known(BleMemoryTest.A));assertEquals("我的大灯",store.load(BleMemoryTest.A).name);
        assertTrue(store.load(BleMemoryTest.A).restorePackets().isEmpty());record(Protocol.mode(1,2,true,true));
        assertEquals("我的大灯",store.load(BleMemoryTest.A).name);assertEquals(Arrays.asList(BleMemoryTest.A),store.addresses());
        store.storage.edit().putString(BleMemoryTest.A,"{\"active\":\"mode\",\"packets\":{\"brake\":[\"bad\"],\"mode\":[105,150,6,3,0,8,6,1,1]}}").commit();
        assertEquals(1,store.load(BleMemoryTest.A).restorePackets().size());
    }

}
