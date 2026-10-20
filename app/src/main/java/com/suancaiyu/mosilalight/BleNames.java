package com.suancaiyu.mosilalight;

import java.nio.charset.StandardCharsets;

/** Resolve real advertised names without inventing a device identity. */
final class BleNames {
    static String clean(String value){
        if(value==null)return null;
        String result=value.replace("\u0000","").replaceAll("[\\p{Cntrl}]","").trim();
        return result.isEmpty()||result.contains("\ufffd")?null:result;
    }
    static String advertised(byte[] data,boolean complete){
        if(data==null)return null;String found=null;
        for(int offset=0;offset<data.length;){
            int length=data[offset]&255;if(length==0)break;
            if(length<1||offset+length>=data.length)break;
            int type=data[offset+1]&255;
            if(type==(complete?0x09:0x08)){
                String name=clean(new String(data,offset+2,length-1,StandardCharsets.UTF_8));if(name!=null)found=name;
            }
            offset+=length+1;
        }
        return found;
    }
    static boolean real(String value){String name=clean(value);return name!=null&&!name.equals("已保存大灯")&&!name.startsWith("未命名 BLE");}
}
