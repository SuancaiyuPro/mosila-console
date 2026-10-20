package com.suancaiyu.mosilalight;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.*;
import java.util.*;

/** Foreground BLE client with per-address settings and paced restoration. */
@SuppressLint("MissingPermission")
public final class BleController {
    public interface Listener { void onChanged(); void onLog(String message); default void onReady(Device device){} }
    public static final class Device {
        public final String address;
        public String name;
        int nameQuality;
        public int rssi;
        public boolean advertisedService;
        public String state = "未连接";
        public boolean selected = true;
        BluetoothDevice device;
        BluetoothGatt gatt;
        BluetoothGattCharacteristic write, pendingWrite;
        int mtu=23;boolean identifying, queryWritten;
        MdkProtocol.Firmware verifiedFirmware;
        String firmware="";
        final MdkProtocol.Decoder decoder=new MdkProtocol.Decoder();
        final ArrayDeque<byte[]> queue = new ArrayDeque<>();
        final ArrayDeque<byte[]> restoreQueue = new ArrayDeque<>();
        boolean busy, restoring, writingRestore;
        int restoreTotal, restored;
        long nextWriteAt;
        Runnable drainTask;
        GattProbe probe;
        long writeId;
        Runnable timeout;
        Device(BluetoothDevice d) {
            device=d;address=d.getAddress();
            try{name=BleNames.clean(d.getName());nameQuality=name==null?0:2;}catch(SecurityException ignored){}
        }
        void updateName(String value,int quality){String clean=BleNames.clean(value);if(clean!=null&&quality>=nameQuality){name=clean;nameQuality=quality;}}
        public boolean ready() { return gatt != null && write != null; }
        public String label() { return name == null || name.isEmpty() ? "未命名 BLE · "+address.substring(Math.max(0,address.length()-8)) : name; }
    }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinkedHashMap<String, Device> devices = new LinkedHashMap<>();
    private BluetoothLeScanner scanner;
    public final BluetoothAdapter adapter;
    private boolean scanning, closed, protocolEnabled=true;
    private String reconnectAddress;
    private SharedPreferences memory;
    private SharedPreferences nameCache;
    private ScanCallback scanCallback;
    private long scanGeneration;
    LampBrand brand=LampBrand.MOSILA;
    final MdkProfiles mdkProfiles;
    final DeviceProfiles profiles;
    static final long RESTORE_GAP_MS=400, CONTROL_GAP_MS=150, CONNECT_SETTLE_MS=700;
    private final Runnable stopTask = () -> {
        boolean missing=reconnectAddress!=null;stopScan();
        if(missing)log("未找到上次连接的大灯，请检查电源或手动重新扫描");
    };
    public BleController(Context c, Listener l) {
        context = c; listener = l; memory=c.getSharedPreferences("connection_memory",Context.MODE_PRIVATE);
        profiles=new DeviceProfiles(c.getSharedPreferences(LampBrand.MOSILA.profileNamespace,Context.MODE_PRIVATE));
        mdkProfiles=new MdkProfiles(c.getSharedPreferences("modengke_p58_profiles",Context.MODE_PRIVATE));
        nameCache=c.getSharedPreferences("bluetooth_names_mosila_v2",Context.MODE_PRIVATE);
        BluetoothManager manager = (BluetoothManager)c.getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = manager == null ? null : manager.getAdapter();
    }
    public void setBrand(LampBrand next){
        if(brand==next)return;
        setProtocolEnabled(false);devices.clear();brand=next;
        memory=context.getSharedPreferences(next==LampBrand.MOSILA?"connection_memory":"connection_memory_modengke_p58",Context.MODE_PRIVATE);
        nameCache=context.getSharedPreferences(next==LampBrand.MOSILA?"bluetooth_names_mosila_v2":"bluetooth_names_modengke_p58_v2",Context.MODE_PRIVATE);
        setProtocolEnabled(next.supported);changed();
    }
    String serviceId(){return brand==LampBrand.MOSILA?Protocol.SERVICE:MdkProtocol.SERVICE;}
    String writeId(){return brand==LampBrand.MOSILA?Protocol.WRITE:MdkProtocol.WRITE;}
    String settingKey(byte[] packet){return brand==LampBrand.MOSILA?DeviceProfiles.key(packet):MdkProtocol.key(packet);}
    public void setProtocolEnabled(boolean enabled){
        if(protocolEnabled==enabled)return;protocolEnabled=enabled;
        if(!enabled){stopScan();for(Device d:devices.values())if(d.gatt!=null)disconnect(d);}
    }
    public boolean isProtocolEnabled(){return protocolEnabled;}
    public List<Device> devices() {
        ArrayList<Device> result = new ArrayList<>(devices.values());
        result.sort((a,b) -> Integer.compare(rank(b), rank(a)));
        return result;
    }
    private int rank(Device d) { return d.ready() ? 4 : d.gatt != null ? 3 : d.advertisedService || d.label().toUpperCase(Locale.ROOT).contains(brand==LampBrand.MOSILA?"MOSILA":"JHDD30") ? 2 : 0; }
    public int readyCount() { if(!protocolEnabled)return 0;int n = 0; for (Device d : devices.values()) if (d.ready() && d.selected) n++; return n; }
    public boolean isScanning() { return scanning; }
    public String rememberedAddress(){
        String value=memory.getString("address","");
        return BluetoothAdapter.checkBluetoothAddress(value)?value:"";
    }
    public String rememberedLabel(){return memory.getString("name","上次连接的大灯");}
    public void clearMemory(){
        if(reconnectAddress!=null)stopScan();memory.edit().clear().apply();changed();
    }
    public boolean deleteSavedParameters(String address){
        if(closed||!BluetoothAdapter.checkBluetoothAddress(address))return false;
        Device d=devices.get(address);
        if(d!=null&&(d.busy||d.restoring||!d.queue.isEmpty()||!d.restoreQueue.isEmpty()))fail(d,"已停止参数发送，删除本地参数");
        if(brand==LampBrand.MOSILA)profiles.delete(address);else mdkProfiles.delete(address);
        log("已删除 "+brand.title+" 设备 ["+address+"] 的本地参数；连接记忆保留");changed();return true;
    }
    public void startRememberedScan(){
        String address=rememberedAddress();if(address.isEmpty()||closed||!protocolEnabled)return;
        Device existing=devices.get(address);if(existing!=null&&existing.gatt!=null)return;
        if(scanning)stopScan();reconnectAddress=address;
        log("正在查找上次连接的大灯："+rememberedLabel());beginScan();
    }
    public void startScan(){if(scanning||closed||!protocolEnabled)return;reconnectAddress=null;beginScan();}
    private void beginScan() {
        if (scanning || closed || !protocolEnabled) return;
        try {
            if (adapter == null || !adapter.isEnabled()) { log("蓝牙未开启"); return; }
            Iterator<Device> it = devices.values().iterator();
            while (it.hasNext()) if (it.next().gatt == null) it.remove();
            scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) { log("无法启动蓝牙扫描"); return; }
            scanCallback=newScanCallback(++scanGeneration,brand);
            scanner.startScan(Collections.emptyList(), new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback);
            scanning = true; main.postDelayed(stopTask, 15000); changed(); log("开始扫描，15 秒后自动结束");
        } catch (SecurityException | IllegalStateException ex) { scanning = false; changed(); log("扫描失败，请检查附近设备权限与蓝牙状态"); }
    }
    public void stopScan() {
        main.removeCallbacks(stopTask);reconnectAddress=null;scanGeneration++;
        if (scanning && scanner != null) {
            try { scanner.stopScan(scanCallback); } catch (RuntimeException ignored) {}
        }
        if (scanning) { scanning = false; changed(); }
    }
    private ScanCallback newScanCallback(final long generation,final LampBrand scanBrand){return new ScanCallback() {
        @Override public void onScanResult(int type, ScanResult result) {
            main.post(() -> {
                if (!scanning || closed || !protocolEnabled || generation!=scanGeneration || scanBrand!=brand) return;
                try {
                    String key = result.getDevice().getAddress();
                    if(!belongsToActiveBrand(result))return;
                    Device d = devices.get(key);
                    if (d == null) { d = new Device(result.getDevice()); devices.put(key, d); }
                    ScanRecord record = result.getScanRecord();
                    if (record != null) {
                        List<ParcelUuid> services = record.getServiceUuids();
                        d.advertisedService = services != null && services.contains(ParcelUuid.fromString(serviceId()));
                    }
                    refreshName(d,record);d.rssi = result.getRssi(); changed();
                    if(key.equals(reconnectAddress)){
                        log("已发现上次连接的大灯，正在自动连接");connect(d);
                    }
                } catch (SecurityException ex) { stopScan(); log("蓝牙权限被撤回"); }
            });
        }
        @Override public void onBatchScanResults(List<ScanResult> results){for(ScanResult result:results)onScanResult(0,result);}
        @Override public void onScanFailed(int code) { main.post(() -> { if(generation!=scanGeneration||scanBrand!=brand||!scanning||closed)return;scanning = false; reconnectAddress=null; main.removeCallbacks(stopTask); changed(); log("扫描失败，系统错误 " + code + "；请稍后重试"); }); }
    };}
    /** Suppress known foreign devices; unclassified/nameless advertisements remain available for discovery. */
    private boolean belongsToActiveBrand(ScanResult result){
        String address=result.getDevice().getAddress();ScanRecord record=result.getScanRecord();
        List<ParcelUuid> services=record==null?null:record.getServiceUuids();
        String foreignService=brand==LampBrand.MOSILA?MdkProtocol.SERVICE:Protocol.SERVICE;
        if(services!=null&&services.contains(ParcelUuid.fromString(foreignService))&&!services.contains(ParcelUuid.fromString(serviceId())))return false;
        String advertised=record==null?null:BleNames.advertised(record.getBytes(),true);
        if(advertised==null&&record!=null)advertised=BleNames.clean(record.getDeviceName());
        String system=BleNames.clean(result.getDevice().getName());
        if(isForeignName(advertised)||isForeignName(system))return false;
        boolean own=brand==LampBrand.MOSILA?profiles.known(address):mdkProfiles.entry(address).has("name")||mdkProfiles.known(address);
        boolean foreign=brand==LampBrand.MOSILA?mdkProfiles.entry(address).has("name")||mdkProfiles.known(address):profiles.known(address);
        String foreignMemory=context.getSharedPreferences(brand==LampBrand.MOSILA?"connection_memory_modengke_p58":"connection_memory",Context.MODE_PRIVATE).getString("address","");
        return own||address.equals(rememberedAddress())||!(foreign||address.equals(foreignMemory));
    }
    private boolean isForeignName(String name){
        if(name==null)return false;String upper=name.toUpperCase(Locale.ROOT);
        return brand==LampBrand.MOSILA?upper.contains("JHDD30")||upper.contains("摩灯客")||upper.contains("魔灯客"):upper.contains("MOSILA")||upper.contains("魔斯拉");
    }
    private void refreshName(Device d,ScanRecord record){
        if(d.nameQuality==0){
            String saved=nameCache.getString(d.address,null);
            if(!BleNames.real(saved))saved=brand==LampBrand.MOSILA?profiles.load(d.address).name:mdkProfiles.entry(d.address).optString("name",null);
            if(!BleNames.real(saved)&&d.address.equals(memory.getString("address","")))saved=memory.getString("name",null);
            if(BleNames.real(saved))d.updateName(saved,1);
        }
        // Refresh the system cache on every result; it may be populated after the first advertisement.
        try{String cached=BleNames.clean(d.device.getName());if(cached!=null&&d.nameQuality<3)d.updateName(cached,2);}catch(SecurityException ignored){}
        if(record!=null){
            String full=BleNames.advertised(record.getBytes(),true);
            if(full!=null)d.updateName(full,3);
            else{
                String name=BleNames.clean(record.getDeviceName());if(name==null)name=BleNames.advertised(record.getBytes(),false);
                if(name!=null&&!(d.nameQuality==2&&d.name!=null&&d.name.length()>name.length()))d.updateName(name,2);
            }
        }
        if(BleNames.real(d.name)&&!d.name.equals(nameCache.getString(d.address,null))){
            SharedPreferences.Editor editor=nameCache.edit();
            if(nameCache.getAll().size()>=256&&!nameCache.contains(d.address))editor.remove(nameCache.getAll().keySet().iterator().next());
            editor.putString(d.address,d.name).apply();
        }
    }
    public void connect(Device d) {
        if (closed || !protocolEnabled || d.gatt != null || devices.get(d.address)!=d) return;
        stopScan();
        d.state = "连接中…"; changed();
        try {
            d.gatt = d.device.connectGatt(context, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int status, int state) {
                    main.post(() -> {
                        if (closed || d.gatt != g) return;
                        if (status == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                            refreshName(d,null);d.state = "检查服务…"; changed();
                            try { if (!g.discoverServices()) fail(d, "无法发现服务"); }
                            catch (SecurityException ex) { fail(d, "缺少蓝牙权限"); }
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                            fail(d, status == 0 ? "已断开" : "连接失败 " + status);
                        }
                    });
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
                    main.post(() -> {
                        if (closed || d.gatt != g) return;
                        BluetoothGattService service = g.getService(UUID.fromString(serviceId()));
                        BluetoothGattCharacteristic ch = service == null ? null : service.getCharacteristic(UUID.fromString(writeId()));
                        if (status != 0 || ch == null || (ch.getProperties() & (brand==LampBrand.MOSILA?BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE:BluetoothGattCharacteristic.PROPERTY_WRITE|BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) == 0) {
                            fail(d, "不支持的大灯服务"); return;
                        }
                        if(d.write!=null||d.pendingWrite!=null)return;
                        if(brand==LampBrand.MODENGKE){
                            d.pendingWrite=ch;d.state="协商通信长度…";changed();
                            try{if(!g.requestMtu(256))fail(d,"无法协商通信长度，请重连");}catch(RuntimeException e){fail(d,"无法协商通信长度，请重连");}return;
                        }
                        d.write = ch; d.state = "已连接";refreshName(d,null);
                        profiles.rememberName(d.address,d.label());
                        memory.edit().putString("address",d.address).putString("name",d.label()).apply();
                        if (d.timeout != null) main.removeCallbacks(d.timeout);
                        log(d.label()+" 已连接");describeServices(d);
                        restoreProfile(d);listener.onReady(d);changed();
                    });
                }
                @Override public void onMtuChanged(BluetoothGatt g,int mtu,int status){
                    main.post(()->{
                        if(closed||d.gatt!=g||brand!=LampBrand.MODENGKE||d.pendingWrite==null||d.identifying||d.ready())return;
                        if(status!=0||mtu<MdkProtocol.MAX_FRAME+3){fail(d,"通信长度不足，未发送控制指令");return;}
                        d.mtu=mtu;subscribeMdk(d);
                    });
                }
                @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,int status){
                    probeRead(g,c,c.getValue(),status);
                }
                @Override public void onCharacteristicRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){
                    probeRead(g,c,value,status);
                }
                void probeRead(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value,int status){
                    byte[] copy=value==null?null:value.clone();
                    main.post(()->{if(!closed&&d.gatt==g&&d.probe!=null)d.probe.read(c,copy,status);});
                }
                @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor descriptor,int status){
                    main.post(()->{
                        if(closed||d.gatt!=g)return;
                        if(brand==LampBrand.MODENGKE&&d.pendingWrite!=null&&!d.identifying&&descriptor.getUuid().equals(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))){
                            if(status!=0){fail(d,"通知订阅失败 "+status);return;}identifyMdk(d);return;
                        }
                        if(d.probe!=null)d.probe.descriptor(descriptor,status);
                    });
                }
                @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){received(g,c,c.getValue());}
                @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){received(g,c,value);}
                void received(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){
                    byte[] copy=value==null?null:value.clone();
                    main.post(()->{if(!closed&&d.gatt==g){log(d.label()+" 收到通知 "+c.getUuid()+" data="+GattProbe.data(copy));if(brand==LampBrand.MODENGKE&&c.getUuid().equals(UUID.fromString(MdkProtocol.NOTIFY)))receiveMdk(d,copy);}});
                }
                @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
                    main.post(() -> {
                        if(closed||d.gatt!=g)return;
                        if(brand==LampBrand.MODENGKE&&d.identifying&&ch==d.pendingWrite){
                            if(status!=0){fail(d,"型号查询写入失败 "+status);return;}
                            d.queryWritten=true;finishMdkIdentity(d);return;
                        }
                        if(ch != d.write || !d.busy)return;
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            abortWrites(d,"写入失败 "+status);return;
                        }
                        boolean restoringWrite=d.writingRestore;
                        d.busy=false;d.writeId++;
                        if(restoringWrite){
                            d.restored++;log(d.label()+" ["+d.address+"] 恢复已提交 "+d.restored+"/"+d.restoreTotal);
                            if(d.restoreQueue.isEmpty()){
                                d.restoring=false;log(d.label()+" 参数恢复指令已全部提交（无设备执行确认）");changed();
                            }
                        }
                        d.nextWriteAt=SystemClock.uptimeMillis()+(brand==LampBrand.MODENGKE?500:restoringWrite?RESTORE_GAP_MS:CONTROL_GAP_MS);
                        scheduleDrain(d);

                    });
                }
            }, BluetoothDevice.TRANSPORT_LE);
            if (d.gatt == null) { fail(d, "连接未建立"); return; }
            BluetoothGatt expected = d.gatt;
            d.timeout = () -> { if (d.gatt == expected && !d.ready()) fail(d, "连接超时，请检查大灯电源"); };
            main.postDelayed(d.timeout, 12000);
        } catch (SecurityException | IllegalArgumentException ex) { fail(d, "连接失败，请检查权限"); }
    }
    private void subscribeMdk(Device d){
        BluetoothGattCharacteristic c=d.gatt.getService(UUID.fromString(MdkProtocol.SERVICE)).getCharacteristic(UUID.fromString(MdkProtocol.NOTIFY));
        BluetoothGattDescriptor cccd=c==null?null:c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if(c==null||(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_NOTIFY)==0||cccd==null){fail(d,"设备缺少型号核对通知通道");return;}
        try{
            if(!d.gatt.setCharacteristicNotification(c,true)){fail(d,"无法开启型号通知");return;}
            boolean ok;if(Build.VERSION.SDK_INT>=33)ok=d.gatt.writeDescriptor(cccd,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)==0;else{cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);ok=d.gatt.writeDescriptor(cccd);}
            if(!ok)fail(d,"无法订阅型号通知");
        }catch(RuntimeException e){fail(d,"无法订阅型号通知");}
    }
    private int writeType(BluetoothGattCharacteristic c){return brand==LampBrand.MOSILA||(c.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)==0?BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE:BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;}
    private void identifyMdk(Device d){
        d.identifying=true;d.state="核对大灯型号…";changed();byte[] packet=MdkProtocol.query();
        try{
            int result;if(Build.VERSION.SDK_INT>=33)result=d.gatt.writeCharacteristic(d.pendingWrite,packet,writeType(d.pendingWrite));else{d.pendingWrite.setWriteType(writeType(d.pendingWrite));d.pendingWrite.setValue(packet);result=d.gatt.writeCharacteristic(d.pendingWrite)?0:-1;}
            if(result!=0){fail(d,"型号查询未接受 "+result);return;}
            log(d.label()+" 查询产品型号（A9），尚未发送灯光控制指令");
            BluetoothGatt expected=d.gatt;main.postDelayed(()->{if(d.gatt==expected&&!d.ready())fail(d,"未收到有效型号回复，未下发参数");},5000);
        }catch(RuntimeException e){fail(d,"型号查询失败");}
    }
    private void receiveMdk(Device d,byte[] data){
        for(byte[] p:d.decoder.accept(data)){
            MdkProtocol.Firmware f=MdkProtocol.firmware(p);
            if(f!=null&&d.identifying&&!d.ready()){
                if(f.product!=MdkProtocol.PRODUCT){fail(d,"型号不匹配：产品 ID "+f.product+"，仅支持九号 N/F/M 大灯 3.0（58）");return;}
                d.verifiedFirmware=f;finishMdkIdentity(d);
            }else if(MdkProtocol.u(p,6)==0xee&&p.length==13){log(d.label()+" 设备回复指令 "+String.format(Locale.ROOT,"%02X",MdkProtocol.u(p,7))+"："+(p[8]==1?"操作成功":"操作失败 "+MdkProtocol.u(p,8)));}
        }
    }
    private void finishMdkIdentity(Device d){
        if(!d.identifying||!d.queryWritten||d.verifiedFirmware==null||d.pendingWrite==null)return;
        MdkProtocol.Firmware f=d.verifiedFirmware;
        d.firmware=f.version;d.write=d.pendingWrite;d.pendingWrite=null;d.identifying=false;d.state="已连接 · 固件 "+f.version;
        d.nextWriteAt=SystemClock.uptimeMillis()+CONNECT_SETTLE_MS;
        if(d.timeout!=null)main.removeCallbacks(d.timeout);
        refreshName(d,null);mdkProfiles.remember(d.address,d.label());memory.edit().putString("address",d.address).putString("name",d.label()).apply();
        log(d.label()+" 型号已确认：九号 N/F/M 大灯 3.0，固件 "+f.version);restoreProfile(d);listener.onReady(d);changed();
    }
    public DeviceProfiles.Profile profile(Device d){return profiles.load(d.address);}
    private void restoreProfile(Device d){
        if(!protocolEnabled)return;
        List<byte[]> packets=brand==LampBrand.MOSILA?profile(d).restorePackets():mdkProfiles.load(d.address).restore();
        if(packets.isEmpty()){log(d.label()+" 没有该设备的参数记录，不自动下发");return;}
        d.restoreQueue.addAll(packets);d.restoreTotal=packets.size();d.restored=0;d.restoring=true;
        d.nextWriteAt=SystemClock.uptimeMillis()+CONNECT_SETTLE_MS;
        log(d.label()+" ["+d.address+"] 恢复该设备的 "+packets.size()+" 条设置，逐条等待回调，间隔至少 "+(brand==LampBrand.MODENGKE?500:RESTORE_GAP_MS)+" ms");
        scheduleDrain(d);
    }
    private void scheduleDrain(Device d){
        if(d.drainTask==null)d.drainTask=()->drain(d);
        main.removeCallbacks(d.drainTask);
        if(d.ready()&&(!d.queue.isEmpty()||!d.restoreQueue.isEmpty()))main.postDelayed(d.drainTask,Math.max(0,d.nextWriteAt-SystemClock.uptimeMillis()));
    }
    private void abortWrites(Device d,String reason){
        boolean restore=d.restoring;d.queue.clear();d.restoreQueue.clear();d.busy=false;d.writingRestore=false;d.restoring=false;d.writeId++;
        if(d.drainTask!=null)main.removeCallbacks(d.drainTask);
        log(d.label()+"："+reason+(restore?"；恢复中断，已保存参数仍保留":""));changed();
    }
    private void describeServices(Device d){
        int count=0;
        for(BluetoothGattService service:d.gatt.getServices()){
            log("服务 "+service.getUuid());
            for(BluetoothGattCharacteristic c:service.getCharacteristics()){
                log("特征 "+c.getUuid()+" "+GattProbe.properties(c.getProperties()));
                if(++count>=48){log("更多特征已省略");return;}
            }
        }
    }
    public boolean isProbing(){for(Device d:devices.values())if(d.probe!=null)return true;return false;}
    public void probeState(Device d){
        if(brand!=LampBrand.MOSILA||closed||!protocolEnabled||!d.ready()){log("请先连接大灯再进行回读探测");return;}
        if(d.probe!=null||d.busy||d.restoring||!d.queue.isEmpty()){log("设备正在通信，请稍后再试");return;}
        log(d.label()+" 开始回读探测：只读取及订阅，不发送灯光控制指令");
        d.probe=new GattProbe(d.gatt,main,new GattProbe.Events(){
            public void log(String message){BleController.this.log(d.label()+" "+message);}
            public void done(boolean timeout){d.probe=null;if(timeout)fail(d,"回读探测超时，请重新连接");else{changed();drain(d);}}
        });
        changed();d.probe.start();
    }
    public void disconnect(Device d) { fail(d, "已断开"); }
    private void fail(Device d, String reason) {
        if(d.probe!=null){d.probe.cancel();d.probe=null;}
        if (d.timeout != null) main.removeCallbacks(d.timeout);
        if(d.drainTask!=null)main.removeCallbacks(d.drainTask);
        d.restoreQueue.clear();d.restoring=false;d.writingRestore=false;d.nextWriteAt=0;
        BluetoothGatt g = d.gatt;
        d.gatt = null; d.write = null;d.pendingWrite=null;d.identifying=false;d.queryWritten=false;d.verifiedFirmware=null;d.mtu=23;d.decoder.pending=new byte[0]; d.busy = false; d.writeId++; d.queue.clear(); d.state = reason;
        if (g != null) {
            try { g.disconnect(); } catch (RuntimeException ignored) {}
            try { g.close(); } catch (RuntimeException ignored) {}
        }
        log(d.label() + "：" + reason); changed();
    }
    public boolean send(byte[] data, boolean coalesce, boolean record) {
        if(!protocolEnabled||closed||settingKey(data)==null)return false;
        if(isProbing()){log("正在读取设备信息，请稍后再控制灯光");return false;}
        int targets = 0;
        for (Device d : devices.values()) {
            if (!d.ready() || !d.selected) continue;
            String settingKey=settingKey(data);
            if(brand==LampBrand.MODENGKE&&(settingKey==null||data.length>d.mtu-3)){log("参数校验失败，未发送");continue;}
            boolean persistent=settingKey!=null&&!settingKey.equals("lastMotion");
            if(coalesce||persistent){
                Iterator<byte[]> it=d.queue.iterator();
                while(it.hasNext()){
                    byte[] pending=it.next();
                    if(settingKey!=null&&settingKey.equals(settingKey(pending)))it.remove();
                    else if(settingKey==null&&pending.length>=4&&data.length>=4&&pending[3]==data[3])it.remove();
                }
            }
            if (d.queue.size() >= 24) { log("指令队列繁忙，请稍后重试"); continue; }
            if(brand==LampBrand.MOSILA)profiles.record(d.address,data);else mdkProfiles.record(d.address,data);
            d.queue.add(data.clone()); targets++; drain(d);
        }
        if (targets > 0 && record) log("发送至 " + targets + " 台：" + Protocol.hex(data));
        return targets > 0;
    }
    private void drain(Device d) {
        if (closed || !protocolEnabled || d.probe!=null || d.busy || !d.ready() || (d.queue.isEmpty()&&d.restoreQueue.isEmpty())) return;
        if(SystemClock.uptimeMillis()<d.nextWriteAt){scheduleDrain(d);return;}
        d.writingRestore=!d.restoreQueue.isEmpty();
        byte[] next = d.writingRestore?d.restoreQueue.remove():d.queue.remove();
        d.busy = true;
        long token = ++d.writeId;
        try {
            int result;
            if (Build.VERSION.SDK_INT >= 33) result = d.gatt.writeCharacteristic(d.write, next, writeType(d.write));
            else {
                d.write.setWriteType(writeType(d.write));
                d.write.setValue(next);
                result = d.gatt.writeCharacteristic(d.write) ? 0 : -1;
            }
            if (result != 0) { abortWrites(d,"写入未接受 "+result);return; }
            if(d.writingRestore)log(d.label()+" ["+d.address+"] 恢复报文："+Protocol.hex(next));
            // Do not blindly resend after a missing callback: delivery is ambiguous.
            main.postDelayed(() -> {
                if (d.busy && d.writeId == token) fail(d, "写入超时，请重新连接");
            }, 2500);
        } catch (RuntimeException ex) { fail(d, "写入失败，请重新连接"); }
    }
    public void cancelContinuous() {
        if(brand!=LampBrand.MOSILA)return;
        for (Device d : devices.values()) {
            Iterator<byte[]> it = d.queue.iterator();
            while (it.hasNext()) { byte[] b = it.next(); if (b.length >= 4 && (b[3] & 255) == 7) it.remove(); }
        }
    }
    public void close() {
        stopScan(); closed = true;
        for (Device d : devices.values()) if (d.gatt != null) disconnect(d);
        main.removeCallbacksAndMessages(null);
    }
    private void changed() { if (!closed) listener.onChanged(); }
    private void log(String message) { if (!closed) listener.onLog(message); }
}
