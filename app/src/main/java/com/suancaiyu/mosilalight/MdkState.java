package com.suancaiyu.mosilalight;
import org.json.*;
import java.util.*;

/** Independent complete UI state, retaining inactive single colors and effect palettes. */
final class MdkState {
    static final int[] DEFAULT_PALETTE={0xffff0000,0xff48fefe,0xffefff00,0xff00ff0c,0xff00ffd3,0xff0000ff,0xffff00ce};
    static final class Light {
        int effect=0,speed=2,flash=0,direction=1,width=10,brightness=100,color=0xffff0000;
        boolean on=true;int[] palette=DEFAULT_PALETTE.clone();
    }
    final Light[] lights={new Light(),new Light(),new Light()};
    String password="123456";
    int boot=2,bootColor=0xffff0000,orientationEffect=1,orientationMode=1,orientationSpeed=3,position=50;
    boolean selfCheck=true,orientationCheck=false,sync=false;
    final LinkedHashSet<String> configured=new LinkedHashSet<>();
    MdkState(){lights[1].color=0xff48fefe;lights[2].effect=8;lights[2].color=0xffffffff;}
    byte[] light(int slot){
        Light l=lights[slot];int[] colors=l.effect==0?new int[]{l.color}:l.palette;int[] p=new int[10+3*colors.length];
        int[] head={MdkProtocol.CHANNELS[slot],l.effect,l.speed,l.flash,colors.length,l.on?1:0,l.direction,l.width,MdkProtocol.COUNTS[slot],l.brightness};System.arraycopy(head,0,p,0,10);
        for(int i=0;i<colors.length;i++){p[10+3*i]=(colors[i]>>16)&255;p[11+3*i]=(colors[i]>>8)&255;p[12+3*i]=colors[i]&255;}
        return MdkProtocol.command(0xa1,password,p);
    }
    byte[] packet(String key){
        if(key.startsWith("light"))return light(Integer.parseInt(key.substring(5)));
        switch(key){case "boot":return MdkProtocol.command(0xa0,password,boot);case "bootColor":return MdkProtocol.command(0xa5,password,(bootColor>>16)&255,(bootColor>>8)&255,bootColor&255);case "selfCheck":return MdkProtocol.command(0xa8,password,selfCheck?1:0);case "orientation":return MdkProtocol.command(0xa6,password,orientationCheck?1:0,orientationEffect,orientationMode,orientationSpeed);case "position":return MdkProtocol.command(0xa3,password,3,position);default:throw new IllegalArgumentException(key);}
    }
    boolean apply(byte[] p){
        String key=MdkProtocol.key(p);if(key==null)return false;password=MdkProtocol.password(p);configured.add(key);
        if(key.startsWith("light")){Light l=lights[Integer.parseInt(key.substring(5))];l.effect=MdkProtocol.u(p,14);l.speed=MdkProtocol.u(p,15);l.flash=MdkProtocol.u(p,16);l.on=p[18]!=0;l.direction=MdkProtocol.u(p,19);l.width=MdkProtocol.u(p,20);l.brightness=MdkProtocol.u(p,22);int[] colors=new int[MdkProtocol.u(p,17)];for(int i=0;i<colors.length;i++)colors[i]=0xff000000|(MdkProtocol.u(p,23+3*i)<<16)|(MdkProtocol.u(p,24+3*i)<<8)|MdkProtocol.u(p,25+3*i);if(l.effect==0)l.color=colors[0];else l.palette=colors;}
        else switch(key){case "boot":boot=MdkProtocol.u(p,13);break;case "bootColor":bootColor=0xff000000|(MdkProtocol.u(p,13)<<16)|(MdkProtocol.u(p,14)<<8)|MdkProtocol.u(p,15);break;case "selfCheck":selfCheck=p[13]!=0;break;case "orientation":orientationCheck=p[13]!=0;orientationEffect=MdkProtocol.u(p,14);orientationMode=MdkProtocol.u(p,15);orientationSpeed=MdkProtocol.u(p,16);break;case "position":position=MdkProtocol.u(p,14);break;}
        return true;
    }
    List<byte[]> restore(){ArrayList<byte[]> out=new ArrayList<>();for(String key:new String[]{"boot","bootColor","selfCheck","orientation","position","light0","light1","light2"})if(configured.contains(key))out.add(packet(key));return out;}
    JSONObject json(){
        JSONObject j=new JSONObject();try{j.put("schema",1).put("password",password).put("sync",sync);JSONArray packets=new JSONArray();for(byte[] p:restore())packets.put(Protocol.hex(p));j.put("packets",packets);JSONArray caches=new JSONArray();for(Light l:lights){JSONObject c=new JSONObject();c.put("color",l.color);JSONArray palette=new JSONArray();for(int x:l.palette)palette.put(x);c.put("palette",palette);caches.put(c);}j.put("caches",caches);}catch(JSONException e){throw new IllegalStateException(e);}return j;
    }
    static MdkState read(JSONObject j){
        MdkState s=new MdkState();if(j==null||j.optInt("schema")!=1)return s;String pwd=j.optString("password","123456");if(pwd.matches("[0-9]{6}"))s.password=pwd;s.sync=j.optBoolean("sync",false);
        JSONArray a=j.optJSONArray("packets");if(a!=null)for(int i=0;i<a.length();i++)try{s.apply(hex(a.getString(i)));}catch(Exception ignored){}
        JSONArray c=j.optJSONArray("caches");if(c!=null)for(int i=0;i<3;i++)try{JSONObject v=c.getJSONObject(i);s.lights[i].color=v.getInt("color");JSONArray palette=v.getJSONArray("palette");if(palette.length()<1||palette.length()>7)continue;int[] colors=new int[palette.length()];for(int k=0;k<colors.length;k++)colors[k]=palette.getInt(k);s.lights[i].palette=colors;}catch(Exception ignored){}return s;
    }
    static byte[] hex(String s){s=s.replace(" ","");if(s.length()%2!=0)throw new IllegalArgumentException();byte[] p=new byte[s.length()/2];for(int i=0;i<p.length;i++)p[i]=(byte)Integer.parseInt(s.substring(2*i,2*i+2),16);return p;}
    MdkState copy(){return read(json());}
}
