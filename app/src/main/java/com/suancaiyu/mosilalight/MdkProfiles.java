package com.suancaiyu.mosilalight;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
/** Product 58 namespace; never reads or overwrites MOSILA records. */
final class MdkProfiles {
    final SharedPreferences prefs;
    MdkProfiles(SharedPreferences p){prefs=p;}
    JSONObject entry(String address){try{return new JSONObject(prefs.getString(address,"{}"));}catch(Exception e){return new JSONObject();}}
    MdkState load(String address){return MdkState.read(entry(address).optJSONObject("state"));}
    String name(String address){return entry(address).optString("name","九号 N/F/M 大灯 3.0");}
    boolean known(String address){return entry(address).has("state");}
    List<String> addresses(){ArrayList<String> out=new ArrayList<>();for(String k:prefs.getAll().keySet())if(known(k))out.add(k);Collections.sort(out);return out;}
    void delete(String address){
        if(!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address))return;
        JSONObject j=entry(address);j.remove("state");j.remove("updatedAt");
        // Retain name-only ownership metadata, not lamp parameters.
        if(j.length()==0)prefs.edit().remove(address).apply();else prefs.edit().putString(address,j.toString()).apply();
    }
    void remember(String address,String name){put(address,null,name);}
    void save(String address,MdkState state){put(address,state,null);}
    private void put(String address,MdkState state,String name){if(!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address))return;try{JSONObject j=entry(address);if(state!=null)j.put("state",state.json()).put("updatedAt",System.currentTimeMillis());if(name!=null)j.put("name",name);prefs.edit().putString(address,j.toString()).apply();}catch(JSONException ignored){}}
    void record(String address,byte[] packet){MdkState state=load(address);if(state.apply(packet))save(address,state);}
}
