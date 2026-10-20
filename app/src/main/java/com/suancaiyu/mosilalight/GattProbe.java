package com.suancaiyu.mosilalight;

import android.bluetooth.*;
import android.os.*;
import java.util.*;

/** Standard GATT reads and CCCD subscriptions only; no guessed lamp commands. */
final class GattProbe {
    interface Events { void log(String message); void done(boolean timeout); }
    static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final class Step {
        final BluetoothGattCharacteristic characteristic;
        final BluetoothGattDescriptor descriptor;
        Step(BluetoothGattCharacteristic c,BluetoothGattDescriptor d){characteristic=c;descriptor=d;}
    }
    final BluetoothGatt gatt;
    final Handler main;
    final Events events;
    final ArrayDeque<Step> steps=new ArrayDeque<>();
    Step current;
    boolean finished;
    final Runnable deadline=()->finish(true);
    GattProbe(BluetoothGatt gatt,Handler main,Events events){this.gatt=gatt;this.main=main;this.events=events;}
    void start(){
        BluetoothGattService service=gatt.getService(UUID.fromString(Protocol.SERVICE));
        if(service!=null)for(BluetoothGattCharacteristic c:service.getCharacteristics()){
            int p=c.getProperties();
            if((p&(BluetoothGattCharacteristic.PROPERTY_NOTIFY|BluetoothGattCharacteristic.PROPERTY_INDICATE))!=0){
                BluetoothGattDescriptor descriptor=c.getDescriptor(CCCD);
                if(descriptor!=null)steps.add(new Step(c,descriptor));
                else events.log("通知特征没有 CCCD，跳过："+c.getUuid());
            }
            if((p&BluetoothGattCharacteristic.PROPERTY_READ)!=0)steps.add(new Step(c,null));
        }
        if(steps.isEmpty())events.log("大灯服务未提供可读特征或可订阅通知；尚不能通过标准 GATT 回读模式");
        next();
    }
    void next(){
        if(finished)return;
        main.removeCallbacks(deadline);current=steps.poll();
        if(current==null){events.log("回读探测结束；返回数据未经协议确认，不会覆盖当前灯效");finish(false);return;}
        main.postDelayed(deadline,5000);
        boolean accepted=false;
        try{
            if(current.descriptor==null){
                events.log("读取："+current.characteristic.getUuid());accepted=gatt.readCharacteristic(current.characteristic);
            }else{
                BluetoothGattCharacteristic c=current.characteristic;
                if(gatt.setCharacteristicNotification(c,true)){
                    byte[] value=(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0?BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE:BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                    if(Build.VERSION.SDK_INT>=33)accepted=gatt.writeDescriptor(current.descriptor,value)==BluetoothStatusCodes.SUCCESS;
                    else{current.descriptor.setValue(value);accepted=gatt.writeDescriptor(current.descriptor);}
                }
            }
        }catch(RuntimeException e){events.log("读取/订阅失败："+e.getClass().getSimpleName());}
        if(!accepted){
            if(current.descriptor!=null)try{gatt.setCharacteristicNotification(current.characteristic,false);}catch(RuntimeException ignored){}
            events.log("系统未接受探测操作："+current.characteristic.getUuid());next();
        }
    }
    void read(BluetoothGattCharacteristic c,byte[] value,int status){
        if(finished||current==null||current.descriptor!=null||current.characteristic!=c)return;
        events.log("读取返回 "+c.getUuid()+" status="+status+" data="+data(value));next();
    }
    void descriptor(BluetoothGattDescriptor descriptor,int status){
        if(finished||current==null||current.descriptor!=descriptor)return;
        events.log("订阅通知 "+current.characteristic.getUuid()+" status="+status);
        if(status!=BluetoothGatt.GATT_SUCCESS)try{gatt.setCharacteristicNotification(current.characteristic,false);}catch(RuntimeException ignored){}
        next();
    }
    void finish(boolean timeout){if(finished)return;finished=true;main.removeCallbacks(deadline);steps.clear();current=null;events.done(timeout);}
    void cancel(){finished=true;main.removeCallbacks(deadline);steps.clear();current=null;}
    static String data(byte[] value){
        if(value==null)return "(无数据)";if(value.length==0)return "(空)";
        return Protocol.hex(Arrays.copyOf(value,Math.min(value.length,96)))+(value.length>96?" … ["+value.length+" 字节]":"");
    }
    static String properties(int value){
        ArrayList<String> result=new ArrayList<>();
        if((value&BluetoothGattCharacteristic.PROPERTY_READ)!=0)result.add("READ");
        if((value&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0)result.add("WRITE");
        if((value&BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0)result.add("WRITE_NO_RESPONSE");
        if((value&BluetoothGattCharacteristic.PROPERTY_NOTIFY)!=0)result.add("NOTIFY");
        if((value&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0)result.add("INDICATE");
        return String.format(Locale.ROOT,"0x%02X ",value)+String.join("|",result);
    }
}
