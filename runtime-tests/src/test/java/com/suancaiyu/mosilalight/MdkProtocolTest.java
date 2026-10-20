package com.suancaiyu.mosilalight;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class MdkProtocolTest {
    @Test public void originalJavascriptGoldenFramesMatchByteForByte() throws Exception {
        JSONArray original=new JSONArray(new String(Files.readAllBytes(Paths.get("../tests/mdk-golden-original.json")),"UTF-8"));
        MdkState s=new MdkState();s.boot=4;s.bootColor=0xff123456;s.orientationCheck=true;s.orientationEffect=2;s.orientationMode=2;s.orientationSpeed=73;s.position=87;
        LinkedHashMap<String,byte[]> actual=new LinkedHashMap<>();actual.put("query",MdkProtocol.query());for(String k:new String[]{"boot","bootColor","selfCheck","orientation","position"})actual.put(k,s.packet(k));
        for(int i=0;i<3;i++){MdkState.Light l=s.lights[i];l.effect=i==0?0:i==1?9:8;l.speed=9;l.flash=31;l.palette=new int[]{0xff010203,0xff040506};l.color=0xff123456;l.on=i!=1;l.brightness=67;l.direction=4;l.width=72;actual.put("light"+i,s.light(i));}
        for(int i=0;i<original.length();i++){JSONObject v=original.getJSONObject(i);byte[] generated=actual.get(v.getString("name"));assertEquals(v.getString("name"),v.getString("hex"),Protocol.hex(generated).replace(" ",""));assertTrue(MdkProtocol.validFrame(generated));if(!v.getString("name").equals("query"))assertNotNull(MdkProtocol.key(generated));}
    }
    @Test public void foreignChannelsCountsEffectsAndCorruptFramesAreRejected(){
        MdkState s=new MdkState();byte[] good=s.light(0);assertEquals("light0",MdkProtocol.key(good));
        for(int index:new int[]{0,2,6,13,21,good.length-4}){byte[] bad=good.clone();bad[index]^=127;assertNull(MdkProtocol.key(bad));}
        byte[] params=Arrays.copyOfRange(good,7,good.length-4);params[6]=2;assertNull(MdkProtocol.key(MdkProtocol.frame(0xa1,params)));params[6]=1;params[14]=44;assertNull(MdkProtocol.key(MdkProtocol.frame(0xa1,params)));
        assertNull(MdkProtocol.key(MdkProtocol.command(0xab,"123456",0,255,255,255,1,5,50)));assertNull(MdkProtocol.key(MdkProtocol.command(0xa3,"123456",1,50)));
    }
    @Test public void maximumPaletteFitsNegotiatedMtuAndZeroBrightnessIsExplicit(){
        MdkState s=new MdkState();s.lights[0].effect=8;s.lights[0].brightness=0;byte[] p=s.light(0);assertEquals(48,p.length);assertEquals(0,p[22]);assertEquals("light0",MdkProtocol.key(p));assertEquals(7,p[17]);
    }
    @Test public void fragmentedAndCombinedNotificationsValidateBeforeUse(){
        byte[] reply=MdkProtocol.frame(0xb9,new byte[]{1,2,3,1,0,58});MdkProtocol.Decoder d=new MdkProtocol.Decoder();assertTrue(d.accept(Arrays.copyOfRange(reply,0,5)).isEmpty());List<byte[]> frames=d.accept(Arrays.copyOfRange(reply,5,reply.length));assertEquals(1,frames.size());assertEquals(58,MdkProtocol.firmware(frames.get(0)).product);
        byte[] both=new byte[reply.length*2+2];both[0]=1;both[1]=2;System.arraycopy(reply,0,both,2,reply.length);System.arraycopy(reply,0,both,reply.length+2,reply.length);assertEquals(2,d.accept(both).size());reply[8]^=1;assertTrue(d.accept(reply).isEmpty());
        byte[] corruptAndGood=new byte[reply.length*2];System.arraycopy(reply,0,corruptAndGood,0,reply.length);reply[8]^=1;System.arraycopy(reply,0,corruptAndGood,reply.length,reply.length);assertEquals(1,d.accept(corruptAndGood).size());
        d.accept(new byte[2000]);assertTrue(d.pending.length<=512);
    }
    @Test public void completeStateRoundTripRetainsInactiveColorPaletteAndOnlyConfiguredCommands(){
        MdkState s=new MdkState();s.lights[0].color=0xff123456;s.apply(s.light(0));s.lights[0].palette=new int[]{0xffabcdef,0xff010203};s.lights[0].effect=9;s.apply(s.light(0));s.orientationMode=2;s.position=83;s.apply(s.packet("position"));s.apply(s.packet("orientation"));s.sync=true;
        MdkState loaded=MdkState.read(s.json());assertEquals(0xff123456,loaded.lights[0].color);assertArrayEquals(new int[]{0xffabcdef,0xff010203},loaded.lights[0].palette);assertEquals(9,loaded.lights[0].effect);assertEquals(83,loaded.position);assertEquals(2,loaded.orientationMode);assertTrue(loaded.sync);assertEquals(3,loaded.restore().size());assertEquals("orientation",MdkProtocol.key(loaded.restore().get(0)));assertEquals("position",MdkProtocol.key(loaded.restore().get(1)));assertEquals("light0",MdkProtocol.key(loaded.restore().get(2)));
        assertTrue(new MdkState().restore().isEmpty());loaded.lights[0].effect=0;assertEquals(0x12,loaded.light(0)[23]&255);
    }
    @Test public void eachAddressAndBrandKeepsIndependentParameters(){
        android.content.Context c=RuntimeEnvironment.getApplication();MdkProfiles p=new MdkProfiles(c.getSharedPreferences("mdk_test",0));MdkState a=new MdkState();a.lights[0].brightness=22;p.record(BleMemoryTest.A,a.light(0));a.lights[0].brightness=88;p.record(BleMemoryTest.B,a.light(0));assertEquals(22,p.load(BleMemoryTest.A).lights[0].brightness);assertEquals(88,p.load(BleMemoryTest.B).lights[0].brightness);assertEquals(2,p.addresses().size());assertTrue(new DeviceProfiles(c.getSharedPreferences("device_profiles",0)).addresses().isEmpty());
    }
}
