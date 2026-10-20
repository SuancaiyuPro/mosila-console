# 大灯状态回读探测

原版基准：MOSILA 1.0.6，包名 com.kimoji.mosila，Dart 3.5.3；静态分析，不代表硬件验证。

已确认原版 XBleManager.sendMessage（0x40bad8）选取 EEA0 服务 / EE01 特征，并于 0x40be90 调用 FlutterReactiveBle.writeCharacteristicWithoutResponse。已恢复的 mohuanled 应用代码目录中没有找到 readCharacteristic 或 subscribeToCharacteristic 的调用，也未发现灯效状态解析路径。XBleManager.syncTimestamp（0x499bec）构造时间与时区并调用 sendMessage，是发送时间，不是回读模式。

这只能说明目前没有从原版恢复出回读实现，不能证明固件绝对不支持查询。不能把控制报文或读出的任意字节直接当作状态回包。

0.1.6 提供现场探测：

1. 连接时枚举并记录服务、特征 UUID 和 READ / NOTIFY / INDICATE / WRITE 属性。
2. 用户点击“检测状态回读（实验）”后，仅对 EEA0 服务声明支持的特征进行读取，以及通过标准 2902 CCCD 开启通知或指示。不猜测私有查询指令。
3. 操作串行执行，等待回调后再进行下一步；探测期间暂停接受灯光控制。记录读取返回码、原始十六进制数据和收到的通知。
4. 每步最多等待 5 秒；超时后关闭本次 GATT，避免未完成操作与后续指令混用。已保存的连接记忆不清除，可重新连接。
5. 不自动覆盖本地颜色、灯效或亮度。用户提供记录后，再核对数据含义和能否代表当前运行模式。

现场流程：先记住实物当前灯效，连接后探测，复制完整记录。若有原始返回，再切换到一个容易识别的已知灯效，探测一次，对比记录。写特征的缓存值和控制命令回显都不能单独证明是实物当前状态；多台设备需要分别核对。
