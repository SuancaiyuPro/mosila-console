package com.suancaiyu.mosilalight;

import android.content.SharedPreferences;
import org.json.*;
import java.util.*;

/** Desired settings, indexed by the device address; never interpreted as hardware readback. */
final class DeviceProfiles {
    final SharedPreferences storage;
    DeviceProfiles(SharedPreferences storage){this.storage=storage;}
    boolean known(String address){return address!=null&&address.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")&&storage.contains(address);}
    List<String> addresses(){ArrayList<String> result=new ArrayList<>();for(String key:storage.getAll().keySet())if(known(key))result.add(key);Collections.sort(result);return result;}
    void delete(String address){if(known(address))storage.edit().remove(address).apply();}
    void rememberName(String address,String name){Profile p=load(address);if(name.equals(p.name))return;p.name=name;write(address,p);}
    static int u(byte[] b,int i){return b[i]&255;}
    static String key(byte[] b){
        if(b==null)return null;
        if(b.length==2&&u(b,0)==0x91&&u(b,1)>=1&&u(b,1)<=6)return "lineOrder";
        if(b.length<5||u(b,0)!=0x69||u(b,1)!=0x96||u(b,2)!=b.length-3)return null;
        switch(u(b,3)){
            case 1:return b.length==9&&u(b,4)<=1&&u(b,8)>0?"power":null;
            case 2:return b.length==8&&u(b,7)>0?"color":null;
            case 3:return b.length==9&&u(b,4)==0&&u(b,5)<=212&&u(b,6)>=1&&u(b,6)<=10&&u(b,7)<=1&&u(b,8)<=1?"mode":null;
            case 4:return b.length==10&&u(b,4)<=6&&u(b,5)>=10&&u(b,5)<=100&&u(b,9)==255?"startup":null;
            case 5:return b.length==6&&u(b,4)<=1&&u(b,5)<=1?"brake":null;
            case 6:return b.length==6&&u(b,4)<=1&&u(b,5)<=1?"turn":null;
            case 7:return b.length==5&&u(b,4)<=1?"lastMotion":null;
            case 8:return b.length==5&&u(b,4)<=1?"selfCheck":null;
            case 9:return b.length==6&&u(b,4)<=1&&u(b,5)<=1?(u(b,4)==0?"routeA":"routeB"):null;
            case 10:return b.length==5&&u(b,4)<=18?"level":null;
            default:return null;
        }
    }
    Profile load(String address){
        Profile p=new Profile();
        try{
            JSONObject json=new JSONObject(storage.getString(address,"{}"));
            p.active=json.optString("active","");p.name=json.optString("name","已保存大灯");
            JSONObject packets=json.optJSONObject("packets");
            if(packets!=null)for(Iterator<String> it=packets.keys();it.hasNext();){
                String k=it.next();
                try{
                    JSONArray a=packets.optJSONArray(k);if(a==null||a.length()>32)continue;
                    byte[] b=new byte[a.length()];boolean valid=true;
                    for(int i=0;i<b.length;i++){int v=a.getInt(i);if(v<0||v>255){valid=false;break;}b[i]=(byte)v;}
                    if(valid&&k.equals(key(b)))p.values.put(k,b);
                }catch(JSONException ignored){}
            }
        }catch(JSONException|ClassCastException ignored){}
        return p;
    }
    void record(String address,byte[] packet){
        String key=key(packet);if(key==null)return;Profile p=load(address);
        if(key.equals("lastMotion")&&Arrays.equals(p.get(key),packet))return;
        apply(p,packet);write(address,p);
    }
    void recordAll(String address,List<byte[]> packets){
        Profile p=load(address);boolean valid=false;for(byte[] packet:packets)if(key(packet)!=null){apply(p,packet);valid=true;}if(valid)write(address,p);
    }
    private void apply(Profile p,byte[] packet){
        String k=key(packet);if(k==null)return;
        if(k.equals("lastMotion")&&Arrays.equals(p.get(k),packet))return;p.values.put(k,packet.clone());
        if(k.equals("color")||k.equals("mode"))p.active=k;
        if(k.equals("power")){
            byte[] rgb=Protocol.rgb(0xff000000|(u(packet,5)<<16)|(u(packet,6)<<8)|u(packet,7),u(packet,8));
            if(u(packet,4)==1){p.values.put("color",rgb);p.active="color";}
            else if(!p.values.containsKey("color")){p.values.put("color",rgb);if(p.active.isEmpty())p.active="color";}
        }
    }
    private void write(String address,Profile p){
        try{
            JSONObject json=new JSONObject(),packets=new JSONObject();
            for(Map.Entry<String,byte[]> entry:p.values.entrySet()){
                JSONArray a=new JSONArray();for(byte value:entry.getValue())a.put(value&255);packets.put(entry.getKey(),a);
            }
            json.put("schema",1).put("name",p.name).put("active",p.active).put("packets",packets).put("updatedAt",System.currentTimeMillis());
            storage.edit().putString(address,json.toString()).apply();
        }catch(JSONException e){throw new IllegalStateException(e);}
    }
    static final class Profile {
        final LinkedHashMap<String,byte[]> values=new LinkedHashMap<>();
        String active="",name="已保存大灯";
        byte[] get(String key){byte[] b=values.get(key);return b==null?null:b.clone();}
        int savedCount(){return values.size();}
        List<byte[]> restorePackets(){
            ArrayList<byte[]> result=new ArrayList<>();
            // Configuration first; visible output last. Never repeat a held movement.
            for(String k:new String[]{"lineOrder","startup","brake","turn","selfCheck","level"})add(result,k);
            byte[] power=get("power");
            if(power!=null&&u(power,4)==1)result.add(power);
            if(active.equals("color")||active.equals("mode"))add(result,active);
            add(result,"routeA");add(result,"routeB");
            if(power!=null&&u(power,4)==0)result.add(power);
            return result;
        }
        void add(List<byte[]> result,String key){byte[] b=get(key);if(b!=null)result.add(b);}
    }
}
