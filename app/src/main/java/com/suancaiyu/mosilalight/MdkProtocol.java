package com.suancaiyu.mosilalight;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Verified against 摩灯客 2.2.0 ble/protocol.js, crc16.js and product 58 metadata. */
final class MdkProtocol {
    static final String SERVICE="0000fff0-0000-1000-8000-00805f9b34fb", WRITE="0000fff2-0000-1000-8000-00805f9b34fb", NOTIFY="0000fff1-0000-1000-8000-00805f9b34fb";
    static final int PRODUCT=58, MAX_FRAME=48;
    static final int[] CHANNELS={1,3,4}, COUNTS={45,56,45};
    static final String[] NAMES={"一字日行灯","侧翼灯","恶魔眼"}, EFFECTS={"单色","轮播","闪播","混色渐变","单色渐变","脉冲闪播","覆盖","游侠","七色渐变","拖尾"};
    static int crc(byte[] data,int length){int c=65535;for(int n=0;n<length;n++){c^=data[n]&255;for(int i=0;i<8;i++)c=(c&1)!=0?(c>>>1)^0xa001:c>>>1;}return c;}
    static byte[] frame(int type,byte[] data){
        byte[] out=new byte[11+data.length];out[0]=(byte)0xaa;out[1]=0x55;System.arraycopy("0001".getBytes(StandardCharsets.US_ASCII),0,out,2,4);out[6]=(byte)type;System.arraycopy(data,0,out,7,data.length);
        int c=crc(out,out.length-4);out[out.length-4]=(byte)c;out[out.length-3]=(byte)(c>>>8);out[out.length-2]=13;out[out.length-1]=10;return out;
    }
    static byte[] command(int type,String password,int... params){
        if(password==null||!password.matches("[0-9]{6}"))throw new IllegalArgumentException("蓝牙密码须为6位数字");
        byte[] data=new byte[6+params.length];System.arraycopy(password.getBytes(StandardCharsets.US_ASCII),0,data,0,6);for(int i=0;i<params.length;i++){if(params[i]<0||params[i]>255)throw new IllegalArgumentException("参数超出字节范围");data[i+6]=(byte)params[i];}return frame(type,data);
    }
    static byte[] query(){return frame(0xa9,new byte[0]);}
    static boolean validFrame(byte[] p){if(p==null||p.length<11||p.length>512||u(p,0)!=170||u(p,1)!=85||p[p.length-2]!=13||p[p.length-1]!=10)return false;for(int i=2;i<6;i++)if(p[i]!=48+(i==5?1:0))return false;int c=crc(p,p.length-4);return u(p,p.length-4)==(c&255)&&u(p,p.length-3)==(c>>>8);}
    static int u(byte[] p,int i){return p[i]&255;}
    static String password(byte[] p){return new String(p,7,6,StandardCharsets.US_ASCII);}
    static String key(byte[] p){
        if(!validFrame(p)||p.length<17||!password(p).matches("[0-9]{6}"))return null;
        int n=p.length,type=u(p,6);
        if(type==0xa1){
            if(n<30)return null;int ch=u(p,13),slot=ch==1?0:ch==3?1:ch==4?2:-1,count=u(p,17);
            if(slot<0||u(p,14)>9||u(p,15)>9||u(p,16)>100||count<1||count>7||u(p,18)>1||u(p,19)<1||u(p,19)>4||u(p,20)>100||u(p,21)!=COUNTS[slot]||u(p,22)>100||n!=27+count*3||u(p,14)==0&&count!=1)return null;
            return "light"+slot;
        }
        if(type==0xa0&&n==18&&u(p,13)>=2&&u(p,13)<=4)return "boot";
        if(type==0xa5&&n==20)return "bootColor";
        if(type==0xa8&&n==18&&u(p,13)<=1)return "selfCheck";
        if(type==0xa6&&n==21&&u(p,13)<=1&&u(p,14)>=1&&u(p,14)<=2&&u(p,15)>=1&&u(p,15)<=2&&u(p,16)<=100)return "orientation";
        if(type==0xa3&&n==19&&u(p,13)==3&&u(p,14)<=100)return "position";
        return null;
    }
    static final class Firmware {
        final int product,partition;final String version;
        Firmware(byte[] p){version=u(p,7)+"."+u(p,8)+"."+u(p,9);partition=u(p,10);product=(u(p,11)<<8)|u(p,12);}
    }
    static Firmware firmware(byte[] p){return validFrame(p)&&u(p,6)==0xb9&&(p.length==17||p.length==29)?new Firmware(p):null;}
    /** Reassembles fragmented notifications; CRC/end marker delimit variable-length original frames. */
    static final class Decoder {
        byte[] pending=new byte[0];
        List<byte[]> accept(byte[] data){
            ArrayList<byte[]> out=new ArrayList<>();if(data==null||data.length==0)return out;
            ByteArrayOutputStream b=new ByteArrayOutputStream();b.write(pending,0,pending.length);b.write(data,0,data.length);byte[] all=b.toByteArray();int start=0,keep=-1;
            while(start+1<all.length){
                if(u(all,start)!=170||u(all,start+1)!=85){start++;continue;}
                boolean found=false;for(int end=start+11;end<=all.length&&end-start<=512;end++)if(all[end-2]==13&&all[end-1]==10){byte[] p=Arrays.copyOfRange(all,start,end);if(validFrame(p)){out.add(p);start=end;found=true;keep=-1;break;}}
                if(!found){if(keep<0&&all.length-start<=512)keep=start;start++;}
            }
            pending=keep>=0?Arrays.copyOfRange(all,keep,all.length):all.length>0&&u(all,all.length-1)==170?new byte[]{(byte)170}:new byte[0];return out;
        }
    }
}
