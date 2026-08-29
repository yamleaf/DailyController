# Room
-keep class * extends androidx.room.RoomDatabase
-keep class com.yample.daily.controller.DeviceRecord { *; }
-keep class com.yample.daily.controller.ServerlessBackend { *; }

# 备份/恢复信封与偏好项（Gson 反射序列化/反序列化依赖字段名）
-keep class com.yample.daily.controller.BackupManager$BackupEnvelope { *; }
-keep class com.yample.daily.controller.BackupManager$PrefEntry { *; }

# 最近指令缓存（Gson 反射序列化到 SharedPreferences：字段名混淆会让旧缓存反序列化出
# null label/result，快捷操作后渲染「最近指令」时 NPE 闪退，故 keep 字段名）
-keep class com.yample.daily.controller.RecentCommand { *; }

# 协议类防混淆（Gson 序列化依赖字段名，类内另有 @Keep，此规则为双保险）
-keep class com.yample.mqttprotocol.MqttPacket { *; }
-keep class com.yample.mqttprotocol.PacketValue { *; }
-keep class com.yample.mqttprotocol.PacketValue$* { *; }
# 绑定二维码载荷（Gson 反序列化依赖字段名）
-keep class com.yample.mqttprotocol.BindingPayload { *; }
# 配对握手派生会话密钥
-keep class com.yample.mqttprotocol.Hkdf { *; }

# Paho MQTT client：内部通过反射（Class.forName）加载 SimpleLogger 等日志类，
# 混淆后会抛 MissingResourceException「Error locating the logging class」导致崩溃
-keep class org.eclipse.paho.client.mqttv3.** { *; }
-dontwarn org.eclipse.paho.client.mqttv3.**
